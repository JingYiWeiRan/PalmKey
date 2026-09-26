package com.jywr.pcbuapk.utils

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * 保活管理器
 *
 * 集中处理「让服务在国产 ROM 上活下来」和「让验证界面能弹出来」所需的系统设置跳转。
 *
 * 国内厂商 ROM（小米/华为/OPPO/vivo）对后台进程有额外的自启动、后台弹出界面、
 * 锁屏显示限制，不引导用户开启，前台服务依然会被回收。
 */
object KeepAliveManager {

    private const val TAG = "KeepAliveManager"
    private const val PREFS_NAME = "pcbu_keepalive"
    private const val KEY_GUIDE_SHOWN = "keepalive_guide_shown_v1"

    // ---------------------------------------------------------------- 电池优化白名单

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (isIgnoringBatteryOptimizations(context)) {
            Log.d(TAG, "已在电池优化白名单中")
            return
        }
        startSafely(
            context,
            Intent().apply {
                action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                data = Uri.parse("package:${context.packageName}")
            },
            "电池优化白名单"
        )
    }

    fun openBatteryOptimizationSettings(context: Context) {
        startSafely(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), "电池优化设置")
    }

    // ---------------------------------------------------------------- 精确闹钟

    /**
     * Android 12+ 起 SCHEDULE_EXACT_ALARM 默认不授予。
     * 必须先判断，否则 setExactAndAllowWhileIdle 会抛 SecurityException。
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.canScheduleExactAlarms() ?: false
        } else {
            true
        }
    }

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        startSafely(
            context,
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
            },
            "精确闹钟设置"
        )
    }

    /**
     * 统一的唤醒闹钟调度：能用精确闹钟就用，否则降级为 setAndAllowWhileIdle。
     * 保活心跳依赖它，之前直接调用精确闹钟在 Android 13+ 上会被静默拒绝导致心跳断链。
     *
     * @return true 表示使用了精确闹钟，false 表示已降级
     */
    fun scheduleWakeup(context: Context, triggerAtMillis: Long, pendingIntent: PendingIntent): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (alarmManager == null) {
            Log.e(TAG, "无法获取 AlarmManager")
            return false
        }

        if (canScheduleExactAlarms(context)) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                Log.d(TAG, "⏰ 已设置精确保活闹钟")
                return true
            } catch (e: SecurityException) {
                Log.w(TAG, "⚠️ 精确闹钟被拒绝，降级为 setAndAllowWhileIdle", e)
            }
        } else {
            Log.d(TAG, "ℹ️ 未授予精确闹钟权限，使用 setAndAllowWhileIdle 降级")
        }

        return try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            Log.d(TAG, "⏰ 已设置降级保活闹钟")
            false
        } catch (e: Exception) {
            Log.e(TAG, "设置保活闹钟失败", e)
            false
        }
    }

    // ---------------------------------------------------------------- 全屏通知（Android 14+）

    /** Android 14+ USE_FULL_SCREEN_INTENT 需用户在系统设置里手动授权 */
    fun canUseFullScreenIntent(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.canUseFullScreenIntent() ?: false
        } else {
            true
        }
    }

    fun openFullScreenIntentSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        startSafely(
            context,
            // 用字符串常量避免在低版本编译期引用问题
            Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT").apply {
                data = Uri.parse("package:${context.packageName}")
            },
            "全屏通知设置"
        )
    }

    // ---------------------------------------------------------------- 悬浮窗（后台直接拉起界面）

    /**
     * 是否已获得「显示在其他应用上层」（悬浮窗）权限。
     *
     * 该权限是 Android 官方列出的「允许从后台启动 Activity」豁免条件之一：
     * 拿到它之后，服务在后台直接 startActivity 就能把验证界面推到用户眼前，
     * 无需用户先点通知；没有它时亮屏状态下只能退化为横幅。
     */
    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * 跳转本应用的悬浮窗权限设置页。
     *
     * 注意：这个页面无法用「请求权限」的方式弹窗询问，只能引导用户手动打开开关。
     */
    fun openOverlaySettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        startSafely(
            context,
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            },
            "悬浮窗权限"
        )
    }

    // ---------------------------------------------------------------- 厂商自启动 / 后台弹出界面

    /**
     * 打开自启动管理页面（针对不同厂商）
     */
    fun openAutoStartSettings(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()

        val intent = when {
            isHuawei(manufacturer) -> componentIntent(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
            isXiaomi(manufacturer) -> componentIntent(
                "com.miui.securitycenter",
                "com.miui.permcenter.autoperm.AutoStartManagementActivity"
            )
            isOppo(manufacturer) -> componentIntent(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            )
            manufacturer.contains("vivo") -> componentIntent(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
            manufacturer.contains("samsung") -> componentIntent(
                "com.samsung.android.sm_cn",
                "com.samsung.android.sm.ui.ram.AutoRunActivity"
            )
            else -> appDetailIntent(context)
        }

        startSafely(context, intent, "自启动设置 ($manufacturer)")
    }

    /**
     * 打开「后台弹出界面 / 锁屏显示」类页面。
     * 小米把这项权限放在应用权限编辑页里，不给就只能靠通知，所以必须能跳过去。
     */
    fun openBackgroundPopupSettings(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()

        val intent = when {
            isXiaomi(manufacturer) -> componentIntent(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.AppPermissionsEditorActivity"
            ).apply { putExtra("extra_pkgname", context.packageName) }
            isOppo(manufacturer) -> componentIntent(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            )
            manufacturer.contains("vivo") -> componentIntent(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
            isHuawei(manufacturer) -> componentIntent(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
            )
            else -> appDetailIntent(context)
        }

        startSafely(context, intent, "后台弹出界面设置 ($manufacturer)")
    }

    // ---------------------------------------------------------------- 首次引导标记

    fun hasShownGuide(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_GUIDE_SHOWN, false)
    }

    fun markGuideShown(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_GUIDE_SHOWN, true)
            .apply()
    }

    // ---------------------------------------------------------------- 内部工具

    private fun isHuawei(manufacturer: String) =
        manufacturer.contains("huawei") || manufacturer.contains("honor")

    private fun isXiaomi(manufacturer: String) =
        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco")

    private fun isOppo(manufacturer: String) =
        manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus")

    private fun componentIntent(packageName: String, className: String) =
        Intent().apply { component = ComponentName(packageName, className) }

    private fun appDetailIntent(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }

    /**
     * 厂商页面的组件名属于「未公开接口」，不同机型可能不存在，失败时统一退回应用详情页。
     */
    private fun startSafely(context: Context, intent: Intent, label: String) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            Log.d(TAG, "已打开$label")
        } catch (e: Exception) {
            Log.w(TAG, "打开$label 失败，退回应用详情页", e)
            try {
                context.startActivity(appDetailIntent(context).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e2: Exception) {
                Log.e(TAG, "打开应用详情页也失败", e2)
            }
        }
    }
}
