package io.github.yixuexiaoao.tvstock

import io.github.yixuexiaoao.tvstock.data.HeatmapParser
import io.github.yixuexiaoao.tvstock.model.HeatmapWeights
import io.github.yixuexiaoao.tvstock.model.Quote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatmapParserTest {
    @Test
    fun parseEastMoneySectorsAndWeights() {
        val sample = """
            {"rc":0,"data":{"total":2,"diff":[
              {"f12":"BK1283","f14":"银行","f3":-0.85,"f6":13436507644.0,"f20":17859689984000,"f21":11549697792000,"f104":3,"f105":37,"f106":2,"f128":"建设银行","f136":1.13},
              {"f12":"BK1201","f14":"电子","f3":-3.16,"f6":51000000000.0,"f20":8000000000000,"f21":6000000000000,"f104":51,"f105":470,"f106":2,"f128":"寒武纪","f136":"-"}
            ]}}
        """.trimIndent()
        val byCap = HeatmapParser.parseEastMoneySectors(sample, HeatmapWeights.CIRC_CAP)
        assertEquals(2, byCap.size)
        assertEquals("BK1283", byCap[0].id)
        assertEquals("银行", byCap[0].name)
        assertEquals(-0.85, byCap[0].changePct, 1e-6)
        assertEquals(11549697792000.0, byCap[0].weight, 1.0)
        assertEquals(3, byCap[0].upCount)
        assertEquals(37, byCap[0].downCount)
        assertEquals("建设银行", byCap[0].leaderName)
        assertTrue(byCap[0].isSector)
        // "-" 字段安全回退为 0.0
        assertEquals(0.0, byCap[1].leaderPct, 1e-6)

        val byAmt = HeatmapParser.parseEastMoneySectors(sample, HeatmapWeights.AMOUNT)
        assertEquals(51000000000.0, byAmt[1].weight, 1.0)
    }

    @Test
    fun parseEastMoneyStocksNormalizesCodesAndGroupsByIndustry() {
        val sample = """
            {"rc":0,"data":{"total":3,"diff":[
              {"f12":"601398","f13":1,"f14":"工 商 银 行","f2":9.14,"f3":0.44,"f6":1450050737.0,"f20":3257553189793,"f21":2464255622606,"f100":"银行Ⅱ"},
              {"f12":"300750","f13":0,"f14":"宁德时代","f2":228.56,"f3":-2.57,"f6":3602595852.34,"f20":1057604188232,"f21":973806640490,"f100":"电池"},
              {"f12":"830799","f13":0,"f14":"艾融软件","f2":25.0,"f3":3.2,"f6":200000000.0,"f20":5000000000,"f21":3000000000,"f100":"软件开发Ⅲ"}
            ]}}
        """.trimIndent()
        val stocks = HeatmapParser.parseEastMoneyStocks(sample, HeatmapWeights.CIRC_CAP)
        assertEquals(3, stocks.size)
        assertEquals("sh601398", stocks[0].id)
        assertEquals("工商银行", stocks[0].name)
        assertEquals("银行", stocks[0].groupName)
        assertEquals("sz300750", stocks[1].id)
        assertEquals("bj830799", stocks[2].id)
        assertEquals("软件开发", stocks[2].groupName)

        val groups = HeatmapParser.groupByIndustry(stocks)
        assertEquals(3, groups.size)
        assertEquals("银行", groups[0].name)
        assertEquals(0.44, groups[0].changePct, 1e-6)
    }

    @Test
    fun singleGroupFlattensToEmptyGroupList() {
        val sample = """
            {"rc":0,"data":{"total":2,"diff":[
              {"f12":"601398","f13":1,"f14":"工商银行","f2":9.14,"f3":0.44,"f6":100,"f20":200,"f21":150,"f100":"银行Ⅱ"},
              {"f12":"601288","f13":1,"f14":"农业银行","f2":7.65,"f3":0.0,"f6":100,"f20":200,"f21":150,"f100":"银行Ⅱ"}
            ]}}
        """.trimIndent()
        val stocks = HeatmapParser.parseEastMoneyStocks(sample)
        assertTrue(HeatmapParser.groupByIndustry(stocks).isEmpty())
    }

    @Test
    fun parseEastMoneyBreadthSumsThreeMarkets() {
        val sample = """
            {"data":{"diff":[
              {"f12":"000001","f6":299889951093.5,"f104":107,"f105":2240,"f106":8},
              {"f12":"399001","f6":336660000461.5,"f104":135,"f105":2798,"f106":5},
              {"f12":"899050","f6":4693193661.0,"f104":26,"f105":329,"f106":1}
            ]}}
        """.trimIndent()
        val b = HeatmapParser.parseEastMoneyBreadth(sample)
        assertEquals(268, b.upCount)
        assertEquals(14, b.flatCount)
        assertEquals(5367, b.downCount)
        assertEquals(641243145216.0, b.totalAmountYuan, 1.0)
    }

    @Test
    fun parseSinaFallbackSectorsAndStocks() {
        val hy = """var S_Finance_bankuai_sinaindustry = {"new_blhy":"new_blhy,玻璃行业,19,12.17,-0.35,-2.82,215413496,2721671909,sz002623,-0.058,17.22,-0.01,亚玛顿"};"""
        val sectors = HeatmapParser.parseSinaSectors(hy)
        assertEquals(1, sectors.size)
        assertEquals("new_blhy", sectors[0].id)
        assertEquals("玻璃行业", sectors[0].name)
        assertEquals(-2.82, sectors[0].changePct, 1e-6)
        assertEquals("亚玛顿", sectors[0].leaderName)
        assertTrue(sectors[0].isSector)

        val st = """[{"symbol":"sh601398","code":"601398","name":"工商银行","trade":"9.140","changepercent":0.44,"amount":1455199725,"mktcap":325755318.9,"nmc":246425562.2}]"""
        val stocks = HeatmapParser.parseSinaStocks(st, HeatmapWeights.CIRC_CAP)
        assertEquals(1, stocks.size)
        assertEquals("sh601398", stocks[0].id)
        assertEquals(9.14, stocks[0].price, 1e-6)
        assertEquals(246425562.2 * 10_000.0, stocks[0].circCapYuan, 10.0)
    }

    @Test
    fun parseEastMoneyUsAndAggregateGicsSectors() {
        val sample = """
            {"rc":0,"data":{"total":3,"diff":[
              {"f12":"AAPL","f13":105,"f14":"苹果","f2":349.21,"f3":-0.92,"f6":14301192448.0,"f20":5074783276220,"f21":5074783276220,"f100":"信息技术"},
              {"f12":"MSFT","f13":105,"f14":"微软","f2":498.3,"f3":1.5,"f6":14904513536.0,"f20":3700472745262,"f21":3700472745262,"f100":"信息技术"},
              {"f12":"LLY","f13":106,"f14":"礼来","f2":1351.12,"f3":-2.29,"f6":4348373760.0,"f20":1271059426285,"f21":1271059426285,"f100":"医疗保健"}
            ]}}
        """.trimIndent()
        val stocks = HeatmapParser.parseEastMoneyUsStocks(sample, HeatmapWeights.CIRC_CAP)
        assertEquals(3, stocks.size)
        assertEquals("usAAPL", stocks[0].id)
        assertEquals("信息技术", stocks[0].groupName)

        val sectors = HeatmapParser.aggregateUsSectors(stocks)
        assertEquals(2, sectors.size)
        assertEquals(HeatmapParser.US_SECTOR_PREFIX + "信息技术", sectors[0].id)
        assertEquals("信息技术", sectors[0].name)
        assertEquals(1, sectors[0].upCount)
        assertEquals(1, sectors[0].downCount)
        assertEquals("微软", sectors[0].leaderName)
        assertTrue(sectors[0].isSector)
    }

    @Test
    fun parseSinaUsFallback() {
        val jsonp = """/*<script>*/IO({"count":"1","data":[{"name":"Apple Inc.","cname":"苹果公司","category":"计算机","symbol":"AAPL","price":"349.21","chg":"-0.92","volume":"40906840","mktcap":"5071586054264"}]})"""
        val stocks = HeatmapParser.parseSinaUsStocks(jsonp, HeatmapWeights.CIRC_CAP)
        assertEquals(1, stocks.size)
        assertEquals("usAAPL", stocks[0].id)
        assertEquals("苹果公司", stocks[0].name)
        assertEquals("计算机", stocks[0].groupName)
        assertEquals(-0.92, stocks[0].changePct, 1e-6)
        assertEquals(5071586054264.0, stocks[0].circCapYuan, 10.0)
    }

    @Test
    fun applyUsExtendedUpdatesCloudItemsAndSessionLabel() {
        val sample = """
            {"rc":0,"data":{"total":2,"diff":[
              {"f12":"AAPL","f13":105,"f14":"苹果","f2":349.21,"f3":-0.92,"f6":14301192448.0,"f20":5074783276220,"f21":5074783276220,"f100":"信息技术"},
              {"f12":"NVDA","f13":105,"f14":"英伟达","f2":145.98,"f3":-3.30,"f6":48724731064.0,"f20":3524979059999,"f21":3524979059999,"f100":"信息技术"}
            ]}}
        """.trimIndent()
        val base = HeatmapParser.parseEastMoneyUsStocks(sample)
        val extMap = mapOf(
            "usAAPL" to Quote("usAAPL", "苹果", 349.21, 352.45, 352.91, 355.28, 346.47, -3.24, -0.92, 1.0, 1.0, extPrice = 350.22, extChange = 1.01, extChangePct = 0.29, extSession = "PRE"),
            "usNVDA" to Quote("usNVDA", "英伟达", 145.98, 150.96, 152.92, 158.13, 145.71, -4.98, -3.30, 1.0, 1.0, extPrice = 142.29, extChange = -3.69, extChangePct = -2.53, extSession = "PRE"),
        )
        val (label, merged) = HeatmapParser.applyUsExtended(base, extMap)
        assertEquals("盘前", label)
        assertEquals(350.22, merged[0].price, 1e-6)
        assertEquals(0.29, merged[0].changePct, 1e-6)
        assertEquals("盘前 350.22", merged[0].badge)
        val sectors = HeatmapParser.aggregateUsSectors(merged)
        assertEquals("苹果", sectors[0].leaderName)
        assertEquals(1, sectors[0].upCount)
        assertEquals(1, sectors[0].downCount)
    }
}
