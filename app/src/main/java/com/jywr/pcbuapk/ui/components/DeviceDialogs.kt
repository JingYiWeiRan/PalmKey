package com.jywr.pcbuapk.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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

/** XX:XX:XX:XX:XX:XX 形式的蓝牙 MAC 地址 */
private val BLUETOOTH_ADDRESS_REGEX = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

/**
 * 编辑蓝牙地址对话框。
 *
 * 说明：蓝牙配对失败或电脑端换了蓝牙适配器时，配对记录里的 MAC 会失效，
 * 而重配对成本较高，因此提供手工修正入口。
 */
@Composable
fun EditBluetoothAddressDialog(
    currentAddress: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var address by remember { mutableStateOf(currentAddress) }
    var error by remember { mutableStateOf<String?>(null) }

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
                error?.let { message ->
                    Text(
                        text = message,
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
                    } else if (!BLUETOOTH_ADDRESS_REGEX.matches(address)) {
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
