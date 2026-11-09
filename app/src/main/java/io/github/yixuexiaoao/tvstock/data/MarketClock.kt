package io.github.yixuexiaoao.tvstock.data

import java.util.Calendar
import java.util.TimeZone

/** A 股交易时段判断，统一按北京时间，不依赖电视的时区设置。 */
object MarketClock {
    private val ZONE: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun calendar(ms: Long) = Calendar.getInstance(ZONE).apply { timeInMillis = ms }

    /** 工作日 9:15-11:31、12:59-15:01（含集合竞价和尾盘数据延迟）。不含节假日判断。 */
    fun isTradingTime(ms: Long): Boolean {
        val c = calendar(ms)
        val weekday = c.get(Calendar.DAY_OF_WEEK)
        if (weekday == Calendar.SATURDAY || weekday == Calendar.SUNDAY) return false
        val minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return minute in (9 * 60 + 15)..(11 * 60 + 31) || minute in (12 * 60 + 59)..(15 * 60 + 1)
    }

    /** 盘面状态文字。lastTradeDate 为最新行情日期，用来识别节假日休市。 */
    fun status(ms: Long, lastTradeDate: String?): String {
        val c = calendar(ms)
        val weekday = c.get(Calendar.DAY_OF_WEEK)
        if (weekday == Calendar.SATURDAY || weekday == Calendar.SUNDAY) return "休市"
        val minute = minuteOfDay(ms)
        if (minute >= 9 * 60 + 30 && !lastTradeDate.isNullOrEmpty() && lastTradeDate != dateString(ms)) return "休市"
        return when {
            minute < 9 * 60 + 15 -> "未开盘"
            minute < 9 * 60 + 30 -> "集合竞价"
            minute <= 11 * 60 + 30 -> "交易中"
            minute < 13 * 60 -> "午休"
            minute < 15 * 60 -> "交易中"
            else -> "已收盘"
        }
    }

    /** 北京时间当天第几分钟。 */
    fun minuteOfDay(ms: Long): Int {
        val c = calendar(ms)
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** yyyyMMdd。 */
    fun dateString(ms: Long): String {
        val c = calendar(ms)
        return "%04d%02d%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** HH:mm:ss。 */
    fun timeString(ms: Long): String {
        val c = calendar(ms)
        return "%02d:%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND))
    }
}
