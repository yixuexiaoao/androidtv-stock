package io.github.yixuexiaoao.tvstock.data

import io.github.yixuexiaoao.tvstock.model.KLine
import io.github.yixuexiaoao.tvstock.model.MinutePoint
import io.github.yixuexiaoao.tvstock.model.Quote
import io.github.yixuexiaoao.tvstock.model.SearchHit
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import javax.net.ssl.SSLException

/** 公开行情接口的 HTTP 访问。腾讯为主、新浪备用。 */
object QuoteApi {
    private const val BATCH = 50
    private val GBK: Charset = Charset.forName("GBK")

    private fun fetchOnce(url: String, charset: Charset, referer: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AndroidTV-Stock")
            referer?.let { conn.setRequestProperty("Referer", it) }
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }.toString(charset)
        } finally {
            conn.disconnect()
        }
    }

    /** 老电视常因根证书过旧或系统时间错误导致 TLS 失败，此时回退 HTTP。 */
    private fun fetch(host: String, pathAndQuery: String, charset: Charset, referer: String? = null): String =
        try {
            fetchOnce("https://$host$pathAndQuery", charset, referer)
        } catch (e: SSLException) {
            fetchOnce("http://$host$pathAndQuery", charset, referer)
        }

    private fun tencent(codes: List<String>): List<Quote> =
        codes.chunked(BATCH).flatMap { QuoteParser.parseTencent(fetch("qt.gtimg.cn", "/q=" + it.joinToString(","), GBK)) }

    private fun sina(codes: List<String>): List<Quote> = codes.chunked(BATCH).flatMap {
        QuoteParser.parseSina(fetch("hq.sinajs.cn", "/list=" + it.joinToString(","), GBK, "https://finance.sina.com.cn"))
    }

    /** source：AUTO / TENCENT / SINA。AUTO 在腾讯失败或无数据时切换新浪。 */
    fun quotes(codes: List<String>, source: String): List<Quote> {
        if (codes.isEmpty()) return emptyList()
        return when (source) {
            "TENCENT" -> tencent(codes)
            "SINA" -> sina(codes)
            else -> try {
                tencent(codes).ifEmpty { sina(codes) }
            } catch (e: IOException) {
                sina(codes)
            }
        }
    }

    fun minute(code: String): List<MinutePoint> =
        QuoteParser.parseMinute(fetch("web.ifzq.gtimg.cn", "/appstock/app/minute/query?code=$code", Charsets.UTF_8), code)

    fun kline(code: String, period: String, count: Int): List<KLine> = QuoteParser.parseKline(
        fetch("web.ifzq.gtimg.cn", "/appstock/app/fqkline/get?param=$code,$period,,,$count,qfq", Charsets.UTF_8),
        code, period,
    )

    fun search(keyword: String): List<SearchHit> {
        val q = URLEncoder.encode(keyword, "UTF-8")
        return QuoteParser.parseSuggest(fetch("smartbox.gtimg.cn", "/s3/?v=2&q=$q&t=all", Charsets.UTF_8))
    }
}
