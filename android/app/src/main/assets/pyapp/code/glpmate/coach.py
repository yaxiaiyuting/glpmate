"""AI 教练：任意 OpenAI 兼容接口的客户端 + 上下文拼装 + 安全护栏。

三件事分得很清楚：
  1. `ChatClient` 只管怎么把一次请求发出去（任何 OpenAI 兼容端点都能用）；
  2. `build_context`/`context_text` 只管把本机数据库里的记录变成给模型看的事实；
  3. `Coach` 把两者接起来，并在前面加一道"不给用药建议"的硬护栏。

护栏是代码，不是提示词：命中调药意图时直接返回固定话术，压根不发给模型。
"""

from __future__ import annotations

import base64
import json
import logging
import re
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from typing import Any, Optional, Sequence

from . import config as cfg
from .models import Record, day_index, fmt_dt, treatment_start

log = logging.getLogger("glpmate.coach")

MAX_IMAGE_BYTES = 6 * 1024 * 1024
VISION_EDGE = 1024


# --------------------------------------------------------------------- HTTP
class AIError(RuntimeError):
    """调用模型接口失败（网络、鉴权、限流、返回体异常都归到这里）。"""

    def __init__(self, message: str, status: Optional[int] = None, body: str = ""):
        super().__init__(message)
        self.status = status
        self.body = body[:2000]


def normalize_endpoint(base_url: str, path: str = "/chat/completions") -> str:
    """把用户填的各种 base_url 归一成完整 URL。

    接受：https://api.deepseek.com / .../v1 / 已经带 /chat/completions 的完整地址。
    """
    url = (base_url or cfg.DEFAULT_AI_BASE_URL).strip().rstrip("/")
    if not url:
        url = cfg.DEFAULT_AI_BASE_URL
    if url.endswith(path):
        return url
    if re.search(r"/chat/completions/?$", url):
        return re.sub(r"/chat/completions/?$", path, url)
    if not re.search(r"/v\d+$", url):
        url = f"{url}/v1"
    return f"{url}{path}"


@dataclass
class ChatClient:
    base_url: str
    api_key: str
    model: str
    temperature: float = 0.6
    timeout: float = 120.0
    extra_headers: dict[str, str] = field(default_factory=dict)

    @property
    def endpoint(self) -> str:
        return normalize_endpoint(self.base_url)

    def chat(self, messages: Sequence[dict[str, Any]], *, model: Optional[str] = None,
             temperature: Optional[float] = None, json_mode: bool = False,
             max_tokens: Optional[int] = None) -> dict[str, Any]:
        model = model or self.model
        if not model:
            raise AIError("没有配置模型名（设置 → 模型与额度）")
        payload: dict[str, Any] = {
            "model": model,
            "messages": list(messages),
            "temperature": self.temperature if temperature is None else temperature,
            "stream": False,
        }
        if max_tokens:
            payload["max_tokens"] = max_tokens
        if json_mode and "reasoner" not in model:
            # 推理模型对 response_format 支持不一，直接不加，靠提示词约束
            payload["response_format"] = {"type": "json_object"}

        data = self._post(payload, headers=None)
        text = extract_text(data)
        if not text and json_mode:
            # JSON 模式偶发返回空内容（官方文档也这么提醒），退一步不带 response_format 重试
            log.warning("JSON 模式返回空内容，改为不带 response_format 重试一次")
            payload.pop("response_format", None)
            data = self._post(payload, headers=None)
            text = extract_text(data)
        return {
            "text": text,
            "model": (data.get("model") if isinstance(data, dict) else None) or model,
            "latency_ms": int(data.pop("_latency_ms", 0)),
            "usage": (data.get("usage") if isinstance(data, dict) else None) or {},
            "raw": data,
        }

    def _post(self, payload: dict[str, Any], headers: Optional[dict[str, str]] = None) -> dict[str, Any]:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req_headers = {
            "Content-Type": "application/json",
            "Accept": "application/json",
            **self.extra_headers,
            **(headers or {}),
        }
        if self.api_key:
            req_headers["Authorization"] = f"Bearer {self.api_key}"
        req = urllib.request.Request(self.endpoint, data=body, headers=req_headers, method="POST")

        started = time.perf_counter()
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                raw = resp.read().decode("utf-8", "replace")
                status = resp.status
        except urllib.error.HTTPError as exc:                       # 4xx/5xx
            detail = exc.read().decode("utf-8", "replace")
            raise AIError(_explain_http(exc.code, detail), exc.code, detail) from exc
        except urllib.error.URLError as exc:                        # 连不上/超时
            raise AIError(f"连接模型接口失败：{exc.reason}（检查 base_url 与网络）") from exc
        except TimeoutError as exc:
            raise AIError(f"模型接口超时（>{self.timeout:.0f}s）") from exc

        try:
            data = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise AIError(f"模型返回的不是 JSON（HTTP {status}）：{raw[:200]}", status, raw) from exc
        if isinstance(data, dict) and data.get("error"):
            raise AIError(_explain_http(status, raw), status, raw)
        if isinstance(data, dict):
            # 延迟只在本进程内传递，不污染返回结构
            data["_latency_ms"] = int((time.perf_counter() - started) * 1000)
        return data

    def ping(self) -> dict[str, Any]:
        result = self.chat([{"role": "user", "content": "ping"}], max_tokens=4, temperature=0.0)
        return {"ok": True, "model": result["model"], "latency_ms": result["latency_ms"],
                "usage": result["usage"]}


