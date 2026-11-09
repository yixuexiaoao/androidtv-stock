package io.github.yixuexiaoao.tvstock.data

import io.github.yixuexiaoao.tvstock.log.AppLog
import io.github.yixuexiaoao.tvstock.model.KLine
import io.github.yixuexiaoao.tvstock.model.MinutePoint
import io.github.yixuexiaoao.tvstock.model.Quote
import io.github.yixuexiaoao.tvstock.model.SearchHit
import io.github.yixuexiaoao.tvstock.model.StockCodes
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
    private val sslFallbackHosts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var sinaFallbackActive = false

    private fun fetchOnce(url: String, charset: Charset, referer: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 6000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AndroidTV-Stock")
        referer?.let { conn.setRequestProperty("Referer", it) }
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IOException("HTTP $code")
        }
        // 读完并关闭就能复用连接；调用 disconnect 的话，每次刷新都要重新做 TLS 握手
        return conn.inputStream.use { it.readBytes() }.toString(charset)
    }

    /** 老电视常因根证书过旧或系统时间错误导致 TLS 失败，此时回退 HTTP。 */
    private fun fetch(host: String, pathAndQuery: String, charset: Charset, referer: String? = null): String =
        try {
            fetchOnce("https://$host$pathAndQuery", charset, referer)
        } catch (e: SSLException) {
            if (sslFallbackHosts.add(host)) AppLog.w("Quote", "$host 的 HTTPS 不可用，回退 HTTP（检查电视系统时间）", e)
            fetchOnce("http://$host$pathAndQuery", charset, referer)
        }

    private fun tencent(codes: List<String>): List<Quote> =
        codes.chunked(BATCH).flatMap { QuoteParser.parseTencent(fetch("qt.gtimg.cn", "/q=" + it.joinToString(","), GBK)) }

    private fun sina(codes: List<String>): List<Quote> = codes.chunked(BATCH).flatMap {
        val list = it.joinToString(",") { code -> QuoteParser.sinaCode(code) }
        QuoteParser.parseSina(fetch("hq.sinajs.cn", "/list=$list", GBK, "https://finance.sina.com.cn"))
    }

    /** source：AUTO / TENCENT / SINA。AUTO 在腾讯失败或无数据时切换新浪。 */
    fun quotes(codes: List<String>, source: String): List<Quote> {
        if (codes.isEmpty()) return emptyList()
        return when (source) {
            "TENCENT" -> tencent(codes)
            "SINA" -> sina(codes)
            else -> try {
                val list = tencent(codes)
                if (sinaFallbackActive && list.isNotEmpty()) {
                    sinaFallbackActive = false
                    AppLog.i("Quote", "腾讯行情恢复")
                }
                list.ifEmpty { sina(codes) }
            } catch (e: IOException) {
                if (!sinaFallbackActive) AppLog.w("Quote", "腾讯行情失败，切换新浪", e)
                sinaFallbackActive = true
                sina(codes)
            }
        }
    }

    fun minute(code: String): List<MinutePoint> {
        val api = if (StockCodes.isUs(code)) "UsMinute" else "minute"
        return QuoteParser.parseMinute(fetch("web.ifzq.gtimg.cn", "/appstock/app/$api/query?code=$code", Charsets.UTF_8), code)
    }

    /**
     * 美股要用带交易所后缀的代码（usAAPL.OQ）：不带后缀时接口会返回全部历史的日线，
     * 周线月线却只有一条。exchange 来自腾讯报价。
     */
    fun kline(code: String, exchange: String, period: String, count: Int): List<KLine> {
        val us = StockCodes.isUs(code)
        val key = if (us) "us$exchange" else code
        val api = if (us) "usfqkline" else "fqkline"
        return QuoteParser.parseKline(
            fetch("web.ifzq.gtimg.cn", "/appstock/app/$api/get?param=$key,$period,,,$count,qfq", Charsets.UTF_8),
            key, period,
        )
    }

    fun search(keyword: String): List<SearchHit> {
        val q = URLEncoder.encode(keyword, "UTF-8")
        return QuoteParser.parseSuggest(fetch("smartbox.gtimg.cn", "/s3/?v=2&q=$q&t=all", Charsets.UTF_8))
    }
}
