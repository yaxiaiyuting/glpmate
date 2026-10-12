package com.glpmate.native_app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 可触摸折线图 —— **刻意做得很简单**。
 *
 * ============ 为什么重写过一次（别再往里加缩放/平移）============
 * 第一版我给它加了双指缩放 + 单指平移的时间窗口（span/offset 那套）。结果三个 bug：
 *   - 后端明明返回了完整序列，曲线上却有一段画不出来：y 轴值域只按"窗口内的点"
 *     计算，窗口一偏值域就崩，剩下的点被压成一条平线或飞出画布
 *   - 双指拖不回原位置：可平移量在 span=1 时是 1 − 1/1 = 0，数学上就动不了；
 *     而我为绕开它写的"拖动时自动收窄窗口"又引入了锚点换算错误
 *   - 一处 coerceIn(0f, 负数) 直接把应用打崩
 * 三处 bug 全部出自同一套机制。而 WebView 版的图表**没有**缩放平移，
 * 只是整条画完 + 游标读数，反而一直很稳。
 *
 * 所以这里照 Web 版的模型做：**整条序列一屏画完，横向不做任何窗口裁剪**，
 * 手势只负责"拖动游标读数"。想看更细的区间就换数据（调用方传不同的 days），
 * 而不是在图表内部做视口变换——那样坐标系只有一套，不可能互相打架。
 */

/** 图表上的一个点。x 为 0..1 的相对横坐标，由调用方给出。 */
data class ChartPoint(
    val x: Float,
    val y: Float,
    val label: String,
    val value: String,
)

