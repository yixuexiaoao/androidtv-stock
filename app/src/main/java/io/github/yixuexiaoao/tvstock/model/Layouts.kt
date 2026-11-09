package io.github.yixuexiaoao.tvstock.model

import org.json.JSONArray
import org.json.JSONObject

data class GridCell(val x: Int, val y: Int, val w: Int, val h: Int) {
    fun overlaps(o: GridCell) = x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h
}

/** 在 cols × rows 的网格上放若干矩形窗口，同视频监控软件的“画面分割”。 */
data class GridLayoutDef(
    val id: String,
    val name: String,
    val cols: Int,
    val rows: Int,
    val cells: List<GridCell>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("cols", cols)
        put("rows", rows)
        put("cells", JSONArray().also { arr ->
            cells.forEach { c ->
                arr.put(JSONObject().put("x", c.x).put("y", c.y).put("w", c.w).put("h", c.h))
            }
        })
    }

    companion object {
        const val MAX_DIM = 12

        /** 校验失败（越界、重叠、为空）返回 null。 */
        fun fromJson(json: JSONObject): GridLayoutDef? {
            val cols = json.optInt("cols", 0)
            val rows = json.optInt("rows", 0)
            if (cols !in 1..MAX_DIM || rows !in 1..MAX_DIM) return null
            val arr = json.optJSONArray("cells") ?: return null
            val cells = mutableListOf<GridCell>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: return null
                val c = GridCell(o.optInt("x", -1), o.optInt("y", -1), o.optInt("w", 0), o.optInt("h", 0))
                if (c.x < 0 || c.y < 0 || c.w < 1 || c.h < 1 || c.x + c.w > cols || c.y + c.h > rows) return null
                if (cells.any { it.overlaps(c) }) return null
                cells += c
            }
            if (cells.isEmpty()) return null
            return GridLayoutDef(Layouts.CUSTOM_ID, "自定义", cols, rows, cells)
        }
    }
}

object Layouts {
    const val CUSTOM_ID = "custom"

    private fun grid(id: String, name: String, cols: Int, rows: Int) = GridLayoutDef(
        id, name, cols, rows,
        (0 until rows).flatMap { y -> (0 until cols).map { x -> GridCell(x, y, 1, 1) } },
    )

    /** 其余格子按从上到下、从左到右填满 1×1 窗口。 */
    private fun irregular(id: String, name: String, cols: Int, rows: Int, vararg big: GridCell): GridLayoutDef {
        val rest = (0 until rows).flatMap { y -> (0 until cols).map { x -> GridCell(x, y, 1, 1) } }
            .filter { c -> big.none { it.overlaps(c) } }
        return GridLayoutDef(id, name, cols, rows, big.toList() + rest)
    }

    val ALL: List<GridLayoutDef> = listOf(
        grid("1", "1 画面", 1, 1),
        grid("2", "2 画面", 2, 1),
        grid("3", "3 画面", 3, 1),
        grid("4", "4 画面", 2, 2),
        grid("6", "6 画面", 3, 2),
        grid("8", "8 画面", 4, 2),
        grid("9", "9 画面", 3, 3),
        grid("12", "12 画面", 4, 3),
        grid("16", "16 画面", 4, 4),
        irregular("1+2", "1 大 2 小", 3, 2, GridCell(0, 0, 2, 2)),
        irregular("1+3", "1 大 3 小", 4, 3, GridCell(0, 0, 3, 3)),
        irregular("1+5", "1 大 5 小", 3, 3, GridCell(0, 0, 2, 2)),
        irregular("1+7", "1 大 7 小", 4, 4, GridCell(0, 0, 3, 3)),
        irregular("2+4", "2 大 4 小", 4, 3, GridCell(0, 0, 2, 2), GridCell(2, 0, 2, 2)),
        irregular("2+8", "2 大 8 小", 4, 4, GridCell(0, 0, 2, 2), GridCell(2, 0, 2, 2)),
        irregular("1+12", "中心 1 大 12 小", 4, 4, GridCell(1, 1, 2, 2)),
    )

    fun byId(id: String): GridLayoutDef? = ALL.firstOrNull { it.id == id }

    fun resolve(id: String, custom: GridLayoutDef?): GridLayoutDef =
        (if (id == CUSTOM_ID) custom else byId(id)) ?: byId("4")!!

    /** 遥控器数字键直接切换到对应画面数（没有的返回 null）。 */
    fun forDigit(digit: Int): String? = byId(digit.toString())?.id

    fun pageCount(stockCount: Int, cellCount: Int): Int =
        if (stockCount == 0 || cellCount == 0) 1 else (stockCount + cellCount - 1) / cellCount

    /** 取第 page 页的股票；页码超出时自动回绕。 */
    fun <T> pageItems(items: List<T>, cellCount: Int, page: Int): List<T> {
        if (items.isEmpty() || cellCount <= 0) return emptyList()
        val pages = pageCount(items.size, cellCount)
        val p = Math.floorMod(page, pages)
        return items.drop(p * cellCount).take(cellCount)
    }
}
