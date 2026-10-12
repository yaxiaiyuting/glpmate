"""HiTrack 解析器测试。

这里的用例全部来自**真机实测**（华为运动健康 17.0.8.300 / WATCH GT 4，
一份 173 点心率 + 446 点 GPS 的真实记录），包括几个踩过的坑：

1. `lbs` 的 `t` 是 10 位**秒**，`h-r` 的 `k` 是 13 位**毫秒** —— 套错单位会让
   446 个 GPS 点挤进 1 秒，算出 6949 km/h 这种荒谬速度。
2. `sec` 的 `sd` 是**厘米**，不是米 —— 按米读会得出 175 公里。
3. 心率合理范围要过滤，否则脏值会污染均值/最大心率。
4. 距离优先用 GPS 自算（可验证），而不是分段字段。
"""

from __future__ import annotations

import pathlib
import sys
import unittest
from datetime import datetime

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

from glpmate.hitrack import (  # noqa: E402
    HiTrackWorkout, find_hitrack_files, haversine_m, parse_name, parse_text, to_records,
)

NAME = "HiTrack_175335748500017533583520001791777564600"

# 真实文件的开头片段（含两种时间戳格式与一个 GPS 点）
SAMPLE = "\n".join([
    "tp=h-r;k=1753357490000;v=84;",
    "tp=h-r;k=1753357495000;v=81;",
    "tp=h-r;k=1753357500000;v=80;",
    "tp=lbs;k=0;lat=43.91250984812367;lon=87.47326493283154;alt=0;t=1753357529;",
    "tp=lbs;k=1;lat=43.91248913585315;lon=87.47332478359719;alt=0;t=1753357530;",
    "tp=sec;sn=1;st=340;sd=100000;sp=340;shr=84;sc=0;",
    "tp=alt;k=1753357490000;v=624.1;",
    "tp=rp;k=1753357490000;gct=1;gia=2;ee=3;",
])


class TestTimeStamps(unittest.TestCase):
    def test_hr_uses_milliseconds(self):
        w = parse_text(SAMPLE, source=NAME)
        self.assertEqual(w.heart_rate[0].at, datetime(2025, 7, 24, 19, 44, 50))

    def test_gps_uses_seconds_not_milliseconds(self):
        """回归测试：GPS 的 t 是 10 位秒。套成毫秒会让所有点挤进 1 秒。"""
        w = parse_text(SAMPLE, source=NAME)
        self.assertEqual(len(w.track), 2)
        span = (w.track[-1].at - w.track[0].at).total_seconds()
        self.assertEqual(span, 1.0)
        self.assertEqual(w.track[0].at, datetime(2025, 7, 24, 19, 45, 29))
        # 若误按毫秒解析，年份会落到 1970
        self.assertGreater(w.track[0].at.year, 2000)


class TestUnits(unittest.TestCase):
    def test_split_distance_is_centimetres(self):
        """回归测试：sec.sd = 100000 是 1000 米，不是 100000 米。"""
        w = parse_text(SAMPLE, source=NAME)
        self.assertAlmostEqual(w.distance_from_splits(), 1000.0, places=6)

    def test_gps_distance_is_preferred_over_splits(self):
        w = parse_text(SAMPLE, source=NAME)
        self.assertIsNotNone(w.distance_from_gps())
        self.assertEqual(w.distance_m(), w.distance_from_gps())

    def test_haversine_known_distance(self):
        # 纬度相差 0.001° ≈ 111.2 米
        d = haversine_m(43.9125, 87.4732, 43.9135, 87.4732)
        self.assertAlmostEqual(d, 111.2, delta=1.0)
        self.assertAlmostEqual(haversine_m(0, 0, 0, 0), 0.0, places=6)

    def test_gps_jumps_are_dropped(self):
        """定位漂移产生的跳点（>200m）不该计入距离。"""
        lines = ["tp=lbs;k=0;lat=43.0;lon=87.0;alt=0;t=1753357529;",
                 "tp=lbs;k=1;lat=45.0;lon=89.0;alt=0;t=1753357530;",   # 跳了几百公里
                 "tp=lbs;k=2;lat=43.0005;lon=87.0;alt=0;t=1753357531;"]
        w = parse_text("\n".join(lines), source=NAME)
        d = w.distance_from_gps()
        self.assertLess(d, 200.0)      # 只保留那段 55 米左右的真实位移


