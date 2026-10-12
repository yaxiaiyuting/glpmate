"""纯逻辑单元测试：python3 tests/test_core.py（不需要 pytest，也没有网络请求）。

覆盖三处最容易悄悄出错的地方：
  1. 药代动力学估算——算错了界面还是很好看，但会误导人；
  2. 记录归一化——前端各种传法都要落到同一套字段；
  3. 安全护栏——漏拦调药意图、或者误拦正常提问，都是实际问题。
"""

from __future__ import annotations

import math
import sys
import pathlib
import tempfile
import unittest
from datetime import date, datetime, timedelta, time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

from glpmate.coach import (  # noqa: E402
    AIError, ChatClient, normalize_endpoint, safety_check, _normalize_estimate, _parse_json_loose,
)
from glpmate import drugs, efficacy  # noqa: E402
from glpmate.models import (  # noqa: E402
    DoseEvent, bateman_fraction, daily_totals, day_index, decay_fraction_exp, fmt_dt,
    impulse_response, level_at, level_curve, next_dose_info, normalize_payload,
    project_events, steady_state_trough, theoretical_max, tmax_of,
    bmi_of, bmi_category, healthy_weight_range, bmi_payload,
)
from glpmate.store import Store  # noqa: E402
from glpmate.seed import seed_demo  # noqa: E402

# 替尔泊肽：FDA 说明书 ZEPBOUND 12.3 节（约 5 天半衰期、中位 24 小时达峰）
HALF_LIFE = 120.0
TMAX = 24.0
WEEK = 168.0


