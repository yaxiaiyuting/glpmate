"""药物参数表：每个数字都必须能追到说明书原文。

为什么要单独一个文件：界面上那条"体内水平"曲线的形状完全由这几个参数决定，
参数错了曲线还是很好看，但会误导人。所以这里的每一项都带 `source`（出处）
和 `quote`（原文），并且 `confidence` 标明可信程度。

tirzepatide 的参数逐条摘自 FDA 说明书 ZEPBOUND（NDA 217806，2024 年版本）：
  https://www.accessdata.fda.gov/drugsatfda_docs/label/2024/217806s003lbl.pdf
  12.3 Pharmacokinetics 一节。

仍然要强调：这些是"群体药代动力学"参数，用一室模型近似，只能看趋势，
不是你的血药浓度，不能用来决定打多少、什么时候打。
"""

from __future__ import annotations

from typing import Any

# 数据核验日期：参数如果来自说明书更新，改这里并复核 quote
VERIFIED_ON = "2026-10-12"

FDA_ZEPBOUND = "https://www.accessdata.fda.gov/drugsatfda_docs/label/2024/217806s003lbl.pdf"

DRUGS: dict[str, dict[str, Any]] = {
    # ------------------------------------------------------------------ 替尔泊肽
    "tirzepatide": {
        "id": "tirzepatide",
        "name_cn": "替尔泊肽",
        "name_en": "tirzepatide",
        "brands": ["穆峰达", "Mounjaro", "Zepbound"],
        "kind": "GIP/GLP-1 双受体激动剂",
        "interval_days": 7,
        "dose_steps_mg": [2.5, 5.0, 7.5, 10.0, 12.5, 15.0],
        "max_dose_mg": 15.0,
        "start_dose_mg": 2.5,
        # —— 药代参数（决定曲线形状）
        "half_life_hours": 120.0,        # 说明书"约 5 天"
        "tmax_hours": 24.0,              # 说明书明写"中位 24 小时（8–72）"，这是真中位数
        "tmax_is_lower_bound": False,
        "tmax_range_hours": [8.0, 72.0],
        "bioavailability_pct": 80.0,
        "steady_state_weeks": 4,
        "accumulation_ratio": 1.75,      # 肥胖/超重人群；T2DM 为 1.6
        "clearance": "表观清除率 0.056 L/h (CV 20.9%)",
        "volume_of_distribution": "表观稳态分布容积约 9.7 L (CV 28.5%)",
        "protein_binding": "99%（白蛋白）",
        "molecular_weight_da": 4813.53,
        # 说明书**没有**"末次给药后 X 天清除"这种表述。我们只引用真实存在的原文，
        # 不自己按半衰期外推出一个天数——那属于编造。
        "elimination_after_last_dose": "说明书未给出清除天数。仅有的相关原文：过量处理一节提到"
                                       "「考虑到替尔泊肽约 5 天的半衰期」；欧盟 SmPC 基于长半衰期要求"
                                       "计划妊娠前至少停药 1 个月。",
        "elimination_quotes": [
            {"text": "A period of observation and treatment for these symptoms may be necessary, taking into "
                     "account the half-life of tirzepatide of approximately 5 days.",
             "section": "FDA Zepbound 说明书 · Overdosage"},
            {"text": "Tirzepatide should be discontinued at least 1 month before a planned pregnancy due to the "
                     "long half-life.",
             "section": "EU Mounjaro SmPC"},
        ],
        "sources": [
            {"claim": "消除半衰期约 5 天", "url": FDA_ZEPBOUND,
             "quote": "with an elimination half-life of approximately 5 days"},
            {"claim": "皮下注射后达峰时间中位 24 小时（8–72 小时）", "url": FDA_ZEPBOUND,
             "quote": "the median time (range) to maximum plasma concentration of tirzepatide is 24 hours (8 to 72 hours)"},
            {"claim": "绝对生物利用度 80%", "url": FDA_ZEPBOUND,
             "quote": "The mean absolute bioavailability of tirzepatide following subcutaneous administration is 80%."},
            {"claim": "每周一次给药 4 周后达稳态", "url": FDA_ZEPBOUND,
             "quote": "Steady-state plasma tirzepatide concentrations were achieved following 4 weeks of once weekly administration."},
            {"claim": "表观清除率 0.056 L/h", "url": FDA_ZEPBOUND,
             "quote": "The apparent population mean clearance (CV%) of tirzepatide in patients with overweight or obesity is 0.056 L/h (20.9%)"},
            {"claim": "每 4 周按 2.5 mg 递增，最大 15 mg 每周一次", "url": FDA_ZEPBOUND,
             "quote": "Increase the dosage in 2.5 mg increments after at least 4 weeks"},
            {"claim": "规格 2.5/5/7.5/10/12.5/15 mg", "url": FDA_ZEPBOUND,
             "quote": "Injection: 2.5 mg, 5 mg, 7.5 mg, 10 mg, 12.5 mg, or 15 mg per 0.5 mL"},
        ],
        "confidence": "high",
        "notes": "半衰期与 tmax 直接来自 FDA 说明书，可信度高。曲线用一室模型 + 一级吸收近似："
                 "真实吸收是 8–72 小时的分布，个体差异大；本工具只表达趋势。",
    },
    # ------------------------------------------------------------------ 司美格鲁肽
    "semaglutide": {
        "id": "semaglutide",
        "name_cn": "司美格鲁肽",
        "name_en": "semaglutide",
        "brands": ["诺和泰", "诺和盈", "Ozempic", "Wegovy"],
        "kind": "GLP-1 受体激动剂",
        "interval_days": 7,
        "dose_steps_mg": [0.25, 0.5, 1.0, 1.7, 2.4],
        "max_dose_mg": 2.4,
        "start_dose_mg": 0.25,
        "half_life_hours": 168.0,        # 说明书"约 1 周"（= 7×24 h 换算，说明书未印小时数）
        # 说明书只说"1–3 天"，没有给中位值。24 h 是范围**下界**，不是点估计——写清楚免得被误读。
        "tmax_hours": 24.0,
        "tmax_is_lower_bound": True,
        "tmax_range_hours": [24.0, 72.0],
        "bioavailability_pct": 89.0,
        "steady_state_weeks": 4,
        "clearance": "表观清除率约 0.05 L/h（体重 100 kg 受试者）",
        "volume_of_distribution": "表观分布容积约 12.5 L",
        "protein_binding": ">99%（白蛋白）",
        "molecular_weight_da": 4113.58,
        "elimination_after_last_dose": "Wegovy 2.4 mg：末次注射后约 5–7 周仍在循环中；"
                                       "Ozempic：末次给药后约 5 周。",
        "elimination_quotes": [
            {"text": "With an elimination half-life of approximately 1 week, semaglutide will be present in the "
                     "circulation for about 5 to 7 weeks after the last injectable dose of 2.4 mg.",
             "section": "FDA Wegovy 说明书 12.3"},
            {"text": "With an elimination half-life of approximately 1 week, semaglutide will be present in the "
                     "circulation for about 5 weeks after the last dose.",
             "section": "FDA Ozempic 说明书 12.3"},
        ],
        "sources": [
            {"claim": "消除半衰期约 1 周（165 小时）", "url": "https://www.accessdata.fda.gov/drugsatfda_docs/label/2023/209637s020lbl.pdf",
             "quote": "The elimination half-life of semaglutide is approximately 1 week"},
            {"claim": "皮下给药后 1–3 天达峰", "url": "https://www.accessdata.fda.gov/drugsatfda_docs/label/2023/209637s020lbl.pdf",
             "quote": "Maximum concentration of semaglutide is reached 1 to 3 days after a subcutaneous dose"},
        ],
        "confidence": "medium",
        "notes": "半衰期与达峰时间与公开说明书一致；生物利用度与分布容积的具体数字建议以最新说明书复核。",
    },
    # ------------------------------------------------------------------ 其他
    "dulaglutide": {
        "id": "dulaglutide",
        "name_cn": "度拉糖肽",
        "name_en": "dulaglutide",
        "brands": ["度易达", "Trulicity"],
        "kind": "GLP-1 受体激动剂",
        "interval_days": 7,
        "dose_steps_mg": [0.75, 1.5, 3.0, 4.5],
        "max_dose_mg": 4.5,
        "start_dose_mg": 0.75,
        "half_life_hours": 120.0,
        "tmax_hours": 48.0,              # 说明书给的 24–72 h 的中位数
        "tmax_is_lower_bound": False,
        "bioavailability_pct": None,
        "steady_state_weeks": 2,
        "clearance": None,
        "volume_of_distribution": None,
        "protein_binding": None,
        "molecular_weight_da": 59671.0,
        "elimination_after_last_dose": None,
        "sources": [
            {"claim": "半衰期约 5 天（需以最新说明书复核）", "url": "https://www.ema.europa.eu/en/documents/product-information/trulicity-epar-product-information_en.pdf",
             "quote": ""},
        ],
        "confidence": "low",
        "notes": "未逐条核验原文，仅作可选项保留；使用前请核对说明书。",
    },
    "liraglutide": {
        "id": "liraglutide",
        "name_cn": "利拉鲁肽",
        "name_en": "liraglutide",
        "brands": ["诺和力", "善纤达", "Victoza", "Saxenda"],
        "kind": "GLP-1 受体激动剂",
        "interval_days": 1,              # 每日一次，注意间隔与每周药不同
        "dose_steps_mg": [0.6, 1.2, 1.8, 2.4, 3.0],
        "max_dose_mg": 3.0,
        "start_dose_mg": 0.6,
        "half_life_hours": 13.0,
        "tmax_hours": 10.0,              # Victoza 8–12 h 的下界；Saxenda 为 11 h
        "tmax_is_lower_bound": True,
        "bioavailability_pct": 55.0,
        "steady_state_weeks": 1,
        "clearance": None,
        "volume_of_distribution": None,
        "protein_binding": ">98%",
        "molecular_weight_da": 3751.2,
        "elimination_after_last_dose": None,
        "sources": [
            {"claim": "半衰期约 13 小时；每日一次给药（需以最新说明书复核）",
             "url": "https://www.accessdata.fda.gov/drugsatfda_docs/label/2023/022341s033lbl.pdf", "quote": ""},
        ],
        "confidence": "low",
        "notes": "每日给药，与每周一次的剂量间隔假设不同，界面会按 interval_days 处理。未逐条核验原文。",
    },
}

