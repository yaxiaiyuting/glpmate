package com.glpmate.native_app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glpmate.native_app.data.AppViewModel
import com.glpmate.native_app.data.DrugOption
import com.glpmate.native_app.data.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 设置页（原生版）。
 *
 * 版块顺序与 WebView 版一致：档案 → 治疗（药物与剂量 / 提醒 / 目标 / 疗效参考）→ 应用（模型与额度 / 关于）。
 *
 * 这一页有两条**不能违反**的后端约束，代码里都用注释标了出来，改动前请先读：
 *
 * 1) 药代参数（med_name / half_life_hours / tmax_hours / dose_interval_days）**绝不回传**。
 *    `Store.save_profile` 会把任何显式收到的 PK 字段记进 `profile.pk_overrides`，
 *    此后 `Store.profile()` 不再用 `drugs.py` 的说明书参数表覆盖它——等于把这个字段永久冻在
 *    当前值上。结果是以后更正说明书参数，老用户库里还是旧数字。所以这里只发
 *    drug_id / dose_mg / dose_time / dose_weekday / treatment_start。
 *
 * 2) 饮水目标必须**同时**发 `profile.water_target_l` 和 `targets.water_ml`。
 *    `Store.targets()` 里 `water_target_l` 优先级最高（"升是权威单位"），只发 water_ml
 *    会被库里旧的 water_target_l 覆盖回去，用户改了饮水目标却看不到变化。
 *
 * 安全底线：剂量只能从后端给的 `doseStepsMg`（说明书核准阶梯）里挑，任何地方都没有
 * 自由输入的剂量输入框——手输 "7" 不能变成 7 mg 替尔泊肽。
 */
@Composable
fun SettingsScreen(state: UiState, vm: AppViewModel, modifier: Modifier = Modifier) {
    // 进页面拉一次设置（药物列表 + 原始 settings/profile/targets/schedule）
    LaunchedEffect(Unit) {
        if (state.drugOptions.isEmpty()) vm.loadSettingsData()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 和今天页一样：导航是手写的，没有 Scaffold 替我们让开状态栏
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Text(
            "设置",
            color = GlpColors.Text,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
        )

        ProfileCard(state)

        SectionHead("治疗")
        DrugDoseCard(state, vm)
        ReminderCard(state, vm)
        TargetCard(state, vm)
        EfficacyCard(state, vm)

        SectionHead("应用")
        ModelQuotaCard(state, vm)
        AboutCard(state)

        Spacer(Modifier.height(28.dp))
    }
}

// ================================================================ 1. 档案

@Composable
private fun ProfileCard(state: UiState) {
    val profile = state.bootstrap?.profile
    val name = profile?.displayName?.takeIf { it.isNotBlank() }
        ?: state.rawSettings?.optJSONObject("profile")?.optString("display_name")?.takeIf { it.isNotBlank() }
        ?: "朋友"
    val email = state.rawSettings?.optJSONObject("profile")?.optString("email").orEmpty()

    // 概括一行：本机账号 · 当前方案 · 加入时间。没有的不编。
    val drug = selectableDrugs(state).firstOrNull { it.value == profile?.drugId }
    val summary = buildList {
        add(email.ifBlank { "本机账号 · 数据不出这台设备" })
        if (drug != null && profile != null) {
            add("${drug.label.substringBefore("（")} ${formatNum(profile.doseMg)} mg")
        }
        add("本周加入")
    }.joinToString(" · ")

    GlpCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(GlpColors.Accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name.take(1),
                    color = GlpColors.Accent,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = GlpColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(summary, color = GlpColors.Text3, fontSize = 11.5.sp, lineHeight = 16.sp)
            }
        }
    }
}

