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
    private const val RELEASES = "https://api.github.com/repos/yixuexiaoao/androidtv-stock/releases"
    private const val AUTO_INTERVAL_MS = 12 * 3600_000L
    private const val RELEASES_PAGE = "https://github.com/yixuexiaoao/androidtv-stock/releases"
    /** 授权通常几分钟内完成；隔太久再授权时，那时下载的版本可能已经过时，不自动装 */
    private const val PENDING_MAX_MS = 6 * 3600_000L
    private const val ACCEPT_JSON = "application/vnd.github+json"
    private const val ACCEPT_APK = "application/octet-stream"
    /** 所有地址都失败后再完整试一轮，应对 504 这类瞬时错误 */
    private const val DOWNLOAD_ROUNDS = 2
    private const val RETRY_DELAY_MS = 3_000L

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
    @Volatile private var promptAfterCheck = false

    fun init(context: Context) {
        app = context.applicationContext
        worker.execute(::restoreDownloadedApk)
    }

    private fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** 等待授权的标记：系统在“安装未知应用”权限变化时会杀掉本进程，内存里的状态都会丢。 */
    private fun pendingMarker(context: Context) = File(context.filesDir, "update.pending")

    /**
     * 只删已经装上（版本不比当前新）的安装包，更新的留着不用重新下载。
     * 第一次安装时系统会在权限变化时杀掉本进程；如果用户已经授权，重新打开 App 就接着安装。
     */
    @Suppress("DEPRECATION")
    private fun restoreDownloadedApk() {
        val marker = pendingMarker(app)
        val file = apkFile(app)
        val archive = if (file.exists()) app.packageManager.getPackageArchiveInfo(file.path, 0) else null
        if (archive == null || archive.packageName != app.packageName || archive.versionCode <= BuildConfig.VERSION_CODE) {
            file.delete()
            marker.delete()
            return
        }
        if (!marker.exists()) return
        if (System.currentTimeMillis() - marker.lastModified() > PENDING_MAX_MS) {
            marker.delete()
            return
        }
        if (!canInstallPackages(app)) return
        marker.delete()
        release = ReleaseInfo(archive.versionName.orEmpty(), false, "", RELEASES_PAGE, file.name, "", file.length(), "")
        installRequested = true
        AppLog.i(TAG, "已授权，继续安装 ${archive.versionName}")
        set(State.READY)
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
        check(prompt = true)
    }

    /** prompt：发现新版本时在电视上弹窗。Web 后台有自己的安装按钮，不需要弹。 */
    fun check(prompt: Boolean = false) {
        if (state == State.CHECKING || state == State.DOWNLOADING) return
        // 已恢复出待安装的包、正等界面调起安装器时（例如 Web 后台同时点了检查更新），不打断它
        if (state == State.READY && installRequested) return
        promptAfterCheck = prompt
        lastCheckMs = System.currentTimeMillis()
        set(State.CHECKING)
        worker.execute(::doCheck)
    }

    private fun readText(url: String): String {
        val conn = openGet(url, ACCEPT_JSON)
        try {
            return conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 每次推送都会新增预发布版，正式版可能排在很后面，所以只看正式版时直接查 latest。
     * latest 只指向正式版；还没有正式版时接口返回 404。
     */
    private fun fetchReleasesJson(includePre: Boolean): String {
        if (includePre) return readText("$RELEASES?per_page=30")
        return try {
            "[" + readText("$RELEASES/latest") + "]"
        } catch (e: HttpStatusException) {
            if (e.code == 404) "[]" else throw e
        }
    }

    private fun doCheck() {
        // 同一个后台线程上，启动时恢复的安装包可能排在这次检查前面执行：已经可以安装了，就不要用检查结果覆盖
        if (state == State.READY) {
            AppLog.i(TAG, "已有待安装的更新包，跳过本次检查")
            return
        }
        try {
            val includePre = includePrerelease()
            val abis = Build.SUPPORTED_ABIS.toList()
            val newest = ReleaseParser.newest(ReleaseParser.parse(fetchReleasesJson(includePre), abis), includePre)
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
            fail("检查更新失败：${networkErrorText(e)}", e)
        } catch (e: org.json.JSONException) {
            fail("更新信息解析失败", e)
        } catch (e: RuntimeException) {
            fail("检查更新出错，详见日志", e)
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
        AppLog.i(TAG, "开始下载 ${target.apkName}（${target.apkSize} 字节）")
        var lastError: Throwable? = null
        try {
            if (alreadyDownloaded(file, target)) {
                AppLog.i(TAG, "已有校验通过的安装包：${target.version}")
                set(State.READY)
                return
            }
            for (round in 1..DOWNLOAD_ROUNDS) {
                for (url in target.downloadUrls()) {
                    try {
                        downloadFrom(url, target, file)
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) file.setReadable(true, false)
                        AppLog.i(TAG, "下载完成并校验通过：${target.version}，来源 ${hostOf(url)}")
                        set(State.READY)
                        return
                    } catch (e: IOException) {
                        // 校验失败比网络错误更值得告诉用户，不被后面的网络错误覆盖
                        if (lastError !is VerifyException) lastError = e
                        file.delete()
                        AppLog.w(TAG, "从 ${hostOf(url)} 下载失败（第 $round 轮）：${networkErrorText(e)}", e)
                    }
                }
                if (round < DOWNLOAD_ROUNDS) Thread.sleep(RETRY_DELAY_MS)
            }
            installRequested = false
            fail("下载失败：${networkErrorText(lastError)}。可重试或到 Releases 页面手动下载")
        } catch (e: RuntimeException) {
            file.delete()
            installRequested = false
            fail("更新包处理出错，详见日志", e)
        }
    }

    private fun alreadyDownloaded(file: File, target: ReleaseInfo): Boolean {
        if (!file.exists() || target.sha256.isEmpty()) return false
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            verify(file, target, digest.digest())
            true
        } catch (e: IOException) {
            // 读不了或校验不过（VerifyException 也是 IOException）都当作没下载过
            false
        }
    }

    private fun hostOf(url: String): String = Uri.parse(url).host.orEmpty()

    private fun downloadFrom(url: String, target: ReleaseInfo, file: File) {
        file.parentFile?.mkdirs()
        progress = 0
        AppCore.notifyUpdate()
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = openGet(url, ACCEPT_APK)
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
    }

    private fun verify(file: File, target: ReleaseInfo, sha256: ByteArray) {
        if (target.apkSize > 0 && file.length() != target.apkSize) {
            throw VerifyException("校验失败：文件大小不一致")
        }
        val hex = sha256.joinToString("") { "%02x".format(it) }
        if (target.sha256.isNotEmpty() && target.sha256 != hex) {
            throw VerifyException("校验失败：SHA-256 不一致")
        }
        val info = app.packageManager.getPackageArchiveInfo(file.path, 0)
        if (info?.packageName != app.packageName) {
            throw VerifyException("校验失败：不是本应用的安装包")
        }
    }

    /** 前台界面调用：检查到新版本且需要弹窗时返回版本信息，只返回一次。 */
    fun consumePrompt(): ReleaseInfo? {
        if (state != State.AVAILABLE || !promptAfterCheck) return null
        promptAfterCheck = false
        return release
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
        // 不预先检查“安装未知应用”权限：没有权限时系统安装器会自己引导授权
        if (!canInstallPackages(activity)) {
            try {
                pendingMarker(activity).writeText(release?.version.orEmpty())
            } catch (e: IOException) {
                AppLog.w(TAG, "无法记录待安装状态", e)
            }
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
            openUnknownSourcesOrExplain(activity)
        }
    }

    /** 部分电视系统精简了安装器，这时才退回到授权页和手动安装的提示。 */
    private fun openUnknownSourcesOrExplain(activity: Activity) {
        val page = release?.pageUrl?.ifEmpty { null } ?: RELEASES_PAGE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
                )
                toast(activity, "请允许本应用安装未知应用，然后再次选择“检查更新”")
                return
            } catch (e: ActivityNotFoundException) {
                AppLog.w(TAG, "系统没有未知应用设置页", e)
            }
        }
        toast(activity, "此设备无法直接安装，请用 U 盘或 adb 安装：$page")
    }

    private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    private fun openGet(url: String, accept: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "androidtv-stock/${BuildConfig.VERSION_NAME}")
        conn.setRequestProperty("Accept", accept)
        if (conn.responseCode !in 200..299) {
            val code = conn.responseCode
            conn.disconnect()
            throw HttpStatusException(code)
        }
        return conn
    }

    /** 给界面显示的一句话状态。 */
    fun statusText(): String = when (state) {
        State.IDLE -> "当前 ${BuildConfig.VERSION_NAME}"
        State.CHECKING -> "正在检查…"
        State.UP_TO_DATE -> "已是最新（${BuildConfig.VERSION_NAME}）"
        State.AVAILABLE -> "发现新版本 ${release?.version}"
        State.DOWNLOADING -> "正在下载 $progress%"
        State.READY -> "已下载 ${release?.version}，可以安装"
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
