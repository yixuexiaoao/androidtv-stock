package io.github.yixuexiaoao.tvstock.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.yixuexiaoao.tvstock.AppCore
import io.github.yixuexiaoao.tvstock.data.MarketClock
import io.github.yixuexiaoao.tvstock.data.QuoteRepository
import io.github.yixuexiaoao.tvstock.log.AppLog
import io.github.yixuexiaoao.tvstock.model.AlertEvent
import io.github.yixuexiaoao.tvstock.model.AlertModes
import io.github.yixuexiaoao.tvstock.model.AppConfig
import io.github.yixuexiaoao.tvstock.model.DisplayModes
import io.github.yixuexiaoao.tvstock.model.GridLayoutDef
import io.github.yixuexiaoao.tvstock.model.Layouts
import io.github.yixuexiaoao.tvstock.model.Markets
import io.github.yixuexiaoao.tvstock.model.StockCodes
import io.github.yixuexiaoao.tvstock.model.StockConfig
import io.github.yixuexiaoao.tvstock.model.StockSort
import io.github.yixuexiaoao.tvstock.update.UpdateManager
import org.json.JSONObject
import kotlin.math.max

/**
 * 主看盘界面。遥控器：
 * 方向键移动焦点（到边缘翻页）；确认放大/还原；长按确认或菜单键打开设置；
 * 频道+/- 翻页；数字键切换画面数；0 切换图文方式；* 键或顶部标签切换 A 股/美股；返回键退出放大或退出 App。
 */
class MainActivity : Activity(), AppCore.Listener {
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var headerRow: LinearLayout
    private lateinit var header: HeaderView
    private lateinit var gear: TextView
    private val marketTabs = LinkedHashMap<String, TextView>()
    private var shownMarket = ""
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
    private var pendingFocusIndex: Int? = null
    private var appliedTheme = ""
    private var screenKept: Boolean? = null
    private var lastSoundMs = 0L

