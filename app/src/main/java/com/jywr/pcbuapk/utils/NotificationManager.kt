package com.jywr.pcbuapk.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat

/**
 * 通知权限工具。
 *
 * 说明：原先这里还有一整套通知发送逻辑（`showDeviceDetectedNotification`、
 * `showUnlockResultNotification`、`cancelAllNotifications`）以及一个独立渠道
 * `pcbu_channel`，但它们**全部没有调用点**，而解锁提醒实际由
 * [com.jywr.pcbuapk.service.UnlockListenerService] 用自己的
 * `pcbu_service` / `pcbu_unlock_request` 两个渠道发出。留着那套代码的副作用是：
 * `MainActivity` 每次启动都会创建一个永远不会有通知的 `pcbu_channel`，
 * 在系统通知设置里留下一个无用条目。已整体删除，只保留仍被使用的权限检查。
 */
object NotificationManager {

    /**
     * 检查是否有通知权限。
     *
     * Android 13（TIRAMISU）起 `POST_NOTIFICATIONS` 是运行时权限；
     * 更低版本默认具备。
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
