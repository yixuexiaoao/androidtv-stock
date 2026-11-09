package io.github.yixuexiaoao.tvstock.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import io.github.yixuexiaoao.tvstock.model.AppSettings
import io.github.yixuexiaoao.tvstock.model.DisplayModes
import io.github.yixuexiaoao.tvstock.model.Holding
import io.github.yixuexiaoao.tvstock.model.KLine
import io.github.yixuexiaoao.tvstock.model.MinutePoint
import io.github.yixuexiaoao.tvstock.model.Quote
import io.github.yixuexiaoao.tvstock.model.StockCodes
import io.github.yixuexiaoao.tvstock.model.StockConfig
import kotlin.math.max
import kotlin.math.min

/** 单个行情窗口。整个窗口用 Canvas 绘制，并根据窗口大小自动决定显示多少内容。 */
class StockCellView(context: Context) : View(context) {
    var stock: StockConfig? = null
    var quote: Quote? = null
        private set
    var minutePoints: List<MinutePoint> = emptyList()
    var klineList: List<KLine> = emptyList()
    var settings = AppSettings()
    var palette = Palette.of(settings)
    /** 放大查看模式：显示五档盘口和完整数据 */
    var detail = false
    /** 放大模式下用遥控器临时切换的展示方式，空字符串表示跟随配置 */
    var modeOverride = ""
    var flashing = false
    var flashOn = false
    var flashColor = Color.RED

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chart = ChartRenderer()
    private val chartRect = RectF()
    private val bounds = Rect()

    private var tickColor = 0
    private var tickUntil = 0L

    init {
        isFocusable = true
        isClickable = true
        isLongClickable = true
    }

    fun displayMode(): String = modeOverride.ifBlank {
        stock?.displayMode?.takeIf { it.isNotBlank() } ?: settings.displayMode
    }

