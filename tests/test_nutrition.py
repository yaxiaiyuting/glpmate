"""营养参考摄入量与达标判定的测试。

这个模块的数字会直接变成界面上的进度条和"你还差多少"，所以每个数字都要
有出处、都要能算对被验证。三件最容易出错的：
  1. 蛋白质目标用总体重算 → 对肥胖者给出执行不了的数字（144kg → 173g）
  2. 供能比的分母用错（用热量目标而不是三大营养素自身的热量）
  3. 热量缺口不区分"过快"和"没缺口"——两者对 GLP-1 用药者的含义完全相反
"""
from __future__ import annotations

import pathlib
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

from glpmate.store import Store  # noqa: E402
from glpmate.nutrition import (  # noqa: E402
    AMDR, DEFICIT_COMFORT_MAX, DEFICIT_WARN_FAST, FIBER_MIN_G, KCAL_PER_KG,
    adjusted_weight_kg, amdr_status, expected_weight_loss_pct, nutrition_payload,
    protein_target_g, sources, weight_forecast,
)


class NutritionReferenceTest(unittest.TestCase):
    def test_amdr_matches_the_guideline(self):
        """供能比区间必须是指南原文的 50-65 / 10-15 / 20-30。

        这三个数如果被"顺手改成整数"（比如 10-20%），界面上所有供能比判定
        都会跟着错，而且看起来很正常。
        """
        self.assertEqual(AMDR["carb"], (50.0, 65.0))
        self.assertEqual(AMDR["protein"], (10.0, 15.0))
        self.assertEqual(AMDR["fat"], (20.0, 30.0))

    def test_sources_are_quoted(self):
        """每个数字都要有出处，且必须带原文引用——本项目对数据的一贯要求。"""
        src = sources()
        self.assertGreaterEqual(len(src), 4)
        for item in src:
            self.assertTrue(item.get("claim"))
            self.assertTrue(item.get("source"))
            self.assertTrue(item.get("quote"), f"缺原文引用：{item.get('claim')}")
        joined = " ".join(s["claim"] for s in src)
        self.assertIn("50-65", joined)
        self.assertIn("25-30", joined)


class AdjustedWeightTest(unittest.TestCase):
    def test_obese_weight_is_adjusted_down(self):
        """144 kg / 185 cm 的调整体重应在 90-95 kg（不是 144）。"""
        adj = adjusted_weight_kg(144.0, 185.0)
        self.assertIsNotNone(adj)
        self.assertGreater(adj, 85.0)
        self.assertLess(adj, 100.0)

    def test_normal_weight_is_unchanged(self):
        """不超重的人不该被"调整"——否则会凭空压低他们的蛋白质目标。"""
        adj = adjusted_weight_kg(60.0, 165.0)
        self.assertAlmostEqual(adj, 60.0, places=1)

    def test_protein_target_stays_executable(self):
        """蛋白质目标必须落在人能做到的范围里。

        这是回归测试：按总体重算时 144kg 会得到 173-230 g/天，
        而应用别处的蛋白质目标是 90 g，两个数字互相打架，
        界面上会出现"你还差 135 g 蛋白质"这种无法执行的建议。
        """
        lo, hi, basis = protein_target_g(144.0, 185.0)
        self.assertLess(hi, 160.0, f"蛋白质上限 {hi} g 不现实")
        self.assertGreater(lo, 80.0, f"蛋白质下限 {lo} g 偏低")
        self.assertIsNotNone(basis)

    def test_protein_target_without_height_falls_back_conservatively(self):
        """没有身高时要保守，而不是拿总体重硬算。"""
        lo, hi, basis = protein_target_g(144.0, None)
        self.assertLess(hi, 160.0)
        self.assertIsNotNone(basis)

    def test_protein_target_without_weight_uses_rni(self):
        lo, hi, basis = protein_target_g(None, None)
        self.assertEqual((lo, hi), (55.0, 75.0))
        self.assertIsNone(basis)


