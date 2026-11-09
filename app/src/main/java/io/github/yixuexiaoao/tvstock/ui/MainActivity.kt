package io.github.yixuexiaoao.tvstock.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.yixuexiaoao.tvstock.AppCore
import io.github.yixuexiaoao.tvstock.model.AlertEvent
import io.github.yixuexiaoao.tvstock.model.AlertModes
import io.github.yixuexiaoao.tvstock.model.AppConfig
import io.github.yixuexiaoao.tvstock.model.DisplayModes
import io.github.yixuexiaoao.tvstock.model.GridLayoutDef
import io.github.yixuexiaoao.tvstock.model.Layouts
import io.github.yixuexiaoao.tvstock.model.StockConfig
import io.github.yixuexiaoao.tvstock.web.WebService
import org.json.JSONObject
import kotlin.math.max

/**
 * 主看盘界面。遥控器：
 * 方向键移动焦点（到边缘翻页）；确认放大/还原；长按确认或菜单键打开设置；
 * 频道+/- 翻页；数字键切换画面数；0 切换图文方式；返回键退出放大或退出 App。
 */
class MainActivity : Activity(), AppCore.Listener {
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var headerRow: LinearLayout
    private lateinit var header: HeaderView
    private lateinit var gear: TextView
    private lateinit var grid: FrameLayout
    private lateinit var ticker: TickerView
    private lateinit var flashOverlay: FlashOverlayView
    private lateinit var banner: TextView

    private val cells = mutableListOf<StockCellView>()
    private var builtLayout: GridLayoutDef? = null
    private var builtGap = -1

    private var page = 0
    /** 放大查看的股票代码；null 表示宫格 */
    private var zoomCode: String? = null
    private var zoomMode = ""
    private var flashPhase = false
    private var lastPageFlipMs = 0L
    private var lastBackMs = 0L
    private var burnInStep = 0
    private var tone: ToneGenerator? = null
    private var pendingFocusIndex: Int? = null

    private val clockTick = object : Runnable {
        override fun run() {
            header.invalidate()
            maybeAutoPage()
            handler.postDelayed(this, 1000)
        }
    }

    private val flashTick = object : Runnable {
        override fun run() {
            pruneExpiredAlerts()
            if (AppCore.activeAlerts.isEmpty()) {
                stopFlash()
                return
            }
            flashPhase = !flashPhase
            renderAlerts()
            handler.postDelayed(this, config.settings.flashSpeedMs.toLong())
        }
    }

    private val burnInTick = object : Runnable {
        override fun run() {
            if (config.settings.burnInGuard) {
                burnInStep = (burnInStep + 1) % BURN_IN_OFFSETS.size
                val (dx, dy) = BURN_IN_OFFSETS[burnInStep]
                root.translationX = dx.toFloat()
                root.translationY = dy.toFloat()
            } else {
                root.translationX = 0f
                root.translationY = 0f
            }
            handler.postDelayed(this, 60_000)
        }
    }

