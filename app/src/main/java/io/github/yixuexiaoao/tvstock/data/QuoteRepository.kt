package io.github.yixuexiaoao.tvstock.data

import io.github.yixuexiaoao.tvstock.model.AppConfig
import io.github.yixuexiaoao.tvstock.model.KLine
import io.github.yixuexiaoao.tvstock.model.MinutePoint
import io.github.yixuexiaoao.tvstock.model.Quote
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 轮询行情并缓存。全部网络访问都在后台线程，结果通过回调通知。 */
class QuoteRepository(
    private val config: () -> AppConfig,
    private val onQuotes: (List<Quote>) -> Unit,
    private val onCharts: () -> Unit,
) {
    data class KLineSet(val period: String, val count: Int, val lines: List<KLine>)

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val chartPool = Executors.newFixedThreadPool(3)
    private val started = AtomicBoolean(false)
    private val wakePending = AtomicBoolean(false)
    private var pending: ScheduledFuture<*>? = null

    val quotes = ConcurrentHashMap<String, Quote>()
    val minutes = ConcurrentHashMap<String, List<MinutePoint>>()
    val klines = ConcurrentHashMap<String, KLineSet>()

    private val minuteFetchedAt = ConcurrentHashMap<String, Long>()
    private val klineFetchedAt = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    @Volatile var foreground = false
    /** Web 服务运行时允许后台轮询；服务和界面都不在时停止访问网络。 */
    @Volatile var backgroundAllowed = false
    @Volatile var lastSuccessMs = 0L
        private set
    @Volatile var lastError: String? = null
        private set

    /** 界面当前需要图表的股票（由界面设置）。 */
    @Volatile var wantMinute: Set<String> = emptySet()
    @Volatile var wantKline: Set<String> = emptySet()

    fun start() {
        if (started.compareAndSet(false, true)) schedule(0)
    }

    /** 配置变化后立刻刷新一次。 */
    fun wake() {
        wakePending.set(true)
        if (started.get()) schedule(0)
    }

    @Synchronized
    private fun schedule(delayMs: Long) {
        pending?.cancel(false)
        pending = scheduler.schedule(::loop, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun loop() {
        wakePending.set(false)
        try {
            if (foreground || backgroundAllowed) pollOnce()
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
        }
        schedule(if (wakePending.get()) 0 else nextDelayMs())
    }

    private fun nextDelayMs(): Long {
        val s = config().settings
        val trading = MarketClock.isTradingTime(System.currentTimeMillis())
        var sec = if (trading) s.refreshSec else maxOf(s.refreshSec, s.idleRefreshSec)
        if (!foreground) sec = maxOf(sec, 30)
        return sec * 1000L
    }

    private fun pollOnce() {
        val cfg = config()
        val codes = (cfg.stocks.filter { it.enabled }.map { it.code } + INDEX_CODES).distinct()
        val list = QuoteApi.quotes(codes, cfg.settings.source)
        list.forEach { quotes[it.code] = it }
        lastSuccessMs = System.currentTimeMillis()
        lastError = null
        onQuotes(list)
        fetchChartsIfDue(cfg)
    }

    private fun fetchChartsIfDue(cfg: AppConfig) {
        val now = System.currentTimeMillis()
        val minuteMaxAge = if (MarketClock.isTradingTime(now)) MINUTE_MAX_AGE_TRADING else MINUTE_MAX_AGE_IDLE
        wantMinute.forEach { code ->
            if (now - (minuteFetchedAt[code] ?: 0) >= minuteMaxAge) {
                runChart("m:$code") {
                    minutes[code] = QuoteApi.minute(code)
                    minuteFetchedAt[code] = System.currentTimeMillis()
                }
            }
        }
        val period = cfg.settings.klinePeriod
        val count = cfg.settings.klineCount
        wantKline.forEach { code ->
            val cached = klines[code]
            val stale = now - (klineFetchedAt[code] ?: 0) >= KLINE_MAX_AGE
            if (cached == null || cached.period != period || cached.count != count || stale) {
                runChart("k:$code") {
                    klines[code] = KLineSet(period, count, QuoteApi.kline(code, period, count))
                    klineFetchedAt[code] = System.currentTimeMillis()
                }
            }
        }
    }

    private fun runChart(key: String, block: () -> Unit) {
        if (!inFlight.add(key)) return
        chartPool.execute {
            try {
                block()
                onCharts()
            } catch (_: Exception) {
                // 图表失败不影响报价，下个周期重试
            } finally {
                inFlight.remove(key)
            }
        }
    }

    companion object {
        val INDEX_CODES = listOf("sh000001", "sz399001", "sz399006", "sh000300")
        private const val MINUTE_MAX_AGE_TRADING = 20_000L
        private const val MINUTE_MAX_AGE_IDLE = 600_000L
        private const val KLINE_MAX_AGE = 300_000L
    }
}
