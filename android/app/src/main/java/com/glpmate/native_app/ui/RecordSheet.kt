package com.glpmate.native_app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glpmate.native_app.data.AppViewModel
import com.glpmate.native_app.data.DrugOption
import com.glpmate.native_app.data.Profile
import com.glpmate.native_app.data.UiState
import kotlin.math.abs

/**
 * 记录录入（原生版）。
 *
 * 覆盖 15 种快速记录 + 饮水 / 餐食 / 剂量（共 18 种），全部走同一个全屏覆盖层。
 * payload 的形状**对着 WebView 版的 recordFields() / recordPayload() 写**
 * （glpmate-lite/static/js/components/sheet.js），单位与 meta 键不能改：
 * 后端 normalize_payload() 按 type 归一化，daily_totals() / 药代投影按 meta 键取值，
 * 键名错了不会报错，只会静默少算一条记录——这是最难发现的一类 bug。
 *
 * 三条硬约束（改动前先读）：
 *
 * 1) **剂量只能选，不能输**。选项来自后端 /api/drugs 的 dose_steps_mg（说明书核准阶梯）；
 *    读不到就退化成"当前档案剂量"这唯一一项。这个文件里不存在任何剂量自由输入框，
 *    手输一个说明书里不存在的剂量（例如 7 mg 替尔泊肽）是这里最危险的错误。
 *
 * 2) **校验必须在调后端之前**。buildPayload() 返回 null 时调用方什么都不做，
 *    所以非法输入（缺字段、非数字、超出合理范围）永远到不了 POST /api/records。
 *
 * 3) **纵向只有一个 verticalScroll**（覆盖层正文）。剂量阶梯等横向排布用 chunked 换行，
 *    不叠横向滚动，免得手势打架。
 */

// ================================================================ 类型表

/** 快速记录的类型（今天页底部那一排圆形按钮用）。 */
data class QuickType(val key: String, val label: String, val icon: String, val unit: String)

/**
 * 15 种快速记录类型。
 *
 * key / label / unit 与后端 config.QUICK_TYPES 完全一致（那边是权威定义），
 * icon 是单字符 emoji：原生版没有 WebView 版的 SVG 图标集，用 emoji 保持"一眼能认"。
 */
val QUICK_TYPES: List<QuickType> = listOf(
    QuickType("weight", "体重", "⚖️", "kg"),
    QuickType("body_fat", "体脂", "🧍", "%"),
    QuickType("waist", "腰围", "📏", "cm"),
    QuickType("bp", "血压", "🩺", "mmHg"),
    QuickType("glucose", "血糖", "🩸", "mmol/L"),
    QuickType("hba1c", "糖化", "🧪", "%"),
    QuickType("lipid", "血脂", "🫀", "mmol/L"),
    QuickType("liver", "肝功能", "🧫", "U/L"),
    QuickType("sleep", "睡眠", "🌙", "h"),
    QuickType("training", "训练", "🏃", "min"),
    QuickType("side_effect", "副作用", "🤢", "级"),
    QuickType("symptom", "症状", "🩹", ""),
    QuickType("ketone", "酮体", "🔥", "mmol/L"),
    QuickType("muscle", "肌肉量", "💪", "kg"),
    QuickType("note", "备注", "📝", ""),
)

/** 15 种之外还必须支持的三种表单（饮水 / 餐食 / 剂量），同样走这个覆盖层。 */
private val EXTRA_TYPES: List<QuickType> = listOf(
    QuickType("water", "饮水", "💧", "ml"),
    QuickType("food", "餐食", "🍽", "kcal"),
    QuickType("dose", "剂量", "💉", "mg"),
)

/** 未知类型也返回一个能显示的壳，绝不抛异常给界面。 */
private fun typeSpec(type: String): QuickType =
    QUICK_TYPES.firstOrNull { it.key == type }
        ?: EXTRA_TYPES.firstOrNull { it.key == type }
        ?: QuickType(type, type, "📝", "")

/**
 * 类型配色。
 *
 * 故意在本文件里重写一份，而不是调用 TodayScreen.kt 里的 colorForType()：
 * 那个函数是给今天页的记录行用的，改今天页时不应该连带把录入表单的配色改掉。
 */
private fun typeColor(type: String): Color = when (type) {
    "dose" -> GlpColors.Dose
    "weight", "muscle" -> GlpColors.Weight
    "training" -> GlpColors.Weight
    "food", "bp" -> GlpColors.Food
    "side_effect" -> GlpColors.Food
    "water" -> GlpColors.Water
    "sleep", "symptom", "hba1c", "lipid" -> GlpColors.Sleep
    "glucose", "ketone" -> GlpColors.Accent
    "liver" -> GlpColors.Weight
    else -> GlpColors.Accent
}

/**
 * 快速记录那一排。
 *
 * 视觉与 TodayScreen.kt 的 QuickRecordCard 完全一致（44dp 圆角方块 + 16% 底色 + 11sp 名称），
 * 只是 15 种放不下一屏，所以横向可滚（横向不计入"只有一个纵向 scroll"的约束）。
 */
