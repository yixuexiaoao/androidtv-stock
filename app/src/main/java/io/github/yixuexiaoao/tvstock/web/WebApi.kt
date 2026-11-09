package io.github.yixuexiaoao.tvstock.web

import io.github.yixuexiaoao.tvstock.model.AlertEvent
import io.github.yixuexiaoao.tvstock.model.AlertModes
import io.github.yixuexiaoao.tvstock.model.AppConfig
import io.github.yixuexiaoao.tvstock.model.DisplayModes
import io.github.yixuexiaoao.tvstock.model.Holding
import io.github.yixuexiaoao.tvstock.model.Layouts
import io.github.yixuexiaoao.tvstock.model.PortfolioSummary
import io.github.yixuexiaoao.tvstock.model.Quote
import io.github.yixuexiaoao.tvstock.model.SearchHit
import io.github.yixuexiaoao.tvstock.model.StockCodes
import io.github.yixuexiaoao.tvstock.model.StockConfig
import io.github.yixuexiaoao.tvstock.model.Themes
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

interface WebBackend {
    fun config(): AppConfig
    fun updateSettings(json: JSONObject)
    fun upsertStock(stock: StockConfig)
    fun deleteStock(code: String)
    fun reorder(codes: List<String>)
    fun replaceConfig(next: AppConfig)
    fun quotes(): Map<String, Quote>
    fun search(keyword: String): List<SearchHit>
    fun testAlert(code: String?, modes: List<String>?)
    fun dismissAlerts()
    fun alertHistory(): List<AlertEvent>
    fun status(): JSONObject
}

data class ApiRequest(
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    /** key 为小写 */
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
)

data class ApiResponse(val status: Int, val body: String)

/** Web 后台 API。不依赖 Android，可直接做单元测试。 */
class WebApi(private val backend: WebBackend) {
    private fun ok(json: Any = JSONObject().put("ok", true)) = ApiResponse(200, json.toString())
    private fun fail(status: Int, message: String) =
        ApiResponse(status, JSONObject().put("ok", false).put("error", message).toString())

    /** 防 DNS 重绑定：只接受 IP 或 localhost 访问。 */
    private fun hostAllowed(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val name = if (host.startsWith("[")) return true else host.substringBefore(':')
        return name == "localhost" || IPV4.matches(name)
    }

    private fun pinMatches(given: String?, pin: String): Boolean = MessageDigest.isEqual(
        (given ?: "").toByteArray(Charsets.UTF_8), pin.toByteArray(Charsets.UTF_8),
    )

    fun handle(req: ApiRequest): ApiResponse {
        if (!hostAllowed(req.headers["host"])) return fail(403, "请通过 IP 地址访问")
        val pin = backend.config().settings.webPin
        if (pin.isNotEmpty() && !pinMatches(req.headers["x-pin"], pin)) return fail(401, "需要访问密码")
        // 修改类请求必须带自定义头：跨站页面无法在不经 CORS 预检的情况下发送，从而防 CSRF
        if (req.method != "GET" && req.headers["x-requested-with"] != CLIENT_HEADER) return fail(403, "非法来源")
        return try {
            route(req)
        } catch (e: JSONException) {
            fail(400, "JSON 格式错误")
        } catch (e: IOException) {
            fail(502, "行情服务访问失败")
        }
    }

    private fun route(req: ApiRequest): ApiResponse = when {
        req.method == "GET" && req.path == "/api/state" -> ok(state())
        req.method == "GET" && req.path == "/api/meta" -> ok(meta())
        req.method == "GET" && req.path == "/api/search" -> search(req.query["q"].orEmpty())
        req.method == "GET" && req.path == "/api/export" -> ok(backend.config().toJson())
        req.method == "POST" && req.path == "/api/settings" -> {
            backend.updateSettings(JSONObject(req.body))
            ok()
        }
        req.method == "POST" && req.path == "/api/stocks" -> upsert(req.body)
        req.method == "POST" && req.path == "/api/stocks/delete" -> {
            val code = StockCodes.normalize(JSONObject(req.body).optString("code"))
            if (code == null) fail(400, "股票代码无效") else {
                backend.deleteStock(code)
                ok()
            }
        }
        req.method == "POST" && req.path == "/api/stocks/reorder" -> {
            val arr = JSONObject(req.body).getJSONArray("codes")
            backend.reorder((0 until arr.length()).mapNotNull { StockCodes.normalize(arr.optString(it)) })
            ok()
        }
        req.method == "POST" && req.path == "/api/alert/test" -> {
            val json = if (req.body.isBlank()) JSONObject() else JSONObject(req.body)
            val modes = json.optJSONArray("modes")?.let { a ->
                (0 until a.length()).map { a.optString(it) }.filter { it in AlertModes.ALL }
            }
            backend.testAlert(StockCodes.normalize(json.optString("code")), modes)
            ok()
        }
        req.method == "POST" && req.path == "/api/alert/dismiss" -> {
            backend.dismissAlerts()
            ok()
        }
        req.method == "POST" && req.path == "/api/import" -> {
            val json = JSONObject(req.body)
            if (!json.has("settings") && !json.has("stocks")) fail(400, "不是有效的配置文件") else {
                backend.replaceConfig(AppConfig.fromJson(json))
                ok()
            }
        }
        else -> fail(404, "接口不存在")
    }