class AmdrTest(unittest.TestCase):
    def test_balanced_diet_is_ok(self):
        """落在指南区间内的配比应全部判 ok。

        这里要小心构造数据：脂肪供能比的上限是 30%，写成 66.7 g 会得到 30.8%
        （4-4-9 系数下），直接判 high。第一版测试就是栽在这个边界上——
        所以下面用精确的 60/15/25 配比。
        """
        # 60% 碳水 / 15% 蛋白 / 25% 脂肪，合计 2000 kcal
        out = amdr_status(2000.0, protein_g=75.0, carb_g=300.0, fat_g=55.6)
        self.assertTrue(out["has_data"])
        self.assertEqual(out["protein"]["verdict"], "ok")
        self.assertEqual(out["carb"]["verdict"], "ok")
        self.assertEqual(out["fat"]["verdict"], "ok")

    def test_denominator_is_the_macros_not_the_calorie_target(self):
        """供能比的分母必须是三大营养素自身的热量，不是用户填的热量目标。

        用热量目标当分母的话，"热量填了但营养没填全"会得到假比例，
        而且三大营养素的比例加起来不等于 100%，界面上会自相矛盾。
        """
        # 三种营养素的热量只够 800 kcal，但热量目标是 1600
        out = amdr_status(1600.0, protein_g=50.0, carb_g=100.0, fat_g=22.2)
        total_pct = sum(out[k]["pct"] for k in ("protein", "carb", "fat"))
        self.assertAlmostEqual(total_pct, 100.0, places=1)
        self.assertAlmostEqual(out["total_kcal"], 800.0, places=0)

    def test_no_data(self):
        out = amdr_status(0.0, 0.0, 0.0, 0.0)
        self.assertFalse(out["has_data"])
        self.assertNotIn("protein", out)


class DeficitTest(unittest.TestCase):
    def test_weekly_kg_uses_the_energy_equivalent(self):
        """7700 kcal ≈ 1 kg。缺口 500/天 → 每周约 0.45 kg。"""
        p = nutrition_payload(
            totals={"kcal": 1100.0}, targets={"calories": 1600.0}, weight_kg=100.0)
        d = p["deficit"]
        self.assertAlmostEqual(d["value"], 500.0, places=0)
        self.assertAlmostEqual(d["weekly_kg"], round(500 * 7 / KCAL_PER_KG, 2), places=2)

    def test_fast_deficit_is_flagged(self):
        """缺口过大要明确警示——GLP-1 用药期间这是真实风险，不是小事。"""
        p = nutrition_payload(
            totals={"kcal": 500.0}, targets={"calories": 1700.0}, weight_kg=100.0)
        self.assertEqual(p["deficit"]["verdict"], "fast")
        self.assertGreater(p["deficit"]["value"], DEFICIT_WARN_FAST)

    def test_mild_deficit_is_not_flagged_as_fast(self):
        p = nutrition_payload(
            totals={"kcal": 1200.0}, targets={"calories": 1600.0}, weight_kg=100.0)
        self.assertNotEqual(p["deficit"]["verdict"], "fast")
        self.assertLessEqual(p["deficit"]["value"], DEFICIT_COMFORT_MAX)

    def test_no_deficit_when_not_eating_enough_to_have_one(self):
        p = nutrition_payload(
            totals={"kcal": 1590.0}, targets={"calories": 1600.0}, weight_kg=100.0)
        self.assertEqual(p["deficit"]["verdict"], "none")

    def test_deficit_absent_without_data(self):
        """没吃没目标时不该编一个缺口出来。"""
        p = nutrition_payload(totals={}, targets={}, weight_kg=100.0)
        self.assertIsNone(p["deficit"])

    def test_deficit_formula_is_disclosed(self):
        """必须在数据里说明缺口不含运动消耗——否则用户会以为已经算进去了。"""
        p = nutrition_payload(
            totals={"kcal": 1200.0}, targets={"calories": 1600.0}, weight_kg=100.0)
        self.assertIn("运动", p["deficit"]["formula"])


