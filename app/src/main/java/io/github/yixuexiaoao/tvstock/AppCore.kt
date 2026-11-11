package io.github.yixuexiaoao.tvstock

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.yixuexiaoao.tvstock.alert.AlertEngine
import io.github.yixuexiaoao.tvstock.data.QuoteApi
import io.github.yixuexiaoao.tvstock.data.QuoteRepository
import io.github.yixuexiaoao.tvstock.log.AppLog
import io.github.yixuexiaoao.tvstock.log.LogEntry
import io.github.yixuexiaoao.tvstock.model.AlertEvent
import io.github.yixuexiaoao.tvstock.model.AlertModes
import io.github.yixuexiaoao.tvstock.model.AppConfig
import io.github.yixuexiaoao.tvstock.model.Markets
import io.github.yixuexiaoao.tvstock.model.Quote
import io.github.yixuexiaoao.tvstock.model.SearchHit
import io.github.yixuexiaoao.tvstock.model.StockCodes
import io.github.yixuexiaoao.tvstock.model.StockConfig
import io.github.yixuexiaoao.tvstock.model.TradeError
import io.github.yixuexiaoao.tvstock.model.Trades
import io.github.yixuexiaoao.tvstock.update.UpdateManager
import io.github.yixuexiaoao.tvstock.web.WebBackend
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** 进程级单例：配置、行情、提醒状态；界面和 Web 后台共用同一份数据。 */
object AppCore : WebBackend {
    interface Listener {
        fun onQuotes() {}
        fun onCharts() {}
        fun onConfig() {}
        fun onAlert(event: AlertEvent) {}
        fun onAlertsCleared() {}
        fun onUpdate() {}
    }

    private const val HISTORY_MAX = 200
    private const val CHARTS_COALESCE_MS = 150L
    private val main = Handler(Looper.getMainLooper())
    private val chartsChanged = Runnable { listeners.forEach { it.onCharts() } }
    private val io = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val engine = AlertEngine()
    private val history = ArrayDeque<AlertEvent>()
    private lateinit var configFile: File

    @Volatile var webUrl: String = ""
    /** 声音静音截止时间，只影响声音，不影响闪烁和横幅；进程重启后失效 */
    @Volatile private var muteUntilMs = 0L
    @Volatile private var cfg = AppConfig()
    lateinit var repo: QuoteRepository
        private set

    /** 当前正在提醒的事件（code -> 事件），仅主线程访问。 */
    val activeAlerts = LinkedHashMap<String, AlertEvent>()

    val config: AppConfig get() = cfg

    fun init(context: Context) {
        configFile = File(context.filesDir, "config.json")
        cfg = loadConfig()
        AppLog.verbose = cfg.settings.logVerbose
        repo = QuoteRepository(
            config = { cfg },
            onQuotes = ::handleQuotes,
            onCharts = {
                main.removeCallbacks(chartsChanged)
                main.postDelayed(chartsChanged, CHARTS_COALESCE_MS)
            },
        )
    }

