package io.github.yixuexiaoao.tvstock

import io.github.yixuexiaoao.tvstock.alert.SoundPresets
import io.github.yixuexiaoao.tvstock.model.AppConfig
import io.github.yixuexiaoao.tvstock.model.PortfolioSummary
import io.github.yixuexiaoao.tvstock.model.Quote
import io.github.yixuexiaoao.tvstock.model.StockConfig
import io.github.yixuexiaoao.tvstock.model.StockSort
import io.github.yixuexiaoao.tvstock.model.TradeError
import io.github.yixuexiaoao.tvstock.model.Trades
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TradesTest {
    private val base = StockConfig("sh600519", shares = 100, costPrice = 100.0)

    @Test fun buyAveragesCost() {
        val s = Trades.apply(base, Trades.BUY, 120.0, 300, 1)
        assertEquals(400L, s.shares)
        assertEquals(115.0, s.costPrice!!, 1e-9)
        assertEquals(0.0, s.realizedProfit, 1e-9)
        assertNull(s.trades.single().profit)
    }

    @Test fun firstBuySetsCost() {
        val s = Trades.apply(StockConfig("sh600519"), Trades.BUY, 50.0, 100, 1)
        assertEquals(100L, s.shares)
        assertEquals(50.0, s.costPrice!!, 1e-9)
    }

    @Test fun sellKeepsCostAndRealizes() {
        val s = Trades.apply(base, Trades.SELL, 110.0, 40, 1)
        assertEquals(60L, s.shares)
        assertEquals(100.0, s.costPrice!!, 1e-9)
        assertEquals(400.0, s.realizedProfit, 1e-9)
        assertEquals(400.0, s.trades.single().profit!!, 1e-9)
    }

    @Test fun clearEmptiesHoldingButKeepsMonitoring() {
        val s = Trades.apply(base.copy(targetHigh = 150.0), Trades.CLEAR, 90.0, null, 1)
        assertNull(s.shares)
        assertNull(s.costPrice)
        assertEquals(-1000.0, s.realizedProfit, 1e-9)
        assertEquals(150.0, s.targetHigh!!, 1e-9)
    }

    @Test fun sellingEverythingAlsoClearsCost() {
        val s = Trades.apply(base, Trades.SELL, 100.0, 100, 1)
        assertNull(s.shares)
        assertNull(s.costPrice)
    }

    @Test fun invalidInputRejected() {
        assertThrows(TradeError::class.java) { Trades.apply(base, Trades.BUY, 0.0, 100, 1) }
        assertThrows(TradeError::class.java) { Trades.apply(base, Trades.BUY, Double.NaN, 100, 1) }
        assertThrows(TradeError::class.java) { Trades.apply(base, Trades.BUY, 10.0, null, 1) }
        assertThrows(TradeError::class.java) { Trades.apply(base, Trades.SELL, 10.0, 101, 1) }
        assertThrows(TradeError::class.java) { Trades.apply(StockConfig("sh600519"), Trades.CLEAR, 10.0, null, 1) }
        assertThrows(TradeError::class.java) { Trades.apply(base, "HOLD", 10.0, 1, 1) }
        assertThrows(TradeError::class.java) { Trades.undo(base) }
    }

    @Test fun undoRestoresPreviousState() {
        val bought = Trades.apply(base, Trades.BUY, 120.0, 100, 1)
        val cleared = Trades.apply(bought, Trades.CLEAR, 130.0, null, 2)
        val undoClear = Trades.undo(cleared)
        assertEquals(200L, undoClear.shares)
        assertEquals(110.0, undoClear.costPrice!!, 1e-9)
        assertEquals(0.0, undoClear.realizedProfit, 1e-9)
        assertEquals(base.copy(), Trades.undo(undoClear))
    }

    @Test fun recordsSurviveSerializationAndAreBounded() {
        var s = StockConfig("sh600519")
        repeat(60) { s = Trades.apply(s, Trades.BUY, 10.0, 100, it.toLong()) }
        assertEquals(50, s.trades.size)
        val restored = StockConfig.fromJson(JSONObject(s.toJson().toString()))!!
        assertEquals(s, restored)
        val cfg = AppConfig.fromJson(JSONObject(AppConfig(stocks = listOf(s)).toJson().toString()))
        assertEquals(s, cfg.stocks.single())
    }

    private fun quote(code: String, pct: Double) =
        Quote(code, code, 100.0 + pct, 100.0, 100.0, 100.0, 100.0, pct, pct, 1.0, 1.0)

    @Test fun portfolioIncludesRealizedFromClearedStocks() {
        val cleared = Trades.apply(base, Trades.CLEAR, 110.0, null, 1)
        val p = PortfolioSummary.of(listOf(cleared), emptyMap())!!
        assertEquals(1000.0, p.realized, 1e-9)
        assertEquals(0, p.count)
    }

    @Test fun sorting() {
        val a = StockConfig("sh600001")
        val b = StockConfig("sh600002", shares = 1000)
        val c = StockConfig("sh600003")
        val none = StockConfig("sh600004")
        val quotes = mapOf(a.code to quote(a.code, 1.0), b.code to quote(b.code, -5.0), c.code to quote(c.code, 3.0))
        val list = listOf(none, a, b, c)
        fun order(mode: String) = StockSort.apply(list, quotes, mode).map { it.code.last() }.joinToString("")
        assertEquals("4123", order(StockSort.MANUAL))
        assertEquals("3124", order(StockSort.PCT_DESC))
        assertEquals("2134", order(StockSort.PCT_ASC))
        assertEquals("2314", order(StockSort.ACTIVE))
        assertEquals("2134", order(StockSort.PROFIT))
    }

    @Test fun soundsAreDistinct() {
        val rise = SoundPresets.pcm(SoundPresets.RISE)
        val fall = SoundPresets.pcm(SoundPresets.FALL)
        assertTrue(rise.isNotEmpty())
        assertEquals(rise.size, fall.size)
        assertFalse(rise.contentEquals(fall))
        assertTrue(SoundPresets.pcm(SoundPresets.NONE).isEmpty())
        assertTrue(SoundPresets.pcm("unknown").isEmpty())
        assertTrue(SoundPresets.pcm(SoundPresets.RISE, 2).size > rise.size * 2)
        SoundPresets.ALL.forEach { id -> SoundPresets.pcm(id, 5) }
    }
}
