package io.github.yixuexiaoao.tvstock.model

import org.json.JSONArray
import org.json.JSONObject

/** 读取可选数字：null、空字符串、非法值都视为“未填写”。 */
fun JSONObject.optNum(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val value = when (val raw = opt(key)) {
        is Number -> raw.toDouble()
        is String -> raw.trim().toDoubleOrNull()
        else -> null
    }
    return value?.takeIf { !it.isNaN() && !it.isInfinite() }
}

/** 读取可选正数：0 和负数同样视为未填写（价格/股数不可能 ≤ 0）。 */
fun JSONObject.optPositive(key: String): Double? = optNum(key)?.takeIf { it > 0 }

private fun JSONArray.toStringList(): List<String> =
    (0 until length()).mapNotNull { i -> optString(i, "").takeIf { it.isNotBlank() } }

private fun Collection<String>.toJsonArray(): JSONArray = JSONArray().also { arr -> forEach { arr.put(it) } }

object StockCodes {
    private val PREFIXED = Regex("^(sh|sz|bj)(\\d{6})$")
    private val SUFFIXED = Regex("^(\\d{6})\\.(sh|sz|bj)$")
    private val PLAIN = Regex("^\\d{6}$")

    /** 把 600519 / SH600519 / 600519.SH 统一为 sh600519；非法输入返回 null。 */
    fun normalize(input: String?): String? {
        val text = input?.trim()?.lowercase() ?: return null
        PREFIXED.matchEntire(text)?.let { return text }
        SUFFIXED.matchEntire(text)?.let { return it.groupValues[2] + it.groupValues[1] }
        if (!PLAIN.matches(text)) return null
        val market = when {
            text.startsWith("92") -> "bj"
            text[0] == '6' || text[0] == '5' || text[0] == '9' -> "sh"
            text[0] == '0' || text[0] == '1' || text[0] == '2' || text[0] == '3' -> "sz"
            text[0] == '4' || text[0] == '8' -> "bj"
            else -> return null
        }
        return market + text
    }

    fun shortCode(code: String): String = code.removePrefix("sh").removePrefix("sz").removePrefix("bj")
}

/**
 * 单只股票的监控配置。除 code 外都可不填，不填时只显示行情、不做提醒和持仓计算。
 */
data class StockConfig(
    val code: String,
    val alias: String = "",
    /** 目标价：价格涨到 ≥ 该值时提醒 */
    val targetHigh: Double? = null,
    /** 下破价/止损价：价格跌到 ≤ 该值时提醒 */
    val targetLow: Double? = null,
    /** 涨幅提醒（%）：涨跌幅 ≥ 该值时提醒 */
    val pctUp: Double? = null,
    /** 跌幅提醒（%，填正数）：涨跌幅 ≤ -该值时提醒 */
    val pctDown: Double? = null,
    /** 持仓盈利提醒（%） */
    val profitPct: Double? = null,
    /** 持仓亏损提醒（%，填正数） */
    val lossPct: Double? = null,
    val buyPrice: Double? = null,
    val shares: Long? = null,
    val holdPrice: Double? = null,
    val costPrice: Double? = null,
    val buyDate: String = "",
    val note: String = "",
    /** 空表示跟随全局展示方式 */
    val displayMode: String = "",
    /** null 表示跟随全局提醒方式；空列表表示这只股票不提醒 */
    val alertModes: List<String>? = null,
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("code", code)
        put("alias", alias)
        putOpt("targetHigh", targetHigh)
        putOpt("targetLow", targetLow)
        putOpt("pctUp", pctUp)
        putOpt("pctDown", pctDown)
        putOpt("profitPct", profitPct)
        putOpt("lossPct", lossPct)
        putOpt("buyPrice", buyPrice)
        putOpt("shares", shares)
        putOpt("holdPrice", holdPrice)
        putOpt("costPrice", costPrice)
        put("buyDate", buyDate)
        put("note", note)
        put("displayMode", displayMode)
        alertModes?.let { put("alertModes", it.toJsonArray()) }
        put("enabled", enabled)
    }

    companion object {
        /** code 非法时返回 null。 */
        fun fromJson(json: JSONObject): StockConfig? {
            val code = StockCodes.normalize(json.optString("code")) ?: return null
            return StockConfig(
                code = code,
                alias = json.optString("alias", "").trim().take(32),
                targetHigh = json.optPositive("targetHigh"),
                targetLow = json.optPositive("targetLow"),
                pctUp = json.optPositive("pctUp"),
                pctDown = json.optPositive("pctDown"),
                profitPct = json.optPositive("profitPct"),
                lossPct = json.optPositive("lossPct"),
                buyPrice = json.optPositive("buyPrice"),
                shares = json.optPositive("shares")?.toLong(),
                holdPrice = json.optPositive("holdPrice"),
                costPrice = json.optPositive("costPrice"),
                buyDate = json.optString("buyDate", "").trim().take(16),
                note = json.optString("note", "").trim().take(200),
                displayMode = json.optString("displayMode", "").takeIf { it in DisplayModes.ALL }.orEmpty(),
                alertModes = if (json.isNull("alertModes")) null else json.optJSONArray("alertModes")
                    ?.toStringList()?.filter { it in AlertModes.ALL },
                enabled = json.optBoolean("enabled", true),
            )
        }
    }
}