    fun addListener(l: Listener) { listeners.addIfAbsent(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private fun loadConfig(): AppConfig = try {
        if (configFile.exists()) AppConfig.fromJson(JSONObject(configFile.readText())) else AppConfig()
    } catch (e: Exception) {
        AppLog.e("Config", "配置文件读取失败，使用默认配置", e)
        AppConfig()
    }

    private fun persist(next: AppConfig) {
        io.execute {
            try {
                val tmp = File(configFile.parentFile, "config.json.tmp")
                tmp.writeText(next.toJson().toString(2))
                if (!tmp.renameTo(configFile)) {
                    configFile.delete()
                    tmp.renameTo(configFile)
                }
            } catch (e: java.io.IOException) {
                AppLog.e("Config", "配置保存失败", e)
            }
        }
    }

    @Synchronized
    private fun commit(next: AppConfig) {
        cfg = next
        AppLog.verbose = next.settings.logVerbose
        persist(next)
        repo.wake()
        main.post { listeners.forEach { it.onConfig() } }
    }

    private fun handleQuotes(list: List<Quote>) {
        val now = System.currentTimeMillis()
        val current = cfg
        val byCode = current.stocks.associateBy { it.code }
        val events = list.flatMap { q ->
            byCode[q.code]?.let { engine.evaluate(it, q, current.settings, now) }.orEmpty()
        }
        main.post {
            listeners.forEach { it.onQuotes() }
            events.forEach(::raise)
        }
    }

    private fun raise(event: AlertEvent) {
        AppLog.i("Alert", "${event.kind} ${event.code}：${event.message}，方式 ${event.modes.joinToString()}")
        synchronized(history) {
            history.addFirst(event)
            while (history.size > HISTORY_MAX) history.removeLast()
        }
        activeAlerts[event.code] = event
        listeners.forEach { it.onAlert(event) }
    }

    /** 主线程：清除全部正在进行的提醒。 */
    fun notifyUpdate() {
        main.post { listeners.forEach { it.onUpdate() } }
    }

    fun clearAlerts() {
        if (activeAlerts.isNotEmpty()) AppLog.i("Alert", "提醒已关闭")
        activeAlerts.clear()
        listeners.forEach { it.onAlertsCleared() }
    }

    // ---- WebBackend（在 Web 线程调用）----

    override fun config() = cfg

    @Synchronized
    override fun updateSettings(json: JSONObject) {
        // 只记录字段名，避免把访问密码写进日志
        AppLog.i("Config", "修改设置：" + json.keys().asSequence().joinToString())
        val settings = cfg.settings.merged(json)
        // 第一次切到美股时放几只示例，否则首页全是空窗口
        val needsUsStocks = settings.market == Markets.US && cfg.stocks.none { StockCodes.isUs(it.code) }
        commit(cfg.copy(settings = settings, stocks = if (needsUsStocks) cfg.stocks + AppConfig.DEFAULT_US_STOCKS else cfg.stocks))
    }

    @Synchronized
    override fun upsertStock(stock: StockConfig) {
        AppLog.i("Config", "保存股票 ${stock.code}")
        val list = cfg.stocks.toMutableList()
        val index = list.indexOfFirst { it.code == stock.code }
        if (index >= 0) list[index] = stock else list += stock
        commit(cfg.copy(stocks = list))
    }

    @Synchronized
    override fun deleteStock(code: String) {
        AppLog.i("Config", "删除股票 $code")
        commit(cfg.copy(stocks = cfg.stocks.filterNot { it.code == code }))
    }

    @Synchronized
    override fun reorder(codes: List<String>) {
        val rank = codes.withIndex().associate { (i, c) -> c to i }
        commit(cfg.copy(stocks = cfg.stocks.sortedBy { rank[it.code] ?: Int.MAX_VALUE }))
    }

    @Synchronized
    override fun replaceConfig(next: AppConfig) {
        AppLog.i("Config", "导入配置，共 ${next.stocks.size} 只股票")
        commit(next)
    }

    override fun quotes(): Map<String, Quote> = repo.quotes

    override fun search(keyword: String): List<SearchHit> = QuoteApi.search(keyword)

    private fun requireStock(code: String): StockConfig =
        cfg.stocks.firstOrNull { it.code == code } ?: throw TradeError("自选中没有这只股票")

    private fun replaceStock(next: StockConfig) {
        commit(cfg.copy(stocks = cfg.stocks.map { if (it.code == next.code) next else it }))
    }

    /** 基于最新配置修改一只股票，避免覆盖 Web 后台同时做的改动。 */
    @Synchronized
    fun updateStock(code: String, change: (StockConfig) -> StockConfig) {
        // 弹窗打开期间股票可能已被 Web 后台删除，此时不需要保存
        val current = cfg.stocks.firstOrNull { it.code == code } ?: return
        replaceStock(change(current))
    }

    /** 本地记账，不产生真实交易。日志只记操作和代码，不记数量和价格。 */
    @Synchronized
    override fun trade(code: String, action: String, price: Double, shares: Long?) {
        replaceStock(Trades.apply(requireStock(code), action, price, shares, System.currentTimeMillis()))
        AppLog.i("Trade", "${Trades.label(action)}记账 $code")
    }

    @Synchronized
    override fun undoTrade(code: String) {
        replaceStock(Trades.undo(requireStock(code)))
        AppLog.i("Trade", "撤销记账 $code")
    }

    val isMuted: Boolean get() = System.currentTimeMillis() < muteUntilMs

    fun muteRemainingMin(): Long = maxOf(0, (muteUntilMs - System.currentTimeMillis() + 59_999) / 60_000)

    override fun muteSound(minutes: Int) {
        muteUntilMs = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0
        AppLog.i("Alert", if (minutes > 0) "提示音静音 $minutes 分钟" else "取消静音")
        main.post { listeners.forEach { it.onQuotes() } }
    }

    override fun testAlert(code: String?, modes: List<String>?, direction: String) {
        main.post {
            val settings = cfg.settings
            val target = code
                ?: cfg.stocks.firstOrNull { it.enabled && StockCodes.market(it.code) == settings.market }?.code
                ?: cfg.stocks.firstOrNull { it.enabled }?.code
                ?: return@post
            val quote = repo.quotes[target]
            val stock = cfg.stocks.firstOrNull { it.code == target }
            val name = stock?.alias?.takeIf { it.isNotBlank() } ?: quote?.name ?: target
            val useModes = modes ?: stock?.alertModes ?: settings.alertModes
            raise(engine.testEvent(target, name, useModes.ifEmpty { AlertModes.ALL }, System.currentTimeMillis(), quote?.displayPrice ?: 0.0, direction))
        }
    }

    override fun dismissAlerts() { main.post { clearAlerts() } }

    override fun alertHistory(): List<AlertEvent> = synchronized(history) { history.toList() }

    override fun logs(level: String?, keyword: String?, limit: Int): List<LogEntry> = AppLog.query(level, keyword, limit)

    override fun clearLogs() {
        AppLog.clear()
        AppLog.i("Log", "日志已清除")
    }

    override fun checkUpdate() = UpdateManager.check()

    override fun installUpdate() = UpdateManager.download()

    override fun status(): JSONObject = JSONObject().apply {
        put("version", BuildConfig.VERSION_NAME)
        put("webUrl", webUrl)
        put("lastSuccessMs", repo.lastSuccessMs)
        put("lastError", repo.lastError ?: JSONObject.NULL)
        put("foreground", repo.foreground)
        put("muteRemainingMin", muteRemainingMin())
        put("update", UpdateManager.statusJson())
    }
}
