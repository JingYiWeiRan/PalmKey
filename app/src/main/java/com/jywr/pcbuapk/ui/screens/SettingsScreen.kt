package com.jywr.pcbuapk.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.jywr.pcbuapk.ui.viewmodel.SettingsViewModel
import com.jywr.pcbuapk.utils.KeepAliveManager
import kotlinx.coroutines.delay

/**
 * 设置界面。
 *
 * 版式说明：
 * - 内容按「常规 / 权限与保活 / 关于」分成三张卡片，每组上方有一条主色小标题，
 *   比原来一整列平铺更容易扫读（原来 11 个条目连成一片，找不到分界）。
 * - 卡片用 Surface + 细描边手搓，而不是 Card：Card 的默认容器色在不同 Material3
 *   版本间变过（surfaceVariant → surfaceContainerLow），显式指定才不会随版本漂移。
 * - 列表项之间用 HorizontalDivider 的分隔线，且线色用 outlineVariant，比默认更淡，
 *   不至于把卡片切碎。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val preferences by viewModel.preferences.collectAsState()
    val context = LocalContext.current

    // 这几项都需要跳到系统设置页去开，用户返回后状态可能已变，轮询保证显示的是最新值
    var overlayGranted by remember { mutableStateOf(KeepAliveManager.canDrawOverlays(context)) }
    var fullScreenGranted by remember {
        mutableStateOf(KeepAliveManager.canUseFullScreenIntent(context))
    }
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                // padding 放在滚动之前：先让出顶部栏高度，再在剩余区域里滚动
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            // ---------------------------------------------------------- 常规
            SectionLabel("常规")
            SettingsCard {
                SwitchRow(
                    title = "通知栏快捷验证",
                    description = "关闭后点击通知将进入App再验证",
                    checked = !preferences.notificationModeA,
                    onCheckedChange = { viewModel.setNotificationMode(!it) }
                )

                RowDivider()

                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = "后台服务保活模式",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = when (preferences.keepAliveMode) {
                            0 -> "省电模式：30 分钟检查一次，息屏可能收不到请求"
                            2 -> "可靠模式：5 分钟检查一次，响应最及时"
                            else -> "平衡模式：15 分钟检查一次（推荐）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "省电", 1 to "平衡", 2 to "可靠").forEach { (mode, label) ->
                            FilterChip(
                                selected = preferences.keepAliveMode == mode,
                                onClick = { viewModel.setKeepAliveMode(mode) },
                                label = { Text(label) }
                            )
                        }
                    }
                }

                RowDivider()

                UdpPortRow(
                    currentPort = preferences.udpListenPort,
                    onSave = { viewModel.setUdpPort(it) }
                )
            }

            Spacer(Modifier.height(20.dp))

            // ---------------------------------------------------------- 权限与保活
            SectionLabel("权限与保活")
            Text(
                text = "以下几项是国产 ROM 上「免点击弹出指纹」和「服务常驻」的前提，未开启时功能会明显退化。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
            SettingsCard {
                PermissionRow(
                    title = "悬浮窗权限",
                    description = if (overlayGranted) {
                        "已开启，桌面状态下可直接弹出指纹"
                    } else {
                        "未开启，桌面状态只能弹通知，需要手点一次"
                    },
                    granted = overlayGranted
                ) { KeepAliveManager.openOverlaySettings(context) }

                RowDivider()

                PermissionRow(
                    title = "后台弹出界面 / 锁屏显示",
                    description = "国产 ROM 的独立开关，不开则桌面和锁屏下弹不出界面",
                    granted = null
                ) { KeepAliveManager.openBackgroundPopupSettings(context) }

                RowDivider()

                PermissionRow(
                    title = "自启动 / 后台运行",
                    description = "开机或被清理后台后能自动恢复服务",
                    granted = null
                ) { KeepAliveManager.openAutoStartSettings(context) }

                RowDivider()

                PermissionRow(
                    title = "电池优化白名单",
                    description = if (batteryGranted) "已在白名单中" else "防止休眠后服务被回收",
                    granted = batteryGranted
                ) { KeepAliveManager.requestIgnoreBatteryOptimizations(context) }

                RowDivider()

                PermissionRow(
                    title = "全屏通知权限",
                    description = if (fullScreenGranted) {
                        "已允许，锁屏/熄屏下可直接全屏弹出"
                    } else {
                        "Android 14+ 需手动允许，否则锁屏下只有横幅"
                    },
                    granted = fullScreenGranted
                ) { KeepAliveManager.openFullScreenIntentSettings(context) }

                RowDivider()

                PermissionRow(
                    title = "精确闹钟",
                    description = "保活心跳使用；未授权时会自动降级，精度下降",
                    granted = null
                ) { KeepAliveManager.openExactAlarmSettings(context) }
            }

            Spacer(Modifier.height(20.dp))

            // ---------------------------------------------------------- 关于
            SectionLabel("关于")
            SettingsCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("掌钥 · PalmKey", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "版本 1.0.0",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// --------------------------------------------------------------------- 版式组件

/** 分组小标题：用主色 + labelLarge，充当卡片之间的视觉分界 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

/** 带描边的圆角卡片容器 */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(content = content)
    }
}

/** 卡片内分隔线，两端留白，避免把卡片切得太碎 */
@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/** 带开关的设置行 */
@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * UDP 监听端口。
 *
 * 这里用本地 state 暂存输入、点右侧对勾才写回：
 * 旧实现把输入框直接绑在 DataStore 的值上，输入过程中任何一次「非法中间态」
 * （清空、只留半截数字）都会被丢弃并回滚，导致想改端口时**根本删不掉旧数字**。
 */
@Composable
private fun UdpPortRow(
    currentPort: Int,
    onSave: (Int) -> Unit
) {
    var portText by remember(currentPort) { mutableStateOf(currentPort.toString()) }
    val pending = portText.toIntOrNull()?.takeIf { it != currentPort && it in 1..65535 }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "UDP 监听端口", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                text = "当前端口：$currentPort",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedTextField(
            value = portText,
            // 只允许数字，最长 5 位（65535）
            onValueChange = { input ->
                if (input.length <= 5 && input.all { it.isDigit() }) portText = input
            },
            modifier = Modifier.width(132.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            label = { Text("端口") },
            trailingIcon = {
                IconButton(
                    onClick = { pending?.let(onSave) },
                    enabled = pending != null
                ) {
                    Icon(
                        imageVector = Icons.Default.Done,
                        contentDescription = "保存端口",
                        tint = if (pending != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        }
                    )
                }
            }
        )
    }
}

/**
 * 需要跳系统设置页去开启的权限项。
 *
 * @param granted true=已开启（对勾，主色），false=未开启（感叹号，错误色），null=无法直接检测
 */
@Composable
private fun PermissionRow(
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = statusIcon,
            contentDescription = null,
            tint = statusColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
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
        TextButton(onClick = onClick) {
            Text(if (granted == true) "已开启" else "去开启")
        }
    }
}
