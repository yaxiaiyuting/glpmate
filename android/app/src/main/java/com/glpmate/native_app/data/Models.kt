package com.glpmate.native_app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 后端数据模型。
 *
 * 为什么用手写解析而不是 kotlinx.serialization / Gson：
 * 后端返回的 JSON 里有大量"字段可能缺失、可能是 null、meta 是任意形状的对象"
 * 的情况（记录类型有 15 种，meta 各不同）。反射式序列化在这里要么需要一堆
 * @Serializable 壳类，要么在字段缺失时直接抛异常。手写解析更笨但更可控，
 * 而且能对缺失字段给出明确的默认值——这一层的错误会让界面显示错误数字，
 * 所以"显式处理"比"少写代码"重要。
 *
 * 所有解析函数都对缺字段/类型不符保持容错，绝不抛异常给界面。
 */

// ---------------------------------------------------------------- 基础取值
private fun JSONObject.str(key: String, fallback: String = ""): String =
    if (isNull(key)) fallback else optString(key, fallback)

private fun JSONObject.dbl(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

private fun JSONObject.int(key: String, fallback: Int = 0): Int =
    if (isNull(key)) fallback else optInt(key, fallback)

private fun JSONObject.obj(key: String): JSONObject? =
    if (isNull(key)) null else optJSONObject(key)

private fun JSONObject.arr(key: String): JSONArray =
    if (isNull(key)) JSONArray() else (optJSONArray(key) ?: JSONArray())

private fun JSONObject.strMap(key: String): Map<String, String> {
    val o = obj(key) ?: return emptyMap()
    val out = mutableMapOf<String, String>()
    for (k in o.keys()) out[k] = o.optString(k, "")
    return out
}

// ---------------------------------------------------------------- 记录
data class RecordView(
    val id: Int,
    val type: String,
    val at: String,
    val date: String,
    val time: String,
    val title: String,
    val subtitle: String,
    val value: Double?,
    val unit: String,
    val meta: Map<String, String>,
) {
    val isDose: Boolean get() = type == "dose"
    val isFood: Boolean get() = type == "food"
    val isWater: Boolean get() = type == "water"
    val isWeight: Boolean get() = type == "weight"

    /** meta 是 JSON，数值字段统一从这里取，避免各处重复 parse。 */
    fun metaNum(key: String): Double = meta[key]?.toDoubleOrNull() ?: 0.0

    companion object {
        fun from(o: JSONObject): RecordView {
            val metaObj = o.obj("meta")
            val meta = mutableMapOf<String, String>()
            if (metaObj != null) {
                for (k in metaObj.keys()) {
                    val v = metaObj.opt(k)
                    // items / hr_series 这类嵌套结构不塞进 String map，需要时单独取
                    if (v is JSONObject || v is JSONArray) continue
                    meta[k] = if (v == null || v == JSONObject.NULL) "" else v.toString()
                }
            }
            return RecordView(
                id = o.int("id"),
                type = o.str("type"),
                at = o.str("at"),
                date = o.str("date"),
                time = o.str("time"),
                title = o.str("title"),
                subtitle = o.str("subtitle"),
                value = o.dbl("value"),
                unit = o.str("unit"),
                meta = meta,
            )
        }
    }
}

// ---------------------------------------------------------------- 趋势点
data class SeriesPoint(val t: String, val v: Double)

// ---------------------------------------------------------------- 药物水平
data class DrugInfo(
    val id: String,
    val nameCn: String,
    val nameEn: String,
    val brands: List<String>,
    val confidence: String,
    val steadyStateWeeks: Int?,
    val elimination: String,
    val notes: String,
    val verifiedOn: String,
) {
    companion object {
        fun from(o: JSONObject?): DrugInfo? {
            if (o == null) return null
            val brands = mutableListOf<String>()
            val b = o.arr("brands")
            for (i in 0 until b.length()) brands.add(b.optString(i))
            return DrugInfo(
                id = o.str("id"),
                nameCn = o.str("name_cn"),
                nameEn = o.str("name_en"),
                brands = brands,
                confidence = o.str("confidence"),
                steadyStateWeeks = o.dbl("steady_state_weeks")?.toInt(),
                elimination = o.str("elimination_after_last_dose"),
                notes = o.str("notes"),
                verifiedOn = o.str("verified_on"),
            )
        }
    }
}

data class LevelPoint(val t: String, val mg: Double, val pct: Double, val pctMax: Double)

data class LevelInfo(
    val pctPeak: Double,
    val pctMax: Double,
    val currentMg: Double,
    val baselineMg: Double,
    val maxMg: Double,
    val trend: String,
    val halfLifeHours: Double,
    val tmaxHours: Double,
    val series: List<LevelPoint>,
    val disclaimer: String,
    val drug: DrugInfo?,
) {
    companion object {
        fun from(o: JSONObject?): LevelInfo? {
            if (o == null) return null
            val series = mutableListOf<LevelPoint>()
            val arr = o.arr("series")
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                series.add(
                    LevelPoint(
                        t = p.str("t"),
                        mg = p.dbl("mg") ?: 0.0,
                        pct = p.dbl("pct") ?: 0.0,
                        pctMax = p.dbl("pct_max") ?: 0.0,
                    )
                )
            }
            return LevelInfo(
                pctPeak = o.dbl("pct_peak") ?: 0.0,
                pctMax = o.dbl("pct_max") ?: 0.0,
                currentMg = o.dbl("current_mg") ?: 0.0,
                baselineMg = o.dbl("baseline_mg") ?: 0.0,
                maxMg = o.dbl("max_mg") ?: 0.0,
                trend = o.str("trend"),
                halfLifeHours = o.dbl("half_life_hours") ?: 0.0,
                tmaxHours = o.dbl("tmax_hours") ?: 0.0,
                series = series,
                disclaimer = o.str("disclaimer"),
                drug = DrugInfo.from(o.obj("drug")),
            )
        }
    }
}