class TestPharmacokinetics(unittest.TestCase):
    """曲线形状的正确性。算错了界面依然好看，但会误导人，所以这里卡得很细。"""

    def test_tmax_matches_label(self):
        """模型的达峰时间必须等于说明书 tmax（替尔泊肽：中位 24 小时，范围 8–72）。"""
        self.assertAlmostEqual(tmax_of(HALF_LIFE, TMAX), TMAX, places=6)
        low, high = drugs.get("tirzepatide")["tmax_range_hours"]
        self.assertLessEqual(low, TMAX)
        self.assertGreaterEqual(high, TMAX)
        for tmax in (8.0, 12.0, 24.0, 48.0, 72.0):
            self.assertAlmostEqual(tmax_of(HALF_LIFE, tmax), tmax, places=6)

    def test_single_dose_shape(self):
        """单次给药：t=0 还没吸收、tmax 处最高、之后按半衰期衰减。"""
        self.assertEqual(bateman_fraction(0, HALF_LIFE, TMAX), 0.0)
        self.assertEqual(bateman_fraction(-6, HALF_LIFE, TMAX), 0.0)
        self.assertAlmostEqual(bateman_fraction(TMAX, HALF_LIFE, TMAX), 1.0, places=9)
        # 峰值前后都应低于峰值
        self.assertLess(bateman_fraction(TMAX - 8, HALF_LIFE, TMAX), 1.0)
        self.assertLess(bateman_fraction(TMAX + 8, HALF_LIFE, TMAX), 1.0)
        # 消除相：达峰后再过一个半衰期，剩余约一半。
        # 与纯指数 0.5 略有偏差（吸收项此时仍有极小残留），偏差应在 5% 以内。
        ratio = bateman_fraction(TMAX + HALF_LIFE, HALF_LIFE, TMAX) / bateman_fraction(TMAX + 1, HALF_LIFE, TMAX)
        self.assertAlmostEqual(ratio, 0.5, delta=0.05)
        self.assertLess(abs(ratio - 0.5), 0.05)

    def test_half_life_scaling(self):
        """半衰期越长，同一时刻的残留越多——替尔泊肽(5天) vs 司美格鲁肽(7天)。"""
        short = bateman_fraction(168.0, 120.0, TMAX)
        long_ = bateman_fraction(168.0, 165.0, TMAX)
        self.assertGreater(long_, short)
        # 5 天半衰期的药，7 天后衰减到 40%~50%（纯消除项是 38%，吸收项使其略高）
        self.assertGreater(short, 0.38)
        self.assertLess(short, 0.50)
        # 1 周半衰期的药残留明显更多，但比例不等于纯指数比——吸收项的贡献与 ke 有关，
        # 所以这里只断言"更多且合理"，不去钉一个模型无关的假比例
        self.assertGreater(long_ / short, 1.10)
        self.assertLess(long_ / short, 1.50)

    def test_peak_is_at_tmax_not_at_injection(self):
        """回归测试：早期版本假设"注射瞬间满血"，与说明书 tmax 24 小时不符。"""
        t0 = datetime(2026, 1, 1, 12, 0)
        events = [DoseEvent(t0, 2.5)]
        at_injection = level_at(t0, events, HALF_LIFE, TMAX)
        at_peak = level_at(t0 + timedelta(hours=TMAX), events, HALF_LIFE, TMAX)
        self.assertAlmostEqual(at_injection, 0.0, places=9)
        self.assertGreater(at_peak, at_injection)
        self.assertAlmostEqual(at_peak, 2.5, places=6)

    def test_superposition_recurrence(self):
        """叠加：给药后 tmax 处的水平应等于历次末次剂量响应之和。"""
        t0 = datetime(2026, 1, 1, 12, 0)
        events = [DoseEvent(t0 + timedelta(days=7 * i), 2.5) for i in range(8)]
        query = events[-1].at + timedelta(hours=TMAX)
        expected = 2.5 * sum(bateman_fraction(TMAX + 7 * 24 * k, HALF_LIFE, TMAX) for k in range(8))
        self.assertAlmostEqual(level_at(query, events, HALF_LIFE, TMAX), expected, places=9)

    def test_steady_state_converges_to_geometric_series(self):
        """稳态峰值 = D·Σ b(tmax + k·间隔)。

        注意分母是 b(间隔 + tmax) 而不是 b(间隔)——峰值在 tmax 处，不在给药瞬间，
        用 b(间隔) 会得到一个偏大的假稳态值（曾把 4.09 算成 4.58）。
        """
        t0 = datetime(2026, 1, 1, 12, 0)
        series = sum(bateman_fraction(TMAX + k * WEEK, HALF_LIFE, TMAX) for k in range(200))
        steady = 2.5 * series
        self.assertAlmostEqual(steady, 4.0919, places=3)
        # 常见的错误闭式：用 b(间隔+tmax) 当公比会得到偏大的假稳态值，
        # 这里顺手钉住这个坑，防止以后又"化简"回去
        wrong = 2.5 / (1 - bateman_fraction(WEEK + TMAX, HALF_LIFE, TMAX))
        self.assertGreater(wrong, steady)
        self.assertGreater(level_at(t0 + timedelta(days=7 * 59, hours=TMAX),
                                    [DoseEvent(t0 + timedelta(days=7 * i), 2.5) for i in range(60)],
                                    HALF_LIFE, TMAX), steady - 0.01)
        many = [DoseEvent(t0 + timedelta(days=7 * i), 2.5) for i in range(60)]
        peak = level_at(many[-1].at + timedelta(hours=TMAX), many, HALF_LIFE, TMAX)
        self.assertAlmostEqual(peak, steady, places=3)
        self.assertLessEqual(peak, steady + 1e-9)   # 只能从下方逼近（有限次给药达不到极限）

    def test_theoretical_max_is_a_real_upper_bound(self):
        """进度条分母必须是真上界：跨多个周期细扫都不能被超过。"""
        t0 = datetime(2026, 1, 1, 12, 0)
        events = [DoseEvent(t0 + timedelta(days=7 * i), 2.5) for i in range(48)]
        bound = theoretical_max(events, HALF_LIFE, WEEK, TMAX)
        worst = 0.0
        for k in range(40, 48):
            for minutes in range(0, 168 * 60, 30):
                worst = max(worst, level_at(events[k].at + timedelta(minutes=minutes), events,
                                            HALF_LIFE, TMAX))
        self.assertLessEqual(worst, bound, f"理论上限 {bound:.4f} 被 {worst:.4f} 突破")
        self.assertLess(bound / worst - 1, 0.12)      # 但也不能虚高太多

    def test_trough_and_percent_semantics(self):
        """界面大数字口径：稳态谷值=100%，稳态峰值约 220%。"""
        t0 = datetime(2026, 1, 1, 12, 0)
        events = [DoseEvent(t0 + timedelta(days=7 * i), 2.5) for i in range(40)]
        trough = steady_state_trough(events, HALF_LIFE, WEEK, TMAX)
        peak = level_at(events[-1].at + timedelta(hours=TMAX), events, HALF_LIFE, TMAX)
        self.assertGreater(peak / trough, 1.9)        # 替尔泊肽 5 天半衰期、每周一次：波动明显
        self.assertLess(peak / trough, 2.1)
        self.assertGreater(100 * peak / trough, 195.0)

    def test_curve_samples_the_peak(self):
        """曲线必须采到达峰时刻，否则画出来是假的平滑线。"""
        t0 = datetime(2026, 1, 1, 12, 0)
        events = [DoseEvent(t0 - timedelta(days=7 * (11 - i)), 2.5) for i in range(12)]
        profile = {"half_life_hours": HALF_LIFE, "tmax_hours": TMAX, "dose_interval_days": 7}
        points = level_curve(events, profile, t0, t0 + timedelta(days=10), steps_per_day=4)
        self.assertGreater(len(points), 40)
        for p in points:
            self.assertLessEqual(p["pct_max"], 100.5)
            self.assertGreaterEqual(p["pct"], 0.0)
        self.assertGreater(max(p["pct"] for p in points), 195.0)
        self.assertLess(min(p["pct"] for p in points), 105.0)
        peak_ts = {fmt_dt(t0 + timedelta(days=7 * i, hours=TMAX)) for i in (0, 1)}
        self.assertTrue(peak_ts <= {p["t"] for p in points}, "达峰时刻未被采样")

    def test_edge_cases_do_not_explode(self):
        """参数误配时不能返回 NaN/负数/除零。"""
        self.assertEqual(bateman_fraction(10, 0, TMAX), 0.0)
        self.assertEqual(steady_state_trough([], HALF_LIFE, WEEK, TMAX), 0.0)
        self.assertEqual(theoretical_max([], HALF_LIFE, WEEK, TMAX), 0.0)
        self.assertEqual(steady_state_trough([DoseEvent(datetime(2026, 1, 1), 2.5)], 0), 0.0)
        # 半衰期极短（比如参数填错成 1 小时）也不该出现异常
        self.assertLess(theoretical_max([DoseEvent(datetime(2026, 1, 1), 2.5)], 1.0, WEEK, TMAX), 2.7)

    def test_exp_decay_helper_unchanged(self):
        """瞬时吸收的纯指数衰减保留，供对照使用。"""
        self.assertAlmostEqual(decay_fraction_exp(0, HALF_LIFE), 1.0)
        self.assertAlmostEqual(decay_fraction_exp(HALF_LIFE, HALF_LIFE), 0.5, places=9)
        self.assertEqual(decay_fraction_exp(-1, HALF_LIFE), 0.0)

    def test_impulse_response_consistency(self):
        """冲激响应与归一化响应必须是同一个函数，否则稳态求和会错。"""
        for t in (1.0, 24.0, 48.0, 168.0, 336.0):
            self.assertAlmostEqual(impulse_response(t, HALF_LIFE, TMAX),
                                   bateman_fraction(t, HALF_LIFE, TMAX), places=12)


