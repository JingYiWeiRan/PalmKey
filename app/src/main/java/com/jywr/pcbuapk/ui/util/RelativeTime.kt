package com.jywr.pcbuapk.ui.util

/**
 * 「最后连接时间」的相对文案。
 *
 * 从 `MainScreen` 抽出来是为了两件事：
 * 1. 让它可单测（`now` 可注入，不再直接读系统时钟）；
 * 2. 顺手修掉**未来时间戳**的处理 —— 电脑端时钟偏快或从备份恢复后，
 *    `now - timestamp` 为负，原先会被判成「刚刚」，于是一年后的事情也显示「刚刚」。
 *    现在负差值有明确语义（按「刚刚」处理），不会产生负数文案。
 */
object RelativeTime {

    fun format(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val diff = now - timestamp

        return when {
            // diff < 0（未来）与 diff < 60s 都归为「刚刚」
            diff < 60_000L -> "刚刚"
            diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
            diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
            else -> "${diff / 86_400_000L} 天前"
        }
    }
}
