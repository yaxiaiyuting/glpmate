"""解析华为运动健康的 HiTrack 文件（明文，无需任何逆向）。

## 这东西是什么

`/data/data/com.huawei.health/files/HiTrack_*` 是华为运动健康在**你查看某次运动记录时**
生成的明文运动记录文件。实测（2026-10-12，运动健康 17.0.8.300 / WATCH GT 4）：

- 纯 ASCII 文本，一行一条记录，`键=值;` 分隔，**没有任何加密**
- 单行格式：`tp=<类型>;k=<毫秒时间戳>;v=<数值>;`，GPS 点则是 `lat/lon/alt/t`
- 文件名：`HiTrack_<开始毫秒><结束毫秒><轨迹ID>`

实测到的类型（对同一份文件统计）：

| tp | 含义 | 字段 |
|---|---|---|
| `h-r` | **心率**（本次实测 5 秒一个点） | `k` 毫秒时间戳, `v` bpm |
| `lbs` | GPS 轨迹点 | `lat`, `lon`, `alt`, `t` |
| `rs` / `s-r` / `alt` | 逐点记录（速度/步频、海拔等） | `k`, `v` |
| `rp` | 含配速与心率区间相关量 | `gct/gia/sa/ee/fsp/wsp/hsp/aht` |
| `r-r-h` | 心率区间统计 | `k`, `v` |
| `sec` | 分段（圈）统计 | `sn` 圈号, `sd` 距离, `shr` 该圈平均心率 … |
| `p-m` / `b-p-m` | 运动参数 / 最佳记录 | `k`, `v` |

**重要限制（必须让用户知道）**：HiTrack 只覆盖**你手动记录的运动**（跑步/骑行/步行等），
它**不是** 24 小时连续心率，也没有血氧。24 小时静息心率、血氧那些在加密的
`hihealth_003.db`（SQLCipher）里，本模块拿不到、也不尝试拿。

字段含义里 `rp` 的私有缩写（gct/gia/sa/ee/fsp/wsp/hsp/aht）没有公开文档，
本模块**不猜**：能确定的（心率、GPS、圈统计）才解析，其余原样保留在 extras 里。
"""

from __future__ import annotations

import math
import re
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Iterable, Optional

# 文件名：HiTrack_<开始13位><结束13位><轨迹ID>
NAME_RE = re.compile(r"^HiTrack_(\d{13})(\d{13})(\d+)$")
LINE_RE = re.compile(r"^tp=([a-zA-Z\-_]+);(.*)$")
KV_RE = re.compile(r"([a-zA-Z\-_]+)=([^;]+)")


@dataclass
class HeartRateSample:
    at: datetime
    bpm: float


@dataclass
class TrackPoint:
    at: datetime
    lat: float
    lon: float
    altitude: Optional[float] = None


@dataclass
class Split:
    """分段（圈）统计。字段名按实测键名原样保留，不做推测性改名。

    注意 `distance_m` 在解析后存放的是**原始厘米值**，由 `HiTrackWorkout.distance_m()`
    统一换算；单独读这个字段请自己乘 0.01。
    """
    index: int
    distance_m: Optional[float] = None   # 原始值：厘米
    avg_hr: Optional[float] = None
    raw: dict[str, str] = field(default_factory=dict)