// ================================================================ 2. 治疗 · 药物与剂量

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DrugDoseCard(state: UiState, vm: AppViewModel) {
    var expanded by remember { mutableStateOf(true) }   // 默认展开：折叠起来用户根本不知道里面有东西
    val drugs = selectableDrugs(state)

    // 药物与剂量始终从「当前设置」出发；换药时手动重置剂量（见下面的 LaunchedEffect）。
    val initialDrugId = state.bootstrap?.profile?.drugId
        ?: state.rawSettings?.optJSONObject("profile")?.optString("drug_id").orEmpty()
    var drugId by remember(initialDrugId, drugs.size) {
        mutableStateOf(
            drugs.firstOrNull { it.value == initialDrugId }?.value ?: drugs.firstOrNull()?.value.orEmpty()
        )
    }
    var doseText by remember(initialDrugId, drugs.size) {
        mutableStateOf(
            initialDose(drugs, drugId, state.bootstrap?.profile?.doseMg).let { if (it == null) "" else numText(it) }
        )
    }
    var doseTime by remember { mutableStateOf(hhmm(state.bootstrap?.profile?.doseTime ?: "12:00", "12:00")) }
    var weekday by remember {
        mutableStateOf((state.bootstrap?.profile?.doseWeekday ?: 0).coerceIn(0, 6))
    }
    var startDate by remember {
        mutableStateOf((state.bootstrap?.profile?.treatmentStart ?: "").take(10))
    }
    var error by remember { mutableStateOf("") }

    val drug = drugs.firstOrNull { it.value == drugId }
    val isDaily = drug?.isDaily ?: ((drug?.intervalDays ?: 7) <= 1)
    val dose = doseText.toDoubleOrNull()
    val activeDose = dose?.takeIf { step -> drug?.doseStepsMg?.any { near(it, step) } == true }

    // 换药：剂量重置为该药的第一个说明书阶梯，PK 只读块立即跟着换（不发任何 PK 字段）
    LaunchedEffect(drugId) {
        val d = drugs.firstOrNull { it.value == drugId } ?: return@LaunchedEffect
        if (activeDose == null) {
            doseText = d.doseStepsMg.firstOrNull()?.let { numText(it) } ?: ""
        }
    }

    val summary = if (drug == null) {
        "正在读取药物参数表…"
    } else {
        listOf(
            drug.label.substringBefore("（"),
            activeDose?.let { formatNum(it) + " mg" } ?: "未选剂量",
            drug.intervalText,
        ).joinToString(" · ")
    }

    SectionCard(
        title = "药物与剂量",
        icon = "💉",
        accent = GlpColors.Dose,
        summary = summary,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        if (drug == null) {
            EmptyState("还没读到药物参数表", "检查后端连接后重试")
            PillButton("重新读取", GlpColors.Accent) { vm.loadSettingsData() }
            return@SectionCard
        }
        val activeDrug = drug   // 局部非空别名：后面的 smart cast 在 lambda 里不成立

        FieldLabel("药物", "换药后剂量会重置为该药的起始剂量；半衰期等参数由后端的说明书参数表套用。")
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            drugs.forEach { option ->
                ChoiceChip(option.label, option.value == drugId) {
                    if (option.value != drugId) {
                        drugId = option.value
                        // 立即重置，不等 LaunchedEffect：避免"新药 + 旧剂量"短暂同屏
                        doseText = option.doseStepsMg.firstOrNull()?.let { numText(it) } ?: ""
                        error = ""
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        FieldLabel("剂量", "只能选该药说明书里的规格，不能手填。")
        if (activeDrug.doseStepsMg.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("后端没有给出这支药的剂量阶梯，先不改剂量。", color = GlpColors.Food, fontSize = 12.sp)
        } else {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                activeDrug.doseStepsMg.forEach { step ->
                    val selected = activeDose?.let { near(step, it) } == true
                    ChoiceChip("${formatNum(step)} mg", selected) {
                        doseText = numText(step)
                        error = ""
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        KeyValueBlock {
            KvRow("给药间隔", activeDrug.intervalText)
            KvRow("半衰期", "${formatNum(activeDrug.halfLifeHours)}h")
            KvRow("达峰", "${formatNum(activeDrug.tmaxHours)}h")
            KvRow("数据置信度", confidenceCn(activeDrug.confidence))
            if (activeDrug.brands.isNotEmpty()) KvRow("常见商品名", activeDrug.brands.joinToString("、"))
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlpField(
                label = "注射时间",
                value = doseTime,
                onValueChange = { doseTime = it; error = "" },
                placeholder = "HH:mm",
                keyboardType = KeyboardType.Ascii,
                modifier = Modifier.weight(1f),
            )
            GlpField(
                label = "治疗开始日",
                value = startDate,
                onValueChange = { startDate = it; error = "" },
                placeholder = "YYYY-MM-DD",
                keyboardType = KeyboardType.Number,
                hint = "留空 = 用第一条剂量记录的日期",
                modifier = Modifier.weight(1f),
            )
        }

        // 每天给药的药（interval_days = 1，例如利拉鲁肽）没有"星期几"这回事
        if (!isDaily) {
            Spacer(Modifier.height(14.dp))
            FieldLabel("给药日", "每 ${activeDrug.intervalDays} 天一次，选一个固定的星期几。")
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (0..6).forEach { index ->
                    ChoiceChip(weekdayShort(index), weekday == index) { weekday = index }
                }
            }
        } else {
            Spacer(Modifier.height(10.dp))
            HintLine("这支药是每天给药，所以不需要选星期几。")
        }

        HintLine("这里只是记录你自己的用药方案，不构成医疗建议；调整剂量前请先问医生。")
        FormError(error)
        PrimaryButton(
            text = if (state.busy) "保存中…" else "保存药物与剂量",
            enabled = !state.busy,
            onClick = save@{
                val problem = validateDrugForm(drug, activeDose, doseTime, startDate)
                if (problem != null) {
                    error = problem
                    return@save
                }
                error = ""
                val payload = mutableMapOf<String, Any?>(
                    "drug_id" to activeDrug.value,
                    "dose_mg" to activeDose,
                    "dose_time" to hhmm(doseTime, ""),
                )
                // 只发这五个字段：任何 PK 字段都会写进后端的 pk_overrides，永久冻结说明书参数
                if (!isDaily) payload["dose_weekday"] = weekday
                // 空 = 明确清空（后端回退到第一条剂量记录）。JSONObject.NULL 而不是 Kotlin null：
                // ViewModel 里 `if (v != null)` 会把 Kotlin null 整个丢掉，那样后端不会清空旧值。
                payload["treatment_start"] =
                    if (startDate.isBlank()) JSONObject.NULL else startDate.trim().take(10)
                vm.saveSettings(profile = payload, successMessage = "已保存药物与剂量")
            },
        )
    }
}

/** 药物表单校验；返回中文原因或 null。 */
private fun validateDrugForm(
    drug: DrugOption?,
    dose: Double?,
    time: String,
    startDate: String,
): String? {
    if (drug == null) return "请先选择药物"
    if (dose == null) return "请选择剂量（只能从说明书规格里选）"
    if (drug.doseStepsMg.none { near(it, dose) }) return "这个剂量不在该药的说明书规格里"
    if (hhmm(time, "").isEmpty()) return "注射时间请按 HH:mm 填写，例如 12:00"
    val start = startDate.trim()
    if (start.isNotEmpty() && !isIsoDate(start)) return "治疗开始日请按 YYYY-MM-DD 填写，或留空"
    return null
}

// ================================================================ 2. 治疗 · 提醒

private data class ScheduleRow(
    val key: String,
    val label: String,
    val start: String,
    val end: String,
    val enabled: Boolean,
)

@Composable
private fun ReminderCard(state: UiState, vm: AppViewModel) {
    var expanded by remember { mutableStateOf(true) }   // 默认展开：折叠起来用户根本不知道里面有东西
    var error by remember { mutableStateOf("") }
    val parsed = remember(state.rawSettings) { parseSchedule(state.rawSettings) }
    var rows by remember(parsed) { mutableStateOf(parsed) }

    val enabledCount = rows.count { it.enabled }
    val summary = if (rows.isEmpty()) {
        "没有读到提醒项"
    } else {
        "已开启 $enabledCount/${rows.size} 项"
    }

    SectionCard(
        title = "提醒",
        icon = "🔔",
        accent = GlpColors.Water,
        summary = summary,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        if (rows.isEmpty()) {
            EmptyState("还没有读到提醒项", "设置还没同步下来")
            PillButton("重新读取", GlpColors.Accent) { vm.loadSettingsData() }
            return@SectionCard
        }

        // 注意：原生版另有 ReminderScheduler（AlarmManager）会按这份 schedule 排本机通知，
        // 所以这里说的"通知"是指应用内的定时提醒，不是后端推送。
        HintLine("这些时间会同步到手机上的本机通知（按应用内闹钟触发），后端不会推送；关掉开关就停止提醒。")

        rows.forEachIndexed { index, row ->
            Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.label.ifBlank { "提醒 ${index + 1}" },
                            color = if (row.enabled) GlpColors.Text else GlpColors.Text3,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            if (row.enabled) "${row.start} – ${row.end}" else "已关闭",
                            color = GlpColors.Text3,
                            fontSize = 11.5.sp,
                        )
                    }
                    Switch(
                        checked = row.enabled,
                        onCheckedChange = { on ->
                            rows = rows.toMutableList().also { it[index] = row.copy(enabled = on) }
                            error = ""
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF04222B),
                            checkedTrackColor = GlpColors.Accent,
                            uncheckedThumbColor = GlpColors.Text3,
                            uncheckedTrackColor = GlpColors.SurfaceAlt,
                            uncheckedBorderColor = GlpColors.Line,
                        ),
                    )
                }
                if (row.enabled) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GlpField(
                            label = "开始",
                            value = row.start,
                            onValueChange = { v ->
                                rows = rows.toMutableList().also { it[index] = row.copy(start = v) }
                                error = ""
                            },
                            placeholder = "HH:mm",
                            keyboardType = KeyboardType.Ascii,
                            modifier = Modifier.weight(1f),
                        )
                        GlpField(
                            label = "结束",
                            value = row.end,
                            onValueChange = { v ->
                                rows = rows.toMutableList().also { it[index] = row.copy(end = v) }
                                error = ""
                            },
                            placeholder = "HH:mm",
                            keyboardType = KeyboardType.Ascii,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(GlpColors.Line))
            }
        }

        Spacer(Modifier.height(8.dp))
        FormError(error)
        PrimaryButton(
            text = if (state.busy) "保存中…" else "保存提醒",
            enabled = !state.busy,
            onClick = save@{
                val problem = validateSchedule(rows)
                if (problem != null) {
                    error = problem
                    return@save
                }
                error = ""
                // 整份数组回传：后端是整体替换 semantics，只发改动项会丢字段
                val list = rows.map { row ->
                    val item = mutableMapOf<String, Any?>(
                        "start" to hhmm(row.start, ""),
                        "end" to hhmm(row.end, ""),
                        "enabled" to row.enabled,
                    )
                    if (row.key.isNotBlank()) item["key"] = row.key
                    if (row.label.isNotBlank()) item["label"] = row.label
                    item
                }
                vm.saveSettings(schedule = list, successMessage = "已保存提醒")
            },
        )
    }
}

