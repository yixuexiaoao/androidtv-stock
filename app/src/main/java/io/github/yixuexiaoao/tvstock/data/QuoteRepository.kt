package io.github.yixuexiaoao.tvstock.data

import io.github.yixuexiaoao.tvstock.log.AppLog
import io.github.yixuexiaoao.tvstock.model.AppConfig
import io.github.yixuexiaoao.tvstock.model.KLine
import io.github.yixuexiaoao.tvstock.model.Markets
import io.github.yixuexiaoao.tvstock.model.MinutePoint
import io.github.yixuexiaoao.tvstock.model.Quote
import io.github.yixuexiaoao.tvstock.model.StockCodes
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
    private var lastMissing: Set<String> = emptySet()
    private val chartRetryAt = ConcurrentHashMap<String, Long>()
    @Volatile private var lastFullPollMs = 0L
    private var consecutiveFailures = 0
    private val chartErrorLoggedAt = ConcurrentHashMap<String, Long>()

    @Volatile var foreground = false
    /** Web 服务运行时（如正在设置页）低频轮询；离开 App 后服务关闭，不再访问行情接口。 */
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
        lastFullPollMs = 0
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
        // 离开 App 后不再定时唤醒，回到前台时 wake() 会重新开始
        if (!foreground && !backgroundAllowed) return
        try {
            pollOnce()
        } catch (t: Throwable) {
            // 只在状态变化时记录，断网期间不刷屏
            if (lastError == null) {
                // 网络问题记警告；其他异常是代码缺陷，要带堆栈才能定位
                if (t is java.io.IOException) AppLog.w(TAG, "行情获取失败", t) else AppLog.e(TAG, "行情处理异常", t)
            }
            consecutiveFailures++
            lastError = t.message ?: t.javaClass.simpleName
        }
        schedule(if (wakePending.get()) 0 else nextDelayMs())
    }

    private fun nextDelayMs(): Long {
        val s = config().settings
        val now = System.currentTimeMillis()
        val trading = watchedMarkets(config()).any { MarketClock.isTradingTime(now, it) }
        var sec = if (trading) s.refreshSec else maxOf(s.refreshSec, s.idleRefreshSec)
        if (!foreground) sec = maxOf(sec, 30)
        // 连续失败时逐步放慢（最多 60 秒），断网期间不空转，也减轻接口压力
        if (consecutiveFailures > 0) sec = maxOf(sec, minOf(60, 1 shl minOf(consecutiveFailures, 6)))
        return sec * 1000L
    }

    /** 决定刷新频率的市场：正在看的，以及设了提醒、需要及时判断的。 */
    private fun watchedMarkets(cfg: AppConfig): Set<String> {
        val everyStock = cfg.settings.speedPct > 0
        return cfg.stocks.filter { it.enabled && (everyStock || it.hasAlertRule) }
            .map { StockCodes.market(it.code) }.toSet() + cfg.settings.market
    }

    private fun pollOnce() {
        val cfg = config()
        val started = System.currentTimeMillis()
        val stocks = cfg.stocks.filter { it.enabled }.map { it.code }
        // 没在看又休市的市场，行情不会变，按非交易时段的间隔刷新一次就够了
        val fullDue = started - lastFullPollMs >= maxOf(cfg.settings.idleRefreshSec, FULL_POLL_MIN_SEC) * 1000L
        if (fullDue) lastFullPollMs = started
        val markets = (stocks.map(StockCodes::market).toSet() + cfg.settings.market).filter {
            fullDue || it == cfg.settings.market || MarketClock.isTradingTime(started, it)
        }
        val codes = (stocks.filter { StockCodes.market(it) in markets } + markets.flatMap { indexCodes(it) }).distinct()
        val list = QuoteApi.quotes(codes, cfg.settings.source)
        list.forEach { quotes[it.code] = it }
        lastSuccessMs = System.currentTimeMillis()
        if (lastError != null) AppLog.i(TAG, "行情恢复正常")
        consecutiveFailures = 0
        lastError = null
        AppLog.d(TAG, "请求 ${codes.size} 只，返回 ${list.size} 只，耗时 ${lastSuccessMs - started}ms")
        val returned = list.map { it.code }.toSet()
        val missing = codes.toSet() - returned
        val fresh = missing - lastMissing
        if (fresh.isNotEmpty()) AppLog.w(TAG, "这些代码没有返回行情：" + fresh.joinToString())
        lastMissing = lastMissing - returned + missing
        onQuotes(list)
        fetchChartsIfDue(cfg)
    }

    private fun fetchChartsIfDue(cfg: AppConfig) {
        val now = System.currentTimeMillis()
        wantMinute.forEach { code ->
            val trading = MarketClock.isTradingTime(now, StockCodes.market(code))
            val minuteMaxAge = if (trading) MINUTE_MAX_AGE_TRADING else CHART_MAX_AGE_IDLE
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
            // 美股的交易所后缀来自腾讯报价；新浪备用时没有，等腾讯恢复后再取
            val exchange = quotes[code]?.exchange.orEmpty()
            if (StockCodes.isUs(code) && exchange.isEmpty()) return@forEach
            val cached = klines[code]
            val klineMaxAge = if (MarketClock.isTradingTime(now, StockCodes.market(code))) KLINE_MAX_AGE_TRADING else CHART_MAX_AGE_IDLE
            val stale = now - (klineFetchedAt[code] ?: 0) >= klineMaxAge
            if (cached == null || cached.period != period || cached.count != count || stale) {
                runChart("k:$code") {
                    klines[code] = KLineSet(period, count, QuoteApi.kline(code, exchange, period, count))
                    klineFetchedAt[code] = System.currentTimeMillis()
                }
            }
        }
    }

    private fun runChart(key: String, block: () -> Unit) {
        if (System.currentTimeMillis() < (chartRetryAt[key] ?: 0)) return
        if (!inFlight.add(key)) return
        chartPool.execute {
            try {
                block()
                onCharts()
            } catch (e: Exception) {
                // 图表失败不影响报价，下个周期重试；同一张图每分钟最多记录一次
                val now = System.currentTimeMillis()
                // 失败后等一会儿再试，不跟着每轮行情刷新反复请求
                chartRetryAt[key] = now + CHART_RETRY_MS
                if (now - (chartErrorLoggedAt[key] ?: 0) >= 60_000) {
                    chartErrorLoggedAt[key] = now
                    AppLog.w(TAG, "图表获取失败 $key", e)
                }
            } finally {
                inFlight.remove(key)
            }
        }
    }

    companion object {
        private const val TAG = "Quote"
        private val CN_INDEX_CODES = listOf("sh000001", "sz399001", "sz399006", "sh000300")
        private val US_INDEX_CODES = listOf("us.DJI", "us.IXIC", "us.INX")

        /** 第一个用来判断当天是否休市。 */
        fun indexCodes(market: String): List<String> = if (market == Markets.US) US_INDEX_CODES else CN_INDEX_CODES
        private const val MINUTE_MAX_AGE_TRADING = 20_000L
        private const val KLINE_MAX_AGE_TRADING = 300_000L
        /** 休市时分时和 K 线都不会再变，只为防止漏掉收盘最后几分钟而偶尔刷新 */
        private const val CHART_MAX_AGE_IDLE = 900_000L
        private const val CHART_RETRY_MS = 30_000L
        private const val FULL_POLL_MIN_SEC = 30
    }
}