def extract_text(data: dict[str, Any]) -> str:
    """兼容 OpenAI 风格与常见变体。"""
    choices = data.get("choices") or []
    if not choices:
        raise AIError(f"模型没有返回 choices：{json.dumps(data, ensure_ascii=False)[:200]}")
    message = choices[0].get("message") or {}
    content = message.get("content")
    if isinstance(content, list):   # 有些实现返回分段数组
        content = "".join(part.get("text", "") for part in content if isinstance(part, dict))
    if not content:
        content = message.get("reasoning_content") or choices[0].get("text") or ""
    return str(content).strip()


def _explain_http(status: int, detail: str) -> str:
    hint = {
        401: "API key 无效或没带 Authorization 头",
        402: "账户余额不足",
        403: "没有该模型的权限",
        404: "路径或模型名不对（base_url 一般填 https://api.deepseek.com 即可）",
        422: "请求体不被接受（可能是模型不支持图片或该参数）",
        429: "触发限流或额度用尽",
        500: "服务端错误",
        503: "服务端繁忙",
    }.get(status, "")
    return f"模型接口返回 HTTP {status}：{hint} {detail[:300]}".strip()


# --------------------------------------------------------------------- 安全护栏
REDIRECT_TEXT = (
    "这个我不能替你决定：剂量调整、停药、换药、补打漏掉的针，都必须由你的医生根据你的"
    "具体情况判断，我这边只是一个记录和整理工具。\n\n"
    "我可以帮你把这段时间的情况整理成一段话，方便你原样发给医生——比如最近的注射日期与剂量、"
    "体重变化、恶心/便秘等不适出现的频率和严重程度、每天的进食和饮水量。需要的话说一声，"
    "我现在就生成。\n\n"
    "如果出现持续呕吐、剧烈腹痛、完全无法进食饮水、明显脱水或视力变化，请尽快就医，不要等下次复诊。"
)

