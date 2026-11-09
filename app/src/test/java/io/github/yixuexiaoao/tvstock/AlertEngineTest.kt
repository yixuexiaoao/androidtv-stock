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

    @Test fun firesOnceThenRearmsAfterLeaving() {
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
