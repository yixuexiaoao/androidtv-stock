package io.github.yixuexiaoao.tvstock.web

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.yixuexiaoao.tvstock.AppCore
import io.github.yixuexiaoao.tvstock.R
import io.github.yixuexiaoao.tvstock.log.AppLog
import java.io.IOException

/** 前台服务：保活 Web 后台和行情轮询，退出界面后也可继续设置。 */
class WebService : Service(), AppCore.Listener {
    private var server: WebServer? = null
    private var boundPort = 0
    private var pendingPort = 0
    private val handler = Handler(Looper.getMainLooper())

    /** 电视可能晚于 App 联网，或者 DHCP 更换地址，所以定时刷新后台地址。 */
    private val addressTick = object : Runnable {
        override fun run() {
            publishAddress()
            handler.postDelayed(this, ADDRESS_REFRESH_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground("正在启动 Web 后台…")
        AppCore.addListener(this)
        restartServer()
        AppCore.repo.backgroundAllowed = true
        AppCore.repo.start()
        handler.postDelayed(addressTick, ADDRESS_REFRESH_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onConfig() {
        val wanted = AppCore.config.settings.webPort
        if (wanted != pendingPort) restartServer()
    }

    private fun restartServer() {
        server?.stop()
        server = null
        val wanted = AppCore.config.settings.webPort
        pendingPort = wanted
        // 端口被占用时依次尝试后面的端口
        for (port in wanted until wanted + PORT_TRIES) {
            try {
                val s = WebServer(this, AppCore, port)
                s.start(SOCKET_TIMEOUT_MS, true)
                server = s
                boundPort = port
                AppLog.i(TAG, "Web 服务已启动，端口 $port")
                break
            } catch (e: IOException) {
                AppLog.w(TAG, "端口 $port 不可用", e)
                continue
            }
        }
        publishAddress(force = true)
    }

    private fun publishAddress(force: Boolean = false) {
        val ip = WebServer.lanAddress() ?: "127.0.0.1"
        val url = if (server != null) "http://$ip:$boundPort" else ""
        if (!force && url == AppCore.webUrl) return
        AppLog.i(TAG, "后台地址：${url.ifEmpty { "无" }}")
        AppCore.webUrl = url
        goForeground(if (url.isEmpty()) "Web 后台启动失败：端口被占用" else "Web 后台：$url")
    }

    private fun goForeground(text: String) {
        val notification = buildNotification(text)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: RuntimeException) {
            // 部分定制系统不允许前台服务：Web 服务依然随界面运行
            AppLog.w(TAG, "无法进入前台服务", e)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Web 后台服务", NotificationManager.IMPORTANCE_LOW),
            )
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        AppLog.i(TAG, "Web 服务已停止")
        handler.removeCallbacks(addressTick)
        AppCore.repo.backgroundAllowed = false
        AppCore.removeListener(this)
        server?.stop()
        server = null
        AppCore.webUrl = ""
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Web"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "web_service"
        private const val PORT_TRIES = 50
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val ADDRESS_REFRESH_MS = 15_000L

        fun start(context: Context) {
            val intent = Intent(context, WebService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: RuntimeException) {
                // 后台启动受限（如开机广播）：下次打开 App 时会再次启动
                AppLog.w(TAG, "服务启动受限", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WebService::class.java))
        }
    }
}