_MED_WORDS = r"(?:剂量|用量|打了|补打|补针|加量|减量|加针|减针|停药|加药|换药|改药|提前打|推迟打|提前一天打|漏打|多吃一片|司美|替尔泊|泽拉|穆峰达|诺和|glp-?1|wegovy|ozempic|mounjaro|zepbound|semaglutide|tirzepatide)"
_ACTION_WORDS = r"(?:我(?:该|应该|要不要|能不能|可以|是否|想)|帮我|请问|建议|怎么办|要不要|能不能|可不可以|是否|多少|加到|减到|调到|改成|换成|加大|减少|提高|降低)"
# 明确指向"调整用药"的动作，单独就能判定，不要求同句出现药名
_MED_ACTION = r"(?:提前一天打|推迟一天打|提前打|推迟打|补打|补一针|补针|加量|减量|停药|换药|改药|加大剂量|减少剂量|漏打)"
_GUARD_RE = re.compile(
    rf"(?:{_ACTION_WORDS}[^。！？\n]{{0,20}}{_MED_WORDS})"
    rf"|(?:{_MED_WORDS}[^。！？\n]{{0,20}}{_ACTION_WORDS})"
    rf"|(?:{_MED_ACTION})",
    re.IGNORECASE,
)

# 纯科普问法：允许模型回答机制/原理类问题，避免把正常提问误拦
_EDU_RE = re.compile(
    r"(?:作用机制|是什么|什么是|为什么|原理|机制|副作用|不良反应|注意事项|说明书|"
    r"半衰期|药代|怎么起效|如何起效|科普|一般|通常|大概多久|多久起效)"
)


EMERGENCY_RE = re.compile(
    r"(?:持续呕吐|不停呕吐|剧烈腹痛|腹痛难忍|无法进食|喝水都吐|意识模糊|视力(?:模糊|下降)|"
    r"严重脱水|心跳(?:很快|加速)|呼吸困难|黄疸|右上腹(?:剧痛|疼痛))"
)


def safety_check(text: str) -> Optional[dict[str, str]]:
    """返回护栏命中的类型；None 表示可以正常发给模型。"""
    if not text:
        return None
    if EMERGENCY_RE.search(text):
        return {"kind": "emergency"}
    if _GUARD_RE.search(text):
        # 科普式问法（"作用机制是什么""半衰期多久"）放行，只有真的要动剂量才拦
        if _EDU_RE.search(text) and not re.search(_MED_ACTION, text):
            return None
        return {"kind": "medical_advice"}
    return None


EMERGENCY_TEXT = (
    "你描述的情况需要当面处理，别在这里等 AI 回复：请尽快联系你的医生或直接去急诊。\n\n"
    "严重且持续的呕吐、剧烈腹痛、完全无法进食或饮水、明显脱水、视力变化，这些都不是"
    "「再观察一天」的情况。去的时候把用药记录带上——我可以立刻给你生成一份可以打印/截图"
    "的用药与症状小结，说一声就行。"
)


