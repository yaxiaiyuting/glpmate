"""SQLite 存储层：连接管理、建表、以及全部读写操作。

只用标准库 sqlite3。每个线程一个连接（ThreadingHTTPServer 是多线程的），
开 WAL 以便读写并发，外键打开以免留下孤儿数据。
"""

from __future__ import annotations

import json
import sqlite3
import threading
import time
from datetime import date, datetime, timedelta, time as dtime
from pathlib import Path
from typing import Any, Iterable, Optional, Sequence

from . import config as cfg
from . import drugs
from .nutrition import nutrition_payload, weight_forecast
from .models import (
    Record,
    daily_totals,
    day_bounds,
    day_index,
    fmt_dt,
    bmi_payload,
    level_curve,
    next_dose_info,
    normalize_payload,
    parse_dt,
    project_events,
    rolling_average,
    steady_state_trough,
    theoretical_max,
    summarize,
    treatment_start,
)

SCHEMA = """
CREATE TABLE IF NOT EXISTS kv (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS records (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    type    TEXT NOT NULL,
    at      TEXT NOT NULL,
    day     TEXT NOT NULL,
    value   REAL,
    unit    TEXT DEFAULT '',
    meta    TEXT NOT NULL DEFAULT '{}',
    source  TEXT DEFAULT 'manual',
    created TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_records_day  ON records(day);
CREATE INDEX IF NOT EXISTS idx_records_type ON records(type, at);

CREATE TABLE IF NOT EXISTS coach_messages (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    role    TEXT NOT NULL,
    at      TEXT NOT NULL,
    day     TEXT NOT NULL,
    text    TEXT NOT NULL,
    card    TEXT,
    meta    TEXT NOT NULL DEFAULT '{}'
);
CREATE INDEX IF NOT EXISTS idx_coach_day ON coach_messages(day);

CREATE TABLE IF NOT EXISTS pending_cards (
    id        TEXT PRIMARY KEY,
    kind      TEXT NOT NULL,
    payload   TEXT NOT NULL,
    status    TEXT NOT NULL DEFAULT 'pending',
    created   TEXT NOT NULL,
    record_id INTEGER
);
"""


class Database:
    """极简连接池：threading.local 每线程一个连接。"""

    def __init__(self, path: Path | str):
        self.path = str(path)
        if self.path != ":memory:":
            Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        self._local = threading.local()
        self._lock = threading.Lock()
        self.init_schema()

    # -- 连接 ---------------------------------------------------------------
    @property
    def conn(self) -> sqlite3.Connection:
        conn = getattr(self._local, "conn", None)
        if conn is None:
            conn = sqlite3.connect(self.path, timeout=15.0, isolation_level=None)
            conn.row_factory = sqlite3.Row
            conn.execute("PRAGMA journal_mode=WAL")
            conn.execute("PRAGMA synchronous=NORMAL")
            conn.execute("PRAGMA foreign_keys=ON")
            conn.execute("PRAGMA busy_timeout=15000")
            self._local.conn = conn
        return conn

    def init_schema(self) -> None:
        with self._lock:
            self.conn.executescript(SCHEMA)

    def execute(self, sql: str, params: Sequence[Any] = ()) -> sqlite3.Cursor:
        return self.conn.execute(sql, params)

    def query(self, sql: str, params: Sequence[Any] = ()) -> list[sqlite3.Row]:
        return list(self.conn.execute(sql, params))

    def one(self, sql: str, params: Sequence[Any] = ()) -> Optional[sqlite3.Row]:
        cur = self.conn.execute(sql, params)
        return cur.fetchone()

    def close(self) -> None:
        conn = getattr(self._local, "conn", None)
        if conn is not None:
            conn.close()
            self._local.conn = None


def _row_to_record(row: sqlite3.Row) -> Record:
    try:
        meta = json.loads(row["meta"] or "{}")
    except json.JSONDecodeError:
        meta = {}
    return Record(
        id=row["id"],
        type=row["type"],
        at=parse_dt(row["at"]),
        value=row["value"],
        unit=row["unit"] or "",
        meta=meta,
    )