class TestSanity(unittest.TestCase):
    def test_implausible_hr_is_filtered(self):
        lines = ["tp=h-r;k=1753357490000;v=84;", "tp=h-r;k=1753357495000;v=999;",
                 "tp=h-r;k=1753357500000;v=0;", "tp=h-r;k=1753357505000;v=88;"]
        w = parse_text("\n".join(lines), source=NAME)
        self.assertEqual([s.bpm for s in w.heart_rate], [84.0, 88.0])

    def test_hr_stats(self):
        w = parse_text(SAMPLE, source=NAME)
        st = w.hr_stats()
        self.assertEqual(st["count"], 3)
        self.assertEqual(st["min"], 80.0)
        self.assertEqual(st["max"], 84.0)
        self.assertEqual(st["median"], 81.0)
        self.assertAlmostEqual(st["avg"], 81.7, places=1)

    def test_sampling_interval(self):
        w = parse_text(SAMPLE, source=NAME)
        self.assertEqual(w.sampling_interval_seconds(), 5.0)

    def test_empty_input_is_safe(self):
        w = parse_text("", source=NAME)
        self.assertEqual(w.heart_rate, [])
        self.assertIsNone(w.distance_m())
        self.assertEqual(to_records(w), [])
        self.assertEqual(w.hr_stats()["count"], 0)

    def test_unknown_types_are_preserved_not_guessed(self):
        """不认识的类型原样保留，不猜含义——猜错比不做更糟。"""
        w = parse_text(SAMPLE, source=NAME)
        self.assertIn("alt", w.extras)
        self.assertIn("rp", w.extras)
        self.assertEqual(w.extras["rp"][0]["ee"], "3")


class TestName(unittest.TestCase):
    def test_parse_filename(self):
        start, end, tid = parse_name(NAME)
        self.assertEqual(start, datetime(2025, 7, 24, 19, 44, 45))
        self.assertEqual(end, datetime(2025, 7, 24, 19, 59, 12))
        self.assertEqual(tid, "1791777564600")

    def test_bad_filename_is_tolerated(self):
        self.assertEqual(parse_name("random.txt"), (None, None, ""))

    def test_find_files(self):
        import tempfile
        d = pathlib.Path(tempfile.mkdtemp())
        (d / NAME).write_text(SAMPLE)
        (d / "other.txt").write_text("x")
        found = find_hitrack_files(d)
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0].name, NAME)
        self.assertEqual(find_hitrack_files(d / "nope"), [])


class TestToRecords(unittest.TestCase):
    def test_records_shape(self):
        w = parse_text(SAMPLE, source=NAME)
        recs = to_records(w, source="hitrack")
        self.assertEqual(len(recs), 2)
        training = recs[0]
        self.assertEqual(training["type"], "training")
        self.assertEqual(training["unit"], "min")
        self.assertIn("hr_avg", training["meta"])
        self.assertEqual(training["meta"]["hr_samples"], 3)
        self.assertTrue(recs[1]["meta"]["hr_series"])
        # 时间戳必须是本地时间字符串，不带时区后缀
        self.assertNotIn("+", training["at"])

    def test_records_carry_dedup_keys(self):
        """重复导入同一份文件不应该翻倍——每类记录都要带去重键。"""
        w = parse_text(SAMPLE, source=NAME)
        recs = to_records(w, source="hitrack")
        keys = [r["meta"].get("source_key") for r in recs]
        self.assertTrue(all(keys), f"缺少 source_key: {keys}")
        self.assertEqual(len(set(keys)), len(keys))       # 同类之间也不能撞
        self.assertTrue(all(k.startswith("hitrack:") for k in keys))

    def test_no_hr_no_records(self):
        w = parse_text("tp=alt;k=1753357490000;v=1;", source=NAME)
        self.assertEqual(to_records(w), [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