class TestDrugParameters(unittest.TestCase):
    """参数表本身的可信度：每个数字都要能追到说明书。"""

    def test_tirzepatide_matches_fda_label(self):
        drug = drugs.get("tirzepatide")
        self.assertEqual(drug["half_life_hours"], 120.0)      # "approximately 5 days"
        self.assertEqual(drug["tmax_hours"], 24.0)            # "median ... 24 hours"
        self.assertEqual(drug["tmax_range_hours"], [8.0, 72.0])
        self.assertEqual(drug["bioavailability_pct"], 80.0)
        self.assertEqual(drug["steady_state_weeks"], 4)
        self.assertEqual(drug["interval_days"], 7)
        self.assertEqual(drug["dose_steps_mg"], [2.5, 5.0, 7.5, 10.0, 12.5, 15.0])
        self.assertEqual(drug["max_dose_mg"], 15.0)
        self.assertIn("穆峰达", drug["brands"])

    def test_every_number_has_a_cited_source(self):
        """防止以后顺手改数字却忘了改出处。"""
        for drug in drugs.DRUGS.values():
            self.assertTrue(drug["sources"], f"{drug['id']} 没有来源")
            for src in drug["sources"]:
                self.assertTrue(src["url"].startswith("http"), f"{drug['id']} 来源链接无效")
                self.assertTrue(src["claim"], f"{drug['id']} 来源缺少说明")
            self.assertIn(drug["confidence"], ("high", "medium", "low"))
            if drug["confidence"] == "high":
                # 高置信度必须附原文引用
                self.assertTrue(any(s.get("quote") for s in drug["sources"]),
                                f"{drug['id']} 标为 high 却没有原文引用")

    def test_no_invented_elimination_claims(self):
        """回归测试：不许在参数表里写说明书没有的结论。

        我曾在 tirzepatide 里写"末次给药后约 4–5 周接近清除"——说明书里根本没有这句话，
        是按半衰期自己外推出来的。凡是带周/天数字的清除描述，必须能在
        elimination_quotes 里找到对应原文，否则就是编造。
        """
        import re
        for drug in drugs.DRUGS.values():
            claim = drug.get("elimination_after_last_dose")
            if not claim:
                continue
            has_numbers = bool(re.search(r"\d", claim))
            quotes = drug.get("elimination_quotes") or []
            if has_numbers:
                # 提到具体数字 → 必须附原文佐证，且原文里要有同样的数字
                self.assertTrue(quotes, f"{drug['id']} 的清除描述带了数字却没有原文佐证：{claim}")
                joined = " ".join(q["text"] for q in quotes)
                for num in re.findall(r"(\d+(?:\.\d+)?)\s*(?:–|-|到)?\s*(\d+(?:\.\d+)?)?\s*(周|天|个月)", claim):
                    self.assertIn(num[0], joined,
                                  f"{drug['id']} 声称「{''.join(x for x in num if x)}」，但原文里找不到该数字")

    def test_lower_bound_tmax_is_flagged(self):
        """说明书写范围、没给中位值的药，必须标记 tmax 是下界，别被当成点估计。"""
        self.assertTrue(drugs.get("semaglutide").get("tmax_is_lower_bound"))   # 说明书只说 1–3 天
        self.assertFalse(drugs.get("tirzepatide").get("tmax_is_lower_bound"))  # 说明书明写中位 24 h
        for drug in drugs.DRUGS.values():
            self.assertIn("tmax_is_lower_bound", drug, f"{drug['id']} 缺少下界标记")

    def test_switching_drug_updates_parameters(self):
        st = Store(pathlib.Path(tempfile.mkdtemp()) / "d.db")
        st.save_profile({"drug_id": "tirzepatide"})
        self.assertEqual(st.profile()["half_life_hours"], 120.0)
        self.assertEqual(st.profile()["tmax_hours"], 24.0)
        self.assertEqual(st.profile()["dose_interval_days"], 7)
        st.save_profile({"drug_id": "liraglutide"})
        self.assertEqual(st.profile()["half_life_hours"], 13.0)
        self.assertEqual(st.profile()["dose_interval_days"], 1)     # 每日一次
        self.assertEqual(st.drug()["name_cn"], "利拉鲁肽")
        # 显式覆盖仍然有效（医生开了非标准方案）
        st.save_profile({"half_life_hours": 150.0})
        self.assertEqual(st.profile()["half_life_hours"], 150.0)

    def test_sources_markdown_renders(self):
        text = drugs.sources_markdown()
        self.assertIn("替尔泊肽", text)
        self.assertIn("accessdata.fda.gov", text)


