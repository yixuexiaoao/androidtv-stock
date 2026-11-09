package io.github.yixuexiaoao.tvstock.log

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 全局日志入口，同时输出到 logcat 和 LogStore（Web 后台可查看）。
 * 不要记录访问密码；持仓和备注也不写入日志。
 */
object AppLog {
    private const val TAG_PREFIX = "TvStock/"

    @Volatile private var store = LogStore(null)

    /** 详细日志开关：关闭时不记录 D 级。 */
    @Volatile var verbose = false

    fun init(context: Context) {
        store = LogStore(File(File(context.filesDir, "logs"), "app.log"))
    }

    fun d(tag: String, message: String) {
        if (!verbose) return
        Log.d(TAG_PREFIX + tag, message)
        store.add(System.currentTimeMillis(), "D", tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(TAG_PREFIX + tag, message)
        store.add(System.currentTimeMillis(), "I", tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        val text = withError(message, error, stack = false)
        Log.w(TAG_PREFIX + tag, text)
        store.add(System.currentTimeMillis(), "W", tag, text)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        val text = withError(message, error, stack = true)
        Log.e(TAG_PREFIX + tag, text)
        store.add(System.currentTimeMillis(), "E", tag, text)
    }

    private fun withError(message: String, error: Throwable?, stack: Boolean): String = when {
        error == null -> message
        stack -> message + "\n" + error.stackTraceToString()
        else -> "$message：${error.javaClass.simpleName}: ${error.message}"
    }

    fun query(minLevel: String?, keyword: String?, limit: Int): List<LogEntry> = store.query(minLevel, keyword, limit)

    fun clear() = store.clear()

    /** 记录未捕获异常后交回系统默认处理（系统会结束进程）。 */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            e("CRASH", "未捕获异常，线程 ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }
}
