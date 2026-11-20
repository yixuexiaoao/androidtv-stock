package io.github.yixuexiaoao.tvstock.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import io.github.yixuexiaoao.tvstock.model.Box
import io.github.yixuexiaoao.tvstock.model.HeatTile
import io.github.yixuexiaoao.tvstock.model.HeatmapGroup
import io.github.yixuexiaoao.tvstock.model.HeatmapItem
import io.github.yixuexiaoao.tvstock.model.MarketBreadth
import io.github.yixuexiaoao.tvstock.model.NavDir
import io.github.yixuexiaoao.tvstock.model.Themes
import io.github.yixuexiaoao.tvstock.model.Treemap
import io.github.yixuexiaoao.tvstock.model.TreemapLayout
import kotlin.math.max
import kotlin.math.min

/**
 * 原生 Canvas 热力图视图，供「大盘云图」与「自选热力图」共用。
 * 性能与防泄漏设计：
 * - 静态云图底图缓存在单张 RGB_565 离屏 Bitmap（显存减半），仅数据/主题/尺寸变化时重绘；
 * - 遥控器移动焦点与提醒闪烁直接贴底图后仅画高亮框与底部摘要条（< 1ms）；
 * - 视图离窗或尺寸变更时立即 recycle() 释放 Native 像素内存，不在 onDraw 分配大对象。
 */
class HeatmapView(context: Context) : View(context) {
    var onActivate: ((HeatmapItem) -> Unit)? = null
    var onLongActivate: ((HeatmapItem?) -> Unit)? = null

    private var title = ""
    private var weightLabel = ""
    private var updatedText = ""
    private var hintText = ""
    private var loading = false
    private var errorText: String? = null
    private var breadth = MarketBreadth()
    private var groups: List<HeatmapGroup> = emptyList()
    private var items: List<HeatmapItem> = emptyList()
    private var palette = Palette.of(io.github.yixuexiaoao.tvstock.model.AppSettings())
    private var fontScale = 100
    private var isUsd = false

    private var layout = TreemapLayout(emptyList(), emptyList())
    private var selectedIndex = -1
    private var selectedId: String? = null
    private var alertFlashColors: Map<String, Int> = emptyMap()
    private var flashOn = false

    private var cacheBitmap: Bitmap? = null
    private var cacheCanvas: Canvas? = null
    private var cacheDirty = true

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var topBarH = 0f
    private var bottomBarH = 0f

    init {
        isFocusable = true
        isClickable = true
        isLongClickable = true
        setOnClickListener { selectedItem()?.let { onActivate?.invoke(it) } }
        setOnLongClickListener {
            onLongActivate?.invoke(selectedItem())
            true
        }
    }

    fun bind(
        title: String,
        weightLabel: String,
        updatedText: String,
        hintText: String,
        breadth: MarketBreadth,
        groups: List<HeatmapGroup>,
        items: List<HeatmapItem>,
        loading: Boolean,
        errorText: String?,
        palette: Palette,
        fontScale: Int,
        isUsd: Boolean = false,
    ) {
        val layoutChanged = this.groups != groups || this.items != items ||
            this.palette != palette || this.fontScale != fontScale
        val headerChanged = this.title != title || this.weightLabel != weightLabel ||
            this.updatedText != updatedText || this.breadth != breadth ||
            this.loading != loading || this.errorText != errorText || this.isUsd != isUsd
        this.title = title
        this.weightLabel = weightLabel
        this.updatedText = updatedText
        this.hintText = hintText
        this.breadth = breadth
        this.groups = groups
        this.items = items
        this.loading = loading
        this.errorText = errorText
        this.palette = palette
        this.fontScale = fontScale
        this.isUsd = isUsd

        if (layoutChanged) {
            rebuildLayout()
            cacheDirty = true
            invalidate()
        } else if (headerChanged) {
            cacheDirty = true
            invalidate()
        }
    }