private fun parseSchedule(raw: JSONObject?): List<ScheduleRow> {
    val arr = raw?.optJSONArray("schedule") ?: return emptyList()
    val out = mutableListOf<ScheduleRow>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out.add(
            ScheduleRow(
                key = o.optString("key"),
                label = o.optString("label"),
                start = hhmm(o.optString("start"), "08:00"),
                end = hhmm(o.optString("end"), "22:00"),
                enabled = o.optBoolean("enabled", true),
            )
        )
    }
    return out
}

private fun validateSchedule(rows: List<ScheduleRow>): String? {
    rows.forEachIndexed { index, row ->
        val name = row.label.ifBlank { "第 ${index + 1} 项" }
        if (!row.enabled) return@forEachIndexed
        val start = hhmm(row.start, "")
        val end = hhmm(row.end, "")
        if (start.isEmpty()) return "$name：开始时间请按 HH:mm 填写"
        if (end.isEmpty()) return "$name：结束时间请按 HH:mm 填写"
        if (end <= start) return "$name：结束时间要晚于开始时间"
    }
    return null
}

// ================================================================ 2. 治疗 · 目标

private data class TargetField(
    val key: String,
    val label: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val hint: String,
)

private val TARGET_FIELDS = listOf(
    // 身高放最前面：BMI 全靠它，而 BMI 是 GLP-1 用药里医生最常看的指标。
    // 范围 100–250cm 拦掉"175 写成 17.5"这类笔误（后端也会再拦一次）。
    TargetField("height", "身高", "cm", 100.0, 250.0, "100–250"),
    TargetField("weight", "目标体重", "kg", 20.0, 400.0, "20–400"),
    TargetField("calories", "卡路里", "kcal", 600.0, 6000.0, "600–6000"),
    TargetField("protein", "蛋白质", "g", 20.0, 400.0, "20–400"),
    TargetField("carbs", "碳水", "g", 20.0, 800.0, "20–800"),
    TargetField("fat", "脂肪", "g", 10.0, 300.0, "10–300"),
    TargetField("fiber", "纤维", "g", 5.0, 100.0, "5–100"),
    TargetField("water", "饮水", "升", 0.5, 10.0, "0.5–10"),
)