object DisplayModes {
    const val MIXED = "MIXED"
    const val MINUTE = "MINUTE"
    const val KLINE = "KLINE"
    const val TEXT = "TEXT"
    val ALL = listOf(MIXED, MINUTE, KLINE, TEXT)
    fun label(mode: String) = when (mode) {
        MIXED -> "图文（分时+数据）"
        MINUTE -> "分时图"
        KLINE -> "K线图"
        TEXT -> "纯文字"
        else -> mode
    }
}

object AlertModes {
    const val CELL = "CELL"
    const val FULLSCREEN = "FULLSCREEN"
    const val BANNER = "BANNER"
    const val SOUND = "SOUND"
    val ALL = listOf(CELL, FULLSCREEN, BANNER, SOUND)
    fun label(mode: String) = when (mode) {
        CELL -> "窗口闪烁"
        FULLSCREEN -> "全屏闪烁"
        BANNER -> "顶部横幅"
        SOUND -> "声音"
        else -> mode
    }
}

/** 全部全局设置。新增字段只需写入默认值和 fromJson/toJson。 */
data class AppSettings(
    val layoutId: String = "4",
    val customLayout: GridLayoutDef? = null,
    val displayMode: String = DisplayModes.MIXED,
    val klinePeriod: String = "day",
    val klineCount: Int = 60,
    val refreshSec: Int = 3,
    val idleRefreshSec: Int = 60,
    val fontScale: Int = 100,
    val theme: String = "DARK",
    val colorScheme: String = "RED_UP",
    val gridGap: Int = 6,
    val screenMargin: Int = 0,
    val showHeader: Boolean = true,
    val showIndices: Boolean = true,
    val showClock: Boolean = true,
    val showPortfolio: Boolean = true,
    val showTicker: Boolean = false,
    val showStats: Boolean = true,
    val showHoldings: Boolean = true,
    val showTargetLines: Boolean = true,
    val privacy: Boolean = false,
    val priceTickFlash: Boolean = true,
    val pageIntervalSec: Int = 0,
    val burnInGuard: Boolean = false,
    val alertModes: List<String> = listOf(AlertModes.CELL, AlertModes.BANNER),
    val alertDurationSec: Int = 30,
    val flashSpeedMs: Int = 500,
    val flashColor: String = "#FF1744",
    val alertRepeatMin: Int = 0,
    val alertTradingOnly: Boolean = true,
    val alertJumpPage: Boolean = true,
    val source: String = "AUTO",
    val autostart: Boolean = false,
    val keepServiceOnExit: Boolean = true,
    val webPort: Int = 8080,
    val webPin: String = "",
) {
    fun effectiveLayout(): GridLayoutDef = Layouts.resolve(layoutId, customLayout)

    fun toJson(): JSONObject = JSONObject().apply {
        put("layoutId", layoutId)
        customLayout?.let { put("customLayout", it.toJson()) }
        put("displayMode", displayMode)
        put("klinePeriod", klinePeriod)
        put("klineCount", klineCount)
        put("refreshSec", refreshSec)
        put("idleRefreshSec", idleRefreshSec)
        put("fontScale", fontScale)
        put("theme", theme)
        put("colorScheme", colorScheme)
        put("gridGap", gridGap)
        put("screenMargin", screenMargin)
        put("showHeader", showHeader)
        put("showIndices", showIndices)
        put("showClock", showClock)
        put("showPortfolio", showPortfolio)
        put("showTicker", showTicker)
        put("showStats", showStats)
        put("showHoldings", showHoldings)
        put("showTargetLines", showTargetLines)
        put("privacy", privacy)
        put("priceTickFlash", priceTickFlash)
        put("pageIntervalSec", pageIntervalSec)
        put("burnInGuard", burnInGuard)
        put("alertModes", alertModes.toJsonArray())
        put("alertDurationSec", alertDurationSec)
        put("flashSpeedMs", flashSpeedMs)
        put("flashColor", flashColor)
        put("alertRepeatMin", alertRepeatMin)
        put("alertTradingOnly", alertTradingOnly)
        put("alertJumpPage", alertJumpPage)
        put("source", source)
        put("autostart", autostart)
        put("keepServiceOnExit", keepServiceOnExit)
        put("webPort", webPort)
        put("webPin", webPin)
    }

    /** 把 json 中出现的字段合并到当前设置，并把所有值限制到安全范围。 */
    fun merged(json: JSONObject): AppSettings {
        fun int(key: String, cur: Int, min: Int, max: Int): Int =
            (json.optNum(key)?.toInt() ?: cur).coerceIn(min, max)
        fun bool(key: String, cur: Boolean) = if (json.has(key)) json.optBoolean(key, cur) else cur
        fun choice(key: String, cur: String, allowed: Collection<String>): String =
            json.optString(key, cur).takeIf { it in allowed } ?: cur

        return copy(
            layoutId = json.optString("layoutId", layoutId).takeIf { it == Layouts.CUSTOM_ID || Layouts.byId(it) != null } ?: layoutId,
            customLayout = json.optJSONObject("customLayout")?.let { GridLayoutDef.fromJson(it) } ?: customLayout,
            displayMode = choice("displayMode", displayMode, DisplayModes.ALL),
            klinePeriod = choice("klinePeriod", klinePeriod, listOf("day", "week", "month")),
            klineCount = int("klineCount", klineCount, 20, 250),
            refreshSec = int("refreshSec", refreshSec, 1, 600),
            idleRefreshSec = int("idleRefreshSec", idleRefreshSec, 5, 3600),
            fontScale = int("fontScale", fontScale, 50, 250),
            theme = choice("theme", theme, Themes.ALL),
            colorScheme = choice("colorScheme", colorScheme, listOf("RED_UP", "GREEN_UP")),
            gridGap = int("gridGap", gridGap, 0, 32),
            screenMargin = int("screenMargin", screenMargin, 0, 64),
            showHeader = bool("showHeader", showHeader),
            showIndices = bool("showIndices", showIndices),
            showClock = bool("showClock", showClock),
            showPortfolio = bool("showPortfolio", showPortfolio),
            showTicker = bool("showTicker", showTicker),
            showStats = bool("showStats", showStats),
            showHoldings = bool("showHoldings", showHoldings),
            showTargetLines = bool("showTargetLines", showTargetLines),
            privacy = bool("privacy", privacy),
            priceTickFlash = bool("priceTickFlash", priceTickFlash),
            pageIntervalSec = int("pageIntervalSec", pageIntervalSec, 0, 3600),
            burnInGuard = bool("burnInGuard", burnInGuard),
            alertModes = json.optJSONArray("alertModes")?.toStringList()?.filter { it in AlertModes.ALL } ?: alertModes,
            alertDurationSec = int("alertDurationSec", alertDurationSec, 0, 3600),
            flashSpeedMs = int("flashSpeedMs", flashSpeedMs, 150, 3000),
            flashColor = json.optString("flashColor", flashColor).takeIf { COLOR.matches(it) } ?: flashColor,
            alertRepeatMin = int("alertRepeatMin", alertRepeatMin, 0, 1440),
            alertTradingOnly = bool("alertTradingOnly", alertTradingOnly),
            alertJumpPage = bool("alertJumpPage", alertJumpPage),
            source = choice("source", source, listOf("AUTO", "TENCENT", "SINA")),
            autostart = bool("autostart", autostart),
            keepServiceOnExit = bool("keepServiceOnExit", keepServiceOnExit),
            webPort = int("webPort", webPort, 1024, 65535),
            webPin = if (json.has("webPin")) json.optString("webPin", "").trim().take(32) else webPin,
        )
    }

    private companion object {
        val COLOR = Regex("^#[0-9a-fA-F]{6}$")
    }
}

