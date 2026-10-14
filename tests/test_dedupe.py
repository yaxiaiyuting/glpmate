"""重复记录与卡片 ID 的回归测试。

背景（都来自真机）：
  - 饮食记录里同一餐出现两条完全相同的行（早餐 14:11 那条记了 4 次）
  - pending_cards 建卡时抛 UNIQUE constraint failed
这两件事同源：同一餐被生成多张卡片、各自确认。
"""
import pathlib
import shutil
import sys
import tempfile
import unittest
from datetime import date

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from glpmate.store import Store  # noqa: E402


def meal(day: str, slot: str = "breakfast", desc: str = "燕麦+鸡蛋", kcal: float = 300) -> dict:
    return {
        "type": "food", "at": f"{day}T14:11:00", "value": kcal, "unit": "kcal",
        "meta": {"slot": slot, "desc": desc, "kcal": kcal, "protein_g": 19},
    }


class CardIdTest(unittest.TestCase):
    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp())
        self.store = Store(self.tmp / "t.db")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_ids_are_unique_even_in_the_same_millisecond(self):
        """同一毫秒内连建多张卡片不能撞 ID。

        旧实现 `card_{毫秒}_{payload长度}`：用户连点两下就会抛
        `UNIQUE constraint failed: pending_cards.id`。
        """
        ids = [
            self.store.create_card("meal_confirm", {"records": [meal("2026-10-13")]})["id"]
            for _ in range(10)
        ]
        self.assertEqual(len(set(ids)), 10, f"卡片 ID 有重复：{ids}")

    def test_payload_length_no_longer_matters(self):
        """payload 的 key 数量相同也必须能区分（旧实现会撞）。"""
        a = self.store.create_card("meal_confirm", {"records": [meal("2026-10-13")]})["id"]
        b = self.store.create_card("meal_confirm", {"records": [meal("2026-10-14")]})["id"]
        self.assertNotEqual(a, b)


class ConfirmIdempotencyTest(unittest.TestCase):
    """同一餐被多张卡片确认，也只应产生一条记录。"""

    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp())
        self.store = Store(self.tmp / "t.db")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_four_cards_for_the_same_meal_produce_one_record(self):
        """真机情况：早餐同一餐被生成 4 张卡片，各自确认 → 记了 4 次。"""
        day = date.today().isoformat()
        for _ in range(4):
            card = self.store.create_card("meal_confirm", {"records": [meal(day)]})
            self.store.confirm_card(card["id"], "save")
        recs = [r for r in self.store.records(limit=100) if r.type == "food"]
        self.assertEqual(len(recs), 1, f"同一餐记了 {len(recs)} 条，应为 1 条")

    def test_estimation_differences_do_not_create_duplicates(self):
        """两次估算数字略有不同（热量差几 kcal）仍是同一餐。

        身份判断刻意不含热量/蛋白——否则"估算差 1 kcal"就会判成两条，
        而那正是重复记录的一种来源。
        """
        day = date.today().isoformat()
        for kcal in (300, 305, 298):
            card = self.store.create_card("meal_confirm", {"records": [meal(day, kcal=kcal)]})
            self.store.confirm_card(card["id"], "save")
        recs = [r for r in self.store.records(limit=100) if r.type == "food"]
        self.assertEqual(len(recs), 1)

    def test_different_meals_are_both_kept(self):
        """不同餐次/不同描述必须都保留——去重不能误删。"""
        day = date.today().isoformat()
        for slot, desc in (("breakfast", "燕麦+鸡蛋"), ("lunch", "鸡胸肉+葡萄"), ("dinner", "馕饼+芒果")):
            card = self.store.create_card("meal_confirm", {"records": [meal(day, slot=slot, desc=desc)]})
            self.store.confirm_card(card["id"], "save")
        recs = [r for r in self.store.records(limit=100) if r.type == "food"]
        self.assertEqual(len(recs), 3)

    def test_same_slot_different_time_is_not_a_duplicate(self):
        """同一餐次但时间不同 = 两次加餐，不能当重复删掉。"""
        day = date.today().isoformat()
        for at in (f"{day}T08:00:00", f"{day}T10:30:00"):
            r = dict(meal(day))
            r["at"] = at
            self.store.add_record(r, source="coach", skip_if_exists=True)
        recs = [r for r in self.store.records(limit=100) if r.type == "food"]
        self.assertEqual(len(recs), 2)