    private fun upsert(body: String): ApiResponse {
        val stock = StockConfig.fromJson(JSONObject(body)) ?: return fail(400, "股票代码无效，请输入如 600519 或 sh600519")
        backend.upsertStock(stock)
        return ok(JSONObject().put("ok", true).put("code", stock.code))
    }

    private fun search(keyword: String): ApiResponse {
        val text = keyword.trim()
        if (text.isEmpty()) return ok(JSONArray())
        return ok(JSONArray().also { arr ->
            backend.search(text).take(20).forEach { hit ->
                arr.put(JSONObject().put("code", hit.code).put("name", hit.name).put("type", hit.type))
            }
        })
    }

    private fun quoteJson(q: Quote) = JSONObject().apply {
        put("name", q.name)
        put("price", q.displayPrice)
        put("hasTrade", q.hasTrade)
        put("prevClose", q.prevClose)
        put("open", q.open)
        put("high", q.high)
        put("low", q.low)
        put("change", q.change)
        put("changePct", q.changePct)
        put("volume", q.volume)
        put("amountWan", q.amountWan)
        put("decimals", q.decimals)
        put("time", q.time)
    }

    private fun state(): JSONObject {
        val cfg = backend.config()
        val quotes = backend.quotes()
        return JSONObject().apply {
            put("settings", cfg.settings.toJson())
            put("stocks", JSONArray().also { arr ->
                cfg.stocks.forEach { s ->
                    val q = quotes[s.code]
                    arr.put(s.toJson().apply {
                        q?.let { put("quote", quoteJson(it)) }
                        Holding.of(s, q)?.let { h ->
                            put("holding", JSONObject().apply {
                                put("shares", h.shares)
                                putOpt("cost", h.cost)
                                put("marketValue", h.marketValue)
                                putOpt("profit", h.profit)
                                putOpt("profitPct", h.profitPct)
                                put("dayProfit", h.dayProfit)
                            })
                        }
                    })
                }
            })
            PortfolioSummary.of(cfg.stocks, quotes)?.let { p ->
                put("portfolio", JSONObject().apply {
                    put("count", p.count)
                    put("marketValue", p.marketValue)
                    put("profit", p.profit)
                    put("dayProfit", p.dayProfit)
                    putOpt("profitPct", p.profitPct)
                })
            }
            put("alerts", JSONArray().also { arr ->
                backend.alertHistory().take(50).forEach { e ->
                    arr.put(JSONObject().apply {
                        put("code", e.code)
                        put("name", e.name)
                        put("kind", e.kind)
                        put("message", e.message)
                        put("price", e.price)
                        put("timeMs", e.timeMs)
                    })
                }
            })
            put("status", backend.status())
        }
    }

    private fun meta() = JSONObject().apply {
        put("layouts", JSONArray().also { arr -> Layouts.ALL.forEach { arr.put(it.toJson()) } })
        put("displayModes", labeled(DisplayModes.ALL, DisplayModes::label))
        put("alertModes", labeled(AlertModes.ALL, AlertModes::label))
        put("themes", labeled(Themes.ALL, Themes::label))
    }

    private fun labeled(values: List<String>, label: (String) -> String) = JSONArray().also { arr ->
        values.forEach { arr.put(JSONObject().put("value", it).put("label", label(it))) }
    }

    companion object {
        const val CLIENT_HEADER = "androidtv-stock"
        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    }
}
