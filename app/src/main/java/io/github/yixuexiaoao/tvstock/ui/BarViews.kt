package io.github.yixuexiaoao.tvstock.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import io.github.yixuexiaoao.tvstock.data.MarketClock
import io.github.yixuexiaoao.tvstock.model.AppConfig
import io.github.yixuexiaoao.tvstock.model.PortfolioSummary
import io.github.yixuexiaoao.tvstock.model.Quote

/** 顶部状态栏：大盘指数、持仓汇总、Web 地址、页码、时钟、网络状态。 */
class HeaderView(context: Context) : View(context) {
    var config = AppConfig()
    var quotes: Map<String, Quote> = emptyMap()
    var webUrl = ""
    var pageText = ""
    var error: String? = null
    var updateText = ""
    var palette = Palette.of(config.settings)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val s = config.settings
        paint.textSize = h * 0.46f
        val baseline = h * 0.67f
        val gap = h * 0.5f
        canvas.drawColor(palette.background)

        var right = width - gap * 0.3f
        paint.textAlign = Paint.Align.RIGHT
        paint.typeface = Typeface.DEFAULT
        if (s.showClock) {
            paint.color = palette.text
            val clock = MarketClock.timeString(System.currentTimeMillis())
            canvas.drawText(clock, right, baseline, paint)
            right -= paint.measureText(clock) + gap
        }
        if (pageText.isNotEmpty()) {
            paint.color = palette.accent
            canvas.drawText(pageText, right, baseline, paint)
            right -= paint.measureText(pageText) + gap
        }
        if (updateText.isNotEmpty()) {
            paint.color = palette.accent
            canvas.drawText(updateText, right, baseline, paint)
            right -= paint.measureText(updateText) + gap
        }
        error?.let {
            paint.color = palette.up
            canvas.drawText("网络异常", right, baseline, paint)
            right -= paint.measureText("网络异常") + gap
        }
        if (webUrl.isNotEmpty()) {
            paint.color = palette.subText
            val urlText = "后台 " + webUrl.removePrefix("http://")
            canvas.drawText(urlText, right, baseline, paint)
            right -= paint.measureText(urlText) + gap
        }

        paint.textAlign = Paint.Align.LEFT
        var x = gap * 0.3f
        if (s.showIndices) {
            for ((code, name) in INDICES) {
                val q = quotes[code] ?: continue
                val label = "$name ${Format.price(q.displayPrice)} ${Format.percent(q.changePct)}"
                val w = paint.measureText(label)
                if (x + w > right) break
                paint.color = palette.forChange(q.change)
                canvas.drawText(label, x, baseline, paint)
                x += w + gap
            }
        }
        if (s.showPortfolio && !s.privacy) {
            PortfolioSummary.of(config.stocks, quotes)?.let { p ->
                val label = "持仓 ${Format.money(p.marketValue)}  今日 ${Format.signedMoney(p.dayProfit)}  累计 ${Format.signedMoney(p.profit)}"
                if (x + paint.measureText(label) <= right) {
                    paint.color = palette.forChange(p.profit)
                    canvas.drawText(label, x, baseline, paint)
                }
            }
        }
    }

    private companion object {
        val INDICES = listOf("sh000001" to "上证", "sz399001" to "深证", "sz399006" to "创业板")
    }
}

/** 底部滚动行情条。 */
class TickerView(context: Context) : View(context) {
    var config = AppConfig()
    var quotes: Map<String, Quote> = emptyMap()
    var palette = Palette.of(config.settings)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var offset = 0f
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            // 不可见时只保持低频检查，不累加偏移，也不重绘
            if (isShown) {
                offset += height * SPEED_FACTOR
                invalidate()
                postDelayed(this, FRAME_MS)
            } else {
                postDelayed(this, IDLE_MS)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        post(tick)
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.background)
        val h = height.toFloat()
        paint.textSize = h * 0.6f
        val baseline = h * 0.72f
        val gap = h * 1.2f
        val items = config.stocks.filter { it.enabled }.mapNotNull { s ->
            quotes[s.code]?.let { q ->
                "${s.alias.ifBlank { q.name }} ${Format.price(q.displayPrice, q.decimals)} ${Format.percent(q.changePct)}" to q.change
            }
        }
        if (items.isEmpty()) return
        val total = items.sumOf { (paint.measureText(it.first) + gap).toDouble() }.toFloat()
        if (total <= 0) return
        offset %= total
        var x = -offset
        while (x < width) {
            for ((text, change) in items) {
                paint.color = palette.forChange(change)
                canvas.drawText(text, x, baseline, paint)
                x += paint.measureText(text) + gap
            }
        }
    }

    private companion object {
        const val FRAME_MS = 50L
        const val IDLE_MS = 1000L
        const val SPEED_FACTOR = 0.06f
    }
}

/** 全屏闪烁层：不可获取焦点，只用来叠加颜色和提示文字。 */
class FlashOverlayView(context: Context) : View(context) {
    var color = 0
    var flashOn = false
    var title = ""
    var subtitle = ""

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: Canvas) {
        if (flashOn) canvas.drawColor(color)
        val cx = width / 2f
        val cy = height / 2f
        paint.color = 0xFFFFFFFF.toInt()
        paint.setShadowLayer(12f, 0f, 0f, 0xFF000000.toInt())
        paint.textSize = 72f.coerceAtMost(height / 9f)
        canvas.drawText(title.take(32), cx, cy, paint)
        paint.textSize = paint.textSize * 0.5f
        canvas.drawText(subtitle, cx, cy + paint.textSize * 2f, paint)
    }
}
