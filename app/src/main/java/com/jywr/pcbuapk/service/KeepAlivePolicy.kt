package com.jywr.pcbuapk.service

/**
 * 保活模式的语义集中在这里。
 *
 * 为什么不散在各处写 if：模式一旦加值，漏改一处就会产生最难排查的现象 ——
 * 「清了后台，过了十分钟它又活了」。所有「是否复活」的判断收敛到一个纯逻辑对象，
 * 并用 `KeepAlivePolicyTest` 钉死。
 *
 * ## 模式含义
 *
 * | 值 | 名称 | 含义 |
 * |---|---|---|
 * | 0 | 省电 | 30 分钟自检一次，其余照常 |
 * | 1 | 平衡（默认） | 15 分钟自检一次 |
 * | 2 | 可靠 | 5 分钟自检一次 |
 * | 3 | 不保活 | **不做任何主动续命**：不排闹钟、不入队 WorkManager、开机不自启、<br>划掉最近任务即彻底停止、被系统杀死后不再重建 |
 *
 * 「不保活」只关掉**自动复活**，不改变应用运行期间的行为（仍然正常监听解锁请求，
 * 包括息屏场景）—— 用户要的是"清掉就死透"，而不是"活着的时候也别干活"。
 */
object KeepAlivePolicy {

    /** 省电模式：30 分钟自检一次 */
    const val MODE_BATTERY_SAVER = 0

    /** 平衡模式（默认）：15 分钟自检一次 */
    const val MODE_BALANCED = 1

    /** 可靠模式：5 分钟自检一次 */
    const val MODE_RELIABLE = 2

    /** 不保活：不做任何主动续命，清掉即彻底停止 */
    const val MODE_NO_KEEP_ALIVE = 3

    /** 设置界面按这个顺序展示 */
    val ALL_MODES = listOf(MODE_BATTERY_SAVER, MODE_BALANCED, MODE_RELIABLE, MODE_NO_KEEP_ALIVE)

    /**
     * 是否主动续命：排保活闹钟 + 入队 WorkManager。
     * 这是「不保活」要关掉的第一条路径。
     */
    fun shouldArmKeepAlive(mode: Int): Boolean = mode != MODE_NO_KEEP_ALIVE

    /** 开机（BOOT_COMPLETED / QUICKBOOT / 应用更新）后是否自启 */
    fun shouldStartOnBoot(mode: Int): Boolean = mode != MODE_NO_KEEP_ALIVE

    /** 用户把应用从最近任务划掉后，服务是否继续在后台运行 */
    fun shouldSurviveTaskRemoval(mode: Int): Boolean = mode != MODE_NO_KEEP_ALIVE

    /** 服务被系统杀死后，是否允许系统重建它（`START_STICKY`） */
    fun shouldBeSticky(mode: Int): Boolean = mode != MODE_NO_KEEP_ALIVE

    /**
     * 保活心跳间隔。
     *
     * 未知取值回落到 15 分钟而不是"不保活"：把异常数据当成不保活会让应用在用户
     * 毫无察觉的情况下彻底失能，而回落成常规模式只是多耗一点电 —— 失败方向要偏安全。
     */
    fun keepAliveIntervalMs(mode: Int): Long = when (mode) {
        MODE_BATTERY_SAVER -> 30 * 60 * 1000L
        MODE_RELIABLE -> 5 * 60 * 1000L
        else -> 15 * 60 * 1000L
    }
}
