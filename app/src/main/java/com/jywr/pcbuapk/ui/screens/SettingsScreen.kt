package com.jywr.pcbuapk.ui.screens

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import com.jywr.pcbuapk.service.KeepAlivePolicy
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
/** 「不保活」模式的取值（见 [KeepAlivePolicy]）。本文件多处依赖它做分支与配色。 */
private val NO_KEEP_ALIVE_MODE = KeepAlivePolicy.MODE_NO_KEEP_ALIVE

/**
 * 读取本应用的版本名，用于「关于」区显示。
 *
 * 不写死字面量：原先那里是 `"版本 1.0.0"`，一发新版本界面就说谎。
 * 也不用 `BuildConfig.VERSION_NAME`：本工程未开启 buildConfig 生成（AGP 8 默认关闭）。
 *
 * 读不到时返回「未知」而不是猜一个值 —— 猜出来的版本号比没有更糟，
 * 它会让人以为"装的是旧版"，从而在错误的方向上排查。
 */
private fun appVersionName(context: Context): String =
    runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "未知"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    // 精确闹钟本来就是可查询的（canScheduleExactAlarms），之前却硬编码成 null，
    // 于是无论用户开没开都显示"去设置" —— 属于实打实的显示错误，这里接上真实状态。
    var exactAlarmGranted by remember {
        mutableStateOf(KeepAliveManager.canScheduleExactAlarms(context))
    }

    // 「后台弹出界面 / 锁屏显示」「自启动 / 后台运行」是国产 ROM 私有开关，
    // **没有公开 API 可查**，所以只能由用户手动确认并持久化。
    var backgroundPopupConfirmed by remember {
        mutableStateOf(KeepAliveManager.isRomPermissionConfirmed(context, KeepAliveManager.ROM_PERM_BACKGROUND_POPUP))
    }
    var autoStartConfirmed by remember {
        mutableStateOf(KeepAliveManager.isRomPermissionConfirmed(context, KeepAliveManager.ROM_PERM_AUTOSTART))
    }

    LaunchedEffect(Unit) {
        while (true) {
            overlayGranted = KeepAliveManager.canDrawOverlays(context)
            fullScreenGranted = KeepAliveManager.canUseFullScreenIntent(context)
            batteryGranted = KeepAliveManager.isIgnoringBatteryOptimizations(context)
            exactAlarmGranted = KeepAliveManager.canScheduleExactAlarms(context)
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
                        // 四种模式的语义见 KeepAlivePolicy：前三者只影响「多久自检一次」，
                        // 「不保活」关掉一切自动复活。
                        //
                        // 旧文案写「省电模式…息屏可能收不到请求」——那是当时的真实行为
                        // （省电模式会放弃常驻唤醒锁），但也正是「选了省电模式就再也解不开
                        // 电脑」这个坑的来源。唤醒锁现在无条件持有，所以不再与模式联动。
                        text = when (preferences.keepAliveMode) {
                            0 -> "省电模式：30 分钟自检一次（最省电，被系统回收后恢复最慢）"
                            2 -> "可靠模式：5 分钟自检一次（最不容易被系统回收）"
                            3 -> "不保活：划掉最近任务即彻底停止；清掉后台后不会自动恢复，" +
                                    "需要手动打开本应用才能再次使用"
                            else -> "平衡模式：15 分钟自检一次（推荐）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (preferences.keepAliveMode == NO_KEEP_ALIVE_MODE) {
                            // 这是一项行为差异很大的设置，用主色让它更容易被看见
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    // 这句只对"保活模式"成立；不保活模式下 App 被清掉就不在了，
                    // 与那项权限无关，所以那种情况不显示，免得误导。
                    if (preferences.keepAliveMode != NO_KEEP_ALIVE_MODE) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "以上仅影响自检频率；息屏能否收到解锁请求由下面的「自启动 / 后台运行」决定。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    // 用 FlowRow 而不是 Row：第 4 个 chip 加进来后，
                    // 四枚 chip 在窄屏上会正好顶到可用宽度上限（本屏可用宽度约 screenWidth-64dp），
                    // 用 Row 会挤压或裁切；FlowRow 会自动换行。
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(0 to "省电", 1 to "平衡", 2 to "可靠", 3 to "不保活")
                            .forEach { (mode, label) ->
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
                    granted = null,
                    manualConfirmed = backgroundPopupConfirmed,
                    onManualConfirmChange = { confirmed ->
                        backgroundPopupConfirmed = confirmed
                        KeepAliveManager.setRomPermissionConfirmed(
                            context, KeepAliveManager.ROM_PERM_BACKGROUND_POPUP, confirmed
                        )
                    }
                ) { KeepAliveManager.openBackgroundPopupSettings(context) }

                RowDivider()

                PermissionRow(
                    // 这一项**直接决定息屏解锁能不能用**：实测（vivo / Android 15）
                    // 屏幕一熄系统就把本应用放进 freezer（cgroup.freeze=1），
                    // 并强制禁用它的唤醒锁，于是 UDP 报文只能堆在内核队列里、
                    // 直到点亮屏幕才被处理。放行「自启动 / 后台运行」才会解冻。
                    // 原先的描述只说「自动恢复服务」，完全没提示这个后果。
                    title = "自启动 / 后台运行",
                    // 注意：Compose 的 Text **不解析 Markdown**，这里不能写 **粗体**，
                    // 否则界面上会原样显示出星号（真机上已经出现过这个问题）。
                    // 需要强调就靠措辞，而不是标记。
                    description = "决定息屏能否解锁：未放行时系统会冻结本应用，息屏后收不到解锁请求",
                    granted = null,
                    manualConfirmed = autoStartConfirmed,
                    onManualConfirmChange = { confirmed ->
                        autoStartConfirmed = confirmed
                        KeepAliveManager.setRomPermissionConfirmed(
                            context, KeepAliveManager.ROM_PERM_AUTOSTART, confirmed
                        )
                    }
                ) { KeepAliveManager.openAutoStartSettings(context) }

                RowDivider()

                PermissionRow(
                    title = "电池优化白名单",
                    description = if (batteryGranted) "已在白名单中" else "防止休眠后服务被回收",
                    granted = batteryGranted
                ) { KeepAliveManager.openBatteryOptimizationSettings(context) }

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
                    description = if (exactAlarmGranted) {
                        "已授权，保活心跳使用精确闹钟"
                    } else {
                        "未授权，保活心跳会自动降级为非精确，精度下降"
                    },
                    granted = exactAlarmGranted
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
                        // 从 PackageManager 读，不要写死：
                        // 原先这里是字面量 "版本 1.0.0"，一发新版本界面就会说谎，
                        // 而"界面显示 1.0.0、装的其实是 1.0.1"这种不一致会让
                        // 排查版本相关问题时直接走错方向。
                        // 用 PackageManager 而不是 BuildConfig.VERSION_NAME：本工程没开
                        // buildConfig 生成（AGP 8 默认关闭），这样也不引入额外的构建配置。
                        text = "版本 " + appVersionName(context),
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
 * @param manualConfirmed 仅用于**系统不提供查询接口**的 ROM 权限（如 vivo 的自启动）：
 *   非空表示交给用户手动确认，值为当前是否已确认。
 *   不这样做的话，那一行会永远显示成「需要开启」—— 用户明明开了也会被反复要求去开。
 * @param onManualConfirmChange 用户点「标记为已开启 / 取消标记」时的回调
 */
@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean?,
    manualConfirmed: Boolean? = null,
    onManualConfirmChange: ((Boolean) -> Unit)? = null,
    // onClick 必须留在最后：调用处用的是尾随 lambda 写法 `PermissionRow(...) { ... }`，
    // 一旦它后面还有参数，lambda 就会绑到别的参数上（编译直接报 No value passed for onClick）。
    onClick: () -> Unit
) {
    // 手动确认过的无法检测项，按"已开启"呈现：用户已经明确告诉我们他开好了
    val effectiveGranted: Boolean? = when {
        granted != null -> granted
        manualConfirmed == true -> true
        else -> null
    }

    val statusColor: Color = when (effectiveGranted) {
        true -> MaterialTheme.colorScheme.secondary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusIcon = when (effectiveGranted) {
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
                color = if (effectiveGranted == false) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            // 无法查询的权限：给出「手动确认」入口，让界面能反映真实情况
            if (manualConfirmed != null && onManualConfirmChange != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (manualConfirmed) {
                        "系统不提供查询接口 · 已标记为开启（点此取消）"
                    } else {
                        "系统不提供查询接口 · 开好后点此标记为已开启"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        onManualConfirmChange(!manualConfirmed)
                    }
                )
            }
        }
        TextButton(onClick = onClick) {
            Text(
                when (effectiveGranted) {
                    true -> if (granted == null) "已确认" else "已开启"
                    false -> "去开启"
                    // null = 无法检测：这里只能表示"去设置页看一下"这个动作，
                    // 不能写"去开启"—— 那会被读成"当前没开"。真机上就发生过这种误判。
                    null -> "去设置"
                }
            )
        }
    }
}
