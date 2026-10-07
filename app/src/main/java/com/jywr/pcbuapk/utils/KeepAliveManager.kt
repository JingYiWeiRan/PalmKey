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
import androidx.work.WorkManager
import com.jywr.pcbuapk.receiver.KeepAliveReceiver
import com.jywr.pcbuapk.service.KeepAlivePolicy

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
    private const val KEY_KEEP_ALIVE_INTERVAL = "keepalive_interval_ms"
    private const val KEY_KEEP_ALIVE_MODE = "keepalive_mode"
    private const val KEY_ROM_PERMISSION_PREFIX = "rom_permission_confirmed_"

    /** 保活间隔默认值：15 分钟（与「平衡模式」一致） */
    const val DEFAULT_KEEP_ALIVE_INTERVAL_MS = 15 * 60 * 1000L

    /**
     * WorkManager 保活周期任务的唯一名字 / tag。
     *
     * 定义在这里而不是服务里，是为了让"取消"与"入队"用的是同一个值：
     * 两边各写一份字面量的后果是取消静默失效（本项目就发生过 —— 清理用的 tag
     * 与打标的 tag 不一致，清理一直是空操作）。
     */
    const val KEEP_ALIVE_WORK_TAG = "pcbu_keep_alive"

    // ---------------------------------------------------------------- 电池优化白名单

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }

    /**
     * 打开系统「电池优化」列表页，由用户自行把本应用设为「不优化」。
     *
     * 为什么不用 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 那个「一键加白」弹窗：
     * 它需要 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限，而 Google Play 对该权限的
     * 用途有明确限制（闹钟 / VoIP / companion 设备等特定类别），指纹解锁工具申请它
     * 属于典型驳回理由。
     * `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` 是普通设置页：无需权限、无政策风险，
     * 代价只是用户多一次点击。
     */
    fun openBatteryOptimizationSettings(context: Context) {
        startSafely(
            context,
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            "电池优化设置"
        )
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

    /**
     * 取消保活闹钟链。
     *
     * 切到「不保活」模式时**必须**调用：闹钟链是自我续期的，已排的那一环到点照样会把
     * 服务拉起来 —— 只"不再排新的"是不够的，用户会看到"清了后台过几分钟它又活了"。
     *
     * 这里的 PendingIntent 参数必须与三处武装点（服务 / KeepAliveReceiver / KeepAliveWorker）
     * 完全一致（同一组件、同一 action、同一请求码），否则取消的是另一个 PendingIntent，
     * 真正的闹钟依然留着。
     */
    fun cancelKeepAliveAlarm(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, KeepAliveReceiver::class.java).apply {
                action = KeepAliveReceiver.ACTION_KEEP_ALIVE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                KeepAliveReceiver.REQUEST_CODE_KEEP_ALIVE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.i(TAG, "保活闹钟已取消")
        } catch (e: Exception) {
            Log.w(TAG, "取消保活闹钟失败", e)
        }
    }

    /**
     * 停止**一切**主动续命手段：取消保活闹钟 + 取消 WorkManager 周期任务。
     *
     * 用户把模式切成「不保活」时必须**当场**调用。只在服务启动时判断是不够的：
     * `startKeepAlive()` 每个服务实例只执行一次（有 `keepAliveArmed` 闸门），
     * 用户改模式时它不会再跑，于是那条会自我续期的闹钟继续把服务拉起来。
     * 真机上已复现过：切到「不保活」之后 `dumpsys alarm` 里 `ACTION_KEEP_ALIVE`
     * 依然存在，而且刚被重新武装过一次。
     */
    fun stopKeepAlive(context: Context) {
        cancelKeepAliveAlarm(context)
        try {
            WorkManager.getInstance(context).cancelUniqueWork(KEEP_ALIVE_WORK_TAG)
            Log.i(TAG, "保活周期任务已取消")
        } catch (e: Exception) {
            Log.w(TAG, "取消保活周期任务失败", e)
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

    // ---------------------------------------------------------------- 保活间隔
    //
    // 保活链是由三处协同接力的（服务首次武装 → KeepAliveReceiver 续期 → KeepAliveWorker 续期），
    // 它们必须用**同一个**间隔。服务把用户选的间隔写在这里，续期方读它。
    //
    // 早先续期方各自写死 5 分钟，于是用户选的「省电模式 30 分钟」在第一跳之后就
    // 被无声地降级成 5 分钟 —— 设置项形同虚设，还额外耗电。

    /** 记录本次保活链使用的间隔（毫秒），由 UnlockListenerService 在武装时写入。 */
    fun setKeepAliveInterval(context: Context, intervalMs: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_KEEP_ALIVE_INTERVAL, intervalMs)
            .apply()
    }

    /** 读取保活链应使用的间隔（毫秒）；未设置过时回退到 [DEFAULT_KEEP_ALIVE_INTERVAL_MS]。 */
    fun getKeepAliveInterval(context: Context): Long {
        val interval = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_KEEP_ALIVE_INTERVAL, DEFAULT_KEEP_ALIVE_INTERVAL_MS)
        // 防御历史/异常数据：间隔必须为正，否则闹钟会以过去时间触发形成忙循环
        return if (interval > 0) interval else DEFAULT_KEEP_ALIVE_INTERVAL_MS
    }

    // ---------------------------------------------------------------- 保活模式的同步镜像
    //
    // 保活模式的正本在 DataStore（UserPreferences），但它只能异步读；而下面三处都必须
    // **同步**拿到模式，否则无法在同一个调用里决定行为：
    //   - UnlockListenerService.onStartCommand：决定返回 START_STICKY 还是 START_NOT_STICKY
    //   - UnlockListenerService.onTaskRemoved：决定划掉任务后是停服还是继续跑
    //   - BootReceiver.onReceive：决定开机要不要自启
    // 所以在用户改动模式时同步写一份到 SharedPreferences 作为镜像。
    //
    // 镜像缺失时默认「平衡模式」是安全的：上面三处只区分「不保活」与「其余模式」，
    // 而默认值不属于「不保活」，因此老版本升级上来的用户、以及从未改过模式的用户，
    // 行为都与以前一致，不会因为镜像缺失而失能。

    fun setKeepAliveMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_KEEP_ALIVE_MODE, mode)
            .apply()
    }

    /** @return 保活模式；未设置过时回退到「平衡模式」 */
    fun getKeepAliveMode(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_KEEP_ALIVE_MODE, KeepAlivePolicy.MODE_BALANCED)

    // ---------------------------------------------------------------- 无法查询的 ROM 权限
    //
    // 「后台弹出界面 / 锁屏显示」「自启动 / 后台运行」是国产 ROM 的私有开关，
    // **没有任何公开 API 可以查询**。此前界面固定传 granted = null，于是无论用户开没开，
    // 那一行永远显示成「需要开启」，用户看到的就是"我明明开了，它还说没开"。
    // 既然查不到，就把状态交给用户自己确认（并持久化），界面据此显示「已确认」。

    /** 手动确认项：后台弹出界面 / 锁屏显示 */
    const val ROM_PERM_BACKGROUND_POPUP = "background_popup"

    /** 手动确认项：自启动 / 后台运行 */
    const val ROM_PERM_AUTOSTART = "autostart"

    fun isRomPermissionConfirmed(context: Context, key: String): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ROM_PERMISSION_PREFIX + key, false)

    fun setRomPermissionConfirmed(context: Context, key: String, confirmed: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ROM_PERMISSION_PREFIX + key, confirmed)
            .apply()
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
