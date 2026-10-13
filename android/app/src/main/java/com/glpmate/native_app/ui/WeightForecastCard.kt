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
import com.glpmate.native_app.data.WeightForecast

/**
 * 体重预期卡：用说明书 III 期试验的**实测终点**估"大概会瘦多少"。
 *
 * ============ 为什么不再只报热量缺口 ============
 * 原来只有"缺口 ÷ 7700 kcal"这一个数。那只描述**能量账**：吃进来比目标少多少，
 * 理论上对应多少体重。但 GLP-1 用药者实际掉秤往往更快——胃排空变慢、饱腹信号增强、
 * 能耗结构也可能变化。只报能量账会系统性低估。
 *
 * 所以这里以 SURMOUNT-1 的实测终点为主，能量账作为对照一起给，
 * 并说明两者为什么会不一样。
 *
 * ============ 两个刻意的选择 ============
 * ① **近端里程碑优先**（12 / 24 周），72 周是终点参考。
 *    只报 72 周指导不了当下；12 周约三个月，正好是一次复诊的间隔。
 * ② **目标差距如实说，但不宣判**。
 *    如果目标幅度超过试验平均值，界面会写明"是试验平均的 N 倍"，
 *    同时说明个体差异很大、建议和医生讨论阶段性目标——
 *    隐瞒差距等于让用户按不现实的预期努力；只丢一个"到不了"又是在替医生下结论。
 */
@Composable
fun WeightForecastCard(forecast: WeightForecast?, modifier: Modifier = Modifier) {
    if (forecast == null) return

    GlpCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("体重预期", color = GlpColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(
                    "按 ${forecast.trialName} 实测终点 · 用 ${fmtDose(forecast.usedDoseMg)} mg 组数据",
                    color = GlpColors.Text3,
                    fontSize = 11.sp,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---------------------------------------------------------- 里程碑
        forecast.basisWeightKg?.let { base ->
            Text("预期体重", color = GlpColors.Text2, fontSize = 11.5.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                forecast.milestones.forEach { m ->
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${m.weeks} 周",
                            color = GlpColors.Text3,
                            fontSize = 10.5.sp,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            formatNum(m.weightKg, 0),
                            color = GlpColors.Weight,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("kg", color = GlpColors.Text3, fontSize = 10.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${formatNum(m.pct, 1)}%",
                            color = GlpColors.Text3,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "基线 ${formatNum(base, 1)} kg" +
                    if (forecast.basisIsStartWeight) "（起始体重）" else "（当前体重）",
                color = GlpColors.Text3,
                fontSize = 10.5.sp,
            )
        }

        // ---------------------------------------------------------- 瘦体重拆分
        //
        // 治疗刚开始时预期减重可能只有零点几公斤，拆分出来会变成
        // "瘦体重约 0 kg、脂肪约 0.1 kg"——"0 kg" 看着像 bug。
        // 所以低于 1 kg 时不显示拆分，改为提示"疗程还短"。
        forecast.leanMassLossKg?.let { lean ->
            val total = abs(forecast.expectedLossKg ?: 0.0)
            if (total >= 1.0) {
                Spacer(Modifier.height(10.dp))
                forecast.fatMassLossKg?.let { fat ->
                    Text(
                        "预计减重里，瘦体重约 ${formatNum(abs(lean), 1)} kg、脂肪约 ${formatNum(abs(fat), 1)} kg",
                        color = GlpColors.Text3,
                        fontSize = 10.5.sp,
                    )
                    Text(
                        "瘦体重约占减重量的 25%。用药期蛋白质吃不够会掉肌肉，这也是为什么要盯着蛋白质达标。",
                        color = GlpColors.Text3,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                    )
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    "疗程还短，预期减重不到 1 kg，暂时不拆分瘦体重与脂肪。",
                    color = GlpColors.Text3,
                    fontSize = 10.5.sp,
                )
            }
        }

        // ---------------------------------------------------------- 目标
        forecast.goal?.let { g ->
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("目标", color = GlpColors.Text2, fontSize = 11.5.sp)
                Spacer(Modifier.weight(1f))
                if (g.basisNote.isNotBlank()) {
                    Text(g.basisNote, color = GlpColors.Text3, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(6.dp))

            if (g.alreadyThere) {
                Text("已经达到目标体重了 🎉", color = GlpColors.Accent, fontSize = 13.sp)
            } else {
                Text(
                    "${formatNum(g.targetWeightKg, 0)} kg 需要再减 ${formatNum(g.needLossKg, 1)} kg" +
                        "（${formatNum(g.needPct, 1)}% 体重）",
                    color = GlpColors.Text,
                    fontSize = 12.5.sp,
                )
                Spacer(Modifier.height(4.dp))
                if (g.reachableWithinTrial && g.weeksToGoal != null) {
                    Text(
                        "按这个方案，预计约 ${formatNum(g.weeksToGoal, 0)} 周达到",
                        color = GlpColors.Accent,
                        fontSize = 12.sp,
                    )
                } else {
                    // 到不了就如实说，并给出参照——但措辞不替医生下结论
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(GlpColors.Dose.copy(alpha = 0.18f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                g.ratioVsTrial?.let { "${formatNum(it, 1)} 倍" } ?: "超出",
                                color = GlpColors.Dose,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "试验平均减 ${formatNum(g.trialEndpointPct, 1)}%",
                            color = GlpColors.Text2,
                            fontSize = 11.5.sp,
                        )
                    }
                    g.note?.let {
                        Spacer(Modifier.height(5.dp))
                        Text(it, color = GlpColors.Text3, fontSize = 10.5.sp, lineHeight = 15.sp)
                    }
                }
            }
        }

        // ---------------------------------------------------------- 依据
        Spacer(Modifier.height(12.dp))
        HintLine(
            if (forecast.isExtrapolatedCurve) {
                "终点百分比来自 ${forecast.trialName} 的实测；中间各周的时间曲线是拟合" +
                    "（公开数据只给终点）。${forecast.trialCaveat}"
            } else {
                forecast.trialCaveat
            },
            "ℹ️",
        )
        if (forecast.trialPopulation.isNotBlank()) {
            Text(
                "${forecast.trialName} 人群：${forecast.trialPopulation}",
                color = GlpColors.Text3,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
        }
    }
}

/** 剂量显示：2.5 → "2.5"，15.0 → "15"。 */
private fun fmtDose(mg: Double): String =
    if (mg == mg.toLong().toDouble()) mg.toLong().toString() else mg.toString()

private fun abs(v: Double): Double = if (v < 0) -v else v
