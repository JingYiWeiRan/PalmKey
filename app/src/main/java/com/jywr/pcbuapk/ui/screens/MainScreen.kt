package com.jywr.pcbuapk.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.jywr.pcbuapk.data.entity.PairedDeviceEntity
import com.jywr.pcbuapk.ui.components.DeleteDeviceDialog
import com.jywr.pcbuapk.ui.components.DesktopGlyph
import com.jywr.pcbuapk.ui.components.DeviceCard
import com.jywr.pcbuapk.ui.components.EditBluetoothAddressDialog
import com.jywr.pcbuapk.ui.components.ToastPill
import com.jywr.pcbuapk.ui.viewmodel.MainViewModel
import com.jywr.pcbuapk.utils.BiometricUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "MainScreen"

/** 提示胶囊不贴屏幕底边，抬高到中下方 */
private val SNACKBAR_BOTTOM_OFFSET = 132.dp

/** 等待设备列表加载完成的上限：应用可能刚被解锁请求从后台拉起 */
private const val DEVICE_LOAD_TIMEOUT_MS = 2000L

/**
 * 主界面 - 设备列表
 *
 * 本文件只负责这一屏的渲染与交互编排；可复用的子组件已拆到
 * `ui.components`（DeviceCard / 两个弹窗 / ToastPill / DesktopGlyph），
 * 而「解锁请求怎么投递、什么情况下允许跳过指纹」等策略分别落在
 * `MainActivity.pendingUnlock`（无损队列）与 `UnlockAuthorizationPolicy`（有单测）。
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

    // 正在派发/已派发过的请求 ID，用于幂等（见下面的说明）
    var biometricRequestId by remember { mutableStateOf<String?>(null) }
    var lastDispatchedRequestId by remember { mutableStateOf<String?>(null) }

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

    // 正在处理的解锁请求（非 null 表示「此刻用户是来解锁的」）
    var activeUnlockRequest by remember { mutableStateOf<com.jywr.pcbuapk.UnlockRequest?>(null) }

    // 处理解锁请求（来自通知点击、全屏 Intent 或服务直接拉起界面）。
    //
    // 用 Channel 驱动的 Flow 而不是 StateFlow：请求会**排队、逐个投递、恰好消费一次**。
    // 原先用 StateFlow 时，用户停在设置/配对页会让请求无人处理而被丢弃，
    // 连续两次请求还会互相覆盖。见 MainActivity.pendingUnlock 的说明。
    //
    // 不把 request 当 key：effect 只在进入组合时启动一次并持续收集，
    // 这样即便组合在请求处理期间被重建，也不会中断正在进行的那一次。
    LaunchedEffect(Unit) {
        com.jywr.pcbuapk.MainActivity.pendingUnlock.collect { request ->
            Log.i(TAG, "处理解锁请求: ${request.deviceName} (${request.deviceId}) mode=${request.mode}")
            activeUnlockRequest = request

            if (request.mode == com.jywr.pcbuapk.service.UnlockListenerService.MODE_WAIT_KEYGUARD) {
                // 手机还锁着：不要在这里弹指纹（此时 keyguard 未解除，BiometricPrompt 必然失败）。
                // 只需提示用户去锁屏解锁；用户解锁成功后，服务会再投递一条
                // MODE_SKIP_BIOMETRIC，那时才真正执行解锁。
                snackbarHostState.showSnackbar("请在系统锁屏上解锁以继续")
                return@collect
            }

            biometricDeviceId = request.deviceId
            biometricRequestId = request.id
            biometricSkip = request.mode == com.jywr.pcbuapk.service.UnlockListenerService.MODE_SKIP_BIOMETRIC
            biometricSeq++
        }
    }

    // 指纹验证 + 解锁上报
    LaunchedEffect(biometricSeq) {
        if (biometricSeq == 0) return@LaunchedEffect
        val deviceId = biometricDeviceId ?: return@LaunchedEffect

        // 幂等闸门：同一请求只派发一次。
        //
        // 实机验证发现（vivo / Android 15）：从设置页被解锁请求切回主页时，
        // 这个 LaunchedEffect 会**以同一个 key 再触发一次**（同一组合实例，
        // remember 状态也在），而下面的派发走 viewModelScope、不随 effect 取消而停止，
        // 于是同一个请求弹了两次、并给电脑回了两条响应。
        // 不能假设「LaunchedEffect 只跑一次」，所以在派发点用请求 ID 兜底。
        val requestId = biometricRequestId
        if (requestId != null && requestId == lastDispatchedRequestId) {
            Log.w(TAG, "请求 $requestId 已派发过，忽略本次重复触发")
            return@LaunchedEffect
        }
        lastDispatchedRequestId = requestId

        val activity = context as? FragmentActivity
        if (activity == null) {
            snackbarHostState.showSnackbar("无法启动生物识别")
            return@LaunchedEffect
        }

        // 应用可能刚被服务从后台拉起，设备列表还没加载完。
        // 不等待的话设备信息为 null，接下来就无法判断该走蓝牙还是 TCP。
        val device = withTimeoutOrNull(DEVICE_LOAD_TIMEOUT_MS) {
            viewModel.devices.first { list -> list.any { it.id == deviceId } }
        }?.find { it.id == deviceId }

        // 设备信息还没就绪时**必须放弃**，不能猜协议。
        // 原先这里 device 为 null 会落到 TCP 分支：蓝牙设备会被当成 TCP 去连接，
        // 失败信息还是误导性的「未收到解锁响应」。宁可如实提示重试。
        if (device == null) {
            Log.w(TAG, "设备信息尚未就绪，放弃本次解锁: $deviceId")
            snackbarHostState.showSnackbar("设备信息尚未就绪，请重试")
            activeUnlockRequest = null
            return@LaunchedEffect
        }

        // MODE_SKIP_BIOMETRIC：用户刚在系统锁屏上完成了身份验证，那次解锁就是「本人」的证明，
        // 不再要求第二次指纹，否则会出现「解锁进桌面后还得再按一次」的割裂体验。
        // （该模式只在 UnlockAuthorizationPolicy 的等待窗口内才会由服务下发。）
        val token = if (biometricSkip) {
            Log.i(TAG, "已通过系统解锁完成验证，跳过二次指纹")
            BiometricUtils.newToken()
        } else {
            BiometricUtils.authenticate(
                activity = activity,
                title = "解锁电脑",
                subtitle = "验证您的身份以解锁 ${device.deviceName}"
            )
        }

        if (token == null) {
            snackbarHostState.showSnackbar("生物识别失败")
            activeUnlockRequest = null
            return@LaunchedEffect
        }

        if (com.jywr.pcbuapk.data.entity.PairingMethods.isBluetooth(device.pairingMethod)) {
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

        activeUnlockRequest = null
    }

    // 首次启动引导：**正在处理解锁请求时不要弹**。
    // 应用很可能正是被解锁请求拉起来的，此时弹一屏权限说明会把指纹界面顶掉，
    // 用户看到的就是「电脑要解锁，手机却让我读引导」。
    // 请求处理完后 activeUnlockRequest 归位，引导会自然补上。
    if (showKeepAliveGuide && activeUnlockRequest == null) {
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
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = SNACKBAR_BOTTOM_OFFSET)
            ) { data ->
                ToastPill(data)
            }
        }
    ) { paddingValues ->
        if (devices.isEmpty()) {
            EmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                onNavigateToPairing = onNavigateToPairing
            )
        } else {
            DeviceList(
                devices = devices,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                onUnlock = { device ->
                    // 手动点击「解锁」：同样通过序号触发指纹验证。
                    // 必须清掉上一次可能残留的跳过标记，否则这里会跳过指纹直接解锁；
                    // 也要清掉 requestId，否则会被上面那道「同请求只派发一次」的幂等闸门拦住。
                    biometricSkip = false
                    biometricRequestId = null
                    biometricDeviceId = device.id
                    biometricSeq++
                },
                onDelete = { device -> deleteTarget = device },
                onEditBluetoothAddress = { device ->
                    editingDevice = device
                    showEditBtDialog = true
                }
            )
        }
    }

    // 编辑蓝牙地址对话框
    if (showEditBtDialog && editingDevice != null) {
        val target = editingDevice
        EditBluetoothAddressDialog(
            currentAddress = target?.bluetoothAddress ?: "",
            onDismiss = {
                showEditBtDialog = false
                editingDevice = null
            },
            onSave = { newAddress ->
                if (target != null) {
                    viewModel.updateBluetoothAddress(target.id, newAddress)
                }
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

/** 空状态：大图标 + 明确的主操作，而不是只留一句「点右下角」 */
@Composable
private fun EmptyState(
    modifier: Modifier = Modifier,
    onNavigateToPairing: () -> Unit
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
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
}

/** 设备列表；底部留出 FAB 的高度，最后一张卡片不会被悬浮按钮压住 */
@Composable
private fun DeviceList(
    devices: List<PairedDeviceEntity>,
    modifier: Modifier = Modifier,
    onUnlock: (PairedDeviceEntity) -> Unit,
    onDelete: (PairedDeviceEntity) -> Unit,
    onEditBluetoothAddress: (PairedDeviceEntity) -> Unit
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            bottom = 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // key 用设备主键：删除/撤销后列表项不会串位，DeviceCard 内部的菜单展开态
        // 也不会被复用到别的设备上
        items(devices, key = { it.id }) { device ->
            DeviceCard(
                device = device,
                onUnlock = { onUnlock(device) },
                onDelete = { onDelete(device) },
                onEditBluetoothAddress = { onEditBluetoothAddress(device) }
            )
        }
    }
}
