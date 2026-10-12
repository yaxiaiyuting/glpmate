package com.glpmate.native_app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import kotlin.math.abs

/**
 * 设计系统组件：与 WebView 版的视觉语言一一对应。
 *
 * 命名对着 SPA 里的 CSS 类（card / list-row / progress / chip），
 * 这样两边改动时可以互相参照，不会各自漂移成两套语言。
 */

/** 卡片：20dp 圆角 + 1px 描边，对应 CSS 的 .card */
@Composable
fun GlpCard(
    modifier: Modifier = Modifier,
    background: Color = GlpColors.Surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .border(1.dp, GlpColors.Line, RoundedCornerShape(20.dp))
            .padding(16.dp),
        content = content,
    )
}

/** 区块标题，对应 .section-head */
@Composable
fun SectionHead(title: String, trailing: String? = null, onTrailing: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = GlpColors.Text2, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(
                trailing,
                color = GlpColors.Accent,
                fontSize = 12.5.sp,
                modifier = if (onTrailing != null) Modifier.clickable { onTrailing() } else Modifier,
            )
        }
    }
}

/** 细进度条，对应 .thin-bar */
@Composable
fun ThinBar(value: Double, target: Double, color: Color, height: Int = 4) {
    val ratio = if (target > 0) (value / target).coerceIn(0.0, 1.0).toFloat() else 0f
    Box(
        Modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(height.dp / 2))
            .background(Color(0x14FFFFFF)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(ratio)
                .height(height.dp)
                .clip(RoundedCornerShape(height.dp / 2))
                .background(color),
        )
    }
}

/** 指标 + 进度条（餐食的 5 列宏量营养就是它的横排） */
@Composable
fun MacroStat(
    label: String,
    value: Double,
    target: Double,
    color: Color,
    unit: String = "",
    modifier: Modifier = Modifier,
) {
    // 宽度由调用方通过 weight 决定：五列并排时每列 1/5，不能各自 fillMaxWidth
    Column(modifier) {
        Text(label, color = GlpColors.Text3, fontSize = 11.sp)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                formatNum(value, if (value >= 100) 0 else 1),
                color = GlpColors.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "/" + formatNum(target, if (target >= 100) 0 else 1) + unit,
                color = GlpColors.Text3,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(5.dp))
        ThinBar(value, target, color)
    }
}

/** 列表行：图标 + 标题/副标题 + 右侧数值，对应 .list-row */
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    rightTop: String? = null,
    rightBottom: String? = null,
    accent: Color = GlpColors.Accent,
    iconText: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconText != null) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Text(iconText, fontSize = 16.sp) }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = GlpColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = GlpColors.Text2, fontSize = 12.sp, maxLines = 2)
            }
        }
        if (rightTop != null || rightBottom != null) {
            Column(horizontalAlignment = Alignment.End) {
                if (rightTop != null) {
                    Text(rightTop, color = GlpColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                if (rightBottom != null) {
                    Text(rightBottom, color = GlpColors.Text3, fontSize = 11.5.sp)
                }
            }
        }
    }
}

/** 胶囊按钮，对应 .pill-btn */
@Composable
fun PillButton(text: String, color: Color = GlpColors.Accent, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.5f), CircleShape)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** 主按钮，对应 .btn-primary */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) GlpColors.Accent else GlpColors.SurfaceAlt)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) Color(0xFF04222B) else GlpColors.Text3,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 可选项胶囊（感受 chips、时间范围切换都用它） */
@Composable
fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (selected) GlpColors.Accent.copy(alpha = 0.18f) else Color(0x0FFFFFFF))
            .border(
                1.dp,
                if (selected) GlpColors.Accent.copy(alpha = 0.6f) else GlpColors.Line,
                CircleShape,
            )
            .clickable { onClick() }
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        Text(
            text,
            color = if (selected) GlpColors.Accent else GlpColors.Text2,
            fontSize = 12.5.sp,
        )
    }
}

/** 说明性提示行，对应 .hint-line / .info-note */
@Composable
fun HintLine(text: String, icon: String = "ⓘ") {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(icon, color = GlpColors.Text3, fontSize = 12.sp)
        Spacer(Modifier.width(6.dp))
        Text(text, color = GlpColors.Text3, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

/** 空状态 */
@Composable
fun EmptyState(title: String, hint: String? = null) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = GlpColors.Text2, fontSize = 14.sp)
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(hint, color = GlpColors.Text3, fontSize = 12.sp)
        }
    }
}

/** 错误状态：把后端返回的中文原因原样显示，不吞掉 */
@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("出错了", color = GlpColors.Food, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(message, color = GlpColors.Text2, fontSize = 12.5.sp, lineHeight = 18.sp)
        if (onRetry != null) {
            Spacer(Modifier.height(10.dp))
            PillButton("重试", GlpColors.Food) { onRetry() }
        }
    }
}

// ---------------------------------------------------------------- 格式化
/**
 * 数字格式化：整数不带小数点，小数保留指定精度。
 * 单独抽出来是为了让"100.0 显示成 100"这种行为在到处都一致。
 */
fun formatNum(value: Double, decimals: Int = 1): String {
    if (value.isNaN() || value.isInfinite()) return "—"
    return if (abs(value - value.toLong()) < 0.0001) {
        value.toLong().toString()
    } else {
        String.format(java.util.Locale.US, "%.${decimals}f", value)
    }
}

/** "2026-10-12T11:45:00" → "10月12日" */
fun shortDate(iso: String): String {
    if (iso.length < 10) return iso
    val parts = iso.substring(0, 10).split("-")
    if (parts.size != 3) return iso
    return "${parts[1].trimStart('0')}月${parts[2].trimStart('0')}日"
}

/** 星期名（0=周一） */
fun weekdayCn(index: Int): String =
    listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
        .getOrElse(index) { "星期一" }