class TestEfficacyReference(unittest.TestCase):
    """疗效参考数据：宁可不显示，也不能显示没核验的数字。"""

    def test_unverified_numbers_are_never_published(self):
        for drug_id in efficacy.EFFICACY:
            data = efficacy.payload(drug_id)
            for indication in data["indications"]:
                if indication.get("verified"):
                    continue
                for arm in indication["arms"]:
                    for key in ("weight_change_pct", "ge5_pct", "ge10_pct", "ge15_pct", "ge20_pct"):
                        # 未核验的条目允许没有这些字段，但只要有就必须是 None
                        self.assertIsNone(arm.get(key), f"{drug_id} 未核验却下发了 {key}")

    def test_tirzepatide_matches_label_table3(self):
        data = efficacy.payload("tirzepatide")
        study1 = data["indications"][0]
        self.assertIn("SURMOUNT-1", study1["trial"])
        self.assertEqual(study1["duration_weeks"], 72)
        by_arm = {str(a["dose_mg"]): a for a in study1["arms"]}
        self.assertAlmostEqual(by_arm["placebo"]["weight_change_pct"], -3.1)
        self.assertAlmostEqual(by_arm["15"]["weight_change_pct"], -20.9)
        self.assertAlmostEqual(by_arm["10"]["weight_change_pct"], -19.5)
        self.assertAlmostEqual(by_arm["5"]["weight_change_pct"], -15.0)
        # 必须有对照组，否则数字会误导
        self.assertIn("placebo", by_arm)
        # ≥5% 达标率随剂量单调不减
        rates = [by_arm[k]["ge5_pct"] for k in ("placebo", "5", "10", "15")]
        self.assertEqual(rates, sorted(rates))

    def test_diabetes_population_loses_less(self):
        """合并糖尿病的人群减重幅度明显更小——这是说明书里的真实差异，别被抹平。

        注意 SURMOUNT-2 的糖尿病人群只有 10 mg / 15 mg 两个臂。
        """
        ind = efficacy.payload("tirzepatide")["indications"][0]
        nodm = {str(a["dose_mg"]): a for a in ind["arms"]}
        # 合并糖尿病人群在 additional_studies.SURMOUNT-2 里（说明书归在体重管理章节下）
        sm2 = (ind.get("additional_studies") or {}).get("SURMOUNT-2")
        self.assertIsNotNone(sm2, "SURMOUNT-2（合并糖尿病）数据丢了")
        dm = {str(a["dose_mg"]): a for a in sm2["arms"]}
        self.assertLess(abs(dm["15"]["weight_change_pct"]), abs(nodm["15"]["weight_change_pct"]))
        self.assertLess(abs(dm["10"]["weight_change_pct"]), abs(nodm["10"]["weight_change_pct"]))
        self.assertIn("type 2 diabetes", sm2["population"])

    def test_diabetes_arms_may_only_report_kg(self):
        """说明书口径差异：糖尿病试验常常只报公斤，不报百分比。

        这不是数据缺失，是说明书本来就没印——所以绝对不能自己换算成百分比
        （起始体重不同，除出来的数没有意义）。
        """
        dm = efficacy.payload("tirzepatide")["indications"][1]
        kg_reported = [a for a in dm["arms"] if a.get("weight_change_kg") is not None]
        self.assertTrue(kg_reported, "糖尿病适应症应当有公斤口径的体重数据")
        for arm in kg_reported:
            # 有公斤数据时，百分比要么同为真值、要么明确为 None，不允许"看起来像数字"
            pct = arm.get("weight_change_pct")
            self.assertTrue(pct is None or isinstance(pct, (int, float)))

    def test_semaglutide_step1_verified(self):
        """司美格鲁肽 STEP-1：68 周、2.4 mg、-14.9%（已核验，不是占位）。"""
        ind = efficacy.payload("semaglutide")["indications"][0]
        self.assertIn("STEP", ind["trial"])
        self.assertEqual(ind["duration_weeks"], 68)
        by_arm = {str(a["dose_mg"]): a for a in ind["arms"]}
        self.assertAlmostEqual(by_arm["2.4"]["weight_change_pct"], -14.9)
        self.assertAlmostEqual(by_arm["placebo"]["weight_change_pct"], -2.4)
        self.assertAlmostEqual(by_arm["2.4"]["ge20_pct"], 30.2)

    def test_every_published_number_has_a_quote(self):
        data = efficacy.payload("tirzepatide")
        sources = data["indications"][0]["sources"] + data["gi_side_effects"]["sources"]
        self.assertTrue(sources)
        for src in sources:
            self.assertTrue(src["url"].startswith("http"))
            self.assertTrue(src["quote"], f"缺少原文引用：{src['claim']}")
            self.assertTrue(src.get("section"), f"缺少章节标注：{src['claim']}")

    def test_side_effect_rates_match_table2(self):
        gi = efficacy.payload("tirzepatide")["gi_side_effects"]
        by_arm = {str(a["dose_mg"]): a for a in gi["arms"]}
        self.assertEqual(by_arm["placebo"]["nausea_pct"], 8)
        self.assertEqual(by_arm["15"]["nausea_pct"], 28)
        self.assertEqual(by_arm["10"]["diarrhoea_pct"], 21)
        self.assertEqual(by_arm["5"]["any_gi_ae_pct"], 56)
        self.assertEqual(by_arm["placebo"]["any_gi_ae_pct"], 30)
        self.assertEqual(by_arm["15"]["gi_ae_discontinuation_pct"], 4.3)

    def test_disclaimers_present(self):
        data = efficacy.payload("tirzepatide")
        self.assertIn("不是对你的预测", data["disclaimer"])
        self.assertIn("不能直接横向比较", data["cross_trial_caveat"])


