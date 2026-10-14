"""HTTP 层：标准库 ThreadingHTTPServer + 手写路由。

之所以不用框架：这是要长期跑在用户自己机器（或手机 Termux）上的个人工具，
少一个依赖就少一类"装不上"的失败。全部逻辑就在这里，读一遍就能改。

路由表在 ROUTES 里，模式用 {name} 占位，匹配到的值以关键字参数传给处理函数。
处理函数签名统一为 handler(self, req, **params) -> (status, payload)。
"""

from __future__ import annotations

import json
import logging
import mimetypes
import re
import socket
import threading
import traceback
import urllib.parse
from dataclasses import dataclass, field
from datetime import date, datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable, Optional

from . import __version__, config as cfg, drugs, efficacy, hitrack
from .coach import AIError, Coach, normalize_endpoint, prepare_image
from .models import fmt_dt, parse_dt
from .store import Store

log = logging.getLogger("glpmate.server")

MAX_BODY = 24 * 1024 * 1024


@dataclass
class Request:
    method: str
    path: str
    query: dict[str, list[str]]
    body: bytes
    headers: Any
    params: dict[str, str] = field(default_factory=dict)

    def q(self, key: str, default: Optional[str] = None) -> Optional[str]:
        values = self.query.get(key)
        return values[0] if values else default

    def q_int(self, key: str, default: int) -> int:
        try:
            return int(self.q(key) or default)
        except (TypeError, ValueError):
            return default

    def json(self) -> dict[str, Any]:
        if not self.body:
            return {}
        try:
            data = json.loads(self.body.decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            raise BadRequest(f"请求体不是合法 JSON：{exc}") from exc
        if not isinstance(data, dict):
            raise BadRequest("请求体必须是 JSON 对象")
        return data

    def form_file(self, name: str = "file") -> tuple[bytes, str, str]:
        """极简 multipart/form-data 解析，只取第一个文件字段。"""
        ctype = str(self.headers.get("Content-Type") or "")
        if "multipart/form-data" not in ctype:
            raise BadRequest("请以 multipart/form-data 上传图片")
        match = re.search(r"boundary=([^;]+)", ctype)
        if not match:
            raise BadRequest("multipart 缺少 boundary")
        boundary = match.group(1).strip().strip('"').encode()
        delimiter = b"--" + boundary
        for part in self.body.split(delimiter):
            if b"\r\n\r\n" not in part:
                continue
            head, _, content = part.partition(b"\r\n\r\n")
            head_text = head.decode("utf-8", "replace")
            if f'name="{name}"' not in head_text:
                continue
            filename = ""
            fname_match = re.search(r'filename="([^"]*)"', head_text)
            if fname_match:
                filename = fname_match.group(1)
            content = content.rstrip(b"\r\n-")
            if content:
                return content, filename, head_text
        raise BadRequest("没有在请求里找到图片字段")


class BadRequest(Exception):
    """400"""


class NotFound(Exception):
    """404"""


class Unauthorized(Exception):
    """401"""


ROUTES: list[tuple[str, str, Callable[..., Any]]] = []


def route(method: str, pattern: str):
    def decorator(func: Callable[..., Any]):
        ROUTES.append((method.upper(), pattern, func))
        return func
    return decorator


def _match(pattern: str, path: str) -> Optional[dict[str, str]]:
    if "{" not in pattern:
        return {} if pattern == path else None
    names: list[str] = []

    def repl(m: re.Match[str]) -> str:
        names.append(m.group(1))
        return r"([^/]+)"

    regex = "^" + re.sub(r"\{(\w+)\}", repl, pattern) + "$"
    found = re.match(regex, path)
    if not found:
        return None
    return dict(zip(names, (urllib.parse.unquote(v) for v in found.groups())))


# --------------------------------------------------------------------- 应用
class App:
    """把 store / coach 和静态目录收在一起，方便测试时注入临时路径。"""

    def __init__(self, store: Optional[Store] = None, static_dir: Optional[Path] = None,
                 token: Optional[str] = None):
        self.store = store or Store()
        self.coach = Coach(self.store)
        self.static_dir = Path(static_dir or cfg.STATIC_DIR)
        self.token = token or ""

    # ------------------------------------------------------------ 首页数据
    @route("GET", "/api/bootstrap")
    def bootstrap(self, req: Request) -> tuple[int, Any]:
        return 200, self.store.bootstrap()

    @route("GET", "/api/health")
    def health(self, req: Request) -> tuple[int, Any]:
        # 前端的启动自检会把结果带在这里回传（没有控制台的环境靠它排查）
        if req.q("_bootcheck"):
            log.warning("前端自检：%s —— %s", req.q("_bootcheck"), req.q("detail") or "(无详情)")
            try:
                with open(cfg.ROOT / "data" / "bootcheck.log", "a", encoding="utf-8") as fh:
                    fh.write(f"{fmt_dt(datetime.now())}\t{req.q('_bootcheck')}\t{req.q('detail') or ''}\n")
            except OSError:
                pass
        return 200, {"ok": True, "app": cfg.APP_NAME, "version": __version__,
                     "time": fmt_dt(datetime.now())}

    # ------------------------------------------------------------ 记录
    @route("GET", "/api/records")
    def list_records(self, req: Request) -> tuple[int, Any]:
        start = parse_dt(req.q("start")).date() if req.q("start") else None
        end = parse_dt(req.q("end")).date() if req.q("end") else None
        types = [t for t in (req.q("types") or "").split(",") if t] or None
        records = self.store.records(start, end, types, limit=req.q_int("limit", 500),
                                     order=req.q("order", "desc"))
        profile = self.store.profile()
        return 200, {"items": [self.store.record_view(r, profile) for r in records],
                     "count": len(records)}

    @route("POST", "/api/records")
    def create_record(self, req: Request) -> tuple[int, Any]:
        record = self.store.add_record(req.json(), source="api")
        return 201, self.store.record_view(record, self.store.profile())

    @route("PATCH", "/api/records/{rid}")
    def patch_record(self, req: Request, rid: str) -> tuple[int, Any]:
        record = self.store.update_record(int(rid), req.json())
        if record is None:
            raise NotFound("记录不存在")
        return 200, self.store.record_view(record, self.store.profile())

    @route("DELETE", "/api/records/{rid}")
    def delete_record(self, req: Request, rid: str) -> tuple[int, Any]:
        if not self.store.delete_record(int(rid)):
            raise NotFound("记录不存在")
        return 200, {"ok": True, "id": int(rid)}

    @route("GET", "/api/records/summary")
    def records_summary(self, req: Request) -> tuple[int, Any]:
        return 200, self.store.summary(period=req.q("period", "week") or "week")

    # ------------------------------------------------------------ 图表/日历
    @route("GET", "/api/charts/weight")
    def chart_weight(self, req: Request) -> tuple[int, Any]:
        days = req.q_int("days", 90)
        profile = self.store.profile()
        series = self.store.weight_series(days=days)
        from .models import rolling_average
        return 200, {"series": series, "series_avg": rolling_average(series, 7) if series else [],
                     "goal": profile.get("target_weight_kg"),
                     "start": profile.get("start_weight_kg")}

    @route("GET", "/api/charts/level")
    def chart_level(self, req: Request) -> tuple[int, Any]:
        return 200, self.store.bootstrap()["level"]

    @route("GET", "/api/calendar")
    def calendar(self, req: Request) -> tuple[int, Any]:
        raw = req.q("month") or date.today().strftime("%Y-%m")
        try:
            year, month = (int(x) for x in raw.split("-")[:2])
        except (ValueError, AttributeError):
            raise BadRequest("month 需要形如 2026-10")
        return 200, self.store.calendar(year, month, self.store.profile())

    # ------------------------------------------------------------ 设置
    @route("GET", "/api/settings")
    def get_settings(self, req: Request) -> tuple[int, Any]:
        return 200, {"settings": self.store.settings(mask=True),
                     "profile": self.store.profile(),
                     "targets": self.store.targets(),
                     "schedule": self.store.schedule(),
                     "quick_types": cfg.QUICK_TYPES,
                     "model_presets": cfg.MODEL_PRESETS,
                     "drug_options": drugs.options(),
                     "drug": self.store.drug(),
                     "version": __version__,
                     "license": cfg.LICENSE}

    @route("PUT", "/api/settings")
    def put_settings(self, req: Request) -> tuple[int, Any]:
        body = req.json()
        profile_patch = body.pop("profile", None)
        targets_patch = body.pop("targets", None)
        schedule_patch = body.pop("schedule", None)
        settings = self.store.save_settings(body)
        if isinstance(profile_patch, dict):
            self.store.save_profile(profile_patch)
        if isinstance(targets_patch, dict):
            self.store.save_profile({"targets": targets_patch})
        if isinstance(schedule_patch, list):
            self.store.save_profile({"schedule": schedule_patch})
        return 200, {"ok": True, "settings": settings, "profile": self.store.profile(),
                     "targets": self.store.targets()}

    @route("GET", "/api/efficacy")
    def efficacy_data(self, req: Request) -> tuple[int, Any]:
        """注册试验疗效参考。不参与任何计算，只做参照；数字都带说明书出处。"""
        drug_id = req.q("drug") or str(self.store.profile().get("drug_id") or drugs.DEFAULT_DRUG)
        return 200, efficacy.payload(drug_id)

    @route("GET", "/api/hitrack/scan")
    def hitrack_scan(self, req: Request) -> tuple[int, Any]:
        """扫目录里的 HiTrack 文件（运动记录明文文件），返回摘要供导入前预览。"""
        import os
        raw = req.q("dir") or os.environ.get("GLPMATE_HITRACK_DIR") or ""
        if not raw:
            return 200, {"ok": False, "error": "no_dir",
                         "message": "请传 ?dir=/path，或设置环境变量 GLPMATE_HITRACK_DIR。"
                                    "HiTrack 文件在手机的 /data/data/com.huawei.health/files/ 下。"}
        files = hitrack.find_hitrack_files(raw)
        items = []
        for path in files[:200]:
            try:
                w = hitrack.parse_file(path)
            except (OSError, ValueError) as exc:
                items.append({"file": path.name, "error": str(exc)})
                continue
            st = w.hr_stats()
            items.append({
                "file": path.name, "track_id": w.track_id,
                "start": w.started_at.isoformat() if w.started_at else None,
                "duration_min": round(w.duration_minutes, 1),
                "distance_m": round(w.distance_m()) if w.distance_m() else None,
                "hr_samples": st["count"], "hr_avg": st["avg"],
                "hr_min": st["min"], "hr_max": st["max"],
                "gps_points": len(w.track), "splits": len(w.splits),
            })
        return 200, {"ok": True, "dir": raw, "count": len(files), "items": items,
                     "note": "HiTrack 只包含你手动记录的运动，不含 24 小时连续心率与血氧。"}

    @route("POST", "/api/hitrack/import")
    def hitrack_import(self, req: Request) -> tuple[int, Any]:
        import os
        body = req.json()
        raw = str(body.get("dir") or os.environ.get("GLPMATE_HITRACK_DIR") or "")
        if not raw:
            raise BadRequest("请传 dir 字段")
        dry_run = bool(body.get("dry_run"))
        created, skipped = [], 0
        for path in hitrack.find_hitrack_files(raw):
            try:
                workout = hitrack.parse_file(path)
            except (OSError, ValueError):
                skipped += 1
                continue
            records = hitrack.to_records(workout, source=path.name)
            if not records:
                skipped += 1
                continue
            if dry_run:
                created.extend(records)
                continue
            for rec in records:
                self.store.add_record(rec, source="hitrack")
                created.append(rec)
        return 200, {"ok": True, "dry_run": dry_run, "imported": len(created) if not dry_run else 0,
                     "records": created if dry_run else len(created), "skipped": skipped}

    @route("POST", "/api/import/batch")
    def import_batch(self, req: Request) -> tuple[int, Any]:
        """批量导入记录（APK 采集用）。

        两个关键行为：
        1. **按 source_key 去重**：同一个 HiTrack 文件被重复导入（很常见，用户会反复点）
           不应该产生重复记录。source_key 存在 meta 里，重复的直接算 skipped。
        2. 返回逐条结果，出错的记录不会让整批失败。
        """
        body = req.json()
        items = body.get("records")
        if not isinstance(items, list) or not items:
            raise BadRequest("records 必须是非空数组")
        source = str(body.get("source") or "import")
        dry_run = bool(body.get("dry_run"))
        imported, skipped, errors = [], 0, []

        seen: set[str] = set()
        for item in items[:2000]:
            if not isinstance(item, dict):
                errors.append("非对象记录已忽略")
                continue
            key = str((item.get("meta") or {}).get("source_key") or "")
            if key:
                if key in seen or self.store.has_source_key(key):
                    skipped += 1
                    continue
                seen.add(key)
            if dry_run:
                imported.append(item)
                continue
            try:
                rec = self.store.add_record(item, source=source)
            except (ValueError, TypeError) as exc:
                errors.append(str(exc))
                continue
            imported.append(self.store.record_view(rec, self.store.profile()))
        return 200, {"ok": True, "dry_run": dry_run, "imported": len(imported),
                     "skipped": skipped, "errors": errors[:20],
                     "records": imported if dry_run else None}

    # ---------------------------------------------------------- 隐私数据导出助手
    # 说明：这里**不做**任何自动化提交。华为的数据副本申请需要登录用户自己的账号，
    # 代持凭据去操作厂商门户不是本项目的边界。这里只做两件合规且真正有用的事：
    #   1. 记录"上次申请时间"，算出距今天数并给出是否该再申请的建议；
    #   2. 扫描指定目录里用户自己下载的导出包，报告发现了什么。
    EXPORT_REMIND_DAYS = 30

    @route("GET", "/api/privacy/export-status")
    def export_status(self, req: Request) -> tuple[int, Any]:
        last = self.store.get_kv("privacy_last_export_request")
        days = None
        if last:
            try:
                days = (date.today() - parse_dt(last).date()).days
            except ValueError:
                days = None
        return 200, {
            "last_request_at": last,
            "days_since": days,
            "remind_after_days": self.EXPORT_REMIND_DAYS,
            "should_request_again": days is None or days >= self.EXPORT_REMIND_DAYS,
            "portal_url": "https://privacy.consumer.huawei.com/tool",
            "steps": [
                "用浏览器打开华为隐私中心（手机或电脑都行），链接见 portal_url",
                "登录你自己的华为账号（这一步必须你本人操作）",
                "选择「获取数据副本 / Request Your Data」→ 分类勾选「健康」",
                "提交申请；华为通常在数小时内处理",
                "去注册邮箱下载 ZIP，把 ZIP 放到手机的下载目录",
                "回到 GLP Mate 采集器点「扫描导出文件」，数据会自动解析入库",
            ],
            "next_export_dir": req.q("dir") or "/sdcard/Download",
            "note": "数据副本有时效性：一次导出只覆盖到申请那一刻，建议每 "
                    f"{self.EXPORT_REMIND_DAYS} 天左右再申请一次。",
        }

    @route("POST", "/api/privacy/export-request-mark")
    def export_mark(self, req: Request) -> tuple[int, Any]:
        """用户点了"我已在隐私中心提交申请"时调用，记录时间用于节奏提醒。"""
        body = req.json()
        when = body.get("at") or fmt_dt(datetime.now())
        try:
            parse_dt(when)
        except ValueError as exc:
            raise BadRequest(f"时间格式不对：{when}") from exc
        self.store.set_kv("privacy_last_export_request", when)
        return 200, {"ok": True, "last_request_at": when}

    @route("GET", "/api/privacy/scan-exports")
    def scan_exports(self, req: Request) -> tuple[int, Any]:
        """扫目录里用户自己下载的华为导出包（只报告，不解析内容）。"""
        import os
        raw = req.q("dir") or "/sdcard/Download"
        found: list[dict[str, Any]] = []
        try:
            for name in sorted(os.listdir(raw))[:500]:
                low = name.lower()
                if not (low.endswith(".zip") or "huawei" in low or "health" in low):
                    continue
                path = os.path.join(raw, name)
                try:
                    stat = os.stat(path)
                except OSError:
                    continue
                found.append({"name": name, "path": path,
                              "size_mb": round(stat.st_size / 1048576, 2),
                              "modified": fmt_dt(datetime.fromtimestamp(stat.st_mtime))})
        except (OSError, PermissionError) as exc:
            return 200, {"ok": False, "dir": raw, "error": str(exc),
                         "message": "读不到该目录。手机上下载目录通常是 /sdcard/Download，"
                                    "若服务跑在 Termux 里请确认已授予存储权限。"}
        return 200, {"ok": True, "dir": raw, "count": len(found), "items": found,
                     "note": "只列出候选文件，内容解析尚未实现（等拿到真实的导出包结构再写）。"}

    @route("GET", "/api/drugs")
    def list_drugs(self, req: Request) -> tuple[int, Any]:
        return 200, {"options": drugs.options(), "current": self.store.drug(),
                     "verified_on": drugs.VERIFIED_ON,
                     "sources_markdown": drugs.sources_markdown()}

    @route("POST", "/api/settings/test")
    def test_settings(self, req: Request) -> tuple[int, Any]:
        body = req.json()
        conf = self.store.ai_config()
        if body.get("ai_api_key"):
            conf["api_key"] = str(body["ai_api_key"])
        if body.get("ai_base_url"):
            conf["base_url"] = str(body["ai_base_url"])
        if body.get("ai_model"):
            conf["model"] = str(body["ai_model"])
        from .coach import ChatClient
        client = ChatClient(conf["base_url"], conf["api_key"], conf["model"], 0.0, min(30.0, conf["timeout"]))
        # 把用户填的值也回显出来，便于排查路径拼接问题
        info = {"endpoint": normalize_endpoint(conf["base_url"])}
        try:
            info.update(client.ping())
            return 200, info
        except AIError as exc:
            return 200, {**info, "ok": False, "error": str(exc), "status": exc.status}

    # ------------------------------------------------------------ 导入导出
    @route("GET", "/api/export")
    def export(self, req: Request) -> tuple[int, Any]:
        return 200, self.store.export_all()

    @route("POST", "/api/import")
    def import_data(self, req: Request) -> tuple[int, Any]:
        body = req.json()
        return 200, self.store.import_all(body, replace=bool(body.get("replace")))

    @route("POST", "/api/wipe")
    def wipe(self, req: Request) -> tuple[int, Any]:
        body = req.json()
        if str(body.get("confirm") or "") != "DELETE":
            raise BadRequest('请输入 DELETE 以确认删除全部数据')
        self.store.wipe(keep_settings=bool(body.get("keep_settings", True)))
        return 200, {"ok": True}

    # ------------------------------------------------------------ 教练
    @route("POST", "/api/records/dedupe")
    def records_dedupe(self, req: Request) -> tuple[int, Any]:
        """清理重复记录（同类型+同分钟+同餐次+同描述），保留最早一条。

        真机上出现过同一餐被记 4 次（同一餐生成了多张卡片、各自确认）。
        这个接口用于清理已经产生的脏数据；新数据由 add_record(skip_if_exists=True) 挡住。
        """
        body = req.json() if req.body else {}
        return 200, self.store.dedupe_records(dry_run=bool(body.get("dry_run")))

    @route("POST", "/api/coach/cards/prune")
    def cards_prune(self, req: Request) -> tuple[int, Any]:
        """清理两类无用的待确认卡片。

        ① **空卡片**：拍照识别失败时后端也会建卡（既定行为），那种卡片
           desc 是占位文字「一餐」、热量为 0。确认它等于记一条空餐食。
           真机上留下了 4 张这样的卡片，混在聊天里很干扰。
        ② **已被记录覆盖的卡片**：同一餐已经落库了，但对应的卡片还是 pending。
           留着它用户点一下就又记一遍——这正是重复记录的入口
           （真机上同一张早餐卡片被确认了 4 次）。
        """
        pruned: list[dict[str, str]] = []
        for card in self.store.pending_cards(limit=200):
            if card.get("kind") != "meal_confirm":
                continue
            recs = (card.get("payload") or {}).get("records") or []
            if not recs:
                continue
            # ① 空卡片：全部记录都是 0 热量且描述是占位词
            useless = all(
                float((r.get("meta") or {}).get("kcal") or 0) <= 0
                and str((r.get("meta") or {}).get("desc") or "").strip() in ("", "一餐")
                for r in recs
            )
            reason = "空卡片" if useless else ""
            # ② 已被记录覆盖
            #
            # 必须走 find_similar_record（带描述相似度），不能用 existing_identities()
            # 的精确 key 集合——真机上卡片写「120ml牛奶」、记录是「120ml全脂牛奶」，
            # 精确比较永远不命中，那张卡片就一直挂在那里等着被误确认。
            if not useless:
                covered = all(
                    self.store.find_similar_record(
                        str(r.get("type") or ""), str(r.get("at") or ""), r.get("meta") or {},
                    ) is not None
                    for r in recs
                )
                if covered:
                    reason = "已被记录覆盖"
            if reason:
                self.store.confirm_card(card["id"], "discard")
                pruned.append({"id": card["id"], "reason": reason})
        return 200, {"pruned": len(pruned), "items": pruned,
                     "ids": [p["id"] for p in pruned]}

    @route("GET", "/api/coach/messages")
    def coach_messages(self, req: Request) -> tuple[int, Any]:
        limit = req.q_int("limit", 200)
        return 200, {"items": self.store.messages(limit=limit), "quota": self.store.quota()}

    @route("POST", "/api/coach/messages")
    def coach_send(self, req: Request) -> tuple[int, Any]:
        body = req.json()
        text = str(body.get("text") or "").strip()
        if not text:
            raise BadRequest("消息不能为空")
        quota = self.store.quota()
        if quota["used"] >= quota["limit"]:
            return 200, {"error": "quota_exceeded", "quota": quota,
                         "message": f"今天的教练额度已用完（{quota['used']}/{quota['limit']}），"
                                    f"记录、图表和提醒都不受影响。"}
        user_msg = self.store.add_message("user", text)
        try:
            result = self.coach.reply(text)
        except AIError as exc:
            # 模型这一侧失败：把用户消息留下，并明确说明原因，额度不扣
            err = self.store.add_message(
                "assistant",
                f"调用模型失败：{exc}\n\n（这条不计入额度。检查 设置 → 模型与额度 里的 base_url、模型名和 key。）",
                meta={"error": True},
            )
            return 200, {"user": user_msg, "assistant": err, "quota": self.store.quota(),
                         "error": "ai_error", "message": str(exc)}
        except ValueError as exc:
            raise BadRequest(str(exc)) from exc
        card = result.card
        if card:
            # 卡片状态存进消息里，前端直接渲染
            card = {**card}
        assistant = self.store.add_message("assistant", result.text, card=card,
                                           meta={"model": result.model, "latency_ms": result.latency_ms,
                                                 "usage": result.usage, "guard": result.guard})
        if not result.guard:
            self.store.bump_quota()
        return 200, {"user": user_msg, "assistant": assistant, "quota": self.store.quota(),
                     "guard": result.guard, "model": result.model}

    @route("POST", "/api/coach/photo")
    def coach_photo(self, req: Request) -> tuple[int, Any]:
        raw, filename, _head = req.form_file("file")
        image_bytes, mime = prepare_image(raw, filename)
        note = req.q("note") or ""
        try:
            estimate = self.coach.photo_estimate(image_bytes, mime, note)
        except AIError as exc:
            return 200, {"error": "vision_unavailable", "message": str(exc)}
        slot = _slot_now()
        record = {
            "type": "food",
            "at": datetime.now().replace(microsecond=0).isoformat(),
            "unit": "kcal",
            "value": estimate["total"].get("kcal"),
            "meta": {
                "slot": slot,
                "desc": "、".join(i["name"] for i in estimate["items"]) or "一餐",
                "kcal": estimate["total"].get("kcal", 0),
                "protein_g": estimate["total"].get("protein_g", 0),
                "carb_g": estimate["total"].get("carb_g", 0),
                "fat_g": estimate["total"].get("fat_g", 0),
                "fiber_g": estimate["total"].get("fiber_g", 0),
                "items": estimate["items"],
                "estimated": True,
                "confidence": estimate.get("confidence"),
            },
        }
        card = self.store.create_card("meal_confirm", {
            "records": [record],
            "title": record["meta"]["desc"],
            "summary": f"{estimate['total'].get('kcal', 0):.0f} kcal · "
                       f"蛋白 {estimate['total'].get('protein_g', 0):.0f} g · "
                       f"碳水 {estimate['total'].get('carb_g', 0):.0f} g · "
                       f"脂肪 {estimate['total'].get('fat_g', 0):.0f} g",
            "estimate": estimate,
        })
        return 200, {"estimate": estimate, "card": card}

    @route("POST", "/api/coach/confirm")
    def coach_confirm(self, req: Request) -> tuple[int, Any]:
        body = req.json()
        cid = str(body.get("card_id") or body.get("id") or "")
        action = str(body.get("action") or "save")
        if not cid:
            raise BadRequest("缺少 card_id")
        try:
            result = self.store.confirm_card(cid, action)
        except KeyError as exc:
            raise NotFound(str(exc)) from exc
        return 200, result

    @route("GET", "/api/coach/quota")
    def coach_quota(self, req: Request) -> tuple[int, Any]:
        return 200, self.store.quota()

    @route("DELETE", "/api/coach/messages")
    def coach_clear(self, req: Request) -> tuple[int, Any]:
        return 200, {"ok": True, "deleted": self.store.clear_messages()}

    # ------------------------------------------------------------ 静态文件
    def serve_static(self, path: str) -> tuple[int, Any, bytes, str]:
        rel = path[len("/static/"):] if path.startswith("/static/") else path.lstrip("/")
        target = (self.static_dir / rel).resolve()
        static_root = self.static_dir.resolve()
        if static_root not in target.parents and target != static_root:
            raise NotFound("越界的路径")
        if not target.is_file():
            raise NotFound(f"没有这个文件：{rel}")
        mime, _ = mimetypes.guess_type(str(target))
        return 200, "raw", target.read_bytes(), mime or "application/octet-stream"

    def index_html(self) -> bytes:
        index = self.static_dir / "index.html"
        if index.is_file():
            return index.read_bytes()
        return ("<!doctype html><meta charset=utf-8><title>GLP Mate Lite</title>"
                "<body style='font-family:sans-serif;padding:32px;background:#0B0F12;color:#E6EDF3'>"
                "<h1>GLP Mate Lite</h1><p>static/index.html 还没生成，请先构建前端。</p>"
                "<p>API 可用：<a style='color:#35C6E8' href='/api/health'>/api/health</a></p>").encode()


# ------------------------------------------------------------------ Handler
class Handler(BaseHTTPRequestHandler):
    server_version = f"glpmate-lite/{__version__}"
    protocol_version = "HTTP/1.1"
    app: App

    # 让日志安静一点，错误仍会打印
    def log_message(self, fmt: str, *args: Any) -> None:  # noqa: A003
        log.debug("%s - %s", self.address_string(), fmt % args)

    def log_error(self, fmt: str, *args: Any) -> None:  # noqa: A003
        log.warning("%s - %s", self.address_string(), fmt % args)

    # -- 入口 --------------------------------------------------------------
    def do_GET(self) -> None:      # noqa: N802
        self._dispatch("GET")

    def do_POST(self) -> None:     # noqa: N802
        self._dispatch("POST")

    def do_PUT(self) -> None:      # noqa: N802
        self._dispatch("PUT")

    def do_PATCH(self) -> None:    # noqa: N802
        self._dispatch("PATCH")

    def do_DELETE(self) -> None:   # noqa: N802
        self._dispatch("DELETE")

    def do_OPTIONS(self) -> None:  # noqa: N802
        self._send(204, b"", "text/plain", extra={"Access-Control-Allow-Methods": "GET,POST,PUT,PATCH,DELETE,OPTIONS",
                                                  "Access-Control-Allow-Headers": "Content-Type,Authorization"})

    def _dispatch(self, method: str) -> None:
        parsed = urllib.parse.urlsplit(self.path)
        path = urllib.parse.unquote(parsed.path)
        query = urllib.parse.parse_qs(parsed.query)
        try:
            length = int(self.headers.get("Content-Length") or 0)
            if length > MAX_BODY:
                raise BadRequest("请求体过大（>24MB）")
            body = self.rfile.read(length) if length else b""
            req = Request(method=method, path=path, query=query, body=body, headers=self.headers)

            if method == "OPTIONS":
                self._send(204, b"", "text/plain")
                return
            if self.app.token and not self._authorized(req):
                raise Unauthorized("需要访问口令（在启动命令里设置 GLPMATE_TOKEN）")

            for route_method, pattern, func in ROUTES:
                if route_method != method:
                    continue
                params = _match(pattern, path)
                if params is not None:
                    req.params = params
                    status, payload = func(self.app, req, **params)
                    if isinstance(payload, tuple) and len(payload) == 4 and payload[1] == "raw":
                        _, _, data, mime = payload
                        self._send(status, data, mime)
                    else:
                        self._send(status, json.dumps(payload, ensure_ascii=False, default=_json_default).encode("utf-8"),
                                   "application/json; charset=utf-8")
                    return

            if path.startswith("/static/") or path in ("/favicon.ico", "/manifest.webmanifest",
                                                       "/service-worker.js", "/apple-touch-icon.png"):
                status, kind, data, mime = self.app.serve_static(path)
                self._send(status, data, mime)
                return
            if method == "GET" and not path.startswith("/api/"):
                self._send(200, self.app.index_html(), "text/html; charset=utf-8")
                return
            raise NotFound(f"没有这个接口：{method} {path}")
        except BadRequest as exc:
            self._send(400, json.dumps({"error": "bad_request", "message": str(exc)},
                                       ensure_ascii=False).encode(), "application/json; charset=utf-8")
        except Unauthorized as exc:
            self._send(401, json.dumps({"error": "unauthorized", "message": str(exc)},
                                       ensure_ascii=False).encode(), "application/json; charset=utf-8")
        except NotFound as exc:
            self._send(404, json.dumps({"error": "not_found", "message": str(exc)},
                                       ensure_ascii=False).encode(), "application/json; charset=utf-8")
        except (BrokenPipeError, ConnectionResetError):
            log.debug("客户端断开连接：%s", path)
        except Exception as exc:  # noqa: BLE001
            log.error("处理 %s %s 出错：%s\n%s", method, path, exc, traceback.format_exc())
            self._send(500, json.dumps({"error": "internal", "message": str(exc)},
                                       ensure_ascii=False).encode(), "application/json; charset=utf-8")

    def _authorized(self, req: Request) -> bool:
        supplied = str(req.headers.get("X-GLPMATE-Token") or "")
        if not supplied:
            auth = str(req.headers.get("Authorization") or "")
            supplied = auth[7:] if auth.lower().startswith("bearer ") else ""
        if supplied == self.app.token:
            return True
        return req.q("token") == self.app.token

    def _send(self, status: int, body: bytes, content_type: str,
              extra: Optional[dict[str, str]] = None) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store" if "json" in content_type else "no-cache")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self.end_headers()
        if body and self.command != "HEAD":
            self.wfile.write(body)


def _json_default(value: Any) -> Any:
    if isinstance(value, (datetime, date)):
        return fmt_dt(value) if isinstance(value, datetime) else value.isoformat()
    if isinstance(value, Path):
        return str(value)
    return str(value)


def _slot_now() -> str:
    hour = datetime.now().hour
    if hour < 10:
        return "breakfast"
    if hour < 15:
        return "lunch"
    if hour < 21:
        return "dinner"
    return "snack"


# ------------------------------------------------------------------ 启动
def make_server(host: str = cfg.DEFAULT_HOST, port: int = cfg.DEFAULT_PORT,
                store: Optional[Store] = None, static_dir: Optional[Path] = None,
                token: Optional[str] = None, port_retries: int = 10) -> ThreadingHTTPServer:
    app = App(store=store, static_dir=static_dir, token=token)

    class BoundHandler(Handler):
        pass

    BoundHandler.app = app  # type: ignore[attr-defined]

    last_error: Optional[OSError] = None
    for offset in range(port_retries):
        try:
            httpd = ThreadingHTTPServer((host, port + offset), BoundHandler)
            httpd.daemon_threads = True
            httpd.app = app           # type: ignore[attr-defined]
            return httpd
        except OSError as exc:
            last_error = exc
            if exc.errno not in (98, 48, 10048):   # EADDRINUSE
                raise
            log.warning("端口 %s 被占用，试下一个", port + offset)
    raise SystemExit(f"没有可用端口：{last_error}")


def local_ip() -> str:
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.connect(("8.8.8.8", 80))
        ip = sock.getsockname()[0]
        sock.close()
        return ip
    except OSError:
        return "127.0.0.1"
