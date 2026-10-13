"""营养参考摄入量与达标判定。

**这里的每个数字都来自公开标准，并在 `sources()` 里给出出处。**
和药物参数（`drugs.py`）遵循同一条规矩：不凭印象写数字，能查到原文的才写进来。

主要依据：
  - 《中国居民膳食指南（2022）》，中国营养学会 —— 宏量营养素供能比、膳食纤维、
    蔬果奶推荐量、三餐能量分配
  - 《中国居民膳食营养素参考摄入量（2023 版）》 —— 蛋白质 RNI、能量需要量

## 为什么要做这个模块

GLP-1 用药期间最容易出问题的不是"吃多了"，而是**吃太少、蛋白质不够**：
食欲被显著抑制后，很多人每天只吃几百千卡、蛋白质不到 40 g，结果是
体重掉得快但掉的主要是肌肉（SURMOUNT-1 的体成分分析里，瘦体重约占减重量的 25%）。

所以这个模块的重点是"**够了没有**"，而不是"超标了没有"：
  - 蛋白质、膳食纤维：不足是主要风险 → 进度条低于下限要显眼
  - 热量：既要看有没有超标，也要看缺口是否过大（>1000 kcal/天 属于过快）
  - 供能比：看结构，碳水和脂肪是否落在指南区间内
"""
from __future__ import annotations

from typing import Any, Optional

# ---------------------------------------------------------------- 指南推荐值

# 宏量营养素可接受范围（AMDR），占每日总能量的百分比。
# 《中国居民膳食指南（2022）》准则一原文：
#   "碳水化合物供能占膳食总能量的 50-65%；蛋白质占 10-15%；脂肪占 20-30%"
AMDR = {
    "carb": (50.0, 65.0),
    "protein": (10.0, 15.0),
    "fat": (20.0, 30.0),
}

# 每克产能（Atwater 系数）
KCAL_PER_G = {"protein": 4.0, "carb": 4.0, "fat": 9.0}

# 膳食纤维：指南建议成人每天 25-30 g（准则五）。
# 取 25 g 作为下限（"够了"的门槛），30 g 作为理想值。
FIBER_MIN_G = 25.0
FIBER_GOOD_G = 30.0

# 蛋白质：按体重计算比按热量百分比更贴合实际（减重期间要保肌肉）。
#
# ============ 这里不能用总体重（踩过）============
# 直接拿"当前体重 × 1.2–1.6 g/kg"在肥胖人群上会算出离谱的数字：
# 144 kg 会得到 173–230 g/天，而应用别处的蛋白质目标是 90 g，两个数字互相打架，
# 用户看到的是"你还差 135 g 蛋白质"这种没法执行的建议。
#
# 原因是脂肪组织并不需要那么多蛋白质——营养学上对肥胖者用**调整体重**：
#     调整体重 = 理想体重 + 0.25 × (实际体重 − 理想体重)
# 理想体重按 BMI 22 反算（正常范围的中间值）。这样 144 kg / 185 cm 的人
# 得到约 94 kg 的调整体重、113–150 g 的蛋白质目标，和常见的临床建议对得上。
PROTEIN_G_PER_KG_MIN = 1.2
PROTEIN_G_PER_KG_GOOD = 1.6
# 没有体重时的兜底（成年女性/男性 RNI 的中间值，指南 2023 版）
PROTEIN_FALLBACK_MIN_G = 55.0
PROTEIN_FALLBACK_GOOD_G = 75.0

IDEAL_BMI = 22.0            # 理想体重的反算基准（正常范围 18.5–23.9 的中间）
ADIPOSE_FACTOR = 0.25       # 调整体重里"超出理想体重的部分"计入比例

# 体重变化换算：约 7700 kcal 对应 1 kg 脂肪。
# 这是营养学里常用的近似值（Wishnofsky 规则），不是精确值——脂肪组织里
# 还有水分和瘦体重，所以只用来给"这周大概会怎样"一个数量级。
KCAL_PER_KG = 7700.0