class DedupeExistingDataTest(unittest.TestCase):
    """清理已经产生的脏数据。"""

    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp())
        self.store = Store(self.tmp / "t.db")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_dry_run_reports_without_deleting(self):
        day = date.today().isoformat()
        for _ in range(3):
            self.store.add_record(meal(day))          # 不加 skip，造脏数据
        res = self.store.dedupe_records(dry_run=True)
        self.assertEqual(res["removed"], 2)
        self.assertEqual(len(self.store.records(limit=100)), 3, "dry_run 不能真删")

    def test_dedupe_keeps_the_earliest_and_removes_the_rest(self):
        day = date.today().isoformat()
        first = self.store.add_record(meal(day))
        for _ in range(3):
            self.store.add_record(meal(day))
        res = self.store.dedupe_records()
        self.assertEqual(res["removed"], 3)
        left = self.store.records(limit=100)
        self.assertEqual(len(left), 1)
        self.assertEqual(left[0].id, first.id, "应保留最早一条")

    def test_dedupe_does_not_touch_distinct_records(self):
        """不同天的同一条餐食不是重复（按分钟比较，跨天不会误判）。"""
        for day in ("2026-10-12", "2026-10-13"):
            self.store.add_record(meal(day))
        res = self.store.dedupe_records()
        self.assertEqual(res["removed"], 0)
        self.assertEqual(len(self.store.records(limit=100)), 2)


class DescriptionSimilarityTest(unittest.TestCase):
    """描述相似度：同一餐的措辞差异要能识别，不同餐不能误判。

    阈值 0.7 的标定依据是下面两组真实样本：
      同一餐、措辞有小差异 → 0.778
      不同餐、只差主食     → 0.429
    """

    def test_similarity_separates_real_cases(self):
        same = [
            ("20g燕麦+2个水煮蛋+120ml牛奶", "20g燕麦+2个水煮蛋+120ml全脂牛奶"),
            ("300g鸡胸肉+150g葡萄", "150g葡萄 + 300g鸡胸肉"),
        ]
        diff = [
            ("300g鸡胸肉+150g葡萄", "100g煎海鱼+10g白菜+木耳"),
            ("300g鸡胸肉+150g葡萄", "300g鸡胸肉+150g米饭"),
        ]
        for a, b in same:
            with self.subTest(same=(a, b)):
                v = Store._desc_similar(Store._desc_key(a), Store._desc_key(b))
                self.assertGreaterEqual(v, 0.7, f"同一餐被判为不同：{a} vs {b}（{v:.3f}）")
        for a, b in diff:
            with self.subTest(diff=(a, b)):
                v = Store._desc_similar(Store._desc_key(a), Store._desc_key(b))
                self.assertLess(v, 0.7, f"不同餐被判为同一餐：{a} vs {b}（{v:.3f}）")

    def test_quantities_are_ignored(self):
        """份量估算浮动不算另一餐（约80g vs 80g）。

        注意断言的是**份量被剔掉**，不是整个 key 相等：
        「煎」是烹饪方式不是量词，不该被剔（剔了会把「煎海鱼」和「清蒸海鱼」
        变成同一个 key）。措辞差异由相似度负责，这里只验证数字和单位消失了。
        """
        key = Store._desc_key("约80g紫米饭+100g煎海鱼")
        self.assertNotIn("80", key)
        self.assertNotIn("100", key)
        self.assertNotIn("g", key)
        self.assertNotIn("约", key)
        self.assertIn("米", key)
        self.assertIn("鱼", key)
        # 与不带份量的写法相似，足以判为同一餐
        self.assertGreaterEqual(
            Store._desc_similar(key, Store._desc_key("80g紫米饭 + 100g海鱼")), 0.7)

    def test_word_order_is_ignored(self):
        self.assertEqual(
            Store._desc_key("鸡蛋+牛奶"),
            Store._desc_key("牛奶+鸡蛋"),
        )