# --------------------------------------------------------------------- 上下文
def build_context(store, today: Optional[date] = None, days: int = 14) -> dict[str, Any]:
    """把库里的记录整理成模型能读的事实包（不含任何原始隐私字段以外的推断）。"""
    today = today or date.today()
    profile = store.profile()
    targets = store.targets()
    doses = store.all_dose_records()
    t_start = treatment_start(today, profile, doses)
    try:
        profile["day_index"] = day_index(t_start, today)
    except Exception:  # pragma: no cover - 防御性
        profile["day_index"] = 1

    since = today - timedelta(days=days - 1)
    records = store.records(since, today, limit=3000, order="asc")

    by_day: dict[str, dict[str, Any]] = {}
    for r in records:
        key = r.day.isoformat()
        bucket = by_day.setdefault(key, {"meals": [], "water_ml": 0.0, "weight": None,
                                        "doses": [], "side_effects": [], "training_min": 0.0,
                                        "notes": []})
        if r.type == "food":
            bucket["meals"].append({
                "slot": r.meta.get("slot") or "meal",
                "time": r.at.strftime("%H:%M"),
                "desc": r.meta.get("desc") or "",
                "kcal": r.meta_num("kcal"),
                "protein_g": r.meta_num("protein_g"),
                "carb_g": r.meta_num("carb_g"),
                "fat_g": r.meta_num("fat_g"),
                "fiber_g": r.meta_num("fiber_g"),
            })
        elif r.type == "water":
            bucket["water_ml"] += r.meta_num("amount_ml")
        elif r.type == "weight":
            bucket["weight"] = r.value
        elif r.type == "dose":
            bucket["doses"].append({"time": r.at.strftime("%H:%M"), "mg": r.meta_num("dose_mg"),
                                    "site": r.meta.get("site") or "", "skipped": bool(r.meta.get("skipped"))})
        elif r.type == "side_effect":
            bucket["side_effects"].append({"label": r.meta.get("label") or "不适",
                                           "severity": r.meta.get("severity") or 1,
                                           "time": r.at.strftime("%H:%M")})
        elif r.type == "training":
            bucket["training_min"] += r.value or 0.0
        elif r.type in ("note", "symptom"):
            bucket["notes"].append({"time": r.at.strftime("%H:%M"), "text": r.meta.get("desc") or ""})

    weights = store.weight_series(today, days=90)
    latest_weight = weights[-1]["v"] if weights else None
    first_weight = weights[0]["v"] if weights else None
    # 取"约 30 条前"的体重做对比；记录不足时退回最早一条，绝不越界
    ref = weights[max(0, len(weights) - 30)]["v"] if weights else None
    if ref is None and weights:
        ref = weights[0]["v"]
    return {
        "profile": profile,
        "targets": targets,
        "today": today.isoformat(),
        "next_dose": store.next_dose(today),
        "level": store.level_summary(today),
        "weight": {"latest": latest_weight, "first": first_weight,
                   "delta_30d": round(latest_weight - ref, 2) if (latest_weight is not None and ref is not None) else None,
                   "goal": profile.get("target_weight_kg")},
        "days": by_day,
        "dose_history": [{"date": r.at.date().isoformat(), "mg": r.meta_num("dose_mg"),
                          "skipped": bool(r.meta.get("skipped"))} for r in doses[-12:]],
    }


