package io.github.yixuexiaoao.tvstock.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import io.github.yixuexiaoao.tvstock.AppCore
import io.github.yixuexiaoao.tvstock.BuildConfig
import io.github.yixuexiaoao.tvstock.log.AppLog
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/** 从 GitHub Releases 检查、下载并调起系统安装器。所有状态变化通过 AppCore.notifyUpdate 通知界面。 */
object UpdateManager {
    enum class State { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, ERROR }

    private const val TAG = "Update"
    private const val API = "https://api.github.com/repos/yixuexiaoao/androidtv-stock/releases?per_page=30"
    private const val AUTO_INTERVAL_MS = 12 * 3600_000L

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var app: Context

    @Volatile var state = State.IDLE
        private set
    @Volatile var release: ReleaseInfo? = null
        private set
    @Volatile var progress = 0
        private set
    @Volatile var errorMessage = ""
        private set
    @Volatile private var lastCheckMs = 0L
    @Volatile private var installRequested = false

    fun init(context: Context) {
        app = context.applicationContext
        // 上次的更新包已经没用了（可能已安装）
        worker.execute { apkFile(app).delete() }
    }

    /**
     * Android 7 以下的安装器只接受 file://，需要放在安装器读得到的外部目录；
     * 新系统通过 ApkProvider 授权，放在私有缓存目录。
     */
    fun apkFile(context: Context): File {
        val dir = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            context.getExternalFilesDir(null) ?: context.cacheDir
        } else {
            context.cacheDir
        }
        return File(dir, "update.apk")
    }

    private fun includePrerelease(): Boolean = when (AppCore.config.settings.updateChannel) {
        "PRE" -> true
        "STABLE" -> false
        else -> Versions.isPrerelease(BuildConfig.VERSION_NAME)
    }

    private fun set(next: State) {
        state = next
        AppCore.notifyUpdate()
    }

    private fun fail(message: String, error: Throwable? = null) {
        errorMessage = message
        AppLog.w(TAG, message, error)
        set(State.ERROR)
    }

    fun autoCheckIfDue() {
        if (!AppCore.config.settings.updateAutoCheck) return
        if (state != State.IDLE && state != State.UP_TO_DATE && state != State.ERROR) return
        if (System.currentTimeMillis() - lastCheckMs < AUTO_INTERVAL_MS) return
        check()
    }

    fun check() {
        if (state == State.CHECKING || state == State.DOWNLOADING) return
        lastCheckMs = System.currentTimeMillis()
        set(State.CHECKING)
        worker.execute(::doCheck)
    }

    private fun doCheck() {
        try {
            val json = openGet(API).let { conn ->
                try {
                    conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                } finally {
                    conn.disconnect()
                }
            }
            val abis = Build.SUPPORTED_ABIS.toList()
            val newest = ReleaseParser.newest(ReleaseParser.parse(json, abis), includePrerelease())
            if (newest != null && Versions.compare(newest.version, BuildConfig.VERSION_NAME) > 0) {
                release = newest
                AppLog.i(TAG, "发现新版本 ${newest.version}（当前 ${BuildConfig.VERSION_NAME}），安装包 ${newest.apkName}")
                set(State.AVAILABLE)
            } else {
                release = null
                AppLog.i(TAG, "已是最新版本（当前 ${BuildConfig.VERSION_NAME}，服务器最新 ${newest?.version ?: "无"}）")
                set(State.UP_TO_DATE)
            }
        } catch (e: IOException) {
            fail("检查更新失败，请检查网络", e)
        } catch (e: org.json.JSONException) {
            fail("更新信息解析失败", e)
        }
    }

    /** 下载更新包，完成后在前台界面调起安装。 */
    fun download() {
        val target = release ?: return
        if (state == State.DOWNLOADING || state == State.CHECKING) return
        installRequested = true
        if (state == State.READY) {
            AppCore.notifyUpdate()
            return
        }
        progress = 0
        set(State.DOWNLOADING)
        worker.execute { doDownload(target) }
    }

    private fun doDownload(target: ReleaseInfo) {
        val file = apkFile(app)
        try {
            AppLog.i(TAG, "开始下载 ${target.apkName}（${target.apkSize} 字节）")
            file.parentFile?.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            val conn = openGet(target.apkUrl)
            try {
                val total = conn.contentLength.toLong().takeIf { it > 0 } ?: target.apkSize
                conn.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            done += n
                            val percent = if (total > 0) (done * 100 / total).toInt().coerceAtMost(100) else 0
                            if (percent != progress) {
                                progress = percent
                                AppCore.notifyUpdate()
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            verify(file, target, digest.digest())
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) file.setReadable(true, false)
            AppLog.i(TAG, "下载完成并校验通过：${target.version}")
            set(State.READY)
        } catch (e: IOException) {
            file.delete()
            installRequested = false
            fail(e.message?.takeIf { it.startsWith("校验") } ?: "下载失败，请检查网络", e)
        }
    }

    private fun verify(file: File, target: ReleaseInfo, sha256: ByteArray) {
        if (target.apkSize > 0 && file.length() != target.apkSize) {
            throw IOException("校验失败：文件大小不一致")
        }
        val hex = sha256.joinToString("") { "%02x".format(it) }
        if (target.sha256.isNotEmpty() && target.sha256 != hex) {
            throw IOException("校验失败：SHA-256 不一致")
        }
        val info = app.packageManager.getPackageArchiveInfo(file.path, 0)
        if (info?.packageName != app.packageName) {
            throw IOException("校验失败：不是本应用的安装包")
        }
    }

    /** 前台界面调用：如有等待中的安装请求且已下载完成，返回 true 并清除请求。 */
    fun consumeInstallRequest(): Boolean {
        if (state != State.READY || !installRequested) return false
        installRequested = false
        return true
    }

    fun install(activity: Activity) {
        val file = apkFile(activity)
        if (state != State.READY || !file.exists()) {
            set(if (release != null) State.AVAILABLE else State.IDLE)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            AppLog.w(TAG, "未授予安装未知应用权限，打开系统设置")
            toast(activity, "请允许本应用安装未知应用，然后再次选择“检查更新”进行安装")
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
                )
            } catch (e: ActivityNotFoundException) {
                AppLog.w(TAG, "系统没有未知应用设置页", e)
                toast(activity, "请在系统设置中手动允许，或用电脑通过 adb 安装：${release?.pageUrl.orEmpty()}")
            }
            return
        }
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Uri.parse("content://${activity.packageName}.apk/update.apk")
        } else {
            Uri.fromFile(file)
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, ApkProvider.MIME_APK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            AppLog.i(TAG, "调起系统安装器：${release?.version}")
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            AppLog.w(TAG, "系统没有安装器", e)
            toast(activity, "此设备无法直接安装，请到 Releases 页面下载：${release?.pageUrl.orEmpty()}")
        }
    }

    private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    private fun openGet(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "androidtv-stock/${BuildConfig.VERSION_NAME}")
        conn.setRequestProperty("Accept", "application/json, application/octet-stream")
        if (conn.responseCode !in 200..299) {
            val code = conn.responseCode
            conn.disconnect()
            throw IOException("HTTP $code")
        }
        return conn
    }

    /** 给界面显示的一句话状态。 */
    fun statusText(): String = when (state) {
        State.IDLE -> "当前 ${BuildConfig.VERSION_NAME}，按确认键检查"
        State.CHECKING -> "正在检查…"
        State.UP_TO_DATE -> "已是最新（${BuildConfig.VERSION_NAME}）"
        State.AVAILABLE -> "发现新版本 ${release?.version}，按确认键查看"
        State.DOWNLOADING -> "正在下载 $progress%"
        State.READY -> "已下载 ${release?.version}，按确认键安装"
        State.ERROR -> errorMessage
    }

    fun statusJson(): JSONObject = JSONObject().apply {
        put("state", state.name)
        put("text", statusText())
        put("progress", progress)
        put("current", BuildConfig.VERSION_NAME)
        release?.let { r ->
            put("release", JSONObject().apply {
                put("version", r.version)
                put("prerelease", r.prerelease)
                put("notes", r.notes)
                put("pageUrl", r.pageUrl)
                put("apkName", r.apkName)
            })
        }
    }
}
