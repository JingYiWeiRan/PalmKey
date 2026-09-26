package com.jywr.pcbuapk.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.zxing.integration.android.IntentIntegrator
import com.jywr.pcbuapk.service.QrCodeData
import com.jywr.pcbuapk.ui.activity.PortraitCaptureActivity
import com.jywr.pcbuapk.ui.viewmodel.PairingViewModel

private const val TAG = "PairingScreen"

/**
 * 配对界面 - 支持扫码和手动输入配对码
 *
 * 版式说明：四种状态（等待扫码 / 手动输入 / 配对中 / 成功 / 失败）都统一成
 * 「居中大图标 + 标题 + 说明 + 主操作按钮」的结构，切换状态时视觉重心不跳。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    onNavigateBack: () -> Unit,
    viewModel: PairingViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val pairingState by viewModel.pairingState.collectAsState()
    // 上报给电脑的 UDP 端口必须与设置里实际监听的端口一致
    val udpListenPort by viewModel.udpListenPort.collectAsState()

    // 配对方式选择
    var pairingMethod by remember { mutableStateOf("scan") } // "scan" or "manual"

    // 手动输入字段
    var ipAddress by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var encKey by remember { mutableStateOf("") }

    // 启动扫码器的 Launcher
    val scanLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "扫码结果回调: resultCode=${result.resultCode}, data=${result.data}")
        Log.d(TAG, "Intent extras keys: ${result.data?.extras?.keySet()}")

        // IntentIntegrator 使用的 Intent extra 键名
        val contents = result.data?.getStringExtra("SCAN_RESULT")
        val formatName = result.data?.getStringExtra("SCAN_RESULT_FORMAT")

        Log.d(TAG, "SCAN_RESULT: $contents")
        Log.d(TAG, "SCAN_RESULT_FORMAT: $formatName")

        if (result.resultCode == Activity.RESULT_OK && contents != null) {
            // 扫码成功，解析二维码内容
            Log.d(TAG, "扫码成功，内容: $contents")
            val qrData = viewModel.parseQrCode(contents)

            if (qrData != null) {
                // 开始配对
                Log.d(TAG, "二维码解析成功，开始配对: ip=${qrData.ip}, port=${qrData.port}")
                viewModel.startPairing(
                    ip = qrData.ip,
                    port = qrData.port,
                    encKey = qrData.encKey,
                    deviceName = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL,
                    udpPort = udpListenPort
                )
            } else {
                Log.e(TAG, "二维码格式错误，原始内容: $contents")
                viewModel.setErrorMessage("二维码格式错误: $contents")
            }
        } else if (result.resultCode == Activity.RESULT_CANCELED) {
            Log.d(TAG, "用户取消扫码")
            viewModel.setErrorMessage("已取消扫码")
        } else {
            Log.e(TAG, "扫码失败: resultCode=${result.resultCode}")
            viewModel.setErrorMessage("扫码失败")
        }
    }

    // 启动扫码器 - 直接通过 Launcher 启动，而不是 IntentIntegrator.initiateScan()
    fun startScan() {
        val activity = context as? ComponentActivity ?: return
        Log.d(TAG, "构建扫码 Intent")
        // 使用 IntentIntegrator 构建 Intent，但不调用 initiateScan()
        val integrator = IntentIntegrator(activity)
        integrator.setCaptureActivity(PortraitCaptureActivity::class.java)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("请扫描电脑端的二维码")
        integrator.setBeepEnabled(true)
        integrator.setOrientationLocked(true)

        // 获取 Intent 后手动通过 Launcher 启动
        val scanIntent = integrator.createScanIntent()
        Log.d(TAG, "启动扫码 Activity")
        scanLauncher.launch(scanIntent)
    }

    // 相机权限请求
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // 权限已授予，启动扫码器
            startScan()
        } else {
            viewModel.setErrorMessage("需要相机权限才能扫码")
        }
    }

    // 蓝牙权限请求（Android 12+）
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // 处理权限结果
    }

    LaunchedEffect(Unit) {
        if (pairingMethod == "scan") {
            // 请求相机权限
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)

            // 请求蓝牙权限（Android 12+）
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                bluetoothPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                    )
                )
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("添加设备", style = MaterialTheme.typography.titleLarge) },
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
                .padding(16.dp)
        ) {
            // 配对方式选择标签页
            if (pairingState is PairingViewModel.PairingState.Idle ||
                pairingState is PairingViewModel.PairingState.Error
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = pairingMethod == "scan",
                        onClick = {
                            pairingMethod = "scan"
                            viewModel.resetState()
                        },
                        label = { Text("扫码配对") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                    FilterChip(
                        selected = pairingMethod == "manual",
                        onClick = {
                            pairingMethod = "manual"
                            viewModel.resetState()
                        },
                        label = { Text("手动输入") },
                        leadingIcon = {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            when (pairingState) {
                is PairingViewModel.PairingState.Idle -> {
                    if (pairingMethod == "scan") {
                        // 扫码模式
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            ScanFrame(modifier = Modifier.size(140.dp))

                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "正在等待扫码",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "请扫描电脑端显示的二维码",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(28.dp))
                            FilledTonalButton(
                                onClick = {
                                    // 重新启动相机
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            ) {
                                Text("重新扫码")
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(onClick = { pairingMethod = "manual" }) {
                                Text("或者手动输入配对信息")
                            }
                        }
                    } else {
                        // 手动输入模式
                        ManualPairingForm(
                            ipAddress = ipAddress,
                            onIpAddressChange = { ipAddress = it },
                            port = port,
                            onPortChange = { port = it },
                            encKey = encKey,
                            onEncKeyChange = { encKey = it },
                            onPairClick = {
                                val portNum = port.toIntOrNull()
                                if (portNum == null) {
                                    viewModel.setErrorMessage("端口号格式错误")
                                    return@ManualPairingForm
                                }
                                viewModel.startPairing(
                                    ip = ipAddress,
                                    port = portNum,
                                    encKey = encKey,
                                    deviceName = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL,
                                    udpPort = udpListenPort
                                )
                            }
                        )
                    }
                }

                is PairingViewModel.PairingState.Pairing -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(52.dp),
                            strokeWidth = 4.dp
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "正在配对…",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "请保持手机与电脑在同一网络",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is PairingViewModel.PairingState.Success -> {
                    StatusPanel(
                        icon = Icons.Default.CheckCircle,
                        iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                        iconBackground = MaterialTheme.colorScheme.secondaryContainer,
                        title = "配对成功",
                        message = "现在可以在电脑上发起解锁了",
                        primaryText = "完成",
                        onPrimary = onNavigateBack
                    )
                }

                is PairingViewModel.PairingState.Error -> {
                    val error = pairingState as PairingViewModel.PairingState.Error
                    StatusPanel(
                        icon = Icons.Default.Warning,
                        iconTint = MaterialTheme.colorScheme.onErrorContainer,
                        iconBackground = MaterialTheme.colorScheme.errorContainer,
                        title = "配对失败",
                        message = error.message,
                        primaryText = "重试",
                        onPrimary = { viewModel.resetState() },
                        secondaryText = "返回",
                        onSecondary = onNavigateBack
                    )
                }
            }
        }
    }
}

/**
 * 扫码取景框（自绘）。
 *
 * 之所以不直接用图标：Material icons 的基础集合里没有「扫码/取景」这类图标，
 * 而为了一个图标去引入 material-icons-extended 会让包体积明显变大。
 * 四角 + 一条扫描线的画法十几行就能画出来，且和主题色自动一致。
 */
