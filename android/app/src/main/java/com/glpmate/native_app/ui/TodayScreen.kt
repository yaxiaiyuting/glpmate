package com.glpmate.native_app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glpmate.native_app.data.Bootstrap
import com.glpmate.native_app.data.LevelPoint
import com.glpmate.native_app.data.RecordView

/**
 * 今天页（原生版）。
 *
 * 版块顺序对齐 WebView 版与参考设计：
 *   问候 → 下次剂量 → 体内药物水平（可触摸）→ 免责声明 → 今日记录
 *   → 感受 → 餐食 → 饮水 → 已完成 → 体重/健康 → 快速记录
 *
 * 药物水平曲线的三个口径在界面上必须写清楚（这是全应用最容易误导人的地方）：
 *   - 大数字：相对**稳态谷值**的百分比（谷=100%，峰≈200%）
 *   - 曲线形态：给药后约 24 小时达峰，不是注射瞬间满格
 *   - 免责声明：群体药代动力学估算，不是血药浓度
 */
@Composable
fun TodayScreen(
    data: Bootstrap?,
    busy: Boolean,
    onAddWater: (Int) -> Unit,
    onQuickRecord: (String) -> Unit,
    onOpenFood: (String) -> Unit = {},
) {
    if (data == null) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Text("正在加载…", color = GlpColors.Text2, fontSize = 14.sp)
        }
        return
    }

    Column(
        Modifier
            .fillMaxWidth()
            // 状态栏 inset 必须显式加：enableEdgeToEdge 之后内容会画到状态栏下面，
            // 而 Scaffold 没参与布局（导航是手写的），所以没人替我们让位
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        GreetingHeader(data)
        NextDoseCard(data)
        LevelCard(data)
        DisclaimerNote(data)

        SectionHead("今日你的记录")
        FeelingCard(data)
        MealCard(data, onOpenFood)
        // 三餐三栏：紧接餐食卡，回答"早/午/晚分别吃了什么、多少热量"
        MealSlotsCard(
            slots = data.mealsBySlot,
            totalKcal = data.stats.calories.v,
            totalProtein = data.stats.protein.v,
            kcalTarget = data.stats.calories.t,
            proteinTarget = data.stats.protein.t,
        )
        // 营养达标紧跟餐食卡：用户刚记完一餐，最想知道的就是"今天够了没有"
        NutritionCard(data.nutrition)
        // 体重预期紧跟营养达标：一个说"吃够了没有"，一个说"照这样会瘦多少"，
        // 两件事互为因果（蛋白质不够会掉肌肉，而掉肌肉会影响减重质量）
        WeightForecastCard(data.weightForecast)
        WaterCard(data, busy, onAddWater)
        CompletedCard(data)
        HalfCards(data)
        QuickRecordCard(data, onQuickRecord)

        Spacer(Modifier.height(96.dp))   // 给底部导航留出空间
    }
}