class Store:
    """所有业务读写都经过这里，HTTP 层不直接碰 SQL。"""

    def __init__(self, path: Path | str | None = None):
        self.db = Database(path or cfg.DEFAULT_DB)

    # ---------------------------------------------------------------- 键值
    def get_kv(self, key: str, default: Any = None) -> Any:
        row = self.db.one("SELECT value FROM kv WHERE key = ?", (key,))
        if row is None:
            return default
        try:
            return json.loads(row["value"])
        except json.JSONDecodeError:
            return default

    def set_kv(self, key: str, value: Any) -> None:
        self.db.execute(
            "INSERT INTO kv(key, value) VALUES(?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            (key, json.dumps(value, ensure_ascii=False)),
        )

    # ---------------------------------------------------------------- 配置
    # drugs.py 管的字段：没被显式覆盖时，一律以药物参数表为准
    PK_KEYS = ("med_name", "half_life_hours", "tmax_hours", "dose_interval_days")

    def profile(self) -> dict[str, Any]:
        data = dict(cfg.DEFAULT_PROFILE)
        stored = self.get_kv("profile", {}) or {}
        data.update({k: v for k, v in stored.items() if v is not None or k in stored})
        # 药物参数表是这几个字段的事实来源：只有用户显式改过（记在 pk_overrides）才保留旧值。
        # 否则改了 drugs.py 里的说明书参数，老用户的库里还留着过期数字。
        pk = drugs.pk_profile(str(data.get("drug_id") or drugs.DEFAULT_DRUG))
        overrides = set(stored.get("pk_overrides") or []) if isinstance(stored, dict) else set()
        for key in self.PK_KEYS:
            if key not in overrides:
                data[key] = pk[key]
        return data

    def save_profile(self, patch: dict[str, Any]) -> dict[str, Any]:
        data = self.profile()
        allowed = set(cfg.DEFAULT_PROFILE) | {"targets", "schedule"}
        # 换药时先把该药的说明书参数铺上，patch 里的显式值随后覆盖
        new_drug = patch.get("drug_id")
        if new_drug and new_drug != data.get("drug_id"):
            pk = drugs.pk_profile(str(new_drug))
            for key, value in pk.items():
                if key in allowed:
                    data[key] = value
            data["dose_mg"] = pk["dose_mg"]
            data["pk_overrides"] = []          # 换药后不再沿用上一支药的覆盖值
        for key, value in patch.items():
            if key in allowed:
                data[key] = value
                if key in self.PK_KEYS:
                    # 显式改过药代参数：记下来，之后 profile() 不再用参数表覆盖它
                    overrides = set(data.get("pk_overrides") or [])
                    overrides.add(key)
                    data["pk_overrides"] = sorted(overrides)
        self.set_kv("profile", data)
        return data

    def drug(self) -> dict[str, Any]:
        """当前药物的完整参数（含出处），供界面"来源"弹窗与教练上下文使用。"""
        return drugs.get(str(self.profile().get("drug_id") or drugs.DEFAULT_DRUG))

    def targets(self) -> dict[str, float]:
        """每日目标。

        优先级（从低到高）：默认值 < profile.targets < profile.water_target_l。
        **支持 targets.weight_goal**：以前它被 DEFAULT_TARGETS 的白名单过滤掉，
        导致"目标体重"改了不生效（前端只能改存到 profile.target_weight_kg 绕过）。
        这里补上，并且保留这条注释——静默丢字段是最难查的一类 bug。
        """
        data = dict(cfg.DEFAULT_TARGETS)
        prof = self.profile()
        if isinstance(prof.get("targets"), dict):
            for key, value in prof["targets"].items():
                if key in data or key == "weight_goal":
                    data[key] = value
        # 升是权威单位（界面按升输入），放最后确保它赢
        if prof.get("water_target_l"):
            data["water_ml"] = float(prof["water_target_l"]) * 1000.0
        if prof.get("target_weight_kg") and "weight_goal" not in data:
            data["weight_goal"] = float(prof["target_weight_kg"])
        return data

    def schedule(self) -> list[dict[str, Any]]:
        prof = self.profile()
        if isinstance(prof.get("schedule"), list) and prof["schedule"]:
            return prof["schedule"]
        return list(cfg.DEFAULT_SCHEDULE)

    def settings(self, mask: bool = True) -> dict[str, Any]:
        data = {
            "ai_base_url": cfg.DEFAULT_AI_BASE_URL,
            "ai_model": cfg.DEFAULT_AI_MODEL,
            "ai_api_key": "",
            "vision_base_url": cfg.DEFAULT_VISION_BASE_URL,
            "vision_model": cfg.DEFAULT_VISION_MODEL,
            "vision_api_key": "",
            "daily_quota": cfg.DEFAULT_DAILY_QUOTA,
            "temperature": cfg.DEFAULT_TEMPERATURE,
            "timeout_seconds": cfg.DEFAULT_TIMEOUT_SECONDS,
            "display_name": self.profile().get("display_name") or "朋友",
        }
        data.update(self.get_kv("settings", {}) or {})
        key = str(data.get("ai_api_key") or "")
        vkey = str(data.get("vision_api_key") or "")
        data["ai_api_key_set"] = bool(key)
        data["vision_api_key_set"] = bool(vkey)
        if mask:
            data["ai_api_key"] = _mask(key)
            data["vision_api_key"] = _mask(vkey)
        return data

    def save_settings(self, patch: dict[str, Any]) -> dict[str, Any]:
        data = self.get_kv("settings", {}) or {}
        for key, value in patch.items():
            if key in ("ai_api_key_set", "vision_api_key_set"):
                continue
            if key in ("ai_api_key", "vision_api_key"):
                # 约定：null/缺省 = 不改动；"" = 明确清除；带省略号的掩码 = 前端原样回传，不改动
                if value is None:
                    continue
                if isinstance(value, str) and "…" in value:
                    continue
                if isinstance(value, str) and value == "":
                    data[key] = ""          # 用户点了"清除密钥"
                    continue
            data[key] = value
        self.set_kv("settings", data)
        if isinstance(patch.get("display_name"), str) and patch["display_name"].strip():
            self.save_profile({"display_name": patch["display_name"].strip()})
        return self.settings()

    def ai_config(self) -> dict[str, Any]:
        s = self.settings(mask=False)
        return {
            "base_url": s.get("ai_base_url") or cfg.DEFAULT_AI_BASE_URL,
            "model": s.get("ai_model") or cfg.DEFAULT_AI_MODEL,
            "api_key": s.get("ai_api_key") or "",
            "temperature": float(s.get("temperature") or cfg.DEFAULT_TEMPERATURE),
            "timeout": float(s.get("timeout_seconds") or cfg.DEFAULT_TIMEOUT_SECONDS),
            "vision_base_url": s.get("vision_base_url") or "",
            "vision_model": s.get("vision_model") or "",
            "vision_api_key": s.get("vision_api_key") or s.get("ai_api_key") or "",
        }

    # ---------------------------------------------------------------- 记录
    def add_record(self, payload: dict[str, Any], source: str = "manual") -> Record:
        norm = normalize_payload(payload)
        now = fmt_dt(datetime.now())
        cur = self.db.execute(
            "INSERT INTO records(type, at, day, value, unit, meta, source, created) "
            "VALUES(?,?,?,?,?,?,?,?)",
            (norm["type"], fmt_dt(norm["at"]), norm["at"].date().isoformat(), norm["value"],
             norm["unit"], json.dumps(norm["meta"], ensure_ascii=False), source, now),
        )
        rid = int(cur.lastrowid or 0)
        rec = self.get_record(rid)
        assert rec is not None
        return rec

    def get_record(self, rid: int) -> Optional[Record]:
        row = self.db.one("SELECT * FROM records WHERE id = ?", (rid,))
        return _row_to_record(row) if row else None

    def update_record(self, rid: int, patch: dict[str, Any]) -> Optional[Record]:
        current = self.get_record(rid)
        if current is None:
            return None
        merged = {
            "type": patch.get("type", current.type),
            "at": patch.get("at", fmt_dt(current.at)),
            "value": patch.get("value", current.value),
            "unit": patch.get("unit", current.unit),
            "meta": {**current.meta, **(patch.get("meta") or {})},
        }
        norm = normalize_payload(merged)
        self.db.execute(
            "UPDATE records SET type=?, at=?, day=?, value=?, unit=?, meta=? WHERE id=?",
            (norm["type"], fmt_dt(norm["at"]), norm["at"].date().isoformat(), norm["value"],
             norm["unit"], json.dumps(norm["meta"], ensure_ascii=False), rid),
        )
        return self.get_record(rid)

    def delete_record(self, rid: int) -> bool:
        cur = self.db.execute("DELETE FROM records WHERE id = ?", (rid,))
        return cur.rowcount > 0

    def records(self, start: Optional[date] = None, end: Optional[date] = None,
                types: Optional[Sequence[str]] = None, limit: int = 2000,
                order: str = "desc") -> list[Record]:
        sql = "SELECT * FROM records WHERE 1=1"
        params: list[Any] = []
        if start:
            sql += " AND day >= ?"
            params.append(start.isoformat())
        if end:
            sql += " AND day <= ?"
            params.append(end.isoformat())
        if types:
            sql += f" AND type IN ({','.join('?' * len(types))})"
            params.extend(types)
        sql += f" ORDER BY at {'DESC' if order == 'desc' else 'ASC'} LIMIT ?"
        params.append(limit)
        return [_row_to_record(r) for r in self.db.query(sql, params)]

    def has_source_key(self, key: str) -> bool:
        """meta.source_key 是否已存在（批量导入去重用）。"""
        if not key:
            return False
        row = self.db.one(
            "SELECT 1 FROM records WHERE json_extract(meta, '$.source_key') = ? LIMIT 1", (key,))
        return row is not None

    def records_on(self, day: date) -> list[Record]:
        return self.records(day, day, order="asc", limit=500)

    def all_dose_records(self) -> list[Record]:
        return self.records(types=["dose"], order="asc", limit=5000)

    # ---------------------------------------------------------------- 汇总
    def bootstrap(self, today: Optional[date] = None) -> dict[str, Any]:
        today = today or date.today()
        profile = self.profile()
        drug = self.drug()
        targets = self.targets()
        doses = self.all_dose_records()
        t_start = treatment_start(today, profile, doses)
        today_recs = self.records_on(today)
        totals = daily_totals(today_recs)

        events = project_events(doses, today, profile, horizon_days=7)
        # 曲线起点：从**首剂**往前 2 天，而不是"末剂往前 10 天"。
        #
        # 为什么改：旧写法在只有一剂记录时会画出 10 天的平线（全是 0，因为更早
        # 根本没有用药记录），那条线占掉半张图、且容易让人误读成"药一直没起效"。
        # 保留 2 天前缀是为了让"打针前是 0"这件事仍然看得见——曲线从 0 起跳这个
        # 视觉信息有价值，但不能用 10 天的空白去换。
        curve_start = (
            doses[0].at - timedelta(days=2)
            if doses
            else datetime.combine(today - timedelta(days=6), dtime(8, 0))
        )
        curve_end = datetime.combine(today + timedelta(days=7), dtime(23, 59))
        series = level_curve(events, profile, curve_start, curve_end, steps_per_day=4) if events else []
        now = datetime.now().replace(microsecond=0)
        half_life = float(profile.get("half_life_hours") or 120.0)
        tmax = float(profile.get("tmax_hours") or 24.0)
        interval = float(profile.get("dose_interval_days") or 7) * 24.0
        baseline = steady_state_trough(events, half_life, interval, tmax)
        top = theoretical_max(events, half_life, interval, tmax)
        from .models import level_at  # 局部导入避免与模块级重名
        mg_now = level_at(now, events, half_life, tmax) if events else 0.0
        # 只算"现在"，不要用未来的点
        pct_peak = round(100.0 * mg_now / baseline, 1) if baseline else 0.0
        pct_max = round(100.0 * mg_now / top, 1) if top else 0.0
        # 趋势用最近 7 天的斜率，而不是整条曲线（曲线含未来计划剂量，会误判成"正在积累"）
        past = [p for p in series if p["t"] <= fmt_dt(now)]
        trend = "持平"
        if len(past) >= 2:
            delta = past[-1]["mg"] - past[0]["mg"]
            trend = "正在积累" if delta > 0.01 else ("正在回落" if delta < -0.01 else "持平")

        annotations: list[dict[str, Any]] = []
        for ev in events[:20]:
            annotations.append({
                "t": fmt_dt(ev.at),
                "label": "计划剂量" if ev.planned else "已注射",
                "mg": ev.mg,
                "planned": ev.planned,
            })

        completed = [self.record_view(r, profile) for r in today_recs]
        weights = self.weight_series(today, days=90)
        start_weight = profile.get("start_weight_kg")
        if start_weight is None and weights:
            start_weight = weights[0]["v"]
        latest_weight = weights[-1]["v"] if weights else None
        delta = round(latest_weight - float(start_weight), 1) if (latest_weight and start_weight) else 0.0
        week = self.week_summary(today, profile)

        return {
            "today": today.isoformat(),
            "weekday": today.weekday(),
            "day_index": max(1, day_index(t_start, today)),
            "profile": {**profile, "start_weight_kg": start_weight},
            "settings": self.settings(mask=True),
            "targets": targets,
            "schedule": self.schedule(),
            # 营养达标：对照《中国居民膳食指南（2022）》的供能比与推荐量。
            # 蛋白质目标按**调整体重**算（见 nutrition.py 的说明），所以要把身高传进去。
            "nutrition": nutrition_payload(
                totals,
                targets,
                latest_weight,
                height_cm=profile.get("height_cm"),
            ),
            # 体重下降预测：用 SURMOUNT-1 的实测终点，而不是只算能量账。
            # 两个数字都给出并解释差异（见 nutrition.weight_forecast 的说明）。
            "weight_forecast": weight_forecast(
                start_weight,
                latest_weight,
                weeks_on_treatment=max(0.0, (today - t_start).days / 7.0),
                dose_mg=float(profile.get("dose_mg") or 0.0),
                deficit_kcal=(
                    (targets["calories"] - totals["kcal"])
                    if targets.get("calories") and totals.get("kcal") else None
                ),
                target_weight_kg=profile.get("target_weight_kg"),
                max_dose_mg=drug.get("max_dose_mg"),
            ),
            "today_stats": {
                "calories": {"v": totals["kcal"], "t": targets["calories"]},
                "protein": {"v": totals["protein_g"], "t": targets["protein_g"]},
                "carbs": {"v": totals["carb_g"], "t": targets["carbs_g"]},
                "fat": {"v": totals["fat_g"], "t": targets["fat_g"]},
                "fiber": {"v": totals["fiber_g"], "t": targets["fiber_g"]},
                "water": {"v": round(totals["water_ml"] / 1000.0, 2), "t": round(targets["water_ml"] / 1000.0, 1)},
                "meals_logged": sum(1 for r in today_recs if r.type == "food"),
                "side_effects": [self.record_view(r, profile) for r in today_recs if r.type == "side_effect"],
                "dose_taken": any(r.type == "dose" and not r.meta.get("skipped") for r in today_recs),
                "training_min": totals["training_min"],
            },
            "next_dose": next_dose_info(today, profile, doses),
            "level": {
                # pct_peak 是界面上的大数字：以稳态谷值（基线）为 100%，稳态峰值约 198%。
                # pct_max 以理论上限为 100%，适合做进度条。字段名保持兼容，含义见 README。
                "pct_peak": pct_peak,
                "now_pct": pct_peak,
                "pct_max": pct_max,
                "current_mg": round(mg_now, 3),
                "baseline_mg": round(baseline, 3),
                "max_mg": round(top, 3),
                "peak_mg": round(top, 3),
                "trend": trend,
                "half_life_hours": half_life,
                "tmax_hours": tmax,
                "drug": {"id": drug["id"], "name_cn": drug["name_cn"], "name_en": drug["name_en"],
                         "brands": drug["brands"], "confidence": drug["confidence"],
                         "steady_state_weeks": drug["steady_state_weeks"],
                         "elimination_after_last_dose": drug["elimination_after_last_dose"],
                         "sources": drug["sources"], "notes": drug["notes"],
                         "verified_on": drugs.VERIFIED_ON},
                "series": series,
                "annotations": annotations,
                "disclaimer": "基于群体药代动力学的估算，并非医疗测量。",
            },
            "weight": {
                "latest": latest_weight,
                "start": start_weight,
                "delta": delta,
                "goal": profile.get("target_weight_kg"),
                "series": weights,
                "series_avg": rolling_average(weights, 7) if weights else [],
            },
            # BMI：一处算好，两个前端都用。身高没填时 bmi 为 None、category 为
            # "未计算"，界面据此提示去填身高，而不是拿默认身高编一个数字出来。
            "bmi": bmi_payload(
                profile.get("height_cm"),
                latest_weight,
                start_weight,
                profile.get("target_weight_kg"),
            ),
            "week": week,
            "completed": completed,
            "quick_types": cfg.QUICK_TYPES,
            "side_effect_chips": cfg.SIDE_EFFECT_CHIPS,
            "coach_quota": self.quota(today),
        }

    def record_view(self, rec: Record, profile: dict[str, Any]) -> dict[str, Any]:
        view = summarize(rec, self.targets(), profile)
        return {
            "id": rec.id,
            "type": rec.type,
            "at": fmt_dt(rec.at),
            "time": rec.at.strftime("%H:%M"),
            "date": rec.day.isoformat(),
            "value": rec.value,
            "unit": rec.unit,
            "meta": rec.meta,
            **view,
        }

    def summary(self, today: Optional[date] = None, period: str = "week") -> dict[str, Any]:
        today = today or date.today()
        start, end = self.period_bounds(today, period)
        totals = {"kcal": 0.0, "protein_g": 0.0, "carb_g": 0.0, "fat_g": 0.0, "fiber_g": 0.0, "water_ml": 0.0}
        days = max(1, (end - start).days + 1)
        per_day: dict[str, float] = {}
        for day in range(days):
            recs = self.records_on(start + timedelta(days=day))
            t = daily_totals(recs)
            for key in totals:
                totals[key] += t[key]
            per_day[(start + timedelta(days=day)).isoformat()] = round(t["kcal"], 1)
        targets = self.targets()
        return {
            "period": period,
            "start": start.isoformat(),
            "end": end.isoformat(),
            "days": days,
            "totals": {k: round(v, 1) for k, v in totals.items()},
            "averages": {
                "kcal_per_day": round(totals["kcal"] / days, 1),
                "protein_per_day": round(totals["protein_g"] / days, 1),
                "water_per_day": round(totals["water_ml"] / days / 1000.0, 2),
            },
            "targets": targets,
            "per_day": per_day,
        }

    def next_dose(self, today: Optional[date] = None) -> dict[str, Any]:
        """下一次剂量信息（教练上下文与今天页共用）。"""
        today = today or date.today()
        return next_dose_info(today, self.profile(), self.all_dose_records())

    def level_summary(self, today: Optional[date] = None) -> dict[str, Any]:
        """体内水平的标量部分（不含曲线），避免为了几个数字算整条时间线。"""
        today = today or date.today()
        profile = self.profile()
        doses = self.all_dose_records()
        events = project_events(doses, today, profile, horizon_days=7)
        half_life = float(profile.get("half_life_hours") or 165.0)
        # tmax 必须显式取出来传给下面几个函数。
        #
        # 这里曾经漏掉这行（改曲线起点时误删），后果是**教练完全不可用**：
        # 每次发消息都在 build_context → level_summary 抛 NameError，
        # 界面只显示「发送失败: name 'tmax' is not defined」。
        # 之所以没被测出来：教练路径需要 API key，原有 81 个测试没有一个碰过它。
        # 现在 tests/test_coach_context.py 会在无 key 的情况下把整条上下文链路跑通。
        tmax = float(profile.get("tmax_hours") or 24.0)
        interval = float(profile.get("dose_interval_days") or 7) * 24.0
        baseline = steady_state_trough(events, half_life, interval, tmax)
        top = theoretical_max(events, half_life, interval, tmax)
        from .models import level_at
        now = datetime.now().replace(microsecond=0)
        mg_now = level_at(now, events, half_life, tmax) if events else 0.0
        # 趋势看最近一次给药前后：刚打完在积累，接近下次给药时在回落
        recent = [(r.at, level_at(r.at, events, half_life, tmax)) for r in doses[-3:]]
        trend = "持平"
        if recent:
            last_dose_at, last_level = recent[-1]
            if mg_now > last_level + 0.01:
                trend = "正在积累"
            elif mg_now < last_level - 0.01:
                trend = "正在回落"
        pct = round(100.0 * mg_now / baseline, 1) if baseline else 0.0
        return {
            "pct_peak": pct,
            "now_pct": pct,
            "pct_max": round(100.0 * mg_now / top, 1) if top else 0.0,
            "current_mg": round(mg_now, 3),
            "baseline_mg": round(baseline, 3),
            "max_mg": round(top, 3),
            "peak_mg": round(top, 3),
            "trend": trend,
            "half_life_hours": half_life,
            "disclaimer": "基于群体药代动力学的估算，并非医疗测量。",
        }

    def week_summary(self, today: date, profile: dict[str, Any]) -> dict[str, Any]:
        start = today - timedelta(days=today.weekday())
        end = start + timedelta(days=6)
        recs = self.records(start, end, limit=5000)
        totals = daily_totals(recs)
        doses = [r for r in recs if r.type == "dose" and not r.meta.get("skipped")]
        weights = [r for r in recs if r.type == "weight"]
        water_days = len({r.day for r in recs if r.type == "water"})
        span = max(1, (min(end, today) - start).days + 1)
        return {
            "start": start.isoformat(),
            "end": end.isoformat(),
            "label": (f"本周 · {start.month}月{start.day}–{end.day}日"
                      if start.month == end.month else
                      f"本周 · {start.month}月{start.day}日–{end.month}月{end.day}日"),
            "weight": {"latest": weights[-1].value if weights else None,
                       "first": weights[0].value if weights else None,
                       "count": len(weights)},
            "protein_per_day": round(totals["protein_g"] / span, 1),
            "doses": {"taken": len(doses), "planned": max(1, round(span / max(1, int(profile.get("dose_interval_days") or 7))))},
            "water_per_day": round(totals["water_ml"] / span / 1000.0, 2),
            "water_days": water_days,
            "training_min": totals["training_min"],
            "side_effects": sum(1 for r in recs if r.type == "side_effect"),
            "week_index": (today - treatment_start(today, profile, self.all_dose_records())).days // 7 + 1,
        }

    @staticmethod
    def period_bounds(today: date, period: str) -> tuple[date, date]:
        if period == "month":
            start = today.replace(day=1)
            nxt = (start + timedelta(days=32)).replace(day=1)
            return start, min(today, nxt - timedelta(days=1))
        start = today - timedelta(days=today.weekday())
        return start, min(today, start + timedelta(days=6))

    def weight_series(self, today: Optional[date] = None, days: int = 90) -> list[dict[str, Any]]:
        today = today or date.today()
        start = today - timedelta(days=days)
        recs = self.records(start, today, types=["weight"], order="asc", limit=2000)
        return [{"t": fmt_dt(r.at), "v": round(float(r.value or 0), 2)} for r in recs]

    def calendar(self, year: int, month: int, profile: dict[str, Any]) -> dict[str, Any]:
        first = date(year, month, 1)
        nxt = (first + timedelta(days=32)).replace(day=1)
        last = nxt - timedelta(days=1)
        recs = self.records(first, last, limit=5000, order="asc")
        days: dict[str, dict[str, Any]] = {}
        for r in recs:
            bucket = days.setdefault(r.day.isoformat(), {"dots": [], "planned": False, "types": {}})
            key = {"dose": "dose", "weight": "weight", "food": "food", "water": "water",
                   "training": "training", "side_effect": "side_effect", "sleep": "sleep"}.get(r.type)
            if key and key not in bucket["dots"]:
                bucket["dots"].append(key)
            bucket["types"][r.type] = bucket["types"].get(r.type, 0) + 1
        # 计划/称重日：按剂量间隔推未来剂量
        interval = max(1, int(profile.get("dose_interval_days") or 7))
        doses = self.all_dose_records()
        if doses:
            cursor = doses[-1].at
            while cursor.date() <= last + timedelta(days=interval):
                cursor = cursor + timedelta(days=interval)
                if first <= cursor.date() <= last:
                    bucket = days.setdefault(cursor.date().isoformat(), {"dots": [], "planned": False, "types": {}})
                    bucket["planned"] = True
        for key in list(days):
            days[key]["dot_colors"] = [cfg.DOT_COLORS.get(d, "#6B757D") for d in days[key]["dots"]]
        return {
            "month": f"{year:04d}-{month:02d}",
            "year": year,
            "month_num": month,
            "month_cn": cfg.MONTH_CN[month - 1],
            "first_weekday": first.weekday(),
            "days_in_month": last.day,
            "days": days,
            "dot_colors": cfg.DOT_COLORS,
        }

    # ---------------------------------------------------------------- 教练
    def quota(self, today: Optional[date] = None) -> dict[str, Any]:
        today = today or date.today()
        key = f"quota:{today.isoformat()}"
        used = int(self.get_kv(key, 0) or 0)
        quota_cfg = self.settings(mask=False)
        limit = int(quota_cfg.get("daily_quota") or cfg.DEFAULT_DAILY_QUOTA)
        return {"used": used, "limit": limit, "remaining": max(0, limit - used),
                "reset_at": f"{today.isoformat()}T23:59:59"}

    def bump_quota(self, today: Optional[date] = None) -> dict[str, Any]:
        today = today or date.today()
        key = f"quota:{today.isoformat()}"
        used = int(self.get_kv(key, 0) or 0) + 1
        self.set_kv(key, used)
        return self.quota(today)

    def add_message(self, role: str, text: str, card: Optional[dict[str, Any]] = None,
                    meta: Optional[dict[str, Any]] = None, at: Optional[datetime] = None) -> dict[str, Any]:
        at = at or datetime.now().replace(microsecond=0)
        cur = self.db.execute(
            "INSERT INTO coach_messages(role, at, day, text, card, meta) VALUES(?,?,?,?,?,?)",
            (role, fmt_dt(at), at.date().isoformat(), text,
             json.dumps(card, ensure_ascii=False) if card else None,
             json.dumps(meta or {}, ensure_ascii=False)),
        )
        return self.get_message(int(cur.lastrowid or 0)) or {}

    def get_message(self, mid: int) -> Optional[dict[str, Any]]:
        row = self.db.one("SELECT * FROM coach_messages WHERE id = ?", (mid,))
        return _row_to_message(row) if row else None

    def messages(self, limit: int = 200, since: Optional[date] = None) -> list[dict[str, Any]]:
        """最近 [limit] 条消息，按时间**升序**返回（最老的在前）。

        ============ 这里曾经取反了（别再改回 ASC + LIMIT）============
        旧写法是 `ORDER BY at ASC, id ASC LIMIT ?`，也就是"按时间升序取前 N 条"
        ——拿到的是**最早**的 N 条，而不是最近的 N 条。

        后果不容易发现但很致命：`reply()` 取 `limit=12` 当对话历史，于是对话
        一旦超过 12 条，教练就再也看不到用户最近说的话，只会拿十几轮之前的
        上下文回答。表面现象是"教练答非所问"，很难联想到是取数顺序的问题。

        正确做法是先按时间**降序**取最近的 N 条，再在外层翻回升序，
        这样既能拿到最近的，顺序也对（提示词要求时间从早到晚）。
        """
        inner = "SELECT * FROM coach_messages WHERE 1=1"
        params: list[Any] = []
        if since:
            inner += " AND day >= ?"
            params.append(since.isoformat())
        inner += " ORDER BY at DESC, id DESC LIMIT ?"
        params.append(limit)
        sql = f"SELECT * FROM ({inner}) ORDER BY at ASC, id ASC"
        return [_row_to_message(r) for r in self.db.query(sql, params)]

    def clear_messages(self) -> int:
        cur = self.db.execute("DELETE FROM coach_messages")
        return cur.rowcount

    # ---------------------------------------------------------------- 待确认卡片
    def create_card(self, kind: str, payload: dict[str, Any]) -> dict[str, Any]:
        cid = f"card_{int(time.time() * 1000)}_{len(payload)}"
        self.db.execute(
            "INSERT INTO pending_cards(id, kind, payload, status, created) VALUES(?,?,?,?,?)",
            (cid, kind, json.dumps(payload, ensure_ascii=False), "pending", fmt_dt(datetime.now())),
        )
        return {"id": cid, "kind": kind, "payload": payload, "status": "pending"}

    def get_card(self, cid: str) -> Optional[dict[str, Any]]:
        row = self.db.one("SELECT * FROM pending_cards WHERE id = ?", (cid,))
        if not row:
            return None
        return {"id": row["id"], "kind": row["kind"], "status": row["status"],
                "payload": json.loads(row["payload"] or "{}"), "record_id": row["record_id"]}

    def confirm_card(self, cid: str, action: str = "save") -> dict[str, Any]:
        card = self.get_card(cid)
        if card is None:
            raise KeyError("卡片不存在或已过期")
        if card["status"] != "pending":
            return {"ok": True, "status": card["status"], "record_ids": [], "already": True}
        record_ids: list[int] = []
        if action == "save":
            payload = card["payload"]
            recs = payload.get("records") or ([payload["record"]] if payload.get("record") else [])
            for rec in recs:
                created = self.add_record(rec, source="coach")
                if created.id:
                    record_ids.append(created.id)
        self.db.execute("UPDATE pending_cards SET status = ?, record_id = ? WHERE id = ?",
                        ("saved" if action == "save" else "discarded",
                         record_ids[0] if record_ids else None, cid))
        # 同步更新聊天里那张卡片的状态
        for msg in self.messages(limit=200):
            if msg.get("card") and msg["card"].get("id") == cid:
                card_state = dict(msg["card"])
                card_state["status"] = "saved" if action == "save" else "discarded"
                self.db.execute("UPDATE coach_messages SET card = ? WHERE id = ?",
                                (json.dumps(card_state, ensure_ascii=False), msg["id"]))
        return {"ok": True, "status": "saved" if action == "save" else "discarded",
                "record_ids": record_ids}

    # ---------------------------------------------------------------- 导入导出
    def export_all(self) -> dict[str, Any]:
        return {
            "app": cfg.APP_NAME,
            "version": __import__("glpmate").__version__,
            "exported_at": fmt_dt(datetime.now()),
            "kv": {row["key"]: json.loads(row["value"]) for row in self.db.query("SELECT * FROM kv")},
            "records": [
                {"type": r.type, "at": fmt_dt(r.at), "value": r.value, "unit": r.unit, "meta": r.meta,
                 "source": row["source"]}
                for r, row in ((_row_to_record(x), x) for x in self.db.query("SELECT * FROM records ORDER BY at ASC"))
            ],
            "coach_messages": self.messages(limit=5000),
        }

    def import_all(self, payload: dict[str, Any], replace: bool = False) -> dict[str, Any]:
        imported = {"records": 0, "messages": 0, "kv": 0}
        if replace:
            self.db.execute("DELETE FROM records")
            self.db.execute("DELETE FROM coach_messages")
            self.db.execute("DELETE FROM kv WHERE key IN ('profile', 'settings')")
        for key, value in (payload.get("kv") or {}).items():
            if key.startswith("quota:"):
                continue
            self.set_kv(key, value)
            imported["kv"] += 1
        for rec in payload.get("records") or []:
            try:
                self.add_record(rec, source=str(rec.get("source") or "import"))
                imported["records"] += 1
            except (ValueError, TypeError):
                continue
        for msg in payload.get("coach_messages") or []:
            if msg.get("role") and msg.get("text"):
                self.add_message(str(msg["role"]), str(msg["text"]), msg.get("card"),
                                 at=parse_dt(msg.get("at")))
                imported["messages"] += 1
        return {"ok": True, "imported": imported}

    def wipe(self, keep_settings: bool = True) -> None:
        self.db.execute("DELETE FROM records")
        self.db.execute("DELETE FROM coach_messages")
        self.db.execute("DELETE FROM pending_cards")
        self.db.execute("DELETE FROM kv WHERE key LIKE 'quota:%'")
        if not keep_settings:
            self.db.execute("DELETE FROM kv")


def _row_to_message(row: sqlite3.Row) -> dict[str, Any]:
    try:
        card = json.loads(row["card"]) if row["card"] else None
    except json.JSONDecodeError:
        card = None
    try:
        meta = json.loads(row["meta"] or "{}")
    except json.JSONDecodeError:
        meta = {}
    return {
        "id": row["id"],
        "role": row["role"],
        "text": row["text"],
        "at": row["at"],
        "time": str(row["at"])[11:16],
        "card": card,
        "meta": meta,
    }


def _mask(key: str) -> str:
    if not key:
        return ""
    if len(key) <= 8:
        return "…"
    return f"{key[:3]}…{key[-4:]}"
