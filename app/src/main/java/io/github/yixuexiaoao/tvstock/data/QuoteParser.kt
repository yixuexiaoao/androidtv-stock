package io.github.yixuexiaoao.tvstock.data

import io.github.yixuexiaoao.tvstock.model.KLine
import io.github.yixuexiaoao.tvstock.model.Level
import io.github.yixuexiaoao.tvstock.model.MinutePoint
import io.github.yixuexiaoao.tvstock.model.Quote
import io.github.yixuexiaoao.tvstock.model.SearchHit
import io.github.yixuexiaoao.tvstock.model.StockCodes
import org.json.JSONObject

/** 行情接口响应解析（纯函数，便于用真实响应样本做单元测试）。 */
object QuoteParser {
    private val TENCENT_LINE = Regex("v_([A-Za-z0-9.]+)=\"([^\"]*)\"")
    private val SINA_LINE = Regex("hq_str_([A-Za-z0-9_\$.]+)=\"([^\"]*)\"")
    private val SPACES = Regex("\\s+")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private const val SINA_US_PREFIX = "gb_"
    /** 新浪的指数代码没有点，转回代码时要区分 */
    private val SINA_US_INDEXES = setOf("DJI", "IXIC", "INX")
    private val UNICODE = Regex("\\\\u([0-9a-fA-F]{4})")

    private fun List<String>.num(i: Int): Double = getOrNull(i)?.trim()?.toDoubleOrNull() ?: 0.0
    private fun List<String>.numOrNull(i: Int): Double? = getOrNull(i)?.trim()?.toDoubleOrNull()

    /** 接口用空格把名称补齐到固定宽度（如“五 粮 液”），显示时去掉。 */
    private fun cleanName(name: String) = name.replace(" ", "").replace("\u3000", "")

    private fun decimalsOf(raw: String?): Int {
        val fraction = raw?.substringAfter('.', "").orEmpty()
        return if (fraction.length >= 3 && fraction[2] != '0') 3 else 2
    }

    /** 腾讯：qt.gtimg.cn/q=sh600519,sz000001，字段以 ~ 分隔。 */
    fun parseTencent(text: String): List<Quote> = TENCENT_LINE.findAll(text).mapNotNull { m ->
        val f = m.groupValues[2].split('~')
        if (f.size < 35 || f[1].isBlank()) return@mapNotNull null
        if (StockCodes.isUs(m.groupValues[1])) return@mapNotNull parseTencentUs(m.groupValues[1], f)
        val price = f.num(3)
        val prevClose = f.num(4)
        Quote(
            code = m.groupValues[1],
            name = cleanName(f[1]),
            price = price,
            prevClose = prevClose,
            open = f.num(5),
            high = f.num(33),
            low = f.num(34),
            change = if (price > 0) f.num(31) else 0.0,
            changePct = if (price > 0) f.num(32) else 0.0,
            volume = f.num(36),
            amountWan = f.num(37),
            turnover = f.numOrNull(38),
            pe = f.numOrNull(39),
            amplitude = f.numOrNull(43),
            circCapYi = f.numOrNull(44),
            totalCapYi = f.numOrNull(45),
            pb = f.numOrNull(46),
            limitUp = f.numOrNull(47),
            limitDown = f.numOrNull(48),
            volumeRatio = f.numOrNull(49),
            bids = (0 until 5).map { Level(f.num(9 + it * 2), f.num(10 + it * 2)) },
            asks = (0 until 5).map { Level(f.num(19 + it * 2), f.num(20 + it * 2)) },
            time = f.getOrElse(30) { "" },
            decimals = decimalsOf(f[3]),
        )
    }.toList()

    /** 腾讯美股：字段顺序和 A 股不同；只有一档盘口，成交量为股，成交额为美元，时间为美东时间。 */
    private fun parseTencentUs(code: String, f: List<String>): Quote? {
        if (f.size < 46) return null
        val price = f.num(3)
        return Quote(
            code = code,
            name = cleanName(f[1]),
            price = price,
            prevClose = f.num(4),
            open = f.num(5),
            high = f.num(33),
            low = f.num(34),
            change = if (price > 0) f.num(31) else 0.0,
            changePct = if (price > 0) f.num(32) else 0.0,
            volume = f.num(36),
            amountWan = f.num(37) / 10000,
            turnover = f.numOrNull(38),
            pe = f.numOrNull(39),
            amplitude = f.numOrNull(43),
            circCapYi = f.numOrNull(44),
            totalCapYi = f.numOrNull(45),
            bids = listOf(Level(f.num(9), f.num(10))),
            asks = listOf(Level(f.num(19), f.num(20))),
            time = f[30].filter { it.isDigit() },
            decimals = usDecimals(price),
            exchange = f[2],
        )
    }

    /** 低价美股报价到小数点后 4 位。 */
    private fun usDecimals(price: Double) = if (price > 0 && price < 1) 4 else 2

    /** 新浪的代码：A 股不变，usAAPL -> gb_aapl，usBRK.B -> gb_brk$b，us.DJI -> gb_dji。 */
    fun sinaCode(code: String): String =
        if (StockCodes.isUs(code)) SINA_US_PREFIX + StockCodes.shortCode(code).lowercase().replace('.', '$') else code

    private fun fromSinaUsCode(sina: String): String {
        val ticker = sina.removePrefix(SINA_US_PREFIX).uppercase().replace('$', '.')
        return if (ticker in SINA_US_INDEXES) "us.$ticker" else "us$ticker"
    }

