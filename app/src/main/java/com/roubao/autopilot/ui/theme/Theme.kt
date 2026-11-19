package com.roubao.autopilot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// 主色调（DeepSeek 蓝）
val Primary = Color(0xFF4D6BFE)
val PrimaryDark = Color(0xFF3A56D4)
val PrimaryLight = Color(0xFF8CA3FF)
val Secondary = Color(0xFFC7D3FE)
val SecondaryDark = Color(0xFF8CA3FF)
val SecondaryLight = Color(0xFFE4EAFF)

// 深色主题背景色
val BackgroundDark = Color(0xFF0F0F0F)
val BackgroundCard = Color(0xFF292929)
val BackgroundInput = Color(0xFF242424)
val SurfaceVariant = Color(0xFF363636)

// 深色主题文字颜色
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFB0B0B0)
val TextHint = Color(0xFF757575)

// 浅色主题背景色 - 纯白简洁风格（DeepSeek 式）
val BackgroundLight = Color(0xFFFFFFFF)
val BackgroundCardLight = Color(0xFFF7F7F8)
val BackgroundInputLight = Color(0xFFF4F4F5)
val SurfaceVariantLight = Color(0xFFEBEBEF)

// 浅色主题文字颜色
val TextPrimaryLight = Color(0xFF212121)
val TextSecondaryLight = Color(0xFF757575)
val TextHintLight = Color(0xFFBDBDBD)

// 状态颜色
val Success = Color(0xFF4CAF50)
val Error = Color(0xFFF44336)
val Warning = Color(0xFFFF9800)

// 主题颜色数据类
data class BaoziColors(
    val primary: Color,
    val primaryDark: Color,
    val primaryLight: Color,
    val secondary: Color,
    val background: Color,
    val backgroundCard: Color,
    val backgroundInput: Color,
    val surfaceVariant: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textHint: Color,
    val success: Color,
    val error: Color,
    val warning: Color,
    val isDark: Boolean
)

// 深色主题颜色
val DarkBaoziColors = BaoziColors(
    primary = Primary,
    primaryDark = PrimaryDark,
    primaryLight = PrimaryLight,
    secondary = Secondary,
    background = BackgroundDark,
    backgroundCard = BackgroundCard,
    backgroundInput = BackgroundInput,
    surfaceVariant = SurfaceVariant,
    textPrimary = TextPrimary,
    textSecondary = TextSecondary,
    textHint = TextHint,
    success = Success,
    error = Error,
    warning = Warning,
    isDark = true
)

// 浅色主题颜色
val LightBaoziColors = BaoziColors(
    primary = Primary,
    primaryDark = PrimaryDark,
    primaryLight = PrimaryLight,
    secondary = Secondary,
    background = BackgroundLight,
    backgroundCard = BackgroundCardLight,
    backgroundInput = BackgroundInputLight,
    surfaceVariant = SurfaceVariantLight,
    textPrimary = TextPrimaryLight,
    textSecondary = TextSecondaryLight,
    textHint = TextHintLight,
    success = Success,
    error = Error,
    warning = Warning,
    isDark = false
)

// CompositionLocal 用于访问当前主题颜色
val LocalBaoziColors = staticCompositionLocalOf { DarkBaoziColors }

// Material 3 深色配色方案
private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = PrimaryDark,
    onPrimaryContainer = Color.White,
    secondary = Secondary,
    onSecondary = Color.Black,
    secondaryContainer = SecondaryDark,
    onSecondaryContainer = Color.White,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = BackgroundCard,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = Error,
    onError = Color.White
)

// Material 3 浅色配色方案
private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = PrimaryLight,
    onPrimaryContainer = Color.Black,
    secondary = Secondary,
    onSecondary = Color.Black,
    secondaryContainer = SecondaryLight,
    onSecondaryContainer = Color.Black,
    background = BackgroundLight,
    onBackground = TextPrimaryLight,
    surface = BackgroundCardLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = TextSecondaryLight,
    error = Error,
    onError = Color.White
)

// 主题模式枚举
enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM
}

@Composable
fun BaoziTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val isDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = if (isDarkTheme) DarkColorScheme else LightColorScheme
    val baoziColors = if (isDarkTheme) DarkBaoziColors else LightBaoziColors

    CompositionLocalProvider(LocalBaoziColors provides baoziColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(),
            content = content
        )
    }
}

// 便捷访问当前主题颜色
object BaoziTheme {
    val colors: BaoziColors
        @Composable
        get() = LocalBaoziColors.current
}