// ---------------------------------------------------------------- 下次剂量
data class NextDose(
    val date: String,
    val time: String,
    val weekdayCn: String,
    val remainingDays: Int,
    val isDueToday: Boolean,
    val overdue: Boolean,
    val doseMg: Double,
    val medName: String,
    val intervalDays: Int,
) {
    companion object {
        fun from(o: JSONObject?): NextDose? {
            if (o == null) return null
            return NextDose(
                date = o.str("date"),
                time = o.str("time"),
                weekdayCn = o.str("weekday_cn"),
                remainingDays = o.int("remaining_days"),
                isDueToday = o.optBoolean("is_due_today", false),
                overdue = o.optBoolean("overdue", false),
                doseMg = o.dbl("dose_mg") ?: 0.0,
                medName = o.str("med_name"),
                intervalDays = o.int("interval_days", 7),
            )
        }
    }
}

// ---------------------------------------------------------------- 今日统计
data class TargetPair(val v: Double, val t: Double) {
    companion object {
        fun from(o: JSONObject?): TargetPair =
            TargetPair(v = o?.dbl("v") ?: 0.0, t = o?.dbl("t") ?: 0.0)
    }
}

data class TodayStats(
    val calories: TargetPair,
    val protein: TargetPair,
    val carbs: TargetPair,
    val fat: TargetPair,
    val fiber: TargetPair,
    val water: TargetPair,
    val mealsLogged: Int,
    val doseTaken: Boolean,
    val trainingMin: Double,
    val sideEffects: List<RecordView>,
) {
    companion object {
        fun from(o: JSONObject?): TodayStats {
            val sideEffects = mutableListOf<RecordView>()
            if (o != null) {
                val arr = o.arr("side_effects")
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { sideEffects.add(RecordView.from(it)) }
                }
            }
            return TodayStats(
                calories = TargetPair.from(o?.obj("calories")),
                protein = TargetPair.from(o?.obj("protein")),
                carbs = TargetPair.from(o?.obj("carbs")),
                fat = TargetPair.from(o?.obj("fat")),
                fiber = TargetPair.from(o?.obj("fiber")),
                water = TargetPair.from(o?.obj("water")),
                mealsLogged = o?.int("meals_logged") ?: 0,
                doseTaken = o?.optBoolean("dose_taken", false) ?: false,
                trainingMin = o?.dbl("training_min") ?: 0.0,
                sideEffects = sideEffects,
            )
        }
    }
}

