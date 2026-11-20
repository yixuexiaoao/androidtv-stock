package io.github.yixuexiaoao.tvstock.ui

import android.graphics.Color
import io.github.yixuexiaoao.tvstock.data.MarketClock
import io.github.yixuexiaoao.tvstock.model.AppSettings
import io.github.yixuexiaoao.tvstock.model.Quote
import io.github.yixuexiaoao.tvstock.model.StockCodes
import io.github.yixuexiaoao.tvstock.model.Themes
import kotlin.math.abs

data class Palette(
    val background: Int,
    val cell: Int,
    val border: Int,
    val text: Int,
    val subText: Int,
    val grid: Int,
    val up: Int,
    val down: Int,
    val flat: Int,
    val accent: Int,
    val avgLine: Int,
    val theme: String = Themes.DARK,
) {
    fun forChange(change: Double) = when {
        change > 0 -> up
        change < 0 -> down
        else -> flat
    }

    /**
     * 热力图连续色阶（参考大盘云图 -4% ~ 0 ~ +4% 映射），自动跟随红涨绿跌与主题明暗。
     * 平盘采用中性灰，涨跌幅越大饱和度与亮度越高。
     */
    fun heatColor(changePct: Double): Int {
        val neutral = when (theme) {
            Themes.LIGHT -> 0xFF94A3B8.toInt()
            Themes.NIGHT -> 0xFF282522.toInt()
            else -> 0xFF334155.toInt()
        }
        val mag = abs(changePct)
        if (mag < 0.005) return neutral
        val target = if (changePct > 0) up else down
        // 非线性平滑映射：小波动有辨识度，≥ 4% 达到强饱和，≥ 9.5%（涨跌停）进一步提亮
        val t = (mag / 4.0).coerceIn(0.0, 1.0)
        val blend = (0.28 + 0.62 * t).toFloat()
        val base = blendColor(neutral, target, blend)
        return when {
            theme == Themes.NIGHT -> dim(base, 0.78f)
            theme == Themes.LIGHT -> base
            mag >= 9.5 -> blendColor(base, 0xFFFFFFFF.toInt(), 0.14f)
            else -> dim(base, 0.82f)
        }
    }

    companion object {
        /** 当前实际生效的主题（AUTO 已按时间解析）。 */
        fun resolvedTheme(s: AppSettings, nowMs: Long = System.currentTimeMillis()): String =
            Themes.resolve(s.theme, MarketClock.minuteOfDay(nowMs), s.dayStart, s.nightStart)

        @Volatile private var cached: Pair<String, Palette>? = null

        /** 每次绑定窗口、移动焦点都会调用，结果按“主题 + 涨跌颜色”缓存。 */
        fun of(s: AppSettings, nowMs: Long = System.currentTimeMillis()): Palette {
            val theme = resolvedTheme(s, nowMs)
            val key = theme + "|" + s.colorScheme
            cached?.let { (k, p) -> if (k == key) return p }
            return build(theme, s.colorScheme).also { cached = key to it }
        }

        private fun build(theme: String, colorScheme: String): Palette {
            val red = Color.parseColor("#FF4D4F")
            val green = Color.parseColor("#22C55E")
            val up = if (colorScheme == "GREEN_UP") green else red
            val down = if (colorScheme == "GREEN_UP") red else green
            return when (theme) {
                // 夜晚：整体压低亮度，暖色调，减少夜间刺眼
                Themes.NIGHT -> Palette(c("#050403"), c("#0D0B09"), c("#1F1B17"), c("#A8A29E"), c("#6F6962"), c("#1C1916"), dim(up), dim(down), c("#857F78"), c("#B45309"), c("#8A6A2A"), theme)
                Themes.BLACK -> Palette(Color.BLACK, Color.BLACK, c("#262626"), Color.WHITE, c("#9CA3AF"), c("#1F1F1F"), up, down, c("#BDBDBD"), c("#4FC3F7"), c("#FFD54F"), theme)
                Themes.BLUE -> Palette(c("#071427"), c("#0D2140"), c("#1D3B66"), c("#F1F5F9"), c("#94A9C4"), c("#1A3357"), up, down, c("#B4C2D3"), c("#4FC3F7"), c("#FFD54F"), theme)
                Themes.LIGHT -> Palette(c("#E5E7EB"), c("#FFFFFF"), c("#CBD5E1"), c("#111827"), c("#6B7280"), c("#E5E7EB"), darken(up), darken(down), c("#6B7280"), c("#0284C7"), c("#B45309"), theme)
                else -> Palette(c("#0B0F14"), c("#141A22"), c("#232C38"), c("#F3F4F6"), c("#94A3B8"), c("#232C38"), up, down, c("#B0B8C4"), c("#4FC3F7"), c("#FFD54F"), Themes.DARK)
            }
        }

        private fun c(hex: String) = Color.parseColor(hex)

        private fun blendColor(a: Int, b: Int, ratio: Float): Int {
            val inv = 1f - ratio
            return Color.rgb(
                (Color.red(a) * inv + Color.red(b) * ratio).toInt().coerceIn(0, 255),
                (Color.green(a) * inv + Color.green(b) * ratio).toInt().coerceIn(0, 255),
                (Color.blue(a) * inv + Color.blue(b) * ratio).toInt().coerceIn(0, 255),
            )
        }

        private fun dim(color: Int, factor: Float = 0.68f): Int =
            Color.rgb((Color.red(color) * factor).toInt(), (Color.green(color) * factor).toInt(), (Color.blue(color) * factor).toInt())

        private fun darken(color: Int): Int =
            Color.rgb((Color.red(color) * 0.8).toInt(), (Color.green(color) * 0.75).toInt(), (Color.blue(color) * 0.8).toInt())
    }
}

