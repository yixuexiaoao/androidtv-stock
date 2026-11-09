package io.github.yixuexiaoao.tvstock.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.yixuexiaoao.tvstock.AppCore
import io.github.yixuexiaoao.tvstock.alert.SoundPresets
import io.github.yixuexiaoao.tvstock.BuildConfig
import io.github.yixuexiaoao.tvstock.model.AlertModes
import io.github.yixuexiaoao.tvstock.model.AppSettings
import io.github.yixuexiaoao.tvstock.model.DisplayModes
import io.github.yixuexiaoao.tvstock.model.Layouts
import io.github.yixuexiaoao.tvstock.model.Markets
import io.github.yixuexiaoao.tvstock.model.StockCodes
import io.github.yixuexiaoao.tvstock.model.StockConfig
import io.github.yixuexiaoao.tvstock.model.StockSort
import io.github.yixuexiaoao.tvstock.model.Themes
import io.github.yixuexiaoao.tvstock.update.UpdateManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 电视端设置：按功能分标签，全部可用遥控器操作。
 * 焦点在标签上时左右键切换标签、下键进入；在设置项上时左右键改值、确认键执行、频道键切换标签。
 */
class SettingsActivity : Activity(), AppCore.Listener {
    private class Row(
        val label: String,
        val value: () -> String,
        val onStep: ((Int) -> Unit)? = null,
        val onOk: (() -> Unit)? = null,
    )

    private lateinit var root: LinearLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private val tabs = ArrayList<TextView>()
    private var tabIndex = 0
    private var builtPalette: Palette? = null
    private var builtStocks: List<StockConfig>? = null
    private val palette: Palette get() = Palette.of(settings)
    private val rows = ArrayList<Pair<TextView, Row>>()