class TestNextDose(unittest.TestCase):
    def setUp(self):
        self.profile = {"dose_interval_days": 7, "dose_time": "12:00", "dose_mg": 2.5,
                        "med_name": "司美格鲁肽", "dose_weekday": 0, "treatment_start": "2026-10-05"}
        from glpmate.models import Record
        self.Record = Record

    def test_from_last_dose(self):
        now = datetime(2026, 10, 12, 20, 0)
        taken = [self.Record(1, "dose", datetime(2026, 10, 12, 12, 0), 2.5, "mg", {"dose_mg": 2.5})]
        info = next_dose_info(date(2026, 10, 12), self.profile, taken, now=now)
        self.assertEqual(info["date"], "2026-10-19")
        self.assertEqual(info["remaining_days"], 7)
        self.assertEqual(info["weekday_cn"], "星期一")
        self.assertFalse(info["overdue"])

    def test_from_weekday_without_records(self):
        now = datetime(2026, 10, 12, 20, 0)
        info = next_dose_info(date(2026, 10, 12), self.profile, [], now=now)
        # 2026-10-05 是周一，计划每周一 12:00；今天已过点，则下一次应是下周一
        self.assertEqual(info["weekday"], 0)
        self.assertGreater(info["due_at"], now.isoformat())

    def test_overdue_flag(self):
        now = datetime(2026, 10, 20, 8, 0)
        taken = [self.Record(1, "dose", datetime(2026, 10, 12, 12, 0), 2.5, "mg", {"dose_mg": 2.5})]
        info = next_dose_info(date(2026, 10, 20), self.profile, taken, now=now)
        self.assertEqual(info["date"], "2026-10-19")
        self.assertTrue(info["overdue"])
        self.assertEqual(info["remaining_days"], 0)


class TestNormalization(unittest.TestCase):
    def test_food_items_are_summed(self):
        norm = normalize_payload({"type": "food", "at": "2026-10-12T12:30:00", "meta": {
            "slot": "lunch", "items": [
                {"name": "米饭", "kcal": 200, "protein_g": 4, "carb_g": 44, "fat_g": 1, "fiber_g": 1},
                {"name": "鲫鱼", "kcal": 180, "protein_g": 32, "carb_g": 0, "fat_g": 6, "fiber_g": 0},
            ]}})
        meta = norm["meta"]
        self.assertEqual(meta["kcal"], 380)
        self.assertEqual(meta["protein_g"], 36)
        self.assertEqual(meta["carb_g"], 44)
        self.assertEqual(meta["fiber_g"], 1)

    def test_water_uses_amount_ml(self):
        norm = normalize_payload({"type": "water", "value": 250, "meta": {}})
        self.assertEqual(norm["meta"]["amount_ml"], 250)
        self.assertEqual(norm["unit"], "ml")

    def test_dose_and_side_effect_defaults(self):
        dose = normalize_payload({"type": "dose", "value": 2.5, "meta": {}})
        self.assertEqual(dose["meta"]["dose_mg"], 2.5)
        self.assertFalse(dose["meta"]["skipped"])
        se = normalize_payload({"type": "side_effect", "meta": {"label": "恶心"}})
        self.assertEqual(se["meta"]["severity"], 1)

    def test_strings_are_tolerated(self):
        norm = normalize_payload({"type": "weight", "value": "144.4", "at": "2026/10/12 08:15"})
        self.assertEqual(norm["value"], 144.4)
        self.assertEqual(norm["at"].hour, 8)

    def test_missing_type_raises(self):
        with self.assertRaises(ValueError):
            normalize_payload({"value": 1})

    def test_daily_totals(self):
        from glpmate.models import Record
        recs = [
            Record(1, "food", datetime(2026, 10, 12, 8), None, "", {"kcal": 260, "protein_g": 20}),
            Record(2, "food", datetime(2026, 10, 12, 12), None, "", {"kcal": 580, "protein_g": 36}),
            Record(3, "water", datetime(2026, 10, 12, 9), 250, "ml", {"amount_ml": 250}),
            Record(4, "water", datetime(2026, 10, 12, 13), 250, "ml", {"amount_ml": 250}),
        ]
        totals = daily_totals(recs)
        self.assertEqual(totals["kcal"], 840)
        self.assertEqual(totals["protein_g"], 56)
        self.assertEqual(totals["water_ml"], 500)


