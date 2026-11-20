package io.github.yixuexiaoao.tvstock.data

import io.github.yixuexiaoao.tvstock.model.HeatmapGroup
import io.github.yixuexiaoao.tvstock.model.HeatmapItem
import io.github.yixuexiaoao.tvstock.model.HeatmapWeights
import io.github.yixuexiaoao.tvstock.model.MarketBreadth
import io.github.yixuexiaoao.tvstock.model.StockCodes
import org.json.JSONArray
import org.json.JSONObject

/** 大盘云图公开接口响应解析（纯 JVM 函数，便于单元测试）。 */
object HeatmapParser {
    private fun num(obj: JSONObject, key: String): Double {
        if (!obj.has(key) || obj.isNull(key)) return 0.0
        val v = when (val raw = obj.opt(key)) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
        return if (v.isFinite()) v else 0.0
    }

    private fun intNum(obj: JSONObject, key: String): Int = num(obj, key).toInt()

    private fun cleanGroupName(raw: String): String =
        raw.trim().takeIf { it.isNotEmpty() && it != "-" }
            ?.removeSuffix("Ⅲ")?.removeSuffix("Ⅱ")
            .orEmpty()

    private fun chooseWeight(weightMode: String, circCap: Double, totalCap: Double, amount: Double): Double {
        val primary = if (weightMode == HeatmapWeights.AMOUNT) amount else circCap
        return when {
            primary > 0.0 -> primary
            circCap > 0.0 -> circCap
            totalCap > 0.0 -> totalCap
            amount > 0.0 -> amount
            else -> 1.0
        }
    }

    private fun diffArray(text: String): JSONArray? =
        JSONObject(text).optJSONObject("data")?.optJSONArray("diff")

    /** 解析东方财富 31 个申万一级行业列表（fs=m:90+t:2+s:2）。 */
    fun parseEastMoneySectors(text: String, weightMode: String = HeatmapWeights.CIRC_CAP): List<HeatmapItem> {
        val diff = diffArray(text) ?: return emptyList()
        val out = ArrayList<HeatmapItem>(diff.length())
        for (i in 0 until diff.length()) {
            val o = diff.optJSONObject(i) ?: continue
            val id = o.optString("f12").trim()
            val name = o.optString("f14").trim()
            if (id.isEmpty() || name.isEmpty()) continue
            val pct = num(o, "f3")
            val amount = num(o, "f6")
            val totalCap = num(o, "f20")
            val circCap = num(o, "f21")
            out += HeatmapItem(
                id = id,
                name = name,
                changePct = pct,
                weight = chooseWeight(weightMode, circCap, totalCap, amount),
                amountYuan = amount,
                circCapYuan = circCap,
                upCount = intNum(o, "f104"),
                downCount = intNum(o, "f105"),
                flatCount = intNum(o, "f106"),
                leaderName = o.optString("f128").trim().takeIf { it != "-" }.orEmpty(),
                leaderPct = num(o, "f136"),
                isSector = true,
            )
        }
        return out
    }

    /** 解析东方财富板块/指数成分股列表（每只股票自带所属行业 f100）。 */
    fun parseEastMoneyStocks(text: String, weightMode: String = HeatmapWeights.CIRC_CAP): List<HeatmapItem> {
        val diff = diffArray(text) ?: return emptyList()
        val out = ArrayList<HeatmapItem>(diff.length())
        for (i in 0 until diff.length()) {
            val o = diff.optJSONObject(i) ?: continue
            val rawCode = o.optString("f12").trim()
            val name = o.optString("f14").trim().replace(" ", "")
            if (rawCode.isEmpty() || name.isEmpty()) continue
            val marketNum = intNum(o, "f13")
            val code = if (marketNum == 1) "sh$rawCode" else (StockCodes.normalize(rawCode) ?: "sz$rawCode")
            val price = num(o, "f2")
            val pct = num(o, "f3")
            val amount = num(o, "f6")
            val totalCap = num(o, "f20")
            val circCap = num(o, "f21")
            out += HeatmapItem(
                id = code,
                name = name,
                changePct = pct,
                weight = chooseWeight(weightMode, circCap, totalCap, amount),
                price = price,
                decimals = 2,
                amountYuan = amount,
                circCapYuan = circCap,
                groupName = cleanGroupName(o.optString("f100")),
                isSector = false,
            )
        }
        return out
    }

