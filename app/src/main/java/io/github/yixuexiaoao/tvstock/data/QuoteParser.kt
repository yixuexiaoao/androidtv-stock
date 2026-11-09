package io.github.yixuexiaoao.tvstock.data

import io.github.yixuexiaoao.tvstock.model.KLine
import io.github.yixuexiaoao.tvstock.model.Level
import io.github.yixuexiaoao.tvstock.model.MinutePoint
import io.github.yixuexiaoao.tvstock.model.Quote
import io.github.yixuexiaoao.tvstock.model.SearchHit
import org.json.JSONObject

/** 行情接口响应解析（纯函数，便于用真实响应样本做单元测试）。 */
object QuoteParser {
    private val TENCENT_LINE = Regex("v_([a-z0-9]+)=\"([^\"]*)\"")
    private val SINA_LINE = Regex("hq_str_([a-z0-9]+)=\"([^\"]*)\"")
    private val UNICODE = Regex("\\\\u([0-9a-fA-F]{4})")

    private fun List<String>.num(i: Int): Double = getOrNull(i)?.trim()?.toDoubleOrNull() ?: 0.0
    private fun List<String>.numOrNull(i: Int): Double? = getOrNull(i)?.trim()?.toDoubleOrNull()

    private fun decimalsOf(raw: String?): Int {
        val fraction = raw?.substringAfter('.', "").orEmpty()
        return if (fraction.length >= 3 && fraction[2] != '0') 3 else 2
    }

    /** 腾讯：qt.gtimg.cn/q=sh600519,sz000001，字段以 ~ 分隔。 */
    fun parseTencent(text: String): List<Quote> = TENCENT_LINE.findAll(text).mapNotNull { m ->
        val f = m.groupValues[2].split('~')
        if (f.size < 35 || f[1].isBlank()) return@mapNotNull null
        val price = f.num(3)
        val prevClose = f.num(4)
        Quote(
            code = m.groupValues[1],
            name = f[1],
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

    /** 新浪：hq.sinajs.cn/list=sh600519，成交量单位为股、成交额单位为元。 */
    fun parseSina(text: String): List<Quote> = SINA_LINE.findAll(text).mapNotNull { m ->
        val f = m.groupValues[2].split(',')
        if (f.size < 32 || f[0].isBlank()) return@mapNotNull null
        val price = f.num(3)
        val prevClose = f.num(2)
        val change = if (price > 0) price - prevClose else 0.0
        Quote(
            code = m.groupValues[1],
            name = f[0],
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

    /** 搜索：v_hint="sh~600519~贵...~gzmt~GP-A^...；只保留沪深北市场。 */
    fun parseSuggest(text: String): List<SearchHit> {
        val body = text.substringAfter('"', "").substringBeforeLast('"', "")
        if (body.isBlank() || body == "N") return emptyList()
        return body.split('^').mapNotNull { item ->
            val f = item.split('~')
            if (f.size < 3 || f[0] !in setOf("sh", "sz", "bj")) return@mapNotNull null
            val name = UNICODE.replace(f[2]) { it.groupValues[1].toInt(16).toChar().toString() }
            SearchHit(f[0] + f[1], name, f.getOrElse(4) { "" })
        }
    }
}