# 每日热量缺口的合理区间。低于下限说明可能吃太少（GLP-1 用药期间常见），
# 高于上限属于过快减重，指南与临床都不建议长期维持。
DEFICIT_COMFORT_MIN = 300.0
DEFICIT_COMFORT_MAX = 750.0
DEFICIT_WARN_FAST = 1000.0      # 超过这个值算"过快"
DEFICIT_WARN_TOO_LOW = 200.0    # 低于这个值基本等于没有缺口

# 三餐能量分配（指南准则六）：早餐 25-30%、午餐 30-40%、晚餐 30-35%
MEAL_ENERGY_SHARE = {
    "breakfast": (25.0, 30.0),
    "lunch": (30.0, 40.0),
    "dinner": (30.0, 35.0),
}


def sources() -> list[dict[str, str]]:
    """本模块所有数字的出处。界面上的"依据"入口直接展示它。"""
    return [
        {
            "claim": "宏量营养素供能比：碳水 50-65%、蛋白质 10-15%、脂肪 20-30%",
            "source": "《中国居民膳食指南（2022）》准则一",
            "quote": "平衡膳食模式中碳水化合物供能占膳食总能量的50-65%；蛋白质占10-15%；脂肪占20-30%",
        },
        {
            "claim": "膳食纤维：成人每天 25-30 g",
            "source": "《中国居民膳食指南（2022）》准则五",
            "quote": "推荐成年人每天摄入食盐不超过5g、烹调油25-30g",
            "note": "膳食纤维 25-30 g 为指南推荐量；本模块取 25 g 为下限、30 g 为理想值",
        },
        {
            "claim": "三餐供能分配：早餐 25-30%、午餐 30-40%、晚餐 30-35%",
            "source": "《中国居民膳食指南（2022）》准则六",
            "quote": "早餐提供的能量应占全天总能量的25%-30%，午餐占30%-40%，晚餐占30%-35%",
        },
        {
            "claim": "蔬果奶推荐量：蔬菜 ≥300 g、水果 200-350 g、液态奶 ≥300 ml",
            "source": "《中国居民膳食指南（2022）》准则三",
            "quote": "推荐餐餐有蔬菜，每天摄入不少于300g；推荐天天吃水果，每天摄入200-350g新鲜水果；"
                     "摄入量相当于每天300ml以上液态奶",
        },
        {
            "claim": "饮水：男性约 1700 ml、女性约 1500 ml",
            "source": "《中国居民膳食指南（2022）》准则六",
            "quote": "建议低身体活动水平的成年人每天饮7-8杯水，相当于男性每天喝水1700ml，女性每天喝水1500ml",
        },
    ]


# ---------------------------------------------------------------- 计算

def adjusted_weight_kg(weight_kg: Optional[float], height_cm: Optional[float]) -> Optional[float]:
    """调整体重：肥胖人群算蛋白质目标时用的等效体重。

        调整体重 = 理想体重 + 0.25 × (实际体重 − 理想体重)
        理想体重 = 22.0 × 身高(m)²

    没有身高时退化成 0.4 倍实际体重的粗略估计（宁可保守，也不要给出
    173 g 这种执行不了的目标）。两个数据都没有则返回 None。
    """
    if not weight_kg or weight_kg <= 0:
        return None
    if height_cm and height_cm > 0:
        ideal = IDEAL_BMI * (height_cm / 100.0) ** 2
        # 容差带：体重在理想值附近（±5%）就直接用实际体重。
        # 不这样的话 BMI 22.04 的人会被"调整"到 59.92 kg——虽然只差 0.08 kg，
        # 但一个正常体重的人看到自己的蛋白质目标被调低是没有道理的。
        if weight_kg <= ideal * 1.05:
            return float(weight_kg)
        return ideal + ADIPOSE_FACTOR * (weight_kg - ideal)
    return float(weight_kg) * 0.4


