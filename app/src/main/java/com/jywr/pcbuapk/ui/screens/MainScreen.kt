package com.jywr.pcbuapk.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.jywr.pcbuapk.data.entity.PairedDeviceEntity
import com.jywr.pcbuapk.ui.viewmodel.MainViewModel
import com.jywr.pcbuapk.utils.BiometricUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import android.util.Log

private const val TAG = "MainScreen"

/**
 * 主界面 - 设备列表
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToPairing: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val devices by viewModel.devices.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    
    // 生物识别触发：每次请求都把序号 +1，用它当 LaunchedEffect 的 key。
    // 不用布尔变量是因为「同一台电脑连续请求两次」时 state 没变，弹窗不会重新出现。
    var biometricSeq by remember { mutableStateOf(0) }
    var biometricDeviceId by remember { mutableStateOf<String?>(null) }

    // 用户刚在系统锁屏上完成了解锁时，这一次解锁就是身份证明，跳过二次指纹
    var biometricSkip by remember { mutableStateOf(false) }
    
    // 编辑蓝牙地址状态
    var showEditBtDialog by remember { mutableStateOf(false) }
    var editingDevice by remember { mutableStateOf<PairedDeviceEntity?>(null) }

    // 待确认删除的设备。
    // 删除不可逆（要重新配对才能再用），而删除图标与「解锁」按钮只隔 8dp，误触概率高，
    // 所以不直接删，先记下来交给确认弹窗。
    var deleteTarget by remember { mutableStateOf<PairedDeviceEntity?>(null) }
    
    // 保活引导：首次启动展示一次
    var showKeepAliveGuide by remember {
        mutableStateOf(!com.jywr.pcbuapk.utils.KeepAliveManager.hasShownGuide(context))
    }
    
    // 处理解锁请求（来自通知点击或服务直接拉起界面）
    // 用 StateFlow 驱动：Activity 已在前台时只会回调 onNewIntent，
    // 普通静态变量不会触发重组，验证弹窗就不会出现
    val unlockRequest by com.jywr.pcbuapk.MainActivity.pendingUnlock.collectAsState()
    LaunchedEffect(unlockRequest) {
        val request = unlockRequest ?: return@LaunchedEffect
        Log.i(TAG, "处理解锁请求: ${request.deviceName} (${request.deviceId}) mode=${request.mode}")
        com.jywr.pcbuapk.MainActivity.consumeUnlock()

        if (request.mode == com.jywr.pcbuapk.service.UnlockListenerService.MODE_WAIT_KEYGUARD) {
            // 手机还锁着：不要在这里弹指纹（此时 keyguard 未解除，BiometricPrompt 必然失败）。
            // 只需提示用户去锁屏解锁；用户解锁成功后，服务会再投递一条
            // MODE_SKIP_BIOMETRIC，那时才真正执行解锁。
            snackbarHostState.showSnackbar("请在系统锁屏上解锁以继续")
            return@LaunchedEffect
        }

        biometricDeviceId = request.deviceId
        biometricSkip = request.mode == com.jywr.pcbuapk.service.UnlockListenerService.MODE_SKIP_BIOMETRIC
        biometricSeq++
    }

    // 指纹验证 + 解锁上报
    LaunchedEffect(biometricSeq) {
        if (biometricSeq == 0) return@LaunchedEffect
        val deviceId = biometricDeviceId ?: return@LaunchedEffect

        val activity = context as? FragmentActivity
        if (activity == null) {
            snackbarHostState.showSnackbar("无法启动生物识别")
            return@LaunchedEffect
        }

        // 应用可能刚被服务从后台拉起，设备列表还没加载完。
        // 不等待的话设备信息为 null，蓝牙设备会被误判成 TCP 设备走错协议。
        val device = withTimeoutOrNull(2000L) {
            viewModel.devices.first { list -> list.any { it.id == deviceId } }
        }?.find { it.id == deviceId }

        // MODE_SKIP_BIOMETRIC：用户刚在系统锁屏上完成了身份验证，那次解锁就是「本人」的证明，
        // 不再要求第二次指纹，否则会出现「解锁进桌面后还得再按一次」的割裂体验
        val token = if (biometricSkip) {
            Log.i(TAG, "已通过系统解锁完成验证，跳过二次指纹")
            BiometricUtils.newToken()
        } else {
            BiometricUtils.authenticate(
                activity = activity,
                title = "解锁电脑",
                subtitle = "验证您的身份以解锁 ${device?.deviceName ?: "电脑"}"
            )
        }

        if (token == null) {
            snackbarHostState.showSnackbar("生物识别失败")
            return@LaunchedEffect
        }

        if (device?.pairingMethod == "BLUETOOTH") {
            // 蓝牙设备：把 PC 端发来的 unlockToken 沿同一条连接回传
            viewModel.unlockBluetoothWithToken(deviceId, token) { result ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(result ?: "已发送蓝牙解锁响应")
                }
            }
        } else {
            // TCP/UDP 设备
            viewModel.unlockWithToken(deviceId, token) { result ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(result ?: "解锁成功")
                }
            }
        }
    }
    
    if (showKeepAliveGuide) {
        KeepAliveGuideDialog(
            onDismiss = {
                com.jywr.pcbuapk.utils.KeepAliveManager.markGuideShown(context)
                showKeepAliveGuide = false
            }
        )
    }
    
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "掌钥",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = if (devices.isEmpty()) "尚未配对设备" else "已配对 ${devices.size} 台设备",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToPairing,
                content = {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("添加设备")
                }
            )
        },
        // 提示不贴屏幕底边，抬高到中下方，并渲染成一块居中的小胶囊（见 ToastPill）
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = 132.dp)
            ) { data ->
                ToastPill(data)
            }
        }
    ) { paddingValues ->
        if (devices.isEmpty()) {
            // 空状态：大图标 + 明确的主操作，而不是只留一句「点右下角」
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    modifier = Modifier.size(96.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        DesktopGlyph(
                            modifier = Modifier.size(46.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    text = "还没有配对设备",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "在电脑端打开 PC Bio Unlock，用下面的按钮扫码即可完成配对",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(24.dp))
                Button(onClick = onNavigateToPairing) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("添加设备")
                }
            }
        } else {
            // 设备列表
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                // 底部留出 FAB 的高度，最后一张卡片不会被悬浮按钮压住
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(devices) { device ->
                    DeviceCard(
                        device = device,
                        onUnlock = {
                            // 手动点击「解锁」：同样通过序号触发指纹验证。
                            // 必须清掉上一次可能残留的跳过标记，否则这里会跳过指纹直接解锁。
                            biometricSkip = false
                            biometricDeviceId = device.id
                            biometricSeq++
                        },
                        onDelete = {
                            // 不直接删，先弹确认（删除不可逆）
                            deleteTarget = device
                        },
                        onEditBluetoothAddress = { deviceId ->
                            editingDevice = device
                            showEditBtDialog = true
                        }
                    )
                }
            }
        }
    }
    
    // 编辑蓝牙地址对话框
    if (showEditBtDialog && editingDevice != null) {
        EditBluetoothAddressDialog(
            currentAddress = editingDevice!!.bluetoothAddress ?: "",
            onDismiss = {
                showEditBtDialog = false
                editingDevice = null
            },
            onSave = { newAddress ->
                viewModel.updateBluetoothAddress(editingDevice!!.id, newAddress)
                showEditBtDialog = false
                editingDevice = null
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("蓝牙地址已更新")
                }
            }
        )
    }

    // 删除确认弹窗：删除前必须二次确认，避免手误
    deleteTarget?.let { target ->
        DeleteDeviceDialog(
            deviceName = target.deviceName,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                deleteTarget = null
                viewModel.deleteDevice(target.id)
                // 确认之外再给一次反悔机会：误删后不用重新配对即可还原
                coroutineScope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = "已删除「${target.deviceName}」",
                        actionLabel = "撤销",
                        withDismissAction = false,
                        duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.restoreDevice(target)
                        snackbarHostState.showSnackbar("已恢复「${target.deviceName}」")
                    }
                }
            }
        )
    }
}

/**
 * 删除设备确认弹窗。
 *
 * 删除不可逆（需要重新配对才能再用），而删除图标与「解锁」按钮仅相隔 8dp，
 * 误触代价较高，因此统一在这里做二次确认；确认按钮用错误色以区别于普通操作。
 */