object Themes {
    const val DARK = "DARK"
    const val BLACK = "BLACK"
    const val BLUE = "BLUE"
    const val LIGHT = "LIGHT"
    val ALL = listOf(DARK, BLACK, BLUE, LIGHT)
    fun label(theme: String) = when (theme) {
        DARK -> "深色"
        BLACK -> "纯黑"
        BLUE -> "深蓝"
        LIGHT -> "浅色"
        else -> theme
    }
}

data class AppConfig(
    val settings: AppSettings = AppSettings(),
    val stocks: List<StockConfig> = DEFAULT_STOCKS,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("settings", settings.toJson())
        put("stocks", JSONArray().also { arr -> stocks.forEach { arr.put(it.toJson()) } })
    }

    companion object {
        /** 首次启动的示例自选，可在后台删除。 */
        val DEFAULT_STOCKS = listOf(
            StockConfig("sh000001"),
            StockConfig("sz399006"),
            StockConfig("sh600519"),
            StockConfig("sz300750"),
        )

        fun fromJson(json: JSONObject): AppConfig {
            val settings = json.optJSONObject("settings")?.let { AppSettings().merged(it) } ?: AppSettings()
            val stocks = json.optJSONArray("stocks")?.let { arr ->
                (0 until arr.length())
                    .mapNotNull { i -> arr.optJSONObject(i)?.let { StockConfig.fromJson(it) } }
                    .distinctBy { it.code }
            } ?: DEFAULT_STOCKS
            return AppConfig(settings, stocks)
        }
    }
}