@Composable
fun QuickTypeRow(onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        QUICK_TYPES.forEach { item ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onPick(item.key) }
                    .padding(6.dp),
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(typeColor(item.key).copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) { Text(item.icon, fontSize = 18.sp) }
                Spacer(Modifier.height(5.dp))
                Text(item.label, color = GlpColors.Text2, fontSize = 11.sp)
            }
        }
    }
}

// ================================================================ 覆盖层

/**
 * 打开某个类型的录入表单。
 *
 * 用全屏覆盖层而不是 ModalBottomSheet：表单最高的（餐食、剂量）有十来个控件，
 * 底部弹层在小屏上要么被键盘顶飞，要么只剩一条缝；全屏覆盖层不需要跟键盘抢空间。
 * 调用方负责把它放在根 Box 的**最后一个**子节点，这样它才盖得住底部导航。
 */
@Composable
fun RecordEntryOverlay(
    type: String,
    state: UiState,
    vm: AppViewModel,
    onDismiss: () -> Unit,
) {
    val spec = typeSpec(type)
    val profile = state.bootstrap?.profile ?: Profile.emptyProfile()
    val chips = state.bootstrap?.sideEffectChips?.takeIf { it.isNotEmpty() } ?: DEFAULT_SIDE_EFFECT_CHIPS
    val busy = state.busy

    // 表单状态只建一次：覆盖层的生命周期就是"打开一个类型 → 填完关掉"，类型中途不会变。
    // 默认时间取 vm.nowIso()：与后端写入用的时钟同一个口径，不再自己造一个 Date()。
    val st = remember(type) { EntryState(state.drugOptions, profile, vm.nowIso()) }

    // 物理返回键 = 取消（覆盖层是全屏的，返回键落到别的页面会很突兀）
    BackHandler { onDismiss() }

    Box(
        Modifier
            .fillMaxSize()
            .background(GlpColors.Bg)
            .imePadding()
            // 必须自己吃掉空白处的点击：Compose 的命中测试会继续发给被盖住的兄弟节点，
            // 不拦的话点在空白处会穿透到底下的快速记录按钮，甚至切走标签页。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { },
    ) {
        Column(Modifier.fillMaxSize()) {
            OverlayTopBar(title = spec.label, onCancel = onDismiss)

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                FormBody(type, st, profile, chips)
                Spacer(Modifier.height(20.dp))
            }

            OverlayBottomBar(
                error = st.error,
                busy = busy,
                onSave = {
                    // 校验全部在 buildPayload 里做完；返回 null 就到此为止，
                    // vm.addRecord（也就是 POST /api/records）不会被调用。
                    val data = buildPayload(type, st, profile)
                    if (data != null) {
                        vm.addRecord(
                            type = type,
                            at = data.at,
                            value = data.value,
                            unit = data.unit,
                            meta = data.meta,
                            successMessage = "${spec.label}已记录",
                        )
                        // ViewModel 是 fire-and-forget 的：这里关掉表单，失败原因由它自己弹 toast。
                        onDismiss()
                    }
                },
            )
        }
    }
}

@Composable
private fun OverlayTopBar(title: String, onCancel: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            // 不透明背景是必需的：这行是固定的，下面的表单滚上来会透过去。
            .background(GlpColors.Bg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { onCancel() }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("取消", color = GlpColors.Text2, fontSize = 14.sp)
        }
        Text(
            title,
            color = GlpColors.Text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(64.dp))   // 与左侧「取消」等宽，标题才真正居中
    }
}

/**
 * 底部：错误行 + 保存。
 *
 * 错误放在这里而不是表单内部：表单是滚动的，错误如果在最上面，用户点保存时可能看不到；
 * 贴着保存按钮永远可见，「体重 需在 20–400 kg 之间」这种说明也不需要滚回去看。
 */