@Composable
private fun TargetCard(state: UiState, vm: AppViewModel) {
    var expanded by remember { mutableStateOf(true) }   // 默认展开：折叠起来用户根本不知道里面有东西
    var error by remember { mutableStateOf("") }
    val initial = remember(state.bootstrap, state.rawSettings) { initialTargets(state) }
    var values by remember(initial) { mutableStateOf(initial) }

    val summary = buildString {
        append("身高 ${values["height"].orEmpty()} · 体重 ${values["weight"].orEmpty()} kg")
        append(" · ${values["calories"].orEmpty()} kcal · 饮水 ${values["water"].orEmpty()} 升")
    }

    SectionCard(
        title = "目标",
        icon = "🎯",
        accent = GlpColors.Weight,
        summary = summary,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        HintLine("数值超出范围时会拒绝保存，不会把胡填的数字发到后端。")

        TARGET_FIELDS.forEach { field ->
            Spacer(Modifier.height(10.dp))
            GlpField(
                label = "${field.label}（${field.unit}）",
                value = values[field.key].orEmpty(),
                onValueChange = { v ->
                    values = values.toMutableMap().also { it[field.key] = v }
                    error = ""
                },
                placeholder = field.hint,
                keyboardType = KeyboardType.Decimal,
                hint = "范围 ${field.hint} ${field.unit}",
            )
        }
        Spacer(Modifier.height(10.dp))
        HintLine("目标体重存在个人档案里；饮水按「升」填写，保存时同时换算成毫升。")

        FormError(error)
        PrimaryButton(
            text = if (state.busy) "保存中…" else "保存目标",
            enabled = !state.busy,
            onClick = save@{
                val parsed = mutableMapOf<String, Double>()
                var problem: String? = null
                for (field in TARGET_FIELDS) {
                    val raw = values[field.key].orEmpty().trim()
                    val value = raw.toDoubleOrNull()
                    if (value == null) {
                        problem = problem ?: "${field.label}请填数字"
                        continue
                    }
                    if (value < field.min || value > field.max) {
                        problem = problem ?: "${field.label}要在 ${field.hint} ${field.unit} 之间"
                        continue
                    }
                    parsed[field.key] = value
                }
                if (problem != null) {
                    error = problem
                    return@save
                }
                error = ""
                val weight = parsed.getValue("weight")
                val liters = parsed.getValue("water")
                vm.saveSettings(
                    // 升是权威单位：water_target_l 与 water_ml 必须同时发，
                    // 否则 Store.targets() 会用库里旧的 water_target_l 覆盖回 water_ml
                    profile = mapOf(
                        "height_cm" to parsed.getValue("height"),
                        "target_weight_kg" to weight,
                        "water_target_l" to liters,
                    ),
                    targets = mapOf(
                        "calories" to parsed.getValue("calories"),
                        "protein_g" to parsed.getValue("protein"),
                        "carbs_g" to parsed.getValue("carbs"),
                        "fat_g" to parsed.getValue("fat"),
                        "fiber_g" to parsed.getValue("fiber"),
                        "water_ml" to (liters * 1000).toInt(),
                    ),
                    successMessage = "已保存目标",
                )
            },
        )
    }
}

private fun initialTargets(state: UiState): Map<String, String> {
    val raw = state.rawSettings
    val profileJson = raw?.optJSONObject("profile")
    val targets = raw?.optJSONObject("targets") ?: JSONObject()
    val bootstrap = state.bootstrap

    val weight = firstNum(
        bootstrap?.profile?.targetWeightKg,
        profileJson?.optDouble("target_weight_kg"),
        targets.optDouble("weight_goal"),
        80.0,
    )
    val waterMl = targets.optDouble("water_ml")
    val waterL = if (!waterMl.isNaN() && waterMl > 0) {
        Math.round(waterMl / 10.0) / 100.0
    } else {
        firstNum(profileJson?.optDouble("water_target_l"), 2.0)
    }
    // 身高：没有默认值可编——留空就让用户自己填（后端 BMI 会显示"未计算"）。
    // 给一个假的默认身高会让 BMI 看起来是对的但其实是编的，那比不显示更糟。
    val heightRaw = profileJson?.optDouble("height_cm")
    val heightText = if (heightRaw == null || heightRaw.isNaN() || heightRaw <= 0) "" else numText(heightRaw)

    return linkedMapOf(
        "height" to heightText,
        "weight" to numText(weight),
        "calories" to numText(firstNum(targets.optDouble("calories"), 1600.0)),
        "protein" to numText(firstNum(targets.optDouble("protein_g"), 90.0)),
        "carbs" to numText(firstNum(targets.optDouble("carbs_g"), 180.0)),
        "fat" to numText(firstNum(targets.optDouble("fat_g"), 55.0)),
        "fiber" to numText(firstNum(targets.optDouble("fiber_g"), 25.0)),
        "water" to numText(waterL),
    )
}