/** 一档盘口：价格 + 数量（手）。 */
data class Level(val price: Double, val volume: Double)

data class Quote(
    val code: String,
    val name: String,
    /** 最新价；未成交或停牌时为 0 */
    val price: Double,
    val prevClose: Double,
    val open: Double,
    val high: Double,
    val low: Double,
    val change: Double,
    val changePct: Double,
    /** 成交量（手） */
    val volume: Double,
    /** 成交额（万元） */
    val amountWan: Double,
    val turnover: Double? = null,
    val pe: Double? = null,
    val pb: Double? = null,
    val amplitude: Double? = null,
    val volumeRatio: Double? = null,
    val totalCapYi: Double? = null,
    val circCapYi: Double? = null,
    val limitUp: Double? = null,
    val limitDown: Double? = null,
    val bids: List<Level> = emptyList(),
    val asks: List<Level> = emptyList(),
    /** yyyyMMddHHmmss，北京时间 */
    val time: String = "",
    val decimals: Int = 2,
) {
    val hasTrade: Boolean get() = price > 0
    /** 用于显示：未成交时回退到昨收。 */
    val displayPrice: Double get() = if (hasTrade) price else prevClose
    val tradeDate: String get() = time.take(8)
}

data class MinutePoint(val hhmm: String, val price: Double, val cumVolume: Double, val cumAmount: Double) {
    val avgPrice: Double get() = if (cumVolume > 0) cumAmount / (cumVolume * 100) else 0.0
}

data class KLine(
    val date: String,
    val open: Double,
    val close: Double,
    val high: Double,
    val low: Double,
    val volume: Double,
)

data class SearchHit(val code: String, val name: String, val type: String)

data class AlertEvent(
    val id: Long,
    val code: String,
    val name: String,
    val kind: String,
    val message: String,
    val price: Double,
    val timeMs: Long,
    val modes: List<String>,
    val isTest: Boolean = false,
)
