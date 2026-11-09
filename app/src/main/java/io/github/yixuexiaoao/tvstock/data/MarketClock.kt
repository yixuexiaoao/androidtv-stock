package io.github.yixuexiaoao.tvstock.data

import io.github.yixuexiaoao.tvstock.model.Markets
import java.util.Calendar
import java.util.TimeZone

/** 交易时段判断。A 股按北京时间，美股按美东时间（含夏令时），不依赖电视的时区设置。 */
object MarketClock {
    private val BEIJING: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
    private val NEW_YORK: TimeZone = TimeZone.getTimeZone("America/New_York")

    private fun calendar(ms: Long, market: String = Markets.CN) =
        Calendar.getInstance(if (market == Markets.US) NEW_YORK else BEIJING).apply { timeInMillis = ms }

    private fun isWeekend(ms: Long, market: String): Boolean {
        val weekday = calendar(ms, market).get(Calendar.DAY_OF_WEEK)
        return weekday == Calendar.SATURDAY || weekday == Calendar.SUNDAY
    }

    private fun minute(ms: Long, market: String): Int {
        val c = calendar(ms, market)
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /**
     * A 股工作日 9:15-11:31、12:59-15:01（含集合竞价和尾盘数据延迟）；
     * 美股工作日 9:30-16:01，不含盘前盘后。不含节假日判断。
     */
    fun isTradingTime(ms: Long, market: String = Markets.CN): Boolean {
        if (isWeekend(ms, market)) return false
        val m = minute(ms, market)
        return if (market == Markets.US) {
            m in (9 * 60 + 30)..(16 * 60 + 1)
        } else {
            m in (9 * 60 + 15)..(11 * 60 + 31) || m in (12 * 60 + 59)..(15 * 60 + 1)
        }
    }

    /** 工作日盘中保持亮屏，A 股午休也不例外：屏保启动后界面被停止，下午开盘不会自动回来。 */
    fun isKeepAwakeTime(ms: Long, market: String = Markets.CN): Boolean {
        if (isWeekend(ms, market)) return false
        val m = minute(ms, market)
        return if (market == Markets.US) m in (9 * 60 + 20)..(16 * 60 + 10) else m in (9 * 60)..(15 * 60 + 10)
    }

    /** 盘面状态文字。lastTradeDate 为最新行情日期（当地），用来识别节假日休市。 */
    fun status(ms: Long, lastTradeDate: String?, market: String = Markets.CN): String {
        if (isWeekend(ms, market)) return "休市"
        val m = minute(ms, market)
        val open = 9 * 60 + 30
        // 美股行情可能延迟十几分钟，开盘后多等一会儿，避免把刚开盘误判成休市
        if (m >= open + (if (market == Markets.US) 20 else 0) && !lastTradeDate.isNullOrEmpty() && lastTradeDate != dateString(ms, market)) {
            return "休市"
        }
        if (market == Markets.US) {
            return when {
                m < 4 * 60 -> "未开盘"
                m < open -> "盘前"
                m < 16 * 60 -> "交易中"
                m < 20 * 60 -> "盘后"
                else -> "已收盘"
            }
        }
        return when {
            m < 9 * 60 + 15 -> "未开盘"
            m < open -> "集合竞价"
            m <= 11 * 60 + 30 -> "交易中"
            m < 13 * 60 -> "午休"
            m < 15 * 60 -> "交易中"
            else -> "已收盘"
        }
    }

    /** 北京时间当天第几分钟。 */
    fun minuteOfDay(ms: Long): Int = minute(ms, Markets.CN)

    /** yyyyMMdd。 */
    fun dateString(ms: Long, market: String = Markets.CN): String {
        val c = calendar(ms, market)
        return "%04d%02d%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** HH:mm:ss。 */
    fun timeString(ms: Long, market: String = Markets.CN): String {
        val c = calendar(ms, market)
        return "%02d:%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND))
    }
}