// ================================================================ 2. 治疗 · 疗效参考（只读）

private data class EfficacyArm(
    val name: String,
    val weightChangePct: Double?,
    val weightChangeKg: Double?,
    val ge5: Double?,
    val ge10: Double?,
    val ge15: Double?,
    val ge20: Double?,
)

private data class EfficacyGiArm(
    val name: String,
    val nausea: Double?,
    val diarrhoea: Double?,
    val vomiting: Double?,
    val constipation: Double?,
    val anyGi: Double?,
)

private data class EfficacyIndication(
    val title: String,
    val meta: String,
    val arms: List<EfficacyArm>,
    val verified: Boolean,
)

private data class EfficacyView(
    val indications: List<EfficacyIndication>,
    val giTitle: String,
    val giArms: List<EfficacyGiArm>,
    val disclaimer: String,
    val caveat: String,
    val verifiedOn: String,
    val confidence: String,
    val notes: String,
)

@Composable
private fun EfficacyCard(state: UiState, vm: AppViewModel) {
    var expanded by remember { mutableStateOf(true) }   // 默认展开：折叠起来用户根本不知道里面有东西
    var reload by remember { mutableStateOf(0) }
    var data by remember { mutableStateOf<EfficacyView?>(null) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    val drugId = state.bootstrap?.profile?.drugId

    LaunchedEffect(drugId, reload) {
        loading = true
        error = ""
        try {
            val json = withContext(Dispatchers.IO) { vm.backend.efficacy(drugId) }
            data = parseEfficacy(json)
        } catch (e: Exception) {
            // 读不到就说读不到，绝不编数字
            error = "读取疗效参考失败：${e.message ?: e.javaClass.simpleName}"
            data = null
        }
        loading = false
    }

    val summary = when {
        loading -> "正在读取说明书数据…"
        error.isNotEmpty() -> "读取失败"
        data == null -> "暂无数据"
        else -> {
            val view = data
            "${view?.indications?.size ?: 0} 项适应症 · 核验于 ${view?.verifiedOn?.ifBlank { "—" } ?: "—"}"
        }
    }

    SectionCard(
        title = "疗效参考",
        icon = "📄",
        accent = GlpColors.Accent,
        summary = summary,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        if (loading) {
            Text("正在读取说明书数据…", color = GlpColors.Text3, fontSize = 12.5.sp)
            return@SectionCard
        }
        val view = data
        if (view == null) {
            ErrorState(error.ifBlank { "没有读到数据" }) { reload += 1 }
            return@SectionCard
        }

        if (view.disclaimer.isNotBlank()) HintLine(view.disclaimer, "ⓘ")

        if (view.indications.isEmpty()) {
            EmptyState("暂无已核验数据", "这款药的数据还没逐条核对，宁可不显示")
        } else {
            view.indications.forEach { indication ->
                Spacer(Modifier.height(14.dp))
                Text(
                    indication.title,
                    color = GlpColors.Text,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (indication.meta.isNotBlank()) {
                    Text(indication.meta, color = GlpColors.Text3, fontSize = 11.sp, lineHeight = 16.sp)
                }
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth()) {
                    TableRow(
                        listOf("组别", "体重变化", "≥5%", "≥10%", "≥15%", "≥20%"),
                        header = true,
                    )
                    indication.arms.forEach { arm ->
                        TableRow(
                            listOf(
                                arm.name,
                                arm.weightChangePct?.let { signedPct(it) }
                                    ?: arm.weightChangeKg?.let { signed(it, "kg") }
                                    ?: "—",
                                pctText(arm.ge5),
                                pctText(arm.ge10),
                                pctText(arm.ge15),
                                pctText(arm.ge20),
                            ),
                            header = false,
                        )
                    }
                }
                if (!indication.verified) {
                    HintLine("该条目尚未完成核验，数字一律留空。", "⚠︎")
                }
            }
        }

        if (view.giArms.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "常见胃肠道不良反应（发生率 %）",
                color = GlpColors.Text,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (view.giTitle.isNotBlank()) {
                Text(view.giTitle, color = GlpColors.Text3, fontSize = 11.sp, lineHeight = 16.sp)
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth()) {
                TableRow(listOf("组别", "恶心", "腹泻", "呕吐", "便秘", "任意GI"), header = true)
                view.giArms.forEach { arm ->
                    TableRow(
                        listOf(
                            arm.name,
                            pctText(arm.nausea),
                            pctText(arm.diarrhoea),
                            pctText(arm.vomiting),
                            pctText(arm.constipation),
                            pctText(arm.anyGi),
                        ),
                        header = false,
                    )
                }
            }
        }

        if (view.notes.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            HintLine(view.notes, "📝")
        }
        if (view.caveat.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            HintLine(view.caveat, "⚠︎")
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "数据核验日期：${view.verifiedOn.ifBlank { "—" }}" +
                if (view.confidence.isNotBlank()) " · 置信度 ${confidenceCn(view.confidence)}" else "",
            color = GlpColors.Text3,
            fontSize = 11.5.sp,
        )
    }
}

private fun tableWeights(columns: Int): List<Float> = when (columns) {
    6 -> listOf(1.5f, 1.05f, 0.68f, 0.72f, 0.72f, 0.72f)
    else -> List(columns) { 1f }
}

/** 紧凑表格行：用带权重的 Text 横排，避免宽表在小屏上横向溢出。 */
@Composable
private fun TableRow(cells: List<String>, header: Boolean) {
    val weights = tableWeights(cells.size)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (header) GlpColors.SurfaceAlt else Color.Transparent)
                .padding(vertical = 6.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            cells.forEachIndexed { index, cell ->
                Text(
                    cell,
                    color = if (header) GlpColors.Text2 else GlpColors.Text,
                    fontSize = if (header) 10.5.sp else 11.5.sp,
                    fontWeight = if (header || index == 0) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 2,
                    modifier = Modifier.weight(weights.getOrElse(index) { 1f }),
                )
            }
        }
        if (header) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(GlpColors.Line))
        }
    }
}