def context_text(ctx: dict[str, Any]) -> str:
    """把事实包压成紧凑的文本（省 token，且模型更容易引用准确数字）。"""
    p = ctx["profile"]
    t = ctx["targets"]
    lines: list[str] = []
    lines.append(f"今天：{ctx['today']}（治疗第 {p.get('day_index', 1)} 天）")
    lines.append(
        f"用药：{p.get('med_name')} {p.get('dose_mg')} mg，每 {p.get('dose_interval_days')} 天一次，"
        f"计划时间 {p.get('dose_time')}"
    )
    nd = ctx.get("next_dose") or {}
    if nd:
        lines.append(f"下次剂量：{nd.get('date')} {nd.get('time')}（{nd.get('weekday_cn')}），"
                     f"距今约 {nd.get('remaining_days')} 天；剂量 {nd.get('dose_mg')} mg")
    lv = ctx.get("level") or {}
    if lv:
        lines.append(
            # 必须用 pct_max（稳态峰值 = 100%），和界面上那个大数字同一口径。
            # 曾经用的是 pct_peak（稳态谷值 = 100%），于是教练看到的百分比是
            # 界面的 2.4 倍——模型会拿"130%"去讨论一个用户屏幕上写着"55%"的数，
            # 两边对不上，回答自然驴唇不对马嘴。
            f"体内水平估算：当前约为稳态峰值的 {lv.get('pct_max')}%（{lv.get('current_mg')} mg 等效），"
            f"趋势 {lv.get('trend')}；该值由 {lv.get('half_life_hours')} 小时半衰期的一室模型推算，仅供观察趋势"
        )
    w = ctx.get("weight") or {}
    if w.get("latest"):
        lines.append(f"体重：最新 {w['latest']} kg，起始 {w.get('first')} kg，目标 {w.get('goal')} kg")
    hist = ctx.get("dose_history") or []
    if hist:
        joined = "、".join(f"{h['date']} {h['mg']:g}mg" + ("（跳过）" if h.get("skipped") else "") for h in hist[-8:])
        lines.append(f"最近注射记录：{joined}")
    lines.append(
        f"每日目标：热量 {t.get('calories'):g} kcal，蛋白 {t.get('protein_g'):g} g，"
        f"碳水 {t.get('carbs_g'):g} g，脂肪 {t.get('fat_g'):g} g，纤维 {t.get('fiber_g'):g} g，"
        f"饮水 {t.get('water_ml') / 1000:g} 升"
    )
    lines.append("")
    lines.append("逐日记录（新 → 旧）：")
    for day in sorted(ctx["days"], reverse=True):
        b = ctx["days"][day]
        parts = []
        if b["doses"]:
            parts.append("注射 " + "、".join(f"{d['time']} {d['mg']:g}mg" for d in b["doses"]))
        if b["weight"] is not None:
            parts.append(f"体重 {b['weight']:g}kg")
        for m in b["meals"]:
            macro = f"{m['kcal']:.0f}kcal/蛋白{m['protein_g']:.0f}g/碳水{m['carb_g']:.0f}g/脂肪{m['fat_g']:.0f}g"
            parts.append(f"{m['slot']}({m['time']}) {m['desc']} [{macro}]")
        if b["water_ml"]:
            parts.append(f"饮水 {b['water_ml'] / 1000:.1f}L")
        if b["training_min"]:
            parts.append(f"训练 {b['training_min']:.0f}分钟")
        for s in b["side_effects"]:
            parts.append(f"不适：{s['label']} {s['severity']}/10")
        for n in b["notes"]:
            parts.append(f"备注：{n['text']}")
        if b["meals"]:
            kcal = sum(m["kcal"] for m in b["meals"])
            prot = sum(m["protein_g"] for m in b["meals"])
            parts.append(f"当日合计 {kcal:.0f}kcal / 蛋白 {prot:.0f}g")
        lines.append(f"- {day}：" + ("；".join(parts) if parts else "无记录"))
    return "\n".join(lines)


SYSTEM_PROMPT = """你是 GLP Mate Lite 里内置的记录助手，用户正在用 GLP-1 类药物（如司美格鲁肽）做体重管理。

你的职责：
1. 帮用户记录和整理：把口语化的描述转成结构化记录，例如「中午吃了红烧鲫鱼配米饭」→ 估算热量与三大营养素并生成待确认卡片。
2. 基于下面的真实数据做汇总、对比、趋势解读：摄入了多少、蛋白质够不够、饮水够不够、体重趋势、不适出现的规律。
3. 做常识性科普（例如蛋白质摄入、膳食纤维、补水、缓释不适的一般生活方式建议），并明确说明这不是医疗建议。

硬性边界（不可违反）：
- 绝不建议调整用药：不判断剂量高低、不建议加减量、不建议提前/推迟/补打、不建议停药或换药。遇到这类问题，明确说明必须由医生决定，并主动提出帮用户整理一份给医生看的小结。
- 不诊断疾病，不解读化验单为确诊结论，不推荐处方药或保健品品牌。
- 出现持续呕吐、剧烈腹痛、无法进食饮水、明显脱水、视力变化等警示症状时，直接建议尽快就医或急诊，不做任何居家处理方案。
- 不编造数据。下面没记录的东西就说没记录，需要时主动问一句。

风格：简体中文，直接、简短、具体，先给结论再给一句理由。不要复述整段数据，只引用与问题相关的数字。不要用「作为一个AI」这类套话。

需要用户确认后才能写入记录时，在回复的最后单独给一个卡片块，格式严格为：

```card
{"type":"meal_confirm","payload":{"records":[{"type":"food","at":"YYYY-MM-DDTHH:MM:SS","meta":{"slot":"lunch","desc":"红烧鲫鱼配米饭","kcal":580,"protein_g":36,"carb_g":60,"fat_g":18,"fiber_g":2}}]}}
```

卡片块的规则：type 只能是 meal_confirm / record_confirm；payload.records 里每条都必须有 type、at、meta；不要在卡片块外面重复 JSON；不需要写记录时就不要输出卡片块。

下面是用户的真实数据，回答时以它为准：

---
{context}
---"""