    fun updateAlerts(colors: Map<String, Int>, flashOn: Boolean) {
        if (this.alertFlashColors == colors && this.flashOn == flashOn) return
        this.alertFlashColors = colors
        this.flashOn = flashOn
        invalidate()
    }

    fun selectedItem(): HeatmapItem? = layout.tiles.getOrNull(selectedIndex)?.item

    fun selectId(id: String?) {
        selectedId = id
        syncSelectionIndex()
        invalidate()
    }

    /** 方向键在色块间导航；返回 false 表示该方向已到边缘，交由父容器处理（如回到顶部导航栏）。 */
    fun moveFocus(dir: NavDir): Boolean {
        val tiles = layout.tiles
        if (tiles.isEmpty()) return false
        if (selectedIndex !in tiles.indices) {
            selectedIndex = 0
            selectedId = tiles[0].item.id
            invalidate()
            return true
        }
        val next = Treemap.navigate(tiles, selectedIndex, dir)
        if (next < 0) return false
        selectedIndex = next
        selectedId = tiles[next].item.id
        invalidate()
        return true
    }

    private fun syncSelectionIndex() {
        val tiles = layout.tiles
        if (tiles.isEmpty()) {
            selectedIndex = -1
            return
        }
        val idx = selectedId?.let { id -> tiles.indexOfFirst { it.item.id == id } } ?: -1
        selectedIndex = if (idx >= 0) idx else 0
        selectedId = tiles[selectedIndex].item.id
    }

    private fun computeBars(h: Float) {
        topBarH = (h * 0.052f).coerceIn(28f, 44f)
        bottomBarH = (h * 0.056f).coerceIn(30f, 48f)
    }