    private val config: AppConfig get() = AppCore.config

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildViews()
        WebService.start(this)
        AppCore.repo.start()
    }

    private fun buildViews() {
        root = FrameLayout(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header = HeaderView(this)
        gear = TextView(this).apply {
            text = "⚙ 设置"
            gravity = Gravity.CENTER
            isFocusable = true
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { openSettings() }
            setOnFocusChangeListener { _, _ -> applyGearStyle() }
        }
        headerRow.addView(header, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        headerRow.addView(gear, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        grid = FrameLayout(this)
        ticker = TickerView(this)

        content.addView(headerRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)))
        content.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(ticker, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)))
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        flashOverlay = FlashOverlayView(this).apply {
            visibility = View.GONE
            isFocusable = false
        }
        root.addView(flashOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        banner = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isFocusable = false
        }
        root.addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        setContentView(root)
        grid.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) grid.post { positionCells() }
        }
    }

    private fun applyGearStyle() {
        val p = Palette.of(config.settings)
        gear.setTextColor(if (gear.isFocused) Color.BLACK else p.subText)
        gear.setBackgroundColor(if (gear.isFocused) p.accent else p.background)
    }

    override fun onStart() {
        super.onStart()
        AppCore.addListener(this)
        AppCore.repo.foreground = true
        applyConfig()
        renderAlerts()
        if (AppCore.activeAlerts.isNotEmpty()) startFlash()
        AppCore.repo.wake()
        handler.post(clockTick)
        handler.post(burnInTick)
    }

    override fun onStop() {
        AppCore.repo.foreground = false
        AppCore.removeListener(this)
        handler.removeCallbacksAndMessages(null)
        tone?.release()
        tone = null
        super.onStop()
    }

    // ---- 渲染 ----

    private fun enabledStocks(): List<StockConfig> = config.stocks.filter { it.enabled }

    private fun applyConfig() {
        val s = config.settings
        val palette = Palette.of(s)
        root.setBackgroundColor(palette.background)
        grid.setBackgroundColor(palette.background)

        header.config = config
        header.palette = palette
        ticker.config = config
        ticker.palette = palette
        headerRow.visibility = if (s.showHeader) View.VISIBLE else View.GONE
        ticker.visibility = if (s.showTicker) View.VISIBLE else View.GONE
        applyGearStyle()

        ensureCells()
        bindCells()
        refreshHeader()
    }

    private fun currentLayout(): GridLayoutDef =
        if (zoomCode != null) Layouts.byId("1")!! else config.settings.effectiveLayout()

    private fun ensureCells() {
        val layout = currentLayout()
        val gap = config.settings.gridGap
        if (layout == builtLayout && gap == builtGap && cells.isNotEmpty()) return
        val focusIndex = pendingFocusIndex ?: cells.indexOfFirst { it.isFocused }
        pendingFocusIndex = null
        val keepGearFocus = gear.isFocused
        builtLayout = layout
        builtGap = gap
        grid.removeAllViews()
        cells.clear()
        layout.cells.forEach { _ ->
            val cell = StockCellView(this)
            cell.setOnClickListener { toggleZoom(cell) }
            cell.setOnLongClickListener {
                openSettings()
                true
            }
            cells += cell
            grid.addView(cell)
        }
        positionCells()
        grid.post {
            if (keepGearFocus) return@post
            val target = cells.getOrNull(max(focusIndex, 0))?.takeIf { it.isFocusable }
                ?: cells.firstOrNull { it.isFocusable }
            (target ?: gear).requestFocus()
        }
    }

    private fun positionCells() {
        val layout = builtLayout ?: return
        val w = grid.width
        val h = grid.height
        if (w <= 0 || h <= 0) return
        val gap = builtGap
        val unitW = (w - gap).toFloat() / layout.cols
        val unitH = (h - gap).toFloat() / layout.rows
        layout.cells.forEachIndexed { i, c ->
            val view = cells.getOrNull(i) ?: return@forEachIndexed
            val left = gap + (c.x * unitW).toInt()
            val top = gap + (c.y * unitH).toInt()
            val right = ((c.x + c.w) * unitW).toInt()
            val bottom = ((c.y + c.h) * unitH).toInt()
            val lp = FrameLayout.LayoutParams(right - left, bottom - top)
            lp.leftMargin = left
            lp.topMargin = top
            view.layoutParams = lp
        }
    }

    private fun pageCount(): Int = Layouts.pageCount(enabledStocks().size, cells.size)

    private fun bindCells() {
        val s = config.settings
        val palette = Palette.of(s)
        val stocks = enabledStocks()
        val zoom = zoomCode
        val visible = if (zoom != null) {
            listOfNotNull(stocks.firstOrNull { it.code == zoom })
        } else {
            page = Math.floorMod(page, pageCount())
            Layouts.pageItems(stocks, cells.size, page)
        }
        if (zoom != null && visible.isEmpty()) {
            zoomCode = null
            ensureCells()
            bindCells()
            return
        }
        val minuteWanted = HashSet<String>()
        val klineWanted = HashSet<String>()
        cells.forEachIndexed { i, cell ->
            val stock = visible.getOrNull(i)
            val changed = cell.stock?.code != stock?.code
            cell.stock = stock
            cell.settings = s
            cell.palette = palette
            cell.detail = zoom != null
            cell.modeOverride = if (zoom != null) zoomMode else ""
            cell.flashColor = parseColor(s.flashColor)
            if (changed) cell.updateQuote(null)
            if (stock != null) {
                cell.updateQuote(AppCore.repo.quotes[stock.code])
                cell.minutePoints = AppCore.repo.minutes[stock.code].orEmpty()
                cell.klineList = AppCore.repo.klines[stock.code]?.lines.orEmpty()
                when (cell.displayMode()) {
                    DisplayModes.MIXED, DisplayModes.MINUTE -> minuteWanted += stock.code
                    DisplayModes.KLINE -> klineWanted += stock.code
                }
            } else {
                cell.minutePoints = emptyList()
                cell.klineList = emptyList()
            }
            cell.isFocusable = stock != null
            cell.invalidate()
        }
        AppCore.repo.wantMinute = minuteWanted
        AppCore.repo.wantKline = klineWanted
    }

    private fun refreshHeader() {
        val repo = AppCore.repo
        header.quotes = repo.quotes
        header.webUrl = AppCore.webUrl
        header.error = repo.lastError
        val pages = pageCount()
        header.pageText = if (zoomCode == null && pages > 1) "第 ${page + 1}/$pages 页" else ""
        header.invalidate()
        ticker.quotes = repo.quotes
    }

    private fun parseColor(hex: String): Int = try {
        Color.parseColor(hex)
    } catch (e: IllegalArgumentException) {
        Color.RED
    }

    // ---- AppCore 回调 ----

    override fun onQuotes() {
        bindCells()
        refreshHeader()
    }

    override fun onCharts() = bindCells()

    override fun onConfig() {
        applyConfig()
        renderAlerts()
    }

    override fun onAlert(event: AlertEvent) {
        if (event.modes.contains(AlertModes.SOUND)) playAlertSound()
        if (config.settings.alertJumpPage && zoomCode == null && event.modes.contains(AlertModes.CELL)) {
            val index = enabledStocks().indexOfFirst { it.code == event.code }
            if (index >= 0 && cells.isNotEmpty()) {
                val target = index / cells.size
                if (target != page) {
                    page = target
                    bindCells()
                    refreshHeader()
                }
            }
        }
        flashPhase = true
        renderAlerts()
        startFlash()
    }

    override fun onAlertsCleared() {
        stopFlash()
    }

    // ---- 提醒 ----

    private fun startFlash() {
        handler.removeCallbacks(flashTick)
        handler.postDelayed(flashTick, config.settings.flashSpeedMs.toLong())
    }

    private fun stopFlash() {
        handler.removeCallbacks(flashTick)
        flashPhase = false
        renderAlerts()
    }

    private fun pruneExpiredAlerts() {
        val duration = config.settings.alertDurationSec
        if (duration <= 0) return
        val now = System.currentTimeMillis()
        val expired = AppCore.activeAlerts.values.filter { now - it.timeMs >= duration * 1000L }
        expired.forEach { AppCore.activeAlerts.remove(it.code) }
    }

    private fun renderAlerts() {
        val active = AppCore.activeAlerts
        val color = parseColor(config.settings.flashColor)

        cells.forEach { cell ->
            val event = cell.stock?.let { active[it.code] }
            val flash = event != null && event.modes.contains(AlertModes.CELL)
            if (cell.flashing != flash || cell.flashOn != (flash && flashPhase)) {
                cell.flashing = flash
                cell.flashOn = flash && flashPhase
                cell.invalidate()
            }
        }

        val full = active.values.lastOrNull { it.modes.contains(AlertModes.FULLSCREEN) }
        if (full != null) {
            flashOverlay.visibility = View.VISIBLE
            flashOverlay.color = (color and 0x00FFFFFF) or (0xD0 shl 24)
            flashOverlay.flashOn = flashPhase
            flashOverlay.title = full.message
            flashOverlay.subtitle = "按 确认/返回 键关闭提醒"
            flashOverlay.invalidate()
        } else {
            flashOverlay.visibility = View.GONE
        }

        val banners = active.values.filter { it.modes.contains(AlertModes.BANNER) }
        if (banners.isNotEmpty()) {
            banner.visibility = View.VISIBLE
            banner.text = banners.takeLast(3).joinToString("\n") { it.message }
            banner.setBackgroundColor(if (flashPhase) color else (color and 0x00FFFFFF) or (0xA0 shl 24))
        } else {
            banner.visibility = View.GONE
        }
    }

    private fun playAlertSound() {
        try {
            val generator = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 100).also { tone = it }
            generator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1500)
        } catch (_: RuntimeException) {
            // 部分电视没有可用音频通道
        }
    }

    // ---- 翻页 / 放大 / 布局 ----

    private fun maybeAutoPage() {
        val seconds = config.settings.pageIntervalSec
        if (seconds <= 0 || zoomCode != null || pageCount() <= 1) return
        if (AppCore.activeAlerts.isNotEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastPageFlipMs >= seconds * 1000L) changePage(1)
    }

    private fun changePage(delta: Int) {
        lastPageFlipMs = System.currentTimeMillis()
        page += delta
        bindCells()
        refreshHeader()
        renderAlerts()
        val focused = cells.firstOrNull { it.isFocused }
        if (focused == null || focused.stock == null) {
            if (!gear.isFocused) cells.firstOrNull { it.stock != null }?.requestFocus()
        }
    }

    private fun toggleZoom(cell: StockCellView) {
        val zoom = zoomCode
        if (zoom != null) {
            leaveZoom()
        } else if (cell.stock != null) {
            zoomCode = cell.stock?.code
            zoomMode = ""
            ensureCells()
            bindCells()
            refreshHeader()
            renderAlerts()
        }
    }

    private fun leaveZoom() {
        val code = zoomCode ?: return
        zoomCode = null
        zoomMode = ""
        val index = enabledStocks().indexOfFirst { it.code == code }
        val cellCount = config.settings.effectiveLayout().cells.size
        if (index >= 0) {
            page = index / cellCount
            pendingFocusIndex = index % cellCount
        }
        ensureCells()
        bindCells()
        refreshHeader()
        renderAlerts()
    }

    private fun zoomStep(delta: Int) {
        val stocks = enabledStocks()
        if (stocks.isEmpty()) return
        val index = stocks.indexOfFirst { it.code == zoomCode }
        zoomCode = stocks[Math.floorMod(index + delta, stocks.size)].code
        bindCells()
        renderAlerts()
        AppCore.repo.wake()
    }

    private fun cycleZoomMode(delta: Int) {
        val current = cells.firstOrNull()?.displayMode() ?: DisplayModes.MINUTE
        val modes = DisplayModes.ALL
        zoomMode = modes[Math.floorMod(modes.indexOf(current) + delta, modes.size)]
        bindCells()
        toast(DisplayModes.label(zoomMode))
        AppCore.repo.wake()
    }

    private fun setLayout(id: String) {
        AppCore.updateSettings(JSONObject().put("layoutId", id))
        toast(Layouts.byId(id)?.name ?: id)
    }

    private fun cycleDisplayMode() {
        val modes = DisplayModes.ALL
        val next = modes[(modes.indexOf(config.settings.displayMode) + 1) % modes.size]
        AppCore.updateSettings(JSONObject().put("displayMode", next))
        toast(DisplayModes.label(next))
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ---- 遥控器 ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && handleKey(event)) return true
        // 关闭提醒时吃掉确认键的抬起事件，避免紧接着触发放大
        if (event.action == KeyEvent.ACTION_UP && swallowUpKey == event.keyCode) {
            swallowUpKey = -1
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private var swallowUpKey = -1

    private fun handleKey(event: KeyEvent): Boolean {
        val key = event.keyCode
        val isConfirm = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (AppCore.activeAlerts.isNotEmpty() && (isConfirm || key == KeyEvent.KEYCODE_BACK)) {
            AppCore.clearAlerts()
            swallowUpKey = key
            return true
        }
        when (key) {
            KeyEvent.KEYCODE_BACK -> {
                onBackKey()
                swallowUpKey = key
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                openSettings()
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                if (zoomCode != null) zoomStep(-1) else changePage(-1)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (zoomCode != null) zoomStep(1) else changePage(1)
                return true
            }
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> {
                cycleDisplayMode()
                return true
            }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> {
                showWebQr()
                return true
            }
        }
        val digit = when (key) {
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> key - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_1..KeyEvent.KEYCODE_NUMPAD_9 -> key - KeyEvent.KEYCODE_NUMPAD_0
            else -> null
        }
        if (digit != null) {
            Layouts.forDigit(digit)?.let(::setLayout)
            return true
        }
        return if (zoomCode != null) handleZoomDpad(key) else handleGridDpad(key)
    }

    private fun handleZoomDpad(key: Int): Boolean {
        when (key) {
            KeyEvent.KEYCODE_DPAD_LEFT -> zoomStep(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> zoomStep(1)
            KeyEvent.KEYCODE_DPAD_UP -> cycleZoomMode(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> cycleZoomMode(1)
            else -> return false
        }
        return true
    }

    /** 左右方向键到达边缘且有多页时翻页。 */
    private fun handleGridDpad(key: Int): Boolean {
        val direction = when (key) {
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            else -> return false
        }
        val focused = currentFocus as? StockCellView ?: return false
        if (pageCount() <= 1) return false
        if (focused.focusSearch(direction) is StockCellView) return false
        changePage(if (direction == View.FOCUS_RIGHT) 1 else -1)
        return true
    }

    private fun onBackKey() {
        if (zoomCode != null) {
            leaveZoom()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBackMs < 2000) {
            if (!config.settings.keepServiceOnExit) WebService.stop(this)
            finish()
        } else {
            lastBackMs = now
            toast("再按一次返回键退出")
        }
    }

    private fun showWebQr() {
        val url = AppCore.webUrl
        if (url.isEmpty()) {
            toast("Web 后台未启动")
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        box.addView(ImageView(this).apply { setImageBitmap(QrCode.create(url, dp(220))) })
        box.addView(TextView(this).apply {
            text = "手机扫码或在浏览器打开\n$url"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        })
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setView(box)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val BURN_IN_OFFSETS = listOf(0 to 0, 2 to 0, 2 to 2, 0 to 2, -2 to 2, -2 to 0, -2 to -2, 0 to -2, 2 to -2)
    }
}