# --------------------------------------------------------------------- 教练
@dataclass
class CoachResult:
    text: str
    card: Optional[dict[str, Any]] = None
    guard: Optional[str] = None
    model: str = ""
    latency_ms: int = 0
    usage: dict[str, Any] = field(default_factory=dict)


class Coach:
    """把 store、ChatClient 和护栏粘起来。"""

    def __init__(self, store):
        self.store = store

    # -- 客户端 ------------------------------------------------------------
    def client(self, vision: bool = False) -> ChatClient:
        conf = self.store.ai_config()
        if not conf.get("api_key"):
            raise AIError("还没填 API key。打开 设置 → 模型与额度，填入你自己的 key（只存在本机数据库里）。")
        if vision:
            # 没单独配视觉端点就沿用主模型：DeepSeek 当前的 deepseek-flash 本身支持图片。
            # 如果主模型不吃图片，请求会返回 422/400，错误信息会原样透出给用户。
            base = conf.get("vision_base_url") or conf.get("base_url") or ""
            model = conf.get("vision_model") or conf.get("model") or ""
            key = conf.get("vision_api_key") or conf.get("api_key") or ""
            if not model:
                raise AIError("没有可用的视觉模型：请先在 设置 → 模型与额度 里填一个模型名。")
            return ChatClient(base, key, model, 0.2, conf["timeout"])
        return ChatClient(conf["base_url"], conf["api_key"], conf["model"],
                          conf["temperature"], conf["timeout"])

    # -- 问答 --------------------------------------------------------------
    def reply(self, text: str, today: Optional[date] = None, history_limit: int = 12) -> CoachResult:
        today = today or date.today()
        text = (text or "").strip()
        if not text:
            raise ValueError("消息不能为空")

        guard = safety_check(text)
        if guard:
            fixed = EMERGENCY_TEXT if guard["kind"] == "emergency" else REDIRECT_TEXT
            return CoachResult(text=fixed, guard=guard["kind"])

        ctx = build_context(self.store, today)
        system = SYSTEM_PROMPT.replace("{context}", context_text(ctx))
        messages: list[dict[str, Any]] = [{"role": "system", "content": system}]
        for msg in self.store.messages(limit=history_limit):
            if msg["role"] in ("user", "assistant") and msg["text"]:
                messages.append({"role": msg["role"], "content": msg["text"]})
        messages.append({"role": "user", "content": text})

        client = self.client()
        result = client.chat(messages, max_tokens=1200)
        clean, card = self._extract_card(result["text"])
        return CoachResult(text=clean, card=card, model=result["model"],
                           latency_ms=result["latency_ms"], usage=result["usage"])

    # -- 图片估算 ----------------------------------------------------------
    def photo_estimate(self, image_bytes: bytes, mime: str, note: str = "",
                       today: Optional[date] = None) -> dict[str, Any]:
        today = today or date.today()
        client = self.client(vision=True)
        b64 = base64.b64encode(image_bytes).decode("ascii")
        now = datetime.now().strftime("%H:%M")
        prompt = (
            f"这是用户的一餐照片。请估算营养，只输出 JSON，不要任何解释文字。\n"
            f"当前时间 {today.isoformat()} {now}。\n"
            + (f"用户补充：{note}\n" if note else "")
            + 'JSON 结构：{"items":[{"name":"菜名","qty":"约 200g","kcal":320,"protein_g":36,'
              '"carb_g":8,"fat_g":18,"fiber_g":1}],"confidence":"low|medium|high","note":"一句话提示"}'
        )
        messages = [{
            "role": "user",
            "content": [
                {"type": "text", "text": prompt},
                {"type": "image_url", "image_url": {"url": f"data:{mime};base64,{b64}"}},
            ],
        }]
        result = client.chat(messages, temperature=0.1, json_mode=True, max_tokens=900)
        data = _parse_json_loose(result["text"])
        estimate = _normalize_estimate(data)
        estimate["model"] = result["model"]
        estimate["latency_ms"] = result["latency_ms"]
        estimate["at"] = datetime.now().replace(microsecond=0).isoformat()
        return estimate

    # -- 卡片 --------------------------------------------------------------
    def _extract_card(self, text: str) -> tuple[str, Optional[dict[str, Any]]]:
        matches = list(re.finditer(r"```card\s*(\{.*?\})\s*```", text, re.DOTALL))
        clip = re.search(r"```(?:card|json)?\s*(\{.*?\})\s*```", text, re.DOTALL)
        card: Optional[dict[str, Any]] = None
        raw = matches[0].group(1) if matches else (clip.group(1) if clip else None)
        if raw:
            data = _parse_json_loose(raw)
            card = self._make_card(data)
            text = text[:matches[0].start()].strip() if matches else \
                text[:clip.start()].strip() + text[clip.end():].strip()  # type: ignore[union-attr]
        return text.strip(), card

    def _make_card(self, data: Any) -> Optional[dict[str, Any]]:
        if not isinstance(data, dict):
            return None
        kind = str(data.get("type") or "").strip()
        payload = data.get("payload")
        if kind not in ("meal_confirm", "record_confirm") or not isinstance(payload, dict):
            return None
        records = payload.get("records")
        if not isinstance(records, list) or not records:
            return None
        clean_records: list[dict[str, Any]] = []
        for rec in records[:6]:
            if not isinstance(rec, dict) or not rec.get("type"):
                continue
            clean_records.append({
                "type": str(rec["type"]),
                "at": str(rec.get("at") or datetime.now().replace(microsecond=0).isoformat()),
                "value": rec.get("value"),
                "unit": str(rec.get("unit") or ""),
                "meta": rec.get("meta") if isinstance(rec.get("meta"), dict) else {},
            })
        if not clean_records:
            return None
        card = self.store.create_card(kind, {
            "records": clean_records,
            "title": str(payload.get("title") or ("这一餐" if kind == "meal_confirm" else "这条记录")),
            "summary": str(payload.get("summary") or _records_summary(clean_records)),
        })
        card["status"] = "pending"
        return card

    def propose_records(self, records: list[dict[str, Any]], kind: str = "record_confirm",
                        title: str = "") -> dict[str, Any]:
        """不经过模型也能造卡片（前端手动添加、照片确认都用得到）。"""
        return self.store.create_card(kind or "record_confirm", {
            "records": records,
            "title": title,
            "summary": _records_summary(records),
        })


