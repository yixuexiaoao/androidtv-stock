package io.github.yixuexiaoao.tvstock.ui

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import io.github.yixuexiaoao.tvstock.model.KLine
import io.github.yixuexiaoao.tvstock.model.MinutePoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 一条水平标记线（目标价、成本价等）。 */
data class PriceMark(val price: Double, val color: Int, val label: String)

/** 分时图和 K 线图的 Canvas 绘制，无第三方依赖，低配盒子也能流畅运行。 */
class ChartRenderer {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val path = Path()

    /** A 股全天 241 个分钟点的横坐标序号；午休时段映射到 11:30/13:00 的同一位置。 */
    private fun minuteIndex(hhmm: String): Int {
        val h = hhmm.substring(0, 2).toIntOrNull() ?: return 0
        val m = hhmm.substring(2, 4).toIntOrNull() ?: return 0
        val t = h * 60 + m
        return when {
            t <= 11 * 60 + 30 -> t - (9 * 60 + 30)
            t < 13 * 60 -> 120
            else -> 120 + t - 13 * 60
        }.coerceIn(0, 240)
    }

    fun drawMinute(
        c: Canvas,
        area: RectF,
        points: List<MinutePoint>,
        prevClose: Double,
        decimals: Int,
        palette: Palette,
        fontSize: Float,
        marks: List<PriceMark>,
    ) {
        text.textSize = fontSize
        val axisH = fontSize * 1.3f
        val volH = if (area.height() > fontSize * 12) area.height() * 0.18f else 0f
        val labelW = text.measureText("+10.00%") + fontSize * 0.5f
        val showLabels = area.width() > labelW * 4
        val plot = RectF(area.left, area.top, if (showLabels) area.right - labelW else area.right, area.bottom - axisH - volH)
        if (plot.height() <= 4 || plot.width() <= 4) return

        val prices = points.map { it.price }.filter { it > 0 }
        // 以昨收为中轴对称，至少显示 ±1%
        var maxDiff = max(prevClose * 0.01, prices.maxOfOrNull { abs(it - prevClose) } ?: 0.0)
        marks.forEach { mark ->
            val d = abs(mark.price - prevClose)
            if (d > maxDiff && d < maxDiff * 1.5) maxDiff = d
        }
        if (maxDiff <= 0) maxDiff = 1.0
        maxDiff *= 1.05
        val hi = prevClose + maxDiff
        val lo = prevClose - maxDiff
        fun y(p: Double) = plot.bottom - ((p - lo) / (hi - lo)).toFloat() * plot.height()
        val stepX = plot.width() / 240f
        fun x(i: Int) = plot.left + i * stepX

        line.color = palette.grid
        line.strokeWidth = 1f
        for (i in 1..3) {
            val gy = plot.top + plot.height() * i / 4
            c.drawLine(plot.left, gy, plot.right, gy, line)
        }
        for (i in 1..3) {
            val gx = plot.left + plot.width() * i / 4
            c.drawLine(gx, plot.top, gx, plot.bottom, line)
        }
        c.drawRect(plot, line)

        dashed.color = palette.flat
        dashed.strokeWidth = 1.5f
        path.reset()
        path.moveTo(plot.left, y(prevClose))
        path.lineTo(plot.right, y(prevClose))
        c.drawPath(path, dashed)

        drawMarks(c, plot, marks, fontSize) { p -> if (p in lo..hi) y(p) else null }

        if (showLabels) {
            text.textAlign = Paint.Align.LEFT
            val tx = plot.right + fontSize * 0.25f
            val pct = maxDiff / prevClose * 100
            text.color = palette.up
            c.drawText(Format.price(hi, decimals), tx, plot.top + fontSize, text)
            text.color = palette.down
            c.drawText(Format.price(lo, decimals), tx, plot.bottom - fontSize * 0.2f, text)
            text.color = palette.flat
            c.drawText(Format.price(prevClose, decimals), tx, y(prevClose) + fontSize * 0.35f, text)
            if (plot.height() > fontSize * 6) {
                text.color = palette.up
                c.drawText("+%.2f%%".format(pct), tx, plot.top + fontSize * 2.2f, text)
                text.color = palette.down
                c.drawText("-%.2f%%".format(pct), tx, plot.bottom - fontSize * 1.4f, text)
            }
        }

        text.color = palette.subText
        val axisY = area.bottom - fontSize * 0.2f
        text.textAlign = Paint.Align.LEFT
        c.drawText("09:30", plot.left, axisY, text)
        text.textAlign = Paint.Align.CENTER
        c.drawText("11:30/13:00", plot.centerX(), axisY, text)
        text.textAlign = Paint.Align.RIGHT
        c.drawText("15:00", plot.right, axisY, text)
        text.textAlign = Paint.Align.LEFT

        if (points.isEmpty()) return

        val indexed = points.filter { it.price > 0 }.map { minuteIndex(it.hhmm) to it }
        if (indexed.isEmpty()) return
        val last = indexed.last().second.price
        val trendColor = palette.forChange(last - prevClose)

        path.reset()
        indexed.forEachIndexed { i, (index, p) ->
            if (i == 0) path.moveTo(x(index), y(p.price)) else path.lineTo(x(index), y(p.price))
        }
        val area2 = Path(path)
        area2.lineTo(x(indexed.last().first), plot.bottom)
        area2.lineTo(x(indexed.first().first), plot.bottom)
        area2.close()
        fill.color = trendColor
        fill.alpha = 36
        c.drawPath(area2, fill)
        fill.alpha = 255

        line.color = trendColor
        line.strokeWidth = max(2f, fontSize / 9f)
        c.drawPath(path, line)

        // 均价线：指数的累计额口径不同，均价明显偏离时不绘制
        val avg = indexed.map { (i, p) -> i to p.avgPrice }
            .filter { (_, a) -> a > 0 && abs(a - last) / last < 0.25 }
        if (avg.size > 1) {
            path.reset()
            avg.forEachIndexed { i, (index, a) ->
                val ay = y(a.coerceIn(lo, hi))
                if (i == 0) path.moveTo(x(index), ay) else path.lineTo(x(index), ay)
            }
            line.color = palette.avgLine
            line.strokeWidth = max(1.5f, fontSize / 14f)
            c.drawPath(path, line)
        }

        if (volH > 0) {
            val volTop = plot.bottom + fontSize * 0.2f
            val volBottom = area.bottom - axisH
            val deltas = indexed.mapIndexed { i, (_, p) ->
                val previous = if (i == 0) 0.0 else indexed[i - 1].second.cumVolume
                max(0.0, p.cumVolume - previous)
            }
            val maxVol = deltas.maxOrNull()?.takeIf { it > 0 } ?: return
            fill.strokeWidth = 0f
            indexed.forEachIndexed { i, (index, p) ->
                val prev = if (i == 0) prevClose else indexed[i - 1].second.price
                fill.color = palette.forChange(p.price - prev)
                val h = (deltas[i] / maxVol).toFloat() * (volBottom - volTop)
                val bx = x(index)
                c.drawRect(bx, volBottom - h, bx + max(1f, stepX * 0.7f), volBottom, fill)
            }
        }
    }

