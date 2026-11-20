package io.github.yixuexiaoao.tvstock

import io.github.yixuexiaoao.tvstock.model.Box
import io.github.yixuexiaoao.tvstock.model.HeatmapGroup
import io.github.yixuexiaoao.tvstock.model.HeatmapItem
import io.github.yixuexiaoao.tvstock.model.NavDir
import io.github.yixuexiaoao.tvstock.model.Treemap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TreemapTest {
    private fun item(id: String, weight: Double, pct: Double = 0.0, group: String = "") =
        HeatmapItem(id = id, name = id, changePct = pct, weight = weight, groupName = group)

    @Test
    fun squarifyFillsAreaWithoutOverlap() {
        val bounds = Box(0f, 0f, 1000f, 600f)
        val weights = listOf(500.0, 300.0, 200.0, 100.0, 80.0, 50.0, 30.0, 10.0)
        val boxes = Treemap.squarify(weights, bounds)
        assertEquals(weights.size, boxes.size)

        var areaSum = 0.0
        for (i in boxes.indices) {
            val b = boxes[i]
            assertTrue("box $i width > 0", b.width > 0f)
            assertTrue("box $i height > 0", b.height > 0f)
            assertTrue(b.left >= bounds.left - 0.1f && b.right <= bounds.right + 0.1f)
            assertTrue(b.top >= bounds.top - 0.1f && b.bottom <= bounds.bottom + 0.1f)
            areaSum += b.width * b.height
            for (j in i + 1 until boxes.size) {
                val o = boxes[j]
                val overlapX = minOf(b.right, o.right) - maxOf(b.left, o.left)
                val overlapY = minOf(b.bottom, o.bottom) - maxOf(b.top, o.top)
                assertTrue("box $i and $j should not overlap", overlapX <= 0.5f || overlapY <= 0.5f)
            }
        }
        assertEquals(600_000.0, areaSum, 500.0)
    }

    @Test
    fun flatLayoutHandlesZeroAndNegativeWeightsGracefully() {
        val bounds = Box(0f, 0f, 800f, 400f)
        val items = listOf(item("A", 0.0), item("B", -5.0), item("C", Double.NaN), item("D", 10.0))
        val layout = Treemap.layoutFlat(items, bounds)
        assertEquals(4, layout.tiles.size)
        assertEquals("D", layout.tiles.first().item.id)
        layout.tiles.forEach { t ->
            assertTrue(t.box.width > 0f && t.box.height > 0f)
        }
    }

    @Test
    fun groupedLayoutPlacesTilesInsideTheirGroupBox() {
        val g1 = HeatmapGroup("半导体", listOf(item("s1", 60.0), item("s2", 40.0)), 2.5, 100.0)
        val g2 = HeatmapGroup("消费电子", listOf(item("c1", 50.0), item("c2", 30.0)), -1.2, 80.0)
        val layout = Treemap.layoutGrouped(listOf(g1, g2), Box(0f, 0f, 1200f, 700f), headerHeight = 24f)
        assertEquals(2, layout.groups.size)
        assertEquals(4, layout.tiles.size)
        val byId = layout.tiles.associateBy { it.item.id }
        val g1Box = layout.groups.first { it.group.name == "半导体" }
        for (id in listOf("s1", "s2")) {
            val tb = byId[id]!!.box
            assertTrue(tb.left >= g1Box.box.left - 0.1f && tb.right <= g1Box.box.right + 0.1f)
            assertTrue(tb.top >= g1Box.headerBottom - 0.1f && tb.bottom <= g1Box.box.bottom + 0.1f)
        }
    }

    @Test
    fun navigateMovesAcrossNeighborsAndDetectsTopEdge() {
        // 2x2 均分布局
        val items = listOf(item("A", 10.0), item("B", 10.0), item("C", 10.0), item("D", 10.0))
        val layout = Treemap.layoutFlat(items, Box(0f, 0f, 400f, 400f), gap = 0f)
        val topLeft = layout.tiles.indexOfFirst { it.box.centerX < 200f && it.box.centerY < 200f }
        assertTrue(topLeft >= 0)
        // 顶排按上键应返回 -1（交还焦点给顶部导航栏）
        assertEquals(-1, Treemap.navigate(layout.tiles, topLeft, NavDir.UP))
        // 向右、向下都能走到有效邻居
        val right = Treemap.navigate(layout.tiles, topLeft, NavDir.RIGHT)
        val down = Treemap.navigate(layout.tiles, topLeft, NavDir.DOWN)
        assertTrue(right >= 0 && layout.tiles[right].box.centerX > layout.tiles[topLeft].box.centerX)
        assertTrue(down >= 0 && layout.tiles[down].box.centerY > layout.tiles[topLeft].box.centerY)
        assertEquals(topLeft, Treemap.navigate(layout.tiles, right, NavDir.LEFT))
    }
}
