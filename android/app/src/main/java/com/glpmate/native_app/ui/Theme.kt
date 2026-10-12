package com.glpmate.native_app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计系统：与 WebView 版保持同一套视觉语言。
 *
 * 色值直接对齐 SPA 里的令牌（app.css 的 :root），这样两个前端看起来是同一个应用，
 * 以后要切换或对比也不会"像两个软件"。
 */
object GlpColors {
    val Bg = Color(0xFF0B0F12)          // 页面底色（近黑，微蓝）
    val Surface = Color(0xFF151A1E)     // 卡片
    val SurfaceAlt = Color(0xFF1A2026)  // 次级卡片
    val Elevated = Color(0xFF202830)    // 浮起元素（气泡/弹层）
    val Line = Color(0x0FFFFFFF)        // 分隔线：白 6%

    val Text = Color(0xFFFFFFFF)
    val Text2 = Color(0xFF9AA5AD)
    val Text3 = Color(0xFF6B757D)

    val Accent = Color(0xFF35C6E8)      // 主色（按钮/激活态）
    val Dose = Color(0xFFE8A33D)        // 剂量 / 琥珀
    val Weight = Color(0xFF3DBFA0)      // 体重 / 训练
    val Food = Color(0xFFE8607A)        // 餐食 / 副作用
    val Water = Color(0xFF4A9BE8)       // 饮水
    val Sleep = Color(0xFF8B7BD8)       // 睡眠
}

private val GlpDarkScheme = darkColorScheme(
    primary = GlpColors.Accent,
    onPrimary = Color(0xFF04222B),
    secondary = GlpColors.Weight,
    background = GlpColors.Bg,
    onBackground = GlpColors.Text,
    surface = GlpColors.Surface,
    onSurface = GlpColors.Text,
    surfaceVariant = GlpColors.SurfaceAlt,
    onSurfaceVariant = GlpColors.Text2,
    outline = Color(0x1FFFFFFF),
    error = GlpColors.Food,
)

private val GlpShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),      // 卡片统一 20dp 圆角
    extraLarge = RoundedCornerShape(28.dp),
)

private val GlpTypography = Typography(
    displaySmall = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 13.5.sp),
    labelSmall = TextStyle(fontSize = 11.5.sp, color = GlpColors.Text3),
)

@Composable
fun GlpMateTheme(content: @Composable () -> Unit) {
    // 这个应用只有深色一种形态：它在暗光环境下用（晚上看用药记录），
    // 强行跟随系统日间模式会让图表配色全部失效
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = GlpDarkScheme,
        shapes = GlpShapes,
        typography = GlpTypography,
        content = content,
    )
}
