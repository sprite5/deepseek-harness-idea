package com.deepseek.harness.idea.runtime

import com.intellij.openapi.diagnostic.Logger
import java.net.HttpURLConnection
import java.net.URL

/**
 * dsh 0.1.2+ BrowserAuth 客户端适配器。
 *
 * dsh web 现在要求：
 * - 根 URL 带 `?token=<launchToken>`：换 cookie（Set-Cookie + 303 → `/`）
 * - 其他请求带该 cookie（Host/Origin authority-bound，HMAC 签名）
 *
 * 插件 Java 侧的进程内调用（健康检查、`workspace.create` 等）没有浏览器的自动 cookie 流程，
 * 这里手工完成一次 token → cookie 交换，并把 cookie 存起来供后续请求用。
 *
 * 每次 dsh 重启 token 都会变，所以 cookie 也得跟着重新拿；dwebAuth 是 per-process 实例。
 */
class DshBrowserAuth(private val baseUrl: String) {
    companion object {
        private val LOG = Logger.getInstance(DshBrowserAuth::class.java)
    }

    @Volatile private var cookie: String? = null

    /** 是否已完成 token → cookie 交换。 */
    val isReady: Boolean get() = cookie != null

    /**
     * 用 launchToken 换一次 cookie（GET `/?token=<token>` 不跟随 303）。
     * @return true 当 dsh 返回 303 + Set-Cookie；任何 401/异常都视为失败
     */
    fun authenticate(token: String): Boolean = try {
        val tokenUrl = "${baseUrl.trimEnd('/')}/?token=$token"
        val conn = URL(tokenUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.instanceFollowRedirects = false  // 关键：303 不跟随，否则 Java 会去 GET `/` 拿 401
        conn.requestMethod = "GET"
        val code = conn.responseCode
        if (code == 303) {
            // Set-Cookie 在 responseHeaders.get("Set-Cookie")（可能多个），取 cookie-name=value 对
            val setCookies = conn.getHeaderField("Set-Cookie")
            // 也可能每个 Set-Cookie 是单独的 key
            val allHeaders = conn.headerFields.filterKeys { it.equals("Set-Cookie", ignoreCase = true) }
            val allSetCookie = (listOf(setCookies).filterNotNull() + allHeaders.values.flatten())
                .joinToString(", ")
            // 提取首个 "name=value" 对；dsh BrowserAuth 只 Set-Cookie 一个 cookie
            val first = allSetCookie.split(",").map { it.trim() }.firstOrNull { it.contains("=") }
            cookie = first?.substringBefore(";")  // 去掉 Path=/; HttpOnly 等属性
            LOG.info("dsh BrowserAuth: got cookie (${cookie?.length ?: 0} chars)")
            cookie != null
        } else {
            LOG.warn("dsh BrowserAuth: unexpected code $code (expected 303)")
            false
        }.also { conn.disconnect() }
    } catch (e: Exception) {
        LOG.warn("dsh BrowserAuth: token exchange failed", e)
        false
    }

    /**
     * 带 cookie 做一次 GET/POST；用于健康检查（GET `/`）与 workspace.create（POST `/api/...`）。
     * 不跟随 303（健康检查不需要）。
     */
    fun open(path: String, method: String = "GET", jsonBody: String? = null): HttpURLConnection {
        val url = URL("${baseUrl.trimEnd('/')}$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 8000
        conn.instanceFollowRedirects = false
        conn.requestMethod = method
        cookie?.let { conn.setRequestProperty("Cookie", it) }
        if (jsonBody != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
        }
        return conn
    }

    /** cookie 当前值；外部 workspace 初始化器复用同 cookie。 */
    fun cookieValue(): String? = cookie
}