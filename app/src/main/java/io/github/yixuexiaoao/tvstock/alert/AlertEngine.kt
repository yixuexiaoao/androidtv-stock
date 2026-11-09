package io.github.yixuexiaoao.tvstock.alert

import io.github.yixuexiaoao.tvstock.data.MarketClock
import io.github.yixuexiaoao.tvstock.model.AlertEvent
import io.github.yixuexiaoao.tvstock.model.AppSettings
import io.github.yixuexiaoao.tvstock.model.Holding
import io.github.yixuexiaoao.tvstock.model.Quote
import io.github.yixuexiaoao.tvstock.model.StockConfig

/**
 * 到价判断。只在条件“由假变真”时触发一次，价格回落后重新布防；
 * 可选 alertRepeatMin 在条件持续满足时周期性重复提醒。
 */
class AlertEngine {
    private data class Armed(var active: Boolean, var lastFiredMs: Long, var firedDate: String = "")
    private class PricePoint(val timeMs: Long, val price: Double)

    private val states = HashMap<String, Armed>()
    private val history = HashMap<String, ArrayDeque<PricePoint>>()
    private var nextId = 1L

    /** 记录价格并返回窗口内的变化百分比（相对窗口内最早的价格）。 */
    private fun speedChange(code: String, price: Double, nowMs: Long, windowMs: Long): Double {
        val points = history.getOrPut(code) { ArrayDeque() }
        points.addLast(PricePoint(nowMs, price))
        while (points.first().timeMs < nowMs - windowMs) points.removeFirst()
        val oldest = points.first().price
        return if (oldest > 0) (price - oldest) / oldest * 100 else 0.0
    }

    @Synchronized
    fun evaluate(stock: StockConfig, quote: Quote, settings: AppSettings, nowMs: Long): List<AlertEvent> {
        if (!stock.enabled || !quote.hasTrade) return emptyList()
        val modes = stock.alertModes ?: settings.alertModes
        if (modes.isEmpty()) return emptyList()
        // 过滤盘后/节假日的旧行情，避免晚上打开 App 时误报
        val stale = settings.alertTradingOnly &&
            (!MarketClock.isTradingTime(nowMs) || quote.tradeDate != MarketClock.dateString(nowMs))

        val name = stock.alias.ifBlank { quote.name }
        val price = quote.price
        val fmt = "%.${quote.decimals}f"
        val holding = Holding.of(stock, quote)
        val events = mutableListOf<AlertEvent>()

        fun check(kind: String, direction: String, threshold: Double?, met: (Double) -> Boolean, message: (Double) -> String) {
            if (threshold == null) return
            // key 含阈值：用户改了目标价后可重新触发
            val state = states.getOrPut("${stock.code}|$kind|$threshold") { Armed(false, 0) }
            if (!met(threshold)) {
                state.active = false
                return
            }
            if (stale) return
            val today = MarketClock.dateString(nowMs)
            val sinceLast = nowMs - state.lastFiredMs
            val repeat = settings.alertRepeatMin
            val due = when {
                settings.alertFrequency == "DAILY" -> state.firedDate != today
                !state.active -> sinceLast >= settings.alertCooldownMin * 60_000L
                else -> repeat > 0 && sinceLast >= repeat * 60_000L
            }
            if (due) {
                // 只有真正提醒了才算“已触发”，冷却期内的穿越等冷却结束后仍会提醒
                state.active = true
                state.lastFiredMs = nowMs
                state.firedDate = today
                events += AlertEvent(nextId++, stock.code, name, kind, message(threshold), price, nowMs, modes, direction = direction)
            }
        }

        val up = AlertEvent.UP
        val down = AlertEvent.DOWN
        check("TARGET_HIGH", up, stock.targetHigh, { price >= it }) { "$name 达到目标价 ${fmt.format(it)}，现价 ${fmt.format(price)}" }
        check("TARGET_LOW", down, stock.targetLow, { price <= it }) { "$name 跌破提醒价 ${fmt.format(it)}，现价 ${fmt.format(price)}" }
        check("PCT_UP", up, stock.pctUp, { quote.changePct >= it }) { name + " 涨幅 %.2f%% 超过 %.2f%%".format(quote.changePct, it) }
        check("PCT_DOWN", down, stock.pctDown, { quote.changePct <= -it }) { name + " 跌幅 %.2f%% 超过 %.2f%%".format(quote.changePct, it) }
        holding?.profitPct?.let { pct ->
            check("PROFIT", up, stock.profitPct, { pct >= it }) { name + " 持仓盈利 %.2f%% 达到 %.2f%%".format(pct, it) }
            check("LOSS", down, stock.lossPct, { pct <= -it }) { name + " 持仓亏损 %.2f%% 达到 %.2f%%".format(pct, it) }
        }
        if (settings.limitAlert) {
            check("LIMIT_UP", up, quote.limitUp?.takeIf { it > 0 }, { price >= it }) { "$name 涨停 ${fmt.format(price)}" }
            check("LIMIT_DOWN", down, quote.limitDown?.takeIf { it > 0 }, { price <= it }) { "$name 跌停 ${fmt.format(price)}" }
        }
        if (settings.speedPct > 0) {
            val window = settings.speedWindowMin
            val change = speedChange(stock.code, price, nowMs, window * 60_000L)
            check("SPEED_UP", up, settings.speedPct, { change >= it }) { name + " ${window} 分钟急涨 %.2f%%，现价 ${fmt.format(price)}".format(change) }
            check("SPEED_DOWN", down, settings.speedPct, { change <= -it }) { name + " ${window} 分钟急跌 %.2f%%，现价 ${fmt.format(price)}".format(change) }
        }
        return events
    }

    @Synchronized
    fun testEvent(code: String, name: String, modes: List<String>, nowMs: Long, price: Double, direction: String): AlertEvent {
        val label = if (direction == AlertEvent.DOWN) "下跌" else "上涨"
        return AlertEvent(nextId++, code, name, "TEST", "$name ${label}提醒测试", price, nowMs, modes, isTest = true, direction = direction)
    }
}
