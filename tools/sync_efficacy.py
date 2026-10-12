#!/usr/bin/env python3
"""把核验过的说明书 JSON 同步成应用用的 efficacy.py 数据。

为什么要有这个脚本：手抄 85KB 的核验结果到 Python 字面量，一定会抄错，
而且以后说明书更新时无法复核。这里做纯机械映射——JSON 里是 null 的，
生成出来还是 None；JSON 里没写的字段，生成出来也不写。

用法：
  python3 tools/sync_efficacy.py ../glp1_labels/glp1_weight_efficacy_verified.json

产物：glpmate/efficacy_data.py（自动生成，不要手改）
"""

from __future__ import annotations

import json
import pprint
import sys
from pathlib import Path

# 核验 JSON 里的药物键 → 应用里的 drug_id
DRUG_MAP = {
    "tirzepatide": "tirzepatide",
    "semaglutide_2_4": "semaglutide",
    "liraglutide_3_0": "liraglutide",
    "dulaglutide": "dulaglutide",
}
# 每个药物取哪些适应症（键名 → 展示用 kind）
INDICATION_KEYS = {
    "tirzepatide": [("indication_weight", "weight"), ("indication_diabetes", "diabetes")],
    "semaglutide_2_4": [("indication_weight", "weight")],
    "liraglutide_3_0": [("indication_weight", "weight")],
    "dulaglutide": [("indication_diabetes", "diabetes")],
}

# 核验 JSON 里用过的臂字段。名字对不上就会静默丢数据——所以有测试兜底。
ARM_FIELDS = (
    # 体重管理
    "dose_mg", "n", "weight_change_pct", "weight_change_kg",
    "ge5_pct", "ge10_pct", "ge15_pct", "ge20_pct",
    "discontinuation_of_study_drug_any_cause_pct",
    # 糖尿病
    "hba1c_change_pct", "hba1c_change_mmol",
    # 不良反应
    "nausea_pct", "vomiting_pct", "diarrhoea_pct", "constipation_pct",
    "abdominal_pain_pct", "dyspepsia_pct", "injection_site_reaction_pct",
    "discontinuation_due_to_ae_pct", "gi_ae_discontinuation_pct", "any_gi_ae_pct",
    # 标签
    "arm", "placebo",
)


def clean_arm(arm: dict) -> dict:
    out = {}
    for key in ARM_FIELDS:
        if key in arm:
            out[key] = arm[key]
    # 保留核验里对单臂的脚注（例如"未控制 I 类错误率"）
    for extra in ("note", "footnote", "flags"):
        if arm.get(extra):
            out[extra] = arm[extra]
    return out


def srcurl(src: dict) -> dict:
    return {
        "claim": src.get("claim") or "",
        "url": src.get("url") or "",
        "section": src.get("section") or src.get("page") or "",
        "quote": src.get("quote") or "",
    }


def build(data: dict) -> dict:
    drugs: dict[str, dict] = {}
    for json_key, drug_id in DRUG_MAP.items():
        raw = data["drugs"].get(json_key)
        if not raw:
            continue
        entry: dict = {
            "drug_id": drug_id,
            "brand_names": raw.get("brand_names") or [],
            "indications": [],
        }
        for json_section, kind in INDICATION_KEYS.get(json_key, []):
            section = raw.get(json_section)
            if not section:
                continue
            entry["indications"].append({
                "kind": kind,
                "trial": section.get("trial"),
                "population": section.get("population"),
                "duration_weeks": section.get("duration_weeks"),
                "estimand": section.get("estimand"),
                "arms": [clean_arm(a) for a in (section.get("arms") or [])],
                "between_arm_differences_vs_placebo": section.get("between_arm_differences_vs_placebo"),
                # 额外研究（如 SURMOUNT-2 合并糖尿病人群、SURMOUNT-3/4）也要带走臂数据，
                # 只是标题不同——漏掉会让"糖尿病人群减重更少"这个关键差异消失
                "additional_studies": {
                    name: {**study, "arms": [clean_arm(a) for a in (study.get("arms") or [])]}
                    for name, study in (section.get("additional_studies") or {}).items()
                } or None,
                "cardiovascular_outcomes": section.get("cardiovascular_outcomes"),
                "sources": [srcurl(s) for s in (section.get("sources") or [])],
                "verified": True,
            })
        ae = raw.get("adverse_events") or {}
        if ae:
            entry["gi_side_effects"] = {
                "trial": ae.get("trial"),
                "arms": [clean_arm(a) for a in (ae.get("arms") or [])],
                "leading_discontinuation_reactions": ae.get("leading_discontinuation_reactions"),
                "other_reactions_of_note": ae.get("other_reactions_of_note"),
                "sources": [srcurl(s) for s in (ae.get("sources") or [])],
                "verified": True,
            }
        if raw.get("body_composition"):
            entry["body_composition"] = raw["body_composition"]
        if raw.get("notes"):
            entry["notes"] = raw["notes"]
        entry["confidence"] = raw.get("confidence")
        entry["label_urls"] = raw.get("label_urls")
        drugs[drug_id] = entry
    return drugs


HEADER = '''"""由 tools/sync_efficacy.py 从核验过的说明书 JSON 自动生成——请勿手改。

源文件：glp1_labels/glp1_weight_efficacy_verified.json
生成方式：pdftotext 抽取厂商说明书 PDF 原文 → 逐条带 quote 核验 → 本脚本机械映射。

改数据的正确做法：改核验 JSON（或重新核验），然后重跑同步脚本。
手改本文件会在下次同步时被覆盖，而且会丢掉"每个数字对应哪句原文"的链。
"""

from __future__ import annotations

from typing import Any

'''


def main() -> int:
    src = Path(sys.argv[1] if len(sys.argv) > 1
               else Path(__file__).resolve().parent.parent.parent / "glp1_labels"
               / "glp1_weight_efficacy_verified.json")
    if not src.is_file():
        print(f"找不到核验文件：{src}", file=sys.stderr)
        return 2
    data = json.loads(src.read_text(encoding="utf-8"))
    drugs = build(data)
    out = Path(__file__).resolve().parent.parent / "glpmate" / "efficacy_data.py"
    # 用 pprint 而不是 json.dumps：JSON 的 null/true/false 不是合法 Python 字面量。
    # sort_dicts=False 保持核验文件里的字段顺序，便于人工对照。
    literal = pprint.pformat(drugs, width=118, sort_dicts=False)
    body = [HEADER, f"VERIFIED_ON = {data['meta'].get('compiled', '')!r}\n\n",
            "EFFICACY: dict[str, dict[str, Any]] = ", literal, "\n"]
    out.write_text("".join(body), encoding="utf-8")

    counts = {k: (len(v.get("indications", [])), len(v.get("gi_side_effects", {}).get("arms", [])))
              for k, v in drugs.items()}
    print(f"已生成 {out}")
    print(f"药物数：{len(drugs)}")
    for drug_id, (n_ind, n_ae) in counts.items():
        print(f"  {drug_id:14s} 适应症 {n_ind} 条 · 不良反应臂 {n_ae} 条")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
