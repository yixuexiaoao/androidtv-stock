package io.github.yixuexiaoao.tvstock.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.yixuexiaoao.tvstock.AppCore
import io.github.yixuexiaoao.tvstock.BuildConfig
import io.github.yixuexiaoao.tvstock.model.AlertModes
import io.github.yixuexiaoao.tvstock.model.AppSettings
import io.github.yixuexiaoao.tvstock.model.DisplayModes
import io.github.yixuexiaoao.tvstock.model.Layouts
import io.github.yixuexiaoao.tvstock.model.StockCodes
import io.github.yixuexiaoao.tvstock.model.StockConfig
import io.github.yixuexiaoao.tvstock.model.Themes
import org.json.JSONArray
import org.json.JSONObject

/** 电视端设置：全部可用遥控器操作。左右键改值，确认键循环下一项或执行。 */
class SettingsActivity : Activity() {
    private class Row(
        val label: String,
        val value: () -> String,
        val onStep: ((Int) -> Unit)? = null,
        val onOk: (() -> Unit)? = null,
    )

    private lateinit var list: LinearLayout
    private val rows = ArrayList<Pair<TextView, Row>>()

    private val settings: AppSettings get() = AppCore.config.settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F1720"))
            isFillViewport = true
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 电视安全区：四周留白防止过扫描裁切
            setPadding(dp(56), dp(28), dp(56), dp(28))
        }
        scroll.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        rebuild(0)
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
            Row("＋ 添加股票", { "输入 6 位代码" }, onOk = ::addStockDialog)
        return listOf(
            "显示" to listOf(
                Row("画面布局", { settings.effectiveLayout().name }, { d -> put("layoutId", stepped(layoutIds, settings.layoutId, d)) }),
                choice("展示方式", DisplayModes.ALL, { settings.displayMode }, DisplayModes::label, "displayMode"),
                choice("K线周期", listOf("day", "week", "month"), { settings.klinePeriod }, { mapOf("day" to "日K", "week" to "周K", "month" to "月K")[it] ?: it }, "klinePeriod"),
                number("字体大小", listOf(50, 60, 70, 80, 90, 100, 110, 120, 130, 150, 175, 200, 250), { settings.fontScale }, "%", "fontScale"),
                choice("主题", Themes.ALL, { settings.theme }, Themes::label, "theme"),
                choice("涨跌颜色", listOf("RED_UP", "GREEN_UP"), { settings.colorScheme }, { if (it == "RED_UP") "红涨绿跌" else "绿涨红跌" }, "colorScheme"),
                number("窗口间距", listOf(0, 2, 4, 6, 8, 12, 16), { settings.gridGap }, "px", "gridGap"),
                number("屏幕边距（画面被裁切时调大）", listOf(0, 8, 16, 24, 32, 48, 64), { settings.screenMargin }, "dp", "screenMargin"),
                number("自动翻页", listOf(0, 5, 10, 15, 30, 60, 120, 300), { settings.pageIntervalSec }, "秒", "pageIntervalSec", "关闭"),
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
                toggle("防烧屏微移", { settings.burnInGuard }, "burnInGuard"),
            ),
            "刷新" to listOf(
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
                number("闪烁间隔", listOf(150, 250, 500, 750, 1000, 1500), { settings.flashSpeedMs }, "毫秒", "flashSpeedMs"),
                choice("闪烁颜色", COLORS.keys.toList(), { settings.flashColor }, { COLORS[it] ?: it }, "flashColor"),
                number("持续满足时重复", listOf(0, 1, 5, 10, 30, 60), { settings.alertRepeatMin }, "分钟", "alertRepeatMin", "只提醒一次"),
                toggle("仅交易时段提醒", { settings.alertTradingOnly }, "alertTradingOnly"),
                toggle("提醒时跳到对应页", { settings.alertJumpPage }, "alertJumpPage"),
                Row("测试提醒", { "确认键触发" }, onOk = {
                    AppCore.testAlert(null, null)
                    finish()
                }),
            ),
            "自选股（确认键管理）" to stockRows,
            "系统" to listOf(
                Row("Web 后台", { AppCore.webUrl.ifEmpty { "未启动" } }, onOk = ::showQr),
                toggle("开机自启", { settings.autostart }, "autostart"),
                toggle("退出后保持 Web 服务", { settings.keepServiceOnExit }, "keepServiceOnExit"),
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
            }.joinToString("  ").ifEmpty { "仅监控" }
        }, onOk = { stockMenu(stock) })
    }

    private fun rebuild(focusIndex: Int) {
        list.removeAllViews()
        rows.clear()
        list.addView(TextView(this).apply {
            text = "设置"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setPadding(0, 0, 0, dp(6))
        })
        list.addView(TextView(this).apply {
            text = "方向键上下选择，左右修改，确认键执行，返回键退出。完整设置（持仓、自定义布局等）请使用 Web 后台。"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, dp(6))
        })
        sections().forEach { (title, sectionRows) ->
            list.addView(TextView(this).apply {
                text = title
                setTextColor(Color.parseColor("#4FC3F7"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(dp(4), dp(14), 0, dp(4))
            })
            sectionRows.forEach { addRow(it) }
        }
        rows.getOrNull(focusIndex.coerceIn(0, rows.size - 1))?.first?.requestFocus()
    }

    private fun addRow(row: Row) {
        val view = TextView(this).apply {
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
        view.setTextColor(if (focused) Color.BLACK else Color.parseColor("#E2E8F0"))
        view.background = GradientDrawable().apply {
            setColor(if (focused) Color.parseColor("#4FC3F7") else Color.parseColor("#1A2430"))
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
        rows.forEach { (view, row) -> view.text = rowText(row) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            val row = rows.firstOrNull { it.first.isFocused }?.second
            if (row?.onStep != null) {
                row.onStep.invoke(if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                refreshTexts()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun currentIndex() = rows.indexOfFirst { it.first.isFocused }.coerceAtLeast(0)

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun addStockDialog() {
        val input = EditText(this).apply {
            hint = "如 600519、sz000001"
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
        val toggleLabel = if (stock.enabled) "停用" else "启用"
        val items = arrayOf("设置目标价与持仓", "上移", "下移", toggleLabel, "删除")
        dialog().setItems(items) { _, which ->
            // 列表可能已被 Web 后台修改，按代码重新定位
            val codes = AppCore.config.stocks.map { it.code }.toMutableList()
            val at = codes.indexOf(stock.code)
            when (which) {
                0 -> stockForm(stock)
                1 -> if (at > 0) {
                    codes.add(at - 1, codes.removeAt(at))
                    AppCore.reorder(codes)
                }
                2 -> if (at in 0 until codes.size - 1) {
                    codes.add(at + 1, codes.removeAt(at))
                    AppCore.reorder(codes)
                }
                3 -> AppCore.upsertStock(stock.copy(enabled = !stock.enabled))
                4 -> AppCore.deleteStock(stock.code)
            }
            if (which != 0) rebuild(currentIndex())
        }.show()
    }

    private fun stockForm(stock: StockConfig) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        fun field(hintText: String, value: Number?): EditText = EditText(this).apply {
            hint = hintText
            setText(value?.toString().orEmpty())
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            box.addView(this)
        }
        val high = field("目标价（≥ 提醒）", stock.targetHigh)
        val low = field("下破价（≤ 提醒）", stock.targetLow)
        val cost = field("成本价", stock.costPrice)
        val shares = field("持股数", stock.shares)
        dialog()
            .setTitle(StockCodes.shortCode(stock.code))
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                AppCore.upsertStock(
                    stock.copy(
                        targetHigh = high.text.toString().toDoubleOrNull()?.takeIf { it > 0 },
                        targetLow = low.text.toString().toDoubleOrNull()?.takeIf { it > 0 },
                        costPrice = cost.text.toString().toDoubleOrNull()?.takeIf { it > 0 },
                        shares = shares.text.toString().toDoubleOrNull()?.toLong()?.takeIf { it > 0 },
                    ),
                )
                rebuild(currentIndex())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showQr() {
        val url = AppCore.webUrl
        if (url.isEmpty()) return
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
        dialog().setView(box).setPositiveButton("关闭", null).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
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