    /** "Nov 06 04:00PM EST" -> yyyyMMddHHmmss，无法解析时返回空。 */
    private fun sinaUsTime(stamp: String, year: String): String {
        val p = stamp.trim().split(SPACES)
        if (p.size < 3) return ""
        val month = MONTHS.indexOf(p[0].lowercase()) + 1
        val day = p[1].toIntOrNull()
        val hour = p[2].substringBefore(':').toIntOrNull()
        val minute = p[2].substringAfter(':', "").take(2).toIntOrNull()
        val y = year.toIntOrNull()
        if (month == 0 || day == null || hour == null || minute == null || y == null) return ""
        val hour24 = hour % 12 + if (p[2].endsWith("PM", ignoreCase = true)) 12 else 0
        return "%04d%02d%02d%02d%02d00".format(y, month, day, hour24, minute)
    }

    /** 新浪美股：hq.sinajs.cn/list=gb_aapl，没有盘口；价格是常规交易时段的价格。 */
    private fun parseSinaUs(raw: String, f: List<String>): Quote? {
        if (f.size < 30 || f[0].isBlank()) return null
        val price = f.num(1)
        return Quote(
            code = fromSinaUsCode(raw),
            name = cleanName(f[0]),
            price = price,
            prevClose = f.num(26),
            open = f.num(5),
            high = f.num(6),
            low = f.num(7),
            change = if (price > 0) f.num(4) else 0.0,
            changePct = if (price > 0) f.num(2) else 0.0,
            volume = f.num(10),
            amountWan = f.num(30) / 10000,
            pe = f.numOrNull(14),
            totalCapYi = f.numOrNull(12)?.let { it / 100_000_000 },
            time = sinaUsTime(f[25], f[29]),
            decimals = usDecimals(price),
        )
    }

    /** 新浪：hq.sinajs.cn/list=sh600519，成交量单位为股、成交额单位为元。 */
    fun parseSina(text: String): List<Quote> = SINA_LINE.findAll(text).mapNotNull { m ->
        val f = m.groupValues[2].split(',')
        if (m.groupValues[1].startsWith(SINA_US_PREFIX)) return@mapNotNull parseSinaUs(m.groupValues[1], f)
        if (f.size < 32 || f[0].isBlank()) return@mapNotNull null
        val price = f.num(3)
        val prevClose = f.num(2)
        val change = if (price > 0) price - prevClose else 0.0
        Quote(
            code = m.groupValues[1],
            name = cleanName(f[0]),
            price = price,
            prevClose = prevClose,
            open = f.num(1),
            high = f.num(4),
            low = f.num(5),
            change = change,
            changePct = if (price > 0 && prevClose > 0) change / prevClose * 100 else 0.0,
            volume = f.num(8) / 100,
            amountWan = f.num(9) / 10000,
            bids = (0 until 5).map { Level(f.num(11 + it * 2), f.num(10 + it * 2) / 100) },
            asks = (0 until 5).map { Level(f.num(21 + it * 2), f.num(20 + it * 2) / 100) },
            time = f[30].replace("-", "") + f[31].replace(":", ""),
            decimals = decimalsOf(f[3]),
        )
    }.toList()

    /** 分时：每条 "0930 1440.06 186 26785115.56"（时间 价格 累计量(手) 累计额(元)）。 */
    fun parseMinute(text: String, code: String): List<MinutePoint> {
        val arr = JSONObject(text).optJSONObject("data")?.optJSONObject(code)
            ?.optJSONObject("data")?.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val f = arr.optString(i).trim().split(' ')
            val price = f.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            if (f[0].length != 4) return@mapNotNull null
            MinutePoint(f[0], price, f.getOrNull(2)?.toDoubleOrNull() ?: 0.0, f.getOrNull(3)?.toDoubleOrNull() ?: 0.0)
        }
    }

    /** K 线：[日期, 开, 收, 高, 低, 量, ...]。个股为 qfq{period}，指数为 {period}。 */
    fun parseKline(text: String, code: String, period: String): List<KLine> {
        val node = JSONObject(text).optJSONObject("data")?.optJSONObject(code) ?: return emptyList()
        val arr = node.optJSONArray("qfq$period") ?: node.optJSONArray(period) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val row = arr.optJSONArray(i) ?: return@mapNotNull null
            val open = row.optString(1).toDoubleOrNull() ?: return@mapNotNull null
            val close = row.optString(2).toDoubleOrNull() ?: return@mapNotNull null
            val high = row.optString(3).toDoubleOrNull() ?: return@mapNotNull null
            val low = row.optString(4).toDoubleOrNull() ?: return@mapNotNull null
            KLine(row.optString(0), open, close, high, low, row.optString(5).toDoubleOrNull() ?: 0.0)
        }
    }

    /** 搜索：v_hint="sh~600519~贵...~gzmt~GP-A^us~aapl.oq~苹果~pg~GP^...；只保留沪深北和美股。 */
    fun parseSuggest(text: String): List<SearchHit> {
        val body = text.substringAfter('"', "").substringBeforeLast('"', "")
        if (body.isBlank() || body == "N") return emptyList()
        return body.split('^').mapNotNull { item ->
            val f = item.split('~')
            if (f.size < 3) return@mapNotNull null
            val type = f.getOrElse(4) { "" }
            val code = when (f[0]) {
                "sh", "sz", "bj" -> f[0] + f[1]
                // 指数返回的是 dji，没有点
                "us" -> if (type == "ZS") "us." + f[1].uppercase() else StockCodes.normalize(f[1])
                else -> null
            } ?: return@mapNotNull null
            val name = UNICODE.replace(f[2]) { it.groupValues[1].toInt(16).toChar().toString() }
            SearchHit(code, name, type)
        }
    }
}