class GapsTest(unittest.TestCase):
    def test_protein_and_fiber_gaps_are_reported(self):
        p = nutrition_payload(
            totals={"kcal": 820.0, "protein_g": 38.0, "fiber_g": 9.0},
            targets={"calories": 1600.0}, weight_kg=144.0, height_cm=185.0)
        keys = {g["key"] for g in p["gaps"]}
        self.assertIn("protein", keys)
        self.assertIn("fiber", keys)
        for g in p["gaps"]:
            self.assertGreater(g["need"], 0)
            self.assertTrue(g["hint"])

    def test_no_gaps_when_targets_met(self):
        p = nutrition_payload(
            totals={"kcal": 1500.0, "protein_g": 160.0, "fiber_g": 30.0},
            targets={"calories": 1600.0}, weight_kg=100.0, height_cm=175.0)
        self.assertEqual(p["gaps"], [])

    def test_gaps_not_shown_for_nothing_eaten(self):
        """完全没记录时不列缺口——那不是"还差多少"，是"今天还没开始记"。"""
        p = nutrition_payload(totals={}, targets={"calories": 1600.0}, weight_kg=100.0)
        self.assertEqual(p["gaps"], [])


class MultiDayTest(unittest.TestCase):
    def test_totals_are_averaged_over_days(self):
        """区间统计要换算成日均，否则 7 天的总量会被当成一天的。"""
        p = nutrition_payload(
            totals={"kcal": 11200.0, "protein_g": 700.0},
            targets={"calories": 1600.0}, weight_kg=100.0, days=7)
        self.assertAlmostEqual(p["kcal"]["value"], 1600.0, places=0)
        self.assertAlmostEqual(p["protein"]["value"], 100.0, places=0)

    def test_zero_days_does_not_divide_by_zero(self):
        p = nutrition_payload(totals={"kcal": 100.0}, targets={"calories": 1600.0}, days=0)
        self.assertEqual(p["days"], 1)


if __name__ == "__main__":
    unittest.main()

class WeightForecastTest(unittest.TestCase):
    """体重下降预测（用说明书/III 期试验的实测终点，而不是只算能量账）。

    设计上有两条底线：
      1. 第 72 周必须**精确命中**试验报告的终点百分比——那是唯一有实测数据的地方，
         曲线不能在数据点上偏离（第一版就偏了 15%，等于把说明书数据改小了）。
      2. 剂量只能取**测过的**组（5/10/15/安慰剂），不做线性插值——
         插值等于编造没测过的剂量组的数据。
    """

    def test_endpoint_matches_the_trial_at_72_weeks(self):
        """72 周必须等于 SURMOUNT-1 报告的终点值。"""
        for dose, want in ((5.0, -15.0), (10.0, -19.5), (15.0, -20.9)):
            with self.subTest(dose=dose):
                got = expected_weight_loss_pct(72, dose)["pct"]
                self.assertAlmostEqual(got, want, places=1,
                                       msg=f"{dose} mg 第 72 周算出 {got}%，试验是 {want}%")

    def test_placebo_arm_is_a_real_arm_not_zero(self):
        """0 mg（未用药/对照）不是"不瘦"，试验里安慰剂组也掉了 3.1%。

        这条防的是"剂量为 0 就返回 0%"这种想当然的写法——
        它会让用户以为不吃药体重完全不动。
        """
        self.assertAlmostEqual(expected_weight_loss_pct(72, 0.0)["pct"], -3.1, places=1)

    def test_dose_picks_the_nearest_tested_arm(self):
        """没测过的剂量取最近的组，而不是插值。"""
        self.assertEqual(expected_weight_loss_pct(72, 12.5)["used_dose_mg"], 15.0)
        self.assertEqual(expected_weight_loss_pct(72, 7.5)["used_dose_mg"], 10.0)
        self.assertEqual(expected_weight_loss_pct(72, 2.5)["used_dose_mg"], 5.0)
        # 12.5 mg 是说明书里的剂量阶梯，但试验没有这一组，所以必须向上取整到 15
        got = expected_weight_loss_pct(72, 12.5)["pct"]
        self.assertAlmostEqual(got, -20.9, places=1)

    def test_trajectory_is_monotonic_and_starts_at_zero(self):
        """曲线要单调下降（不会先瘦后胖），且第 0 周为 0。"""
        self.assertAlmostEqual(expected_weight_loss_pct(0, 15.0)["pct"], 0.0, places=6)
        prev = 0.0
        for w in range(0, 105, 4):
            cur = expected_weight_loss_pct(w, 15.0)["pct"]
            self.assertLessEqual(cur, prev + 1e-9, f"第 {w} 周比上一段还高")
            prev = cur

    def test_curve_is_flagged_as_fitted(self):
        """时间曲线是拟合，不是试验数据——必须能被界面识别出来并标注。"""
        r = expected_weight_loss_pct(24, 15.0)
        self.assertTrue(r["is_extrapolated_curve"])
        self.assertEqual(r["endpoint_weeks"], 72)

    def test_forecast_reports_both_estimates(self):
        """试验预期与能量账要**同时**给，并说明两者为什么会不一样。

        只报能量账会系统性低估（它只算"吃得少"，不含药物对胃排空、
        饱腹信号的影响）；只报试验值会让人以为减重与吃多少无关。
        """
        f = weight_forecast(144.0, 143.0, weeks_on_treatment=12, dose_mg=15.0,
                            deficit_kcal=800.0)
        self.assertIn("expected_loss_kg", f)
        self.assertIn("deficit_estimate_kg", f)
        self.assertIn("deficit_note", f)
        self.assertIn("能量账", f["deficit_note"])

    def test_composition_split_is_reported(self):
        """要给出瘦体重/脂肪的拆分——GLP-1 用药期掉肌肉是真实风险。"""
        f = weight_forecast(100.0, 100.0, weeks_on_treatment=72, dose_mg=15.0)
        # 注意：expected_loss_kg 是负数（表示下降），拆分出来的两个也是负数，
        # 所以两边都要取绝对值再比。
        self.assertAlmostEqual(
            abs(f["lean_mass_loss_kg"]) + abs(f["fat_mass_loss_kg"]),
            abs(f["expected_loss_kg"]), places=1)
        self.assertAlmostEqual(abs(f["lean_mass_loss_kg"]) / abs(f["expected_loss_kg"]),
                               0.25, places=2)

    def test_trial_caveat_is_attached(self):
        """必须带上人群说明——试验基线 BMI 38，用户 BMI 不同则百分比不代表同样公斤数。"""
        f = weight_forecast(100.0, 100.0, weeks_on_treatment=52, dose_mg=10.0)
        self.assertIn("BMI", f["trial_meta"]["population"])
        self.assertIn("BMI", f["trial_meta"]["caveat"])

    def test_missing_weight_does_not_crash(self):
        f = weight_forecast(None, None, weeks_on_treatment=12, dose_mg=15.0)
        self.assertNotIn("expected_loss_kg", f)
        self.assertIn("trial", f)

