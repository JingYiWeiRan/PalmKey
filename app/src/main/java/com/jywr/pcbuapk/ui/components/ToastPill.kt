package com.jywr.pcbuapk.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** 无操作按钮的提示自动收起时长。默认 Snackbar 是 4 秒，偏拖沓。 */
private const val AUTO_DISMISS_MS = 1600L

/**
 * 轻提示：屏幕中下方的一小块居中胶囊（替代默认的 Snackbar）。
 *
 * 设计取向（要求是「像系统 Toast 但更精致」）：
 * - 默认 Snackbar 是**通栏**的长条，这里改成宽度由内容决定、最长 320dp 的小胶囊；
 * - 全圆角 + 细描边 + 投影，底色用与卡片/弹窗同一套的中性色（surfaceContainerHighest），
 *   因此浅色/深色主题下都不会跳色，也不会出现刺眼的黑色底；
 * - **不带操作按钮的提示 1.6 秒后自动收起**；
 *   带按钮的（删除后的「撤销」）保持不自动收起，否则用户来不及点。
 *
 * ⚠️ 下面按**中文文案关键字**推断状态图标与颜色。这是一个**有意保留**的取舍：
 * 本应用只提供中文（见 res/values/strings.xml 顶部的决策记录），因此中文关键字
 * 判断在当前范围内是可靠的，不值得为它引入一个结果类型。
 *
 * 但请注意它的边界：一旦将来要做多语言，这段判断会**静默失效**（图标与配色不再匹配）。
 * 届时的正确做法是让上游返回结构化的结果类型（成功/失败/引导）而不是字符串，
 * 那需要把 service 层返回 String 错误消息的接口改成错误码 —— 属于独立的重构。
 */
@Composable
fun ToastPill(data: SnackbarData) {
    val message = data.visuals.message
    val actionLabel = data.visuals.actionLabel

    val isFailure = message.contains("失败") || message.contains("错误")
    val isGuidance = message.startsWith("请") || message.contains("无法")
    val icon = when {
        isFailure -> Icons.Default.Warning
        isGuidance -> Icons.Default.Info
        else -> Icons.Default.CheckCircle
    }
    val accent = when {
        isFailure -> MaterialTheme.colorScheme.error
        isGuidance -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }

    // 无操作按钮时才提前收起
    LaunchedEffect(data) {
        if (actionLabel == null) {
            delay(AUTO_DISMISS_MS)
            data.dismiss()
        }
    }

    Surface(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .widthIn(max = 320.dp),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(
                start = 16.dp,
                end = if (actionLabel == null) 18.dp else 6.dp,
                top = 10.dp,
                bottom = 10.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (actionLabel != null) {
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { data.performAction() }) {
                    Text(actionLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
