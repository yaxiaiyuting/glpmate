"""示例数据：让第一次打开就有东西可看。

数据是合成的，不是任何真实用户的记录。用 --seed 显式触发。
"""

from __future__ import annotations

from datetime import date, datetime, time, timedelta

from .store import Store


def seed_demo(store: Store, today: date | None = None) -> int:
    today = today or date.today()
    if store.records(limit=1):
        return 0   # 已经有数据就不重复灌

    store.save_profile({
        "display_name": "示例用户",
        "drug_id": "tirzepatide",
        "med_name": "替尔泊肽",
        "dose_mg": 2.5,
        "dose_interval_days": 7,
        "dose_time": "12:00",
        "treatment_start": (today - timedelta(days=0)).isoformat(),
        "target_weight_kg": 80.0,
        "start_weight_kg": 147.2,
        "water_target_l": 2.0,
    })

    count = 0
    start = today - timedelta(days=27)

    # 每周一次注射
    dose_day = start
    while dose_day <= today:
        store.add_record({
            "type": "dose",
            "at": datetime.combine(dose_day, time(12, 0)).isoformat(),
            "meta": {"dose_mg": 2.5, "med_name": "替尔泊肽", "site": "腹部左侧"},
        }, source="seed")
        count += 1
        dose_day += timedelta(days=7)

    # 体重：总体下行，带一点水重波动
    weight = 147.2
    day = start
    weights_plan = {0: 147.2, 3: 146.4, 7: 145.8, 10: 145.1, 14: 145.6,
                    17: 106.6, 21: 106.2, 24: 106.0, 27: 106.0}
    for offset, value in weights_plan.items():
        when = start + timedelta(days=offset)
        if when > today:
            continue
        store.add_record({"type": "weight", "at": datetime.combine(when, time(11, 45)).isoformat(),
                          "value": value, "unit": "kg"}, source="seed")
        count += 1

    # 最近 4 天的饮食与饮水
    meals = [
        ("breakfast", "两个鸡蛋 + 一杯无糖豆浆", 260, 20, 8, 14, 2),
        ("lunch", "红烧鲫鱼配蘑菇和米饭", 580, 36, 62, 18, 3),
        ("dinner", "番茄豆腐汤 + 半个玉米", 320, 18, 34, 9, 5),
    ]
    for back in range(4):
        when = today - timedelta(days=back)
        for idx, (slot, desc, kcal, prot, carb, fat, fiber) in enumerate(meals):
            if back == 0 and slot == "dinner":
                continue
            hour = {"breakfast": 8, "lunch": 12, "dinner": 18}[slot]
            store.add_record({
                "type": "food",
                "at": datetime.combine(when, time(hour, 30 + idx * 7)).isoformat(),
                "meta": {"slot": slot, "desc": desc, "kcal": kcal, "protein_g": prot,
                         "carb_g": carb, "fat_g": fat, "fiber_g": fiber},
            }, source="seed")
            count += 1
        for glass in range(3 if back % 2 == 0 else 2):
            store.add_record({
                "type": "water",
                "at": datetime.combine(when, time(9 + glass * 4, 15)).isoformat(),
                "meta": {"amount_ml": 250},
            }, source="seed")
            count += 1
        if back == 1:
            store.add_record({
                "type": "side_effect",
                "at": datetime.combine(when, time(20, 10)).isoformat(),
                "meta": {"label": "恶心", "severity": 3, "desc": "注射后第二天早上"},
            }, source="seed")
            count += 1

    store.add_message("assistant",
                      "我是你的记录助手。你可以直接说「中午吃了红烧鲫鱼配米饭」，"
                      "我会估算营养并生成一张待确认的卡片；也可以问我这周的蛋白质够不够。\n\n"
                      "剂量相关的问题我不会替你决定——那要交给你的医生。")
    return count
