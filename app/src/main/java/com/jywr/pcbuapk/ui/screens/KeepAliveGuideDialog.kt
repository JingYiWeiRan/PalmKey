package com.jywr.pcbuapk.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jywr.pcbuapk.utils.KeepAliveManager
import kotlinx.coroutines.delay

/**
 * 保活与权限引导对话框
 *
 * 两个目标：
 * 1. 让服务在国产 ROM 上活下来（自启动 / 电池白名单）
 * 2. 让验证界面能在**不点通知**的前提下弹出来（悬浮窗 / 后台弹出界面）
 *
 * 第 2 点是用户最直接的体感：Android 10 起后台应用不能随便启动界面，
 * 官方豁免条件之一是 SYSTEM_ALERT_WINDOW（悬浮窗）权限；
 * 拿不到它时，亮屏停在桌面只能收到一条需要手点的横幅。
 *
 * 版式：每一项做成整行可点的卡片（而不是「文字 + 右侧按钮」两段式），
 * 点哪里都能跳系统设置；左侧状态图标 + 颜色直接表达「开了没」。
 */
@Composable
fun KeepAliveGuideDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    // 权限状态在网络/设置页来回切换后会变化，这里轮询一下保证显示的是最新状态。
    // 轮询随组件销毁自动停止，开销可以忽略。
    var overlayGranted by remember { mutableStateOf(KeepAliveManager.canDrawOverlays(context)) }
    var fullScreenGranted by remember { mutableStateOf(KeepAliveManager.canUseFullScreenIntent(context)) }
    var batteryGranted by remember {
        mutableStateOf(KeepAliveManager.isIgnoringBatteryOptimizations(context))
    }
    LaunchedEffect(Unit) {
        while (true) {
            overlayGranted = KeepAliveManager.canDrawOverlays(context)
            fullScreenGranted = KeepAliveManager.canUseFullScreenIntent(context)
            batteryGranted = KeepAliveManager.isIgnoringBatteryOptimizations(context)
            delay(1500L)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = "让解锁提醒能直接弹出",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "手机需要在后台常驻，才能在电脑请求解锁时直接弹出指纹验证。以下几点按重要性排序，点击即可前往系统设置：",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))

                GuideItem(
                    title = "悬浮窗权限（最关键）",
                    description = if (overlayGranted) {
                        "已开启：桌面状态下也能直接弹出指纹，无需点击"
                    } else {
                        "未开启：桌面状态只会收到通知，必须手点一次才能验证"
                    },
                    granted = overlayGranted
                ) {
                    KeepAliveManager.openOverlaySettings(context)
                }

                GuideItem(
                    title = "后台弹出界面 / 锁屏显示",
                    description = "国产 ROM 的独立开关，不开则桌面和锁屏下弹不出界面",
                    granted = null
                ) {
                    KeepAliveManager.openBackgroundPopupSettings(context)
                }

                GuideItem(
                    title = "自启动 / 后台运行",
                    description = "开机或被清理后台后能自动恢复服务",
                    granted = null
                ) {
                    KeepAliveManager.openAutoStartSettings(context)
                }

                GuideItem(
                    title = "电池优化白名单",
                    description = if (batteryGranted) {
                        "已加入白名单，休眠后服务不会被回收"
                    } else {
                        "未加入：系统休眠后可能回收服务，导致收不到解锁请求"
                    },
                    granted = batteryGranted
                ) {
                    KeepAliveManager.requestIgnoreBatteryOptimizations(context)
                }

                GuideItem(
                    title = "全屏通知",
                    description = if (fullScreenGranted) {
                        "已允许，锁屏/熄屏下可直接全屏弹出"
                    } else {
                        "未允许：Android 14+ 需要手动开启，否则锁屏下只能收到横幅"
                    },
                    granted = fullScreenGranted
                ) {
                    KeepAliveManager.openFullScreenIntentSettings(context)
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("知道了")
            }
        }
    )
}

/**
 * 引导项：整行可点的卡片
 *
 * @param granted true=已开启（对勾 + 辅色），false=未开启（感叹号 + 错误色），null=无法直接检测
 */
@Composable
private fun GuideItem(
    title: String,
    description: String,
    granted: Boolean?,
    onClick: () -> Unit
) {
    val statusColor: Color = when (granted) {
        true -> MaterialTheme.colorScheme.secondary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusIcon = when (granted) {
        true -> Icons.Default.CheckCircle
        false -> Icons.Default.Warning
        null -> Icons.Default.Info
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = statusIcon,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (granted == false) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp)
            )
        }
    }

    Spacer(Modifier.height(8.dp))
}