// ---------------------------------------------------------------- 档案
data class Profile(
    val displayName: String,
    val drugId: String,
    val medName: String,
    val doseMg: Double,
    val intervalDays: Int,
    val doseWeekday: Int,
    val doseTime: String,
    val halfLifeHours: Double,
    val tmaxHours: Double,
    val targetWeightKg: Double?,
    val startWeightKg: Double?,
    val treatmentStart: String?,
) {
    companion object {
        fun from(o: JSONObject?): Profile {
            if (o == null) return emptyProfile()
            return Profile(
                displayName = o.str("display_name", "朋友"),
                drugId = o.str("drug_id", "tirzepatide"),
                medName = o.str("med_name", "替尔泊肽"),
                doseMg = o.dbl("dose_mg") ?: 0.0,
                intervalDays = o.int("dose_interval_days", 7),
                doseWeekday = o.int("dose_weekday", 0),
                doseTime = o.str("dose_time", "12:00"),
                halfLifeHours = o.dbl("half_life_hours") ?: 120.0,
                tmaxHours = o.dbl("tmax_hours") ?: 24.0,
                targetWeightKg = o.dbl("target_weight_kg"),
                startWeightKg = o.dbl("start_weight_kg"),
                treatmentStart = if (o.isNull("treatment_start")) null else o.str("treatment_start"),
            )
        }

        fun emptyProfile() = Profile(
            displayName = "朋友", drugId = "tirzepatide", medName = "替尔泊肽",
            doseMg = 0.0, intervalDays = 7, doseWeekday = 0, doseTime = "12:00",
            halfLifeHours = 120.0, tmaxHours = 24.0,
            targetWeightKg = null, startWeightKg = null, treatmentStart = null,
        )
    }
}

// ---------------------------------------------------------------- 体重
data class WeightInfo(
    val latest: Double?,
    val start: Double?,
    val delta: Double?,
    val goal: Double?,
    val series: List<SeriesPoint>,
) {
    companion object {
        fun from(o: JSONObject?): WeightInfo {
            val series = mutableListOf<SeriesPoint>()
            if (o != null) {
                val arr = o.arr("series")
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    series.add(SeriesPoint(p.str("t"), p.dbl("v") ?: 0.0))
                }
            }
            return WeightInfo(
                latest = o?.dbl("latest"),
                start = o?.dbl("start"),
                delta = o?.dbl("delta"),
                goal = o?.dbl("goal"),
                series = series,
            )
        }
    }
}

// ---------------------------------------------------------------- 总额度
data class Quota(val used: Int, val limit: Int) {
    val remaining: Int get() = (limit - used).coerceAtLeast(0)
    companion object {
        fun from(o: JSONObject?): Quota = Quota(o?.int("used") ?: 0, o?.int("limit") ?: 20)
    }
}

// ---------------------------------------------------------------- 首页总包
data class Bootstrap(
    val today: String,
    val weekday: Int,
    val dayIndex: Int,
    val profile: Profile,
    val stats: TodayStats,
    val nextDose: NextDose?,
    val level: LevelInfo?,
    val weight: WeightInfo,
    val completed: List<RecordView>,
    val quota: Quota,
    val sideEffectChips: List<String>,
    /** 后端下发的提醒时间表（原始 JSON 字符串）。原生侧用它排本地闹钟。 */
    val scheduleJson: String?,
    /** BMI（身高没填时 bmi 为 null）。 */
    val bmi: BmiInfo?,
    /** 营养达标（对照《中国居民膳食指南（2022）》）。 */
    val nutrition: Nutrition?,
    /** 体重下降预测（SURMOUNT-1 实测终点 + 能量账）。 */
    val weightForecast: WeightForecast?,
) {
    companion object {
        fun from(o: JSONObject): Bootstrap {
            val completed = mutableListOf<RecordView>()
            val arr = o.arr("completed")
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { completed.add(RecordView.from(it)) }
            }
            val chips = mutableListOf<String>()
            val chipArr = o.arr("side_effect_chips")
            for (i in 0 until chipArr.length()) chips.add(chipArr.optString(i))

            return Bootstrap(
                today = o.str("today"),
                weekday = o.int("weekday"),
                dayIndex = o.int("day_index", 1),
                profile = Profile.from(o.obj("profile")),
                stats = TodayStats.from(o.obj("today_stats")),
                nextDose = NextDose.from(o.obj("next_dose")),
                level = LevelInfo.from(o.obj("level")),
                weight = WeightInfo.from(o.obj("weight")),
                completed = completed,
                quota = Quota.from(o.obj("coach_quota")),
                sideEffectChips = chips,
                scheduleJson = o.optJSONArray("schedule")?.toString(),
                bmi = BmiInfo.from(o.optJSONObject("bmi")),
                nutrition = Nutrition.from(o.optJSONObject("nutrition")),
                weightForecast = WeightForecast.from(o.optJSONObject("weight_forecast")),
            )
        }
    }
}

