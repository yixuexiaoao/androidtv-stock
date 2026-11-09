package io.github.yixuexiaoao.tvstock.update

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class ReleaseInfo(
    val version: String,
    val prerelease: Boolean,
    val notes: String,
    val pageUrl: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    /** 小写十六进制；GitHub 未提供时为空 */
    val sha256: String,
    /** GitHub API 的资源地址，需要 Accept: application/octet-stream */
    val apkApiUrl: String = "",
) {
    /**
     * 下载地址按优先级排列。github.com 的下载入口在部分网络下会超时或返回 504，
     * API 地址能绕过它直接跳转到文件服务器，所以排在前面。
     */
    fun downloadUrls(): List<String> = listOf(apkApiUrl, apkUrl).filter { it.isNotEmpty() }.distinct()
}

class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** 校验失败：message 就是给用户看的说明。 */
class VerifyException(message: String) : IOException(message)

/** 把网络异常转换成用户能看懂的原因。 */
fun networkErrorText(error: Throwable?): String = when (error) {
    is VerifyException -> error.message.orEmpty()
    is HttpStatusException -> when (error.code) {
        403, 429 -> "GitHub 限制了访问频率，请稍后再试"
        else -> "服务器返回 HTTP ${error.code}"
    }
    is UnknownHostException -> "无法解析 GitHub 域名，请检查网络或 DNS"
    is SocketTimeoutException -> "连接超时，GitHub 可能无法访问"
    is SSLException -> "安全连接失败，请检查电视的日期和时间"
    else -> "网络连接失败"
}

object Versions {
    private val TRAILING_NUMBER = Regex("(\\d+)$")

    private fun base(version: String) = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

    /** 比较版本号：0.2.0 > 0.2.0-pre.9 > 0.2.0-pre.8 > 0.1.9。允许带 v 前缀。 */
    fun compare(a: String, b: String): Int {
        val left = a.removePrefix("v")
        val right = b.removePrefix("v")
        val leftBase = base(left)
        val rightBase = base(right)
        for (i in 0 until maxOf(leftBase.size, rightBase.size)) {
            val diff = leftBase.getOrElse(i) { 0 }.compareTo(rightBase.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        val leftSuffix = left.substringAfter('-', "")
        val rightSuffix = right.substringAfter('-', "")
        return when {
            leftSuffix.isEmpty() && rightSuffix.isEmpty() -> 0
            leftSuffix.isEmpty() -> 1
            rightSuffix.isEmpty() -> -1
            else -> {
                val l = TRAILING_NUMBER.find(leftSuffix)?.value?.toLongOrNull()
                val r = TRAILING_NUMBER.find(rightSuffix)?.value?.toLongOrNull()
                if (l != null && r != null) l.compareTo(r) else leftSuffix.compareTo(rightSuffix)
            }
        }
    }

    fun isPrerelease(version: String) = version.contains('-')
}

/** 解析 GitHub Releases 接口响应（纯函数，便于测试）。 */
object ReleaseParser {
    fun parse(json: String, abis: List<String>): List<ReleaseInfo> {
        val arr = JSONArray(json)
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parseOne(it, abis) } }
    }

    private fun parseOne(release: JSONObject, abis: List<String>): ReleaseInfo? {
        if (release.optBoolean("draft", false)) return null
        val tag = release.optString("tag_name", "")
        if (!tag.startsWith("v")) return null
        val asset = chooseAsset(release.optJSONArray("assets") ?: return null, abis) ?: return null
        val digest = asset.optString("digest", "")
        return ReleaseInfo(
            version = tag.removePrefix("v"),
            prerelease = release.optBoolean("prerelease", false),
            notes = cleanNotes(release.optString("body", "")).take(MAX_NOTES),
            pageUrl = release.optString("html_url", ""),
            apkName = asset.optString("name", ""),
            apkUrl = asset.optString("browser_download_url", ""),
            apkSize = asset.optLong("size", 0),
            sha256 = if (digest.startsWith("sha256:")) digest.removePrefix("sha256:").lowercase() else "",
            apkApiUrl = asset.optString("url", ""),
        )
    }

    /** 更新说明是 Markdown，弹窗里显示纯文字；GitHub 自动生成的对比链接没有阅读价值。 */
    fun cleanNotes(body: String): String = body.lines()
        .map { it.trim() }
        .filterNot { it.startsWith("**Full Changelog") || it.startsWith("## ") }
        .map { line ->
            when {
                line.startsWith("### ") -> "【" + line.removePrefix("### ") + "】"
                line.startsWith("- ") -> "• " + line.removePrefix("- ")
                else -> line
            }.replace("**", "").replace("`", "")
        }
        .joinToString("\n")
        .trim()

    /** 优先选设备支持的架构，其次通用包。 */
    private fun chooseAsset(assets: JSONArray, abis: List<String>): JSONObject? {
        val apks = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .filter { it.optString("name", "").endsWith(".apk") }
        for (abi in abis) {
            apks.firstOrNull { it.optString("name").contains("-$abi-") }?.let { return it }
        }
        return apks.firstOrNull { it.optString("name").contains("-universal-") }
    }

    /** 返回允许范围内最新的版本。 */
    fun newest(releases: List<ReleaseInfo>, includePrerelease: Boolean): ReleaseInfo? = releases
        .filter { includePrerelease || !it.prerelease }
        .maxWithOrNull { a, b -> Versions.compare(a.version, b.version) }

    private const val MAX_NOTES = 2000
}