private fun parseEfficacy(json: JSONObject): EfficacyView {
    val indications = mutableListOf<EfficacyIndication>()
    val arr = json.optJSONArray("indications") ?: JSONArray()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val kind = o.optString("kind")
        val title = o.optString("label").ifBlank {
            when (kind) {
                "weight" -> "体重管理（减重）"
                "diabetes" -> "2 型糖尿病（血糖控制）"
                else -> "适应症 ${i + 1}"
            }
        }
        val meta = buildList {
            o.optString("trial").takeIf { it.isNotBlank() }?.let { add("试验 $it") }
            o.opt("duration_weeks")?.takeIf { it != JSONObject.NULL }?.let { add("$it 周") }
            o.optString("population").takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" · ")

        val arms = mutableListOf<EfficacyArm>()
        val armArr = o.optJSONArray("arms") ?: JSONArray()
        for (j in 0 until armArr.length()) {
            val a = armArr.optJSONObject(j) ?: continue
            arms.add(
                EfficacyArm(
                    // 后端字段名是 dose_mg；arm 是旧版/其他接口的写法，两个都认
                    name = armName(a),
                    weightChangePct = firstNum(a.optDouble("weight_change_pct")),
                    weightChangeKg = firstNum(a.optDouble("weight_change_kg")),
                    ge5 = firstNum(a.optDouble("ge5_pct")),
                    ge10 = firstNum(a.optDouble("ge10_pct")),
                    ge15 = firstNum(a.optDouble("ge15_pct")),
                    ge20 = firstNum(a.optDouble("ge20_pct")),
                )
            )
        }
        indications.add(
            EfficacyIndication(
                title = title,
                meta = meta,
                arms = arms.take(8),
                verified = o.optBoolean("verified", true) && o.optJSONArray("sources") != null,
            )
        )
    }

    val gi = json.optJSONObject("gi_side_effects")
    val giArms = mutableListOf<EfficacyGiArm>()
    gi?.optJSONArray("arms")?.let { giArr ->
        for (i in 0 until giArr.length()) {
            val a = giArr.optJSONObject(i) ?: continue
            giArms.add(
                EfficacyGiArm(
                    name = armName(a),
                    nausea = firstNum(a.optDouble("nausea_pct")),
                    diarrhoea = firstNum(a.optDouble("diarrhoea_pct")),
                    vomiting = firstNum(a.optDouble("vomiting_pct")),
                    constipation = firstNum(a.optDouble("constipation_pct")),
                    anyGi = firstNum(a.optDouble("any_gi_pct"), a.optDouble("any_gi_ae_pct")),
                )
            )
        }
    }

    return EfficacyView(
        indications = indications,
        giTitle = gi?.optString("trial").orEmpty(),
        giArms = giArms.take(8),
        disclaimer = json.optString("disclaimer"),
        caveat = json.optString("cross_trial_caveat"),
        verifiedOn = json.optString("verified_on"),
        confidence = json.optString("confidence"),
        notes = json.optString("notes"),
    )
}

private fun armName(a: JSONObject): String {
    val dose = a.opt("dose_mg")?.takeIf { it != JSONObject.NULL }?.toString()
        ?: a.opt("arm")?.takeIf { it != JSONObject.NULL }?.toString()
        ?: "—"
    val n = a.optInt("n", 0)
    val doseText = if (dose == "placebo") "安慰剂" else "$dose mg"
    return if (n > 0) "$doseText（n=$n）" else doseText
}

// ================================================================ 3. 应用

