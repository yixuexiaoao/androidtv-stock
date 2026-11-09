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
