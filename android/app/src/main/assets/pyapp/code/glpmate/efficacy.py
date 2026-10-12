"""注册试验疗效参考数据（只做"参考刻度"，不做预测）。

数据本体不在这里：它由 `tools/sync_efficacy.py` 从核验过的说明书 JSON
（`glp1_labels/glp1_weight_efficacy_verified.json`）机械生成到 `efficacy_data.py`。
本模块只负责三件事：

1. 按药物取出数据（`get` / `payload`）；
2. 统一挂上免责声明与跨试验警告；
3. 强制"未核验的数字一律不下发"——核验里是 null 的，接口也必须返回 None。

为什么这么绕：疗效数字比药代参数更容易被误读（"别人 72 周掉了 20.9%，
我才掉 5%，是不是药没用？"），所以每个数字都必须能追到说明书原文，
并且必须和"人群 + 终点周数 + 统计口径"一起出现。
"""

from __future__ import annotations

from typing import Any

from .efficacy_data import EFFICACY, VERIFIED_ON

__all__ = ["EFFICACY", "VERIFIED_ON", "DISCLAIMER", "CROSS_TRIAL_CAVEAT", "get", "payload"]

CROSS_TRIAL_CAVEAT = (
    "不同试验的数字绝不能直接横向比较。每个数字都来自不同的人群（是否合并 2 型糖尿病、"
    "BMI 门槛、基线体重与 HbA1c）、不同的背景治疗与生活方式干预、不同的导入期或停药设计、"
    "不同的终点周数，以及不同的统计口径（治疗期估计 / 在治估计 / 缺失值插补方式）。"
    "列出来只是给你一个数量级参考，不是对你个人效果的预期。"
)

DISCLAIMER = (
    "以下数字来自厂商说明书里注册试验的人群平均结果，不是对你的预测，"
    "也不能用来判断你该不该加量。体重变化受剂量、饮食、运动、依从性和个体差异影响，"
    "任何调整都请和医生讨论。"
)

# 这些字段未核验时必须为 None，不能留给前端兜底
_NUMERIC_ARM_FIELDS = (
    "weight_change_pct", "weight_change_kg", "ge5_pct", "ge10_pct", "ge15_pct", "ge20_pct",
    "hba1c_change_pct", "nausea_pct", "vomiting_pct", "diarrhoea_pct", "constipation_pct",
    "discontinued_due_to_ae_pct", "discontinued_due_to_gi_pct", "any_gi_pct",
)


def get(drug_id: str | None) -> dict[str, Any]:
    """取某款药的数据；没有就返回空壳，绝不返回别的药的数据。"""
    key = (drug_id or "tirzepatide").strip().lower()
    if key in EFFICACY:
        return EFFICACY[key]
    return {"drug_id": drug_id, "brand_names": [], "indications": [],
            "gi_side_effects": {"arms": [], "sources": []}, "verified": False}


def payload(drug_id: str | None) -> dict[str, Any]:
    """给前端的完整响应：数据 + 免责声明 + 跨试验警告 + 核验日期。"""
    data = dict(get(drug_id))
    for indication in data.get("indications", []):
        if not indication.get("sources"):
            indication["verified"] = False          # 没有原文引用 = 不算核验
        if not indication.get("verified", True):
            for arm in indication.get("arms", []):
                for field in _NUMERIC_ARM_FIELDS:
                    if field in arm:
                        arm[field] = None
    data["disclaimer"] = DISCLAIMER
    data["cross_trial_caveat"] = CROSS_TRIAL_CAVEAT
    data["verified_on"] = VERIFIED_ON
    data["has_data"] = bool(data.get("indications"))
    return data