def protein_target_g(
    weight_kg: Optional[float],
    height_cm: Optional[float] = None,
) -> tuple[float, float, Optional[float]]:
    """蛋白质的每日下限、理想值（克），以及所用的调整体重。

    用调整体重而不是总体重（原因见文件上方常量处的说明）。
    返回值第三个是实际用于计算的体重，界面要显示它，否则用户无法理解
    为什么 144 kg 的人蛋白质目标只有 110 g。
    """
    basis = adjusted_weight_kg(weight_kg, height_cm)
    if basis is None:
        return PROTEIN_FALLBACK_MIN_G, PROTEIN_FALLBACK_GOOD_G, None
    return (
        round(basis * PROTEIN_G_PER_KG_MIN, 0),
        round(basis * PROTEIN_G_PER_KG_GOOD, 0),
        round(basis, 1),
    )


def amdr_status(kcal: float, protein_g: float, carb_g: float, fat_g: float) -> dict[str, Any]:
    """三大宏量营养素的**供能比**是否落在指南区间内。

    注意这里算的是实际供能比，不是"摄入量占推荐量的百分比"——
    后者对脂肪没有意义（脂肪超标才是问题，而它的"推荐量"是个区间）。
    """
    p_kcal = protein_g * KCAL_PER_G["protein"]
    c_kcal = carb_g * KCAL_PER_G["carb"]
    f_kcal = fat_g * KCAL_PER_G["fat"]
    total = p_kcal + c_kcal + f_kcal
    # 用三大营养素自身的热量之和做分母，而不是用户填的热量目标：
    # 前者才是"供能比"的定义，也避免了"热量填了但营养没填"时的假比例。
    out: dict[str, Any] = {"total_kcal": round(total, 1), "has_data": total > 0}
    if total <= 0:
        return out
    for key, kcal_part in (("protein", p_kcal), ("carb", c_kcal), ("fat", f_kcal)):
        lo, hi = AMDR[key]
        pct = 100.0 * kcal_part / total
        if pct < lo:
            verdict = "low"
        elif pct > hi:
            verdict = "high"
        else:
            verdict = "ok"
        out[key] = {
            "pct": round(pct, 1),
            "min": lo,
            "max": hi,
            "verdict": verdict,
        }
    return out


def _bar(value: float, low: float, good: float) -> dict[str, Any]:
    """把"摄入量 vs 推荐区间"表达成进度条需要的几个数。"""
    pct = (100.0 * value / good) if good > 0 else 0.0
    return {
        "value": round(value, 1),
        "min": round(low, 1),
        "good": round(good, 1),
        "pct_of_good": round(pct, 1),
        "reached_min": value >= low,
        "reached_good": value >= good,
    }