@Composable
fun TouchLineChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    chartHeight: Int = 220,
    color: Color = GlpColors.Accent,
    goalValue: Float? = null,
    hint: String = "按住曲线左右拖动可读出每个点",
    /**
     * x 轴刻度文字，从起点到终点。只取首/中/末三个画出来。
     *
     * 为什么单独传而不是从 points 的 label 猜：点的 label 在各图里格式不同
     * （剂量图是 "12:15"，热量图是 "10月12日"）。让调用方给准的，图表不猜。
     */
    xLabels: List<String> = emptyList(),
) {
    if (points.isEmpty()) {
        Box(modifier.fillMaxWidth().height(chartHeight.dp), contentAlignment = Alignment.Center) {
            Text("暂无数据", color = GlpColors.Text3, fontSize = 12.sp)
        }
        return
    }

    var selected by remember(points) { mutableStateOf(-1) }
    var bubbleVisible by remember(points) { mutableStateOf(false) }

    // 松手后气泡再留一会儿，方便看清数字；再次触摸会重置这个计时
    LaunchedEffect(bubbleVisible, selected) {
        if (bubbleVisible) {
            delay(3000)
            bubbleVisible = false
            selected = -1
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val containerWidthPx = constraints.maxWidth.toFloat()

        Box(Modifier.fillMaxWidth()) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(chartHeight.dp)
                    // 只保留一个手势处理器。多个 pointerInput 会互相抢事件
                    // （先命中的消费掉，后面的收不到），这也是上一版"拖不动"的原因之一。
                    .pointerInput(points) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            selected = nearestIndex(points, down.position.x, size.width.toFloat())
                            bubbleVisible = true
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                val change = pressed.first()
                                selected = nearestIndex(points, change.position.x, size.width.toFloat())
                                bubbleVisible = true
                                change.consume()
                            }
                        }
                    },
            ) {
                val padX = 12f
                val padTop = 20f
                val padBottom = 26f
                val plotW = (size.width - padX * 2).coerceAtLeast(1f)
                val plotH = (size.height - padTop - padBottom).coerceAtLeast(1f)

                // 整条序列都参与：不做任何窗口裁剪，值域也就永远稳定。
                val values = points.map { it.y }
                var minV = values.min()
                var maxV = values.max()
                if (goalValue != null) {
                    minV = minOf(minV, goalValue)
                    maxV = maxOf(maxV, goalValue)
                }
                val rawSpan = maxV - minV
                val span = if (rawSpan > 0.0001f) rawSpan else (abs(maxV) * 0.1f).coerceAtLeast(1f)
                minV -= span * 0.10f
                maxV += span * 0.18f   // 上方留空间给气泡

                fun px(i: Int) = padX + points[i].x.coerceIn(0f, 1f) * plotW
                fun py(v: Float) = padTop + plotH - ((v - minV) / (maxV - minV)) * plotH

                // 横向网格
                for (i in 0..3) {
                    val y = padTop + plotH * i / 3f
                    drawLine(Color(0x14FFFFFF), Offset(padX, y), Offset(size.width - padX, y), 1.4f)
                }

                // 目标线
                if (goalValue != null) {
                    val gy = py(goalValue)
                    drawLine(
                        color.copy(alpha = 0.55f),
                        Offset(padX, gy),
                        Offset(size.width - padX, gy),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
                    )
                }

                // 曲线 + 面积
                val line = Path()
                val area = Path()
                points.forEachIndexed { i, p ->
                    val x = px(i)
                    val y = py(p.y)
                    if (i == 0) {
                        line.moveTo(x, y)
                        area.moveTo(x, padTop + plotH)
                        area.lineTo(x, y)
                    } else {
                        line.lineTo(x, y)
                        area.lineTo(x, y)
                    }
                }
                area.lineTo(px(points.lastIndex), padTop + plotH)
                area.close()

                drawPath(
                    area,
                    brush = Brush.verticalGradient(
                        listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0f)),
                        startY = padTop,
                        endY = padTop + plotH,
                    ),
                )
                drawPath(line, color = color, style = Stroke(width = 4.5f))

                if (points.size <= 40) {
                    points.forEachIndexed { i, p ->
                        drawCircle(color, radius = 4f, center = Offset(px(i), py(p.y)))
                    }
                }

                // 游标
                if (selected in points.indices) {
                    val x = px(selected)
                    val y = py(points[selected].y)
                    drawLine(
                        Color(0x59FFFFFF), Offset(x, padTop), Offset(x, padTop + plotH),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 9f)),
                    )
                    drawLine(Color(0x2EFFFFFF), Offset(padX, y), Offset(size.width - padX, y), 1.6f)
                    drawCircle(GlpColors.Bg, radius = 11f, center = Offset(x, y))
                    drawCircle(color, radius = 8f, center = Offset(x, y), style = Stroke(width = 4f))
                }

            }

            // x 轴时间刻度：首 / 中 / 末三个，用普通 Text 画在 Canvas 下方。
            //
            // 为什么不画在 Canvas 里：那需要 android.graphics.Paint + nativeCanvas
            // （后者还要额外依赖）。Compose 文本本来就在这一层，用它更省事，字号
            // 也和界面其余部分统一。左右各留 12px 与 Canvas 的 padX 对齐，所以
            // 首标签正好在曲线起点下方、末标签在终点下方。
            if (xLabels.size >= 2) {
                val ticks = listOf(
                    xLabels.first(),
                    xLabels[xLabels.size / 2],
                    xLabels.last(),
                ).distinct()
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    ticks.forEach { t ->
                        Text(t, color = GlpColors.Text3, fontSize = 10.sp)
                    }
                }
            }

            // 数值气泡。放在 Canvas 外面用 Compose 文本渲染，比在 Canvas 里画字清楚，
            // 也能用 onSizeChanged 拿到实测宽度做边界收拢——不要用猜的常数去夹：
            // 上一版就是拿像素常数去夹相对坐标，量纲不一致，气泡会被推出屏幕。
            if (bubbleVisible && selected in points.indices) {
                val p = points[selected]
                var bubbleWidthPx = 0
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .onSizeChanged { bubbleWidthPx = it.width }
                        .offset {
                            val target = p.x.coerceIn(0f, 1f) * containerWidthPx
                            val maxLeft = (containerWidthPx - bubbleWidthPx).coerceAtLeast(0f)
                            IntOffset(
                                (target - bubbleWidthPx / 2f).coerceIn(0f, maxLeft).roundToInt(),
                                0,
                            )
                        },
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .background(GlpColors.Elevated.copy(alpha = 0.98f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            p.value,
                            color = GlpColors.Text,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(p.label, color = GlpColors.Text2, fontSize = 10.5.sp)
                    }
                }
            }

            // 提示语和 x 轴刻度都想占底部，两者会叠在一起（真机截图里就是
            // "10月10日按住曲线拖动…10月20日"糊成一行）。有刻度时就不再显示提示语：
            // 刻度本身更有信息量，而"能拖动"这件事用户试一次就知道了。
            if (!bubbleVisible && xLabels.size < 2) {
                Text(
                    hint,
                    color = GlpColors.Text3,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = 2.dp),
                )
            }
        }
    }
}

/**
 * 横坐标 → 最近的点的下标。
 *
 * 只做一件事：把触摸位置换算成数据比例，再找最近点。
 * 没有窗口参数——因为整条序列永远全部可见。
 */
private fun nearestIndex(points: List<ChartPoint>, touchX: Float, width: Float): Int {
    if (points.isEmpty() || width <= 0f) return -1
    val padX = 12f
    val plotW = (width - padX * 2).coerceAtLeast(1f)
    val ratio = ((touchX - padX) / plotW).coerceIn(0f, 1f)
    var best = 0
    var bestDist = Float.MAX_VALUE
    points.forEachIndexed { i, p ->
        val d = abs(p.x.coerceIn(0f, 1f) - ratio)
        if (d < bestDist) {
            bestDist = d
            best = i
        }
    }
    return best
}