    private val settings: AppSettings get() = AppCore.config.settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 电视安全区：四周留白防止过扫描裁切
            setPadding(dp(56), dp(24), dp(56), dp(20))
        }
        tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scroll = ScrollView(this).apply { isFillViewport = true }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        rebuild(0)
        tabs[tabIndex].requestFocus()
    }

    override fun onStart() {
        super.onStart()
        AppCore.addListener(this)
        // 不在前台期间错过了通知，这里同步一次
        onConfig()
        onUpdate()
    }

    override fun onStop() {
        AppCore.removeListener(this)
        super.onStop()
    }

    override fun onConfig() {
        // 股票列表变化（含 Web 后台的修改）才需要重建，其他只刷新文字
        if (AppCore.config.stocks != builtStocks) rebuild(currentIndex()) else refreshTexts()
    }

    override fun onUpdate() {
        refreshTexts()
        if (UpdateManager.consumeInstallRequest()) UpdateManager.install(this)
    }

    private fun put(key: String, value: Any) = AppCore.updateSettings(JSONObject().put(key, value))

    private fun <T> stepped(values: List<T>, current: T, delta: Int): T {
        val index = values.indexOf(current).let { if (it < 0) 0 else it }
        return values[Math.floorMod(index + delta, values.size)]
    }

    /** Web 后台可能设置了不在候选列表里的值，这里走向相邻的候选值，到头后回绕。 */
    private fun steppedNumber(values: List<Int>, current: Int, delta: Int): Int =
        if (delta > 0) values.firstOrNull { it > current } ?: values.first()
        else values.lastOrNull { it < current } ?: values.last()

    private fun choice(label: String, values: List<String>, current: () -> String, name: (String) -> String, key: String): Row =
        Row(label, { name(current()) }, { d -> put(key, stepped(values, current(), d)) })

    private fun number(label: String, values: List<Int>, current: () -> Int, unit: String, key: String, zero: String? = null): Row =
        Row(label, { if (current() == 0 && zero != null) zero else "${current()}$unit" }, { d -> put(key, steppedNumber(values, current(), d)) })

    /** HH:mm 可按字符串比较；Web 后台可能设置了非整半点的时间，这里走向相邻的半点。 */
    private fun time(label: String, current: () -> String, key: String): Row = Row(label, current, { d ->
        val now = current()
        put(key, if (d > 0) TIMES.firstOrNull { it > now } ?: TIMES.first() else TIMES.lastOrNull { it < now } ?: TIMES.last())
    })

    /** 切换时试听一次。 */
    private fun sound(label: String, current: () -> String, key: String): Row = Row(label, { SoundPresets.label(current()) }, { d ->
        val next = stepped(SoundPresets.ALL, current(), d)
        put(key, next)
        SoundPlayer.play(next, settings.soundVolume)
    })

    private fun toggle(label: String, current: () -> Boolean, key: String): Row =
        Row(label, { if (current()) "开" else "关" }, { _ -> put(key, !current()) })

    private fun alertToggle(mode: String): Row = Row(
        "提醒方式：" + AlertModes.label(mode),
        { if (mode in settings.alertModes) "开" else "关" },
        { _ ->
            val next = if (mode in settings.alertModes) settings.alertModes - mode else settings.alertModes + mode
            put("alertModes", JSONArray(next))
        },
    )

    private fun sections(): List<Pair<String, List<Row>>> {
        val s = settings
        val layoutIds = Layouts.ALL.map { it.id } + (if (s.customLayout != null) listOf(Layouts.CUSTOM_ID) else emptyList())
        val stockRows = AppCore.config.stocks.map { stockRow(it) } +
            Row("＋ 添加股票", { "A 股代码或美股代码" }, onOk = ::addStockDialog)
        return listOf(
            "画面" to listOf(
                Row("画面布局", { settings.effectiveLayout().name }, { d -> put("layoutId", stepped(layoutIds, settings.layoutId, d)) }),
                choice("展示方式", DisplayModes.ALL, { settings.displayMode }, DisplayModes::label, "displayMode"),
                choice("K线周期", listOf("day", "week", "month"), { settings.klinePeriod }, { mapOf("day" to "日K", "week" to "周K", "month" to "月K")[it] ?: it }, "klinePeriod"),
                choice("排序", StockSort.ALL, { settings.sortMode }, StockSort::label, "sortMode"),
                number("窗口间距", listOf(0, 2, 4, 6, 8, 12, 16), { settings.gridGap }, "px", "gridGap"),
                number("屏幕边距（画面被裁切时调大）", listOf(0, 8, 16, 24, 32, 48, 64), { settings.screenMargin }, "dp", "screenMargin"),
                number("自动翻页", listOf(0, 5, 10, 15, 30, 60, 120, 300), { settings.pageIntervalSec }, "秒", "pageIntervalSec", "关闭"),
                choice("保持亮屏", listOf("TRADING", "ALWAYS", "NEVER"), { settings.keepScreen }, { mapOf("TRADING" to "当前市场盘中", "ALWAYS" to "一直", "NEVER" to "跟随系统")[it] ?: it }, "keepScreen"),
                toggle("防烧屏微移", { settings.burnInGuard }, "burnInGuard"),
            ),
            "外观" to listOf(
                number("字体大小", listOf(50, 60, 70, 80, 90, 100, 110, 120, 130, 150, 175, 200, 250), { settings.fontScale }, "%", "fontScale"),
                choice("主题", Themes.ALL, { settings.theme }, Themes::label, "theme"),
                time("白天开始（自动主题）", { settings.dayStart }, "dayStart"),
                time("夜晚开始（自动主题）", { settings.nightStart }, "nightStart"),
                choice("涨跌颜色", listOf("RED_UP", "GREEN_UP"), { settings.colorScheme }, { if (it == "RED_UP") "红涨绿跌" else "绿涨红跌" }, "colorScheme"),
                toggle("顶部状态栏", { settings.showHeader }, "showHeader"),
                toggle("大盘指数", { settings.showIndices }, "showIndices"),
                toggle("时钟", { settings.showClock }, "showClock"),
                toggle("持仓汇总", { settings.showPortfolio }, "showPortfolio"),
                toggle("底部滚动行情", { settings.showTicker }, "showTicker"),
                toggle("详细数据", { settings.showStats }, "showStats"),
                toggle("持仓与目标价", { settings.showHoldings }, "showHoldings"),
                toggle("图表标记线", { settings.showTargetLines }, "showTargetLines"),
                toggle("隐私模式（隐藏持仓金额）", { settings.privacy }, "privacy"),
                toggle("价格跳动闪烁", { settings.priceTickFlash }, "priceTickFlash"),
            ),
            "行情" to listOf(
                choice("首页市场", Markets.ALL, { settings.market }, Markets::label, "market"),
                number("交易时段刷新", listOf(1, 2, 3, 5, 10, 15, 30, 60, 120, 300), { settings.refreshSec }, "秒", "refreshSec"),
                number("非交易时段刷新", listOf(5, 30, 60, 300, 600, 1800, 3600), { settings.idleRefreshSec }, "秒", "idleRefreshSec"),
                choice("行情来源", listOf("AUTO", "TENCENT", "SINA"), { settings.source }, { mapOf("AUTO" to "自动", "TENCENT" to "腾讯", "SINA" to "新浪")[it] ?: it }, "source"),
            ),
            "提醒" to listOf(
                alertToggle(AlertModes.CELL),
                alertToggle(AlertModes.FULLSCREEN),
                alertToggle(AlertModes.BANNER),
                alertToggle(AlertModes.SOUND),
                number("提醒持续", listOf(0, 5, 10, 30, 60, 120, 300, 600), { settings.alertDurationSec }, "秒", "alertDurationSec", "直到手动关闭"),
                number("闪烁间隔", listOf(200, 250, 500, 750, 1000, 1500), { settings.flashSpeedMs }, "毫秒", "flashSpeedMs"),
                choice("上涨闪烁颜色", COLORS.keys.toList(), { settings.flashColor }, { COLORS[it] ?: it }, "flashColor"),
                choice("下跌闪烁颜色", COLORS.keys.toList(), { settings.flashColorDown }, { COLORS[it] ?: it }, "flashColorDown"),
                sound("上涨声音", { settings.soundUp }, "soundUp"),
                sound("下跌声音", { settings.soundDown }, "soundDown"),
                number("提示音音量", listOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100), { settings.soundVolume }, "%", "soundVolume"),
                number("提示音重复", listOf(1, 2, 3, 4, 5), { settings.soundRepeat }, "次", "soundRepeat"),
                Row("急涨急跌阈值", { if (settings.speedPct > 0) "${settings.speedPct}%" else "关闭" }, { d ->
                    put("speedPct", stepped(listOf(0.0, 0.5, 1.0, 1.5, 2.0, 3.0, 5.0), settings.speedPct, d))
                }),
                number("急涨急跌时间窗", listOf(1, 2, 3, 5, 10, 15, 30), { settings.speedWindowMin }, "分钟", "speedWindowMin"),
                toggle("涨停跌停提醒（仅 A 股）", { settings.limitAlert }, "limitAlert"),
                choice("提醒频率", listOf("EVERY", "DAILY"), { settings.alertFrequency }, { if (it == "DAILY") "每天一次" else "每次穿越" }, "alertFrequency"),
                number("再次提醒冷却", listOf(0, 1, 3, 5, 10, 30, 60), { settings.alertCooldownMin }, "分钟", "alertCooldownMin", "无"),
                number("持续满足时重复", listOf(0, 1, 5, 10, 30, 60), { settings.alertRepeatMin }, "分钟", "alertRepeatMin", "只提醒一次"),
                toggle("仅交易时段提醒", { settings.alertTradingOnly }, "alertTradingOnly"),
                toggle("提醒时跳到对应页", { settings.alertJumpPage }, "alertJumpPage"),
                Row("测试提醒", { "确认键触发" }, onOk = {
                    AppCore.testAlert(null, null)
                    finish()
                }),
            ),
            "自选股" to stockRows,
            "系统" to listOf(
                Row("使用帮助", { "遥控器按键说明" }, onOk = { StockDialogs.help(this) }),
                Row("最近提醒", { "查看" }, onOk = { StockDialogs.recentAlerts(this) }),
                Row("Web 后台", { AppCore.webUrl.ifEmpty { "未启动" } }, onOk = { StockDialogs.webQr(this) }),
                toggle("开机自启", { settings.autostart }, "autostart"),
                Row("检查更新", { UpdateManager.statusText() }, onOk = ::updateAction),
                choice("更新范围", listOf("AUTO", "STABLE", "PRE"), { settings.updateChannel }, { mapOf("AUTO" to "跟随当前版本", "STABLE" to "仅正式版", "PRE" to "包含预发布版")[it] ?: it }, "updateChannel"),
                toggle("自动检查更新", { settings.updateAutoCheck }, "updateAutoCheck"),
                toggle("详细日志（调试时打开）", { settings.logVerbose }, "logVerbose"),
                Row("版本", { BuildConfig.VERSION_NAME }),
            ),
        )
    }

    private fun stockRow(stock: StockConfig): Row {
        val name = stock.alias.ifBlank { AppCore.repo.quotes[stock.code]?.name ?: "" }
        return Row("$name ${StockCodes.shortCode(stock.code)}", {
            buildList {
                if (!stock.enabled) add("已停用")
                stock.targetHigh?.let { add("目标 $it") }
                stock.targetLow?.let { add("下破 $it") }
                stock.shares?.let { add("${it}股") }
                stock.costPrice?.let { add("成本 $it") }
            }.joinToString("  ").ifEmpty { "仅监控" }
        }, onOk = { stockMenu(stock) })
    }

    private fun updateAction() {
        when (UpdateManager.state) {
            UpdateManager.State.AVAILABLE -> {
                val r = UpdateManager.release ?: return
                dialog()
                    .setTitle("新版本 ${r.version}" + if (r.prerelease) "（预发布）" else "")
                    .setMessage(r.notes.ifBlank { "暂无更新说明" })
                    .setPositiveButton("下载并安装") { _, _ -> UpdateManager.download() }
                    .setNegativeButton("取消", null)
                    .show()
            }
            UpdateManager.State.READY -> UpdateManager.install(this)
            UpdateManager.State.CHECKING, UpdateManager.State.DOWNLOADING -> Unit
            else -> UpdateManager.check()
        }
    }

    private fun rebuild(focusIndex: Int) {
        val tabWasFocused = tabs.any { it.isFocused }
        builtPalette = palette
        builtStocks = AppCore.config.stocks
        root.setBackgroundColor(palette.background)
        root.removeAllViews()
        root.addView(TextView(this).apply {
            text = "设置"
            setTextColor(palette.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = "左右键切换标签，下键进入；设置项上左右键修改，确认键执行，频道键换标签，返回键退出。持仓、自定义布局等请使用 Web 后台。"
            setTextColor(palette.subText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, dp(8))
        })
        buildTabs()
        root.addView(tabBar)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })
        showRows()
        if (tabWasFocused) {
            tabs[tabIndex].requestFocus()
        } else {
            rows.getOrNull(focusIndex.coerceIn(0, rows.size - 1))?.first?.requestFocus()
        }
    }

    private fun buildTabs() {
        tabBar.removeAllViews()
        tabs.clear()
        sections().forEachIndexed { index, (title, _) ->
            val tab = TextView(this).apply {
                id = View.generateViewId()
                text = title
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(dp(22), dp(7), dp(22), dp(7))
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { _, hasFocus ->
                    // 电视上的习惯：焦点移到标签就显示内容，不用再按确认
                    if (hasFocus && index != tabIndex) {
                        tabIndex = index
                        showRows()
                    }
                    styleTabs()
                }
                setOnClickListener {
                    if (index != tabIndex) {
                        tabIndex = index
                        showRows()
                    }
                    styleTabs()
                    rows.firstOrNull()?.first?.requestFocus()
                }
            }
            tabs += tab
            tabBar.addView(tab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(6)
            })
        }
        styleTabs()
    }

    private fun styleTabs() {
        tabs.forEachIndexed { index, tab ->
            val active = index == tabIndex
            tab.setTextColor(
                when {
                    tab.isFocused -> palette.background
                    active -> palette.accent
                    else -> palette.subText
                },
            )
            tab.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tab.background = GradientDrawable().apply {
                setColor(
                    when {
                        tab.isFocused -> palette.accent
                        active -> palette.cell
                        else -> Color.TRANSPARENT
                    },
                )
                cornerRadius = dp(6).toFloat()
            }
        }
    }

    /** 显示当前标签的设置项。标签和第一项互相指定焦点方向，避免按上下键时落到相邻的标签。 */
    private fun showRows() {
        list.removeAllViews()
        rows.clear()
        sections()[tabIndex].second.forEach { addRow(it) }
        rows.firstOrNull()?.first?.let { first ->
            first.nextFocusUpId = tabs[tabIndex].id
            tabs.forEach { it.nextFocusDownId = first.id }
        }
        scroll.scrollTo(0, 0)
    }

    private fun addRow(row: Row) {
        val view = TextView(this).apply {
            id = View.generateViewId()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { _, _ -> style(this) }
            setOnClickListener { activate(row, 1) }
        }
        style(view)
        view.text = rowText(row)
        rows += view to row
        list.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(2)
        })
    }

    private fun rowText(row: Row): String = when {
        row.onStep != null -> "${row.label}    ◀ ${row.value()} ▶"
        else -> "${row.label}    ${row.value()}"
    }

    private fun style(view: TextView) {
        val focused = view.isFocused
        view.setTextColor(if (focused) palette.background else palette.text)
        view.background = GradientDrawable().apply {
            setColor(if (focused) palette.accent else palette.cell)
            cornerRadius = dp(6).toFloat()
        }
    }

    private fun activate(row: Row, delta: Int) {
        when {
            row.onOk != null -> row.onOk.invoke()
            row.onStep != null -> {
                row.onStep.invoke(delta)
                refreshTexts()
            }
        }
    }

    private fun refreshTexts() {
        if (palette != builtPalette) {
            rebuild(currentIndex())
            return
        }
        rows.forEach { (view, row) -> view.text = rowText(row) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            val row = rows.firstOrNull { it.first.isFocused }?.second
            if (row != null) {
                // 设置项上的左右键只改值，不让焦点跑到别处
                row.onStep?.invoke(if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                refreshTexts()
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val delta = when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> 1
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> -1
                else -> 0
            }
            if (delta != 0) {
                tabs[Math.floorMod(tabIndex + delta, tabs.size)].requestFocus()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun currentIndex() = rows.indexOfFirst { it.first.isFocused }.coerceAtLeast(0)

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun addStockDialog() {
        val input = EditText(this).apply {
            hint = "如 600519、sz000001、AAPL、.DJI"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        dialog()
            .setTitle("添加股票")
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                val code = StockCodes.normalize(input.text.toString())
                if (code == null) {
                    Toast.makeText(this, "代码无效", Toast.LENGTH_SHORT).show()
                } else {
                    if (AppCore.config.stocks.none { it.code == code }) AppCore.upsertStock(StockConfig(code))
                    rebuild(currentIndex())
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun stockMenu(stock: StockConfig) {
        val code = stock.code
        fun move(delta: Int) {
            val codes = AppCore.config.stocks.map { it.code }.toMutableList()
            val at = codes.indexOf(code)
            val target = at + delta
            if (at >= 0 && target in codes.indices) {
                codes.add(target, codes.removeAt(at))
                AppCore.reorder(codes)
            }
        }
        val items = StockDialogs.stockActions(this, code) + listOf(
            "上移" to { move(-1) },
            "下移" to { move(1) },
            (if (stock.enabled) "停用" else "启用") to { AppCore.updateStock(code) { it.copy(enabled = !it.enabled) } },
            "删除" to { AppCore.deleteStock(code) },
        )
        StockDialogs.menu(this, StockCodes.shortCode(code), items)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val TIMES = (0 until 48).map { "%02d:%02d".format(it / 2, it % 2 * 30) }
        val COLORS = linkedMapOf(
            "#FF1744" to "红色",
            "#FF9100" to "橙色",
            "#FFEA00" to "黄色",
            "#00E676" to "绿色",
            "#2979FF" to "蓝色",
            "#D500F9" to "紫色",
            "#FFFFFF" to "白色",
        )
    }
}