// ---------------------------------------------------------------- 药物选项
/**
 * `/api/drugs` 里的一支药。
 *
 * doseStepsMg 是**说明书核准的剂量阶梯**（例如替尔泊肽 2.5/5/7.5/10/12.5/15），
 * 界面上剂量只能从这个列表里选，不允许自由输入——自由输入会让人打出
 * 说明书里不存在的剂量，这是用药安全底线。
 */
data class DrugOption(
    val value: String,
    val label: String,
    val brands: List<String>,
    val intervalDays: Int,
    val doseStepsMg: List<Double>,
    val halfLifeHours: Double,
    val tmaxHours: Double,
    val confidence: String,
) {
    val isDaily: Boolean get() = intervalDays <= 1
    val intervalText: String get() = if (isDaily) "每天" else "每 $intervalDays 天"

    companion object {
        fun from(o: JSONObject): DrugOption {
            val steps = mutableListOf<Double>()
            val arr = o.optJSONArray("dose_steps_mg")
            if (arr != null) {
                for (i in 0 until arr.length()) steps.add(arr.optDouble(i))
            }
            val brands = mutableListOf<String>()
            val b = o.optJSONArray("brands")
            if (b != null) {
                for (i in 0 until b.length()) brands.add(b.optString(i))
            }
            return DrugOption(
                value = o.optString("value"),
                label = o.optString("label"),
                brands = brands,
                intervalDays = o.optInt("interval_days", 7),
                doseStepsMg = steps,
                halfLifeHours = o.optDouble("half_life_hours", 120.0),
                tmaxHours = o.optDouble("tmax_hours", 24.0),
                confidence = o.optString("confidence", "medium"),
            )
        }
    }
}

// ---------------------------------------------------------------- 提醒项
data class ScheduleItem(
    val key: String,
    val label: String,
    val start: String,
    val end: String,
    val enabled: Boolean,
) {
    companion object {
        fun from(o: JSONObject) = ScheduleItem(
            key = o.optString("key"),
            label = o.optString("label"),
            start = o.optString("start", "08:00"),
            end = o.optString("end", "22:00"),
            enabled = o.optBoolean("enabled", true),
        )
    }
}


// ---------------------------------------------------------------- BMI
/**
 * 身高体重指数。分级用**中国成人标准**（WS/T 428-2013：超重 ≥24，肥胖 ≥28），
 * 不是 WHO 的 25/30——门诊和体检报告跟的是中国标准，用错标准会让
 * BMI 25~30 之间的人对不上自己的体检结论。
 *
 * 身高没填时 bmi 为 null、category.key 为 "unknown"，界面应该提示去填身高，
 * 而不是拿默认身高编一个数字。
 */
data class BmiCategory(
    val key: String,
    val label: String,
    val note: String,
) {
    val isUnknown: Boolean get() = key == "unknown"
    val isNormal: Boolean get() = key == "normal"

    companion object {
        fun from(o: JSONObject?) = BmiCategory(
            key = o?.optString("key").orEmpty().ifBlank { "unknown" },
            label = o?.optString("label").orEmpty().ifBlank { "未计算" },
            note = o?.optString("note").orEmpty(),
        )
    }
}

