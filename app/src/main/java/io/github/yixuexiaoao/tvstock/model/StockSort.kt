package io.github.yixuexiaoao.tvstock.model

import kotlin.math.abs

/** 盯盘排序：没有行情的股票排在最后，同值保持自选顺序。 */
object StockSort {
    const val MANUAL = "MANUAL"
    const val PCT_DESC = "PCT_DESC"
    const val PCT_ASC = "PCT_ASC"
    const val ACTIVE = "ACTIVE"
    const val PROFIT = "PROFIT"
    val ALL = listOf(MANUAL, PCT_DESC, PCT_ASC, ACTIVE, PROFIT)

    fun label(mode: String) = when (mode) {
        MANUAL -> "自选顺序"
        PCT_DESC -> "涨幅从高到低"
        PCT_ASC -> "跌幅从高到低"
        ACTIVE -> "异动优先（涨跌幅绝对值）"
        PROFIT -> "今日持仓盈亏（绝对值）"
        else -> mode
    }

    fun apply(stocks: List<StockConfig>, quotes: Map<String, Quote>, mode: String): List<StockConfig> {
        if (mode == MANUAL) return stocks
        fun key(s: StockConfig): Double? {
            val q = quotes[s.code] ?: return null
            return when (mode) {
                PCT_DESC -> -q.changePct
                PCT_ASC -> q.changePct
                ACTIVE -> -abs(q.changePct)
                PROFIT -> -abs(Holding.of(s, q)?.dayProfit ?: 0.0)
                else -> 0.0
            }
        }
        return stocks.sortedWith(compareBy(nullsLast()) { key(it) })
    }
}