    private val clockTick = object : Runnable {
        override fun run() {
            // 每秒刷新：时钟、网络异常提示、后台地址都从这里更新
            refreshHeader()
            maybeAutoPage()
            // 自动主题到了切换时间
            if (Palette.resolvedTheme(config.settings) != appliedTheme) {
                AppLog.i("UI", "主题切换为 ${Palette.resolvedTheme(config.settings)}")
                applyConfig()
                renderAlerts()
            }
            updateKeepScreen()
            UpdateManager.autoCheckIfDue()
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
        buildViews()
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
        Markets.ALL.forEach { market ->
            val tab = TextView(this).apply {
                text = Markets.label(market)
                gravity = Gravity.CENTER
                isFocusable = true
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { setMarket(market) }
                setOnFocusChangeListener { _, _ -> applyGearStyle() }
            }
            marketTabs[market] = tab
            headerRow.addView(tab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        headerRow.addView(gear, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // 顶部两端按左右键停在原地，否则焦点会跳到下面的窗口
        marketTabs.values.first().apply {
            id = View.generateViewId()
            nextFocusLeftId = id
        }
        gear.id = View.generateViewId()
        gear.nextFocusRightId = gear.id
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

    /** 规范要求静态界面不要一直阻止屏保；默认只在交易时段保持亮屏。 */
    private fun updateKeepScreen() {
        val keep = when (config.settings.keepScreen) {
            "ALWAYS" -> true
            "NEVER" -> false
            else -> MarketClock.isKeepAwakeTime(System.currentTimeMillis(), config.settings.market)
        }
        if (keep == screenKept) return
        screenKept = keep
        if (keep) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun applyGearStyle() {
        val p = Palette.of(config.settings)
        gear.setTextColor(if (gear.isFocused) Color.BLACK else p.subText)
        gear.setBackgroundColor(if (gear.isFocused) p.accent else p.background)
        marketTabs.forEach { (market, tab) ->
            val selected = market == config.settings.market
            tab.setTextColor(
                when {
                    tab.isFocused -> Color.BLACK
                    selected -> p.accent
                    else -> p.subText
                },
            )
            tab.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tab.setBackgroundColor(if (tab.isFocused) p.accent else p.background)
        }
    }

    private fun headerFocused(): Boolean = gear.isFocused || marketTabs.values.any { it.isFocused }

    override fun onStart() {
        super.onStart()
        AppCore.addListener(this)
        AppCore.repo.foreground = true
        lastPageFlipMs = System.currentTimeMillis()
        applyConfig()
        updateKeepScreen()
        renderAlerts()
        if (AppCore.activeAlerts.isNotEmpty()) startFlash()
        if (!config.settings.guideShown) {
            // 真正弹出时才记为已显示；延迟期间离开界面，下次回来再弹
            handler.postDelayed({
                AppCore.updateSettings(JSONObject().put("guideShown", true))
                StockDialogs.help(this)
            }, GUIDE_DELAY_MS)
        }
        AppCore.repo.wake()
        onUpdate()
        handler.post(clockTick)
        handler.post(burnInTick)
    }

    override fun onStop() {
        AppCore.repo.foreground = false
        AppCore.removeListener(this)
        handler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    // ---- 渲染 ----

    /** 当前市场按排序方式的可显示股票；分页、放大切换和提醒跳页都用这个顺序。 */
    private fun enabledStocks(): List<StockConfig> {
        val market = config.settings.market
        val stocks = config.stocks.filter { it.enabled && StockCodes.market(it.code) == market }
        return StockSort.apply(stocks, AppCore.repo.quotes, config.settings.sortMode)
    }

    private fun applyConfig() {
        val s = config.settings
        val marketChanged = s.market != shownMarket
        if (marketChanged) {
            // 两个市场的股票不同，放大和页码都要重来
            shownMarket = s.market
            zoomCode = null
            zoomMode = ""
            page = 0
        }
        val palette = Palette.of(s)
        appliedTheme = Palette.resolvedTheme(s)
        root.setBackgroundColor(palette.background)
        val margin = dp(s.screenMargin)
        content.setPadding(margin, margin, margin, margin)
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
        if (marketChanged) {
            grid.post {
                if (!headerFocused() && cells.none { it.isFocused }) cells.firstOrNull { it.isFocusable }?.requestFocus()
            }
        }
    }

    private fun currentLayout(): GridLayoutDef =
        if (zoomCode != null) Layouts.byId("1")!! else config.settings.effectiveLayout()

    private fun ensureCells() {
        val layout = currentLayout()
        val gap = config.settings.gridGap
        if (layout == builtLayout && gap == builtGap && cells.isNotEmpty()) return
        val focusIndex = pendingFocusIndex ?: cells.indexOfFirst { it.isFocused }
        pendingFocusIndex = null
        val keepHeaderFocus = headerFocused()
        builtLayout = layout
        builtGap = gap
        grid.removeAllViews()
        cells.clear()
        layout.cells.forEach { _ ->
            val cell = StockCellView(this)
            cell.setOnClickListener { toggleZoom(cell) }
            cell.setOnLongClickListener {
                showQuickMenu(cell)
                true
            }
            cells += cell
            grid.addView(cell)
        }
        positionCells()
        grid.post {
            if (keepHeaderFocus) return@post
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
        // 排序会让股票换格子，焦点跟着股票走
        val focusedCode = cells.firstOrNull { it.isFocused }?.stock?.code
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
        if (focusedCode != null) {
            cells.firstOrNull { it.stock?.code == focusedCode && !it.isFocused }?.requestFocus()
        }
        val repo = AppCore.repo
        val chartsChanged = repo.wantMinute != minuteWanted || repo.wantKline != klineWanted
        repo.wantMinute = minuteWanted
        repo.wantKline = klineWanted
        // 新出现的股票要立刻取图表，否则非交易时段可能空白很久
        if (chartsChanged) repo.wake()
    }

    private fun refreshHeader() {
        val repo = AppCore.repo
        header.quotes = repo.quotes
        header.webUrl = AppCore.webUrl
        header.error = repo.lastError
        val now = System.currentTimeMillis()
        val market = config.settings.market
        val status = MarketClock.status(now, repo.quotes[QuoteRepository.indexCodes(market).first()]?.tradeDate, market)
        header.marketActive = status == "交易中"
        header.marketText = if (market == Markets.US) "$status 美东 ${MarketClock.timeString(now, market).take(5)}" else status
        // 交易时段行情太久没更新时明确提示，避免把旧价格当实时价格
        val age = (now - repo.lastSuccessMs) / 1000
        val limit = maxOf(config.settings.refreshSec * 3L, 15L)
        header.delayText = if (MarketClock.isTradingTime(now, market) && repo.lastSuccessMs > 0 && age > limit) "行情延迟 ${age}秒" else ""
        header.muteText = if (AppCore.isMuted) "静音 ${AppCore.muteRemainingMin()}分" else ""
        header.updateText = when (UpdateManager.state) {
            UpdateManager.State.AVAILABLE, UpdateManager.State.READY -> "新版本 ${UpdateManager.release?.version}"
            UpdateManager.State.DOWNLOADING -> "下载更新 ${UpdateManager.progress}%"
            else -> ""
        }
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
        val now = System.currentTimeMillis()
        val eventMarket = StockCodes.market(event.code)
        if (eventMarket != config.settings.market) {
            if (config.settings.alertJumpPage && event.modes.contains(AlertModes.CELL)) {
                // 窗口闪烁只有切到对应市场才看得到
                AppCore.updateSettings(JSONObject().put("market", eventMarket))
                applyConfig()
            } else if (!event.modes.contains(AlertModes.BANNER) && !event.modes.contains(AlertModes.FULLSCREEN)) {
                toast(event.message)
            }
        }
        val soundAllowed = event.isTest || (!AppCore.isMuted && now - lastSoundMs >= SOUND_GAP_MS)
        if (event.modes.contains(AlertModes.SOUND) && soundAllowed) {
            lastSoundMs = now
            val s = config.settings
            val sound = if (event.direction == AlertEvent.DOWN) s.soundDown else s.soundUp
            SoundPlayer.play(sound, s.soundVolume, s.soundRepeat)
        }
        val zoomed = zoomCode
        if (config.settings.alertJumpPage && zoomed != null && zoomed != event.code && event.modes.contains(AlertModes.CELL)) {
            // 放大其他股票时窗口闪烁看不到，切到到价的股票
            if (enabledStocks().any { it.code == event.code }) {
                zoomCode = event.code
                zoomMode = ""
                bindCells()
            }
        } else if (config.settings.alertJumpPage && zoomed == null && event.modes.contains(AlertModes.CELL)) {
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

    override fun onUpdate() {
        refreshHeader()
        if (UpdateManager.consumeInstallRequest()) UpdateManager.install(this)
        UpdateManager.consumePrompt()?.let { StockDialogs.updatePrompt(this, it) }
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
        fun colorOf(event: AlertEvent) = parseColor(
            if (event.direction == AlertEvent.DOWN) config.settings.flashColorDown else config.settings.flashColor,
        )

        cells.forEach { cell ->
            val event = cell.stock?.let { active[it.code] }
            val flash = event != null && event.modes.contains(AlertModes.CELL)
            if (event != null) cell.flashColor = colorOf(event)
            if (cell.flashing != flash || cell.flashOn != (flash && flashPhase)) {
                cell.flashing = flash
                cell.flashOn = flash && flashPhase
                cell.invalidate()
            }
        }

        val full = active.values.lastOrNull { it.modes.contains(AlertModes.FULLSCREEN) }
        if (full != null) {
            flashOverlay.visibility = View.VISIBLE
            flashOverlay.color = (colorOf(full) and 0x00FFFFFF) or (0xD0 shl 24)
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
            val color = colorOf(banners.last())
            val dim = Color.rgb(Color.red(color) * 11 / 20, Color.green(color) * 11 / 20, Color.blue(color) * 11 / 20)
            banner.setBackgroundColor(if (flashPhase) color else dim)
        } else {
            banner.visibility = View.GONE
        }
    }

    // ---- 盯盘快捷操作 ----

    private fun toggleMute() {
        val minutes = if (AppCore.isMuted) 0 else MUTE_MINUTES
        AppCore.muteSound(minutes)
        toast(if (minutes > 0) "提示音静音 $minutes 分钟" else "已取消静音")
    }

    private fun setMarket(market: String) {
        if (market == config.settings.market) return
        AppCore.updateSettings(JSONObject().put("market", market))
        toast("已切换到" + Markets.label(market))
    }

    private fun togglePrivacy() {
        val next = !config.settings.privacy
        AppCore.updateSettings(JSONObject().put("privacy", next))
        toast(if (next) "隐私模式已开启" else "隐私模式已关闭")
    }

    private fun cycleSort() {
        val modes = StockSort.ALL
        val next = modes[(modes.indexOf(config.settings.sortMode) + 1) % modes.size]
        AppCore.updateSettings(JSONObject().put("sortMode", next))
        toast("排序：" + StockSort.label(next))
    }

    private fun cycleLayout() {
        val ids = Layouts.ALL.map { it.id } + (if (config.settings.customLayout != null) listOf(Layouts.CUSTOM_ID) else emptyList())
        val index = ids.indexOf(config.settings.layoutId)
        val next = ids[(index + 1) % ids.size]
        AppCore.updateSettings(JSONObject().put("layoutId", next))
        toast(config.settings.effectiveLayout().name)
    }

    /** 长按确认：对当前股票的快捷菜单，不用离开看盘界面。 */
    private fun showQuickMenu(cell: StockCellView) {
        val code = cell.stock?.code
        val items = buildList {
            if (code != null) {
                add((if (zoomCode != null) "还原宫格" else "放大查看") to { toggleZoom(cell) })
                addAll(StockDialogs.stockActions(this@MainActivity, code))
            }
            val otherMarket = Markets.other(config.settings.market)
            add(("切换到" + Markets.label(otherMarket)) to { setMarket(otherMarket) })
            add((if (AppCore.isMuted) "取消静音" else "提示音静音 $MUTE_MINUTES 分钟") to ::toggleMute)
            add((if (config.settings.privacy) "关闭隐私模式" else "开启隐私模式") to ::togglePrivacy)
            add(("切换排序（当前：" + StockSort.label(config.settings.sortMode) + "）") to ::cycleSort)
            add("切换布局" to ::cycleLayout)
            add("最近提醒" to { StockDialogs.recentAlerts(this@MainActivity) })
            add("后台二维码" to ::showWebQr)
            add("使用帮助" to { StockDialogs.help(this@MainActivity) })
            add("打开设置" to ::openSettings)
        }
        val quote = code?.let { AppCore.repo.quotes[it] }
        val title = cell.stock?.alias?.takeIf { it.isNotBlank() } ?: quote?.name ?: "快捷菜单"
        StockDialogs.menu(this, title, items)
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
            if (!headerFocused()) cells.firstOrNull { it.stock != null }?.requestFocus()
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
        AppLog.d("Key", "${KeyEvent.keyCodeToString(key)} repeat=${event.repeatCount}")
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
            KeyEvent.KEYCODE_STAR -> {
                setMarket(Markets.other(config.settings.market))
                return true
            }
            KeyEvent.KEYCODE_PROG_RED -> {
                toggleMute()
                return true
            }
            KeyEvent.KEYCODE_PROG_GREEN -> {
                togglePrivacy()
                return true
            }
            KeyEvent.KEYCODE_PROG_YELLOW -> {
                cycleSort()
                return true
            }
            KeyEvent.KEYCODE_PROG_BLUE -> {
                cycleLayout()
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
            finish()
        } else {
            lastBackMs = now
            toast("再按一次返回键退出")
        }
    }

    private fun showWebQr() = StockDialogs.webQr(this)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MUTE_MINUTES = 30
        /** 等 Web 服务启动后再弹引导，这样二维码能显示 */
        const val GUIDE_DELAY_MS = 1500L
        /** 同一轮多只股票同时触发时只播一次，避免声音混在一起 */
        const val SOUND_GAP_MS = 1500L
        val BURN_IN_OFFSETS = listOf(0 to 0, 2 to 0, 2 to 2, 0 to 2, -2 to 2, -2 to 0, -2 to -2, 0 to -2, 2 to -2)
    }
}