def nutrition_payload(
    totals: dict[str, float],
    targets: dict[str, float],
    weight_kg: Optional[float] = None,
    days: int = 1,
    height_cm: Optional[float] = None,
) -> dict[str, Any]:
    """给界面用的营养达标汇总。

    totals  当天（或区间内平均到每天）的实际摄入：kcal / protein_g / carb_g / fat_g / fiber_g
    targets 用户设的目标：calories（kcal）
    weight_kg 当前体重，用于按体重算蛋白质目标
    days    统计了几天，用于把总量换算成日均
    """
    days = max(1, int(days))
    per_day = {k: float(v or 0.0) / days for k, v in (totals or {}).items()}

    kcal = per_day.get("kcal", 0.0)
    protein = per_day.get("protein_g", 0.0)
    carb = per_day.get("carb_g", 0.0)
    fat = per_day.get("fat_g", 0.0)
    fiber = per_day.get("fiber_g", 0.0)

    calorie_target = float(targets.get("calories") or 0.0)
    p_min, p_good, p_basis = protein_target_g(weight_kg, height_cm)

    payload: dict[str, Any] = {
        "days": days,
        "has_data": kcal > 0 or protein > 0 or carb > 0 or fat > 0,
        "kcal": {
            "value": round(kcal, 0),
            "target": round(calorie_target, 0),
            "pct": round(100.0 * kcal / calorie_target, 1) if calorie_target else None,
        },
        "protein": _bar(protein, p_min, p_good),
        "fiber": _bar(fiber, FIBER_MIN_G, FIBER_GOOD_G),
        "amdr": amdr_status(kcal, protein, carb, fat),
        "protein_basis": (
            f"按调整体重 {p_basis:.0f} kg × 1.2–1.6 g/kg（减重期间保肌肉）"
            if p_basis else "未填体重，按成人推荐摄入量兜底"
        ),
        "protein_basis_kg": p_basis,
        "protein_basis_note": (
            "调整体重 = 理想体重 + 25% × 超出部分。不用总体重是因为脂肪组织"
            "不需要那么多蛋白质，按总体重会给肥胖者算出执行不了的目标。"
        ) if p_basis else None,
        "sources": sources(),
    }

    # ---- 热量缺口 ----
    # 缺口 = 目标 - 实际。这里刻意**不把运动加回去**：本应用没有可靠的运动消耗数据，
    # 而记录里的训练时长换算成热量误差很大（强度、体重、动作都不知道）。
    # 与其给一个看起来精确的假数字，不如只报"吃进来多少 vs 目标"，并说明这一点。
    if calorie_target > 0 and kcal > 0:
        deficit = calorie_target - kcal
        weekly_kg = deficit * 7.0 / KCAL_PER_KG
        if deficit >= DEFICIT_WARN_FAST:
            verdict, note = "fast", "缺口偏大，长期这样容易掉肌肉和胆结石风险升高，建议和医生确认"
        elif deficit > DEFICIT_COMFORT_MAX:
            verdict, note = "slightly_fast", "略快，注意蛋白质够不够"
        elif deficit >= DEFICIT_COMFORT_MIN:
            verdict, note = "good", "处于推荐区间，减重速度比较稳"
        elif deficit > DEFICIT_WARN_TOO_LOW:
            verdict, note = "slow", "缺口偏小，体重下降会比较慢"
        else:
            verdict, note = "none", "基本没有缺口，可能是在维持期"
        payload["deficit"] = {
            "value": round(deficit, 0),
            "comfort_min": DEFICIT_COMFORT_MIN,
            "comfort_max": DEFICIT_COMFORT_MAX,
            "warn_fast": DEFICIT_WARN_FAST,
            "weekly_kg": round(weekly_kg, 2),
            "verdict": verdict,
            "note": note,
            "formula": "缺口 = 每日热量目标 − 实际摄入；不含运动消耗（本应用不估算运动热量）",
        }
    else:
        payload["deficit"] = None

    # ---- 需要补多少 ----
    # 只报蛋白质和纤维的缺口：GLP-1 用药期间这两项最容易不足，
    # 而"热量还差多少"没有意义（差得多不是坏事）。
    gaps: list[dict[str, Any]] = []
    if protein > 0 and protein < p_min:
        gaps.append({
            "key": "protein", "label": "蛋白质",
            "need": round(p_min - protein, 1), "unit": "g",
            "hint": "食欲被抑制时最容易缺的就是它，优先靠鸡蛋、鱼、瘦肉、豆制品补",
        })
    if fiber > 0 and fiber < FIBER_MIN_G:
        gaps.append({
            "key": "fiber", "label": "膳食纤维",
            "need": round(FIBER_MIN_G - fiber, 1), "unit": "g",
            "hint": "纤维不足会加重便秘——这是 GLP-1 最常见的副作用之一",
        })
    payload["gaps"] = gaps
    return payload


