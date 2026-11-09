package io.github.yixuexiaoao.tvstock

import io.github.yixuexiaoao.tvstock.data.QuoteParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 样本取自 2026-11-09 的真实接口响应。 */
class ParserTest {
    private val tencent = "v_sh600519=\"1~贵州茅台~600519~1444.11~1453.00~1440.06~6001~3105~2894~1442.97~7~1442.60~1~1442.35~1~1442.00~3~1441.20~1~1444.19~1~1444.21~1~1444.27~2~1444.64~2~1444.65~4~~20261109094855~-8.89~-0.61~1449.46~1435.08~1444.11/6001/865275141~6001~86528~0.05~21.97~~1449.46~1435.08~0.99~18052.55~18052.55~6.66~1598.30~1307.70~1.22~3~1441.88~21.00~21.93\";\n" +
        "v_pv_none_match=\"1\";\n"

    @Test fun tencentFields() {
        val list = QuoteParser.parseTencent(tencent)
        assertEquals(1, list.size)
        val q = list[0]
        assertEquals("sh600519", q.code)
        assertEquals("贵州茅台", q.name)
        assertEquals(1444.11, q.price, 1e-6)
        assertEquals(1453.00, q.prevClose, 1e-6)
        assertEquals(1440.06, q.open, 1e-6)
        assertEquals(1449.46, q.high, 1e-6)
        assertEquals(1435.08, q.low, 1e-6)
        assertEquals(-8.89, q.change, 1e-6)
        assertEquals(-0.61, q.changePct, 1e-6)
        assertEquals(6001.0, q.volume, 1e-6)
        assertEquals(86528.0, q.amountWan, 1e-6)
        assertEquals(0.05, q.turnover!!, 1e-6)
        assertEquals(21.97, q.pe!!, 1e-6)
        assertEquals(0.99, q.amplitude!!, 1e-6)
        assertEquals(18052.55, q.totalCapYi!!, 1e-6)
        assertEquals(1598.30, q.limitUp!!, 1e-6)
        assertEquals(1307.70, q.limitDown!!, 1e-6)
        assertEquals(1.22, q.volumeRatio!!, 1e-6)
        assertEquals(1442.97, q.bids[0].price, 1e-6)
        assertEquals(7.0, q.bids[0].volume, 1e-6)
        assertEquals(1444.19, q.asks[0].price, 1e-6)
        assertEquals(1441.20, q.bids[4].price, 1e-6)
        assertEquals(1444.65, q.asks[4].price, 1e-6)
        assertEquals("20261109", q.tradeDate)
        assertEquals(2, q.decimals)
    }

    @Test fun etfUsesThreeDecimals() {
        val raw = tencent.replace("~1444.11~1453.00", "~1.234~1.230")
        assertEquals(3, QuoteParser.parseTencent(raw)[0].decimals)
    }

    @Test fun suspendedStockHasNoTrade() {
        val raw = tencent.replace("~1444.11~1453.00", "~0.00~1453.00")
        val q = QuoteParser.parseTencent(raw)[0]
        assertFalse(q.hasTrade)
        assertEquals(1453.00, q.displayPrice, 1e-6)
        assertEquals(0.0, q.changePct, 1e-6)
    }

    @Test fun garbageIsIgnored() {
        assertTrue(QuoteParser.parseTencent("<html>error</html>").isEmpty())
        assertTrue(QuoteParser.parseSina("var hq_str_sh999999=\"\";").isEmpty())
    }

    @Test fun sinaFields() {
        val raw = "var hq_str_sh600519=\"贵州茅台,1440.060,1453.000,1444.110,1449.460,1435.080,1442.970,1444.190,600100,865275141.000,700,1442.970,100,1442.600,100,1442.350,300,1442.000,100,1441.200,100,1444.190,100,1444.210,200,1444.270,200,1444.640,400,1444.650,2026-11-09,09:48:55,00,\";"
        val q = QuoteParser.parseSina(raw).single()
        assertEquals(1444.11, q.price, 1e-6)
        assertEquals(1453.0, q.prevClose, 1e-6)
        assertEquals(6001.0, q.volume, 1e-6)
        assertEquals(86527.5141, q.amountWan, 1e-6)
        assertEquals(-0.6118, q.changePct, 1e-3)
        assertEquals(7.0, q.bids[0].volume, 1e-6)
        assertEquals(1442.97, q.bids[0].price, 1e-6)
        assertEquals(1444.19, q.asks[0].price, 1e-6)
        assertEquals(1.0, q.asks[0].volume, 1e-6)
        assertEquals("20261109094855", q.time)
        assertEquals(2, q.decimals)
    }

    @Test fun minuteSeries() {
        val raw = """{"code":0,"msg":"","data":{"sh600519":{"data":{"data":["0930 1440.06 186 26785115.56","0931 1440.99 1110 159723413.00","bad"]}}}}"""
        val list = QuoteParser.parseMinute(raw, "sh600519")
        assertEquals(2, list.size)
        assertEquals("0931", list[1].hhmm)
        assertEquals(1440.99, list[1].price, 1e-6)
        assertEquals(1110.0, list[1].cumVolume, 1e-6)
        assertEquals(1440.06, list[0].avgPrice, 0.01)
    }

    @Test fun klineStockAndIndexKeys() {
        val stock = """{"data":{"sh600519":{"qfqday":[["2026-11-04","1470.000","1446.440","1480.600","1439.000","65434.000"],["2026-11-09","1440.06","1444.11","1449.46","1435.08","6001"]]}}}"""
        val k = QuoteParser.parseKline(stock, "sh600519", "day")
        assertEquals(2, k.size)
        assertEquals(1470.0, k[0].open, 1e-6)
        assertEquals(1446.44, k[0].close, 1e-6)
        assertEquals(1480.6, k[0].high, 1e-6)
        assertEquals(1439.0, k[0].low, 1e-6)

        val index = """{"data":{"sh000001":{"week":[["2026-11-06","3900.00","3950.00","3960.00","3890.00","100"]]}}}"""
        assertEquals(1, QuoteParser.parseKline(index, "sh000001", "week").size)
        assertTrue(QuoteParser.parseKline("""{"data":{}}""", "sh600519", "day").isEmpty())
    }

    @Test fun suggestDecodesUnicodeAndFiltersMarkets() {
        val raw = "v_hint=\"sh~600519~\\u8d35\\u5dde\\u8305\\u53f0~gzmt~GP-A^hk~00700~\\u817e\\u8baf~tx~GP\";"
        val hits = QuoteParser.parseSuggest(raw)
        assertEquals(1, hits.size)
        assertEquals("sh600519", hits[0].code)
        assertEquals("贵州茅台", hits[0].name)
        assertTrue(QuoteParser.parseSuggest("v_hint=\"N\";").isEmpty())
    }
}
