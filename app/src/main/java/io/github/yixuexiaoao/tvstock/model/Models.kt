package io.github.yixuexiaoao.tvstock.model

import io.github.yixuexiaoao.tvstock.alert.SoundPresets
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
    val shares: Long? = null,
    val costPrice: Double? = null,
    /** 减仓、清仓累计实现的盈亏 */
    val realizedProfit: Double = 0.0,
    val trades: List<TradeRecord> = emptyList(),
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
        putOpt("shares", shares)
        putOpt("costPrice", costPrice)
        put("realizedProfit", realizedProfit)
        if (trades.isNotEmpty()) put("trades", TradeRecord.listToJson(trades))
        put("buyDate", buyDate)
        put("note", note)
        put("displayMode", displayMode)
        alertModes?.let { put("alertModes", it.toJsonArray()) }
        put("enabled", enabled)
    }

    /** 把 json 中出现的字段覆盖到当前配置，值为 null 或空表示清除；没出现的字段保持不变。 */
    fun merged(json: JSONObject): StockConfig {
        val base = toJson()
        json.keys().forEach { key -> if (key != "code") base.put(key, json.get(key)) }
        return fromJson(base) ?: this
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
                shares = json.optPositive("shares")?.toLong(),
                // 旧版本有购买价、持仓价，合并进成本价
                costPrice = json.optPositive("costPrice") ?: json.optPositive("holdPrice") ?: json.optPositive("buyPrice"),
                realizedProfit = json.optNum("realizedProfit") ?: 0.0,
                trades = TradeRecord.listFromJson(json.optJSONArray("trades")),
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
    val dayStart: String = "07:00",
    val nightStart: String = "19:00",
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
    val flashColorDown: String = "#00C853",
    val soundUp: String = SoundPresets.RISE,
    val soundDown: String = SoundPresets.FALL,
    val soundVolume: Int = 80,
    val soundRepeat: Int = 1,
    /** 急涨急跌提醒阈值（%），0 为关闭 */
    val speedPct: Double = 0.0,
    val speedWindowMin: Int = 5,
    val limitAlert: Boolean = false,
    val sortMode: String = StockSort.MANUAL,
    val alertRepeatMin: Int = 0,
    val alertTradingOnly: Boolean = true,
    val alertJumpPage: Boolean = true,
    val source: String = "AUTO",
    val autostart: Boolean = false,
    val keepServiceOnExit: Boolean = true,
    val webPort: Int = DEFAULT_WEB_PORT,
    val webPin: String = "",
    val updateChannel: String = "AUTO",
    val updateAutoCheck: Boolean = true,
    val logVerbose: Boolean = false,
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
        put("dayStart", dayStart)
        put("nightStart", nightStart)
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
        put("flashColorDown", flashColorDown)
        put("soundUp", soundUp)
        put("soundDown", soundDown)
        put("soundVolume", soundVolume)
        put("soundRepeat", soundRepeat)
        put("speedPct", speedPct)
        put("speedWindowMin", speedWindowMin)
        put("limitAlert", limitAlert)
        put("sortMode", sortMode)
        put("alertRepeatMin", alertRepeatMin)
        put("alertTradingOnly", alertTradingOnly)
        put("alertJumpPage", alertJumpPage)
        put("source", source)
        put("autostart", autostart)
        put("keepServiceOnExit", keepServiceOnExit)
        put("webPort", webPort)
        put("webPin", webPin)
        put("updateChannel", updateChannel)
        put("updateAutoCheck", updateAutoCheck)
        put("logVerbose", logVerbose)
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
            dayStart = json.optString("dayStart", dayStart).takeIf { Themes.parseTime(it) != null } ?: dayStart,
            nightStart = json.optString("nightStart", nightStart).takeIf { Themes.parseTime(it) != null } ?: nightStart,
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
            flashColorDown = json.optString("flashColorDown", flashColorDown).takeIf { COLOR.matches(it) } ?: flashColorDown,
            soundUp = choice("soundUp", soundUp, SoundPresets.ALL),
            soundDown = choice("soundDown", soundDown, SoundPresets.ALL),
            soundVolume = int("soundVolume", soundVolume, 0, 100),
            soundRepeat = int("soundRepeat", soundRepeat, 1, 5),
            speedPct = (json.optNum("speedPct") ?: speedPct).coerceIn(0.0, 30.0),
            speedWindowMin = int("speedWindowMin", speedWindowMin, 1, 30),
            limitAlert = bool("limitAlert", limitAlert),
            sortMode = choice("sortMode", sortMode, StockSort.ALL),
            alertRepeatMin = int("alertRepeatMin", alertRepeatMin, 0, 1440),
            alertTradingOnly = bool("alertTradingOnly", alertTradingOnly),
            alertJumpPage = bool("alertJumpPage", alertJumpPage),
            source = choice("source", source, listOf("AUTO", "TENCENT", "SINA")),
            autostart = bool("autostart", autostart),
            keepServiceOnExit = bool("keepServiceOnExit", keepServiceOnExit),
            webPort = int("webPort", webPort, 1024, 65535),
            webPin = if (json.has("webPin")) json.optString("webPin", "").trim().take(32) else webPin,
            updateChannel = choice("updateChannel", updateChannel, listOf("AUTO", "STABLE", "PRE")),
            updateAutoCheck = bool("updateAutoCheck", updateAutoCheck),
            logVerbose = bool("logVerbose", logVerbose),
        )
    }

    companion object {
        const val DEFAULT_WEB_PORT = 58080
        const val LEGACY_WEB_PORT = 8080
        private val COLOR = Regex("^#[0-9a-fA-F]{6}$")
    }
}