data class BmiInfo(
    val heightCm: Double?,
    val weightKg: Double?,
    val bmi: Double?,
    val category: BmiCategory,
    val startBmi: Double?,
    val bmiChange: Double?,
    val targetBmi: Double?,
    val targetCategory: BmiCategory?,
    val healthyMinKg: Double?,
    val healthyMaxKg: Double?,
    /** 还要减多少公斤才进正常范围；已经正常或没身高时为 null。 */
    val toHealthyKg: Double?,
    val standard: String,
) {
    val hasBmi: Boolean get() = bmi != null

    companion object {
        fun from(o: JSONObject?): BmiInfo? {
            if (o == null) return null
            val range = o.optJSONObject("healthy_range")
            fun num(key: String): Double? =
                if (o.isNull(key)) null else o.optDouble(key).takeIf { !it.isNaN() }
            return BmiInfo(
                heightCm = num("height_cm"),
                weightKg = num("weight_kg"),
                bmi = num("bmi"),
                category = BmiCategory.from(o.optJSONObject("category")),
                startBmi = num("start_bmi"),
                bmiChange = num("bmi_change"),
                targetBmi = num("target_bmi"),
                targetCategory = o.optJSONObject("target_category")?.let { BmiCategory.from(it) },
                healthyMinKg = range?.let { if (it.isNull("min_kg")) null else it.optDouble("min_kg") },
                healthyMaxKg = range?.let { if (it.isNull("max_kg")) null else it.optDouble("max_kg") },
                toHealthyKg = num("to_healthy_kg"),
                standard = o.optString("standard"),
            )
        }
    }
}

// ---------------------------------------------------------------- 教练消息
data class CoachCard(
    val id: String,
    val kind: String,
    val title: String,
    val summary: String,
    val status: String,
) {
    val isPending: Boolean get() = status == "pending" || status.isBlank()
}

data class CoachMessage(
    val id: Int,
    val role: String,
    val text: String,
    val at: String,
    val time: String,
    val card: CoachCard?,
    val isError: Boolean,
) {
    val isUser: Boolean get() = role == "user"

    companion object {
        fun from(o: JSONObject): CoachMessage {
            val cardObj = if (o.isNull("card")) null else o.optJSONObject("card")
            val card = cardObj?.let {
                val payload = it.optJSONObject("payload")
                CoachCard(
                    id = it.optString("id"),
                    kind = it.optString("kind"),
                    title = payload?.optString("title").orEmpty(),
                    summary = payload?.optString("summary").orEmpty(),
                    status = it.optString("status"),
                )
            }
            val meta = o.optJSONObject("meta")
            return CoachMessage(
                id = o.optInt("id"),
                role = o.optString("role"),
                text = o.optString("text"),
                at = o.optString("at"),
                time = o.optString("time"),
                card = card,
                isError = meta?.optBoolean("error", false) ?: false,
            )
        }
    }
}

// ---------------------------------------------------------------- 营养达标
/**
 * 一个"摄入量 vs 推荐区间"的进度条数据。
 *
 * 注意 min/good 是**推荐区间的下限和理想值**（不是一个点目标）：
 * 蛋白质和纤维都是"够了就行"，超过下限才算达标。
 */
data class NutrientBar(
    val value: Double,
    val min: Double,
    val good: Double,
    val pctOfGood: Double,
    val reachedMin: Boolean,
    val reachedGood: Boolean,
) {
    companion object {
        fun from(o: JSONObject?): NutrientBar? {
            if (o == null) return null
            fun n(k: String) = if (o.isNull(k)) 0.0 else o.optDouble(k, 0.0)
            return NutrientBar(
                value = n("value"),
                min = n("min"),
                good = n("good"),
                pctOfGood = n("pct_of_good"),
                reachedMin = o.optBoolean("reached_min", false),
                reachedGood = o.optBoolean("reached_good", false),
            )
        }
    }
}

/** 三大宏量营养素的供能比是否落在指南区间内。 */
data class AmdrPart(val pct: Double, val min: Double, val max: Double, val verdict: String) {
    val isOk: Boolean get() = verdict == "ok"
    val isLow: Boolean get() = verdict == "low"
    val isHigh: Boolean get() = verdict == "high"