def _records_summary(records: list[dict[str, Any]]) -> str:
    out = []
    for rec in records:
        meta = rec.get("meta") or {}
        if rec["type"] == "food":
            out.append(f"{meta.get('desc') or '一餐'} · {float(meta.get('kcal') or 0):.0f} kcal / "
                       f"蛋白 {float(meta.get('protein_g') or 0):.0f} g")
        elif rec["type"] == "weight":
            out.append(f"体重 {float(rec.get('value') or 0):.1f} kg")
        elif rec["type"] == "water":
            out.append(f"饮水 {float(meta.get('amount_ml') or rec.get('value') or 0):.0f} ml")
        elif rec["type"] == "dose":
            out.append(f"注射 {float(meta.get('dose_mg') or 0):g} mg")
        else:
            out.append(str(meta.get("label") or meta.get("desc") or rec["type"]))
    return "；".join(out)


def _parse_json_loose(text: str) -> Any:
    text = (text or "").strip()
    if text.startswith("```"):
        text = re.sub(r"^```[a-zA-Z]*\s*", "", text)
        text = re.sub(r"\s*```$", "", text)
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass
    start, end = text.find("{"), text.rfind("}")
    if start >= 0 and end > start:
        try:
            return json.loads(text[start:end + 1])
        except json.JSONDecodeError:
            return None
    return None