    private fun rebuildLayout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 4f || h <= 4f) {
            layout = TreemapLayout(emptyList(), emptyList())
            return
        }
        computeBars(h)
        val pad = 4f
        val area = Box(pad, topBarH + pad, w - pad, h - bottomBarH - pad)
        layout = if (groups.isNotEmpty()) {
            val headerH = max(18f, min(26f, h * 0.028f) * (fontScale / 100f).coerceIn(0.8f, 1.3f))
            Treemap.layoutGrouped(groups, area, groupGap = 4f, tileGap = 1.5f, headerHeight = headerH)
        } else {
            Treemap.layoutFlat(items, area, gap = 2.5f)
        }
        syncSelectionIndex()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            releaseCache()
            rebuildLayout()
            cacheDirty = true
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus && selectedIndex < 0 && layout.tiles.isNotEmpty()) {
            selectedIndex = 0
            selectedId = layout.tiles[0].item.id
        }
        invalidate()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // 切回宫格看盘或退到后台不可见时立即释放离屏位图，零显存滞留
        if (visibility != VISIBLE) releaseCache()
    }

    override fun onDetachedFromWindow() {
        releaseCache()
        super.onDetachedFromWindow()
    }

    private fun releaseCache() {
        cacheCanvas = null
        cacheBitmap?.recycle()
        cacheBitmap = null
        cacheDirty = true
    }

    private fun ensureCache(w: Int, h: Int): Bitmap? {
        val existing = cacheBitmap
        if (existing != null && !existing.isRecycled && existing.width == w && existing.height == h) {
            return existing
        }
        releaseCache()
        return try {
            // RGB_565 不带 Alpha，1080p 下仅占约 3.9MB，比 ARGB_8888 节省一半显存
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            cacheBitmap = bmp
            cacheCanvas = Canvas(bmp)
            cacheDirty = true
            bmp
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val x = event.x
            val y = event.y
            val hit = layout.tiles.indexOfFirst { t ->
                val b = t.box
                x >= b.left && x <= b.right && y >= b.top && y <= b.bottom
            }
            if (hit >= 0) {
                selectedIndex = hit
                selectedId = layout.tiles[hit].item.id
                requestFocus()
                invalidate()
                onActivate?.invoke(layout.tiles[hit].item)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val bmp = ensureCache(w, h)
        if (bmp != null && cacheCanvas != null) {
            if (cacheDirty) {
                drawStaticBase(cacheCanvas!!, w.toFloat(), h.toFloat())
                cacheDirty = false
            }
            canvas.drawBitmap(bmp, 0f, 0f, null)
        } else {
            drawStaticBase(canvas, w.toFloat(), h.toFloat())
        }

        // 动态层：告警闪烁、焦点高亮边框与底部选中详情条
        drawDynamicOverlay(canvas, w.toFloat(), h.toFloat())
    }

    private fun drawStaticBase(c: Canvas, w: Float, h: Float) {
        computeBars(h)
        c.drawColor(palette.background)
        drawTopSummaryBar(c, w)

        if (layout.tiles.isEmpty()) {
            val msg = when {
                errorText != null -> "数据加载失败：$errorText（按确认键重试）"
                loading -> "云图数据加载中…"
                else -> "暂无可展示的数据"
            }
            setText(min(w, h) * 0.036f, palette.subText, bold = false, align = Paint.Align.CENTER)
            c.drawText(msg, w * 0.5f, h * 0.5f, textPaint)
            return
        }

        // 1. 若有分组，先绘制分组容器与标题栏
        val groupBg = if (palette.theme == Themes.LIGHT) 0xFFDCE2EA.toInt() else palette.cell
        for (gb in layout.groups) {
            val b = gb.box
            fillPaint.color = groupBg
            rect.set(b.left, b.top, b.right, b.bottom)
            c.drawRect(rect, fillPaint)

            strokePaint.color = palette.border
            strokePaint.strokeWidth = 1.2f
            c.drawRect(rect, strokePaint)

            val headerH = gb.headerBottom - b.top
            if (headerH >= 14f && b.width >= 48f) {
                val ts = (headerH * 0.62f).coerceIn(10f, 18f)
                val baseline = b.top + headerH * 0.74f
                val pctStr = Format.percent(gb.group.changePct)
                setText(ts, palette.forChange(gb.group.changePct), bold = true, align = Paint.Align.RIGHT)
                val pctW = textPaint.measureText(pctStr)
                val canShowPct = b.width > pctW + ts * 3.5f
                if (canShowPct) {
                    c.drawText(pctStr, b.right - 5f, baseline, textPaint)
                }
                setText(ts, palette.text, bold = true, align = Paint.Align.LEFT)
                val maxNameW = b.width - 10f - if (canShowPct) pctW + 6f else 0f
                c.drawText(fit(gb.group.name, maxNameW), b.left + 5f, baseline, textPaint)
            }
        }

        // 2. 绘制所有叶子色块与自适应文字
        val tileTextColor = if (palette.theme == Themes.NIGHT) 0xFFE7E5E4.toInt() else Color.WHITE
        val tileSubColor = if (palette.theme == Themes.NIGHT) 0xFFD6D3D1.toInt() else 0xFFEAEFF5.toInt()
        val scale = (fontScale / 100f).coerceIn(0.65f, 1.6f)

        for (tile in layout.tiles) {
            drawTile(c, tile, tileTextColor, tileSubColor, scale)
        }
    }

    private fun drawTile(c: Canvas, tile: HeatTile, mainTextColor: Int, subTextColor: Int, scale: Float) {
        val b = tile.box
        if (b.width < 1.5f || b.height < 1.5f) return

        fillPaint.color = palette.heatColor(tile.item.changePct)
        rect.set(b.left, b.top, b.right, b.bottom)
        c.drawRect(rect, fillPaint)

        if (b.width >= 6f && b.height >= 6f) {
            strokePaint.color = palette.background
            strokePaint.strokeWidth = 1f
            c.drawRect(rect, strokePaint)
        }

        val item = tile.item
        val w = b.width
        val h = b.height
        if (w < 26f || h < 18f) return

        val pad = min(6f, w * 0.08f)
        val availW = w - pad * 2f
        val baseSize = (min(w / 5.4f, h / 2.7f) * scale).coerceIn(10f, 34f)
        val pctText = Format.percent(item.changePct)

        textPaint.setShadowLayer(2f, 0f, 1f, 0x99000000.toInt())
        try {
            when {
                // 大块：名称 + 涨跌幅 + 副信息（行业显示涨跌家数/领涨股，个股显示现价或持仓信息）
                h >= baseSize * 3.8f && w >= baseSize * 4.2f -> {
                    val sub = buildTileSubline(item)
                    val nameSize = baseSize.coerceAtMost(32f)
                    val pctSize = (nameSize * 0.86f).coerceAtLeast(10f)
                    val subSize = (nameSize * 0.62f).coerceAtLeast(10f)
                    val lineGap = nameSize * 0.28f
                    val totalH = nameSize + pctSize + (if (sub.isNotEmpty()) subSize + lineGap * 2f else lineGap)
                    var y = b.centerY - totalH * 0.5f + nameSize * 0.85f

                    setText(nameSize, mainTextColor, bold = true, align = Paint.Align.CENTER)
                    c.drawText(fit(item.name, availW), b.centerX, y, textPaint)

                    y += pctSize + lineGap
                    setText(pctSize, mainTextColor, bold = true, align = Paint.Align.CENTER)
                    c.drawText(fit(pctText, availW), b.centerX, y, textPaint)

                    if (sub.isNotEmpty()) {
                        y += subSize + lineGap
                        setText(subSize, subTextColor, bold = false, align = Paint.Align.CENTER)
                        c.drawText(fit(sub, availW), b.centerX, y, textPaint)
                    }
                }
                // 中块：名称 + 涨跌幅
                h >= baseSize * 2.35f && w >= baseSize * 2.5f -> {
                    val nameSize = baseSize.coerceAtMost(26f)
                    val pctSize = (nameSize * 0.82f).coerceAtLeast(10f)
                    val gap = nameSize * 0.2f
                    val totalH = nameSize + pctSize + gap
                    var y = b.centerY - totalH * 0.5f + nameSize * 0.85f

                    setText(nameSize, mainTextColor, bold = true, align = Paint.Align.CENTER)
                    c.drawText(fit(item.name, availW), b.centerX, y, textPaint)

                    y += pctSize + gap
                    setText(pctSize, mainTextColor, bold = false, align = Paint.Align.CENTER)
                    c.drawText(fit(pctText, availW), b.centerX, y, textPaint)
                }
                // 小块：仅名称
                else -> {
                    val nameSize = min(baseSize, h * 0.62f).coerceIn(9f, 18f)
                    setText(nameSize, mainTextColor, bold = false, align = Paint.Align.CENTER)
                    val y = b.centerY + nameSize * 0.35f
                    c.drawText(fit(item.name, availW), b.centerX, y, textPaint)
                }
            }
        } finally {
            textPaint.clearShadowLayer()
        }
    }

    private fun buildTileSubline(item: HeatmapItem): String = when {
        item.isSector && (item.upCount > 0 || item.downCount > 0) -> {
            val counts = "涨${item.upCount} 跌${item.downCount}"
            if (item.leaderName.isNotEmpty()) "$counts · ${item.leaderName}" else counts
        }
        item.isSector && item.leaderName.isNotEmpty() -> "领涨 ${item.leaderName}"
        item.badge.isNotEmpty() -> item.badge
        item.price > 0.0 -> Format.price(item.price, item.decimals)
        else -> ""
    }

    private fun drawTopSummaryBar(c: Canvas, w: Float) {
        fillPaint.color = palette.cell
        rect.set(0f, 0f, w, topBarH)
        c.drawRect(rect, fillPaint)

        val ts = (topBarH * 0.48f).coerceIn(12f, 20f)
        val baseline = topBarH * 0.68f
        val gap = ts * 0.9f
        var leftX = gap

        // 左侧标题
        setText(ts, palette.text, bold = true, align = Paint.Align.LEFT)
        c.drawText(title, leftX, baseline, textPaint)
        leftX += textPaint.measureText(title) + gap * 1.2f

        // 右侧状态（权重 + 更新时间 + 加载状态）
        var rightX = w - gap
        val statusSuffix = buildString {
            if (loading) append("刷新中…  ")
            else if (errorText != null) append("网络异常  ")
            if (updatedText.isNotEmpty()) append(updatedText).append("  ")
            if (weightLabel.isNotEmpty()) append("面积:").append(weightLabel)
        }.trim()
        if (statusSuffix.isNotEmpty()) {
            setText(ts * 0.92f, if (errorText != null) palette.up else palette.subText, bold = false, align = Paint.Align.RIGHT)
            c.drawText(statusSuffix, rightX, baseline, textPaint)
            rightX -= textPaint.measureText(statusSuffix) + gap * 1.2f
        }

        // 中部统计（涨/平/跌家数 + 成交额）
        val b = breadth
        if (b.totalCount > 0 || b.totalAmountYuan > 0.0) {
            fun drawStat(text: String, color: Int): Boolean {
                setText(ts * 0.94f, color, bold = false, align = Paint.Align.LEFT)
                val tw = textPaint.measureText(text)
                if (leftX + tw > rightX) return false
                c.drawText(text, leftX, baseline, textPaint)
                leftX += tw + gap * 0.85f
                return true
            }
            if (b.totalCount > 0) {
                if (!drawStat("涨 ${b.upCount}", palette.up)) return
                if (!drawStat("平 ${b.flatCount}", palette.flat)) return
                if (!drawStat("跌 ${b.downCount}", palette.down)) return
            }
            if (b.totalAmountYuan > 0.0) {
                val amt = if (isUsd) "$" + Format.fromWan(b.totalAmountYuan / 10_000.0)
                else Format.fromWan(b.totalAmountYuan / 10_000.0)
                drawStat("成交 $amt", palette.text)
            }
        }
    }

    private fun drawDynamicOverlay(c: Canvas, w: Float, h: Float) {
        // 1. 自选热力图到价提醒闪烁
        if (flashOn && alertFlashColors.isNotEmpty()) {
            for (tile in layout.tiles) {
                val fc = alertFlashColors[tile.item.id] ?: continue
                val b = tile.box
                fillPaint.color = (fc and 0x00FFFFFF) or (0x66 shl 24)
                rect.set(b.left, b.top, b.right, b.bottom)
                c.drawRect(rect, fillPaint)
                strokePaint.color = fc
                strokePaint.strokeWidth = 5f
                c.drawRect(rect, strokePaint)
            }
        }

        // 2. 当前选中高亮框
        val focusedTile = layout.tiles.getOrNull(selectedIndex)
        if (focusedTile != null) {
            val b = focusedTile.box
            val bw = if (isFocused) 4.5f else 2f
            val inset = bw * 0.5f
            rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
            strokePaint.color = if (isFocused) Color.WHITE else palette.accent
            strokePaint.strokeWidth = bw
            c.drawRect(rect, strokePaint)
            if (isFocused) {
                rect.set(b.left + bw, b.top + bw, b.right - bw, b.bottom - bw)
                strokePaint.color = palette.accent
                strokePaint.strokeWidth = 2f
                c.drawRect(rect, strokePaint)
            }
        }

        // 3. 底部栏：选中项摘要 + 色阶图例 + 快捷键提示
        val barTop = h - bottomBarH
        fillPaint.color = palette.cell
        rect.set(0f, barTop, w, h)
        c.drawRect(rect, fillPaint)

        val ts = (bottomBarH * 0.44f).coerceIn(11f, 18f)
        val baseline = barTop + bottomBarH * 0.67f
        val gap = ts * 0.8f
        var rightX = w - gap

        // 右侧按键提示
        if (hintText.isNotEmpty()) {
            setText(ts * 0.88f, palette.subText, bold = false, align = Paint.Align.RIGHT)
            c.drawText(hintText, rightX, baseline, textPaint)
            rightX -= textPaint.measureText(hintText) + gap * 1.2f
        }

        // 色阶图例 (-4% ~ 0 ~ +4%)
        val stops = LEGEND_STOPS
        val swatchW = (ts * 1.15f).coerceIn(14f, 24f)
        val swatchH = (bottomBarH * 0.44f).coerceIn(10f, 18f)
        val legendW = stops.size * swatchW + ts * 5.2f
        if (rightX - legendW > w * 0.42f) {
            setText(ts * 0.82f, palette.subText, bold = false, align = Paint.Align.RIGHT)
            c.drawText("+4%", rightX, baseline, textPaint)
            val rightLabelW = textPaint.measureText("+4%") + 4f
            val barRight = rightX - rightLabelW
            val barLeft = barRight - stops.size * swatchW
            val sy = barTop + (bottomBarH - swatchH) * 0.5f
            stops.forEachIndexed { i, pct ->
                fillPaint.color = palette.heatColor(pct)
                rect.set(barLeft + i * swatchW, sy, barLeft + (i + 1) * swatchW, sy + swatchH)
                c.drawRect(rect, fillPaint)
            }
            c.drawText("-4%", barLeft - 4f, baseline, textPaint)
            rightX = barLeft - textPaint.measureText("-4%") - gap * 1.4f
        }

        // 左侧当前选中项摘要
        val item = focusedTile?.item
        if (item != null) {
            val detail = buildSelectedSummary(item)
            setText(ts, palette.text, bold = true, align = Paint.Align.LEFT)
            c.drawText(fit(detail, rightX - gap), gap, baseline, textPaint)
        }
    }

    private fun buildSelectedSummary(item: HeatmapItem): String = buildString {
        append("▶ ").append(item.name)
        if (!item.isSector) {
            append(" ").append(io.github.yixuexiaoao.tvstock.model.StockCodes.shortCode(item.id))
        }
        append("  ").append(Format.trendPercent(item.changePct))
        if (!item.isSector && item.price > 0.0) {
            append("  现价 ").append(Format.price(item.price, item.decimals))
        }
        if (item.isSector && (item.upCount > 0 || item.downCount > 0)) {
            append("  涨").append(item.upCount).append(" 跌").append(item.downCount)
        }
        if (item.circCapYuan > 0.0) {
            val cap = Format.fromWan(item.circCapYuan / 10_000.0)
            append("  流通 ").append(if (isUsd) "$" else "").append(cap)
        }
        if (item.amountYuan > 0.0) {
            val amt = Format.fromWan(item.amountYuan / 10_000.0)
            append("  成交 ").append(if (isUsd) "$" else "").append(amt)
        }
        if (item.isSector && item.leaderName.isNotEmpty()) {
            append("  领涨 ").append(item.leaderName).append(" ").append(Format.percent(item.leaderPct))
        }
        if (item.badge.isNotEmpty()) {
            append("  ").append(item.badge)
        }
    }

    private fun setText(size: Float, color: Int, bold: Boolean, align: Paint.Align) {
        textPaint.textSize = size
        textPaint.color = color
        textPaint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        textPaint.textAlign = align
    }

    private fun fit(text: String, maxWidth: Float): String {
        if (maxWidth <= 0f) return ""
        return if (textPaint.measureText(text) <= maxWidth) text
        else TextUtils.ellipsize(text, textPaint, maxWidth, TextUtils.TruncateAt.END).toString()
    }

    companion object {
        private val LEGEND_STOPS = doubleArrayOf(-4.0, -3.0, -2.0, -1.0, 0.0, 1.0, 2.0, 3.0, 4.0)
    }
}