    fun updateQuote(next: Quote?) {
        val old = quote
        if (settings.priceTickFlash && old != null && next != null && old.code == next.code &&
            next.hasTrade && old.price != next.price
        ) {
            tickColor = palette.forChange(next.price - old.price)
            tickUntil = System.currentTimeMillis() + TICK_MS
            postInvalidateDelayed(TICK_MS + 20)
        }
        quote = next
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    private fun setFont(size: Float, color: Int, bold: Boolean = false) {
        paint.textSize = size
        paint.color = color
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.textAlign = Paint.Align.LEFT
    }

    private fun fit(text: String, maxWidth: Float): String =
        if (paint.measureText(text) <= maxWidth) text
        else TextUtils.ellipsize(text, paint, max(0f, maxWidth), TextUtils.TruncateAt.END).toString()

    private fun drawLeft(c: Canvas, text: String, x: Float, y: Float, maxWidth: Float) {
        paint.textAlign = Paint.Align.LEFT
        c.drawText(fit(text, maxWidth), x, y, paint)
    }

    private fun drawRight(c: Canvas, text: String, x: Float, y: Float) {
        paint.textAlign = Paint.Align.RIGHT
        c.drawText(text, x, y, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    /** 逐步缩小字号直到放得下。 */
    private fun shrinkToFit(text: String, maxWidth: Float, startSize: Float) {
        paint.textSize = startSize
        val w = paint.measureText(text)
        if (w > maxWidth && w > 0) paint.textSize = startSize * maxWidth / w
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        rectPaint.style = Paint.Style.FILL
        rectPaint.color = palette.cell
        canvas.drawRect(0f, 0f, w, h, rectPaint)

        if (System.currentTimeMillis() < tickUntil) {
            rectPaint.color = tickColor
            rectPaint.alpha = 40
            canvas.drawRect(0f, 0f, w, h, rectPaint)
        }
        if (flashing && flashOn) {
            rectPaint.color = flashColor
            rectPaint.alpha = 120
            canvas.drawRect(0f, 0f, w, h, rectPaint)
        }

        val s = stock
        if (s == null) {
            drawEmpty(canvas, w, h)
        } else {
            val mode = displayMode()
            val q = quote
            when {
                q == null -> drawLoading(canvas, s, w, h)
                mode == DisplayModes.TEXT -> drawText(canvas, s, q, w, h)
                detail -> drawDetail(canvas, s, q, w, h, mode)
                else -> drawChartCell(canvas, s, q, w, h, mode)
            }
        }
        drawBorder(canvas, w, h)
    }

    private fun drawBorder(c: Canvas, w: Float, h: Float) {
        rectPaint.style = Paint.Style.STROKE
        when {
            flashing && flashOn -> {
                rectPaint.color = flashColor
                rectPaint.strokeWidth = max(6f, min(w, h) / 40f)
            }
            isFocused -> {
                rectPaint.color = palette.accent
                rectPaint.strokeWidth = max(4f, min(w, h) / 70f)
            }
            else -> {
                rectPaint.color = palette.border
                rectPaint.strokeWidth = 1f
            }
        }
        val inset = rectPaint.strokeWidth / 2
        c.drawRect(inset, inset, w - inset, h - inset, rectPaint)
        rectPaint.style = Paint.Style.FILL
    }

    private fun drawEmpty(c: Canvas, w: Float, h: Float) {
        setFont(min(w, h) / 12f, palette.subText)
        paint.textAlign = Paint.Align.CENTER
        c.drawText("未配置股票", w / 2, h / 2, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawLoading(c: Canvas, s: StockConfig, w: Float, h: Float) {
        setFont(min(w, h) / 12f, palette.subText)
        paint.textAlign = Paint.Align.CENTER
        c.drawText(s.alias.ifBlank { StockCodes.shortCode(s.code) } + " 加载中…", w / 2, h / 2, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private val scale get() = settings.fontScale / 100f

    private fun displayName(s: StockConfig, q: Quote, withTicker: Boolean = false): String {
        val badge = when {
            !q.hasTrade -> ""
            q.limitUp != null && q.limitUp > 0 && q.price >= q.limitUp -> " [涨停]"
            q.limitDown != null && q.limitDown > 0 && q.price <= q.limitDown -> " [跌停]"
            else -> ""
        }
        // 图表窗口没有单独的代码位置；美股的中文名可能陌生，带上更熟悉的代码
        val ticker = if (withTicker && StockCodes.isUs(s.code)) " " + StockCodes.shortCode(s.code) else ""
        return s.alias.ifBlank { q.name } + ticker + badge
    }

    /** 目标价 + 距离现价的百分比。 */
    private fun targetText(label: String, target: Double, q: Quote): String {
        val distance = if (q.displayPrice > 0) (target - q.displayPrice) / q.displayPrice * 100 else 0.0
        return "$label ${Format.price(target, q.decimals)}(${Format.percent(distance)})"
    }

    private fun holdingLines(s: StockConfig, q: Quote): List<Pair<String, Int>> {
        if (!settings.showHoldings) return emptyList()
        val out = mutableListOf<Pair<String, Int>>()
        val holding = Holding.of(s, q)
        val usd = StockCodes.isUs(s.code)
        if (holding != null) {
            if (settings.privacy) {
                out += "持仓 ****   盈亏 ****" to palette.subText
            } else {
                val cost = holding.cost?.let { "  成本 " + Format.price(it, q.decimals) }.orEmpty()
                out += "持仓 ${holding.shares}股$cost" to palette.subText
                if (holding.profit != null) {
                    out += "盈亏 ${Format.signedMoney(holding.profit, usd)} (${Format.percent(holding.profitPct ?: 0.0)})  今日 ${Format.signedMoney(holding.dayProfit, usd)}" to palette.forChange(holding.profit)
                } else {
                    out += "市值 ${Format.money(holding.marketValue, usd)}  今日 ${Format.signedMoney(holding.dayProfit, usd)}" to palette.forChange(holding.dayProfit)
                }
            }
        }
        if (holding == null && s.realizedProfit != 0.0 && !settings.privacy) {
            out += "已实现 ${Format.signedMoney(s.realizedProfit, usd)}" to palette.forChange(s.realizedProfit)
        }
        val targets = buildList {
            s.targetHigh?.let { add(targetText("目标", it, q)) }
            s.targetLow?.let { add(targetText("下破", it, q)) }
        }
        if (targets.isNotEmpty()) out += targets.joinToString("  ") to palette.avgLine
        return out
    }

    private fun statPairs(q: Quote): List<Pair<String, String>> = buildList {
        val d = q.decimals
        add("今开" to Format.priceOrDash(q.open, d))
        add("昨收" to Format.price(q.prevClose, d))
        add("最高" to Format.priceOrDash(q.high, d))
        add("最低" to Format.priceOrDash(q.low, d))
        add("成交量" to Format.volume(q))
        add("成交额" to Format.amount(q))
        q.turnover?.let { add("换手" to "%.2f%%".format(it)) }
        q.amplitude?.let { add("振幅" to "%.2f%%".format(it)) }
        q.volumeRatio?.let { add("量比" to "%.2f".format(it)) }
        q.pe?.takeIf { it != 0.0 }?.let { add("市盈" to "%.2f".format(it)) }
        q.pb?.takeIf { it != 0.0 }?.let { add("市净" to "%.2f".format(it)) }
        q.totalCapYi?.takeIf { it > 0 }?.let { add("总市值" to (if (StockCodes.isUs(q.code)) "$" else "") + "%.0f亿".format(it)) }
    }

    /** 纯文字模式。 */
    private fun drawText(c: Canvas, s: StockConfig, q: Quote, w: Float, h: Float) {
        val base = min(w / 20f, h / 10f) * scale
        val pad = base * 0.6f
        val right = w - pad
        var y = pad + base * 1.1f

        val nameSize = base * 1.25f
        setFont(base * 0.75f, palette.subText)
        val code = StockCodes.shortCode(s.code)
        val codeW = paint.measureText(code)
        drawRight(c, code, right, y)
        setFont(nameSize, palette.text, bold = true)
        drawLeft(c, displayName(s, q), pad, y, right - pad - codeW - base * 0.5f)

        val color = palette.forChange(q.change)
        val priceText = Format.price(q.displayPrice, q.decimals)
        setFont(base * 3.2f, color, bold = true)
        shrinkToFit(priceText, (w - pad * 2) * 0.58f, base * 3.2f)
        val priceSize = paint.textSize
        y += priceSize * 1.05f + base * 0.2f
        c.drawText(priceText, pad, y, paint)
        val priceW = paint.measureText(priceText)

        val changeX = pad + priceW + base * 0.8f
        val changeW = right - changeX
        setFont(base * 1.2f, color, bold = true)
        val changeText = if (q.hasTrade) Format.signed(q.change, q.decimals) else "未成交"
        val pctText = if (q.hasTrade) Format.trendPercent(q.changePct) else "--"
        shrinkToFit(if (changeText.length > pctText.length) changeText else pctText, changeW, base * 1.2f)
        c.drawText(pctText, changeX, y - paint.textSize * 1.25f, paint)
        c.drawText(changeText, changeX, y, paint)

        val holdingRows = holdingLines(s, q)
        val small = base * 0.95f
        val rowH = small * 1.42f
        val bottomLimit = h - pad - holdingRows.size * rowH

        if (settings.showStats) {
            val pairs = statPairs(q)
            val cols = if (w / h > 2.6f) 3 else 2
            val colW = (w - pad * 2) / cols
            y += base * 0.6f
            var i = 0
            while (i < pairs.size && y + rowH <= bottomLimit) {
                y += rowH
                for (col in 0 until cols) {
                    val (label, value) = pairs.getOrNull(i++) ?: break
                    val x = pad + col * colW
                    setFont(small, palette.subText)
                    val labelW = paint.measureText(label) + small * 0.4f
                    c.drawText(fit(label, colW), x, y, paint)
                    setFont(small, palette.text)
                    drawLeft(c, value, x + labelW, y, colW - labelW - small * 0.3f)
                }
            }
        }
        drawHoldingRows(c, holdingRows, pad, right, h - pad, small, rowH)
    }

    private fun drawHoldingRows(c: Canvas, rows: List<Pair<String, Int>>, left: Float, right: Float, bottom: Float, size: Float, rowH: Float) {
        var y = bottom - (rows.size - 1) * rowH
        rows.forEach { (text, color) ->
            setFont(size, color)
            drawLeft(c, text, left, y, right - left)
            y += rowH
        }
    }

    private fun marks(s: StockConfig, q: Quote): List<PriceMark> {
        if (!settings.showTargetLines) return emptyList()
        return buildList {
            s.targetHigh?.let { add(PriceMark(it, palette.avgLine, "目标")) }
            s.targetLow?.let { add(PriceMark(it, palette.avgLine, "下破")) }
            if (!settings.privacy && s.shares != null) {
                s.costPrice?.let { add(PriceMark(it, palette.accent, "成本")) }
            }
        }
    }

    private fun drawChart(c: Canvas, area: RectF, s: StockConfig, q: Quote, mode: String, font: Float) {
        if (mode == DisplayModes.KLINE) {
            if (klineList.isEmpty()) drawChartLoading(c, area, font)
            else chart.drawKline(c, area, klineList, q.decimals, palette, font, marks(s, q))
        } else {
            chart.drawMinute(c, area, minutePoints, q.prevClose, q.decimals, palette, font, marks(s, q), StockCodes.isUs(s.code))
        }
    }

    private fun drawChartLoading(c: Canvas, area: RectF, font: Float) {
        setFont(font, palette.subText)
        paint.textAlign = Paint.Align.CENTER
        c.drawText("图表加载中…", area.centerX(), area.centerY(), paint)
        paint.textAlign = Paint.Align.LEFT
    }

    /** 图表窗口：顶部一行摘要 + 图 +（空间够时）底部数据。 */
    private fun drawChartCell(c: Canvas, s: StockConfig, q: Quote, w: Float, h: Float, mode: String) {
        val base = min(w / 24f, h / 13f) * scale
        val pad = base * 0.5f
        val right = w - pad
        val color = palette.forChange(q.change)

        val headerSize = base * 1.25f
        val top = pad + headerSize
        val pctText = if (q.hasTrade) Format.trendPercent(q.changePct) else "未成交"
        val priceText = Format.price(q.displayPrice, q.decimals)
        setFont(headerSize, color, bold = true)
        val pctW = paint.measureText(pctText)
        drawRight(c, pctText, right, top)
        val priceW = paint.measureText(priceText)
        drawRight(c, priceText, right - pctW - base * 0.6f, top)
        setFont(headerSize, palette.text, bold = true)
        drawLeft(c, displayName(s, q, withTicker = true), pad, top, right - pad - pctW - priceW - base * 1.2f)

        val small = base * 0.82f
        val rowH = small * 1.4f
        val rows = mutableListOf<Pair<String, Int>>()
        val roomy = h > base * 8.5f
        if (mode == DisplayModes.MIXED && settings.showStats && roomy) {
            rows += "高 ${Format.priceOrDash(q.high, q.decimals)}  低 ${Format.priceOrDash(q.low, q.decimals)}  量 ${Format.volume(q)}  额 ${Format.amount(q)}" to palette.subText
        }
        if (roomy) rows += holdingLines(s, q)

        val chartBottom = h - pad - rows.size * rowH
        chartRect.set(pad, top + base * 0.5f, right, chartBottom)
        val font = max(base * 0.62f, 11f)
        if (chartRect.height() > font * 3) drawChart(c, chartRect, s, q, mode, font)
        drawHoldingRows(c, rows, pad, right, h - pad, small, rowH)
    }

    /** 放大模式：左图右盘口。 */
    private fun drawDetail(c: Canvas, s: StockConfig, q: Quote, w: Float, h: Float, mode: String) {
        val base = min(w / 34f, h / 22f) * scale
        val pad = base * 0.8f
        val panelW = w * 0.25f
        val chartRight = w - panelW - pad
        val color = palette.forChange(q.change)

        var y = pad + base * 1.8f
        setFont(base * 1.9f, palette.text, bold = true)
        val name = displayName(s, q)
        c.drawText(name, pad, y, paint)
        var x = pad + paint.measureText(name) + base
        setFont(base, palette.subText)
        c.drawText(StockCodes.shortCode(s.code), x, y, paint)
        x += paint.measureText(StockCodes.shortCode(s.code)) + base
        setFont(base * 1.9f, color, bold = true)
        val line = Format.price(q.displayPrice, q.decimals) + "  " +
            (if (q.hasTrade) Format.signed(q.change, q.decimals) + "  " + Format.trendPercent(q.changePct) else "未成交")
        shrinkToFit(line, chartRight - x, base * 1.9f)
        c.drawText(line, x, y, paint)

        val rows = holdingLines(s, q)
        val small = base * 1.05f
        val rowH = small * 1.45f
        chartRect.set(pad, y + base, chartRight, h - pad - rows.size * rowH)
        drawChart(c, chartRect, s, q, mode, max(base * 0.8f, 12f))
        drawHoldingRows(c, rows, pad, chartRight, h - pad, small, rowH)

        val px = w - panelW
        val pr = w - pad
        var py = pad + base
        val levelSize = base * 0.82f
        val levelH = levelSize * 1.38f
        fun level(label: String, price: Double, volume: Double) {
            py += levelH
            setFont(levelSize, palette.subText)
            c.drawText(label, px, py, paint)
            setFont(levelSize, palette.forChange(price - q.prevClose))
            if (price > 0) c.drawText(Format.price(price, q.decimals), px + (pr - px) * 0.25f, py, paint)
            setFont(levelSize, palette.text)
            if (volume > 0) drawRight(c, "%.0f".format(volume), pr, py)
        }
        q.asks.indices.reversed().forEach { i -> level("卖${i + 1}", q.asks[i].price, q.asks[i].volume) }
        py += levelSize * 0.45f
        rectPaint.color = palette.border
        rectPaint.style = Paint.Style.FILL
        c.drawRect(px, py, pr, py + 2f, rectPaint)
        q.bids.indices.forEach { i -> level("买${i + 1}", q.bids[i].price, q.bids[i].volume) }
        py += levelSize * 0.45f
        c.drawRect(px, py, pr, py + 2f, rectPaint)

        statPairs(q).forEach { (label, value) ->
            if (py + levelH > h - pad) return@forEach
            py += levelH
            setFont(levelSize, palette.subText)
            c.drawText(label, px, py, paint)
            setFont(levelSize, palette.text)
            drawRight(c, value, pr, py)
        }
    }

    private companion object {
        const val TICK_MS = 350L
    }
}