DEFAULT_DRUG = "tirzepatide"


def get(drug_id: str | None) -> dict[str, Any]:
    return DRUGS.get((drug_id or DEFAULT_DRUG).strip().lower(), DRUGS[DEFAULT_DRUG])


def pk_profile(drug_id: str | None) -> dict[str, Any]:
    """只取出影响曲线形状与界面展示的参数。"""
    drug = get(drug_id)
    return {
        "drug_id": drug["id"],
        "med_name": drug["name_cn"],
        "half_life_hours": drug["half_life_hours"],
        "tmax_hours": drug["tmax_hours"],
        "dose_interval_days": drug["interval_days"],
        "dose_mg": drug["start_dose_mg"],
        "dose_steps_mg": drug["dose_steps_mg"],
        "max_dose_mg": drug["max_dose_mg"],
        "steady_state_weeks": drug["steady_state_weeks"],
        "brands": drug["brands"],
        "confidence": drug["confidence"],
        "sources": drug["sources"],
        "pk_notes": drug["notes"],
        "verified_on": VERIFIED_ON,
    }


def options() -> list[dict[str, Any]]:
    """给设置页用的下拉选项。"""
    return [
        {
            "value": drug["id"],
            "label": f"{drug['name_cn']}（{drug['name_en']}）",
            "brands": drug["brands"],
            "interval_days": drug["interval_days"],
            "dose_steps_mg": drug["dose_steps_mg"],
            "half_life_hours": drug["half_life_hours"],
            "tmax_hours": drug["tmax_hours"],
            "confidence": drug["confidence"],
        }
        for drug in DRUGS.values()
    ]


def sources_markdown() -> str:
    """生成数据来源清单（README 与"来源"弹窗共用）。"""
    lines = [f"药物参数核验日期：{VERIFIED_ON}", ""]
    for drug in DRUGS.values():
        lines.append(f"### {drug['name_cn']}（{drug['name_en']}）— 置信度 {drug['confidence']}")
        lines.append(f"- 半衰期 {drug['half_life_hours']} h；达峰 {drug['tmax_hours']} h；"
                     f"给药间隔 {drug['interval_days']} 天")
        for src in drug["sources"]:
            quote = f"：\"{src['quote']}\"" if src.get("quote") else "（未附原文，待复核）"
            lines.append(f"- {src['claim']}{quote}")
            lines.append(f"  来源：{src['url']}")
        if drug.get("notes"):
            lines.append(f"- 说明：{drug['notes']}")
        lines.append("")
    return "\n".join(lines)
