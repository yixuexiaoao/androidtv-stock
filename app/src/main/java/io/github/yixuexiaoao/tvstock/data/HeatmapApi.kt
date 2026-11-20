package io.github.yixuexiaoao.tvstock.data

import io.github.yixuexiaoao.tvstock.log.AppLog
import io.github.yixuexiaoao.tvstock.model.CloudScopes
import io.github.yixuexiaoao.tvstock.model.HeatmapItem
import io.github.yixuexiaoao.tvstock.model.HeatmapSnapshot
import io.github.yixuexiaoao.tvstock.model.HeatmapWeights
import io.github.yixuexiaoao.tvstock.model.MarketBreadth
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException

/** 大盘云图公开接口访问：东方财富为主、新浪行业为备，复用 HTTP 长连接。 */
object HeatmapApi {
    private const val TAG = "Heatmap"
    private const val EM_HOST = "push2.eastmoney.com"
    private const val SINA_HOST = "vip.stock.finance.sina.com.cn"
    private const val EM_REFERER = "https://quote.eastmoney.com/"
    private const val SINA_REFERER = "https://finance.sina.com.cn/"
    private val GBK: Charset = Charset.forName("GBK")
    private val sslFallbackHosts = ConcurrentHashMap.newKeySet<String>()

    private fun fetchOnce(url: String, charset: Charset, referer: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AndroidTV-Stock")
        conn.setRequestProperty("Referer", referer)
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IOException("HTTP $code")
        }
        // 不调用 disconnect()，让底层连接池复用 Keep-Alive 与 TLS 会话
        return conn.inputStream.use { it.readBytes() }.toString(charset)
    }

    private fun fetch(host: String, pathAndQuery: String, charset: Charset, referer: String): String =
        try {
            fetchOnce("https://$host$pathAndQuery", charset, referer)
        } catch (e: SSLException) {
            if (sslFallbackHosts.add(host)) AppLog.w(TAG, "$host 的 HTTPS 不可用，回退 HTTP", e)
            fetchOnce("http://$host$pathAndQuery", charset, referer)
        }

    /** 拉取一份云图快照；东财失败时自动尝试新浪备源（仅限行业全景与新浪板块下钻）。 */
    fun load(scope: String, sectorId: String?, sectorName: String, weightMode: String): HeatmapSnapshot {
        val now = System.currentTimeMillis()
        if (sectorId != null && sectorId.startsWith("new_")) {
            return loadSinaSectorStocks(scope, sectorId, sectorName, weightMode, now)
        }
        return try {
            loadEastMoney(scope, sectorId, sectorName, weightMode, now)
        } catch (e: IOException) {
            if (sectorId == null && scope == CloudScopes.SECTORS) {
                AppLog.w(TAG, "东财云图不可用，切换新浪行业备源", e)
                loadSinaSectors(weightMode, now)
            } else {
                throw e
            }
        }
    }

    private fun fidOf(weightMode: String): String = if (weightMode == HeatmapWeights.AMOUNT) "f6" else "f21"

    private fun loadEastMoney(
        scope: String,
        sectorId: String?,
        sectorName: String,
        weightMode: String,
        now: Long,
    ): HeatmapSnapshot {
        val fid = fidOf(weightMode)
        if (sectorId != null) {
            val path = "/api/qt/clist/get?pn=1&pz=100&po=1&np=1&fltt=2&invt=2&fid=$fid" +
                "&fs=b:$sectorId+f:!50&fields=f12,f13,f14,f2,f3,f6,f20,f21,f100"
            val items = HeatmapParser.parseEastMoneyStocks(fetch(EM_HOST, path, Charsets.UTF_8, EM_REFERER), weightMode)
            return HeatmapSnapshot(
                scope = scope,
                sectorId = sectorId,
                sectorName = sectorName,
                weightMode = weightMode,
                source = "东财",
                fetchedAtMs = now,
                breadth = HeatmapParser.breadthOfStocks(items),
                items = items,
                groups = HeatmapParser.groupByIndustry(items),
            )
        }

        val breadth = try {
            val bPath = "/api/qt/ulist.np/get?fltt=2&fields=f12,f14,f2,f3,f6,f104,f105,f106&secids=1.000001,0.399001,0.899050"
            HeatmapParser.parseEastMoneyBreadth(fetch(EM_HOST, bPath, Charsets.UTF_8, EM_REFERER))
        } catch (_: Exception) {
            MarketBreadth()
        }

        if (scope == CloudScopes.SECTORS) {
            val path = "/api/qt/clist/get?pn=1&pz=50&po=1&np=1&fltt=2&invt=2&fid=$fid" +
                "&fs=m:90+t:2+s:2&fields=f12,f14,f2,f3,f6,f20,f21,f104,f105,f106,f128,f136"
            val sectors = HeatmapParser.parseEastMoneySectors(fetch(EM_HOST, path, Charsets.UTF_8, EM_REFERER), weightMode)
            if (sectors.isEmpty()) throw IOException("东财行业数据为空")
            val effectiveBreadth = if (breadth.totalCount > 0) breadth else MarketBreadth(
                upCount = sectors.sumOf { it.upCount },
                flatCount = sectors.sumOf { it.flatCount },
                downCount = sectors.sumOf { it.downCount },
                totalAmountYuan = sectors.sumOf { it.amountYuan },
            )
            return HeatmapSnapshot(
                scope = scope,
                weightMode = weightMode,
                source = "东财",
                fetchedAtMs = now,
                breadth = effectiveBreadth,
                items = sectors,
            )
        }

        val (fs, pages) = when (scope) {
            CloudScopes.HS300 -> "b:BK0500+f:!50" to 3
            CloudScopes.ALL_TOP -> "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23,m:0+t:81+s:2048" to 2
            CloudScopes.CYB -> "m:0+t:80+f:!50" to 1
            CloudScopes.KCB -> "m:1+t:23+f:!50" to 1
            else -> "b:BK0500+f:!50" to 2
        }
        val all = ArrayList<HeatmapItem>(pages * 100)
        for (pn in 1..pages) {
            val path = "/api/qt/clist/get?pn=$pn&pz=100&po=1&np=1&fltt=2&invt=2&fid=$fid" +
                "&fs=$fs&fields=f12,f13,f14,f2,f3,f6,f20,f21,f100"
            val pageItems = HeatmapParser.parseEastMoneyStocks(fetch(EM_HOST, path, Charsets.UTF_8, EM_REFERER), weightMode)
            all.addAll(pageItems)
            if (pageItems.size < 100) break
        }
        val unique = all.distinctBy { it.id }
        val finalBreadth = if (scope == CloudScopes.ALL_TOP && breadth.totalCount > 0) {
            breadth
        } else {
            HeatmapParser.breadthOfStocks(unique)
        }
        return HeatmapSnapshot(
            scope = scope,
            weightMode = weightMode,
            source = "东财",
            fetchedAtMs = now,
            breadth = finalBreadth,
            items = unique,
            groups = HeatmapParser.groupByIndustry(unique),
        )
    }

    private fun loadSinaSectors(weightMode: String, now: Long): HeatmapSnapshot {
        val text = fetch(SINA_HOST, "/q/view/newSinaHy.php", GBK, SINA_REFERER)
        val sectors = HeatmapParser.parseSinaSectors(text)
        if (sectors.isEmpty()) throw IOException("新浪行业数据为空")
        return HeatmapSnapshot(
            scope = CloudScopes.SECTORS,
            weightMode = weightMode,
            source = "新浪",
            fetchedAtMs = now,
            breadth = MarketBreadth(totalAmountYuan = sectors.sumOf { it.amountYuan }),
            items = sectors,
        )
    }

    private fun loadSinaSectorStocks(
        scope: String,
        sectorId: String,
        sectorName: String,
        weightMode: String,
        now: Long,
    ): HeatmapSnapshot {
        val sort = if (weightMode == HeatmapWeights.AMOUNT) "amount" else "nmc"
        val path = "/quotes_service/api/json_v2.php/Market_Center.getHQNodeData?page=1&num=80&sort=$sort&asc=0&node=$sectorId"
        val items = HeatmapParser.parseSinaStocks(fetch(SINA_HOST, path, Charsets.UTF_8, SINA_REFERER), weightMode)
        return HeatmapSnapshot(
            scope = scope,
            sectorId = sectorId,
            sectorName = sectorName,
            weightMode = weightMode,
            source = "新浪",
            fetchedAtMs = now,
            breadth = HeatmapParser.breadthOfStocks(items),
            items = items,
        )
    }
}
