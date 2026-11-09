package io.github.yixuexiaoao.tvstock.model

/** 持仓计算结果。*/
data class Holding(
    val shares: Long,
    val cost: Double?,
    val marketValue: Double,
    val profit: Double?,
    val profitPct: Double?,
    val dayProfit: Double,
) {
    companion object {
        /** 未填股数时没有持仓，返回 null。 */
        fun of(stock: StockConfig, quote: Quote?): Holding? {
            val shares = stock.shares ?: return null
            if (quote == null) return null
            val price = quote.displayPrice
            val cost = stock.costPrice
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
    /** 全部启用股票累计已实现盈亏（含已清仓的） */
    val realized: Double = 0.0,
) {
    companion object {
        fun of(stocks: List<StockConfig>, quotes: Map<String, Quote>): PortfolioSummary? {
            val enabled = stocks.filter { it.enabled }
            val holdings = enabled.mapNotNull { Holding.of(it, quotes[it.code]) }
            val realized = enabled.sumOf { it.realizedProfit }
            if (holdings.isEmpty() && realized == 0.0) return null
            val value = holdings.sumOf { it.marketValue }
            val profit = holdings.sumOf { it.profit ?: 0.0 }
            val costed = holdings.filter { it.cost != null }
            val base = costed.sumOf { it.cost!! * it.shares }
            return PortfolioSummary(
                holdings.size, value, profit, holdings.sumOf { it.dayProfit },
                if (base > 0) profit / base * 100 else null,
                realized,
            )
        }
    }
}