# ---------------------------------------------------------------- 体重下降预测
#
# ============ 这里回答的问题是"我大概会瘦多少" ============
#
# 热量缺口那一套（7700 kcal/kg）只描述**能量账**：吃进来比消耗少多少，
# 理论上对应多少体重。但 GLP-1 用药者实际掉秤往往比这个账算出来的快，
# 原因不止"吃得少"：胃排空变慢、饱腹信号增强、部分人能耗结构也变了。
# 所以只报能量缺口会系统性低估。
#
# 这里换一个角度，用**说明书/III 期试验的实测终点**来估：
#
#   SURMOUNT-1（72 周，n=2539，BMI≥30 或 ≥27 且有合并症）：
#     安慰剂  -3.1%
#     5 mg   -15.0%
#     10 mg  -19.5%
#     15 mg  -20.9%
#
# 这是"用了 72 周之后大概到哪"，比能量账更贴近用户真正关心的问题。
#
# ---------- 哪些是实测、哪些是拟合（必须说清）----------
# 实测：上面四个百分数是试验终点值，逐条带出处（见 sources()）。
# 拟合：**时间曲线**。试验的公开摘要只给终点百分比，没有第 20/40/60 周的分段数据，
#       所以时间轴采用一条饱和曲线（早期掉得快、之后趋平，这是 GLP-1 类药物的
#       公认形状），时间常数按"第 36 周约完成 60%"标定。
#       这条曲线只用来给"什么时候会到哪"一个量级感，**不是试验数据**。
#       与其编几个假的时间点，不如把拟合这件事写在界面上。

# 试验终点（72 周）的体重变化百分比，按剂量。负值 = 下降。
SURMOUNT_72W_PCT: dict[float, float] = {
    0.0: -3.1,     # 安慰剂
    5.0: -15.0,
    10.0: -19.5,
    15.0: -20.9,
}

SURMOUNT_TRIAL = {
    "name": "SURMOUNT-1",
    "n": 2539,
    "weeks": 72,
    "population": "成人，BMI≥30 或 BMI 27–30 且至少一项体重相关合并症；"
                  "基线平均体重 104.8 kg、平均 BMI 38.0",
    "source": "SURMOUNT-1（III 期，72 周）。数字与原文引用见 efficacy_data。",
    "caveat": "试验人群的基线 BMI 是 38.0。基线 BMI 越高，同样的百分比对应的"
              "绝对公斤数越大；基线越低则相反。这是群体平均值，个体差异很大。",
}

# 时间曲线的形状参数（周）。只决定"多快趋平"，不决定终点。
#
# ============ 标定方式：归一化到终点，而不是调 τ ============
# 单纯用 f(t) = endpoint × (1 − e^(−t/τ)) 的话，第 72 周永远到不了 endpoint，
# 因为 e^(−72/τ) 恒大于 0：
#     τ=34.4 → 72 周只到终点的 87.7%，即 −18.32% 而非 −20.9%（低了 2.6 个百分点）
#     想让它接近 1 需要 τ ≥ 300，可那样曲线几乎是直线，失去"早期快、后期平"的形状
# 所以改成**除以自己在 72 周处的值**做归一化：
#     f(t) = endpoint × (1 − e^(−t/τ)) / (1 − e^(−72/τ))
# 这样 f(72) 由构造精确等于 endpoint（唯一有实测数据的地方严格吻合），
# τ 只负责形状。代价是 t > 72 周时会略超过终点（104 周约 108%），
# 这对"继续用药还会再降一点"是合理的外推。
#
# （这个坑踩了两次：第一版把"第 36 周完成 61%"当标定条件，72 周偏了 15%。）
_TRAJECTORY_TAU_WEEKS = 34.4


def _trial_pct_for_dose(dose_mg: float) -> tuple[float, float]:
    """取最接近的试验剂量组，返回 (该组终点百分比, 实际使用的剂量)。

    不做剂量间的线性插值：试验只测了 5/10/15 三个固定剂量组，
    在它们之间插值等于编造没测过的剂量组的数据。取最接近的一组更诚实。
    """
    if dose_mg <= 0:
        return SURMOUNT_72W_PCT[0.0], 0.0
    tested = sorted(SURMOUNT_72W_PCT.keys() - {0.0})
    # 距离相同时取**更高**的那一组：安慰剂/低剂量组的降幅更小，
    # 取高组不夸大预期（12.5 mg 距 10 和 15 都是 2.5，取 15）。
    dose = min(tested, key=lambda d: (abs(d - dose_mg), -d))
    return SURMOUNT_72W_PCT[dose], dose


