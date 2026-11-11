package io.github.yixuexiaoao.tvstock.web

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import io.github.yixuexiaoao.tvstock.log.AppLog
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface

/** 基于 NanoHTTPD 的局域网 Web 后台：静态页面 + /api。 */
class WebServer(
    private val context: Context,
    backend: WebBackend,
    port: Int,
) : NanoHTTPD(port) {
    private val api = WebApi(backend)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val response = try {
            when {
                uri.startsWith("/api/") -> serveApi(session)
                uri == "/" || uri == "/index.html" -> serveIndex()
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        } catch (e: Exception) {
            AppLog.e("Web", "处理请求失败 ${session.method} $uri", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal Error")
        }
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader("X-Frame-Options", "DENY")
        response.addHeader("Referrer-Policy", "no-referrer")
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun serveIndex(): Response {
        val html = indexHtml ?: context.assets.open("web/index.html").use { it.readBytes() }.toString(Charsets.UTF_8)
            .also { indexHtml = it }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun serveApi(session: IHTTPSession): Response {
        val tooLarge = (session.headers["content-length"]?.toLongOrNull() ?: 0) > MAX_BODY
        if (tooLarge) return json(413, """{"ok":false,"error":"请求内容过大"}""")
        val body = if (session.method == Method.POST) readBody(session) else ""
        val result = api.handle(
            ApiRequest(
                method = session.method.name,
                path = session.uri,
                query = session.parms ?: emptyMap(),
                headers = session.headers,
                body = body,
            ),
        )
        // 轮询接口太频繁，不记录
        if (session.uri != "/api/state" && session.uri != "/api/logs") {
            AppLog.d("Web", "${session.method} ${session.uri} -> ${result.status}")
        }
        if (result.status >= 400) AppLog.w("Web", "${session.method} ${session.uri} -> ${result.status}")
        // 密码错误时稍作延迟，减缓局域网内暴力尝试
        if (result.status == 401 && session.headers.containsKey("x-pin")) Thread.sleep(800)
        return json(result.status, result.body)
    }

    private fun readBody(session: IHTTPSession): String {
        val length = session.headers["content-length"]?.toIntOrNull() ?: return ""
        val out = ByteArrayOutputStream(length)
        val buffer = ByteArray(8192)
        var remaining = length
        while (remaining > 0) {
            val n = session.inputStream.read(buffer, 0, minOf(buffer.size, remaining))
            if (n <= 0) break
            out.write(buffer, 0, n)
            remaining -= n
        }
        return out.toString("UTF-8")
    }

    private fun json(status: Int, body: String): Response {
        val code = Response.Status.lookup(status) ?: Response.Status.INTERNAL_ERROR
        return newFixedLengthResponse(code, "application/json; charset=utf-8", body)
    }

    companion object {
        private const val MAX_BODY = 1024 * 1024
        @Volatile private var indexHtml: String? = null

        /** 优先返回有线/无线局域网 IPv4 地址。 */
        fun lanAddress(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { n ->
                    when {
                        n.name.startsWith("eth") -> 0
                        n.name.startsWith("wlan") -> 1
                        else -> 2
                    }
                }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}
