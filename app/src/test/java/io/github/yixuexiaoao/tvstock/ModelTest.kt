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
import io.github.yixuexiaoao.tvstock.model.Themes
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
        assertNull(StockCodes.normalize("60051"))
        assertNull(StockCodes.normalize("123abc"))
        assertNull(StockCodes.normalize("苹果"))
        assertNull(StockCodes.normalize(""))
        assertNull(StockCodes.normalize("hk00700"))
        assertNull(StockCodes.normalize(null))
    }

    @Test fun normalizeUsCodes() {
        assertEquals("usAAPL", StockCodes.normalize("AAPL"))
        assertEquals("usAAPL", StockCodes.normalize(" aapl "))
        assertEquals("usAAPL", StockCodes.normalize("usAAPL"))
        assertEquals("usAAPL", StockCodes.normalize("aapl.oq"))
        assertEquals("usBRK.B", StockCodes.normalize("BRK.B"))
        assertEquals("usBRK.B", StockCodes.normalize("usBRK.B"))
        assertEquals("usBRK.B", StockCodes.normalize("brk.b.n"))
        assertEquals("us.DJI", StockCodes.normalize(".DJI"))
        assertEquals("us.DJI", StockCodes.normalize("us.DJI"))
        // 代码本身以 US 开头时不能把它当前缀去掉
        assertEquals("usUSB", StockCodes.normalize("USB"))
        assertEquals("usUSB", StockCodes.normalize("usb"))
        assertEquals("usUSB", StockCodes.normalize("usUSB"))
    }

    @Test fun usCodeHelpers() {
        assertTrue(StockCodes.isUs("usAAPL"))
        assertFalse(StockCodes.isUs("sh600519"))
        assertEquals("US", StockCodes.market("us.DJI"))
        assertEquals("CN", StockCodes.market("sz000001"))
        assertEquals("AAPL", StockCodes.shortCode("usAAPL"))
        assertEquals("DJI", StockCodes.shortCode("us.DJI"))
        assertEquals("BRK.B", StockCodes.shortCode("usBRK.B"))
        assertEquals("600519", StockCodes.shortCode("sh600519"))
    }

    @Test fun marketSettingIsValidatedAndOldConfigDefaultsToCn() {
        assertEquals("CN", AppSettings().market)
        assertEquals("US", AppSettings().merged(JSONObject("""{"market":"US"}""")).market)
        assertEquals("CN", AppSettings().merged(JSONObject("""{"market":"HK"}""")).market)
        assertEquals("CN", AppConfig.fromJson(JSONObject("""{"settings":{"layoutId":"4"}}""")).settings.market)
    }

    @Test fun usMarketClockUsesNewYorkTimeAndDst() {
        // 2026-11-09 是冬令时：美东 9:30 = 北京 22:30
        assertFalse(MarketClock.isTradingTime(beijing(2026, 11, 9, 22, 29), "US"))
        assertTrue(MarketClock.isTradingTime(beijing(2026, 11, 9, 22, 30), "US"))
        // 2026-07-06 是夏令时：美东 9:30 = 北京 21:30
        assertFalse(MarketClock.isTradingTime(beijing(2026, 7, 6, 21, 29), "US"))
        assertTrue(MarketClock.isTradingTime(beijing(2026, 7, 6, 21, 30), "US"))
        // 北京周二 4:30 仍是美东周一 15:30
        assertTrue(MarketClock.isTradingTime(beijing(2026, 11, 10, 4, 30), "US"))
        assertEquals("20261109", MarketClock.dateString(beijing(2026, 11, 10, 4, 30), "US"))
        // 北京周六 4:00 仍是美东周五盘中；北京周六晚上已是美东周六
        assertTrue(MarketClock.isTradingTime(beijing(2026, 11, 7, 4, 0), "US"))
        assertFalse(MarketClock.isTradingTime(beijing(2026, 11, 7, 23, 0), "US"))
        // 默认仍是 A 股
        assertTrue(MarketClock.isTradingTime(beijing(2026, 11, 9, 10, 0)))
    }

    @Test fun usMarketStatus() {
        val today = "20261109"
        assertEquals("未开盘", MarketClock.status(beijing(2026, 11, 9, 16, 0), today, "US"))
        assertEquals("盘前", MarketClock.status(beijing(2026, 11, 9, 22, 0), today, "US"))
        assertEquals("交易中", MarketClock.status(beijing(2026, 11, 9, 23, 0), today, "US"))
        assertEquals("盘后", MarketClock.status(beijing(2026, 11, 10, 6, 0), today, "US"))
        assertEquals("已收盘", MarketClock.status(beijing(2026, 11, 10, 10, 0), today, "US"))
        assertEquals("休市", MarketClock.status(beijing(2026, 11, 8, 23, 0), today, "US"))
        // 开盘已过但指数还是旧日期：节假日
        assertEquals("休市", MarketClock.status(beijing(2026, 11, 9, 23, 0), "20261106", "US"))
        assertEquals("盘前", MarketClock.status(beijing(2026, 11, 9, 22, 0), "20261106", "US"))
    }

    @Test fun onlyCodeIsRequired() {
        val s = StockConfig.fromJson(JSONObject("""{"code":"600519"}"""))!!
        assertEquals("sh600519", s.code)
        assertNull(s.targetHigh)
        assertNull(s.shares)
        assertNull(s.alertModes)
        assertTrue(s.enabled)
        assertNull(StockConfig.fromJson(JSONObject("""{"code":"!!"}""")))
    }

    @Test fun blankAndInvalidNumbersBecomeNull() {
        val s = StockConfig.fromJson(JSONObject("""{"code":"600519","targetHigh":"","targetLow":"abc","costPrice":-1,"shares":"200"}"""))!!
        assertNull(s.targetHigh)
        assertNull(s.targetLow)
        assertNull(s.costPrice)
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

    @Test fun autoThemeFollowsTime() {
        fun at(h: Int, day: String = "07:00", night: String = "19:00") = Themes.resolve(Themes.AUTO, h * 60, day, night)
        assertEquals(Themes.LIGHT, at(7))
        assertEquals(Themes.LIGHT, at(18))
        assertEquals(Themes.NIGHT, at(19))
        assertEquals(Themes.NIGHT, at(3))
        // 白天时段跨越 0 点
        assertEquals(Themes.LIGHT, at(23, "22:00", "06:00"))
        assertEquals(Themes.LIGHT, at(2, "22:00", "06:00"))
        assertEquals(Themes.NIGHT, at(12, "22:00", "06:00"))
        assertEquals(Themes.LIGHT, at(3, "08:00", "08:00"))
        // 手动主题不受时间影响
        assertEquals(Themes.BLUE, Themes.resolve(Themes.BLUE, 0, "07:00", "19:00"))
    }

    @Test fun themeSettingsValidated() {
        val s = AppSettings().merged(JSONObject("""{"theme":"AUTO","dayStart":"06:30","nightStart":"25:00","updateChannel":"BAD","logVerbose":true}"""))
        assertEquals("AUTO", s.theme)
        assertEquals("06:30", s.dayStart)
        assertEquals("19:00", s.nightStart)
        assertEquals("AUTO", s.updateChannel)
        assertTrue(s.logVerbose)
    }

    @Test fun flashSpeedStaysBelowPhotosensitiveLimit() {
        assertEquals(AppSettings.FLASH_MIN_MS, AppSettings().merged(JSONObject("""{"flashSpeedMs":50}""")).flashSpeedMs)
    }

    @Test fun newSettingsValidated() {
        val s = AppSettings().merged(JSONObject("""{"keepScreen":"BAD","alertFrequency":"BAD","alertCooldownMin":9999,"guideShown":true}"""))
        assertEquals("TRADING", s.keepScreen)
        assertEquals("EVERY", s.alertFrequency)
        assertEquals(240, s.alertCooldownMin)
        assertTrue(s.guideShown)
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

    @Test fun legacyPricesMigrateToCost() {
        fun parse(extra: String) = StockConfig.fromJson(JSONObject("""{"code":"600519",$extra}"""))!!.costPrice
        assertEquals(100.0, parse(""""costPrice":100,"holdPrice":95,"buyPrice":90""")!!, 1e-9)
        assertEquals(95.0, parse(""""holdPrice":95,"buyPrice":90""")!!, 1e-9)
        assertEquals(90.0, parse(""""buyPrice":90""")!!, 1e-9)
    }

    @Test fun legacyPortMigratesOnlyFromOldConfig() {
        fun port(json: String) = AppConfig.fromJson(JSONObject(json)).settings.webPort
        assertEquals(58080, AppSettings().webPort)
        assertEquals(58080, port("""{"settings":{"webPort":8080}}"""))
        assertEquals(9000, port("""{"settings":{"webPort":9000}}"""))
        // 新版配置中用户主动设置的 8080 保留，且可经过保存再读取
        val saved = AppConfig(AppSettings().copy(webPort = 8080), emptyList()).toJson().toString()
        assertEquals(8080, port(saved))
    }

    @Test fun mergeKeepsUnsubmittedFieldsAndClearsNulls() {
        val s = StockConfig("sh600519", targetHigh = 100.0, shares = 100, costPrice = 10.0, realizedProfit = 5.0)
        val alerts = s.merged(JSONObject("""{"code":"sh600519","targetHigh":null,"pctUp":3}"""))
        assertNull(alerts.targetHigh)
        assertEquals(3.0, alerts.pctUp!!, 1e-9)
        assertEquals(100L, alerts.shares)
        assertEquals(5.0, alerts.realizedProfit, 1e-9)
        val inherit = s.copy(alertModes = listOf("CELL")).merged(JSONObject("""{"alertModes":null}"""))
        assertNull(inherit.alertModes)
    }

    @Test fun holdingUsesCostPrice() {
        val q = quote(110.0)
        val h = Holding.of(StockConfig("sh600519", costPrice = 100.0, shares = 200), q)!!
        assertEquals(100.0, h.cost!!, 1e-9)
        assertEquals(22000.0, h.marketValue, 1e-9)
        assertEquals(2000.0, h.profit!!, 1e-9)
        assertEquals(10.0, h.profitPct!!, 1e-9)
        assertEquals(2000.0, h.dayProfit, 1e-9)
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

    @Test fun keepAwakeCoversLunchBreak() {
        assertTrue(MarketClock.isKeepAwakeTime(beijing(2026, 11, 9, 12, 0)))
        assertTrue(MarketClock.isKeepAwakeTime(beijing(2026, 11, 9, 9, 0)))
        assertTrue(MarketClock.isKeepAwakeTime(beijing(2026, 11, 9, 15, 10)))
        assertFalse(MarketClock.isKeepAwakeTime(beijing(2026, 11, 9, 8, 59)))
        assertFalse(MarketClock.isKeepAwakeTime(beijing(2026, 11, 9, 20, 0)))
        assertFalse(MarketClock.isKeepAwakeTime(beijing(2026, 11, 7, 10, 0)))
    }

    @Test fun marketStatus() {
        val today = "20261109"
        assertEquals("未开盘", MarketClock.status(beijing(2026, 11, 9, 9, 0), today))
        assertEquals("集合竞价", MarketClock.status(beijing(2026, 11, 9, 9, 20), today))
        assertEquals("交易中", MarketClock.status(beijing(2026, 11, 9, 10, 0), today))
        assertEquals("午休", MarketClock.status(beijing(2026, 11, 9, 12, 0), today))
        assertEquals("交易中", MarketClock.status(beijing(2026, 11, 9, 14, 0), today))
        assertEquals("已收盘", MarketClock.status(beijing(2026, 11, 9, 15, 0), today))
        assertEquals("休市", MarketClock.status(beijing(2026, 11, 7, 10, 0), today))
        // 工作日行情日期是旧的：节假日
        assertEquals("休市", MarketClock.status(beijing(2026, 11, 9, 10, 0), "20261106"))
        assertEquals("交易中", MarketClock.status(beijing(2026, 11, 9, 10, 0), null))
    }

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
