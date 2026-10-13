package com.glpmate.native_app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glpmate.native_app.data.Nutrition

/**
 * 营养达标卡（对照《中国居民膳食指南（2022）》）。
 *
 * ============ 设计上的两个要害 ============
 *
 * ① **这是"够了没有"，不是"超标了没有"。**
 *    GLP-1 用药期间最常见的问题是被食欲抑制到吃太少：蛋白质不足会掉肌肉，
 *    纤维不足会加重便秘（最常见的副作用之一）。所以蛋白质和纤维的进度条
 *    把"推荐下限"画成一条明确的刻度线，达标前用警示色，而不是像热量那样
 *    "越接近 100% 越好"。
 *
 * ② **不把运动消耗算进热量缺口。**
 *    本应用没有可靠的运动消耗数据（强度、体重、动作都不知道），与其给一个
 *    看起来精确的假数字，不如只报"吃进来多少 vs 目标"，并在界面上说明。
 */
@Composable
fun NutritionCard(nutrition: Nutrition?, modifier: Modifier = Modifier) {
    if (nutrition == null || !nutrition.hasData) return

    GlpCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("营养达标", color = GlpColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(
                    "对照中国居民膳食指南（2022）",
                    color = GlpColors.Text3,
                    fontSize = 11.sp,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---------------------------------------------------------- 热量
        val kcalPct = nutrition.kcalPct
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                formatNum(nutrition.kcalValue, 0),
                color = GlpColors.Text,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(" / ${formatNum(nutrition.kcalTarget, 0)} kcal", color = GlpColors.Text2, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            kcalPct?.let {
                Text("${formatNum(it, 0)}%", color = GlpColors.Text2, fontSize = 12.5.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        // 热量是"越接近目标越好"的单一目标，所以直接用比例条，
        // 不用下面 RangeBarRow 那种带下限刻度的区间条
        val kcalRatio = if (nutrition.kcalTarget > 0) {
            (nutrition.kcalValue / nutrition.kcalTarget).coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0x14FFFFFF)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(kcalRatio)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(GlpColors.Dose),
            )
        }

        // ---------------------------------------------------------- 缺口
        nutrition.deficit?.let { d ->
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(deficitColor(d.verdict).copy(alpha = 0.18f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        "缺口 ${formatNum(d.value, 0)} kcal",
                        color = deficitColor(d.verdict),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                // 这里**刻意不再显示"≈ 每周 X kg"**：那是纯能量账的推算
                // （缺口 ÷ 7700），而下面的「体重预期」卡用的是 SURMOUNT-1 的实测终点。
                // 两个数字会不一致，摆在一起只会让人不知道该信哪个——
                // 减重的估算统一由「体重预期」卡负责，这里只说热量缺口本身。
            }
            Spacer(Modifier.height(4.dp))
            Text(d.note, color = GlpColors.Text3, fontSize = 10.5.sp, lineHeight = 15.sp)
            Text(d.formula, color = GlpColors.Text3, fontSize = 10.sp, lineHeight = 14.sp)
        }

        // ---------------------------------------------------------- 蛋白质 / 纤维
        nutrition.protein?.let {
            Spacer(Modifier.height(14.dp))
            RangeBarRow(
                label = "蛋白质",
                value = it.value,
                min = it.min,
                good = it.good,
                unit = "g",
                color = GlpColors.Weight,
                reached = it.reachedMin,
            )
        }
        nutrition.fiber?.let {
            Spacer(Modifier.height(12.dp))
            RangeBarRow(
                label = "膳食纤维",
                value = it.value,
                min = it.min,
                good = it.good,
                unit = "g",
                color = GlpColors.Water,
                reached = it.reachedMin,
            )
        }
        if (nutrition.proteinBasis.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "蛋白质目标依据：${nutrition.proteinBasis}",
                color = GlpColors.Text3,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
        }

        // ---------------------------------------------------------- 供能比
        val shares = listOfNotNull(
            nutrition.proteinShare?.let { Triple("蛋白", it, GlpColors.Weight) },
            nutrition.carb?.let { Triple("碳水", it, GlpColors.Food) },
            nutrition.fat?.let { Triple("脂肪", it, GlpColors.Sleep) },
        )
        if (shares.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("供能比（指南区间）", color = GlpColors.Text2, fontSize = 11.5.sp)
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                shares.forEach { (label, part, color) ->
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                formatNum(part.pct, 0),
                                color = amdrColor(part.verdict),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text("%", color = GlpColors.Text3, fontSize = 10.sp)
                        }
                        Text(label, color = GlpColors.Text3, fontSize = 10.sp)
                        Spacer(Modifier.height(3.dp))
                        // 供能比也是区间：把指南给的 lo–hi 画成一段，指针落在里面就是合格
                        RangeBarRow(
                            label = "",
                            value = part.pct,
                            min = part.min,
                            good = part.max,
                            unit = "%",
                            color = color,
                            reached = part.isOk,
                            compact = true,
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------- 需要补什么
        if (nutrition.gaps.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("今天还差什么", color = GlpColors.Text2, fontSize = 11.5.sp)
            Spacer(Modifier.height(6.dp))
            nutrition.gaps.forEach { gap ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Box(
                        Modifier
                            .padding(top = 5.dp)
                            .width(5.dp)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(GlpColors.Dose),
                    )
                    Spacer(Modifier.width(7.dp))
                    Column {
                        Text(
                            "${gap.label} 还差 ${formatNum(gap.need, 0)} ${gap.unit}",
                            color = GlpColors.Text,
                            fontSize = 12.sp,
                        )
                        if (gap.hint.isNotBlank()) {
                            Text(gap.hint, color = GlpColors.Text3, fontSize = 10.5.sp, lineHeight = 15.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 一根带"推荐下限"刻度的进度条。
 *
 * 为什么不直接复用 `ThinBar`：那个是"值/目标"的简单比例，而这里要表达的是
 * **区间**——刻度线画在"推荐下限"的位置，未达标时整条用警示色，
 * 达标后转成正常色。用户一眼能看出"够没够"，而不是去比一个百分比数字。
 */
@Composable
private fun RangeBarRow(
    label: String,
    value: Double,
    min: Double,
    good: Double,
    unit: String,
    color: Color,
    reached: Boolean,
    compact: Boolean = false,
) {
    val denom = if (good > 0) good else (if (min > 0) min else 1.0)
    val ratio = (value / denom).coerceIn(0.0, 1.0).toFloat()
    val minRatio = (min / denom).coerceIn(0.0, 1.0).toFloat()
    val shown = if (reached) color else GlpColors.Dose

    if (label.isNotBlank()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, color = GlpColors.Text2, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(
                "${formatNum(value, 0)} / ${formatNum(min, 0)}$unit",
                color = if (reached) GlpColors.Text2 else GlpColors.Dose,
                fontSize = 12.sp,
                fontWeight = if (reached) FontWeight.Normal else FontWeight.Medium,
            )
            if (reached) {
                Spacer(Modifier.width(6.dp))
                Text("✓", color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(5.dp))
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(if (compact) 4.dp else 6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0x14FFFFFF)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(ratio)
                .height(if (compact) 4.dp else 6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(shown),
        )
        // 推荐下限的刻度线：这条线是"够没够"的判据，必须可见
        if (minRatio > 0.02f && minRatio < 0.98f) {
            Box(
                Modifier
                    .fillMaxWidth(minRatio)
                    .height(if (compact) 4.dp else 6.dp),
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .width(2.dp)
                        .height(if (compact) 4.dp else 6.dp)
                        .background(GlpColors.Text.copy(alpha = 0.55f)),
                )
            }
        }
    }
}

/** 热量缺口按严重程度着色：过快用警示色，合适用主色。 */
private fun deficitColor(verdict: String): Color = when (verdict) {
    "fast" -> Color(0xFFE8607A)
    "slightly_fast" -> GlpColors.Dose
    "good" -> GlpColors.Accent
    "slow" -> GlpColors.Text2
    else -> GlpColors.Text3
}

/** 供能比是否落在指南区间：越界用警示色。 */
private fun amdrColor(verdict: String): Color = when (verdict) {
    "ok" -> GlpColors.Accent
    "high" -> Color(0xFFE8607A)
    "low" -> GlpColors.Dose
    else -> GlpColors.Text2
}