class TestSafetyGuard(unittest.TestCase):
    BLOCKED = [
        "我下周要不要把剂量加到5mg？",
        "我能提前一天打吗",
        "帮我看看是不是该停药了",
        "我想补打一针",
        "司美格鲁肽可以自己减量吗",
        "今天漏打了要不要补？",
    ]
    ALLOWED = [
        "这周蛋白质够吗",
        "恶心有点严重，一般什么时候会缓解",
        "我最近便秘，吃什么有帮助",
        "今天中午吃了红烧鲫鱼配米饭",
        "司美格鲁肽的作用机制是什么",
        "这个药的半衰期一般多久",
        "GLP-1 常见副作用有哪些",
        "帮我把这周的记录整理一下",
    ]

    def test_medical_advice_is_blocked(self):
        for text in self.BLOCKED:
            got = safety_check(text)
            self.assertIsNotNone(got, f"应拦截但放行了：{text}")
            self.assertEqual(got["kind"], "medical_advice", text)

    def test_normal_questions_pass(self):
        for text in self.ALLOWED:
            self.assertIsNone(safety_check(text), f"误拦了正常提问：{text}")

    def test_emergency_is_flagged(self):
        for text in ["我从昨晚开始持续呕吐，喝水都吐", "腹痛难忍而且意识模糊"]:
            got = safety_check(text)
            self.assertIsNotNone(got, text)
            self.assertEqual(got["kind"], "emergency", text)


class TestEndpointNormalization(unittest.TestCase):
    def test_common_forms(self):
        cases = {
            "https://api.deepseek.com": "https://api.deepseek.com/v1/chat/completions",
            "https://api.deepseek.com/": "https://api.deepseek.com/v1/chat/completions",
            "https://api.deepseek.com/v1": "https://api.deepseek.com/v1/chat/completions",
            "https://api.deepseek.com/v1/": "https://api.deepseek.com/v1/chat/completions",
            "https://api.deepseek.com/v1/chat/completions": "https://api.deepseek.com/v1/chat/completions",
            "http://127.0.0.1:11434/v1": "http://127.0.0.1:11434/v1/chat/completions",
            "https://open.bigmodel.cn/api/paas/v4": "https://open.bigmodel.cn/api/paas/v4/chat/completions",
        }
        for given, want in cases.items():
            self.assertEqual(normalize_endpoint(given), want, given)

    def test_client_requires_model(self):
        client = ChatClient("https://api.deepseek.com", "sk-test", "")
        with self.assertRaises(AIError):
            client.chat([{"role": "user", "content": "hi"}])


class TestEstimateParsing(unittest.TestCase):
    def test_loose_json(self):
        self.assertEqual(_parse_json_loose('```json\n{"a":1}\n```'), {"a": 1})
        self.assertEqual(_parse_json_loose('前缀 {"a":2} 后缀'), {"a": 2})
        self.assertIsNone(_parse_json_loose("完全不是 JSON"))

    def test_normalize_estimate_sums_items(self):
        est = _normalize_estimate({"items": [
            {"name": "米饭", "kcal": 200, "protein_g": 4, "carb_g": 44, "fat_g": 1},
            {"name": "鲫鱼", "kcal": 180, "protein_g": 32},
        ], "confidence": "medium"})
        self.assertEqual(len(est["items"]), 2)
        self.assertEqual(est["total"]["kcal"], 380)
        self.assertEqual(est["total"]["protein_g"], 36)
        self.assertEqual(est["confidence"], "medium")

    def test_garbage_input_degrades(self):
        est = _normalize_estimate(None)
        self.assertEqual(est["items"], [])
        self.assertEqual(est["confidence"], "low")
        self.assertIn("手动", est["note"])


