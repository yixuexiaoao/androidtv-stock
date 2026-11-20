package io.github.yixuexiaoao.tvstock.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 简单浮点矩形，不依赖 android.graphics.RectF，便于纯 JVM 单元测试。 */
data class Box(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = max(0f, right - left)
    val height: Float get() = max(0f, bottom - top)
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
}

/** 布局完成后的单个热力图叶子色块。 */
data class HeatTile(
    val item: HeatmapItem,
    val box: Box,
)

/** 布局完成后的外层分组框（含标题栏区域）。 */
data class HeatGroupBox(
    val group: HeatmapGroup,
    val box: Box,
    val headerBottom: Float,
)

/** 整张热力图的布局产物。 */
data class TreemapLayout(
    val groups: List<HeatGroupBox>,
    val tiles: List<HeatTile>,
)

/** 方向键几何导航方向，与 Android View 常量脱耦。 */
enum class NavDir { UP, DOWN, LEFT, RIGHT }

/**
 * Squarified Treemap（矩形树图）纯函数布局与二维方向键焦点导航。
 * 参考 Bruls, Huizing, van Wijk (2000) 算法，尽量让所有矩形的长宽比接近 1。
 */
object Treemap {
    /** 单层平铺布局（适用于 31 行业宏观图、自选股热力图）。 */
    fun layoutFlat(items: List<HeatmapItem>, bounds: Box, gap: Float = 2f): TreemapLayout {
        if (items.isEmpty() || bounds.width <= 1f || bounds.height <= 1f) {
            return TreemapLayout(emptyList(), emptyList())
        }
        val sorted = items.sortedByDescending { safeWeight(it.weight) }
        val boxes = squarify(sorted.map { safeWeight(it.weight) }, bounds)
        val tiles = sorted.mapIndexed { idx, item ->
            HeatTile(item, inset(boxes[idx], gap * 0.5f))
        }
        return TreemapLayout(emptyList(), tiles)
    }

    /**
     * 两层嵌套布局（适用于板块下钻按二级行业分组、沪深300/全A龙头按行业分组）。
     * 先对外层分组做 Squarified 切分，每个分组留出顶部标题栏后再对组内个股做 Squarified 切分。
     */
    fun layoutGrouped(
        groups: List<HeatmapGroup>,
        bounds: Box,
        groupGap: Float = 3f,
        tileGap: Float = 1.5f,
        headerHeight: Float = 24f,
    ): TreemapLayout {
        val nonEmpty = groups.filter { it.items.isNotEmpty() }
            .sortedByDescending { safeWeight(it.weight) }
        if (nonEmpty.isEmpty() || bounds.width <= 1f || bounds.height <= 1f) {
            return TreemapLayout(emptyList(), emptyList())
        }
        val outerBoxes = squarify(nonEmpty.map { safeWeight(it.weight) }, bounds)
        val groupBoxes = ArrayList<HeatGroupBox>(nonEmpty.size)
        val allTiles = ArrayList<HeatTile>()

        nonEmpty.forEachIndexed { idx, group ->
            val outer = inset(outerBoxes[idx], groupGap * 0.5f)
            if (outer.width < 2f || outer.height < 2f) return@forEachIndexed
            // 空间极小的分组自动压缩或隐藏标题栏，把面积留给色块
            val actualHeader = when {
                outer.height < headerHeight * 1.8f || outer.width < 40f -> 0f
                else -> min(headerHeight, outer.height * 0.28f)
            }
            val headerBottom = outer.top + actualHeader
            groupBoxes += HeatGroupBox(group, outer, headerBottom)

            val pad = min(tileGap, min(outer.width, outer.height) * 0.05f)
            val inner = Box(
                outer.left + pad,
                headerBottom + if (actualHeader > 0f) pad * 0.5f else pad,
                outer.right - pad,
                outer.bottom - pad,
            )
            val sortedItems = group.items.sortedByDescending { safeWeight(it.weight) }
            val innerBoxes = squarify(sortedItems.map { safeWeight(it.weight) }, inner)
            sortedItems.forEachIndexed { i, item ->
                allTiles += HeatTile(item, inset(innerBoxes[i], tileGap * 0.5f))
            }
        }
        return TreemapLayout(groupBoxes, allTiles)
    }