@dataclass
class HiTrackWorkout:
    source: str
    started_at: Optional[datetime] = None
    ended_at: Optional[datetime] = None
    track_id: str = ""
    heart_rate: list[HeartRateSample] = field(default_factory=list)
    track: list[TrackPoint] = field(default_factory=list)
    splits: list[Split] = field(default_factory=list)
    extras: dict[str, list[dict[str, str]]] = field(default_factory=dict)

    # -- 派生指标 --------------------------------------------------------
    @property
    def duration_minutes(self) -> float:
        if self.started_at and self.ended_at:
            return (self.ended_at - self.started_at).total_seconds() / 60.0
        if self.heart_rate:
            return (self.heart_rate[-1].at - self.heart_rate[0].at).total_seconds() / 60.0
        return 0.0

    def hr_stats(self) -> dict[str, Optional[float]]:
        vals = [s.bpm for s in self.heart_rate]
        if not vals:
            return {"count": 0, "min": None, "max": None, "avg": None, "median": None}
        ordered = sorted(vals)
        mid = len(ordered) // 2
        median = ordered[mid] if len(ordered) % 2 else (ordered[mid - 1] + ordered[mid]) / 2
        return {
            "count": len(vals),
            "min": min(vals),
            "max": max(vals),
            "avg": round(sum(vals) / len(vals), 1),
            "median": round(median, 1),
        }

    def sampling_interval_seconds(self) -> Optional[float]:
        """心率采样间隔中位数（用来判断这份数据的密度）。"""
        if len(self.heart_rate) < 2:
            return None
        gaps = sorted((b.at - a.at).total_seconds() for a, b in zip(self.heart_rate, self.heart_rate[1:]))
        return gaps[len(gaps) // 2]

    def distance_from_gps(self) -> Optional[float]:
        """用 GPS 点自己算累计距离（相邻点大圆距离求和，剔除 >200m 的跳点）。

        这是**可验证**的算法：坐标是经纬度，距离用 haversine 算，没有单位歧义。
        优先用它，而不是 `sec.sd`。
        """
        if len(self.track) < 2:
            return None
        total = 0.0
        for a, b in zip(self.track, self.track[1:]):
            d = haversine_m(a.lat, a.lon, b.lat, b.lon)
            if d < 200:            # 跳点（定位漂移）不计入
                total += d
        # 全是跳点时返回 0.0 而不是 None：0 米是**结论**（数据不可用），
        # None 是"没有数据"，两者含义不同，混用会让上层判断出错
        return total

    def distance_from_splits(self) -> Optional[float]:
        """分段距离合计（米），按**厘米**换算。

        实测 `sec` 的 `sd` 是厘米（100000 + 75574 → 1755.74 m）。同一个文件里
        GPS 自算是 1930 m，两者差约 9%——GPS 走的是折线、分段值可能只记净位移，
        所以以 GPS 为准，这个值只作为没有 GPS 数据时的后备。
        """
        total_cm = sum(s.distance_m or 0.0 for s in self.splits)
        return total_cm / 100.0 if total_cm else None

    def distance_m(self) -> Optional[float]:
        gps = self.distance_from_gps()
        if gps is not None and gps > 0:
            return gps
        return self.distance_from_splits()


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """两点大圆距离（米）。"""
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def _ms(value: str) -> Optional[datetime]:
    """13 位毫秒时间戳（`h-r`/`alt`/`rp` 等用的 `k` 字段）。"""
    try:
        return datetime.fromtimestamp(int(value) / 1000.0).replace(microsecond=0)
    except (ValueError, OSError, OverflowError):
        return None


def _epoch(value: str) -> Optional[datetime]:
    """按**位数**判断时间戳单位。

    实测坑：`h-r` 的 `k` 是 13 位毫秒，而 `lbs` 的 `t` 是 10 位**秒**。
    一开始按毫秒解 `t`，446 个 GPS 点全挤在 1 秒内——距离算出来 6949 km/h。
    所以这里必须看位数，不能想当然。
    """
    text = str(value).strip()
    if not text.isdigit():
        return None
    try:
        n = int(text)
    except ValueError:
        return None
    seconds = n / 1000.0 if len(text) >= 12 else float(n)
    try:
        return datetime.fromtimestamp(seconds).replace(microsecond=0)
    except (OSError, OverflowError, ValueError):
        return None


def parse_name(path: Path | str) -> tuple[Optional[datetime], Optional[datetime], str]:
    """从文件名解出开始/结束时间与轨迹 ID。"""
    name = Path(path).stem
    m = NAME_RE.match(name)
    if not m:
        return None, None, ""
    return _ms(m.group(1)), _ms(m.group(2)), m.group(3)


def parse_text(text: str, source: str = "") -> HiTrackWorkout:
    workout = HiTrackWorkout(source=source)
    start, end, track_id = parse_name(source)
    workout.started_at, workout.ended_at, workout.track_id = start, end, track_id

    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        m = LINE_RE.match(line)
        if not m:
            continue
        kind, rest = m.group(1), m.group(2)
        fields = dict(KV_RE.findall(rest))

        if kind == "h-r":
            at = _ms(fields.get("k", ""))
            try:
                bpm = float(fields.get("v", ""))
            except ValueError:
                continue
            if at and 20 <= bpm <= 260:          # 明显不合理的值直接丢，别让它污染统计
                workout.heart_rate.append(HeartRateSample(at, bpm))
        elif kind == "lbs":
            at = _epoch(fields.get("t", "")) or _ms(fields.get("k", ""))
            try:
                lat, lon = float(fields["lat"]), float(fields["lon"])
            except (KeyError, ValueError):
                continue
            if at and -90 <= lat <= 90 and -180 <= lon <= 180:
                alt = None
                try:
                    alt = float(fields["alt"])
                except (KeyError, ValueError):
                    pass
                workout.track.append(TrackPoint(at, lat, lon, alt))
        elif kind == "sec":
            split = Split(index=int(fields.get("sn") or len(workout.splits) + 1), raw=fields)
            try:
                split.distance_m = float(fields["sd"])
            except (KeyError, ValueError):
                pass
            try:
                split.avg_hr = float(fields["shr"])
            except (KeyError, ValueError):
                pass
            workout.splits.append(split)
        else:
            # 不认识的类型原样留着：宁可保留原始键值，也不猜含义后写错
            workout.extras.setdefault(kind, []).append(fields)

    workout.heart_rate.sort(key=lambda s: s.at)
    workout.track.sort(key=lambda s: s.at)
    workout.splits.sort(key=lambda s: s.index)
    return workout


def parse_file(path: Path | str) -> HiTrackWorkout:
    """解析文件。source 用真实文件名（去掉目录），track_id 依赖文件名里的数字。"""
    p = Path(path)
    return parse_text(p.read_text(encoding="utf-8", errors="replace"), source=p.name)


def find_hitrack_files(root: Path | str) -> list[Path]:
    """在目录里找 HiTrack 文件（华为就放在 files/ 根下，不递归太深）。"""
    base = Path(root)
    if not base.is_dir():
        return []
    return sorted(p for p in base.glob("HiTrack_*") if p.is_file())


# ------------------------------------------------------------------ 转换
def to_records(workout: HiTrackWorkout, source: str = "hitrack") -> list[dict[str, Any]]:
    """转成 glpmate-lite 的记录格式。

    只产出两类：`training`（整次运动的汇总）与 `heart_rate`（运动期间的心率，
    折成 min/avg/max 一条，而不是把 173 个点灌成 173 条记录——那会把日视图刷爆）。
    """
    records: list[dict[str, Any]] = []
    if not workout.heart_rate and not workout.splits:
        return records
    at = workout.started_at or (workout.heart_rate[0].at if workout.heart_rate else None)
    if at is None:
        return records
    stats = workout.hr_stats()
    distance = workout.distance_m()
    # 去重键：同一个运动容易生成多份 HiTrack（反复查看，或 k= 序号不同），
    # 用「轨迹 ID + 起点时刻 + 用途」做键，重复导入会被后端跳过
    base_key = f"hitrack:{workout.track_id or 'unknown'}:{at.isoformat()}"

    records.append({
        "type": "training",
        "at": at.replace(microsecond=0).isoformat(),
        "value": round(workout.duration_minutes, 1),
        "unit": "min",
        "meta": {
            "desc": "运动记录（HiTrack 导入）",
            "source": source,
            "source_key": base_key + ":summary",
            "track_id": workout.track_id,
            "duration_min": round(workout.duration_minutes, 1),
            "distance_m": round(distance) if distance else None,
            "hr_avg": stats["avg"],
            "hr_min": stats["min"],
            "hr_max": stats["max"],
            "hr_samples": stats["count"],
            "hr_interval_s": workout.sampling_interval_seconds(),
            "gps_points": len(workout.track),
            "splits": len(workout.splits),
        },
    })

    if stats["avg"] is not None:
        records.append({
            "type": "side_effect",     # 暂用既有类型承载"运动心率"以免改后端枚举
            "at": at.replace(microsecond=0).isoformat(),
            "value": stats["avg"],
            "unit": "bpm",
            "meta": {
                "label": "运动心率均值",
                "severity": 1,
                "source_key": base_key + ":hr",
                "desc": f"{stats['count']} 点 · {stats['min']:.0f}–{stats['max']:.0f} bpm",
                "source": source,
                "hr_series": [
                    {"t": s.at.isoformat(), "v": s.bpm} for s in workout.heart_rate
                ],
            },
        })
    return records