object Themes {
    const val DARK = "DARK"
    const val BLACK = "BLACK"
    const val BLUE = "BLUE"
    const val LIGHT = "LIGHT"
    const val NIGHT = "NIGHT"
    const val AUTO = "AUTO"
    val ALL = listOf(AUTO, LIGHT, NIGHT, DARK, BLACK, BLUE)
    private val TIME = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")

    /** HH:mm 转为当天第几分钟，格式错误返回 null。 */
    fun parseTime(text: String): Int? =
        TIME.matchEntire(text)?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }

    /** 把 AUTO 解析为白天或夜晚主题；支持白天时段跨越 0 点。开始时间相同时视为全天白天。 */
    fun resolve(theme: String, minuteOfDay: Int, dayStart: String, nightStart: String): String {
        if (theme != AUTO) return theme
        val day = parseTime(dayStart) ?: 7 * 60
        val night = parseTime(nightStart) ?: 19 * 60
        val isDay = when {
            day == night -> true
            day < night -> minuteOfDay in day until night
            else -> minuteOfDay >= day || minuteOfDay < night
        }
        return if (isDay) LIGHT else NIGHT
    }

    fun label(theme: String) = when (theme) {
        DARK -> "深色"
        BLACK -> "纯黑"
        BLUE -> "深蓝"
        LIGHT -> "白天（浅色）"
        NIGHT -> "夜晚（低亮度）"
        AUTO -> "自动（按时间切换白天/夜晚）"
        else -> theme
    }
}

data class AppConfig(
    val settings: AppSettings = AppSettings(),
    val stocks: List<StockConfig> = DEFAULT_STOCKS,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", CONFIG_VERSION)
        put("settings", settings.toJson())
        put("stocks", JSONArray().also { arr -> stocks.forEach { arr.put(it.toJson()) } })
    }

    companion object {
        const val CONFIG_VERSION = 2

        /** 首次启动的示例自选，可在后台删除。 */
        val DEFAULT_STOCKS = listOf(
            StockConfig("sh000001"),
            StockConfig("sz399006"),
            StockConfig("sh600519"),
            StockConfig("sz300750"),
        )

        fun fromJson(json: JSONObject): AppConfig {
            var settings = json.optJSONObject("settings")?.let { AppSettings().merged(it) } ?: AppSettings()
            // 旧版默认端口迁移到新默认端口；用户在新版里主动改回 8080 不受影响
            if (json.optInt("version", 1) < 2 && settings.webPort == AppSettings.LEGACY_WEB_PORT) {
                settings = settings.copy(webPort = AppSettings.DEFAULT_WEB_PORT)
            }
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
    /** UP 或 DOWN，决定声音和闪烁颜色 */
    val direction: String = AlertEvent.UP,
) {
    companion object {
        const val UP = "UP"
        const val DOWN = "DOWN"
    }
}
