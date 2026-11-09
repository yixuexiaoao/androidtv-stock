package io.github.yixuexiaoao.tvstock

import io.github.yixuexiaoao.tvstock.log.LogEntry
import io.github.yixuexiaoao.tvstock.model.AlertEvent
import io.github.yixuexiaoao.tvstock.model.AppConfig
import io.github.yixuexiaoao.tvstock.model.Quote
import io.github.yixuexiaoao.tvstock.model.SearchHit
import io.github.yixuexiaoao.tvstock.model.StockConfig
import io.github.yixuexiaoao.tvstock.model.Trades
import io.github.yixuexiaoao.tvstock.web.ApiRequest
import io.github.yixuexiaoao.tvstock.web.ApiResponse
import io.github.yixuexiaoao.tvstock.web.WebApi
import io.github.yixuexiaoao.tvstock.web.WebBackend
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WebApiTest {
    private class Fake : WebBackend {
        var cfg = AppConfig(stocks = emptyList())
        var tested: Pair<String?, List<String>?>? = null
        var dismissed = false
        var searchError = false
        var logs = mutableListOf(LogEntry(1, "E", "Web", "boom"))
        var logQuery: Triple<String?, String?, Int>? = null
        var updateChecked = false
        var updateInstalled = false

        override fun config() = cfg
        override fun updateSettings(json: JSONObject) { cfg = cfg.copy(settings = cfg.settings.merged(json)) }
        override fun upsertStock(stock: StockConfig) {
            val list = cfg.stocks.filterNot { it.code == stock.code } + stock
            cfg = cfg.copy(stocks = list)
        }
        override fun deleteStock(code: String) { cfg = cfg.copy(stocks = cfg.stocks.filterNot { it.code == code }) }
        override fun reorder(codes: List<String>) {
            val rank = codes.withIndex().associate { (i, c) -> c to i }
            cfg = cfg.copy(stocks = cfg.stocks.sortedBy { rank[it.code] ?: 999 })
        }
        override fun replaceConfig(next: AppConfig) { cfg = next }
        override fun quotes(): Map<String, Quote> = mapOf(
            "sh600519" to Quote("sh600519", "茅台", 110.0, 100.0, 100.0, 110.0, 100.0, 10.0, 10.0, 1.0, 1.0),
        )
        override fun search(keyword: String): List<SearchHit> {
            if (searchError) throw IOException("down")
            return listOf(SearchHit("sh600519", "茅台", "GP-A"))
        }
        var testedDirection = ""
        var muted = -1
        override fun testAlert(code: String?, modes: List<String>?, direction: String) {
            tested = code to modes
            testedDirection = direction
        }
        override fun trade(code: String, action: String, price: Double, shares: Long?) {
            val s = cfg.stocks.first { it.code == code }
            upsertStock(Trades.apply(s, action, price, shares, 1))
        }
        override fun undoTrade(code: String) {
            upsertStock(Trades.undo(cfg.stocks.first { it.code == code }))
        }
        override fun muteSound(minutes: Int) { muted = minutes }
        override fun dismissAlerts() { dismissed = true }
        override fun alertHistory(): List<AlertEvent> = listOf(AlertEvent(1, "sh600519", "茅台", "TARGET_HIGH", "到价", 110.0, 1, listOf("CELL")))
        override fun status() = JSONObject().put("version", "test")
        override fun logs(level: String?, keyword: String?, limit: Int): List<LogEntry> {
            logQuery = Triple(level, keyword, limit)
            return logs
        }
        override fun clearLogs() { logs.clear() }
        override fun checkUpdate() { updateChecked = true }
        override fun installUpdate() { updateInstalled = true }
    }

    private val fake = Fake()
    private val api = WebApi(fake)
    private val base = mapOf("host" to "192.168.1.10:8080", "x-requested-with" to WebApi.CLIENT_HEADER)

    private fun get(path: String, headers: Map<String, String> = base, query: Map<String, String> = emptyMap()) =
        api.handle(ApiRequest("GET", path, query, headers))

    private fun post(path: String, body: String, headers: Map<String, String> = base) =
        api.handle(ApiRequest("POST", path, emptyMap(), headers, body))

    private fun ApiResponse.obj() = JSONObject(body)

    @Test fun addStockWithOnlyCode() {
        val r = post("/api/stocks", """{"code":"600519"}""")
        assertEquals(200, r.status)
        assertEquals("sh600519", r.obj().getString("code"))
        assertEquals("sh600519", fake.cfg.stocks.single().code)
    }

    @Test fun invalidStockRejected() {
        assertEquals(400, post("/api/stocks", """{"code":"abc"}""").status)
        assertEquals(400, post("/api/stocks", "not json").status)
        assertTrue(fake.cfg.stocks.isEmpty())
    }

    @Test fun stateContainsQuoteAndHolding() {
        post("/api/stocks", """{"code":"600519","alias":"maotai","shares":100,"costPrice":100,"targetHigh":120}""")
        val state = get("/api/state").obj()
        val stock = state.getJSONArray("stocks").getJSONObject(0)
        assertEquals("maotai", stock.getString("alias"))
        assertEquals(110.0, stock.getJSONObject("quote").getDouble("price"), 1e-9)
        assertEquals(1000.0, stock.getJSONObject("holding").getDouble("profit"), 1e-9)
        assertEquals(1000.0, state.getJSONObject("portfolio").getDouble("profit"), 1e-9)
        assertEquals(1, state.getJSONArray("alerts").length())
        assertEquals("test", state.getJSONObject("status").getString("version"))
    }

    @Test fun updateDeleteReorder() {
        post("/api/stocks", """{"code":"600519"}""")
        post("/api/stocks", """{"code":"000001"}""")
        post("/api/stocks/reorder", """{"codes":["sz000001","sh600519"]}""")
        assertEquals("sz000001", fake.cfg.stocks[0].code)
        post("/api/stocks/delete", """{"code":"sz000001"}""")
        assertEquals(1, fake.cfg.stocks.size)
    }

    @Test fun settingsAndLayoutUpdate() {
        val r = post("/api/settings", """{"refreshSec":5,"layoutId":"custom","customLayout":{"cols":2,"rows":1,"cells":[{"x":0,"y":0,"w":1,"h":1},{"x":1,"y":0,"w":1,"h":1}]}}""")
        assertEquals(200, r.status)
        assertEquals(5, fake.cfg.settings.refreshSec)
        assertEquals(2, fake.cfg.settings.effectiveLayout().cells.size)
    }

    @Test fun csrfHeaderRequiredForWrites() {
        val r = post("/api/settings", """{"refreshSec":5}""", mapOf("host" to "192.168.1.10:8080"))
        assertEquals(403, r.status)
        assertEquals(3, fake.cfg.settings.refreshSec)
    }

    @Test fun dnsRebindingBlocked() {
        assertEquals(403, get("/api/state", base + ("host" to "evil.example.com")).status)
        assertEquals(403, get("/api/state", base - "host").status)
        assertEquals(200, get("/api/state", base + ("host" to "localhost:8080")).status)
    }

    @Test fun pinProtectsEverything() {
        post("/api/settings", """{"webPin":"1234"}""")
        assertEquals(401, get("/api/state").status)
        assertEquals(401, get("/api/state", base + ("x-pin" to "9999")).status)
        assertEquals(401, post("/api/stocks", """{"code":"600519"}""").status)
        assertEquals(200, get("/api/state", base + ("x-pin" to "1234")).status)
        assertTrue(fake.cfg.stocks.isEmpty())
    }

    @Test fun search() {
        val hits = JSONArray(get("/api/search", query = mapOf("q" to "茅台")).body)
        assertEquals("sh600519", hits.getJSONObject(0).getString("code"))
        assertEquals(0, JSONArray(get("/api/search", query = mapOf("q" to " ")).body).length())
        fake.searchError = true
        assertEquals(502, get("/api/search", query = mapOf("q" to "茅台")).status)
    }

    @Test fun alertEndpoints() {
        post("/api/alert/test", """{"code":"600519","modes":["FULLSCREEN","BAD"]}""")
        assertEquals("sh600519", fake.tested!!.first)
        assertEquals(listOf("FULLSCREEN"), fake.tested!!.second)
        post("/api/alert/test", "")
        assertEquals(null to null, fake.tested)
        assertFalse(fake.dismissed)
        post("/api/alert/dismiss", "{}")
        assertTrue(fake.dismissed)
    }

    @Test fun exportImportRoundTrip() {
        post("/api/stocks", """{"code":"600519","targetHigh":1500}""")
        post("/api/settings", """{"fontScale":130}""")
        val exported = get("/api/export").body
        fake.cfg = AppConfig(stocks = emptyList())
        assertEquals(200, post("/api/import", exported).status)
        assertEquals(1500.0, fake.cfg.stocks.single().targetHigh!!, 1e-9)
        assertEquals(130, fake.cfg.settings.fontScale)
        assertEquals(400, post("/api/import", """{"foo":1}""").status)
    }

    @Test fun editAndAlertDialogsDoNotOverwriteEachOther() {
        post("/api/stocks", """{"code":"600519","costPrice":100,"shares":100}""")
        post("/api/stocks", """{"code":"600519","targetHigh":120,"alertModes":["SOUND"]}""")
        var s = fake.cfg.stocks.single()
        assertEquals(100.0, s.costPrice!!, 1e-9)
        assertEquals(120.0, s.targetHigh!!, 1e-9)
        post("/api/stocks", """{"code":"600519","costPrice":null,"alias":"x"}""")
        s = fake.cfg.stocks.single()
        assertEquals(null, s.costPrice)
        assertEquals(120.0, s.targetHigh!!, 1e-9)
        assertEquals(listOf("SOUND"), s.alertModes)
    }

    @Test fun tradeFlow() {
        post("/api/stocks", """{"code":"600519","costPrice":100,"shares":100}""")
        assertEquals(200, post("/api/stocks/trade", """{"code":"600519","action":"BUY","price":120,"shares":100}""").status)
        assertEquals(110.0, fake.cfg.stocks.single().costPrice!!, 1e-9)
        // 不传价格时用现价（Fake 中为 110）
        assertEquals(200, post("/api/stocks/trade", """{"code":"600519","action":"CLEAR"}""").status)
        assertEquals(null, fake.cfg.stocks.single().shares)
        assertEquals(0.0, fake.cfg.stocks.single().realizedProfit, 1e-9)
        assertEquals(200, post("/api/stocks/trade/undo", """{"code":"600519"}""").status)
        assertEquals(200L, fake.cfg.stocks.single().shares)
    }

    @Test fun tradeErrorsAreReadable() {
        post("/api/stocks", """{"code":"600519"}""")
        val r = post("/api/stocks/trade", """{"code":"600519","action":"SELL","price":10,"shares":1}""")
        assertEquals(400, r.status)
        assertEquals("当前没有持仓", r.obj().getString("error"))
        assertEquals(400, post("/api/stocks/trade", """{"code":"600519","action":"HOLD","price":10}""").status)
        assertEquals(400, post("/api/stocks/trade/undo", """{"code":"600519"}""").status)
        assertEquals(403, post("/api/stocks/trade", """{"code":"600519","action":"BUY","price":10,"shares":1}""", mapOf("host" to "192.168.1.10:8080")).status)
    }

    @Test fun muteAndDirection() {
        post("/api/alert/mute", """{"minutes":30}""")
        assertEquals(30, fake.muted)
        post("/api/alert/test", """{"direction":"DOWN"}""")
        assertEquals("DOWN", fake.testedDirection)
        post("/api/alert/test", """{"direction":"weird"}""")
        assertEquals("UP", fake.testedDirection)
    }

    @Test fun logsCanBeReadFilteredAndCleared() {
        val list = JSONArray(get("/api/logs", query = mapOf("level" to "W", "q" to "bo", "limit" to "99999")).body)
        assertEquals("boom", list.getJSONObject(0).getString("msg"))
        assertEquals("E", list.getJSONObject(0).getString("level"))
        assertEquals(Triple("W", "bo", 2000), fake.logQuery)
        assertEquals(200, post("/api/logs/clear", "").status)
        assertEquals(0, JSONArray(get("/api/logs").body).length())
    }

    @Test fun logsRequireAuthAndClearRequiresClientHeader() {
        assertEquals(403, post("/api/logs/clear", "", mapOf("host" to "192.168.1.10:8080")).status)
        assertEquals(1, fake.logs.size)
        post("/api/settings", """{"webPin":"1234"}""")
        assertEquals(401, get("/api/logs").status)
    }

    @Test fun updateEndpoints() {
        post("/api/update/check", "{}")
        assertTrue(fake.updateChecked)
        assertFalse(fake.updateInstalled)
        post("/api/update/install", "{}")
        assertTrue(fake.updateInstalled)
    }

    @Test fun metaAndUnknownRoute() {
        val meta = get("/api/meta").obj()
        assertTrue(meta.getJSONArray("layouts").length() >= 16)
        assertEquals(404, get("/api/nope").status)
    }
}
