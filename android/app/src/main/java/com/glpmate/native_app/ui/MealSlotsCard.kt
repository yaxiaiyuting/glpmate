package com.glpmate.native_app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.glpmate.native_app.data.MealSlot

/**
 * 今日三餐三栏（早 / 午 / 晚），加餐单独一行。
 *
 * ============ 为什么单独做这个 ============
 * 原来只有"今日已记录 N 餐"+ 五个宏量进度条，用户看不出**哪一餐吃了什么、
 * 多少热量**（真机反馈："不知道早餐、午餐、晚餐分别吃了什么"）。
 * 逐条流水也不行——那要自己把早餐的几条加起来。
 *
 * 所以这里按餐次分栏：每栏给这一餐的热量/蛋白小计，下面是明细。
 * **没记录的那一栏明确显示"未记录"**，而不是显示 0——
 * 0 kcal 会被读成"这一餐没吃"，而事实是"还没记"。
 */
@Composable
fun MealSlotsCard(
    slots: List<MealSlot>,
    totalKcal: Double,
    totalProtein: Double,
    kcalTarget: Double,
    proteinTarget: Double,
    modifier: Modifier = Modifier,
) {
    if (slots.isEmpty()) return

    val main = slots.filter { it.slot != "snack" }.take(3)
    val snacks = slots.firstOrNull { it.slot == "snack" }

    GlpCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("今日三餐", color = GlpColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                "${formatNum(totalKcal, 0)} / ${formatNum(kcalTarget, 0)} kcal · " +
                    "蛋白 ${formatNum(totalProtein, 0)}/${formatNum(proteinTarget, 0)} g",
                color = GlpColors.Text3,
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------------------- 三栏
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            main.forEach { slot ->
                // 宽度由调用方用 weight 分配，组件内部不 fillMaxWidth，
                // 否则三栏会各自占满导致只显示一栏（这个坑在五列营养上踩过）
                Column(Modifier.weight(1f)) {
                    Text(slot.label, color = GlpColors.Text2, fontSize = 11.5.sp)
                    Spacer(Modifier.height(4.dp))
                    if (slot.isEmpty) {
                        Text("未记录", color = GlpColors.Text3, fontSize = 12.sp)
                    } else {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                formatNum(slot.kcal, 0),
                                color = GlpColors.Food,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(" kcal", color = GlpColors.Text3, fontSize = 10.sp)
                        }
                        Text(
                            "蛋白 ${formatNum(slot.proteinG, 0)} g · ${slot.count} 条",
                            color = GlpColors.Text3,
                            fontSize = 10.sp,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    // 每栏一条细进度条，看这一餐占全天目标的多少
                    val ratio = if (kcalTarget > 0) (slot.kcal / kcalTarget).coerceIn(0.0, 1.0).toFloat() else 0f
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0x14FFFFFF)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(ratio)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(GlpColors.Food),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // 明细：每条的描述与时间
                    slot.items.forEach { item ->
                        Text(
                            item.desc.ifBlank { "（无描述）" },
                            color = GlpColors.Text2,
                            fontSize = 10.5.sp,
                            lineHeight = 14.sp,
                        )
                        Text(item.time, color = GlpColors.Text3, fontSize = 9.5.sp)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        // ---------------------------------------------------------- 加餐
        snacks?.let { s ->
            if (!s.isEmpty) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.label, color = GlpColors.Text2, fontSize = 11.5.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${formatNum(s.kcal, 0)} kcal · 蛋白 ${formatNum(s.proteinG, 0)} g",
                        color = GlpColors.Text3,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        s.items.joinToString("、") { it.desc }.ifBlank { "—" },
                        color = GlpColors.Text3,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
            }
        }

        // ---------------------------------------------------------- 蛋白缺口
        val proteinGap = proteinTarget - totalProtein
        if (proteinGap > 5) {
            Spacer(Modifier.height(10.dp))
            HintLine(
                "蛋白还差 ${formatNum(proteinGap, 0)} g。" +
                    "用药期食欲差时优先补它——蛋白质不够，减掉的体重里肌肉占比会明显升高。",
                "⚠️",
            )
        }
    }
}