@Composable
private fun OverlayBottomBar(error: String?, busy: Boolean, onSave: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(GlpColors.Surface)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (!error.isNullOrBlank()) {
            Text(
                error,
                color = GlpColors.Food,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        PrimaryButton(
            text = if (busy) "保存中…" else "保存",
            onClick = onSave,
            enabled = !busy,
        )
    }
}

// ================================================================ 表单正文

@Composable
private fun FormBody(type: String, st: EntryState, profile: Profile, chips: List<String>) {
    when (type) {
        "bp" -> BpForm(st)
        "side_effect" -> SideEffectForm(st, chips)
        "symptom" -> TextForm(
            st,
            label = "症状",
            placeholder = "例如：注射部位红肿",
            hint = "写清楚部位和感觉，复诊时给医生看",
        )
        "note" -> TextForm(
            st,
            label = "备注",
            placeholder = "例如：今天胃口一般，晚上有点反酸",
            hint = "自由记录，不需要格式",
            multiline = true,
        )
        "training" -> TrainingForm(st)
        "food" -> FoodForm(st)
        "water" -> WaterForm(st)
        "dose" -> DoseForm(st, profile)
        else -> NUM_SPECS[type]?.let { NumForm(type, it, st) }
    }
    TimeCard(st)
}

/** 单数值类型：体重 / 体脂 / 腰围 / 血糖 / 糖化 / 血脂 / 肝功能 / 睡眠 / 酮体 / 肌肉量。 */
@Composable
private fun NumForm(type: String, spec: NumSpec, st: EntryState) {
    GlpCard {
        EntryField(
            label = spec.label,
            value = st.primary,
            onValueChange = { st.primary = it; st.error = null },
            placeholder = spec.placeholder,
            unit = spec.unit,
            keyboardType = KeyboardType.Decimal,
        )
        Spacer(Modifier.height(8.dp))
        HintLine(spec.hint, typeSpec(type).icon)
    }
}

/** 血压：两个数字，拆到 meta.systolic / meta.diastolic，value 记收缩压。 */
@Composable
private fun BpForm(st: EntryState) {
    GlpCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EntryField(
                "收缩压（高压）", st.primary, { st.primary = it; st.error = null },
                Modifier.weight(1f), placeholder = "120", unit = "mmHg",
                keyboardType = KeyboardType.Decimal,
            )
            EntryField(
                "舒张压（低压）", st.second, { st.second = it; st.error = null },
                Modifier.weight(1f), placeholder = "80", unit = "mmHg",
                keyboardType = KeyboardType.Decimal,
            )
        }
        Spacer(Modifier.height(10.dp))
        HintLine("收缩压 50–300、舒张压 30–200 mmHg，且收缩压必须大于舒张压。", "🩺")
    }
}

/** 副作用：不适名称（chips 或自己填）+ 1–10 刻度。severity 不给默认值，必须自己选。 */
@Composable
private fun SideEffectForm(st: EntryState, chips: List<String>) {
    GlpCard {
        Text("哪里不舒服？", color = GlpColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        chips.chunked(3).forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { chip ->
                    ChoiceChip(chip, st.label == chip) { st.label = chip; st.error = null }
                }
            }
        }
        EntryField(
            label = "或者自己填",
            value = st.label,
            onValueChange = { st.label = it; st.error = null },
            placeholder = "例如：注射部位红肿",
        )
        Spacer(Modifier.height(14.dp))
        Text("严重程度（1–10）", color = GlpColors.Text2, fontSize = 11.5.sp)
        Spacer(Modifier.height(8.dp))
        (1..10).toList().chunked(5).forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { n ->
                    ChoiceChip("$n", st.severity == n) { st.severity = n; st.error = null }
                }
            }
        }
        HintLine("1 = 几乎没感觉，10 = 难以忍受；今天页显示的是当天最高严重度。", "🤢")
    }
}

/** 纯文本类型：症状（meta.label）、备注（meta.desc）。 */
@Composable
private fun TextForm(
    st: EntryState,
    label: String,
    placeholder: String,
    hint: String,
    multiline: Boolean = false,
) {
    GlpCard {
        EntryField(
            label = label,
            value = st.primary,
            onValueChange = { st.primary = it; st.error = null },
            placeholder = placeholder,
            multiline = multiline,
        )
        Spacer(Modifier.height(8.dp))
        HintLine(hint, "📝")
    }
}

/** 训练：时长（value，min）+ 项目名（meta.desc，可选）。 */
@Composable
private fun TrainingForm(st: EntryState) {
    GlpCard {
        EntryField(
            label = "时长",
            value = st.primary,
            onValueChange = { st.primary = it; st.error = null },
            placeholder = "例如：30",
            unit = "min",
            hint = "有效范围 0–1440 分钟",
            keyboardType = KeyboardType.Decimal,
        )
        Spacer(Modifier.height(12.dp))
        EntryField(
            label = "训练内容（可选）",
            value = st.second,
            onValueChange = { st.second = it },
            placeholder = "例如：快走 / 游泳",
        )
        Spacer(Modifier.height(8.dp))
        HintLine("时长会累加到今天的训练分钟数，项目名记在 meta.desc。", "🏃")
    }
}

/** 饮水：+250 / +500 快捷累加 + 自定义（清空后手填）。 */
@Composable
private fun WaterForm(st: EntryState) {
    GlpCard {
        Text("快捷加水", color = GlpColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("+250 ml", GlpColors.Water) {
                st.primary = addAmount(st.primary, 250)
                st.error = null
            }
            PillButton("+500 ml", GlpColors.Water) {
                st.primary = addAmount(st.primary, 500)
                st.error = null
            }
            PillButton("自定义", GlpColors.Text2) {
                st.primary = ""
                st.error = null
            }
        }
        Spacer(Modifier.height(14.dp))
        EntryField(
            label = "饮水量",
            value = st.primary,
            onValueChange = { st.primary = it; st.error = null },
            placeholder = "例如：350",
            unit = "ml",
            hint = "有效范围 10–5000 ml；「自定义」会清空上面的数字，直接在这里输入",
            keyboardType = KeyboardType.Decimal,
        )
        Spacer(Modifier.height(8.dp))
        HintLine("少量多次比一次猛灌更能缓解恶心和便秘。", "💧")
    }
}