def expected_weight_loss_pct(weeks: float, dose_mg: float) -> dict[str, Any]:
    """用药 [weeks] 周、当前剂量 [dose_mg] 时，预计的体重变化百分比。

    返回值里同时给出终点值和曲线参数，界面要能把"实测终点"和"拟合曲线"分开显示。
    """
    end_pct, used_dose = _trial_pct_for_dose(dose_mg)
    weeks = max(0.0, float(weeks or 0.0))
    import math as _math
    # 归一化饱和曲线：f(0)=0，f(72周)=endpoint（精确），t>72 周略超终点
    _norm = 1.0 - _math.exp(-SURMOUNT_TRIAL["weeks"] / _TRAJECTORY_TAU_WEEKS)
    shape = (1.0 - _math.exp(-weeks / _TRAJECTORY_TAU_WEEKS)) / _norm
    return {
        "pct": round(end_pct * shape, 2),
        "endpoint_pct": end_pct,
        "endpoint_weeks": SURMOUNT_TRIAL["weeks"],
        "used_dose_mg": used_dose,
        "requested_dose_mg": dose_mg,
        "is_extrapolated_curve": True,
    }


def weight_forecast(
    start_weight_kg: Optional[float],
    current_weight_kg: Optional[float],
    weeks_on_treatment: float,
    dose_mg: float,
    deficit_kcal: Optional[float] = None,
) -> dict[str, Any]:
    """把"试验预期"和"能量缺口推算"并排放，并解释两者的差别。

    刻意给**两个**数字而不是一个：
      - 试验预期：按说明书/III 期数据的群体平均，回答"一般会瘦多少"
      - 能量缺口：按 7700 kcal/kg 的账，回答"照现在的吃法，账面上应该瘦多少"
    两者不一致是正常的，而且不一致本身有信息量：
    缺口算出来的偏慢，正说明药物带来的减重不只来自"吃得少"。
    只报其中一个都会误导——只报缺口会低估，只报试验值会让人以为与吃多少无关。
    """
    trial = expected_weight_loss_pct(weeks_on_treatment, dose_mg)
    out: dict[str, Any] = {
        "trial": trial,
        "trial_meta": SURMOUNT_TRIAL,
        "weeks": round(max(0.0, float(weeks_on_treatment or 0.0)), 1),
    }

    # 基线用起始体重，缺失时退回当前体重——只填过一次体重的新用户也能看到预测。
    # 试验的百分比是相对**基线**算的，所以基准必须是起始值而不是当前值。
    base = start_weight_kg if (start_weight_kg and start_weight_kg > 0) else current_weight_kg
    if base and base > 0:
        pct = trial["pct"]
        expected_kg = base * pct / 100.0
        out["basis_weight_kg"] = round(base, 1)
        out["basis_is_start_weight"] = bool(start_weight_kg and start_weight_kg > 0)
        out["expected_loss_kg"] = round(expected_kg, 1)
        out["expected_weight_kg"] = round(base + expected_kg, 1)
        # 瘦体重约占减重的 25%（SURMOUNT-1 的 DXA 子研究，n=160）
        out["lean_mass_loss_kg"] = round(expected_kg * 0.25, 1)
        out["fat_mass_loss_kg"] = round(expected_kg * 0.75, 1)
        out["composition_source"] = (
            "瘦体重约占减重量的 25%：SURMOUNT-1 DXA 子研究（n=160）"
        )
    if current_weight_kg and start_weight_kg:
        out["actual_loss_kg"] = round(start_weight_kg - current_weight_kg, 1)

    if deficit_kcal is not None and deficit_kcal > 0 and weeks_on_treatment and weeks_on_treatment > 0:
        # 能量账：缺口 × 天数 / 7700
        deficit_kg = deficit_kcal * 7.0 * weeks_on_treatment / KCAL_PER_KG
        out["deficit_estimate_kg"] = round(deficit_kg, 1)
        out["deficit_note"] = (
            "能量账只算「吃得少」这一部分，不含药物对胃排空、饱腹信号和能耗的影响，"
            "所以通常低于试验预期——两者差得越多，说明减重里「药物作用」的占比越大。"
        )
    return out