    /**
     * 把按降序排列的正数权重列表切分填满 bounds。
     * 返回与 weights 等长的 Box 列表。
     */
    fun squarify(weights: List<Double>, bounds: Box): List<Box> {
        val n = weights.size
        if (n == 0) return emptyList()
        if (n == 1) return listOf(bounds)

        val total = weights.sum()
        val totalArea = bounds.width.toDouble() * bounds.height.toDouble()
        if (total <= 0.0 || totalArea <= 0.0) {
            return List(n) { bounds }
        }
        val scale = totalArea / total
        val areas = DoubleArray(n) { weights[it] * scale }
        val out = arrayOfNulls<Box>(n)

        var left = bounds.left.toDouble()
        var top = bounds.top.toDouble()
        var right = bounds.right.toDouble()
        var bottom = bounds.bottom.toDouble()

        var start = 0
        while (start < n) {
            val w = right - left
            val h = bottom - top
            if (w <= 1e-6 || h <= 1e-6) {
                for (i in start until n) out[i] = Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
                break
            }
            val shortSide = min(w, h)
            var end = start + 1
            var rowSum = areas[start]
            var prevWorst = worstRatio(areas, start, end, rowSum, shortSide)

            while (end < n) {
                val nextSum = rowSum + areas[end]
                val nextWorst = worstRatio(areas, start, end + 1, nextSum, shortSide)
                if (nextWorst <= prevWorst) {
                    rowSum = nextSum
                    prevWorst = nextWorst
                    end++
                } else {
                    break
                }
            }

            // 沿较短边铺开这一行/列
            val horizontalStrip = w >= h
            if (horizontalStrip) {
                // 在左侧切出一竖条，宽度 = rowSum / h
                val stripW = (rowSum / h).coerceAtMost(w)
                var y = top
                for (i in start until end) {
                    val itemH = if (i == end - 1 || stripW <= 1e-9) bottom - y else (areas[i] / stripW).coerceAtLeast(0.0)
                    val nextY = (y + itemH).coerceAtMost(bottom)
                    out[i] = Box(left.toFloat(), y.toFloat(), (left + stripW).toFloat(), nextY.toFloat())
                    y = nextY
                }
                left += stripW
            } else {
                // 在顶部切出一横条，高度 = rowSum / w
                val stripH = (rowSum / w).coerceAtMost(h)
                var x = left
                for (i in start until end) {
                    val itemW = if (i == end - 1 || stripH <= 1e-9) right - x else (areas[i] / stripH).coerceAtLeast(0.0)
                    val nextX = (x + itemW).coerceAtMost(right)
                    out[i] = Box(x.toFloat(), top.toFloat(), nextX.toFloat(), (top + stripH).toFloat())
                    x = nextX
                }
                top += stripH
            }
            start = end
        }
        return out.map { it ?: bounds }
    }

    private fun worstRatio(areas: DoubleArray, start: Int, end: Int, sum: Double, side: Double): Double {
        if (sum <= 0.0 || side <= 0.0) return Double.MAX_VALUE
        var minA = Double.MAX_VALUE
        var maxA = 0.0
        for (i in start until end) {
            val a = areas[i]
            if (a < minA) minA = a
            if (a > maxA) maxA = a
        }
        if (minA <= 0.0) return Double.MAX_VALUE
        val s2 = side * side
        val sum2 = sum * sum
        return max(s2 * maxA / sum2, sum2 / (s2 * minA))
    }

    private fun safeWeight(w: Double): Double = if (w.isFinite() && w > 0.0) w else 1.0

    private fun inset(b: Box, delta: Float): Box {
        val dx = min(delta, b.width * 0.25f)
        val dy = min(delta, b.height * 0.25f)
        return Box(b.left + dx, b.top + dy, b.right - dx, b.bottom - dy)
    }

    /**
     * 在 Treemap 色块中进行电视遥控器二维几何导航。
     * 返回目标 tile 下标；若该方向已无更靠前的候选（例如已在顶边按上键），返回 -1 交由父容器处理。
     */
    fun navigate(tiles: List<HeatTile>, currentIndex: Int, dir: NavDir): Int {
        if (tiles.isEmpty()) return -1
        val cur = tiles.getOrNull(currentIndex)?.box ?: return 0
        var bestIdx = -1
        var bestScore = Float.MAX_VALUE

        for (i in tiles.indices) {
            if (i == currentIndex) continue
            val cand = tiles[i].box
            // 忽略面积极小不可见的碎片块，避免焦点落进 2px 的缝隙里找不到
            if (cand.width < 4f || cand.height < 4f) continue

            val primary: Float
            val overlap: Float
            val perpDist: Float

            when (dir) {
                NavDir.RIGHT -> {
                    if (cand.centerX <= cur.centerX + 1f) continue
                    primary = max(0f, cand.left - cur.right) + (cand.centerX - cur.centerX) * 0.25f
                    overlap = min(cur.bottom, cand.bottom) - max(cur.top, cand.top)
                    perpDist = abs(cand.centerY - cur.centerY)
                }
                NavDir.LEFT -> {
                    if (cand.centerX >= cur.centerX - 1f) continue
                    primary = max(0f, cur.left - cand.right) + (cur.centerX - cand.centerX) * 0.25f
                    overlap = min(cur.bottom, cand.bottom) - max(cur.top, cand.top)
                    perpDist = abs(cand.centerY - cur.centerY)
                }
                NavDir.DOWN -> {
                    if (cand.centerY <= cur.centerY + 1f) continue
                    primary = max(0f, cand.top - cur.bottom) + (cand.centerY - cur.centerY) * 0.25f
                    overlap = min(cur.right, cand.right) - max(cur.left, cand.left)
                    perpDist = abs(cand.centerX - cur.centerX)
                }
                NavDir.UP -> {
                    if (cand.centerY >= cur.centerY - 1f) continue
                    primary = max(0f, cur.top - cand.bottom) + (cur.centerY - cand.centerY) * 0.25f
                    overlap = min(cur.right, cand.right) - max(cur.left, cand.left)
                    perpDist = abs(cand.centerX - cur.centerX)
                }
            }

            // 正投影有重叠的邻接色块优先，避免斜向跳太远
            val penalty = if (overlap > 1f) perpDist * 0.6f else perpDist * 3.0f + 80f
            val score = primary + penalty
            if (score < bestScore) {
                bestScore = score
                bestIdx = i
            }
        }
        return bestIdx
    }
}