object Format {
    fun price(value: Double, decimals: Int = 2): String = "%.${decimals}f".format(value)

    /** 开盘前开高低是 0，显示 -- 而不是 0.00。 */
    fun priceOrDash(value: Double, decimals: Int = 2): String = if (value > 0) price(value, decimals) else "--"

    fun signed(value: Double, decimals: Int = 2): String = (if (value > 0) "+" else "") + "%.${decimals}f".format(value)

    fun percent(value: Double): String = signed(value) + "%"

    /** 带涨跌符号的百分比。 */
    fun trendPercent(value: Double): String = (if (value > 0) "▲" else if (value < 0) "▼" else "") + percent(value)

    /** 把“万”为单位的数字转成合适单位。 */
    fun fromWan(wan: Double): String = when {
        abs(wan) >= 10000 -> "%.2f亿".format(wan / 10000)
        else -> "%.0f万".format(wan)
    }

    fun hands(hands: Double): String = when {
        hands >= 100_000_000 -> "%.2f亿手".format(hands / 100_000_000)
        hands >= 10_000 -> "%.2f万手".format(hands / 10_000)
        else -> "%.0f手".format(hands)
    }

    fun shares(shares: Double): String = when {
        shares >= 100_000_000 -> "%.2f亿股".format(shares / 100_000_000)
        shares >= 10_000 -> "%.2f万股".format(shares / 10_000)
        else -> "%.0f股".format(shares)
    }

    /** 成交量：A 股为手，美股为股。 */
    fun volume(q: Quote): String = if (StockCodes.isUs(q.code)) shares(q.volume) else hands(q.volume)

    /** 美股指数接口返回的成交额没有实际意义（道指显示十几万亿美元），不显示。 */
    fun amount(q: Quote): String = when {
        q.code.startsWith("us.") -> "--"
        StockCodes.isUs(q.code) -> "$" + fromWan(q.amountWan)
        else -> fromWan(q.amountWan)
    }

    /** usd 为 true 时带美元符号；A 股保持不带货币符号。 */
    fun money(value: Double, usd: Boolean = false): String {
        val a = abs(value)
        val text = when {
            a >= 100_000_000 -> "%.2f亿".format(a / 100_000_000)
            a >= 100_000 -> "%.2f万".format(a / 10_000)
            else -> "%.2f".format(a)
        }
        return (if (value < 0) "-" else "") + (if (usd) "$" else "") + text
    }

    fun signedMoney(value: Double, usd: Boolean = false): String = (if (value > 0) "+" else "") + money(value, usd)
}