@Composable
private fun GreetingHeader(data: Bootstrap) {
    Column(Modifier.padding(top = 8.dp, bottom = 10.dp)) {
        Text(
            "${weekdayCn(data.weekday)} · 第 ${data.dayIndex} 天",
            color = GlpColors.Text3,
            fontSize = 12.5.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "你好，${data.profile.displayName}",
            color = GlpColors.Text,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun NextDoseCard(data: Bootstrap) {
    val dose = data.nextDose ?: return
    GlpCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(GlpColors.Dose.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) { Text("💉", fontSize = 17.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("下次剂量", color = GlpColors.Text3, fontSize = 11.5.sp)
                Text(
                    "${dose.weekdayCn} ${dose.time} · ${dose.medName} ${formatNum(dose.doseMg)} mg",
                    color = GlpColors.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (dose.overdue) "已过期" else "剩余 ${dose.remainingDays} 天",
                    color = GlpColors.Dose,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (dose.overdue) "请按医生安排" else "剩余",
                    color = GlpColors.Text3,
                    fontSize = 11.sp,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${shortDate(dose.date)} ${dose.weekdayCn} ${dose.time} 服药",
                color = GlpColors.Text2,
                fontSize = 12.5.sp,
                modifier = Modifier.weight(1f),
            )
            PillButton(if (dose.isDueToday) "记录这次注射" else "我的剂量") {
                // 由外层决定行为（当前先记一条剂量）
            }
        }
    }
}

@Composable
private fun LevelCard(data: Bootstrap) {
    val level = data.level
    GlpCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("体内药物水平", color = GlpColors.Text2, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            if (level != null) {
                Text(
                    "半衰期 ${formatNum(level.halfLifeHours, 0)}h · 达峰 ${formatNum(level.tmaxHours, 0)}h",
                    color = GlpColors.Text3,
                    fontSize = 11.sp,
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        if (level == null || level.series.isEmpty()) {
            EmptyState("还没有药物水平数据", "记录一次剂量后就会显示曲线")
            return@GlpCard
        }

        // 大数字：相对**稳态理论上限**的百分比（不是相对谷值）。
        //
        // 为什么改口径：之前用"稳态谷值 = 100%"，给药后峰值会到 ~196%。
        // 数学没错，但没人能一眼看懂"102.3%"——用户的直觉"这不该超过 100%"
        // 才是对的。换成上限为 100% 后：谷值约 51%、峰值接近 100%，
        // 而且和下面的进度条语义一致。
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                formatNum(level.pctMax, 0),
                color = GlpColors.Text,
                fontSize = 40.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text("%", color = GlpColors.Text2, fontSize = 18.sp, modifier = Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("当前 ${formatNum(level.currentMg, 2)} mg", color = GlpColors.Text2, fontSize = 12.sp)
                Text("相对稳态峰值", color = GlpColors.Text3, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(4.dp))

        // 曲线：x 用相对位置，y 用 mg
        val points = remember(level.series) { level.series.toChartPoints() }
        TouchLineChart(
            points = points,
            color = GlpColors.Accent,
            goalValue = null,
            hint = "按住曲线拖动可读出每个时间点的估算水平",
            // x 轴刻度用日期（点的 label 是时刻，看跨度要用日期）
            xLabels = remember(level.series) {
                listOfNotNull(
                    level.series.firstOrNull()?.t,
                    level.series.getOrNull(level.series.size / 2)?.t,
                    level.series.lastOrNull()?.t,
                ).map { shortDate(it) }.distinct()
            },
        )

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(GlpColors.Accent),
            )
            Spacer(Modifier.width(6.dp))
            Text(level.trend.ifBlank { "—" }, color = GlpColors.Text2, fontSize = 12.5.sp)
            Spacer(Modifier.weight(1f))
            Text(
                "稳态峰值 ${formatNum(level.maxMg, 2)} mg = 100%",
                color = GlpColors.Text3,
                fontSize = 11.sp,
            )
        }
    }
}

/** 把后端的水平序列转成图表点（x 归一化到 0..1）。 */
private fun List<LevelPoint>.toChartPoints(): List<ChartPoint> {
    if (isEmpty()) return emptyList()
    val n = size
    return mapIndexed { index, p ->
        ChartPoint(
            x = if (n <= 1) 0.5f else index.toFloat() / (n - 1).toFloat(),
            y = p.mg.toFloat(),
            // 气泡里的时间只用 HH:mm：带日期会让气泡变得很宽，贴边时更容易被夹变形；
            // 起止日期已经画在 x 轴两端了
            label = p.t.substringAfter('T', "").take(5),
            value = formatNum(p.mg, 3) + " mg",
        )
    }
}

@Composable
private fun DisclaimerNote(data: Bootstrap) {
    HintLine(
        data.level?.disclaimer?.ifBlank { null }
            ?: "基于群体药代动力学的估算，并非医疗测量。",
    )
}

@Composable
private fun FeelingCard(data: Bootstrap) {
    val chips = data.sideEffectChips.ifEmpty {
        listOf("我很好", "恶心", "便秘", "疲劳")
    }
    var selected by remember { mutableStateOf<String?>(null) }
    GlpCard {
        Text("你感觉如何？", color = GlpColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        // 两行铺开，避免横向滚动（手指横滑在这个应用里留给图表）
        chips.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                row.forEach { chip ->
                    ChoiceChip(chip, selected == chip) { selected = chip }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
        Text(
            if (selected == null) "点一下就能记下来，不用打字"
            else "已选择「$selected」· 保存需要填写严重度",
            color = GlpColors.Text3,
            fontSize = 11.5.sp,
        )
    }
}

@Composable
private fun MealCard(data: Bootstrap, onOpenFood: (String) -> Unit) {
    val s = data.stats
    GlpCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("晚餐", color = GlpColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(
                "今日已记录 ${s.mealsLogged} 餐",
                color = GlpColors.Text3,
                fontSize = 11.5.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            MacroStat("卡路里", s.calories.v, s.calories.t, GlpColors.Dose, "",
                Modifier.weight(1f))
            MacroStat("蛋白质", s.protein.v, s.protein.t, GlpColors.Weight, "g",
                Modifier.weight(1f))
            MacroStat("碳水", s.carbs.v, s.carbs.t, GlpColors.Food, "g",
                Modifier.weight(1f))
            MacroStat("脂肪", s.fat.v, s.fat.t, GlpColors.Sleep, "g",
                Modifier.weight(1f))
            MacroStat("纤维", s.fiber.v, s.fiber.t, GlpColors.Water, "g",
                Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) {
                PrimaryButton("+ 添加餐食", onClick = { onOpenFood("manual") })
            }
            Spacer(Modifier.width(10.dp))
            // 拍照 / 描述两个快捷入口直接摆在卡片上：饮食记录最大的流失点是
            // "懒得手填"，所以把最快的两条路放外面，不用先进表单再切换
            PillButton("📷 拍照", GlpColors.Accent) { onOpenFood("photo") }
            Spacer(Modifier.width(8.dp))
            PillButton("✍️ 描述", GlpColors.Weight) { onOpenFood("text") }
        }
        Spacer(Modifier.height(6.dp))
        HintLine("拍照或写一句「中午吃了红烧鲫鱼配米饭」，估算结果确认后才入账", "💡")
    }
}

@Composable
private fun WaterCard(data: Bootstrap, busy: Boolean, onAddWater: (Int) -> Unit) {
    val water = data.stats.water
    GlpCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("饮水", color = GlpColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                "${formatNum(water.v, 2)} / ${formatNum(water.t, 1)} 升",
                color = GlpColors.Text2,
                fontSize = 12.5.sp,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "缓解恶心和便秘的第一道防线",
            color = GlpColors.Text3,
            fontSize = 11.5.sp,
        )
        Spacer(Modifier.height(10.dp))
        ThinBar(water.v, water.t, GlpColors.Water, height = 6)
        Spacer(Modifier.height(14.dp))
        Row {
            PillButton(if (busy) "记录中…" else "+250 ml", GlpColors.Water) {
                if (!busy) onAddWater(250)
            }
            Spacer(Modifier.width(10.dp))
            PillButton("自定义", GlpColors.Text2) { }
        }
    }
}

@Composable
private fun CompletedCard(data: Bootstrap) {
    if (data.completed.isEmpty()) {
        GlpCard {
            Text("已完成", color = GlpColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            EmptyState("今天还没有记录", "点下面的按钮开始记录")
        }
        return
    }
    GlpCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("已完成", color = GlpColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("${data.completed.size} 条", color = GlpColors.Text3, fontSize = 11.5.sp)
        }
        Spacer(Modifier.height(4.dp))
        data.completed.forEach { record ->
            ListRow(
                title = record.title,
                subtitle = record.subtitle,
                rightTop = record.value?.let { formatNum(it, 0) } ?: "—",
                rightBottom = record.time,
                accent = colorForType(record.type),
                iconText = iconForType(record.type),
            )
        }
    }
}


/** BMI 分级的颜色：正常用主色、超重用琥珀、肥胖用警示红、偏瘦用蓝色。 */
private fun bmiColor(key: String): Color = when (key) {
    "normal" -> GlpColors.Accent
    "underweight" -> GlpColors.Water
    "overweight" -> GlpColors.Dose
    "obese" -> Color(0xFFE8607A)
    else -> GlpColors.Text3
}

@Composable
private fun HalfCards(data: Bootstrap) {
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f)) {
            val weight = data.weight
            GlpCard {
                Text("体重", color = GlpColors.Text3, fontSize = 11.5.sp)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        weight.latest?.let { formatNum(it, 1) } ?: "—",
                        color = GlpColors.Text,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(" kg", color = GlpColors.Text2, fontSize = 13.sp)
                }
                Spacer(Modifier.height(4.dp))
                val delta = weight.delta ?: 0.0
                Text(
                    (if (delta > 0) "+" else "") + formatNum(delta, 1) + " kg" +
                        (weight.goal?.let { " · 目标 ${formatNum(it, 0)}" } ?: ""),
                    color = GlpColors.Text3,
                    fontSize = 11.sp,
                )

                // BMI：分级颜色直接反映中国成人标准（正常/超重/肥胖），
                // 这是医生和保险都看的那个数。身高没填就提示去填，不编数字。
                Spacer(Modifier.height(6.dp))
                val bmi = data.bmi
                when {
                    bmi == null -> Unit
                    bmi.hasBmi -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "BMI ${formatNum(bmi.bmi ?: 0.0, 1)}",
                                color = bmiColor(bmi.category.key),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                bmi.category.label,
                                color = GlpColors.Text3,
                                fontSize = 11.sp,
                            )
                        }
                        bmi.toHealthyKg?.let { gap ->
                            if (gap > 0) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "再减 ${formatNum(gap, 1)} kg 进正常范围",
                                    color = GlpColors.Text3,
                                    fontSize = 10.5.sp,
                                )
                            }
                        }
                    }
                    else -> Text(
                        "填身高后算 BMI",
                        color = GlpColors.Text3,
                        fontSize = 11.sp,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            GlpCard {
                Text("健康", color = GlpColors.Text3, fontSize = 11.5.sp)
                Spacer(Modifier.height(6.dp))
                val worst = data.stats.sideEffects.maxOfOrNull { it.value ?: 0.0 } ?: 0.0
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (worst > 0) formatNum(worst, 0) else "—",
                        color = GlpColors.Text,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(" /10", color = GlpColors.Text2, fontSize = 13.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (data.stats.sideEffects.isEmpty()) "暂无副作用记录"
                    else data.stats.sideEffects.joinToString("、") { it.title },
                    color = GlpColors.Text3,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun QuickRecordCard(data: Bootstrap, onQuickRecord: (String) -> Unit) {
    GlpCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("快速记录", color = GlpColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("15 种类型", color = GlpColors.Text3, fontSize = 11.5.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(
                Triple("体重", "⚖️", "weight"),
                Triple("体脂", "％", "body_fat"),
                Triple("腰围", "📏", "waist"),
                Triple("血压", "🩺", "bp"),
                Triple("血糖", "🩸", "glucose"),
            ).forEach { (label, icon, type) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onQuickRecord(type) }
                        .padding(6.dp),
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(colorForType(type).copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) { Text(icon, fontSize = 18.sp) }
                    Spacer(Modifier.height(5.dp))
                    Text(label, color = GlpColors.Text2, fontSize = 11.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 辅助
internal fun colorForType(type: String): Color = when (type) {
    "dose" -> GlpColors.Dose
    "weight" -> GlpColors.Weight
    "food" -> GlpColors.Food
    "water" -> GlpColors.Water
    "sleep" -> GlpColors.Sleep
    "side_effect" -> GlpColors.Food
    "training" -> GlpColors.Weight
    else -> GlpColors.Accent
}

internal fun iconForType(type: String): String = when (type) {
    "dose" -> "💉"
    "weight" -> "⚖️"
    "food" -> "🍽"
    "water" -> "💧"
    "sleep" -> "🌙"
    "side_effect" -> "🩺"
    "training" -> "🏃"
    else -> "📝"
}