class TestStoreIntegration(unittest.TestCase):
    def setUp(self):
        self.dir = pathlib.Path(tempfile.mkdtemp())
        self.store = Store(self.dir / "test.db")
        seed_demo(self.store, today=date(2026, 10, 12))

    def test_bootstrap_shape(self):
        b = self.store.bootstrap(date(2026, 10, 12))
        for key in ("today", "weekday", "day_index", "profile", "targets", "today_stats",
                    "next_dose", "level", "weight", "completed", "quick_types", "coach_quota"):
            self.assertIn(key, b)
        self.assertGreater(b["level"]["pct_peak"], 0)
        self.assertGreater(len(b["level"]["series"]), 10)
        self.assertEqual(b["targets"]["calories"], 1600.0)

    def test_dose_label_follows_current_drug(self):
        """历史剂量记录如果残留旧药名，展示时必须跟随当前档案。"""
        rec = self.store.add_record({"type": "dose", "at": "2026-10-05T12:00:00",
                                     "meta": {"dose_mg": 2.5, "med_name": "司美格鲁肽"}})
        view = self.store.record_view(rec, self.store.profile())
        self.assertIn("替尔泊肽", view["subtitle"])
        self.assertNotIn("司美格鲁肽", view["subtitle"])

    def test_records_roundtrip(self):
        created = self.store.add_record({"type": "weight", "at": "2026-10-12T09:00:00",
                                         "value": 143.4, "unit": "kg"})
        self.assertIsNotNone(created.id)
        updated = self.store.update_record(created.id, {"value": 143.0})
        self.assertEqual(updated.value, 143.0)
        self.assertTrue(self.store.delete_record(created.id))
        self.assertIsNone(self.store.get_record(created.id))

    def test_calendar_marks_days(self):
        cal = self.store.calendar(2026, 10, self.store.profile())
        self.assertEqual(cal["days_in_month"], 31)
        # 日历上的剂量点必须与库里的剂量记录一一对应
        dose_dates = sorted(r.at.date().isoformat() for r in self.store.all_dose_records()
                            if r.day.month == 10)
        marked = sorted(d for d, v in cal["days"].items() if "dose" in v["dots"])
        self.assertEqual(marked, dose_dates)
        self.assertGreaterEqual(len(marked), 1)
        dots = cal["days"]["2026-10-12"]["dots"]
        self.assertIn("weight", dots)
        self.assertIn("food", dots)
        self.assertTrue(all(c.startswith("#") for c in cal["days"]["2026-10-12"]["dot_colors"]))

    def test_card_confirm_writes_record(self):
        card = self.store.create_card("meal_confirm", {"records": [{
            "type": "food", "at": "2026-10-12T19:00:00",
            "meta": {"slot": "dinner", "desc": "番茄豆腐汤", "kcal": 150, "protein_g": 13}}]})
        before = len(self.store.records_on(date(2026, 10, 12)))
        result = self.store.confirm_card(card["id"], "save")
        self.assertTrue(result["ok"])
        self.assertEqual(len(result["record_ids"]), 1)
        self.assertEqual(len(self.store.records_on(date(2026, 10, 12))), before + 1)
        # 重复确认不应重复写入
        again = self.store.confirm_card(card["id"], "save")
        self.assertTrue(again.get("already"))
        self.assertEqual(len(self.store.records_on(date(2026, 10, 12))), before + 1)

    def test_discard_card_writes_nothing(self):
        card = self.store.create_card("record_confirm", {"records": [{
            "type": "weight", "at": "2026-10-12T09:00:00", "value": 140,
            "meta": {}}]})
        before = len(self.store.records_on(date(2026, 10, 12)))
        self.store.confirm_card(card["id"], "discard")
        self.assertEqual(len(self.store.records_on(date(2026, 10, 12))), before)

    def test_quota_counts_up(self):
        self.assertEqual(self.store.quota(date(2026, 10, 12))["used"], 0)
        self.store.bump_quota(date(2026, 10, 12))
        self.store.bump_quota(date(2026, 10, 12))
        quota = self.store.quota(date(2026, 10, 12))
        self.assertEqual(quota["used"], 2)
        self.assertEqual(quota["remaining"], quota["limit"] - 2)

    def test_explicit_targets_are_not_silently_dropped(self):
        """回归测试：设置页改过的目标值必须真的生效。

        两个真实踩到的问题：
        1. targets.weight_goal 被白名单过滤掉 → "目标体重"改了不生效
        2. 只写 targets.water_ml 会被 profile.water_target_l 覆盖 → 水目标被还原
        """
        # 1) 目标体重：profile 里写了就要能在 targets 里读到
        self.store.save_profile({"target_weight_kg": 78.5})
        self.assertAlmostEqual(self.store.targets()["weight_goal"], 78.5, places=2)

        # 2) 水目标：profile 的升数是权威值
        self.store.save_profile({"water_target_l": 2.5})
        self.assertAlmostEqual(self.store.targets()["water_ml"], 2500.0, places=2)

        # 3) 其余目标走 targets 通道
        self.store.save_profile({"targets": {"calories": 1700, "protein_g": 110}})
        targets = self.store.targets()
        self.assertAlmostEqual(targets["calories"], 1700.0, places=2)
        self.assertAlmostEqual(targets["protein_g"], 110.0, places=2)

        # 4) 未设置的字段仍然是默认值，不会被写成 0
        self.assertGreater(targets["fiber_g"], 0)

    def test_settings_mask_key(self):
        self.store.save_settings({"ai_api_key": "sk-abcdefghijklmnop", "ai_model": "deepseek-flash"})
        masked = self.store.settings(mask=True)
        self.assertNotIn("abcdefghijklmnop", masked["ai_api_key"])
        self.assertTrue(masked["ai_api_key_set"])
        raw = self.store.settings(mask=False)
        self.assertEqual(raw["ai_api_key"], "sk-abcdefghijklmnop")
        # 1) 回传掩码 → 不改动
        self.store.save_settings({"ai_api_key": masked["ai_api_key"]})
        self.assertEqual(self.store.settings(mask=False)["ai_api_key"], "sk-abcdefghijklmnop")
        # 2) 不传该字段 → 不改动
        self.store.save_settings({"ai_model": "deepseek-v4-pro"})
        self.assertEqual(self.store.settings(mask=False)["ai_api_key"], "sk-abcdefghijklmnop")
        # 3) 传 null → 不改动
        self.store.save_settings({"ai_api_key": None})
        self.assertEqual(self.store.settings(mask=False)["ai_api_key"], "sk-abcdefghijklmnop")
        # 4) 传空串 → 明确清除（用户点了"清除密钥"）
        self.store.save_settings({"ai_api_key": ""})
        self.assertEqual(self.store.settings(mask=False)["ai_api_key"], "")
        self.assertFalse(self.store.settings(mask=True)["ai_api_key_set"])

    def test_export_import_roundtrip(self):
        dump = self.store.export_all()
        other = Store(self.dir / "other.db")
        result = other.import_all(dump)
        self.assertEqual(result["imported"]["records"], len(dump["records"]))
        self.assertGreater(result["imported"]["records"], 0)

    def test_wipe_keeps_settings(self):
        self.store.save_settings({"ai_model": "deepseek-chat"})
        self.store.wipe(keep_settings=True)
        self.assertEqual(self.store.records(limit=5), [])
        self.assertEqual(self.store.settings(mask=False)["ai_model"], "deepseek-chat")