    companion object {
        fun from(o: JSONObject?): AmdrPart? {
            if (o == null || !o.optBoolean("has_data", true)) return null
            if (o.isNull("pct")) return null
            return AmdrPart(
                pct = o.optDouble("pct", 0.0),
                min = o.optDouble("min", 0.0),
                max = o.optDouble("max", 0.0),
                verdict = o.optString("verdict", "ok"),
            )
        }
    }
}

/** 热量缺口。 */
data class Deficit(
    val value: Double,
    val weeklyKg: Double,
    val verdict: String,
    val note: String,
    val formula: String,
)

/** 需要补充的营养素。 */
data class NutritionGap(val key: String, val label: String, val need: Double, val unit: String, val hint: String) {
    companion object {
        fun from(o: JSONObject) = NutritionGap(
            key = o.optString("key"),
            label = o.optString("label"),
            need = o.optDouble("need", 0.0),
            unit = o.optString("unit"),
            hint = o.optString("hint"),
        )
    }
}

/**
 * 全天营养达标情况（对照《中国居民膳食指南（2022）》）。
 *
 * 这份数据的设计重点不是"超标了没有"，而是"**够了没有**"：
 * GLP-1 用药期间最常见的问题是被食欲抑制到吃太少、蛋白质不足，
 * 结果是掉肌肉而不是掉脂肪。
 */
data class Nutrition(
    val kcalValue: Double,
    val kcalTarget: Double,
    val kcalPct: Double?,
    val protein: NutrientBar?,
    val fiber: NutrientBar?,
    val proteinBasis: String,
    val proteinBasisNote: String?,
    val carb: AmdrPart?,
    val fat: AmdrPart?,
    val proteinShare: AmdrPart?,
    val deficit: Deficit?,
    val gaps: List<NutritionGap>,
    val hasData: Boolean,
) {
    companion object {
        fun from(o: JSONObject?): Nutrition? {
            if (o == null) return null
            val kcal = o.optJSONObject("kcal")
            val amdr = o.optJSONObject("amdr")
            val d = o.optJSONObject("deficit")
            val gaps = mutableListOf<NutritionGap>()
            o.optJSONArray("gaps")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { gaps.add(NutritionGap.from(it)) }
            }
            return Nutrition(
                kcalValue = kcal?.optDouble("value", 0.0) ?: 0.0,
                kcalTarget = kcal?.optDouble("target", 0.0) ?: 0.0,
                kcalPct = kcal?.let { if (it.isNull("pct")) null else it.optDouble("pct") },
                protein = NutrientBar.from(o.optJSONObject("protein")),
                fiber = NutrientBar.from(o.optJSONObject("fiber")),
                proteinBasis = o.optString("protein_basis"),
                proteinBasisNote = o.optString("protein_basis_note").takeIf { it.isNotBlank() },
                carb = AmdrPart.from(amdr?.optJSONObject("carb")),
                fat = AmdrPart.from(amdr?.optJSONObject("fat")),
                proteinShare = AmdrPart.from(amdr?.optJSONObject("protein")),
                deficit = d?.let {
                    Deficit(
                        value = it.optDouble("value", 0.0),
                        weeklyKg = it.optDouble("weekly_kg", 0.0),
                        verdict = it.optString("verdict"),
                        note = it.optString("note"),
                        formula = it.optString("formula"),
                    )
                },
                gaps = gaps,
                hasData = o.optBoolean("has_data", false),
            )
        }
    }
}

// ---------------------------------------------------------------- 体重预期
/** 一个时间里程碑的预期体重。 */
data class WeightMilestone(val weeks: Int, val pct: Double, val lossKg: Double, val weightKg: Double) {
    companion object {
        fun from(o: JSONObject) = WeightMilestone(
            weeks = o.optInt("weeks"),
            pct = o.optDouble("pct", 0.0),
            lossKg = o.optDouble("loss_kg", 0.0),
            weightKg = o.optDouble("weight_kg", 0.0),
        )
    }
}