class WeightForecastIntegrationTest(unittest.TestCase):
    """weight_forecast 接进 bootstrap 之后仍要能出数字。

    这一条是为一个真实缺口加的：只依赖 /api/weight 的**起始体重**时，
    没填过 profile.start_weight_kg 的用户会拿不到任何预测（KeyError 级别的缺失，
    不是 0）。所以基线要能退回当前体重。
    """

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp())
        self.store = Store(self.tmp / "t.db")

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_bootstrap_exposes_forecast(self):
        from datetime import date, timedelta
        today = date.today()
        self.store.save_profile({"height_cm": 185.0, "target_weight_kg": 80.0, "dose_mg": 15.0})
        self.store.add_record({
            "type": "weight", "at": f"{today - timedelta(days=60)}T08:00:00",
            "value": 120.0, "unit": "kg", "meta": {},
        })
        self.store.add_record({
            "type": "dose", "at": f"{today - timedelta(days=60)}T12:00:00",
            "value": 15.0, "unit": "mg",
            "meta": {"dose_mg": 15.0, "med_name": "替尔泊肽", "skipped": False},
        })
        w = self.store.bootstrap()["weight_forecast"]
        self.assertIn("trial", w)
        self.assertIn("expected_loss_kg", w, "只填过体重记录时也必须能给出预测")
        self.assertLess(w["expected_loss_kg"], 0)
        self.assertIn("lean_mass_loss_kg", w)

    def test_forecast_survives_empty_database(self):
        """全新库（没有用药、没有体重）不能崩。"""
        w = self.store.bootstrap()["weight_forecast"]
        self.assertIn("trial", w)
        self.assertEqual(w["weeks"], 0.0)
