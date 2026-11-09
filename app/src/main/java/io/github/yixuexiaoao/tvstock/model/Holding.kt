package io.github.yixuexiaoao.tvstock.model

/** 持仓计算结果。成本优先级：成本价 > 持仓价 > 购买价。 */
data class Holding(
    val shares: Long,
    val cost: Double?,
    val marketValue: Double,
    val profit: Double?,
    val profitPct: Double?,
    val dayProfit: Double,
) {
    companion object {
        fun effectiveCost(stock: StockConfig): Double? = stock.costPrice ?: stock.holdPrice ?: stock.buyPrice

        /** 未填股数时没有持仓，返回 null。 */
        fun of(stock: StockConfig, quote: Quote?): Holding? {
            val shares = stock.shares ?: return null
            if (quote == null) return null
            val price = quote.displayPrice
            val cost = effectiveCost(stock)
            val profit = cost?.let { (price - it) * shares }
            val pct = cost?.takeIf { it > 0 }?.let { (price - it) / it * 100 }
            val dayChange = if (quote.hasTrade) price - quote.prevClose else 0.0
            return Holding(shares, cost, price * shares, profit, pct, dayChange * shares)
        }
    }
}

data class PortfolioSummary(
    val count: Int,
    val marketValue: Double,
    val profit: Double,
    val dayProfit: Double,
    val profitPct: Double?,
) {
    companion object {
        fun of(stocks: List<StockConfig>, quotes: Map<String, Quote>): PortfolioSummary? {
            val holdings = stocks.filter { it.enabled }.mapNotNull { Holding.of(it, quotes[it.code]) }
            if (holdings.isEmpty()) return null
            val value = holdings.sumOf { it.marketValue }
            val profit = holdings.sumOf { it.profit ?: 0.0 }
            val costed = holdings.filter { it.cost != null }
            val base = costed.sumOf { it.cost!! * it.shares }
            return PortfolioSummary(
                holdings.size, value, profit, holdings.sumOf { it.dayProfit },
                if (base > 0) profit / base * 100 else null,
            )
        }
    }
}