/** 目标体重能不能到、要多久。 */
data class GoalProjection(
    val targetWeightKg: Double,
    val needLossKg: Double,
    val needPct: Double,
    val trialEndpointPct: Double,
    val usedDoseMg: Double,
    val basisDoseMg: Double?,
    val basisNote: String,
    val ratioVsTrial: Double?,
    val weeksToGoal: Double?,
    val reachableWithinTrial: Boolean,
    val note: String?,
    val alreadyThere: Boolean,
)

/**
 * 体重下降预测。
 *
 * 关键设计：**同时给试验预期和能量账两个数**。
 * 试验预期（SURMOUNT-1 实测终点）回答"一般会瘦多少"；
 * 能量账（7700 kcal/kg）回答"照现在的吃法账面上该瘦多少"。
 * 两者不一致本身有信息量——差得越多，说明减重里药物作用的占比越大。
 * 只报一个都会误导：只报能量账会低估，只报试验值会让人以为与吃多少无关。
 */
data class WeightForecast(
    val weeks: Double,
    val trialPct: Double,
    val endpointPct: Double,
    val endpointWeeks: Int,
    val usedDoseMg: Double,
    val isExtrapolatedCurve: Boolean,
    val trialName: String,
    val trialPopulation: String,
    val trialCaveat: String,
    val basisWeightKg: Double?,
    val basisIsStartWeight: Boolean,
    val expectedLossKg: Double?,
    val expectedWeightKg: Double?,
    val leanMassLossKg: Double?,
    val fatMassLossKg: Double?,
    val actualLossKg: Double?,
    val milestones: List<WeightMilestone>,
    val goal: GoalProjection?,
) {
    companion object {
        fun from(o: JSONObject?): WeightForecast? {
            if (o == null) return null
            val t = o.optJSONObject("trial") ?: return null
            val meta = o.optJSONObject("trial_meta")
            val marks = mutableListOf<WeightMilestone>()
            o.optJSONArray("milestones")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { marks.add(WeightMilestone.from(it)) }
            }
            fun num(obj: JSONObject?, key: String): Double? =
                obj?.let { if (it.isNull(key)) null else it.optDouble(key).takeIf { v -> !v.isNaN() } }

            val g = o.optJSONObject("goal")
            return WeightForecast(
                weeks = o.optDouble("weeks", 0.0),
                trialPct = t.optDouble("pct", 0.0),
                endpointPct = t.optDouble("endpoint_pct", 0.0),
                endpointWeeks = t.optInt("endpoint_weeks", 72),
                usedDoseMg = t.optDouble("used_dose_mg", 0.0),
                isExtrapolatedCurve = t.optBoolean("is_extrapolated_curve", true),
                trialName = meta?.optString("name").orEmpty().ifBlank { "SURMOUNT-1" },
                trialPopulation = meta?.optString("population").orEmpty(),
                trialCaveat = meta?.optString("caveat").orEmpty(),
                basisWeightKg = num(o, "basis_weight_kg"),
                basisIsStartWeight = o.optBoolean("basis_is_start_weight", false),
                expectedLossKg = num(o, "expected_loss_kg"),
                expectedWeightKg = num(o, "expected_weight_kg"),
                leanMassLossKg = num(o, "lean_mass_loss_kg"),
                fatMassLossKg = num(o, "fat_mass_loss_kg"),
                actualLossKg = num(o, "actual_loss_kg"),
                milestones = marks,
                goal = g?.let {
                    GoalProjection(
                        targetWeightKg = it.optDouble("target_weight_kg", 0.0),
                        needLossKg = it.optDouble("need_loss_kg", 0.0),
                        needPct = it.optDouble("need_pct", 0.0),
                        trialEndpointPct = it.optDouble("trial_endpoint_pct", 0.0),
                        usedDoseMg = it.optDouble("used_dose_mg", 0.0),
                        basisDoseMg = num(it, "basis_dose_mg"),
                        basisNote = it.optString("basis_note"),
                        ratioVsTrial = num(it, "ratio_vs_trial"),
                        weeksToGoal = num(it, "weeks_to_goal"),
                        reachableWithinTrial = it.optBoolean("reachable_within_trial", false),
                        note = it.optString("note").takeIf { v -> v.isNotBlank() },
                        alreadyThere = it.optBoolean("already_there", false),
                    )
                },
            )
        }
    }
}