@Composable
private fun ScanFrame(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val arm = minOf(w, h) * 0.26f
        val stroke = 3.dp.toPx()
        val cap = StrokeCap.Round

        // 左上
        drawLine(tint, Offset(0f, 0f), Offset(arm, 0f), stroke, cap)
        drawLine(tint, Offset(0f, 0f), Offset(0f, arm), stroke, cap)
        // 右上
        drawLine(tint, Offset(w, 0f), Offset(w - arm, 0f), stroke, cap)
        drawLine(tint, Offset(w, 0f), Offset(w, arm), stroke, cap)
        // 左下
        drawLine(tint, Offset(0f, h), Offset(arm, h), stroke, cap)
        drawLine(tint, Offset(0f, h), Offset(0f, h - arm), stroke, cap)
        // 右下
        drawLine(tint, Offset(w, h), Offset(w - arm, h), stroke, cap)
        drawLine(tint, Offset(w, h), Offset(w, h - arm), stroke, cap)

        // 中间扫描线
        drawLine(
            color = tint.copy(alpha = 0.45f),
            start = Offset(arm * 0.7f, h / 2f),
            end = Offset(w - arm * 0.7f, h / 2f),
            strokeWidth = stroke * 0.6f,
            cap = cap
        )
    }
}

/**
 * 结果面板：居中圆形图标 + 标题 + 说明 + 主/次按钮。
 * 成功与失败共用，只是配色不同，保证两种状态观感一致。
 */
@Composable
private fun StatusPanel(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    iconBackground: Color,
    title: String,
    message: String,
    primaryText: String,
    onPrimary: () -> Unit,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.size(96.dp),
            shape = CircleShape,
            color = iconBackground
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(52.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (secondaryText != null && onSecondary != null) {
                OutlinedButton(onClick = onSecondary) {
                    Text(secondaryText)
                }
            }
            Button(onClick = onPrimary) {
                Text(primaryText)
            }
        }
    }
}

/**
 * 手动输入配对信息的表单
 */
@Composable
fun ManualPairingForm(
    ipAddress: String,
    onIpAddressChange: (String) -> Unit,
    port: String,
    onPortChange: (String) -> Unit,
    encKey: String,
    onEncKeyChange: (String) -> Unit,
    onPairClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column {
                Text(
                    text = "手动输入配对信息",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "请在电脑端查看配对信息并照此填写",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedTextField(
                value = ipAddress,
                onValueChange = onIpAddressChange,
                label = { Text("电脑 IP 地址") },
                placeholder = { Text("例如: 192.168.1.100") },
                leadingIcon = { Icon(Icons.Default.Place, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            OutlinedTextField(
                value = port,
                onValueChange = { input ->
                    if (input.length <= 5 && input.all { it.isDigit() }) onPortChange(input)
                },
                label = { Text("配对端口") },
                placeholder = { Text("例如: 8080") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            OutlinedTextField(
                value = encKey,
                onValueChange = onEncKeyChange,
                label = { Text("加密密钥") },
                placeholder = { Text("从电脑端获取") },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(2.dp))

            Button(
                onClick = onPairClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                enabled = ipAddress.isNotBlank() && port.isNotBlank() && encKey.isNotBlank()
            ) {
                Text("开始配对", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}
