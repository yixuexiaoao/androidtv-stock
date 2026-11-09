package io.github.yixuexiaoao.tvstock.ui

import android.graphics.Color
import io.github.yixuexiaoao.tvstock.data.MarketClock
import io.github.yixuexiaoao.tvstock.model.AppSettings
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
) {
    fun forChange(change: Double) = when {
        change > 0 -> up
        change < 0 -> down
        else -> flat
    }

    companion object {
        /** 当前实际生效的主题（AUTO 已按时间解析）。 */
        fun resolvedTheme(s: AppSettings, nowMs: Long = System.currentTimeMillis()): String =
            Themes.resolve(s.theme, MarketClock.minuteOfDay(nowMs), s.dayStart, s.nightStart)

        fun of(s: AppSettings, nowMs: Long = System.currentTimeMillis()): Palette {
            val red = Color.parseColor("#FF4D4F")
            val green = Color.parseColor("#22C55E")
            val up = if (s.colorScheme == "GREEN_UP") green else red
            val down = if (s.colorScheme == "GREEN_UP") red else green
            return when (resolvedTheme(s, nowMs)) {
                // 夜晚：整体压低亮度，暖色调，减少夜间刺眼
                Themes.NIGHT -> Palette(c("#050403"), c("#0D0B09"), c("#1F1B17"), c("#A8A29E"), c("#6F6962"), c("#1C1916"), dim(up), dim(down), c("#857F78"), c("#B45309"), c("#8A6A2A"))
                Themes.BLACK -> Palette(Color.BLACK, Color.BLACK, c("#262626"), Color.WHITE, c("#9CA3AF"), c("#1F1F1F"), up, down, c("#BDBDBD"), c("#4FC3F7"), c("#FFD54F"))
                Themes.BLUE -> Palette(c("#071427"), c("#0D2140"), c("#1D3B66"), c("#F1F5F9"), c("#94A9C4"), c("#1A3357"), up, down, c("#B4C2D3"), c("#4FC3F7"), c("#FFD54F"))
                Themes.LIGHT -> Palette(c("#E5E7EB"), c("#FFFFFF"), c("#CBD5E1"), c("#111827"), c("#6B7280"), c("#E5E7EB"), darken(up), darken(down), c("#6B7280"), c("#0284C7"), c("#B45309"))
                else -> Palette(c("#0B0F14"), c("#141A22"), c("#232C38"), c("#F3F4F6"), c("#94A3B8"), c("#232C38"), up, down, c("#B0B8C4"), c("#4FC3F7"), c("#FFD54F"))
            }
        }

        private fun c(hex: String) = Color.parseColor(hex)

        private fun dim(color: Int): Int =
            Color.rgb((Color.red(color) * 0.68).toInt(), (Color.green(color) * 0.68).toInt(), (Color.blue(color) * 0.68).toInt())

        private fun darken(color: Int): Int =
            Color.rgb((Color.red(color) * 0.8).toInt(), (Color.green(color) * 0.75).toInt(), (Color.blue(color) * 0.8).toInt())
    }
}

object Format {
    fun price(value: Double, decimals: Int = 2): String = "%.${decimals}f".format(value)

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

    fun money(value: Double): String = when {
        abs(value) >= 100_000_000 -> "%.2f亿".format(value / 100_000_000)
        abs(value) >= 100_000 -> "%.2f万".format(value / 10_000)
        else -> "%.2f".format(value)
    }

    fun signedMoney(value: Double): String = (if (value > 0) "+" else "") + money(value)
}
