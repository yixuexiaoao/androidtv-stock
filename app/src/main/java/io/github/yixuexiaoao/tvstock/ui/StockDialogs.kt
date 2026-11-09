package io.github.yixuexiaoao.tvstock.ui

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.yixuexiaoao.tvstock.AppCore
import io.github.yixuexiaoao.tvstock.data.MarketClock
import io.github.yixuexiaoao.tvstock.model.StockCodes
import io.github.yixuexiaoao.tvstock.model.StockConfig
import io.github.yixuexiaoao.tvstock.model.TradeError
import io.github.yixuexiaoao.tvstock.model.Trades

/** 电视端的持仓、提醒、记账弹窗，全部可用遥控器和屏幕键盘操作。 */
object StockDialogs {
    private fun stock(code: String): StockConfig? = AppCore.config.stocks.firstOrNull { it.code == code }

    private fun builder(activity: Activity) = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)

    private fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private fun column(activity: Activity) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(activity, 16)
        setPadding(pad, dp(activity, 8), pad, dp(activity, 8))
    }

    private fun LinearLayout.number(hintText: String, value: Any?, decimal: Boolean = true): EditText =
        EditText(context).apply {
            hint = hintText
            setText(value?.toString().orEmpty())
            inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
            this@number.addView(this)
        }

    private fun EditText.positive(): Double? = text.toString().trim().toDoubleOrNull()?.takeIf { it > 0 }

    private fun title(s: StockConfig) =
        (s.alias.ifBlank { AppCore.repo.quotes[s.code]?.name.orEmpty() } + " " + StockCodes.shortCode(s.code)).trim()

    /** 编辑：只设置持股数和成本价。 */
    fun editHolding(activity: Activity, code: String) {
        val s = stock(code) ?: return
        val box = column(activity)
        val cost = box.number("成本价", s.costPrice)
        val shares = box.number("持股数", s.shares, decimal = false)
        builder(activity)
            .setTitle("编辑持仓 " + title(s))
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                AppCore.updateStock(code) {
                    it.copy(costPrice = cost.positive(), shares = shares.positive()?.toLong())
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun alerts(activity: Activity, code: String) {
        val s = stock(code) ?: return
        val box = column(activity)
        val high = box.number("目标价（价格 ≥ 时提醒）", s.targetHigh)
        val low = box.number("下破价（价格 ≤ 时提醒）", s.targetLow)
        val up = box.number("涨幅提醒 %", s.pctUp)
        val down = box.number("跌幅提醒 %（填正数）", s.pctDown)
        val profit = box.number("持仓盈利提醒 %", s.profitPct)
        val loss = box.number("持仓亏损提醒 %（填正数）", s.lossPct)
        builder(activity)
            .setTitle("提醒设置 " + title(s))
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                AppCore.updateStock(code) {
                    it.copy(
                        targetHigh = high.positive(),
                        targetLow = low.positive(),
                        pctUp = up.positive(),
                        pctDown = down.positive(),
                        profitPct = profit.positive(),
                        lossPct = loss.positive(),
                    )
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 加仓、减仓、清仓记账，带实时预览。不会产生真实交易。 */
    fun trade(activity: Activity, code: String, action: String) {
        val s = stock(code) ?: return
        val quote = AppCore.repo.quotes[code]
        val box = column(activity)
        val decimals = quote?.decimals ?: 2
        val price = box.number("成交价", quote?.displayPrice?.takeIf { it > 0 }?.let { Format.price(it, decimals) })
        val shares = if (action == Trades.CLEAR) null else box.number("股数", null, decimal = false)
        val preview = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(activity, 10), 0, 0)
        }
        box.addView(preview)

        fun attempt(): StockConfig = Trades.apply(
            s, action, price.positive() ?: 0.0, shares?.positive()?.toLong(), System.currentTimeMillis(),
        )

        fun refresh() {
            preview.text = try {
                val next = attempt()
                val held = next.shares?.let { "持股 ${it} 股，成本 " + Format.price(next.costPrice ?: 0.0, decimals) } ?: "持仓清空"
                val profit = next.trades.last().profit
                held + (profit?.let { "\n本笔盈亏 " + Format.signedMoney(it) } ?: "")
            } catch (e: TradeError) {
                e.message
            }
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refresh()
        }
        price.addTextChangedListener(watcher)
        shares?.addTextChangedListener(watcher)
        refresh()

        builder(activity)
            .setTitle(Trades.label(action) + " " + title(s) + "（仅记账）")
            .setView(box)
            .setPositiveButton("确定") { _, _ ->
                try {
                    AppCore.trade(code, action, price.positive() ?: 0.0, shares?.positive()?.toLong())
                } catch (e: TradeError) {
                    Toast.makeText(activity, e.message, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun undoTrade(activity: Activity, code: String) {
        val s = stock(code) ?: return
        val last = s.trades.lastOrNull() ?: return
        builder(activity)
            .setMessage("撤销最近一笔：${Trades.label(last.action)} ${last.shares} 股 @ ${last.price}？")
            .setPositiveButton("撤销") { _, _ ->
                try {
                    AppCore.undoTrade(code)
                } catch (e: TradeError) {
                    Toast.makeText(activity, e.message, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 股票记账与提醒相关的菜单项（电视快捷菜单和设置页共用）。 */
    fun stockActions(activity: Activity, code: String): List<Pair<String, () -> Unit>> {
        val s = stock(code) ?: return emptyList()
        val holding = s.shares != null
        return buildList {
            add("提醒设置" to { alerts(activity, code) })
            add("编辑持仓（成本价、持股数）" to { editHolding(activity, code) })
            add("加仓" to { trade(activity, code, Trades.BUY) })
            if (holding) {
                add("减仓" to { trade(activity, code, Trades.SELL) })
                add("清仓" to { trade(activity, code, Trades.CLEAR) })
            }
            if (s.trades.isNotEmpty()) add("撤销上一笔记账" to { undoTrade(activity, code) })
        }
    }

    private fun qrBox(activity: Activity, url: String, caption: String): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val pad = dp(activity, 16)
        setPadding(pad, pad, pad, pad)
        if (url.isNotEmpty()) addView(ImageView(activity).apply { setImageBitmap(QrCode.create(url, dp(activity, 200))) })
        addView(TextView(activity).apply {
            text = caption
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        })
    }

    fun webQr(activity: Activity) {
        val url = AppCore.webUrl
        if (url.isEmpty()) {
            Toast.makeText(activity, "Web 后台未启动", Toast.LENGTH_SHORT).show()
            return
        }
        builder(activity)
            .setView(qrBox(activity, url, "手机扫码或在浏览器打开\n$url"))
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 遥控器帮助，首次启动时也会显示。 */
    fun help(activity: Activity) {
        val url = AppCore.webUrl
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = dp(activity, 16)
            setPadding(pad, 0, pad, 0)
            if (url.isNotEmpty()) {
                addView(ImageView(activity).apply { setImageBitmap(QrCode.create(url, dp(activity, 150))) })
            }
            addView(TextView(activity).apply {
                text = (if (url.isEmpty()) "" else "手机扫码打开后台：\n$url\n\n") + HELP_TEXT
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setLineSpacing(0f, 1.15f)
                setPadding(pad, 0, 0, 0)
            })
        }
        builder(activity)
            .setTitle("使用帮助")
            .setView(box)
            .setPositiveButton("知道了", null)
            .show()
    }

    fun recentAlerts(activity: Activity) {
        val list = AppCore.alertHistory().take(30)
        val items = if (list.isEmpty()) arrayOf("暂无提醒") else list.map { e ->
            MarketClock.timeString(e.timeMs).take(5) + "  " + e.message
        }.toTypedArray()
        builder(activity).setTitle("最近提醒").setItems(items, null).setPositiveButton("关闭", null).show()
    }

    private const val HELP_TEXT = "方向键：移动焦点，左右到边缘时翻页\n" +
        "确认键：放大或还原当前股票\n" +
        "长按确认键：快捷菜单（加仓、提醒等）\n" +
        "数字键：切换画面数，0 切换图文\n" +
        "频道键：翻页；菜单键：设置\n" +
        "红键静音，绿键隐私，黄键排序，蓝键布局"

    fun menu(activity: Activity, title: String, items: List<Pair<String, () -> Unit>>) {
        builder(activity)
            .setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }
}
