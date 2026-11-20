package io.github.yixuexiaoao.tvstock.model

/** 首页顶部大视图：大盘云图 或 自选行情。 */
object HomeTabs {
    const val CLOUD = "CLOUD"
    const val WATCH = "WATCH"
    val ALL = listOf(CLOUD, WATCH)

    fun label(tab: String) = when (tab) {
        CLOUD -> "大盘云图"
        WATCH -> "自选行情"
        else -> tab
    }
}

/** 大盘云图范围。 */
object CloudScopes {
    const val SECTORS = "SECTORS"
    const val HS300 = "HS300"
    const val ALL_TOP = "ALL_TOP"
    const val CYB = "CYB"
    const val KCB = "KCB"
    val ALL = listOf(SECTORS, HS300, ALL_TOP, CYB, KCB)

    fun label(scope: String) = when (scope) {
        SECTORS -> "行业宏观"
        HS300 -> "沪深300"
        ALL_TOP -> "全A龙头"
        CYB -> "创业板"
        KCB -> "科创板"
        else -> scope
    }
}

/** 热力图色块面积权重。 */
object HeatmapWeights {
    const val CIRC_CAP = "CIRC_CAP"
    const val AMOUNT = "AMOUNT"
    const val HOLDING = "HOLDING"
    const val EQUAL = "EQUAL"
    val ALL = listOf(CIRC_CAP, AMOUNT, HOLDING, EQUAL)
    /** 大盘云图无持仓概念，仅支持流通市值与成交额。 */
    val CLOUD_MODES = listOf(CIRC_CAP, AMOUNT)

    fun label(weight: String) = when (weight) {
        CIRC_CAP -> "流通市值"
        AMOUNT -> "成交额"
        HOLDING -> "持仓市值"
        EQUAL -> "等权"
        else -> weight
    }
}

/** 全市场或单板块的涨跌家数与成交额汇总。 */
data class MarketBreadth(
    val upCount: Int = 0,
    val flatCount: Int = 0,
    val downCount: Int = 0,
    /** 总成交额（元） */
    val totalAmountYuan: Double = 0.0,
) {
    val totalCount: Int get() = upCount + flatCount + downCount
}

/** 热力图中的单个叶子节点（可以是一级行业块，也可以是个股块）。 */
data class HeatmapItem(
    /** 板块码（如 BK1283 / new_jrhy）或标准股票代码（如 sh600519 / usAAPL） */
    val id: String,
    val name: String,
    val changePct: Double,
    /** 用于决定色块面积的正数权重 */
    val weight: Double,
    /** 个股现价；行业板块可为 0 */
    val price: Double = 0.0,
    val decimals: Int = 2,
    /** 成交额（元） */
    val amountYuan: Double = 0.0,
    /** 流通市值（元） */
    val circCapYuan: Double = 0.0,
    /** 所属二级/一级行业名（分组嵌套时使用） */
    val groupName: String = "",
    /** 仅行业块有值：上涨/下跌/平盘家数与领涨股 */
    val upCount: Int = 0,
    val downCount: Int = 0,
    val flatCount: Int = 0,
    val leaderName: String = "",
    val leaderPct: Double = 0.0,
    /** true 表示这是一个可下钻的行业板块；false 表示可放大查看的个股 */
    val isSector: Boolean = false,
    /** 可选副标签（如自选股的持仓盈亏或代码简称） */
    val badge: String = "",
)

/** 二级嵌套分组（例如行业名称 + 该行业下的成分股列表）。 */
data class HeatmapGroup(
    val name: String,
    val items: List<HeatmapItem>,
    val changePct: Double,
    val weight: Double,
)

/** 一次大盘云图数据快照。 */
data class HeatmapSnapshot(
    val scope: String,
    /** 非空表示当前处于某行业板块下钻视图 */
    val sectorId: String? = null,
    val sectorName: String = "",
    val weightMode: String = HeatmapWeights.CIRC_CAP,
    val source: String = "",
    val fetchedAtMs: Long = 0L,
    val breadth: MarketBreadth = MarketBreadth(),
    /** 行业宏观视图时为 31 个一级行业；成分股/指数视图时为个股列表 */
    val items: List<HeatmapItem> = emptyList(),
    /** 成分股/指数视图按行业自动分组后的两级结构；行业宏观视图为空 */
    val groups: List<HeatmapGroup> = emptyList(),
) {
    val isGrouped: Boolean get() = groups.isNotEmpty()
}
