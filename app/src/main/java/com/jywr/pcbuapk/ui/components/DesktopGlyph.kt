package com.jywr.pcbuapk.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 「显示器」图标（自绘）。
 *
 * Material 图标的基础集合里没有电脑/设备类图标，而为这一个图标去引入
 * material-icons-extended 会让包体积明显变大；Canvas 画十几行即可，
 * 颜色还能自动跟随主题。
 *
 * 被主页空状态与设备卡片共用，因此从 MainScreen 抽出为独立组件。
 */
@Composable
fun DesktopGlyph(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.6.dp.toPx()

        val screenW = w * 0.86f
        val screenH = h * 0.56f
        val left = (w - screenW) / 2f
        val top = h * 0.12f

        // 屏幕外框
        drawRoundRect(
            color = tint,
            topLeft = Offset(left, top),
            size = Size(screenW, screenH),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            style = Stroke(width = stroke)
        )
        // 支架
        drawLine(
            color = tint,
            start = Offset(w / 2f, top + screenH),
            end = Offset(w / 2f, h * 0.82f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        // 底座
        drawLine(
            color = tint,
            start = Offset(w * 0.32f, h * 0.86f),
            end = Offset(w * 0.68f, h * 0.86f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}