@Composable
fun DeleteDeviceDialog(
    deviceName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text("删除设备") },
        text = { Text("确定要删除「$deviceName」吗？删除后需要重新配对才能再次使用。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 编辑蓝牙地址对话框
 */
@Composable
fun EditBluetoothAddressDialog(
    currentAddress: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var address by remember { mutableStateOf(currentAddress) }
    var error by remember { mutableStateOf<String?>(null) }
    
    // 验证蓝牙地址格式
    fun isValidBluetoothAddress(addr: String): Boolean {
        return addr.matches(Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$"))
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改蓝牙地址") },
        text = {
            Column {
                Text(
                    text = "请输入电脑的蓝牙MAC地址",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "提示：请在电脑设置中查看蓝牙地址",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = address,
                    onValueChange = {
                        address = it.uppercase()
                        error = null
                    },
                    label = { Text("蓝牙地址") },
                    placeholder = { Text("例如: 04:68:74:2A:64:47") },
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                if (error != null) {
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (address.isBlank()) {
                        error = "蓝牙地址不能为空"
                    } else if (!isValidBluetoothAddress(address)) {
                        error = "格式错误，请使用 XX:XX:XX:XX:XX:XX 格式"
                    } else {
                        onSave(address)
                    }
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 设备卡片。
 *
 * 把「修改蓝牙地址 / 删除设备」收进右上角的更多菜单：
 * 原先三个按钮（解锁、编辑、删除）挤在同一行、彼此只隔 8dp，
 * 删除这种不可逆操作紧挨着主操作，误触风险很高。
 */
@Composable
fun DeviceCard(
    device: PairedDeviceEntity,
    onUnlock: () -> Unit,
    onDelete: () -> Unit,
    onEditBluetoothAddress: (String) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isBluetooth = device.pairingMethod != "TCP"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                // 设备图标
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        DesktopGlyph(
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.deviceName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    ConnectionBadge(isBluetooth)
                    Spacer(Modifier.height(10.dp))

                    InfoLine("用户", device.userName)
                    device.ipAddress?.let { InfoLine("IP 地址", it) }
                    if (isBluetooth) {
                        InfoLine(
                            label = "蓝牙",
                            value = device.bluetoothAddress ?: "未设置（点右上角可添加）",
                            valueColor = if (device.bluetoothAddress == null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        if (isBluetooth) {
                            DropdownMenuItem(
                                text = { Text("修改蓝牙地址") },
                                leadingIcon = {
                                    Icon(Icons.Default.Edit, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    onEditBluetoothAddress(device.id)
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("删除设备", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = device.lastConnectedAt?.let { "最后连接 ${formatTimestamp(it)}" }
                        ?: "尚未连接过",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onUnlock) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("解锁")
                }
            }
        }
    }
}

/**
 * 轻提示：屏幕中下方的一小块居中胶囊（替代默认的 Snackbar）。
 *
 * 设计取向（要求是「像系统 Toast 但更精致」）：
 * - 默认 Snackbar 是**通栏**的长条，这里改成宽度由内容决定、最长 320dp 的小胶囊；
 * - 全圆角 + 细描边 + 投影，底色用与卡片/弹窗同一套的中性色（surfaceContainerHighest），
 *   因此浅色/深色主题下都不会跳色，也不会出现刺眼的黑色底；
 * - 左侧按文案推断状态图标与颜色：失败→感叹号/错误色，引导类→信息/琥珀，其余→对勾/青绿；
 * - **不带操作按钮的提示 1.6 秒后自动收起**（默认 Snackbar 要 4 秒，偏拖沓）；
 *   带按钮的（删除后的「撤销」）保持不自动收起，否则用户来不及点。
 */
@Composable
private fun ToastPill(data: SnackbarData) {
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
            delay(1600L)
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

/** 连接方式标签 */
@Composable
private fun ConnectionBadge(isBluetooth: Boolean) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = if (isBluetooth) "蓝牙" else "TCP",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/** 属性行：固定宽度的标签 + 可省略的值，保证多行属性左对齐 */
@Composable
private fun InfoLine(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(60.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 「显示器」图标（自绘）。
 *
 * Material 图标的基础集合里没有电脑/设备类图标，而为这一个图标去引入
 * material-icons-extended 会让包体积明显变大；Canvas 画十几行即可，
 * 颜色还能自动跟随主题。
 */
@Composable
private fun DesktopGlyph(
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

/**
 * 格式化时间戳
 */
private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3600_000 -> "${diff / 60_000} 分钟前"
        diff < 86400_000 -> "${diff / 3600_000} 小时前"
        else -> "${diff / 86400_000} 天前"
    }
}