if __name__ == "__main__":
    unittest.main(verbosity=2)

class TestBmi(unittest.TestCase):
    """BMI 计算与分级。口径：中国成人标准 WS/T 428-2013（超重 ≥24，肥胖 ≥28）。"""

    def test_bmi_formula(self):
        """175cm / 70kg → 22.9（18.5~23.9 属正常）。"""
        self.assertAlmostEqual(bmi_of(70.0, 175.0), 22.857, places=2)

    def test_categories_follow_chinese_standard(self):
        """切点必须是 18.5 / 24 / 28，不是 WHO 的 18.5 / 25 / 30。

        这个差别在 BMI 25~30 之间很实际：WHO 标准下算超重，中国标准下
        28 以上已经是肥胖——门诊和体检报告的结论跟的是中国标准。
        """
        cases = [
            (17.0, "偏瘦"), (18.5, "正常"), (23.9, "正常"),
            (24.0, "超重"), (27.9, "超重"), (28.0, "肥胖"), (40.0, "肥胖"),
        ]
        for bmi, want in cases:
            with self.subTest(bmi=bmi):
                self.assertEqual(bmi_category(bmi)["label"], want)

    def test_missing_height_yields_none_not_a_guess(self):
        """身高没填就返回 None——绝不拿默认身高编一个 BMI 出来。"""
        self.assertIsNone(bmi_of(70.0, None))
        self.assertIsNone(bmi_of(None, 175.0))
        self.assertIsNone(bmi_of(0, 175.0))
        self.assertEqual(bmi_category(None)["key"], "unknown")

    def test_absurd_height_is_rejected(self):
        """把 175 写成 17.5 这类笔误要拦住，否则 BMI 会大得离谱。"""
        self.assertIsNone(bmi_of(70.0, 17.5))
        self.assertIsNone(bmi_of(70.0, 500.0))

    def test_healthy_range_matches_bmi_bounds(self):
        """正常体重区间必须真的落在 18.5~23.9 之间（自洽性检查）。"""
        rng = healthy_weight_range(175.0)
        self.assertAlmostEqual(bmi_of(rng["min_kg"], 175.0), 18.5, places=1)
        self.assertAlmostEqual(bmi_of(rng["max_kg"], 175.0), 23.9, places=1)

    def test_payload_reports_gap_to_healthy(self):
        """to_healthy_kg 是"还要减多少才进正常范围"，界面直接用这个数。"""
        b = bmi_payload(175.0, 144.0, 144.0, 80.0)
        self.assertEqual(b["bmi"], 47.0)
        self.assertEqual(b["category"]["label"], "肥胖")
        # 80kg 在 175cm 下仍是超重（BMI 26.1），这个事实必须如实反映
        self.assertEqual(b["target_category"]["label"], "超重")
        self.assertAlmostEqual(b["to_healthy_kg"], 70.8, places=1)