/** 餐食：餐次 + 吃了什么 + 五个宏量营养（都选填，留空按 0 记）。 */
@Composable
private fun FoodForm(st: EntryState) {
    GlpCard {
        EntryField(
            label = "吃了什么",
            value = st.primary,
            onValueChange = { st.primary = it; st.error = null },
            placeholder = "例如：红烧鲫鱼 + 米饭",
            multiline = true,
        )
        Spacer(Modifier.height(12.dp))
        Text("餐次", color = GlpColors.Text2, fontSize = 11.5.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MEAL_SLOTS.forEach { (key, cn) ->
                ChoiceChip(cn, st.slot == key) { st.slot = key }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("营养估算（都可以留空，留空按 0 记）", color = GlpColors.Text2, fontSize = 11.5.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EntryField(
                "卡路里", st.kcal, { st.kcal = it; st.error = null },
                Modifier.weight(1f), placeholder = "500", unit = "kcal",
                keyboardType = KeyboardType.Decimal,
            )
            EntryField(
                "蛋白质", st.protein, { st.protein = it; st.error = null },
                Modifier.weight(1f), placeholder = "30", unit = "g",
                keyboardType = KeyboardType.Decimal,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EntryField(
                "碳水", st.carb, { st.carb = it; st.error = null },
                Modifier.weight(1f), placeholder = "60", unit = "g",
                keyboardType = KeyboardType.Decimal,
            )
            EntryField(
                "脂肪", st.fat, { st.fat = it; st.error = null },
                Modifier.weight(1f), placeholder = "20", unit = "g",
                keyboardType = KeyboardType.Decimal,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EntryField(
                "纤维", st.fiber, { st.fiber = it; st.error = null },
                Modifier.weight(1f), placeholder = "8", unit = "g",
                keyboardType = KeyboardType.Decimal,
            )
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        HintLine("数字直接进今天页的宏量营养进度；只填卡路里也可以。", "🍽")
    }
}

/**
 * 剂量（安全相关，改动前请三思）。
 *
 * 剂量只能从 `doseSteps` 里点选——那是后端下发的说明书核准阶梯；
 * 没有自由输入框，也没有"不确定就随便填一个"的默认值（只有唯一选项时才预选）。
 * 「跳过这次注射」时 dose_mg 记 0：后端 project_events() / next_dose_info() 都会
 * 跳过带 skipped 的记录，记 0 既不会凭空抬高体内药物水平，也不会把下次给药时间顺延。
 */
@Composable
private fun DoseForm(st: EntryState, profile: Profile) {
    val selected = st.doseMg
    GlpCard {
        Text("药物", color = GlpColors.Text2, fontSize = 11.5.sp)
        Text(
            profile.medName.ifBlank { "未设置" },
            color = GlpColors.Text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )

        Spacer(Modifier.height(14.dp))
        Text("剂量（只能从说明书核准的阶梯里选）", color = GlpColors.Text2, fontSize = 11.5.sp)
        Spacer(Modifier.height(8.dp))
        if (st.doseSteps.isEmpty()) {
            Text(
                "读不到剂量阶梯，也没有当前剂量可用：请先到设置页填好药物与剂量。",
                color = GlpColors.Food,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        } else {
            Column(Modifier.alpha(if (st.skipped) 0.45f else 1f)) {
                st.doseSteps.chunked(3).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { mg ->
                            ChoiceChip(
                                formatNum(mg) + " mg",
                                !st.skipped && selected != null && abs(selected - mg) < 0.001,
                            ) {
                                if (!st.skipped) {
                                    st.doseMg = mg
                                    st.error = null
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text("注射部位（可选）", color = GlpColors.Text2, fontSize = 11.5.sp)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.alpha(if (st.skipped) 0.45f else 1f)) {
            INJECTION_SITES.chunked(2).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { site ->
                        ChoiceChip(site, st.site == site) {
                            if (!st.skipped) {
                                st.site = if (st.site == site) null else site
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("跳过这次注射", color = GlpColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (st.skipped) "已标记跳过：保存后不计入体内药物水平"
                    else "漏打/推迟时打开，记录「这次没打」",
                    color = GlpColors.Text3,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
            Switch(
                checked = st.skipped,
                onCheckedChange = { st.skipped = it; st.error = null },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF04222B),
                    checkedTrackColor = GlpColors.Food,
                    uncheckedThumbColor = GlpColors.Text3,
                    uncheckedTrackColor = GlpColors.SurfaceAlt,
                    uncheckedBorderColor = GlpColors.Line,
                ),
            )
        }

        Spacer(Modifier.height(10.dp))
        HintLine(
            if (st.skipped) {
                "跳过只记录「这次没打」；下次给药时间不会因此顺延，需要改时间请到设置页。"
            } else {
                "剂量选项来自后端下发的说明书核准阶梯。这里没有手动输入剂量的入口。"
            },
            "💉",
        )
    }
}

/** 时间：默认现在（vm.nowIso()），可以改成更早的时间；只做格式校验，不引第三方选择器。 */
@Composable
private fun TimeCard(st: EntryState) {
    GlpCard {
        Text("时间", color = GlpColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("默认现在；记录的是更早发生的事时可以改。", color = GlpColors.Text3, fontSize = 11.sp)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EntryField(
                "日期", st.date, { st.date = it; st.error = null },
                Modifier.weight(1f), placeholder = "YYYY-MM-DD",
                keyboardType = KeyboardType.Ascii,
            )
            EntryField(
                "时间", st.time, { st.time = it; st.error = null },
                Modifier.weight(1f), placeholder = "HH:mm",
                keyboardType = KeyboardType.Ascii,
            )
        }
    }
}

// ================================================================ 输入控件

/**
 * 本地输入框。
 *
 * 与 SettingsScreen.kt 的 GlpField 同一套视觉（BasicTextField + 设计系统令牌），
 * 但不复用那个函数——它是 private 的，且它只服务设置页；这里多一个单位后缀和
 * 多行开关，强行合并会让两边都变别扭。
 */
@Composable
private fun EntryField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    hint: String? = null,
    unit: String? = null,
    multiline: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = if (focused) GlpColors.Accent.copy(alpha = 0.7f) else GlpColors.Line

    Column(modifier) {
        Text(label, color = GlpColors.Text2, fontSize = 11.5.sp)
        Spacer(Modifier.height(5.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(GlpColors.SurfaceAlt)
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp)
                .then(if (multiline) Modifier.heightIn(min = 52.dp) else Modifier),
            verticalAlignment = if (multiline) Alignment.Top else Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, color = GlpColors.Text3, fontSize = 14.sp)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = !multiline,
                    textStyle = TextStyle(color = GlpColors.Text, fontSize = 14.sp),
                    cursorBrush = SolidColor(GlpColors.Accent),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = if (multiline) ImeAction.Default else ImeAction.Done,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused },
                )
            }
            if (!unit.isNullOrBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(unit, color = GlpColors.Text3, fontSize = 12.sp)
            }
        }
        if (!hint.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(hint, color = GlpColors.Text3, fontSize = 10.5.sp, lineHeight = 14.sp)
        }
    }
}

// ================================================================ 表单状态

/**
 * 表单状态。
 *
 * 用一个 class + mutableStateOf，而不是二十个 remember { var }：不同类型共用同一批字段
 * （主数值 / 次要数值 / 文本 / 剂量…），集中放一处，when(type) 的分支才看得清。
 */
private class EntryState(drugs: List<DrugOption>, profile: Profile, nowIso: String) {
    val doseSteps: List<Double> = doseStepsFor(drugs, profile)

    /** 主输入：数字类型是数值，症状 / 备注 / 餐食 / 饮水是文本或数值。 */
    var primary by mutableStateOf("")

    /** 次要输入：血压的舒张压、训练项目名。 */
    var second by mutableStateOf("")

    // 餐食的五个宏量营养（选填；留空保存时按 0 发，保证后端聚合口径一致）
    var kcal by mutableStateOf("")
    var protein by mutableStateOf("")
    var carb by mutableStateOf("")
    var fat by mutableStateOf("")
    var fiber by mutableStateOf("")

    /** 餐次：按当前钟点猜一个（与后端 DEFAULT_SCHEDULE 的窗口一致）。 */
    var slot by mutableStateOf(defaultSlot())

    /** 副作用名称。 */
    var label by mutableStateOf("")

    /** 副作用严重度：null = 还没选。不给假默认值——"没选"和"选了 2 级"是两回事。 */
    var severity by mutableStateOf<Int?>(null)

    /** 剂量：只能从 doseSteps 里选，null = 还没选。 */
    var doseMg by mutableStateOf(defaultDose(doseSteps, profile.doseMg))
    var site by mutableStateOf<String?>(null)
    var skipped by mutableStateOf(false)

    // 默认时间来自 vm.nowIso()（"yyyy-MM-dd'T'HH:mm:ss"）；长度不对就留空，交给校验报错
    var date by mutableStateOf(if (nowIso.length >= 16) nowIso.substring(0, 10) else "")
    var time by mutableStateOf(if (nowIso.length >= 16) nowIso.substring(11, 16) else "")

    /** 校验失败的中文原因；非空时保存被拒绝。 */
    var error by mutableStateOf<String?>(null)
}

// ================================================================ 常量与规则

/**
 * 单数值类型的字段规格与合法范围。
 *
 * 范围不是"随便定的"：体重/体脂/腰围/血糖/睡眠/训练/酮体/肌肉量沿用需求里给的边界，
 * 糖化/血脂/肝功能是需求没给边界时按化验单常见量级取的保守值（见注释）。
 */
private class NumSpec(
    val label: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val placeholder: String,
    val hint: String,
)

private val NUM_SPECS: Map<String, NumSpec> = mapOf(
    "weight" to NumSpec("体重", "kg", 20.0, 400.0, "例如：82.4", "有效范围 20–400 kg"),
    "body_fat" to NumSpec("体脂率", "%", 1.0, 70.0, "例如：28.5", "有效范围 1–70 %"),
    "waist" to NumSpec("腰围", "cm", 30.0, 250.0, "例如：92", "有效范围 30–250 cm"),
    "glucose" to NumSpec("血糖", "mmol/L", 0.5, 40.0, "例如：5.4", "有效范围 0.5–40 mmol/L"),
    // 糖化血红蛋白：化验单常见 4–14 %，放宽到 2–20 % 只拦明显手误
    "hba1c" to NumSpec("糖化血红蛋白", "%", 2.0, 20.0, "例如：5.4", "有效范围 2–20 %（化验单口径）"),
    // 血脂：单值口径（LDL/总胆固醇常见 1–10 mmol/L），上限放到 50 只拦手误
    "lipid" to NumSpec("血脂", "mmol/L", 0.1, 50.0, "例如：4.2", "有效范围 0.1–50 mmol/L"),
    // 肝功能：转氨酶常见 10–40 U/L，上限放到 2000 覆盖急性升高
    "liver" to NumSpec("肝功能", "U/L", 0.0, 2000.0, "例如：24", "有效范围 0–2000 U/L（转氨酶口径）"),
    "sleep" to NumSpec("睡眠时长", "h", 0.0, 24.0, "例如：7.5", "有效范围 0–24 小时"),
    "ketone" to NumSpec("酮体", "mmol/L", 0.0, 20.0, "例如：0.3", "有效范围 0–20 mmol/L"),
    "muscle" to NumSpec("肌肉量", "kg", 5.0, 150.0, "例如：30.5", "有效范围 5–150 kg"),
)

/** 餐次 key 必须与后端一致（breakfast/lunch/dinner/snack），中文只用于显示。 */
private val MEAL_SLOTS: List<Pair<String, String>> = listOf(
    "breakfast" to "早餐",
    "lunch" to "午餐",
    "dinner" to "晚餐",
    "snack" to "加餐",
)

/** 注射部位：限成说明书常用部位，避免出现"随便打的"这种后端无法解读的值。 */
private val INJECTION_SITES: List<String> = listOf("腹部左侧", "腹部右侧", "大腿", "上臂")

/** bootstrap 还没到时的不适名称兜底（与后端 config.SIDE_EFFECT_CHIPS 一致）。 */
private val DEFAULT_SIDE_EFFECT_CHIPS: List<String> =
    listOf("我很好", "恶心", "便秘", "疲劳", "腹泻", "腹胀", "头痛", "食欲不振")

/**
 * 后端 summarize() 对这些 type 没有分支，标题会退回原始英文 key（今天页会显示 "glucose"）。
 * 补一个中文 label（值取后端 config.QUICK_TYPES 的名字）：只影响显示，不参与任何聚合。
 */
private val LABEL_FOR_TITLE: Set<String> =
    setOf("body_fat", "waist", "bp", "glucose", "hba1c", "lipid", "liver", "ketone", "muscle")

/** 餐次默认值：按后端 DEFAULT_SCHEDULE 的窗口猜，不在窗口内算加餐。 */
private fun defaultSlot(): String {
    val cal = java.util.Calendar.getInstance()
    val minutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    return when {
        minutes in 360..600 -> "breakfast"    // 06:00–10:00
        minutes in 660..870 -> "lunch"        // 11:00–14:30
        minutes in 1020..1260 -> "dinner"     // 17:00–21:00
        else -> "snack"
    }
}

/**
 * 剂量阶梯：只认"当前档案那支药"的 doseStepsMg。
 *
 * 刻意不做"匹配不上就用列表里第一支药"的兜底——把 A 药的阶梯显示给用 B 药的人，
 * 比不显示阶梯危险得多。匹配不上就退化成当前档案剂量这一项。
 */
private fun doseStepsFor(drugs: List<DrugOption>, profile: Profile): List<Double> {
    val match = drugs.firstOrNull { it.value == profile.drugId }
    val steps = match?.doseStepsMg?.filter { it > 0.0 }?.sorted().orEmpty()
    if (steps.isNotEmpty()) return steps
    return if (profile.doseMg > 0.0) listOf(profile.doseMg) else emptyList()
}

/** 默认选中当前档案剂量；它不在阶梯里（例如换过药）就不预选，让用户自己挑。 */
private fun defaultDose(steps: List<Double>, currentMg: Double): Double? {
    if (steps.size == 1) return steps[0]
    return steps.firstOrNull { abs(it - currentMg) < 0.001 }
}

/** "+250 / +500" 这类快捷按钮：在已填数字上累加，空着就从 0 开始。 */
private fun addAmount(current: String, delta: Int): String {
    val base = current.trim().toDoubleOrNull() ?: 0.0
    return formatNum(base + delta, 1)
}

private fun rangeText(min: Double, max: Double): String = formatNum(min) + "–" + formatNum(max)

/** 只认 YYYY-MM-DD；月/日范围做粗校验（闰年交给后端 parse_dt 兜底）。 */
private fun isDateText(value: String): Boolean {
    if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(value)) return false
    val parts = value.split("-")
    val month = parts[1].toIntOrNull() ?: return false
    val day = parts[2].toIntOrNull() ?: return false
    return month in 1..12 && day in 1..31
}

/** 只认 HH:mm（24 小时制，小时可以是一位数）。 */
private fun isTimeText(value: String): Boolean {
    val match = Regex("^(\\d{1,2}):(\\d{2})$").find(value) ?: return false
    val hour = match.groupValues[1].toIntOrNull() ?: return false
    val minute = match.groupValues[2].toIntOrNull() ?: return false
    return hour in 0..23 && minute in 0..59
}

// ================================================================ payload

/** 校验通过后的 payload；at 已经由「日期 + 时间」拼好（秒补 00）。 */
private class SaveData(
    val at: String,
    val value: Double?,
    val unit: String,
    val meta: Map<String, Any?>,
)

/**
 * 校验 + 组装 payload。
 *
 * 任何一项不合法都会把中文字段级原因写进 st.error 并返回 null——调用方只在拿到
 * 非 null 时才调 vm.addRecord，所以非法输入永远到不了后端。
 *
 * 各类型的键与 WebView 版 recordPayload() 一致；单位用后端 normalize_payload() 的口径
 * （训练 min、睡眠 h、副作用 级、血压 mmHg…），不要图好看改成中文单位。
 */
private fun buildPayload(type: String, st: EntryState, profile: Profile): SaveData? {
    st.error = null

    val date = st.date.trim()
    val time = st.time.trim()
    if (!isDateText(date)) {
        st.error = "日期格式应为 YYYY-MM-DD"
        return null
    }
    if (!isTimeText(time)) {
        st.error = "时间格式应为 HH:mm（24 小时制）"
        return null
    }
    val at = date + "T" + time + ":00"

    when (type) {
        "bp" -> {
            val systolic = reqNum(st, st.primary, "收缩压", "mmHg", 50.0, 300.0) ?: return null
            val diastolic = reqNum(st, st.second, "舒张压", "mmHg", 30.0, 200.0) ?: return null
            if (systolic <= diastolic) {
                st.error = "收缩压（${formatNum(systolic)}）必须大于舒张压（${formatNum(diastolic)}）"
                return null
            }
            return SaveData(
                at, systolic, "mmHg",
                measureMeta(type, "desc" to "${formatNum(systolic)}/${formatNum(diastolic)}"),
            )
        }

        "side_effect" -> {
            val label = st.label.trim()
            if (label.isEmpty()) {
                st.error = "请选择或填写不适名称"
                return null
            }
            val severity = st.severity
            if (severity == null) {
                st.error = "请选择严重程度（1–10）"
                return null
            }
            return SaveData(at, severity.toDouble(), "级", mapOf("label" to label, "severity" to severity))
        }

        "symptom" -> {
            // WebView 版在 label 为空时会把类型悄悄改成 note；这里直接拦下来，不让空症状入库
            val label = st.primary.trim()
            if (label.isEmpty()) {
                st.error = "请填写症状"
                return null
            }
            return SaveData(at, null, "", mapOf("label" to label))
        }

        "note" -> {
            val desc = st.primary.trim()
            if (desc.isEmpty()) {
                st.error = "备注不能为空"
                return null
            }
            return SaveData(at, null, "", mapOf("desc" to desc))
        }

        "training" -> {
            val minutes = reqNum(st, st.primary, "训练时长", "min", 0.0, 1440.0) ?: return null
            val desc = st.second.trim()
            val meta: Map<String, Any?> =
                if (desc.isEmpty()) emptyMap() else mapOf("desc" to desc)
            return SaveData(at, minutes, "min", meta)
        }

        "food" -> {
            val desc = st.primary.trim()
            if (desc.isEmpty()) {
                st.error = "请填写吃了什么"
                return null
            }
            // 五个营养字段都必发：留空发 0，后端的日均/合计口径才一致（少发键会被当成 0，
            // 但显式发出来能让"用户填了 0"和"用户没填"在库里长得一样、聚合不会漂）
            val kcal = optNum(st, st.kcal, "卡路里", 0.0, 5000.0) ?: return null
            val protein = optNum(st, st.protein, "蛋白质", 0.0, 500.0) ?: return null
            val carb = optNum(st, st.carb, "碳水", 0.0, 800.0) ?: return null
            val fat = optNum(st, st.fat, "脂肪", 0.0, 500.0) ?: return null
            val fiber = optNum(st, st.fiber, "纤维", 0.0, 200.0) ?: return null
            return SaveData(
                at, kcal, "kcal",
                mapOf(
                    "slot" to st.slot,
                    "desc" to desc,
                    "kcal" to kcal,
                    "protein_g" to protein,
                    "carb_g" to carb,
                    "fat_g" to fat,
                    "fiber_g" to fiber,
                ),
            )
        }

        "water" -> {
            val ml = reqNum(st, st.primary, "饮水量", "ml", 10.0, 5000.0) ?: return null
            return SaveData(at, ml, "ml", mapOf("amount_ml" to ml))
        }

        "dose" -> {
            val medName = profile.medName.trim().ifBlank { "未设置" }
            if (st.skipped) {
                // 跳过：剂量记 0，且不发 site（没有注射就没有部位）
                return SaveData(
                    at, 0.0, "mg",
                    mapOf("dose_mg" to 0.0, "med_name" to medName, "skipped" to true),
                )
            }
            val mg = st.doseMg
            if (mg == null) {
                st.error = "请选择剂量"
                return null
            }
            val meta = mutableMapOf<String, Any?>(
                "dose_mg" to mg,
                "med_name" to medName,
                "skipped" to false,
            )
            st.site?.let { meta["site"] = it }
            return SaveData(at, mg, "mg", meta)
        }
    }

    // 剩下的都是单数值类型
    val spec = NUM_SPECS[type]
    if (spec == null) {
        st.error = "不认识的记录类型：$type"
        return null
    }
    val v = reqNum(st, st.primary, spec.label, spec.unit, spec.min, spec.max) ?: return null
    return SaveData(at, v, spec.unit, measureMeta(type))
}

/**
 * 量测类记录的 meta。
 *
 * 需求表里这些类型的 meta 必填字段是"—"，所以这里不塞业务字段；只补一个中文 label
 * 供今天页显示（见 LABEL_FOR_TITLE 的说明）。extra 用于附加展示字段，例如血压的 "120/80"。
 */
private fun measureMeta(type: String, vararg extra: Pair<String, Any?>): Map<String, Any?> {
    val meta = mutableMapOf<String, Any?>()
    if (type in LABEL_FOR_TITLE) meta["label"] = typeSpec(type).label
    for ((key, value) in extra) meta[key] = value
    return meta
}

/** 必填数字：解析 + 范围校验，失败时写中文原因并返回 null。 */
private fun reqNum(
    st: EntryState,
    raw: String,
    label: String,
    unit: String,
    min: Double,
    max: Double,
): Double? {
    val text = raw.trim()
    if (text.isEmpty()) {
        st.error = "请填写$label"
        return null
    }
    val n = text.toDoubleOrNull()
    if (n == null || n.isNaN() || n.isInfinite()) {
        st.error = "$label 请输入数字"
        return null
    }
    if (n < min || n > max) {
        st.error = "$label 需在 ${rangeText(min, max)}${if (unit.isBlank()) "" else " $unit"} 之间"
        return null
    }
    return n
}

/** 选填数字：留空记 0（保证后端聚合口径一致），填了就必须在范围内。 */
private fun optNum(st: EntryState, raw: String, label: String, min: Double, max: Double): Double? {
    val text = raw.trim()
    if (text.isEmpty()) return 0.0
    val n = text.toDoubleOrNull()
    if (n == null || n.isNaN() || n.isInfinite()) {
        st.error = "$label 请输入数字，留空按 0 记"
        return null
    }
    if (n < min || n > max) {
        st.error = "$label 需在 ${rangeText(min, max)} 之间"
        return null
    }
    return n
}

/*
 * ---------------------------------------------------------------- payload 速查表
 *
 * type          value                unit       meta
 * weight        体重                 kg         （无）
 * body_fat      体脂                 %          label
 * waist         腰围                 cm         label
 * bp            收缩压               mmHg       label, systolic, diastolic, desc("120/80")
 * glucose       血糖                 mmol/L     label
 * hba1c         糖化                 %          label
 * lipid         血脂                 mmol/L     label
 * liver         肝功能               U/L        label
 * sleep         睡眠                 h          （无）
 * training      时长                 min        desc（项目名，选填）
 * side_effect   severity            级         label, severity
 * symptom       —                    ""         label
 * ketone        酮体                 mmol/L     label
 * muscle        肌肉量               kg         label
 * note          —                    ""         desc
 * water         amount_ml            ml         amount_ml
 * food          kcal                 kcal       slot, desc, kcal, protein_g, carb_g, fat_g, fiber_g
 * dose          dose_mg              mg         dose_mg, med_name, skipped, site（选填）
 *
 * label 只用于显示：后端 summarize() 对体脂/腰围/血压/血糖/糖化/血脂/肝功能/酮体/肌肉量
 * 没有分支，标题会退回英文 key（今天页会显示 "glucose"）。键名与后端 config.QUICK_TYPES 一致。
 * 各类型的必填 meta 键（需求表里列的那些）一个都没少。
 */
