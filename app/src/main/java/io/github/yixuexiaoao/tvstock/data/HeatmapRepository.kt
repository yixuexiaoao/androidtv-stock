package io.github.yixuexiaoao.tvstock.data

import io.github.yixuexiaoao.tvstock.log.AppLog
import io.github.yixuexiaoao.tvstock.model.CloudScopes
import io.github.yixuexiaoao.tvstock.model.HeatmapSnapshot
import io.github.yixuexiaoao.tvstock.model.HeatmapWeights
import io.github.yixuexiaoao.tvstock.model.Markets
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 大盘云图按需轮询仓库：
 * - 仅在用户停留在云图视图且界面可见时运行，切走或退到后台立即取消定时器；
 * - 最多缓存最近 6 份快照（LRU），避免内存累积；
 * - 盘中 20 秒、休市 120 秒刷新，连续失败指数退避至 60 秒。
 */
class HeatmapRepository(
    private val refreshSec: () -> Int,
    private val onUpdate: () -> Unit,
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "TvStock-Heatmap").apply { isDaemon = true }
    }
    private var pending: ScheduledFuture<*>? = null

    /** 有界 LRU 缓存，切回看过的板块或范围时零等待立即显示。 */
    private val cache = Collections.synchronizedMap(object : LinkedHashMap<String, HeatmapSnapshot>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HeatmapSnapshot>?): Boolean =
            size > MAX_CACHE
    })

    @Volatile var active = false
        private set
    @Volatile var loading = false
        private set
    @Volatile var lastError: String? = null
        private set

    @Volatile private var targetScope = CloudScopes.SECTORS
    @Volatile private var targetSectorId: String? = null
    @Volatile private var targetSectorName = ""
    @Volatile private var targetWeight = HeatmapWeights.CIRC_CAP
    private var consecutiveFailures = 0

    private fun cacheKey(scope: String, sectorId: String?, weight: String): String =
        "$scope|${sectorId.orEmpty()}|$weight"

    private fun effectiveWeight(weight: String): String =
        if (weight == HeatmapWeights.AMOUNT) HeatmapWeights.AMOUNT else HeatmapWeights.CIRC_CAP

    /** 当前目标对应的最新缓存快照（可能为 null 表示首次加载中）。 */
    fun current(): HeatmapSnapshot? = cache[cacheKey(targetScope, targetSectorId, targetWeight)]

    /** 启动或切换云图目标；若缓存尚新鲜则不立即打网，仅按剩余时间调度。 */
    @Synchronized
    fun activate(scope: String, sectorId: String?, sectorName: String, weight: String, forceRefresh: Boolean = false) {
        val w = effectiveWeight(weight)
        val changed = targetScope != scope || targetSectorId != sectorId || targetWeight != w
        targetScope = scope
        targetSectorId = sectorId
        targetSectorName = sectorName
        targetWeight = w
        active = true
        if (changed) {
            lastError = null
            consecutiveFailures = 0
        }
        val cached = current()
        val age = if (cached != null) System.currentTimeMillis() - cached.fetchedAtMs else Long.MAX_VALUE
        val delay = if (forceRefresh || cached == null || age >= intervalMs()) 0L else maxOf(1000L, intervalMs() - age)
        schedule(delay)
    }

    /** 离开云图或退到后台时调用：立刻取消定时任务，不留空转线程任务。 */
    @Synchronized
    fun deactivate() {
        active = false
        pending?.cancel(false)
        pending = null
    }

    @Synchronized
    private fun schedule(delayMs: Long) {
        pending?.cancel(false)
        if (!active) return
        pending = scheduler.schedule(::pollOnce, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun intervalMs(): Long {
        val now = System.currentTimeMillis()
        val baseSec = if (MarketClock.isTradingTime(now, Markets.CN)) {
            maxOf(MIN_TRADING_SEC, refreshSec() * 4)
        } else {
            IDLE_SEC
        }
        val sec = if (consecutiveFailures > 0) {
            maxOf(baseSec, minOf(60, 1 shl minOf(consecutiveFailures + 2, 6)))
        } else {
            baseSec
        }
        return sec * 1000L
    }

    private fun pollOnce() {
        if (!active) return
        val scope = targetScope
        val sectorId = targetSectorId
        val sectorName = targetSectorName
        val weight = targetWeight
        val key = cacheKey(scope, sectorId, weight)
        loading = true
        onUpdate()
        try {
            val started = System.currentTimeMillis()
            val snap = HeatmapApi.load(scope, sectorId, sectorName, weight)
            cache[key] = snap
            if (lastError != null) AppLog.i(TAG, "大盘云图恢复正常")
            lastError = null
            consecutiveFailures = 0
            AppLog.d(TAG, "云图 $key 获取 ${snap.items.size} 条，来源 ${snap.source}，耗时 ${System.currentTimeMillis() - started}ms")
        } catch (t: Throwable) {
            if (lastError == null) {
                if (t is java.io.IOException) AppLog.w(TAG, "大盘云图获取失败", t) else AppLog.e(TAG, "大盘云图异常", t)
            }
            consecutiveFailures++
            lastError = t.message ?: t.javaClass.simpleName
        } finally {
            loading = false
            onUpdate()
            if (active) schedule(intervalMs())
        }
    }

    companion object {
        private const val TAG = "Heatmap"
        private const val MAX_CACHE = 6
        private const val MIN_TRADING_SEC = 15
        private const val IDLE_SEC = 120
    }
}
