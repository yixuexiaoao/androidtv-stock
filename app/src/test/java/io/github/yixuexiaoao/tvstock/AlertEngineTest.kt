package io.github.yixuexiaoao.tvstock

import io.github.yixuexiaoao.tvstock.alert.AlertEngine
import io.github.yixuexiaoao.tvstock.model.AppSettings
import io.github.yixuexiaoao.tvstock.model.Quote
import io.github.yixuexiaoao.tvstock.model.StockConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class AlertEngineTest {
    /** 周一 10:00（北京时间）。 */
    private val trading: Long = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
        clear()
        set(2026, Calendar.NOVEMBER, 9, 10, 0)
    }.timeInMillis
    private val tradeTime = "20261109100000"
    private val settings = AppSettings()

    private fun quote(price: Double, time: String = tradeTime, prev: Double = 100.0) = Quote(
        "sh600519", "茅台", price, prev, prev, price, price,
        if (price > 0) price - prev else 0.0, if (price > 0) (price - prev) / prev * 100 else 0.0,
        1.0, 1.0, time = time,
    )

    private val stock = StockConfig("sh600519", targetHigh = 110.0, targetLow = 90.0)

    @Test fun usStockUsesNewYorkTradingHours() {
        val usQuote = Quote(
            "usAAPL", "苹果", 120.0, 100.0, 100.0, 120.0, 120.0, 20.0, 20.0, 1.0, 1.0,
            time = "20261109100000",
        )
        val usStock = StockConfig("usAAPL", targetHigh = 110.0)
        // 北京 10:00 是 A 股盘中，但美股没开盘
        assertTrue(AlertEngine().evaluate(usStock, usQuote, settings, trading).isEmpty())
        // 北京 23:00 = 美东 10:00
        val usOpen = trading + 13 * 3600_000L
        assertEquals(1, AlertEngine().evaluate(usStock, usQuote, settings, usOpen).size)
        // A 股此时不提醒
        assertTrue(AlertEngine().evaluate(stock, quote(120.0), settings, usOpen).isEmpty())
    }

    @Test fun cooldownStopsRapidReCrossing() {
        val e = AlertEngine()
        assertEquals(1, e.evaluate(stock, quote(111.0), settings, trading).size)
        assertTrue(e.evaluate(stock, quote(105.0), settings, trading + 30_000).isEmpty())
        // 3 分钟冷却内再次穿越不响
        assertTrue(e.evaluate(stock, quote(111.0), settings, trading + 60_000).isEmpty())
        // 冷却结束后条件仍满足，补一次提醒，之后不再重复
        assertEquals(1, e.evaluate(stock, quote(111.0), settings, trading + 181_000).size)
        assertTrue(e.evaluate(stock, quote(111.0), settings, trading + 240_000).isEmpty())
    }

    @Test fun dailyFrequencyFiresOncePerDay() {
        val daily = settings.copy(alertFrequency = "DAILY", alertTradingOnly = false)
        val e = AlertEngine()
        assertEquals(1, e.evaluate(stock, quote(111.0), daily, trading).size)
        assertTrue(e.evaluate(stock, quote(105.0), daily, trading + 3600_000).isEmpty())
        assertTrue(e.evaluate(stock, quote(111.0), daily, trading + 3600_000 * 2).isEmpty())
        assertEquals(1, e.evaluate(stock, quote(111.0), daily, trading + 3600_000 * 24).size)
    }

    @Test fun firesOnceThenRearmsAfterLeaving() {
        val settings = settings.copy(alertCooldownMin = 0)
        val e = AlertEngine()
        assertTrue(e.evaluate(stock, quote(105.0), settings, trading).isEmpty())
        val fired = e.evaluate(stock, quote(110.0), settings, trading)
        assertEquals(1, fired.size)
        assertEquals("TARGET_HIGH", fired[0].kind)
        assertEquals("sh600519", fired[0].code)
        assertEquals(listOf("CELL", "BANNER"), fired[0].modes)
        // 持续满足不重复
        assertTrue(e.evaluate(stock, quote(112.0), settings, trading + 1000).isEmpty())
        // 回落后重新布防
        assertTrue(e.evaluate(stock, quote(105.0), settings, trading + 2000).isEmpty())
        assertEquals(1, e.evaluate(stock, quote(111.0), settings, trading + 3000).size)
    }

    @Test fun lowTargetAndPercentAlerts() {
        val e = AlertEngine()
        assertEquals("TARGET_LOW", e.evaluate(stock, quote(89.0), settings, trading).single().kind)
        val pct = StockConfig("sh600519", pctUp = 5.0, pctDown = 3.0)
        assertEquals("PCT_UP", AlertEngine().evaluate(pct, quote(105.0), settings, trading).single().kind)
        assertEquals("PCT_DOWN", AlertEngine().evaluate(pct, quote(96.0), settings, trading).single().kind)
        assertTrue(AlertEngine().evaluate(pct, quote(102.0), settings, trading).isEmpty())
    }

    @Test fun holdingProfitAndLossAlerts() {
        val s = StockConfig("sh600519", shares = 100, costPrice = 100.0, profitPct = 10.0, lossPct = 5.0)
        assertEquals("PROFIT", AlertEngine().evaluate(s, quote(110.0), settings, trading).single().kind)
        assertEquals("LOSS", AlertEngine().evaluate(s, quote(95.0), settings, trading).single().kind)
        // 没有持仓时盈亏提醒不生效
        val noShares = StockConfig("sh600519", costPrice = 100.0, profitPct = 10.0)
        assertTrue(AlertEngine().evaluate(noShares, quote(120.0), settings, trading).isEmpty())
    }

    @Test fun aliasWithPercentSignDoesNotBreakFormatting() {
        val s = StockConfig("sh600519", alias = "涨5%d", pctUp = 5.0, pctDown = 3.0, shares = 1, costPrice = 100.0, profitPct = 1.0, lossPct = 1.0)
        val up = AlertEngine().evaluate(s, quote(110.0), settings, trading)
        assertEquals(2, up.size)
        assertTrue(up[0].message.startsWith("涨5%d "))
        assertEquals(2, AlertEngine().evaluate(s, quote(90.0), settings, trading).size)
    }

    @Test fun directionsSeparateUpAndDown() {
        assertEquals("UP", AlertEngine().evaluate(stock, quote(111.0), settings, trading).single().direction)
        assertEquals("DOWN", AlertEngine().evaluate(stock, quote(89.0), settings, trading).single().direction)
        val s = StockConfig("sh600519", shares = 1, costPrice = 100.0, lossPct = 5.0)
        assertEquals("DOWN", AlertEngine().evaluate(s, quote(90.0), settings, trading).single().direction)
    }

    @Test fun speedAlertWithinWindow() {
        val plain = StockConfig("sh600519")
        val fast = settings.copy(speedPct = 2.0, speedWindowMin = 5)
        val e = AlertEngine()
        assertTrue(e.evaluate(plain, quote(100.0), fast, trading).isEmpty())
        assertTrue(e.evaluate(plain, quote(101.0), fast, trading + 60_000).isEmpty())
        val up = e.evaluate(plain, quote(102.5), fast, trading + 120_000).single()
        assertEquals("SPEED_UP", up.kind)
        assertEquals("UP", up.direction)
        // 条件持续满足不重复
        assertTrue(e.evaluate(plain, quote(103.0), fast, trading + 180_000).isEmpty())
        // 超出窗口的价格不再参与比较
        val slow = AlertEngine()
        slow.evaluate(plain, quote(100.0), fast, trading)
        assertTrue(slow.evaluate(plain, quote(103.0), fast, trading + 6 * 60_000).isEmpty())
        // 急跌
        val drop = AlertEngine()
        drop.evaluate(plain, quote(100.0), fast, trading)
        assertEquals("SPEED_DOWN", drop.evaluate(plain, quote(97.0), fast, trading + 60_000).single().kind)
        // 默认关闭
        val off = AlertEngine()
        off.evaluate(plain, quote(100.0), settings, trading)
        assertTrue(off.evaluate(plain, quote(120.0), settings, trading + 60_000).isEmpty())
    }

    @Test fun limitAlerts() {
        val on = settings.copy(limitAlert = true)
        val plain = StockConfig("sh600519")
        val q = quote(110.0).copy(limitUp = 110.0, limitDown = 90.0)
        assertEquals("LIMIT_UP", AlertEngine().evaluate(plain, q, on, trading).single().kind)
        assertEquals("LIMIT_DOWN", AlertEngine().evaluate(plain, q.copy(price = 90.0), on, trading).single().kind)
        assertTrue(AlertEngine().evaluate(plain, q, settings, trading).isEmpty())
        // 指数没有涨跌停价
        assertTrue(AlertEngine().evaluate(plain, q.copy(limitUp = 0.0, limitDown = 0.0), on, trading).isEmpty())
    }

    @Test fun plainStockNeverAlerts() {
        assertTrue(AlertEngine().evaluate(StockConfig("sh600519"), quote(999.0), settings, trading).isEmpty())
    }

    @Test fun repeatInterval() {
        val e = AlertEngine()
        val s = settings.copy(alertRepeatMin = 5)
        assertEquals(1, e.evaluate(stock, quote(111.0), s, trading).size)
        assertTrue(e.evaluate(stock, quote(111.0), s, trading + 4 * 60_000).isEmpty())
        assertEquals(1, e.evaluate(stock, quote(111.0), s, trading + 5 * 60_000).size)
    }

    @Test fun changingThresholdRefires() {
        val e = AlertEngine()
        assertEquals(1, e.evaluate(stock, quote(111.0), settings, trading).size)
        assertEquals(1, e.evaluate(stock.copy(targetHigh = 111.0), quote(111.0), settings, trading + 1000).size)
    }

    @Test fun staleDataIsSuppressedUntilTrading() {
        val e = AlertEngine()
        // 非交易时段
        val evening = trading + 10 * 3600_000
        assertTrue(e.evaluate(stock, quote(111.0), settings, evening).isEmpty())
        // 交易时间但行情日期是旧的（节假日）
        assertTrue(e.evaluate(stock, quote(111.0, "20261106150000"), settings, trading).isEmpty())
        // 开盘后条件仍满足，应提醒
        assertEquals(1, e.evaluate(stock, quote(111.0), settings, trading).size)
        // 关闭“仅交易时段”后不做过滤
        val anyTime = settings.copy(alertTradingOnly = false)
        assertEquals(1, AlertEngine().evaluate(stock, quote(111.0), anyTime, evening).size)
    }

    @Test fun modesAndDisabled() {
        assertTrue(AlertEngine().evaluate(stock.copy(enabled = false), quote(111.0), settings, trading).isEmpty())
        assertTrue(AlertEngine().evaluate(stock, quote(0.0), settings, trading).isEmpty())
        assertTrue(AlertEngine().evaluate(stock.copy(alertModes = emptyList()), quote(111.0), settings, trading).isEmpty())
        val custom = stock.copy(alertModes = listOf("FULLSCREEN"))
        assertEquals(listOf("FULLSCREEN"), AlertEngine().evaluate(custom, quote(111.0), settings, trading).single().modes)
    }
}
