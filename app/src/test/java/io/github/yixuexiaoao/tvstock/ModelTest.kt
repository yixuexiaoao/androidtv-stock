package io.github.yixuexiaoao.tvstock

import io.github.yixuexiaoao.tvstock.data.MarketClock
import io.github.yixuexiaoao.tvstock.model.AppConfig
import io.github.yixuexiaoao.tvstock.model.AppSettings
import io.github.yixuexiaoao.tvstock.model.GridLayoutDef
import io.github.yixuexiaoao.tvstock.model.Holding
import io.github.yixuexiaoao.tvstock.model.Layouts
import io.github.yixuexiaoao.tvstock.model.PortfolioSummary
import io.github.yixuexiaoao.tvstock.model.Quote
import io.github.yixuexiaoao.tvstock.model.StockCodes
import io.github.yixuexiaoao.tvstock.model.StockConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ModelTest {
    private fun quote(price: Double, prev: Double = 100.0) = Quote(
        "sh600519", "测试", price, prev, prev, price, price,
        if (price > 0) price - prev else 0.0, if (price > 0) (price - prev) / prev * 100 else 0.0, 1.0, 1.0,
    )

    @Test fun normalizeCodes() {
        assertEquals("sh600519", StockCodes.normalize("600519"))
        assertEquals("sh600519", StockCodes.normalize(" SH600519 "))
        assertEquals("sh600519", StockCodes.normalize("600519.SH"))
        assertEquals("sz000001", StockCodes.normalize("000001"))
        assertEquals("sh000001", StockCodes.normalize("sh000001"))
        assertEquals("sz300750", StockCodes.normalize("300750"))
        assertEquals("sh510300", StockCodes.normalize("510300"))
        assertEquals("sz159915", StockCodes.normalize("159915"))
        assertEquals("bj920001", StockCodes.normalize("920001"))
        assertEquals("bj830001", StockCodes.normalize("830001"))
        assertNull(StockCodes.normalize("abc"))
        assertNull(StockCodes.normalize("60051"))
        assertNull(StockCodes.normalize("hk00700"))
        assertNull(StockCodes.normalize(null))
    }

    @Test fun onlyCodeIsRequired() {
        val s = StockConfig.fromJson(JSONObject("""{"code":"600519"}"""))!!
        assertEquals("sh600519", s.code)
        assertNull(s.targetHigh)
        assertNull(s.shares)
        assertNull(s.alertModes)
        assertTrue(s.enabled)
        assertNull(StockConfig.fromJson(JSONObject("""{"code":"xx"}""")))
    }

    @Test fun blankAndInvalidNumbersBecomeNull() {
        val s = StockConfig.fromJson(JSONObject("""{"code":"600519","targetHigh":"","targetLow":"abc","costPrice":-1,"buyPrice":"12.5","shares":"200"}"""))!!
        assertNull(s.targetHigh)
        assertNull(s.targetLow)
        assertNull(s.costPrice)
        assertEquals(12.5, s.buyPrice!!, 1e-9)
        assertEquals(200L, s.shares)
    }

    @Test fun stockRoundTrip() {
        val s = StockConfig("sz000001", alias = "平银", targetHigh = 12.0, shares = 100, costPrice = 10.5,
            alertModes = listOf("CELL"), buyDate = "2026-01-02", note = "n", displayMode = "TEXT", enabled = false)
        assertEquals(s, StockConfig.fromJson(s.toJson()))
        // 空列表（不提醒）与 null（跟随全局）必须区分
        val silent = s.copy(alertModes = emptyList())
        assertEquals(emptyList<String>(), StockConfig.fromJson(silent.toJson())!!.alertModes)
        val inherit = s.copy(alertModes = null)
        assertNull(StockConfig.fromJson(inherit.toJson())!!.alertModes)
    }

    @Test fun settingsMergeClampsAndIgnoresInvalid() {
        val s = AppSettings().merged(JSONObject("""{"refreshSec":0,"fontScale":9999,"theme":"NOPE","layoutId":"nope","flashColor":"red","webPort":80,"privacy":true}"""))
        assertEquals(1, s.refreshSec)
        assertEquals(250, s.fontScale)
        assertEquals("DARK", s.theme)
        assertEquals("4", s.layoutId)
        assertEquals("#FF1744", s.flashColor)
        assertEquals(1024, s.webPort)
        assertTrue(s.privacy)
    }

    @Test fun configRoundTrip() {
        val cfg = AppConfig(
            AppSettings().merged(JSONObject("""{"layoutId":"1+5","fontScale":120,"alertModes":["FULLSCREEN","SOUND"]}""")),
            listOf(StockConfig("sh600519", targetHigh = 1500.0)),
        )
        // 经过文本序列化，与真实落盘流程一致
        val restored = AppConfig.fromJson(JSONObject(cfg.toJson().toString()))
        assertEquals(cfg, restored)
    }

    @Test fun corruptConfigFallsBack() {
        val cfg = AppConfig.fromJson(JSONObject("""{"stocks":[{"code":"bad"},{"code":"600519"},{"code":"sh600519"}]}"""))
        assertEquals(1, cfg.stocks.size)
        assertEquals(AppSettings(), cfg.settings)
    }

    @Test fun builtInLayoutsAreValid() {
        Layouts.ALL.forEach { def ->
            def.cells.forEach { c ->
                assertTrue(def.id, c.x >= 0 && c.y >= 0 && c.x + c.w <= def.cols && c.y + c.h <= def.rows)
            }
            for (i in def.cells.indices) for (j in i + 1 until def.cells.size) {
                assertFalse("${def.id} 窗口重叠", def.cells[i].overlaps(def.cells[j]))
            }
            assertEquals("${def.id} 未铺满", def.cols * def.rows, def.cells.sumOf { it.w * it.h })
        }
        // 要求的 1/2/4/6/8 画面和不规则布局
        listOf("1" to 1, "2" to 2, "4" to 4, "6" to 6, "8" to 8, "1+5" to 6, "1+7" to 8, "1+12" to 13).forEach { (id, n) ->
            assertEquals(id, n, Layouts.byId(id)!!.cells.size)
        }
    }

    @Test fun customLayoutValidation() {
        val good = JSONObject("""{"cols":3,"rows":2,"cells":[{"x":0,"y":0,"w":2,"h":2},{"x":2,"y":0,"w":1,"h":1}]}""")
        assertNotNull(GridLayoutDef.fromJson(good))
        val overlap = JSONObject("""{"cols":3,"rows":2,"cells":[{"x":0,"y":0,"w":2,"h":2},{"x":1,"y":0,"w":1,"h":1}]}""")
        assertNull(GridLayoutDef.fromJson(overlap))
        val outside = JSONObject("""{"cols":3,"rows":2,"cells":[{"x":2,"y":0,"w":2,"h":1}]}""")
        assertNull(GridLayoutDef.fromJson(outside))
        assertNull(GridLayoutDef.fromJson(JSONObject("""{"cols":3,"rows":2,"cells":[]}""")))
        assertNull(GridLayoutDef.fromJson(JSONObject("""{"cols":99,"rows":2,"cells":[{"x":0,"y":0,"w":1,"h":1}]}""")))
    }

    @Test fun invalidCustomLayoutFallsBack() {
        assertEquals("4", Layouts.resolve("custom", null).id)
        assertEquals("4", Layouts.resolve("missing", null).id)
    }

    @Test fun paging() {
        assertEquals(1, Layouts.pageCount(0, 4))
        assertEquals(1, Layouts.pageCount(4, 4))
        assertEquals(2, Layouts.pageCount(5, 4))
        val items = (1..5).toList()
        assertEquals(listOf(1, 2, 3, 4), Layouts.pageItems(items, 4, 0))
        assertEquals(listOf(5), Layouts.pageItems(items, 4, 1))
        assertEquals(listOf(1, 2, 3, 4), Layouts.pageItems(items, 4, 2))
        assertEquals(listOf(5), Layouts.pageItems(items, 4, -1))
    }

    @Test fun holdingCostPriority() {
        val q = quote(110.0)
        val h = Holding.of(StockConfig("sh600519", buyPrice = 90.0, holdPrice = 95.0, costPrice = 100.0, shares = 200), q)!!
        assertEquals(100.0, h.cost!!, 1e-9)
        assertEquals(22000.0, h.marketValue, 1e-9)
        assertEquals(2000.0, h.profit!!, 1e-9)
        assertEquals(10.0, h.profitPct!!, 1e-9)
        assertEquals(2000.0, h.dayProfit, 1e-9)
        assertEquals(95.0, Holding.of(StockConfig("sh600519", buyPrice = 90.0, holdPrice = 95.0, shares = 1), q)!!.cost!!, 1e-9)
        assertEquals(90.0, Holding.of(StockConfig("sh600519", buyPrice = 90.0, shares = 1), q)!!.cost!!, 1e-9)
    }

    @Test fun holdingWithoutSharesOrCost() {
        assertNull(Holding.of(StockConfig("sh600519", costPrice = 10.0), quote(11.0)))
        val noCost = Holding.of(StockConfig("sh600519", shares = 100), quote(11.0))!!
        assertNull(noCost.profit)
        assertEquals(1100.0, noCost.marketValue, 1e-9)
    }

    @Test fun portfolioSummary() {
        val a = StockConfig("sh600519", shares = 100, costPrice = 100.0)
        val disabled = StockConfig("sz000001", shares = 100, costPrice = 1.0, enabled = false)
        val p = PortfolioSummary.of(listOf(a, disabled), mapOf("sh600519" to quote(110.0), "sz000001" to quote(110.0).copy(code = "sz000001")))!!
        assertEquals(1, p.count)
        assertEquals(1000.0, p.profit, 1e-9)
        assertEquals(10.0, p.profitPct!!, 1e-9)
        assertNull(PortfolioSummary.of(listOf(StockConfig("sh600519")), mapOf("sh600519" to quote(1.0))))
    }

    private fun beijing(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
            clear()
            set(y, mo - 1, d, h, mi)
        }.timeInMillis

    @Test fun tradingHours() {
        // 2026-11-09 周一
        assertTrue(MarketClock.isTradingTime(beijing(2026, 11, 9, 9, 30)))
        assertTrue(MarketClock.isTradingTime(beijing(2026, 11, 9, 14, 59)))
        assertFalse(MarketClock.isTradingTime(beijing(2026, 11, 9, 12, 0)))
        assertFalse(MarketClock.isTradingTime(beijing(2026, 11, 9, 9, 0)))
        assertFalse(MarketClock.isTradingTime(beijing(2026, 11, 9, 15, 30)))
        // 周六
        assertFalse(MarketClock.isTradingTime(beijing(2026, 11, 7, 10, 0)))
        assertEquals("20261109", MarketClock.dateString(beijing(2026, 11, 9, 10, 0)))
    }
}
