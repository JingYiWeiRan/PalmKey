package com.jywr.pcbuapk.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 应用配色（Material 3）。
 *
 * 这里刻意把「浅色 / 深色」两套色板都补全，原因：
 * 原先只填了 primary / secondary / tertiary / surface / background 五个槽位，
 * 其余全部落到 M3 默认色板 —— 那套默认色是**紫色基准**，跟这里的红色主调放在一起就显脏：
 * 例如 FilterChip 选中态、卡片描边、次级文字都会偏紫灰，整体看着「不像一个设计过的 App」。
 *
 * 识别色：主色＝红（与 PC 端一致），辅色＝青绿（"已连接/已开启"这类正向状态），
 * 第三色＝琥珀（最后连接时间等次要强调）。
 */

/** 浅色板：白天使用，表面用极浅的灰白，避免纯白刺眼 */
private val LightColors = lightColorScheme(
    primary = Color(0xFFB3261E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),

    secondary = Color(0xFF00695C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB2DFDB),
    onSecondaryContainer = Color(0xFF00201C),

    tertiary = Color(0xFF8C5000),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDB6),
    onTertiaryContainer = Color(0xFF2D1600),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE8EBEE),
    onSurfaceVariant = Color(0xFF44474B),

    // surfaceContainer 系列：M3 的 AlertDialog / DropdownMenu / Snackbar 等组件的默认底色都取自这里。
    // 不定义的话它们会落到 M3 默认色板（紫色基准），弹窗/菜单就会「偏紫、跟主题不搭」。
    // 数值是「surface 白 + 逐级加深的中性灰」，只做明度分层，不带任何色相倾向。
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F4F7),
    surfaceContainer = Color(0xFFECEEF2),
    surfaceContainerHigh = Color(0xFFE6E9ED),
    surfaceContainerHighest = Color(0xFFE0E4E8),

    outline = Color(0xFF9AA0A6),
    outlineVariant = Color(0xFFD6DBE0),

    inverseSurface = Color(0xFF2F3235),
    inverseOnSurface = Color(0xFFF0F1F3),
    inversePrimary = Color(0xFFFFB4AB),
    scrim = Color(0xFF000000)
)

/**
 * 深色板：夜间使用。
 *
 * 底色不用纯黑（#000）：纯黑下卡片的层次感会完全消失、也更容易在 OLED 上出现拖影；
 * 改用带一点蓝调的深灰，卡片/弹窗才能靠明度差分层。
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF8A80),
    onPrimary = Color(0xFF5C0000),
    primaryContainer = Color(0xFF8C1D18),
    onPrimaryContainer = Color(0xFFFFDAD6),

    secondary = Color(0xFF4DB6AC),
    onSecondary = Color(0xFF00332D),
    secondaryContainer = Color(0xFF00504A),
    onSecondaryContainer = Color(0xFFB2DFDB),

    tertiary = Color(0xFFFFB74D),
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF6B3B00),
    onTertiaryContainer = Color(0xFFFFDDB6),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF101214),
    onBackground = Color(0xFFE3E5E8),
    surface = Color(0xFF16191C),
    onSurface = Color(0xFFE3E5E8),
    surfaceVariant = Color(0xFF2B3035),
    onSurfaceVariant = Color(0xFFBFC5CC),

    // 同浅色板：AlertDialog / DropdownMenu / Snackbar 的默认底色从这里取，
    // 必须显式给出，否则会落到 M3 默认（紫色基准）的 surfaceContainer 上。
    surfaceContainerLowest = Color(0xFF0C0E10),
    surfaceContainerLow = Color(0xFF17191C),
    surfaceContainer = Color(0xFF1B1E21),
    surfaceContainerHigh = Color(0xFF262A2E),
    surfaceContainerHighest = Color(0xFF31363B),

    outline = Color(0xFF8A9199),
    outlineVariant = Color(0xFF3A4046),

    inverseSurface = Color(0xFFE3E5E8),
    inverseOnSurface = Color(0xFF2B3035),
    inversePrimary = Color(0xFFB3261E),
    scrim = Color(0xFF000000)
)

/**
 * 应用主题。
 *
 * 默认跟随系统深浅色，因此 XML 里的 `Theme.Material3.DayNight`（状态栏/窗口背景）
 * 与 Compose 内容始终保持一致，不会出现「浅色系统 + 深色界面」的割裂。
 */
@Composable
fun PcbuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
