"""教练上下文链路的回归测试。

**这个文件是为一个真实故障加的。** `store.level_summary()` 里的 `tmax`
被误删（改曲线起点时顺带删掉了），后果是**教练完全不可用**：每次发消息都在
`build_context → level_summary` 抛 `NameError`，界面只显示

    发送失败: name 'tmax' is not defined

之所以没被原有测试抓到：教练路径要 API key，而 81 个测试没有一个碰过它。
所以这里的策略是**完全不碰网络和密钥**，只把"发消息之前要做的事"全部跑一遍——
`NameError` 这类错误全都发生在这一步，不需要真的调模型就能发现。

同时钉住一个容易反复出错的点：教练看到的百分比必须和界面上的大数字同口径
（`pct_max`，稳态峰值 = 100%），不能一个说 130% 另一个显示 55%。
"""
from __future__ import annotations

import pathlib
import shutil
import sys
import tempfile
import unittest
from datetime import datetime, timedelta

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

from glpmate.coach import build_context, context_text, SYSTEM_PROMPT  # noqa: E402
from glpmate.store import Store  # noqa: E402


class CoachContextTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp())
        self.store = Store(self.tmp / "t.db")
        self._seed()

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _seed(self) -> None:
        today = datetime.now()
        start = today - timedelta(days=20)
        self.store.save_profile({
            "display_name": "测试",
            "drug_id": "tirzepatide",
            "dose_mg": 2.5,
            "height_cm": 175.0,
            "target_weight_kg": 80.0,
        })
        for week in range(3):
            at = (start + timedelta(days=7 * week)).replace(
                hour=12, minute=15, second=0, microsecond=0)
            self.store.add_record({
                "type": "dose", "at": at.strftime("%Y-%m-%dT%H:%M:%S"),
                "value": 2.5, "unit": "mg",
                "meta": {"dose_mg": 2.5, "med_name": "替尔泊肽", "skipped": False},
            })
        self.store.add_record({
            "type": "weight",
            "at": (today - timedelta(days=1)).strftime("%Y-%m-%dT08:00:00"),
            "value": 100.0, "unit": "kg", "meta": {},
        })
        self.store.add_record({
            "type": "food",
            "at": today.strftime("%Y-%m-%dT12:30:00"),
            "value": 600.0, "unit": "kcal",
            "meta": {"slot": "lunch", "desc": "米饭和青菜", "kcal": 600.0,
                     "protein_g": 20.0, "carb_g": 80.0, "fat_g": 15.0, "fiber_g": 5.0},
        })

    # ---------------------------------------------------------------- 崩溃类

    def test_build_context_does_not_raise(self):
        """有数据时组装上下文不能抛异常。

        这里是 `tmax` 那个 NameError 的第一道防线：它必须在**没有 API key**
        的情况下就能失败，而不是等用户点了发送才发现。
        """
        ctx = build_context(self.store, None)
        self.assertIsInstance(ctx, dict)
        self.assertIn("level", ctx)
        self.assertIsInstance(context_text(ctx), str)

    def test_context_works_on_empty_database(self):
        """全新用户（一条记录都没有）打开教练也不能崩。"""
        empty = Store(self.tmp / "empty.db")
        ctx = build_context(empty, None)
        self.assertIsInstance(context_text(ctx), str)

    def test_level_summary_works_without_doses(self):
        """没有任何剂量记录时，level_summary 仍要返回完整结构。"""
        s = Store(self.tmp / "nodose.db")
        s.save_profile({"drug_id": "tirzepatide"})
        lv = s.level_summary()
        for key in ("current_mg", "baseline_mg", "max_mg", "pct_max", "trend"):
            self.assertIn(key, lv)

    # ---------------------------------------------------------------- 口径类

    def test_coach_percentage_matches_ui(self):
        """教练对模型说的百分比必须和界面大数字一致。

        界面用的是 `pct_max`（稳态峰值 = 100%）。教练曾经用 `pct_peak`
        （稳态谷值 = 100%），于是它拿"130%"去讨论用户屏幕上写着的"55%"。
        """
        text = context_text(build_context(self.store, None))
        lv = self.store.level_summary()
        self.assertIn(f"{lv['pct_max']}%", text,
                      "教练上下文里的百分比与 level_summary 的 pct_max 不一致")

    def test_coach_never_quotes_the_trough_basis(self):
        """`pct_peak`（谷值口径）不该出现在给模型的提示里——它没有对应的界面元素。"""
        text = context_text(build_context(self.store, None))
        lv = self.store.level_summary()
        if lv["pct_peak"] != lv["pct_max"]:
            self.assertNotIn(f"{lv['pct_peak']}%", text)

    def test_context_mentions_every_section(self):
        """上下文要有实质内容，不能是空壳。"""
        text = context_text(build_context(self.store, None))
        for keyword in ("用药", "体内水平估算", "体重"):
            self.assertIn(keyword, text, f"上下文缺少「{keyword}」")

    # ---------------------------------------------------------------- 安全类

    def test_system_prompt_keeps_the_medical_guardrails(self):
        """提示词里的用药安全护栏不能被人删掉。

        整个项目的底线之一：教练不替用户决定用药，遇到紧急症状要建议就医。
        这些句子一旦丢失，模型就会开始给剂量建议。
        """
        self.assertIn("{context}", SYSTEM_PROMPT)
        for keyword in ("医生", "剂量"):
            self.assertIn(keyword, SYSTEM_PROMPT, f"系统提示词缺少「{keyword}」相关约束")


if __name__ == "__main__":
    unittest.main()