@Composable
private fun ModelQuotaCard(state: UiState, vm: AppViewModel) {
    val settings = state.rawSettings?.optJSONObject("settings") ?: state.rawSettings
    val savedBaseUrl = settings?.optString("ai_base_url").orEmpty()
    val savedModel = settings?.optString("ai_model").orEmpty()
    val savedQuota = settings?.optInt("daily_quota", 20) ?: 20
    val savedVision = settings?.optString("vision_model").orEmpty()
    val keySet = settings?.optBoolean("ai_api_key_set", false) ?: false

    // 预设模型来自后端（含"旧名已下线"的提示），但模型名仍可手填——
    // 用户可能用第三方 OpenAI 兼容端点，那里的模型名我们不可能穷举。
    val presets = state.rawSettings?.optJSONArray("model_presets")

    var baseUrl by remember(savedBaseUrl) { mutableStateOf(savedBaseUrl) }
    var model by remember(savedModel) { mutableStateOf(savedModel) }
    var vision by remember(savedVision) { mutableStateOf(savedVision) }
    var quota by remember(savedQuota) { mutableStateOf(savedQuota.toString()) }
    var apiKey by remember { mutableStateOf("") }
    var clearKey by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    GlpCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GlpColors.Sleep.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Text("✨", fontSize = 16.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("模型与额度", color = GlpColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (savedModel.isBlank()) "还没配置模型服务"
                    else "$savedModel · 每日 $savedQuota 条" + if (keySet) " · 密钥已设置" else " · 密钥未设置",
                    color = if (keySet) GlpColors.Text2 else GlpColors.Dose,
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        GlpField(
            label = "服务地址（base_url）",
            value = baseUrl,
            onValueChange = { baseUrl = it; error = null },
            placeholder = "https://api.deepseek.com",
            hint = "任何 OpenAI 兼容端点都可以。不用自己补 /v1/chat/completions，后端会自动补。",
        )
        Spacer(Modifier.height(12.dp))

        GlpField(
            label = if (keySet) "API Key（已设置，留空则不改动）" else "API Key",
            value = apiKey,
            onValueChange = { apiKey = it; error = null; clearKey = false },
            placeholder = if (keySet) "sk-…（留空保持不变）" else "sk-…",
            hint = "只写进本机数据库，不会上传到任何第三方。",
        )

        if (keySet) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChoiceChip(if (clearKey) "✓ 保存时清除密钥" else "清除已保存的密钥", clearKey) {
                    clearKey = !clearKey
                }
            }
            if (clearKey) {
                HintLine("保存后密钥会被删除，教练将无法调用模型，直到你重新填入。")
            }
        }

        Spacer(Modifier.height(12.dp))
        GlpField(
            label = "模型名",
            value = model,
            onValueChange = { model = it; error = null },
            placeholder = "deepseek-flash",
            hint = "可手填，用于第三方端点。",
        )

        if (presets != null && presets.length() > 0) {
            Spacer(Modifier.height(10.dp))
            Text("常用模型", color = GlpColors.Text3, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            Column {
                for (i in 0 until presets.length()) {
                    val item = presets.optJSONObject(i) ?: continue
                    val value = item.optString("value")
                    val label = item.optString("label")
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChoiceChip(if (model == value) "✓ $value" else value, model == value) {
                            model = value
                            error = null
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(label, color = GlpColors.Text3, fontSize = 10.5.sp, maxLines = 2)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        GlpField(
            label = "每日教练额度（条）",
            value = quota,
            onValueChange = { quota = it.filter { ch -> ch.isDigit() }; error = null },
            placeholder = "20",
            hint = "自托管就是自己给自己限速，防止手滑烧 token。",
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
        )

        Spacer(Modifier.height(12.dp))
        GlpField(
            label = "视觉模型（可选，用于拍照估算营养）",
            value = vision,
            onValueChange = { vision = it },
            placeholder = "留空则沿用上面的模型",
            hint = "deepseek-flash 本身支持读图片；若用不支持图片的模型，这里单独填一个。",
        )

        error?.let {
            Spacer(Modifier.height(10.dp))
            FormError(it)
        }

        Spacer(Modifier.height(14.dp))
        Row {
            // PrimaryButton 本身会 fillMaxWidth，所以宽度控制放在外层 Box 上
            Box(Modifier.weight(1f)) {
            PrimaryButton(
                text = if (state.busy) "保存中…" else "保存",
                enabled = !state.busy,
                onClick = save@{
                    val q = quota.toIntOrNull()
                    if (baseUrl.isBlank()) {
                        error = "服务地址不能为空"
                        return@save
                    }
                    if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                        error = "服务地址要以 http:// 或 https:// 开头"
                        return@save
                    }
                    if (q == null || q < 1 || q > 500) {
                        error = "每日额度要在 1–500 条之间"
                        return@save
                    }
                    if (model.isBlank()) {
                        error = "模型名不能为空"
                        return@save
                    }
                    error = null
                    // 密钥语义：清除 → ""；填了新值 → 新值；否则 null（不改动）
                    val key: String? = when {
                        clearKey -> ""
                        apiKey.isNotBlank() -> apiKey
                        else -> null
                    }
                    vm.saveAiSettings(baseUrl, key, model, q, vision)
                },
            )
            }
            Spacer(Modifier.width(10.dp))
            PillButton(
                text = if (state.busy) "测试中…" else "测试连接",
                color = GlpColors.Weight,
            ) {
                if (!state.busy) vm.testAiConnection(baseUrl, apiKey, model)
            }
        }

        HintLine("密钥只存在你自己的本机数据库里，不会被上传。测试连接会真的请求一次模型服务。")
    }
}

@Composable
private fun AboutCard(state: UiState) {
    val version = state.rawSettings?.optString("version").orEmpty().ifBlank { "1.0.0" }
    val license = state.rawSettings?.optString("license").orEmpty().ifBlank { "GPL-3.0" }

    GlpCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GlpColors.Accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Text("ℹ️", fontSize = 16.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("关于", color = GlpColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("$license · v$version", color = GlpColors.Text2, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        KeyValueBlock {
            KvRow("版本", version)
            KvRow("许可证", license)
            KvRow("数据存储", "本机 SQLite")
            KvRow("界面语言", "简体中文")
        }
        HintLine("本应用提供的是记录与估算工具，不构成医疗建议。")
    }
}

// ================================================================ 局部样式（只在本文件内使用）

/** 可展开的区块卡片：标题 + 一行摘要，点标题展开/收起。 */
@Composable
private fun SectionCard(
    title: String,
    icon: String,
    accent: Color,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    GlpCard {
        Row(
            Modifier.fillMaxWidth().clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Text(icon, fontSize = 16.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = GlpColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(summary, color = GlpColors.Text2, fontSize = 11.5.sp, lineHeight = 16.sp)
            }
            Text(if (expanded) "▴" else "▾", color = GlpColors.Text3, fontSize = 13.sp)
        }
        if (expanded) {
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

/** 表单小节标签 + 说明。 */
@Composable
private fun FieldLabel(text: String, hint: String? = null) {
    Text(text, color = GlpColors.Text, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
    if (!hint.isNullOrBlank()) {
        Text(hint, color = GlpColors.Text3, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

/**
 * 本地文本输入框。
 *
 * 不用 Material3 的 OutlinedTextField：它的内边距/标签动效会把这一页的节奏打乱，
 * 而且这个应用只有深色一套皮肤。这里是 BasicTextField + 设计系统的令牌，视觉与 WebView 版一致。
 * 注意：**只用来输入时间与数字**，剂量永远不给自由输入。
 */
@Composable
private fun GlpField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    hint: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = if (focused) GlpColors.Accent.copy(alpha = 0.7f) else GlpColors.Line

    Column(modifier) {
        Text(label, color = GlpColors.Text2, fontSize = 11.5.sp)
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(GlpColors.SurfaceAlt)
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp),
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, color = GlpColors.Text3, fontSize = 14.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = GlpColors.Text,
                    fontSize = 14.sp,
                ),
                cursorBrush = SolidColor(GlpColors.Accent),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused },
            )
        }
        if (!hint.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(hint, color = GlpColors.Text3, fontSize = 10.5.sp, lineHeight = 14.sp)
        }
    }
}

/** 只读键值块（带底色，和可编辑输入框区分开）。 */
@Composable
private fun KeyValueBlock(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GlpColors.SurfaceAlt)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        content = { content() },
    )
}

@Composable
private fun KvRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(key, color = GlpColors.Text3, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            color = GlpColors.Text,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

/** 表单错误：等宽的一行红字，保存被拒绝时说清楚原因。 */
@Composable
private fun FormError(message: String) {
    if (message.isBlank()) return
    Text(
        message,
        color = GlpColors.Food,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

// ================================================================ 工具

/** 药物选项：优先用后端 /api/drugs 的列表；读不到时退化成「当前设置」一项。 */
private fun selectableDrugs(state: UiState): List<DrugOption> {
    if (state.drugOptions.isNotEmpty()) return state.drugOptions
    val profile = state.bootstrap?.profile ?: return emptyList()
    return listOf(
        DrugOption(
            value = profile.drugId,
            label = "${profile.medName}（当前设置）",
            brands = emptyList(),
            intervalDays = profile.intervalDays,
            doseStepsMg = if (profile.doseMg > 0) listOf(profile.doseMg) else emptyList(),
            halfLifeHours = profile.halfLifeHours,
            tmaxHours = profile.tmaxHours,
            confidence = "medium",
        )
    )
}

private fun initialDose(drugs: List<DrugOption>, drugId: String, currentMg: Double?): Double? {
    val drug = drugs.firstOrNull { it.value == drugId }
    val want = currentMg?.takeIf { it > 0 }
    if (want != null && drug?.doseStepsMg?.any { near(it, want) } == true) return want
    return drug?.doseStepsMg?.firstOrNull() ?: want
}

/** 浮点比较：2.5 这类阶梯值不能靠 == 判断。 */
private fun near(a: Double, b: Double): Boolean = kotlin.math.abs(a - b) < 0.001

private fun numText(value: Double): String = formatNum(value)

private fun pctText(value: Double?): String =
    if (value == null) "—" else "${formatNum(value)}%"

private fun signedPct(value: Double): String =
    (if (value > 0) "+" else "") + "${formatNum(value)}%"

private fun signed(value: Double, unit: String): String =
    (if (value > 0) "+" else "") + formatNum(value) + " " + unit

private fun confidenceCn(confidence: String): String = when (confidence.trim().lowercase()) {
    "high" -> "高（说明书原文核验）"
    "medium" -> "中"
    "low" -> "低（未逐条核验）"
    else -> confidence.ifBlank { "—" }
}

private fun weekdayShort(index: Int): String =
    listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").getOrElse(index) { "周一" }

/** 取第一个能用的数字：0 是合法剂量，不能用 `?:` 之外的假值兜底。 */
private fun firstNum(vararg values: Double?): Double {
    for (value in values) {
        if (value != null && !value.isNaN() && !value.isInfinite()) return value
    }
    return 0.0
}

/** '08:00:00' / '8:00' → '08:00'；识别不出来时返回 fallback。 */
private fun hhmm(value: String?, fallback: String): String {
    val raw = value?.trim().orEmpty()
    val match = Regex("^(\\d{1,2}):(\\d{2})").find(raw) ?: return fallback
    val hour = match.groupValues[1].toIntOrNull() ?: return fallback
    val minute = match.groupValues[2].toIntOrNull() ?: return fallback
    if (hour !in 0..23 || minute !in 0..59) return fallback
    return (if (hour < 10) "0$hour" else "$hour") + ":" + (if (minute < 10) "0$minute" else "$minute")
}

private fun isIsoDate(value: String): Boolean {
    if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(value)) return false
    val parts = value.split("-")
    val month = parts[1].toIntOrNull() ?: return false
    val day = parts[2].toIntOrNull() ?: return false
    return month in 1..12 && day in 1..31
}
