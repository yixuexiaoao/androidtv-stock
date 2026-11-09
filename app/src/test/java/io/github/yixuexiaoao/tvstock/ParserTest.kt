package io.github.yixuexiaoao.tvstock

import io.github.yixuexiaoao.tvstock.data.QuoteParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun paddedNamesAreCleaned() {
        assertEquals("五粮液", QuoteParser.parseTencent(tencent.replace("贵州茅台", "五 粮 液"))[0].name)
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

    private val usTencent = "v_usAAPL=\"200~苹果~AAPL.OQ~342.37~344.17~338.73~44703806~0~0~342.65~480~0~0~0~0~0~0~0~0~342.82~80~0~0~0~0~0~0~0~0~~2026-11-06 16:00:01~-1.80~-0.52~345.79~338.30~USD~44703806~15326963640~0.31~38.13~~38.13~~2.18~49722.77471~49753.83151~Apple Inc.~8.98~355.38~242.76~400~44.39\";"

    @Test fun tencentUsFields() {
        val q = QuoteParser.parseTencent(usTencent).single()
        assertEquals("usAAPL", q.code)
        assertEquals("苹果", q.name)
        assertEquals("AAPL.OQ", q.exchange)
        assertEquals(342.37, q.price, 1e-6)
        assertEquals(344.17, q.prevClose, 1e-6)
        assertEquals(338.73, q.open, 1e-6)
        assertEquals(345.79, q.high, 1e-6)
        assertEquals(338.30, q.low, 1e-6)
        assertEquals(-1.80, q.change, 1e-6)
        assertEquals(-0.52, q.changePct, 1e-6)
        assertEquals(44703806.0, q.volume, 1e-6)
        assertEquals(1532696.364, q.amountWan, 1e-3)
        assertEquals(0.31, q.turnover!!, 1e-6)
        assertEquals(38.13, q.pe!!, 1e-6)
        assertEquals(2.18, q.amplitude!!, 1e-6)
        assertEquals(49753.83151, q.totalCapYi!!, 1e-3)
        assertEquals(342.65, q.bids.single().price, 1e-6)
        assertEquals(342.82, q.asks.single().price, 1e-6)
        assertEquals("20261106160001", q.time)
        assertEquals("20261106", q.tradeDate)
        assertNull(q.limitUp)
        assertNull(q.limitDown)
    }

    @Test fun tencentMixesMarketsAndKeepsDotsInCodes() {
        val index = usTencent.replace("v_usAAPL", "v_us.DJI").replace("AAPL.OQ", ".DJI")
        val cn = "v_sh600519=\"1~贵州茅台~600519~1444.11~1453.00~1440.06~6001~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~20261109094855~-8.89~-0.61~1449.46~1435.08~1444.11/6001/1~6001~86527~0~0~\";"
        assertEquals(listOf("usAAPL", "us.DJI", "sh600519"), QuoteParser.parseTencent(usTencent + index + cn).map { it.code })
    }

    @Test fun lowPricedUsStockUsesFourDecimals() {
        assertEquals(4, QuoteParser.parseTencent(usTencent.replace("~342.37~", "~0.1234~")).single().decimals)
        assertEquals(2, QuoteParser.parseTencent(usTencent).single().decimals)
    }

    @Test fun sinaUsFieldsAndCodes() {
        val raw = "var hq_str_gb_aapl=\"苹果,342.3700,-0.52,2026-11-09 22:02:55,-1.8000,338.7300,345.7900,338.3000,355.3800,242.8900,44703764,56680400,4975409442959,8.30,41.250000,0.00,0.68,0.00,0.00,14532258793,63,344.3600,0.58,1.99,Nov 09 09:02AM EST,Nov 06 04:00PM EST,344.1700,626953,1,2026,15323827230.3377,345.4100\";\n" +
            "var hq_str_gb_brk\$b=\"伯克希尔B,570.8900,-0.25,2026-11-09 22:02:55,-1.4400,562.0650,575.1200,559.6100,586.3000,464.0100,5417429,5330164,1222275490000,33.59,17.000000,0.00,-0.03,0.00,0.00,2141000000,69,569.5300,-0.27,-1.54,Nov 09 09:02AM EST,Nov 06 12:03PM EST,572.3300,54565,1,2026,3089881994.9650\";\n" +
            "var hq_str_gb_dji=\"道琼斯,51285.7305,-0.46,2026-11-07 05:37:24,-237.3300,51078.8516,51662.2617,50992.6094,54744.3281,45057.2812,523301136,543886114,0,0.00,--,0.00,0.00,0.00,0.00,0,0,0.0000,0.00,0.0000,,Nov 6 04:37PM EST,51523.0586,0,1,2026\";"
        val list = QuoteParser.parseSina(raw)
        assertEquals(listOf("usAAPL", "usBRK.B", "us.DJI"), list.map { it.code })
        val q = list[0]
        assertEquals(342.37, q.price, 1e-6)
        assertEquals(344.17, q.prevClose, 1e-6)
        assertEquals(-1.80, q.change, 1e-6)
        assertEquals(-0.52, q.changePct, 1e-6)
        assertEquals(44703764.0, q.volume, 1e-6)
        assertEquals(49754.09, q.totalCapYi!!, 0.01)
        assertEquals(41.25, q.pe!!, 1e-6)
        assertEquals("20261106160000", q.time)
        assertEquals("", q.exchange)
        assertEquals("20261106120300", list[1].time)
        assertEquals("20261106163700", list[2].time)
        assertNull(list[2].pe)
    }

    @Test fun sinaCodeMapping() {
        assertEquals("sh600519", QuoteParser.sinaCode("sh600519"))
        assertEquals("gb_aapl", QuoteParser.sinaCode("usAAPL"))
        assertEquals("gb_brk\$b", QuoteParser.sinaCode("usBRK.B"))
        assertEquals("gb_dji", QuoteParser.sinaCode("us.DJI"))
    }

    @Test fun usMinuteHasNoAmount() {
        val raw = """{"code":0,"data":{"usAAPL":{"data":{"data":["0930 340.47 1129415","1600 342.37 44703806"],"date":"20261106"}}}}"""
        val list = QuoteParser.parseMinute(raw, "usAAPL")
        assertEquals(2, list.size)
        assertEquals("1600", list[1].hhmm)
        assertEquals(0.0, list[1].avgPrice, 1e-9)
    }

    @Test fun klineDropsPreOpenRowWithZeroPrices() {
        val raw = """{"data":{"usAAPL.OQ":{"qfqday":[["2026-11-06","338.73","342.37","345.79","338.30","44703806"],["2026-11-09","0.00","342.37","0.00","0.00","669600"]]}}}"""
        assertEquals(listOf("2026-11-06"), QuoteParser.parseKline(raw, "usAAPL.OQ", "day").map { it.date })
    }

    @Test fun usKlineUsesExchangeKey() {
        val raw = """{"code":0,"data":{"usAAPL.OQ":{"qfqweek":[["2026-11-06","342.67","342.37","355.38","325.33","337938217.00"]]}}}"""
        assertEquals(342.37, QuoteParser.parseKline(raw, "usAAPL.OQ", "week").single().close, 1e-6)
    }

    @Test fun suggestIncludesUsStocksAndIndexes() {
        val raw = "v_hint=\"us~aapl.oq~\\u82f9\\u679c~pg~GP^us~dji~\\u9053\\u743c\\u65af~dqs~ZS^us~brk.b.n~B~b~GP\";"
        val hits = QuoteParser.parseSuggest(raw)
        assertEquals(listOf("usAAPL", "us.DJI", "usBRK.B"), hits.map { it.code })
        assertEquals("苹果", hits[0].name)
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
