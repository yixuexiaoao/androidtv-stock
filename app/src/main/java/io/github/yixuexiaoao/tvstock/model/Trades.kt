package io.github.yixuexiaoao.tvstock.model

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.round

/** 记账操作的业务错误，message 可直接显示给用户。 */
class TradeError(message: String) : IllegalArgumentException(message)

/** 一笔记账记录，保存操作前的状态以便撤销。这里只是本地记账，不会产生真实交易。 */
data class TradeRecord(
    val timeMs: Long,
    val action: String,
    val price: Double,
    val shares: Long,
    /** 本笔实现的盈亏；加仓或没有成本价时为 null */
    val profit: Double?,
    val prevShares: Long?,
    val prevCost: Double?,
    val prevRealized: Double,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("timeMs", timeMs)
        put("action", action)
        put("price", price)
        put("shares", shares)
        putOpt("profit", profit)
        putOpt("prevShares", prevShares)
        putOpt("prevCost", prevCost)
        put("prevRealized", prevRealized)
    }

    companion object {
        fun fromJson(json: JSONObject): TradeRecord? {
            val action = json.optString("action")
            if (action !in Trades.ACTIONS) return null
            return TradeRecord(
                timeMs = json.optLong("timeMs", 0),
                action = action,
                price = json.optNum("price") ?: return null,
                shares = json.optNum("shares")?.toLong() ?: return null,
                profit = json.optNum("profit"),
                prevShares = json.optNum("prevShares")?.toLong(),
                prevCost = json.optNum("prevCost"),
                prevRealized = json.optNum("prevRealized") ?: 0.0,
            )
        }

        fun listToJson(list: List<TradeRecord>): JSONArray = JSONArray().also { arr -> list.forEach { arr.put(it.toJson()) } }

        fun listFromJson(arr: JSONArray?): List<TradeRecord> =
            if (arr == null) emptyList()
            else (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { fromJson(it) } }
    }
}

/** 加仓、减仓、清仓的持仓计算（纯函数）。 */
object Trades {
    const val BUY = "BUY"
    const val SELL = "SELL"
    const val CLEAR = "CLEAR"
    val ACTIONS = listOf(BUY, SELL, CLEAR)
    private const val MAX_RECORDS = 50

    fun label(action: String) = when (action) {
        BUY -> "加仓"
        SELL -> "减仓"
        CLEAR -> "清仓"
        else -> action
    }

    private fun roundCost(value: Double) = round(value * 10000) / 10000

    /**
     * 加仓：成本按加权平均摊薄（未填成本价时把本次价格当作原成本）。
     * 减仓/清仓：成本不变，差价计入已实现盈亏；股数归零后清空成本。
     * 手续费和印花税不计入。
     */
    fun apply(stock: StockConfig, action: String, price: Double, shares: Long?, timeMs: Long): StockConfig {
        if (!(price > 0) || price.isInfinite()) throw TradeError("价格无效")
        val held = stock.shares ?: 0L
        val cost = stock.costPrice
        fun record(qty: Long, profit: Double?) = TradeRecord(timeMs, action, price, qty, profit, stock.shares, cost, stock.realizedProfit)
        fun history(r: TradeRecord) = (stock.trades + r).takeLast(MAX_RECORDS)

        return when (action) {
            BUY -> {
                val qty = shares ?: 0L
                if (qty <= 0) throw TradeError("请输入加仓股数")
                val total = held + qty
                val newCost = roundCost(((cost ?: price) * held + price * qty) / total)
                stock.copy(shares = total, costPrice = newCost, trades = history(record(qty, null)))
            }
            SELL, CLEAR -> {
                if (held <= 0) throw TradeError("当前没有持仓")
                val qty = if (action == CLEAR) held else shares ?: 0L
                if (qty <= 0) throw TradeError("请输入减仓股数")
                if (qty > held) throw TradeError("减仓股数不能超过持股数 $held")
                val profit = cost?.let { (price - it) * qty }
                val remaining = held - qty
                stock.copy(
                    shares = remaining.takeIf { it > 0 },
                    costPrice = if (remaining > 0) cost else null,
                    realizedProfit = stock.realizedProfit + (profit ?: 0.0),
                    trades = history(record(qty, profit)),
                )
            }
            else -> throw TradeError("不支持的操作")
        }
    }

    /** 撤销最近一笔；没有记录时报错。 */
    fun undo(stock: StockConfig): StockConfig {
        val last = stock.trades.lastOrNull() ?: throw TradeError("没有可撤销的记录")
        return stock.copy(
            shares = last.prevShares,
            costPrice = last.prevCost,
            realizedProfit = last.prevRealized,
            trades = stock.trades.dropLast(1),
        )
    }
}