def _normalize_estimate(data: Any) -> dict[str, Any]:
    if not isinstance(data, dict):
        return {"items": [], "total": {}, "confidence": "low",
                "note": "模型没有返回可解析的 JSON，请手动填写。"}
    items_in = data.get("items") if isinstance(data.get("items"), list) else []
    items: list[dict[str, Any]] = []
    for raw in items_in[:12]:
        if not isinstance(raw, dict):
            continue
        item = {
            "name": str(raw.get("name") or raw.get("food") or "食物"),
            "qty": str(raw.get("qty") or raw.get("amount") or ""),
            "kcal": _f(raw.get("kcal") or raw.get("calories")),
            "protein_g": _f(raw.get("protein_g") or raw.get("protein")),
            "carb_g": _f(raw.get("carb_g") or raw.get("carbs")),
            "fat_g": _f(raw.get("fat_g") or raw.get("fat")),
            "fiber_g": _f(raw.get("fiber_g") or raw.get("fiber")),
        }
        items.append(item)
    total = {
        "kcal": round(sum(i["kcal"] for i in items), 1),
        "protein_g": round(sum(i["protein_g"] for i in items), 1),
        "carb_g": round(sum(i["carb_g"] for i in items), 1),
        "fat_g": round(sum(i["fat_g"] for i in items), 1),
        "fiber_g": round(sum(i["fiber_g"] for i in items), 1),
    }
    return {
        "items": items,
        "total": total,
        "confidence": str(data.get("confidence") or "low"),
        "note": str(data.get("note") or "估算值，请核对后再保存。"),
    }


def _f(value: Any) -> float:
    try:
        return round(float(value or 0), 1)
    except (TypeError, ValueError):
        return 0.0


# --------------------------------------------------------------------- 图片
def prepare_image(data: bytes, filename: str = "") -> tuple[bytes, str]:
    """尽量压到 1024px / JPEG，减少上传体积与 token 消耗。Pillow 可选。"""
    mime = _guess_mime(data, filename)
    if len(data) > MAX_IMAGE_BYTES and mime == "image/jpeg":
        pass  # 交给下面的缩放处理
    try:
        from PIL import Image  # type: ignore
        import io
        img = Image.open(io.BytesIO(data))
        img = img.convert("RGB")
        img.thumbnail((VISION_EDGE, VISION_EDGE))
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=85)
        return buf.getvalue(), "image/jpeg"
    except ImportError:
        log.info("未安装 Pillow，图片按原样上传（体积可能较大）")
    except Exception as exc:  # 图片损坏等
        log.warning("图片处理失败，按原样上传：%s", exc)
    if len(data) > MAX_IMAGE_BYTES * 2:
        raise ValueError("图片太大（>12MB），请换一张或先压缩")
    return data, mime


def _guess_mime(data: bytes, filename: str) -> str:
    head = data[:12]
    if head.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if head.startswith(b"\x89PNG"):
        return "image/png"
    if head[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    if head[:3] == b"GIF":
        return "image/gif"
    if head[4:12] in (b"ftypheic", b"ftypheix", b"ftypmif1"):
        return "image/heic"
    lower = filename.lower()
    for ext, guess in ((".png", "image/png"), (".webp", "image/webp"),
                       (".jpg", "image/jpeg"), (".jpeg", "image/jpeg"), (".heic", "image/heic")):
        if lower.endswith(ext):
            return guess
    return "image/jpeg"