    fun drawKline(
        c: Canvas,
        area: RectF,
        lines: List<KLine>,
        decimals: Int,
        palette: Palette,
        fontSize: Float,
        marks: List<PriceMark>,
    ) {
        text.textSize = fontSize
        text.textAlign = Paint.Align.LEFT
        if (lines.isEmpty()) return
        val axisH = fontSize * 1.3f
        val volH = if (area.height() > fontSize * 12) area.height() * 0.18f else 0f
        val labelW = text.measureText("00000.00") + fontSize * 0.4f
        val showLabels = area.width() > labelW * 4
        val plot = RectF(area.left, area.top, if (showLabels) area.right - labelW else area.right, area.bottom - axisH - volH)
        if (plot.height() <= 4 || plot.width() <= 4) return

        // 窗口太窄时只画最近能放得下的 K 线
        val minCandle = max(3f, fontSize / 5f)
        val capacity = max(5, (plot.width() / minCandle).toInt())
        val visible = lines.takeLast(capacity)
        val hiRaw = visible.maxOf { it.high }
        val loRaw = visible.minOf { it.low }
        val pad = max((hiRaw - loRaw) * 0.06, hiRaw * 0.002)
        val hi = hiRaw + pad
        val lo = loRaw - pad
        fun y(p: Double) = plot.bottom - ((p - lo) / (hi - lo)).toFloat() * plot.height()
        val step = plot.width() / visible.size
        val bodyW = max(1f, step * 0.72f)

        line.color = palette.grid
        line.strokeWidth = 1f
        for (i in 1..3) {
            val gy = plot.top + plot.height() * i / 4
            c.drawLine(plot.left, gy, plot.right, gy, line)
        }
        c.drawRect(plot, line)

        drawMarks(c, plot, marks, fontSize) { p -> if (p in lo..hi) y(p) else null }

        visible.forEachIndexed { i, k ->
            val cx = plot.left + step * (i + 0.5f)
            val color = palette.forChange(k.close - k.open)
            fill.color = color
            line.color = color
            line.strokeWidth = max(1f, step * 0.12f)
            c.drawLine(cx, y(k.high), cx, y(k.low), line)
            val top = y(max(k.open, k.close))
            val bottom = max(y(min(k.open, k.close)), top + 1.5f)
            c.drawRect(cx - bodyW / 2, top, cx + bodyW / 2, bottom, fill)
        }

        val maColors = intArrayOf(palette.avgLine, palette.accent, palette.text)
        listOf(5, 10, 20).forEachIndexed { index, period ->
            if (lines.size < period) return@forEachIndexed
            path.reset()
            var started = false
            val offset = lines.size - visible.size
            for (i in visible.indices) {
                val full = i + offset
                if (full + 1 < period) continue
                var sum = 0.0
                for (j in full - period + 1..full) sum += lines[j].close
                val py = y((sum / period).coerceIn(lo, hi))
                val px = plot.left + step * (i + 0.5f)
                if (started) path.lineTo(px, py) else path.moveTo(px, py)
                started = true
            }
            line.color = maColors[index]
            line.strokeWidth = max(1f, fontSize / 16f)
            c.drawPath(path, line)
        }

        if (showLabels) {
            val tx = plot.right + fontSize * 0.2f
            text.color = palette.subText
            c.drawText(Format.price(hi, decimals), tx, plot.top + fontSize, text)
            c.drawText(Format.price(lo, decimals), tx, plot.bottom - fontSize * 0.2f, text)
        }

        text.color = palette.subText
        val axisY = area.bottom - fontSize * 0.2f
        text.textAlign = Paint.Align.LEFT
        c.drawText(visible.first().date, plot.left, axisY, text)
        text.textAlign = Paint.Align.RIGHT
        c.drawText(visible.last().date, plot.right, axisY, text)
        text.textAlign = Paint.Align.LEFT

        if (volH > 0) {
            val volTop = plot.bottom + fontSize * 0.2f
            val volBottom = area.bottom - axisH
            val maxVol = visible.maxOf { it.volume }.takeIf { it > 0 } ?: return
            visible.forEachIndexed { i, k ->
                fill.color = palette.forChange(k.close - k.open)
                val h = (k.volume / maxVol).toFloat() * (volBottom - volTop)
                val cx = plot.left + step * (i + 0.5f)
                c.drawRect(cx - bodyW / 2, volBottom - h, cx + bodyW / 2, volBottom, fill)
            }
        }
    }

    private fun drawMarks(c: Canvas, plot: RectF, marks: List<PriceMark>, fontSize: Float, yOf: (Double) -> Float?) {
        text.textAlign = Paint.Align.LEFT
        marks.forEach { mark ->
            val y = yOf(mark.price) ?: return@forEach
            dashed.color = mark.color
            dashed.strokeWidth = 2f
            path.reset()
            path.moveTo(plot.left, y)
            path.lineTo(plot.right, y)
            c.drawPath(path, dashed)
            text.color = mark.color
            c.drawText(mark.label, plot.left + fontSize * 0.3f, y - fontSize * 0.2f, text)
        }
    }
}
