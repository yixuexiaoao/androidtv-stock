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
    private data class Armed(var active: Boolean, var lastFiredMs: Long)

    private val states = HashMap<String, Armed>()
    private var nextId = 1L

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

        fun check(kind: String, threshold: Double?, met: (Double) -> Boolean, message: (Double) -> String) {
            if (threshold == null) return
            // key 含阈值：用户改了目标价后可重新触发
            val state = states.getOrPut("${stock.code}|$kind|$threshold") { Armed(false, 0) }
            if (!met(threshold)) {
                state.active = false
                return
            }
            if (stale) return
            val repeat = settings.alertRepeatMin
            val due = !state.active || (repeat > 0 && nowMs - state.lastFiredMs >= repeat * 60_000L)
            state.active = true
            if (due) {
                state.lastFiredMs = nowMs
                events += AlertEvent(nextId++, stock.code, name, kind, message(threshold), price, nowMs, modes)
            }
        }

        check("TARGET_HIGH", stock.targetHigh, { price >= it }) { "$name 达到目标价 ${fmt.format(it)}，现价 ${fmt.format(price)}" }
        check("TARGET_LOW", stock.targetLow, { price <= it }) { "$name 跌破提醒价 ${fmt.format(it)}，现价 ${fmt.format(price)}" }
        check("PCT_UP", stock.pctUp, { quote.changePct >= it }) { "$name 涨幅 %.2f%% 超过 %.2f%%".format(quote.changePct, it) }
        check("PCT_DOWN", stock.pctDown, { quote.changePct <= -it }) { "$name 跌幅 %.2f%% 超过 %.2f%%".format(quote.changePct, it) }
        holding?.profitPct?.let { pct ->
            check("PROFIT", stock.profitPct, { pct >= it }) { "$name 持仓盈利 %.2f%% 达到 %.2f%%".format(pct, it) }
            check("LOSS", stock.lossPct, { pct <= -it }) { "$name 持仓亏损 %.2f%% 达到 %.2f%%".format(pct, it) }
        }
        return events
    }

    @Synchronized
    fun testEvent(code: String, name: String, modes: List<String>, nowMs: Long, price: Double): AlertEvent =
        AlertEvent(nextId++, code, name, "TEST", "$name 提醒测试", price, nowMs, modes, isTest = true)
}
