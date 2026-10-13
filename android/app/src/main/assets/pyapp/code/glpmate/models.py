"""领域模型 + 药代动力学估算 + 汇总计算。

这个模块是整个程序的"事实来源"：记录怎么归一化、目标怎么汇总、
体内药物水平怎么算，都在这里，且全部是纯函数（无 IO、无数据库），
方便单元测试和离线复算。
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta
from typing import Any, Iterable, Literal, Optional, Sequence

RecordType = Literal[
    "weight", "food", "water", "dose", "side_effect", "training",
    "sleep", "note", "vital", "measure", "symptom", "meal_plan",
]

ISO = "%Y-%m-%dT%H:%M:%S"

# 需要参与"今天已记录"计数的类型
FOOD_SLOTS = ("breakfast", "lunch", "dinner", "snack")


# ------------------------------------------------------------------ 时间工具
def parse_dt(value: Any) -> datetime:
    """尽量宽容地把各种时间写法变成 datetime（naive，本地时间）。"""
    if isinstance(value, datetime):
        return value.replace(tzinfo=None)
    if isinstance(value, date):
        return datetime.combine(value, time(0, 0))
    if value in (None, ""):
        return datetime.now().replace(microsecond=0)
    text = str(value).strip().replace("Z", "+00:00")
    try:
        dt = datetime.fromisoformat(text)
    except ValueError:
        # 常见的中文/斜杠/空格写法，逐个试
        for fmt in ("%Y/%m/%d %H:%M:%S", "%Y/%m/%d %H:%M", "%Y/%m/%d",
                    "%Y-%m-%d %H:%M", "%Y-%m-%d %H:%M:%S", "%Y-%m-%d",
                    "%Y年%m月%d日 %H:%M", "%Y年%m月%d日",
                    "%m/%d/%Y %H:%M", "%d/%m/%Y %H:%M"):
            try:
                dt = datetime.strptime(text, fmt)
                break
            except ValueError:
                continue
        else:
            raise ValueError(f"无法识别的时间格式: {value!r}")
    return dt.replace(tzinfo=None)


def fmt_dt(dt: datetime) -> str:
    return dt.replace(microsecond=0).isoformat()


def day_bounds(day: date) -> tuple[datetime, datetime]:
    start = datetime.combine(day, time(0, 0))
    return start, start + timedelta(days=1)


def daterange(start: date, end: date) -> Iterable[date]:
    cur = start
    while cur <= end:
        yield cur
        cur += timedelta(days=1)


def day_index(treatment_start: date, today: date) -> int:
    """治疗第几天，从 1 开始。"""
    return (today - treatment_start).days + 1


# ------------------------------------------------------------------ 记录归一化
@dataclass
class Record:
    id: Optional[int]
    type: str
    at: datetime
    value: Optional[float] = None
    unit: str = ""
    meta: dict[str, Any] = field(default_factory=dict)

    @property
    def day(self) -> date:
        return self.at.date()

    def meta_num(self, key: str, default: float = 0.0) -> float:
        try:
            return float(self.meta.get(key, default) or 0.0)
        except (TypeError, ValueError):
            return default


def _num(value: Any, default: Optional[float] = None) -> Optional[float]:
    if value is None or value == "":
        return default
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def normalize_payload(payload: dict[str, Any]) -> dict[str, Any]:
    """把前端传来的记录体补全成入库格式。"""
    rtype = str(payload.get("type") or "").strip()
    if not rtype:
        raise ValueError("type 不能为空")
    at = parse_dt(payload.get("at"))
    value = _num(payload.get("value"))
    unit = str(payload.get("unit") or "")
    meta = dict(payload.get("meta") or {})

    if rtype == "food":
        items = meta.get("items")
        if isinstance(items, list) and items:
            totals = {"kcal": 0.0, "protein_g": 0.0, "carb_g": 0.0, "fat_g": 0.0, "fiber_g": 0.0}
            for it in items:
                for key in totals:
                    totals[key] += _num(it.get(key), 0.0) or 0.0
            for key, total in totals.items():
                meta.setdefault(key, round(total, 2))
        for key, default in (("kcal", 0.0), ("protein_g", 0.0), ("carb_g", 0.0),
                             ("fat_g", 0.0), ("fiber_g", 0.0)):
            meta[key] = _num(meta.get(key), default) or default

    if rtype == "water":
        # 允许用 value(ml) 或 meta.amount_ml
        ml = _num(meta.get("amount_ml"), value) or 0.0
        meta["amount_ml"] = ml
        value = ml
        unit = unit or "ml"

    if rtype == "dose":
        meta["dose_mg"] = _num(meta.get("dose_mg"), value) or 0.0
        meta["med_name"] = str(meta.get("med_name") or "")
        meta["site"] = str(meta.get("site") or "")
        meta["skipped"] = bool(meta.get("skipped", False))
        value = meta["dose_mg"]
        unit = unit or "mg"

    if rtype == "side_effect":
        meta["label"] = str(meta.get("label") or "不适")
        meta["severity"] = int(_num(meta.get("severity"), 1) or 1)
        value = meta["severity"]
        unit = unit or "级"

    if rtype == "weight":
        unit = unit or "kg"

    return {"type": rtype, "at": at, "value": value, "unit": unit, "meta": meta}


def summarize(rec: Record, targets: dict[str, float], profile: dict[str, Any]) -> dict[str, Any]:
    """给一条记录生成展示用的标题/副标题/数值（前端直接渲染，避免逻辑分散）。"""
    meta = rec.meta
    rtype = rec.type
    if rtype == "weight":
        v = rec.value or 0.0
        return {"title": "体重", "subtitle": f"{v:.1f} kg", "value": round(v, 2), "unit": "kg"}
    if rtype == "food":
        slot = str(meta.get("slot") or "")
        slot_cn = {"breakfast": "早餐", "lunch": "午餐", "dinner": "晚餐", "snack": "加餐"}.get(slot, "餐食")
        desc = str(meta.get("desc") or "").strip()
        kcal = rec.meta_num("kcal")
        prot = rec.meta_num("protein_g")
        # 营养数字来自教练估算，可能还没回来（或用户只描述了没拍照）。
        # 那种情况下**不要显示"0 g 蛋白"**——0 会被读成"这餐没有蛋白质"，
        # 而事实是"还没有数据"，两者意思完全不同。
        has_macros = kcal > 0 or prot > 0
        if desc and has_macros:
            sub = f"{desc} · {prot:.0f} g 蛋白"
        elif desc:
            sub = f"{desc} · 待教练估算"
        elif has_macros:
            sub = f"{kcal:.0f} kcal · 蛋白 {prot:.0f} g"
        else:
            sub = "待教练估算"
        return {"title": slot_cn, "subtitle": sub, "value": round(kcal), "unit": "kcal"}
    if rtype == "water":
        ml = rec.meta_num("amount_ml")
        return {"title": "饮水", "subtitle": f"{ml / 1000:.2f} 升", "value": round(ml), "unit": "ml"}
    if rtype == "dose":
        mg = rec.meta_num("dose_mg")
        # 药名以当前档案为准：历史记录的 meta 可能残留旧药名（换过药或改过参数），
        # 显示时不能被旧值带偏
        name = str(profile.get("med_name") or meta.get("med_name") or "剂量")
        if meta.get("skipped"):
            return {"title": "跳过注射", "subtitle": name, "value": 0, "unit": "mg"}
        return {"title": "剂量", "subtitle": f"{name} {mg:g} mg", "value": round(mg, 3), "unit": "mg"}
    if rtype == "side_effect":
        return {"title": str(meta.get("label") or "不适"),
                "subtitle": f"严重度 {meta.get('severity', 1)}/10",
                "value": meta.get("severity", 1), "unit": "级"}
    if rtype == "training":
        return {"title": "训练", "subtitle": str(meta.get("desc") or ""), "value": rec.value, "unit": rec.unit or "min"}
    if rtype == "sleep":
        return {"title": "睡眠", "subtitle": str(meta.get("desc") or ""), "value": rec.value, "unit": rec.unit or "h"}
    title = str(meta.get("label") or {
        "note": "备注", "vital": "体征", "measure": "测量", "symptom": "症状", "meal_plan": "计划餐",
    }.get(rtype, rtype))
    return {"title": title, "subtitle": str(meta.get("desc") or ""), "value": rec.value, "unit": rec.unit}


def daily_totals(records: Sequence[Record]) -> dict[str, float]:
    """一天的摄入/饮水合计。"""
    totals = {"kcal": 0.0, "protein_g": 0.0, "carb_g": 0.0, "fat_g": 0.0,
              "fiber_g": 0.0, "water_ml": 0.0, "training_min": 0.0}
    for r in records:
        if r.type == "food":
            for key in ("kcal", "protein_g", "carb_g", "fat_g", "fiber_g"):
                totals[key] += r.meta_num(key)
        elif r.type == "water":
            totals["water_ml"] += r.meta_num("amount_ml")
        elif r.type == "training":
            totals["training_min"] += r.value or 0.0
    return {k: round(v, 2) for k, v in totals.items()}


# ------------------------------------------------------------------ 药代动力学
@dataclass
class DoseEvent:
    at: datetime
    mg: float
    planned: bool = False   # True = 按计划补上的未来剂量（仅用于投影）


def impulse_response(t_hours: float, half_life_hours: float, tmax_hours: float) -> float:
    """单位剂量的冲激响应：打了 1 mg，t 小时后体内还剩多少。

    **返回真实量纲**（峰值 ≈0.87 而不是 1.0），与 `bateman_fraction` 同口径。

    ============ 这里曾经归一化过（别再归一化回去）============
    旧实现除以自身在 tmax 处的值，把峰值强行变成 1.0。当时这么写的理由是
    "Σ 冲激响应 求稳态峰值会偏小 17%"——但那个 17% 是**虚高**：归一化把
    首剂峰值抬到了剂量的 100%（忽略达峰前已清除的部分），多出来的量在
    多周期求和时被放大成 17% 的假上界。

    更要紧的是它会和 `steady_state_trough` 量纲打架：那个函数走的是
    `bateman_fraction`，一旦两者一个归一化、一个不归一化，**比值（也就是界面上
    所有的百分比）就被破坏**——实测稳态峰值/谷值会从 2.02 变成 2.18。

    现在两个函数都返回"剩余量占剂量的比例"，比值因此与缩放无关，
    百分比口径稳定；绝对 mg 值也终于符合物理（首剂峰值 = 剂量的 87.1%）。
    """
    if t_hours <= 0 or half_life_hours <= 0:
        return 0.0
    ke = math.log(2.0) / half_life_hours
    tmax = max(1.0, float(tmax_hours or 1.0))
    ka = _solve_ka(ke, tmax)
    if ka <= ke:
        return max(0.0, decay_fraction_exp(t_hours, half_life_hours))
    return max(0.0, ka / (ka - ke) * (math.exp(-ke * t_hours) - math.exp(-ka * t_hours)))


def bateman_fraction(t_hours: float, half_life_hours: float, tmax_hours: float) -> float:
    """单次皮下给药后，**体内还剩多少**（占该次剂量的比例）。

    t=0 为 0（针刚打完还没吸收），t=tmax 处最大，之后按半衰期衰减到 0。

    ============ 这里曾经有个量纲错误（别再"归一化"回去）============
    旧实现除以 `impulse_response(tmax, ...)`，把峰值强制成 1.0。后果是
    "单次给药的峰值 = 剂量的 100%"——等于说吸收进来多少就还剩多少，
    **完全忽略了达峰之前已经被清除掉的那部分**。

    用说明书参数（半衰期 120h、tmax 24h）算，真实峰值只有剂量的 **87.1%**：
    在 t=24h 时 e^(-ka·t) 已经不是小数（≈0.036），它要从吸收项里减掉，
    把幅度系数 ka/(ka-ke)≈1.044 抵消了大半。旧实现因此把首剂水平高估 12.9%，
    用户会看到"2.5 mg 的针打进去、体内峰值 2.5 mg"这种物理上不可能的数。

    现在返回真实比例（峰值 0.871、长时趋于 0）。由于只是乘了一个常数，
    所有**比值**（稳态谷值/峰值、蓄积比、达峰时间、各 % 口径）完全不变，
    受影响的只有绝对 mg 值。
    """
    if half_life_hours <= 0 or t_hours <= 0:
        return 0.0
    ke = math.log(2.0) / half_life_hours
    tmax = max(1.0, float(tmax_hours or 1.0))
    ka = _solve_ka(ke, tmax)
    if ka <= ke:
        return max(0.0, decay_fraction_exp(t_hours, half_life_hours))
    return max(0.0, ka / (ka - ke) * (math.exp(-ke * t_hours) - math.exp(-ka * t_hours)))


def _solve_ka(ke: float, tmax: float, iterations: int = 80) -> float:
    """数值解 ka：f(ka) = ln(ka/ke)/(ka-ke) - tmax = 0，ka ∈ (ke, ∞)。

    f 在 ka→ke+ 时趋于 +∞，在 ka→+∞ 时趋于 0，所以区间内必有唯一解，二分即可。
    """
    lo, hi = ke * 1.0000001, max(ke * 500.0, 1.0)
    for _ in range(iterations):
        mid = 0.5 * (lo + hi)
        value = math.log(mid / ke) / (mid - ke) - tmax
        if value > 0:
            lo = mid          # tmax 还太大，说明 ka 要更大
        else:
            hi = mid
    return 0.5 * (lo + hi)


def tmax_of(half_life_hours: float, tmax_hours: float) -> float:
    """模型实际的达峰时间（用来做自检：应该约等于说明书 tmax）。"""
    if half_life_hours <= 0:
        return 0.0
    ke = math.log(2.0) / half_life_hours
    ka = _solve_ka(ke, max(1.0, float(tmax_hours or 1.0)))
    return math.log(ka / ke) / (ka - ke)


def decay_fraction_exp(dt_hours: float, half_life_hours: float) -> float:
    """纯指数衰减（瞬时吸收近似）。保留给"只看消除"的场合与回归测试。"""
    if half_life_hours <= 0:
        return 1.0
    if dt_hours < 0:
        return 0.0
    return math.exp(-math.log(2.0) * dt_hours / half_life_hours)


def level_at(t: datetime, events: Sequence[DoseEvent], half_life_hours: float,
             tmax_hours: float = 24.0) -> float:
    """叠加原理：当前水平 = 历次给药响应的加权和。

    返回值是"mg 等效"的相对量（1 个单位剂量在其峰值处记为 1），
    不是血药浓度 ng/mL——那需要绝对生物利用度和分布容积，本工具不提供。
    """
    total = 0.0
    for ev in events:
        if ev.at > t or ev.mg <= 0:
            continue
        dt_h = (t - ev.at).total_seconds() / 3600.0
        total += ev.mg * bateman_fraction(dt_h, half_life_hours, tmax_hours)
    return total


def _interval_hours(events: Sequence[DoseEvent], half_life_hours: float,
                    interval_hours: Optional[float] = None) -> float:
    """给药间隔（小时）。优先用处方间隔，其次从相邻给药时刻推断（取中位数）。"""
    if interval_hours and interval_hours > 0:
        return float(interval_hours)
    times = sorted({ev.at for ev in events})
    if len(times) >= 2:
        deltas = sorted((b - a).total_seconds() / 3600.0 for a, b in zip(times, times[1:]))
        guess = deltas[len(deltas) // 2]
        if guess > 0:
            return guess
    return 168.0


def steady_state_trough(events: Sequence[DoseEvent], half_life_hours: float,
                        interval_hours: Optional[float] = None,
                        tmax_hours: float = 24.0) -> float:
    """稳态"给药前"谷值（基线），也是界面上百分比的 100% 基准。

    推导：设给药前谷值为 T，则 T = (T + D)·k（k = 2^(-间隔/半衰期)），
    于是 T = D·k/(1-k)。间隔 7 天、半衰期 165h、单次 2.5mg 时 T ≈ 2.44mg。
    基线本身没有真实血药浓度含义，只用来做相对展示。

    注意 interval_hours 必须传处方间隔：如果让本函数从 events 里猜，而 events 又是
    带计划剂量的加密采样序列，猜出来的间隔会偏小，百分比会整体偏低。
    """
    dose = max((ev.mg for ev in events), default=0.0)
    if dose <= 0 or half_life_hours <= 0:
        return 0.0
    interval = _interval_hours(events, half_life_hours, interval_hours)
    k = bateman_fraction(interval, half_life_hours, tmax_hours)
    if k >= 1.0:
        return dose
    return dose * k / (1.0 - k)


def theoretical_max(events: Sequence[DoseEvent], half_life_hours: float,
                    interval_hours: Optional[float] = None,
                    tmax_hours: float = 24.0) -> float:
    """稳态峰值上限，用它做进度条分母（保证 pct_max 不会超过 100%）。

    峰出现在"某次给药后 tmax"处：本次剂量贡献 1，其余各次按实际经过时间叠加。
    这里不能用 r/(1-r) 偷懒——那个比值是"给药时刻"的残留比，不是达峰时刻的，
    会算低（替尔泊肽参数下低约 8%），进度条就会溢出。
    """
    dose = max((ev.mg for ev in events), default=0.0)
    if dose <= 0 or half_life_hours <= 0:
        return 0.0
    interval = _interval_hours(events, half_life_hours, interval_hours)
    # 稳态下、本次给药后 tmax 处的水平 = D·Σ_{k≥0} 冲激响应(tmax + k·间隔)
    series = sum(impulse_response(tmax_hours + k * interval, half_life_hours, tmax_hours)
                 for k in range(80))
    # 留 3% 余量：真实给药时刻不一定正好落在模型 tmax 上
    return dose * series * 1.03


# 兼容旧名字：只考虑消除的指数衰减（瞬时吸收近似）
decay_fraction = decay_fraction_exp


def project_events(
    taken: Sequence[Record],
    today: date,
    profile: dict[str, Any],
    horizon_days: int = 7,
) -> list[DoseEvent]:
    """把已记录的剂量 + 未来计划剂量合成一条时间线，用于画图。"""
    events = [DoseEvent(r.at, r.meta_num("dose_mg")) for r in taken if r.type == "dose"
              and not r.meta.get("skipped") and r.meta_num("dose_mg") > 0]
    events.sort(key=lambda e: e.at)
    if not events:
        return []
    interval = int(profile.get("dose_interval_days") or 7)
    last = events[-1].at
    horizon = datetime.combine(today + timedelta(days=horizon_days), time(23, 59))
    cursor = last
    while cursor < horizon:
        cursor = cursor + timedelta(days=interval)
        if cursor <= horizon:
            events.append(DoseEvent(cursor, events[0].mg if events else 0.0, planned=True))
    return events


def level_curve(
    events: Sequence[DoseEvent],
    profile: dict[str, Any],
    start: datetime,
    end: datetime,
    steps_per_day: int = 4,
) -> list[dict[str, Any]]:
    """按时间采样体内水平。返回 [{t, mg, pct, pct_max}]。

    pct     = 相对稳态谷值（基线）的百分比，稳态给药前为 100%，稳态峰约 202%；
    pct_max = 相对理论上限（谷值 + 单次剂量）的百分比，最高约 100%，适合做进度条。

    除了等间隔采样，还会在每次给药的时刻与给药后一小段时间各补一个点：给药时间
    通常落在整点上（比如每周一 12:00），正好会跟采样网格重合，只取网格点会把每次
    注射后的跳升抹平，画出来是一条没有锯齿的假曲线。
    """
    half_life = float(profile.get("half_life_hours") or 120.0)
    tmax = float(profile.get("tmax_hours") or 24.0)
    # 间隔必须用处方间隔：从 events 猜会猜成采样间隔
    interval = float(profile.get("dose_interval_days") or 7) * 24.0
    trough = steady_state_trough(events, half_life, interval, tmax)
    top = theoretical_max(events, half_life, interval, tmax)

    times: set[datetime] = set()
    step = timedelta(days=1) / max(1, steps_per_day)
    t = start
    while t <= end:
        times.add(t)
        t += step
    for ev in events:
        # 给药当天要看得到"上升 → 达峰 → 回落"：峰在 tmax 处，不是给药瞬间
        offsets = (timedelta(hours=6), timedelta(hours=tmax),
                   timedelta(hours=tmax + 12), timedelta(hours=tmax + 36),
                   timedelta(hours=tmax + 72))
        for offset in offsets:
            moment = ev.at + offset
            if start <= moment <= end:
                times.add(moment)

    points: list[dict[str, Any]] = []
    for moment in sorted(times):
        mg = level_at(moment, events, half_life, tmax)
        points.append({
            "t": fmt_dt(moment),
            "mg": round(mg, 4),
            "pct": round(100.0 * mg / trough, 1) if trough else 0.0,
            "pct_max": round(100.0 * mg / top, 1) if top else 0.0,
        })
    return points


def next_dose_info(today: date, profile: dict[str, Any], taken: Sequence[Record],
                   now: Optional[datetime] = None) -> dict[str, Any]:
    """下一次剂量：优先按已记录剂量的间隔推算，没有记录则按设定的星期几。"""
    now = now or datetime.now().replace(microsecond=0)
    interval = int(profile.get("dose_interval_days") or 7)
    dose_time = str(profile.get("dose_time") or "12:00")
    try:
        hh, mm = (int(x) for x in dose_time.split(":")[:2])
    except (ValueError, AttributeError):
        hh, mm = 12, 0

    doses = sorted((r for r in taken if r.type == "dose" and not r.meta.get("skipped")),
                   key=lambda r: r.at)
    if doses:
        due = doses[-1].at + timedelta(days=interval)
    else:
        target_weekday = int(profile.get("dose_weekday") or 0)
        start_day = _treatment_start(today, profile, taken)
        delta = (target_weekday - start_day.weekday()) % 7
        due = datetime.combine(start_day + timedelta(days=delta), time(hh, mm))
        # 计划时间已经过去就顺延，直到落在未来（漏了好几周也能一次算对）
        while due <= now:
            due += timedelta(days=interval)
    due = due.replace(hour=hh, minute=mm, second=0, microsecond=0)

    remaining = due - now
    days = max(0, math.ceil(remaining.total_seconds() / 86400.0)) if remaining.total_seconds() > 0 else 0
    return {
        "due_at": fmt_dt(due),
        "date": due.date().isoformat(),
        "weekday": due.weekday(),
        "weekday_cn": ["星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"][due.weekday()],
        "time": f"{due.hour:02d}:{due.minute:02d}",
        "remaining_days": days,
        "remaining_hours": round(remaining.total_seconds() / 3600.0, 1),
        "is_due_today": due.date() == today and remaining.total_seconds() > 0,
        "overdue": remaining.total_seconds() < 0,
        "dose_mg": float(profile.get("dose_mg") or 0),
        "med_name": str(profile.get("med_name") or ""),
        "interval_days": interval,
    }


def _treatment_start(today: date, profile: dict[str, Any], taken: Sequence[Record]) -> date:
    raw = profile.get("treatment_start")
    if raw:
        try:
            return parse_dt(raw).date()
        except ValueError:
            pass
    doses = sorted((r.at for r in taken if r.type == "dose"), key=lambda d: d)
    if doses:
        return doses[0].date()
    return today


def treatment_start(today: date, profile: dict[str, Any], taken: Sequence[Record]) -> date:
    return _treatment_start(today, profile, taken)


def rolling_average(series: Sequence[dict[str, Any]], window: int = 7) -> list[dict[str, Any]]:
    """体重看趋势时要平滑，否则水重波动盖住真实趋势。"""
    out: list[dict[str, Any]] = []
    values: list[float] = []
    for point in series:
        values.append(float(point["v"]))
        chunk = values[-window:]
        out.append({"t": point["t"], "v": point["v"], "avg": round(sum(chunk) / len(chunk), 2)})
    return out


def percent_of(current: float, target: float) -> int:
    if target <= 0:
        return 0
    return max(0, min(999, round(100.0 * current / target)))


def safe_div(a: float, b: float) -> float:
    return a / b if b else 0.0


# ------------------------------------------------------------------ 身高体重指数
#
# 采用**中国成人标准**（WS/T 428-2013），不是 WHO 的国际标准。
# 两者在超重/肥胖的切点上不同：中国标准 24/28，WHO 标准 25/30。
# 对中国用户来说，用中国标准才和体检报告、门诊结论对得上——
# 比如 BMI 26 在 WHO 标准下只是"超重"，在中国标准下同样是超重，
# 但 BMI 28 在 WHO 下算超重、在中国标准下已经是肥胖，这个差别会影响
# 医生怎么判断，也会影响部分地区的用药报销条件。
#
# 数据来源：中国肥胖问题工作组（WGOC）建议，卫生部《成人体重判定》WS/T 428-2013。
BMI_CATEGORIES_CN: tuple[tuple[float, str, str], ...] = (
    (18.5, "偏瘦", "体重低于正常范围"),
    (24.0, "正常", "处于健康范围"),
    (28.0, "超重", "高于正常范围"),
    (float("inf"), "肥胖", "达到肥胖标准"),
)


def bmi_of(weight_kg: float | None, height_cm: float | None) -> float | None:
    """BMI = 体重(kg) / 身高(m)²。

    两个输入任一缺失或非法都返回 None——**不猜、不用默认身高**。
    用默认身高算出来的 BMI 是编的，比不显示更糟。
    """
    if not weight_kg or not height_cm:
        return None
    try:
        w = float(weight_kg)
        h = float(height_cm)
    except (TypeError, ValueError):
        return None
    if w <= 0 or h <= 0:
        return None
    meters = h / 100.0
    if meters < 0.5 or meters > 2.5:      # 明显是填错了（比如把 175 写成 17.5）
        return None
    return w / (meters * meters)


def bmi_category(bmi: float | None) -> dict[str, Any]:
    """按中国成人标准给出分级。未计算出来时如实返回 unknown。"""
    if bmi is None:
        return {"key": "unknown", "label": "未计算", "note": "填了身高才能算"}
    for limit, label, note in BMI_CATEGORIES_CN:
        if bmi < limit:
            key = {"偏瘦": "underweight", "正常": "normal", "超重": "overweight", "肥胖": "obese"}[label]
            return {"key": key, "label": label, "note": note}
    return {"key": "obese", "label": "肥胖", "note": "达到肥胖标准"}


def healthy_weight_range(height_cm: float | None) -> dict[str, float] | None:
    """该身高对应的正常体重区间（BMI 18.5–23.9，取到一位小数）。

    这个比单看 BMI 数字更有用：用户想知道的是"我该到多少公斤"，
    而不是"我的 BMI 是 46.8"。
    """
    if not height_cm:
        return None
    try:
        h = float(height_cm) / 100.0
    except (TypeError, ValueError):
        return None
    if h < 0.5 or h > 2.5:
        return None
    return {
        "min_kg": round(18.5 * h * h, 1),
        "max_kg": round(23.9 * h * h, 1),
    }


def bmi_payload(
    height_cm: float | None,
    latest_weight_kg: float | None,
    start_weight_kg: float | None = None,
    target_weight_kg: float | None = None,
) -> dict[str, Any]:
    """给界面用的 BMI 汇总。所有数字都由上面两个函数算出来，不在这里另起口径。"""
    current = bmi_of(latest_weight_kg, height_cm)
    start = bmi_of(start_weight_kg, height_cm)
    target = bmi_of(target_weight_kg, height_cm)
    return {
        "height_cm": height_cm,
        "weight_kg": latest_weight_kg,
        "bmi": round(current, 1) if current is not None else None,
        "category": bmi_category(current),
        "start_bmi": round(start, 1) if start is not None else None,
        "bmi_change": round(current - start, 1) if current is not None and start is not None else None,
        "target_bmi": round(target, 1) if target is not None else None,
        "target_category": bmi_category(target) if target is not None else None,
        "healthy_range": healthy_weight_range(height_cm),
        # 需要多少公斤才能进正常范围：给一个明确的落点，比只报 BMI 有用
        "to_healthy_kg": (
            round(latest_weight_kg - healthy_weight_range(height_cm)["max_kg"], 1)
            if latest_weight_kg and healthy_weight_range(height_cm) and
            latest_weight_kg > healthy_weight_range(height_cm)["max_kg"]
            else None
        ),
        "standard": "中国成人标准 WS/T 428-2013（超重 ≥24，肥胖 ≥28）",
    }