class NearDuplicateRealDeviceTest(unittest.TestCase):
    """真机上的两个具体场景。"""

    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp())
        self.store = Store(self.tmp / "t.db")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_milk_vs_full_fat_milk_is_the_same_meal(self):
        """卡片写「120ml牛奶」、落库是「120ml全脂牛奶」——差一个词。

        旧实现用字符串相等比较，于是那张卡片一直挂在待确认列表里，
        用户点一下就会再记一条（真机现象）。
        """
        day = date.today().isoformat()
        self.store.add_record({
            "type": "food", "at": f"{day}T14:11:00", "value": 300, "unit": "kcal",
            "meta": {"slot": "breakfast", "desc": "20g燕麦+2个水煮蛋+120ml全脂牛奶",
                     "kcal": 300, "protein_g": 19},
        })
        self.store.add_record({
            "type": "food", "at": f"{day}T14:11:00", "value": 296, "unit": "kcal",
            "meta": {"slot": "breakfast", "desc": "20g燕麦+2个水煮蛋+120ml牛奶",
                     "kcal": 296, "protein_g": 19},
        }, source="coach", skip_if_exists=True)
        self.assertEqual(len(self.store.records(limit=100)), 1)

    def test_dedupe_catches_near_duplicates_not_just_exact(self):
        """清理历史脏数据时也要用相似度，否则真机上那对记录清不掉。"""
        day = date.today().isoformat()
        self.store.add_record({
            "type": "food", "at": f"{day}T14:11:00", "value": 300, "unit": "kcal",
            "meta": {"slot": "breakfast", "desc": "20g燕麦+2个水煮蛋+120ml全脂牛奶", "kcal": 300},
        })
        self.store.add_record({
            "type": "food", "at": f"{day}T14:11:00", "value": 296, "unit": "kcal",
            "meta": {"slot": "breakfast", "desc": "20g燕麦+2个水煮蛋+120ml牛奶", "kcal": 296},
        })
        res = self.store.dedupe_records()
        self.assertEqual(res["removed"], 1)
        self.assertEqual(len(self.store.records(limit=100)), 1)

    def test_different_staple_is_not_merged(self):
        """只差主食必须保留两条——误删比重复更难发现。"""
        day = date.today().isoformat()
        self.store.add_record({
            "type": "food", "at": f"{day}T12:30:00", "value": 400, "unit": "kcal",
            "meta": {"slot": "lunch", "desc": "300g鸡胸肉+150g米饭", "kcal": 400},
        })
        self.store.add_record({
            "type": "food", "at": f"{day}T12:30:00", "value": 300, "unit": "kcal",
            "meta": {"slot": "lunch", "desc": "300g鸡胸肉+150g葡萄", "kcal": 300},
        })
        self.assertEqual(self.store.dedupe_records()["removed"], 0)


class ScopeLimitsTest(unittest.TestCase):
    """去重的生效范围要窄——误删比重复更难发现。

    用户明确要求「一天里有重复的餐条取消就可以了」，所以这里把边界钉死：
    只处理同一天、同一分钟、同一餐次的重复。
    """

    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp())
        self.store = Store(self.tmp / "t.db")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_same_meal_on_different_days_is_kept(self):
        """昨天和今天吃了同样的早餐，必须都保留。"""
        for day in ("2026-10-12", "2026-10-13"):
            self.store.add_record({
                "type": "food", "at": f"{day}T08:30:00", "value": 300, "unit": "kcal",
                "meta": {"slot": "breakfast", "desc": "20g燕麦+2个水煮蛋", "kcal": 300},
            }, source="coach", skip_if_exists=True)
        self.assertEqual(len(self.store.records(limit=100)), 2)

    def test_two_breakfasts_at_different_times_are_both_kept(self):
        """同一餐次但不同时刻 = 两次加餐，都要保留。"""
        day = date.today().isoformat()
        for at in ("08:00:00", "10:30:00"):
            self.store.add_record({
                "type": "food", "at": f"{day}T{at}", "value": 150, "unit": "kcal",
                "meta": {"slot": "breakfast", "desc": "鸡蛋", "kcal": 150},
            }, source="coach", skip_if_exists=True)
        self.assertEqual(len(self.store.records(limit=100)), 2)

    def test_same_time_different_slot_is_kept(self):
        """同一分钟但不同餐次（加餐 vs 午餐）不合并。"""
        day = date.today().isoformat()
        for slot in ("lunch", "snack"):
            self.store.add_record({
                "type": "food", "at": f"{day}T12:30:00", "value": 200, "unit": "kcal",
                "meta": {"slot": slot, "desc": "酸奶", "kcal": 200},
            }, source="coach", skip_if_exists=True)
        self.assertEqual(len(self.store.records(limit=100)), 2)

    def test_non_food_types_are_not_merged_by_description(self):
        """饮水和体重没有 desc，不能因为描述都为空就被当成重复。"""
        day = date.today().isoformat()
        self.store.add_record({
            "type": "water", "at": f"{day}T10:00:00", "value": 250, "unit": "ml",
            "meta": {"amount_ml": 250},
        }, source="manual", skip_if_exists=True)
        self.store.add_record({
            "type": "weight", "at": f"{day}T10:00:00", "value": 140, "unit": "kg", "meta": {},
        }, source="manual", skip_if_exists=True)
        self.assertEqual(len(self.store.records(limit=100)), 2)
