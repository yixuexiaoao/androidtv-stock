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
import io.github.yixuexiaoao.tvstock.model.CloudScopes
import io.github.yixuexiaoao.tvstock.model.DisplayModes
import io.github.yixuexiaoao.tvstock.model.GridLayoutDef
import io.github.yixuexiaoao.tvstock.model.HeatmapItem
import io.github.yixuexiaoao.tvstock.model.HeatmapWeights
import io.github.yixuexiaoao.tvstock.model.Holding
import io.github.yixuexiaoao.tvstock.model.HomeTabs
import io.github.yixuexiaoao.tvstock.model.Layouts
import io.github.yixuexiaoao.tvstock.model.MarketBreadth
import io.github.yixuexiaoao.tvstock.model.Markets
import io.github.yixuexiaoao.tvstock.model.NavDir
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
    private lateinit var cloudTab: TextView
    private lateinit var gear: TextView
    private val marketTabs = LinkedHashMap<String, TextView>()
    private var shownMarket = ""
    private var shownHomeTab = ""
    private var shownWatchHeatmap = false
    private lateinit var stage: FrameLayout
    private lateinit var grid: FrameLayout
    private lateinit var heatmapView: HeatmapView
    private lateinit var ticker: TickerView
    private lateinit var flashOverlay: FlashOverlayView
    private lateinit var banner: TextView

    private val cells = mutableListOf<StockCellView>()
    private var builtLayout: GridLayoutDef? = null
    private var builtGap = -1

    private var page = 0
    /** 放大查看的股票代码；null 表示宫格或热力图 */
    private var zoomCode: String? = null
    private var zoomMode = ""
    /** 标记当前放大页是否从大盘云图进入，返回时还原到云图并释放临时代码 */
    private var zoomFromCloud = false
    private var zoomItemName = ""
    /** 大盘云图下钻的行业板块码；null 表示一级宏观/指数视图 */
    private var drillSectorId: String? = null
    private var drillSectorName = ""
    private var lastSectorId: String? = null
    private var isActivityVisible = false
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
        cloudTab = TextView(this).apply {
            id = View.generateViewId()
            text = "云图"
            gravity = Gravity.CENTER
            isFocusable = true
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { openCloudTab() }
            setOnFocusChangeListener { _, _ -> applyGearStyle() }
        }
        gear = TextView(this).apply {
            id = View.generateViewId()
            text = "⚙ 设置"
            gravity = Gravity.CENTER
            isFocusable = true
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { openSettings() }
            setOnFocusChangeListener { _, _ -> applyGearStyle() }
        }
        headerRow.addView(header, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        // 顶部标签顺序：云图 | A股 | 美股 | ⚙ 设置
        headerRow.addView(cloudTab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        Markets.ALL.forEach { market ->
            val tab = TextView(this).apply {
                id = View.generateViewId()
                text = Markets.label(market)
                gravity = Gravity.CENTER
                isFocusable = true
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { setMarket(market, enterWatch = true) }
                setOnFocusChangeListener { _, _ -> applyGearStyle() }
            }
            marketTabs[market] = tab
            headerRow.addView(tab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        headerRow.addView(gear, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // 顶部两端按左右键停在原地，否则焦点会跳到下面的窗口
        cloudTab.nextFocusLeftId = cloudTab.id
        gear.nextFocusRightId = gear.id

        stage = FrameLayout(this)
        grid = FrameLayout(this)
        heatmapView = HeatmapView(this).apply {
            id = View.generateViewId()
            visibility = View.GONE
            onActivate = ::onHeatmapItemActivate
            onLongActivate = ::showHeatmapQuickMenu
        }
        stage.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        stage.addView(heatmapView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ticker = TickerView(this)

        content.addView(headerRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)))
        content.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
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
        val activeMarket = if (config.settings.homeTab == HomeTabs.CLOUD) Markets.CN else config.settings.market
        val keep = when (config.settings.keepScreen) {
            "ALWAYS" -> true
            "NEVER" -> false
            else -> MarketClock.isKeepAwakeTime(System.currentTimeMillis(), activeMarket)
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
        val inCloud = config.settings.homeTab == HomeTabs.CLOUD
        cloudTab.setTextColor(
            when {
                cloudTab.isFocused -> Color.BLACK
                inCloud -> p.accent
                else -> p.subText
            },
        )
        cloudTab.typeface = if (inCloud) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        cloudTab.setBackgroundColor(if (cloudTab.isFocused) p.accent else p.background)

        gear.setTextColor(if (gear.isFocused) Color.BLACK else p.subText)
        gear.setBackgroundColor(if (gear.isFocused) p.accent else p.background)
        marketTabs.forEach { (market, tab) ->
            val selected = !inCloud && market == config.settings.market
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

    private fun headerFocused(): Boolean = cloudTab.isFocused || gear.isFocused || marketTabs.values.any { it.isFocused }

    private fun focusActiveHeaderTab() {
        val target = if (config.settings.homeTab == HomeTabs.CLOUD) cloudTab else marketTabs[config.settings.market] ?: cloudTab
        target.requestFocus()
    }

    private fun showingHeatmap(): Boolean =
        zoomCode == null && (config.settings.homeTab == HomeTabs.CLOUD || config.settings.watchHeatmap)

    private fun isCloudActive(): Boolean = zoomCode == null && config.settings.homeTab == HomeTabs.CLOUD

    override fun onStart() {
        super.onStart()
        isActivityVisible = true
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
        isActivityVisible = false
        AppCore.heatmap.deactivate()
        AppCore.repo.ephemeralCode = null
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
        val initial = shownHomeTab.isEmpty()
        val marketChanged = s.market != shownMarket
        val tabChanged = s.homeTab != shownHomeTab
        val watchModeChanged = s.watchHeatmap != shownWatchHeatmap
        if (marketChanged || tabChanged) {
            shownMarket = s.market
            shownHomeTab = s.homeTab
            if (zoomCode != null) {
                zoomCode = null
                zoomMode = ""
                zoomFromCloud = false
                AppCore.repo.ephemeralCode = null
            }
            if (tabChanged && s.homeTab == HomeTabs.CLOUD) {
                // 回到云图默认看行业宏观图
                drillSectorId = null
                drillSectorName = ""
            }
            page = 0
        }
        shownWatchHeatmap = s.watchHeatmap
        val palette = Palette.of(s)
        appliedTheme = Palette.resolvedTheme(s)
        root.setBackgroundColor(palette.background)
        val margin = dp(s.screenMargin)
        content.setPadding(margin, margin, margin, margin)
        stage.setBackgroundColor(palette.background)
        grid.setBackgroundColor(palette.background)

        header.config = config
        header.palette = palette
        ticker.config = config
        ticker.palette = palette
        headerRow.visibility = if (s.showHeader) View.VISIBLE else View.GONE
        ticker.visibility = if (s.showTicker && s.homeTab != HomeTabs.CLOUD) View.VISIBLE else View.GONE
        applyGearStyle()

        syncStageVisibility()
        if (showingHeatmap()) {
            // 热力图下不拉取不可见的分时/K 线，节省网络与 CPU
            AppCore.repo.wantMinute = emptySet()
            AppCore.repo.wantKline = emptySet()
            syncHeatmapPolling()
            bindHeatmap()
        } else {
            AppCore.heatmap.deactivate()
            ensureCells()
            bindCells()
        }
        refreshHeader()
        if (marketChanged || tabChanged || watchModeChanged) {
            stage.post {
                if (!initial && headerFocused()) return@post
                if (showingHeatmap()) {
                    heatmapView.requestFocus()
                } else if (cells.none { it.isFocused }) {
                    cells.firstOrNull { it.isFocusable }?.requestFocus()
                }
            }
        }
    }

    private fun syncStageVisibility() {
        val showHeat = showingHeatmap()
        heatmapView.visibility = if (showHeat) View.VISIBLE else View.GONE
        grid.visibility = if (showHeat) View.GONE else View.VISIBLE
    }

    private fun syncHeatmapPolling(forceRefresh: Boolean = false) {
        if (!isActivityVisible || !isCloudActive()) {
            AppCore.heatmap.deactivate()
            return
        }
        val s = config.settings
        AppCore.heatmap.activate(s.cloudScope, drillSectorId, drillSectorName, s.heatmapWeight, forceRefresh)
    }

    private fun bindHeatmap() {
        if (!showingHeatmap()) return
        val s = config.settings
        val palette = Palette.of(s)
        if (s.homeTab == HomeTabs.CLOUD) {
            val repo = AppCore.heatmap
            val snap = repo.current()
            val scopeLabel = CloudScopes.label(s.cloudScope)
            val title = if (drillSectorId != null) "$drillSectorName › 成分股" else "A股大盘云图 · $scopeLabel"
            val wLabel = HeatmapWeights.label(if (s.heatmapWeight == HeatmapWeights.AMOUNT) HeatmapWeights.AMOUNT else HeatmapWeights.CIRC_CAP)
            val updated = if (snap != null && snap.fetchedAtMs > 0L) {
                val src = if (snap.source.isNotEmpty()) "${snap.source} " else ""
                src + MarketClock.timeString(snap.fetchedAtMs, Markets.CN).take(5)
            } else {
                ""
            }
            val hint = if (drillSectorId != null) {
                "确认:放大  返回:行业  蓝键:面积"
            } else if (s.cloudScope == CloudScopes.SECTORS) {
                "确认:下钻行业  黄键:范围  蓝键:面积"
            } else {
                "确认:放大个股  黄键:范围  蓝键:面积"
            }
            heatmapView.bind(
                title = title,
                weightLabel = wLabel,
                updatedText = updated,
                hintText = hint,
                breadth = snap?.breadth ?: MarketBreadth(),
                groups = snap?.groups.orEmpty(),
                items = snap?.items.orEmpty(),
                loading = repo.loading,
                errorText = repo.lastError,
                palette = palette,
                fontScale = s.fontScale,
                isUsd = false,
            )
        } else {
            bindWatchHeatmap(palette)
        }
    }

    private fun bindWatchHeatmap(palette: Palette) {
        val s = config.settings
        val quotes = AppCore.repo.quotes
        val stocks = enabledStocks()
        val isUsd = s.market == Markets.US
        // 隐私模式下若选了持仓市值，自动回退流通市值以防泄露仓位比例
        val effectiveWeight = if (s.privacy && s.heatmapWeight == HeatmapWeights.HOLDING) {
            HeatmapWeights.CIRC_CAP
        } else {
            s.heatmapWeight
        }
        val holdings = stocks.associate { it.code to Holding.of(it, quotes[it.code]) }
        val anyHolding = effectiveWeight == HeatmapWeights.HOLDING && holdings.values.any { it != null && it.marketValue > 0.0 }
        val modeForWeight = if (effectiveWeight == HeatmapWeights.HOLDING && !anyHolding) HeatmapWeights.CIRC_CAP else effectiveWeight

        var up = 0
        var flat = 0
        var down = 0
        var totalAmt = 0.0
        val items = stocks.map { stock ->
            val q = quotes[stock.code]
            val pct = if (q != null && q.hasTrade) q.changePct else 0.0
            if (q != null && q.hasTrade) {
                when {
                    pct > 0.0 -> up++
                    pct < 0.0 -> down++
                    else -> flat++
                }
            }
            val amtYuan = if (q != null && !stock.code.startsWith("us.")) q.amountWan * 10_000.0 else 0.0
            totalAmt += amtYuan
            val circYuan = (q?.circCapYi?.takeIf { it > 0.0 } ?: q?.totalCapYi?.takeIf { it > 0.0 } ?: 0.0) * 100_000_000.0
            val holding = holdings[stock.code]
            val w = when (modeForWeight) {
                HeatmapWeights.EQUAL -> 1.0
                HeatmapWeights.AMOUNT -> amtYuan.takeIf { it > 0.0 } ?: circYuan.takeIf { it > 0.0 } ?: 1.0
                HeatmapWeights.HOLDING -> holding?.marketValue?.takeIf { it > 0.0 } ?: 1.0
                else -> circYuan.takeIf { it > 0.0 } ?: amtYuan.takeIf { it > 0.0 } ?: 1.0
            }
            val badge = if (!s.privacy && s.showHoldings && holding?.profitPct != null) {
                "持仓 " + Format.percent(holding.profitPct)
            } else {
                ""
            }
            HeatmapItem(
                id = stock.code,
                name = stock.alias.ifBlank { q?.name ?: StockCodes.shortCode(stock.code) },
                changePct = pct,
                weight = w,
                price = q?.displayPrice ?: 0.0,
                decimals = q?.decimals ?: 2,
                amountYuan = amtYuan,
                circCapYuan = circYuan,
                badge = badge,
                isSector = false,
            )
        }
        val title = Markets.label(s.market) + " 自选热力图"
        heatmapView.bind(
            title = title,
            weightLabel = HeatmapWeights.label(modeForWeight),
            updatedText = if (AppCore.repo.lastSuccessMs > 0L) MarketClock.timeString(AppCore.repo.lastSuccessMs, s.market).take(5) else "",
            hintText = "确认:放大  蓝键:面积  长按:切回宫格",
            breadth = MarketBreadth(up, flat, down, totalAmt),
            groups = emptyList(),
            items = items,
            loading = quotes.isEmpty() && stocks.isNotEmpty(),
            errorText = AppCore.repo.lastError,
            palette = palette,
            fontScale = s.fontScale,
            isUsd = isUsd,
        )
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

    private fun pageCount(): Int {
        val market = config.settings.market
        val count = config.stocks.count { it.enabled && StockCodes.market(it.code) == market }
        return Layouts.pageCount(count, cells.size)
    }

    private fun bindCells() {
        val s = config.settings
        val palette = Palette.of(s)
        val stocks = enabledStocks()
        val zoom = zoomCode
        val visible = if (zoom != null) {
            val found = config.stocks.firstOrNull { it.code == zoom }
            if (found != null) listOf(found)
            else if (zoomFromCloud) listOf(StockConfig(code = zoom, alias = zoomItemName))
            else emptyList()
        } else {
            page = Math.floorMod(page, pageCount())
            Layouts.pageItems(stocks, cells.size, page)
        }
        if (zoom != null && visible.isEmpty()) {
            zoomCode = null
            zoomFromCloud = false
            AppCore.repo.ephemeralCode = null
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
            val nextQuote = stock?.let { AppCore.repo.quotes[it.code] }
            val nextMinutes = stock?.let { AppCore.repo.minutes[it.code] }.orEmpty()
            val nextKlines = stock?.let { AppCore.repo.klines[it.code]?.lines }.orEmpty()
            val nextMode = if (zoom != null) zoomMode else ""
            // 每轮行情都会走到这里，内容没变化就不重绘，低配电视能省不少 CPU
            val dirty = cell.stock != stock || cell.settings !== s || cell.palette != palette ||
                cell.detail != (zoom != null) || cell.modeOverride != nextMode || cell.quote != nextQuote ||
                cell.minutePoints !== nextMinutes || cell.klineList !== nextKlines
            cell.stock = stock
            cell.settings = s
            cell.palette = palette
            cell.detail = zoom != null
            cell.modeOverride = nextMode
            if (changed) cell.updateQuote(null)
            if (stock != null) {
                cell.updateQuote(nextQuote)
                cell.minutePoints = nextMinutes
                cell.klineList = nextKlines
                when (cell.displayMode()) {
                    DisplayModes.MIXED, DisplayModes.MINUTE -> minuteWanted += stock.code
                    DisplayModes.KLINE -> klineWanted += stock.code
                }
            } else {
                cell.minutePoints = emptyList()
                cell.klineList = emptyList()
            }
            cell.isFocusable = stock != null
            if (dirty) cell.invalidate()
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
        val market = if (config.settings.homeTab == HomeTabs.CLOUD) Markets.CN else config.settings.market
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
        header.pageText = if (zoomCode == null && !showingHeatmap() && pages > 1) "第 ${page + 1}/$pages 页" else ""
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
        if (showingHeatmap()) {
            if (config.settings.homeTab == HomeTabs.WATCH) bindHeatmap()
        } else {
            bindCells()
        }
        refreshHeader()
    }

    override fun onCharts() {
        if (!showingHeatmap()) bindCells()
    }

    override fun onHeatmap() {
        if (isCloudActive()) bindHeatmap()
    }

    override fun onConfig() {
        applyConfig()
        renderAlerts()
    }

    override fun onAlert(event: AlertEvent) {
        val now = System.currentTimeMillis()
        val eventMarket = StockCodes.market(event.code)
        val needSwitchTab = config.settings.homeTab == HomeTabs.CLOUD || eventMarket != config.settings.market
        if (needSwitchTab) {
            if (config.settings.alertJumpPage && event.modes.contains(AlertModes.CELL)) {
                // 窗口闪烁只有切到对应市场的自选界面才看得到
                AppCore.updateSettings(JSONObject().put("homeTab", HomeTabs.WATCH).put("market", eventMarket))
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
                zoomFromCloud = false
                AppCore.repo.ephemeralCode = null
                bindCells()
            }
        } else if (config.settings.alertJumpPage && zoomed == null && event.modes.contains(AlertModes.CELL)) {
            if (showingHeatmap()) {
                heatmapView.selectId(event.code)
            } else {
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

        val heatAlerts = if (showingHeatmap() && config.settings.homeTab == HomeTabs.WATCH) {
            active.values.filter { it.modes.contains(AlertModes.CELL) }.associate { it.code to colorOf(it) }
        } else {
            emptyMap()
        }
        heatmapView.updateAlerts(heatAlerts, flashPhase)

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

    private fun openCloudTab() {
        if (config.settings.homeTab == HomeTabs.CLOUD && drillSectorId == null) return
        drillSectorId = null
        drillSectorName = ""
        if (config.settings.homeTab != HomeTabs.CLOUD) {
            AppCore.updateSettings(JSONObject().put("homeTab", HomeTabs.CLOUD))
            toast("已切换到大盘云图")
        } else {
            syncHeatmapPolling()
            bindHeatmap()
        }
    }

    private fun setMarket(market: String, enterWatch: Boolean = false) {
        val patch = JSONObject()
        if (enterWatch && config.settings.homeTab != HomeTabs.WATCH) patch.put("homeTab", HomeTabs.WATCH)
        if (market != config.settings.market) patch.put("market", market)
        if (patch.length() == 0) return
        AppCore.updateSettings(patch)
        toast("已切换到" + Markets.label(market))
    }

    private fun toggleWatchHeatmap() {
        val next = !config.settings.watchHeatmap
        val patch = JSONObject().put("watchHeatmap", next)
        if (config.settings.homeTab != HomeTabs.WATCH) patch.put("homeTab", HomeTabs.WATCH)
        AppCore.updateSettings(patch)
        toast(if (next) "已切换自选热力图" else "已切回宫格看盘")
    }

    private fun cycleCloudScope(delta: Int = 1) {
        val scopes = CloudScopes.ALL
        val idx = scopes.indexOf(config.settings.cloudScope).let { if (it < 0) 0 else it }
        val next = scopes[Math.floorMod(idx + delta, scopes.size)]
        drillSectorId = null
        drillSectorName = ""
        AppCore.updateSettings(JSONObject().put("cloudScope", next))
        toast("云图范围：" + CloudScopes.label(next))
    }

    private fun cycleHeatmapWeight() {
        val modes = if (config.settings.homeTab == HomeTabs.CLOUD) HeatmapWeights.CLOUD_MODES else HeatmapWeights.ALL
        val idx = modes.indexOf(config.settings.heatmapWeight).let { if (it < 0) 0 else it }
        val next = modes[(idx + 1) % modes.size]
        AppCore.updateSettings(JSONObject().put("heatmapWeight", next))
        toast("面积指标：" + HeatmapWeights.label(next))
    }

    private fun onHeatmapItemActivate(item: HeatmapItem) {
        if (item.isSector) {
            lastSectorId = item.id
            drillSectorId = item.id
            drillSectorName = item.name
            heatmapView.selectId(null)
            syncHeatmapPolling()
            bindHeatmap()
            toast("${item.name} 成分股")
        } else {
            openZoomFromHeatmap(item)
        }
    }

    private fun openZoomFromHeatmap(item: HeatmapItem) {
        val fromCloud = config.settings.homeTab == HomeTabs.CLOUD
        zoomCode = item.id
        zoomMode = ""
        zoomFromCloud = fromCloud
        zoomItemName = item.name
        if (fromCloud && config.stocks.none { it.code == item.id }) {
            AppCore.repo.ephemeralCode = item.id
        } else {
            AppCore.repo.ephemeralCode = null
        }
        AppCore.heatmap.deactivate()
        syncStageVisibility()
        ensureCells()
        bindCells()
        refreshHeader()
        renderAlerts()
        AppCore.repo.wake()
    }

    private fun leaveSectorDrill() {
        if (drillSectorId == null) return
        drillSectorId = null
        drillSectorName = ""
        heatmapView.selectId(lastSectorId)
        syncHeatmapPolling()
        bindHeatmap()
    }

    private fun showHeatmapQuickMenu(item: HeatmapItem?) {
        val inCloud = config.settings.homeTab == HomeTabs.CLOUD
        val items = buildList {
            if (item != null) {
                if (item.isSector) {
                    add("进入 ${item.name} 成分股" to { onHeatmapItemActivate(item) })
                } else {
                    add("放大查看 ${item.name}" to { openZoomFromHeatmap(item) })
                    if (config.stocks.none { it.code == item.id }) {
                        add("＋ 加入自选股" to {
                            AppCore.upsertStock(StockConfig(item.id))
                            toast("已加入自选：${item.name}")
                        })
                    } else if (!inCloud) {
                        addAll(StockDialogs.stockActions(this@MainActivity, item.id))
                    }
                }
            }
            if (inCloud) {
                if (drillSectorId != null) add("返回行业宏观图" to ::leaveSectorDrill)
                add(("切换云图范围（当前：" + CloudScopes.label(config.settings.cloudScope) + "）") to { cycleCloudScope() })
                add(("切换面积指标（当前：" + HeatmapWeights.label(if (config.settings.heatmapWeight == HeatmapWeights.AMOUNT) HeatmapWeights.AMOUNT else HeatmapWeights.CIRC_CAP) + "）") to ::cycleHeatmapWeight)
                add("立即刷新云图" to { syncHeatmapPolling(forceRefresh = true); toast("正在刷新云图…") })
                add("切换到" + Markets.label(config.settings.market) + "自选" to { setMarket(config.settings.market, enterWatch = true) })
            } else {
                add("切回宫格看盘" to ::toggleWatchHeatmap)
                add(("切换面积指标（当前：" + HeatmapWeights.label(config.settings.heatmapWeight) + "）") to ::cycleHeatmapWeight)
                add("切换到大盘云图" to ::openCloudTab)
                val otherMarket = Markets.other(config.settings.market)
                add(("切换到" + Markets.label(otherMarket)) to { setMarket(otherMarket, enterWatch = true) })
            }
            add((if (AppCore.isMuted) "取消静音" else "提示音静音 $MUTE_MINUTES 分钟") to ::toggleMute)
            add((if (config.settings.privacy) "关闭隐私模式" else "开启隐私模式") to ::togglePrivacy)
            add("最近提醒" to { StockDialogs.recentAlerts(this@MainActivity) })
            add("后台二维码" to ::showWebQr)
            add("使用帮助" to { StockDialogs.help(this@MainActivity) })
            add("打开设置" to ::openSettings)
        }
        StockDialogs.menu(this, item?.name ?: if (inCloud) "大盘云图" else "自选热力图", items)
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
        val inWatchlist = code != null && config.stocks.any { it.code == code }
        val items = buildList {
            if (code != null) {
                add((if (zoomCode != null) (if (zoomFromCloud) "返回云图" else "还原宫格") else "放大查看") to { toggleZoom(cell) })
                if (inWatchlist) {
                    addAll(StockDialogs.stockActions(this@MainActivity, code))
                } else {
                    add("＋ 加入自选股" to {
                        AppCore.upsertStock(StockConfig(code))
                        toast("已加入自选")
                    })
                }
            }
            if (zoomCode == null) {
                add("切换自选热力图" to ::toggleWatchHeatmap)
            }
            add("切换到大盘云图" to ::openCloudTab)
            val otherMarket = Markets.other(config.settings.market)
            add(("切换到" + Markets.label(otherMarket)) to { setMarket(otherMarket, enterWatch = true) })
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
        if (seconds <= 0 || zoomCode != null || showingHeatmap() || pageCount() <= 1) return
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
            zoomFromCloud = false
            AppCore.repo.ephemeralCode = null
            ensureCells()
            bindCells()
            refreshHeader()
            renderAlerts()
        }
    }

    private fun leaveZoom() {
        val code = zoomCode ?: return
        val backToCloud = zoomFromCloud
        zoomCode = null
        zoomMode = ""
        zoomFromCloud = false
        zoomItemName = ""
        AppCore.repo.ephemeralCode = null
        syncStageVisibility()
        if (showingHeatmap()) {
            AppCore.repo.wantMinute = emptySet()
            AppCore.repo.wantKline = emptySet()
            if (backToCloud) syncHeatmapPolling()
            bindHeatmap()
            heatmapView.selectId(code)
            heatmapView.post { heatmapView.requestFocus() }
        } else {
            val index = enabledStocks().indexOfFirst { it.code == code }
            val cellCount = config.settings.effectiveLayout().cells.size
            if (index >= 0) {
                page = index / cellCount
                pendingFocusIndex = index % cellCount
            }
            ensureCells()
            bindCells()
        }
        refreshHeader()
        renderAlerts()
    }

    private fun zoomStep(delta: Int) {
        if (zoomFromCloud) {
            val items = AppCore.heatmap.current()?.items?.filter { !it.isSector }.orEmpty()
            if (items.isEmpty()) return
            val index = items.indexOfFirst { it.id == zoomCode }.let { if (it < 0) 0 else it }
            val target = items[Math.floorMod(index + delta, items.size)]
            zoomCode = target.id
            zoomItemName = target.name
            AppCore.repo.ephemeralCode = if (config.stocks.none { it.code == target.id }) target.id else null
            bindCells()
            renderAlerts()
            AppCore.repo.wake()
            return
        }
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
        val patch = JSONObject().put("layoutId", id)
        if (config.settings.watchHeatmap) patch.put("watchHeatmap", false)
        if (config.settings.homeTab == HomeTabs.CLOUD) patch.put("homeTab", HomeTabs.WATCH)
        AppCore.updateSettings(patch)
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
                when {
                    zoomCode != null -> zoomStep(-1)
                    isCloudActive() -> cycleCloudScope(-1)
                    !showingHeatmap() -> changePage(-1)
                }
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                when {
                    zoomCode != null -> zoomStep(1)
                    isCloudActive() -> cycleCloudScope(1)
                    !showingHeatmap() -> changePage(1)
                }
                return true
            }
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> {
                if (config.settings.homeTab == HomeTabs.WATCH && zoomCode == null && config.settings.watchHeatmap) {
                    toggleWatchHeatmap()
                } else {
                    cycleDisplayMode()
                }
                return true
            }
            KeyEvent.KEYCODE_STAR -> {
                if (config.settings.homeTab == HomeTabs.CLOUD) {
                    setMarket(config.settings.market, enterWatch = true)
                } else {
                    setMarket(Markets.other(config.settings.market), enterWatch = true)
                }
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
                if (isCloudActive()) cycleCloudScope() else cycleSort()
                return true
            }
            KeyEvent.KEYCODE_PROG_BLUE -> {
                if (showingHeatmap()) cycleHeatmapWeight() else cycleLayout()
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
        if (showingHeatmap()) {
            return handleHeatmapDpad(key)
        }
        return if (zoomCode != null) handleZoomDpad(key) else handleGridDpad(key)
    }

    private fun handleHeatmapDpad(key: Int): Boolean {
        if (headerFocused()) {
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                heatmapView.requestFocus()
                return true
            }
            return false
        }
        if (currentFocus !== heatmapView) {
            heatmapView.requestFocus()
        }
        val dir = when (key) {
            KeyEvent.KEYCODE_DPAD_UP -> NavDir.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> NavDir.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> NavDir.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> NavDir.RIGHT
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (isCloudActive() && heatmapView.selectedItem() == null && AppCore.heatmap.lastError != null) {
                    syncHeatmapPolling(forceRefresh = true)
                    toast("正在重试加载云图…")
                    return true
                }
                return false
            }
            else -> return false
        }
        if (heatmapView.moveFocus(dir)) return true
        if (dir == NavDir.UP && config.settings.showHeader) {
            focusActiveHeaderTab()
            return true
        }
        return true
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
        if (isCloudActive() && drillSectorId != null) {
            leaveSectorDrill()
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