    /** 解析东方财富上证+深证+北证指数合并的涨跌家数与总成交额。 */
    fun parseEastMoneyBreadth(text: String): MarketBreadth {
        val diff = diffArray(text) ?: return MarketBreadth()
        var up = 0
        var down = 0
        var flat = 0
        var amount = 0.0
        for (i in 0 until diff.length()) {
            val o = diff.optJSONObject(i) ?: continue
            up += intNum(o, "f104")
            down += intNum(o, "f105")
            flat += intNum(o, "f106")
            amount += num(o, "f6")
        }
        return MarketBreadth(up, flat, down, amount)
    }

    /** 根据个股列表自行汇总涨跌家数与成交额（用于板块下钻或新浪备源）。 */
    fun breadthOfStocks(items: List<HeatmapItem>): MarketBreadth {
        var up = 0
        var flat = 0
        var down = 0
        var amount = 0.0
        for (item in items) {
            when {
                item.changePct > 0.0 -> up++
                item.changePct < 0.0 -> down++
                else -> flat++
            }
            amount += item.amountYuan
        }
        return MarketBreadth(up, flat, down, amount)
    }

    /** 按行业名称聚合成两级分组；如果不足 2 个有效分组，返回空列表由界面直接平铺。 */
    fun groupByIndustry(items: List<HeatmapItem>): List<HeatmapGroup> {
        if (items.isEmpty()) return emptyList()
        val buckets = LinkedHashMap<String, MutableList<HeatmapItem>>()
        for (item in items) {
            val key = item.groupName.ifEmpty { "其他" }
            buckets.getOrPut(key) { ArrayList() }.add(item)
        }
        if (buckets.size < 2) return emptyList()
        return buckets.entries.map { (name, list) ->
            var sumW = 0.0
            var weightedPct = 0.0
            for (it in list) {
                val w = if (it.weight > 0.0) it.weight else 1.0
                sumW += w
                weightedPct += it.changePct * w
            }
            HeatmapGroup(
                name = name,
                items = list,
                changePct = if (sumW > 0.0) weightedPct / sumW else 0.0,
                weight = sumW,
            )
        }.sortedByDescending { it.weight }
    }

    /** 解析新浪行业列表备源（newSinaHy.php）。 */
    fun parseSinaSectors(text: String): List<HeatmapItem> {
        val body = text.substringAfter('=', "").trim().trimEnd(';')
        if (!body.startsWith("{")) return emptyList()
        val obj = JSONObject(body)
        val out = ArrayList<HeatmapItem>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val parts = obj.optString(key).split(',')
            if (parts.size < 13) continue
            val name = parts[1].trim()
            if (name.isEmpty()) continue
            val count = parts[2].trim().toIntOrNull() ?: 0
            val pct = parts[5].trim().toDoubleOrNull() ?: 0.0
            val amount = parts[7].trim().toDoubleOrNull() ?: 0.0
            val leaderPct = parts[9].trim().toDoubleOrNull() ?: 0.0
            val leaderName = parts[12].trim()
            out += HeatmapItem(
                id = parts[0].trim().ifEmpty { key },
                name = name,
                changePct = pct,
                weight = if (amount > 0.0) amount else maxOf(1, count).toDouble(),
                amountYuan = amount,
                leaderName = leaderName,
                leaderPct = leaderPct,
                isSector = true,
            )
        }
        return out
    }

    /** 解析新浪行业成分股备源（Market_Center.getHQNodeData）。 */
    fun parseSinaStocks(text: String, weightMode: String = HeatmapWeights.CIRC_CAP): List<HeatmapItem> {
        val trimmed = text.trim()
        if (!trimmed.startsWith("[")) return emptyList()
        val arr = JSONArray(trimmed)
        val out = ArrayList<HeatmapItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val code = StockCodes.normalize(o.optString("symbol").ifBlank { o.optString("code") }) ?: continue
            val name = o.optString("name").trim().replace(" ", "")
            if (name.isEmpty()) continue
            val price = num(o, "trade")
            val pct = num(o, "changepercent")
            val amount = num(o, "amount")
            // 新浪 mktcap / nmc 单位为万元
            val totalCap = num(o, "mktcap") * 10_000.0
            val circCap = num(o, "nmc") * 10_000.0
            out += HeatmapItem(
                id = code,
                name = name,
                changePct = pct,
                weight = chooseWeight(weightMode, circCap, totalCap, amount),
                price = price,
                decimals = 2,
                amountYuan = amount,
                circCapYuan = circCap,
                isSector = false,
            )
        }
        return out
    }
}
