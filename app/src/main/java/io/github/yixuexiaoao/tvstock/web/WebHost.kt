package io.github.yixuexiaoao.tvstock.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.yixuexiaoao.tvstock.AppCore
import io.github.yixuexiaoao.tvstock.log.AppLog
import java.io.IOException
import java.util.concurrent.Executors

/** Web 后台随 App 界面启停：离开 App（退出、主页键、屏保）就关闭，不在后台常驻。只在主线程调用。 */
object WebHost : AppCore.Listener {
    private const val TAG = "Web"
    private const val PORT_TRIES = 50
    private const val SOCKET_TIMEOUT_MS = 15_000
    private const val ADDRESS_REFRESH_MS = 15_000L

    private lateinit var app: Context
    private var running = false
    private var server: WebServer? = null
    private var boundPort = 0
    private var pendingPort = 0
    private val handler = Handler(Looper.getMainLooper())
    private val lookup = Executors.newSingleThreadExecutor()

    /** 电视可能晚于 App 联网，或者 DHCP 更换地址，所以定时刷新后台地址。 */
    private val addressTick = object : Runnable {
        override fun run() {
            publishAddress()
            handler.postDelayed(this, ADDRESS_REFRESH_MS)
        }
    }

    fun start(context: Context) {
        if (running) return
        running = true
        app = context.applicationContext
        AppCore.addListener(this)
        restartServer()
        AppCore.repo.backgroundAllowed = true
        AppCore.repo.start()
        handler.postDelayed(addressTick, ADDRESS_REFRESH_MS)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(addressTick)
        AppCore.repo.backgroundAllowed = false
        AppCore.removeListener(this)
        server?.stop()
        server = null
        AppCore.webUrl = ""
        AppLog.i(TAG, "Web 服务已停止")
    }

    override fun onConfig() {
        if (AppCore.config.settings.webPort != pendingPort) restartServer()
    }

    private fun restartServer() {
        server?.stop()
        server = null
        val wanted = AppCore.config.settings.webPort
        pendingPort = wanted
        // 端口被占用时依次尝试后面的端口
        for (port in wanted until minOf(wanted + PORT_TRIES, 65536)) {
            try {
                val s = WebServer(app, AppCore, port)
                s.start(SOCKET_TIMEOUT_MS, true)
                server = s
                boundPort = port
                AppLog.i(TAG, "Web 服务已启动，端口 $port")
                break
            } catch (e: IOException) {
                AppLog.w(TAG, "端口 $port 不可用", e)
            }
        }
        publishAddress(force = true)
    }

    private fun publishAddress(force: Boolean = false) {
        lookup.execute {
            val ip = WebServer.lanAddress() ?: "127.0.0.1"
            handler.post {
                // 查询期间服务可能已经停止
                if (!running) return@post
                val url = if (server != null) "http://$ip:$boundPort" else ""
                if (!force && url == AppCore.webUrl) return@post
                AppLog.i(TAG, "后台地址：${url.ifEmpty { "无" }}")
                AppCore.webUrl = url
            }
        }
    }
}
