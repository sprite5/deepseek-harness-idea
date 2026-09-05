package com.deepseek.harness.idea.runtime

import java.nio.file.Files
import java.nio.file.Path

/**
 * 解析 dsh web 启动日志中的 URL 行。
 *
 * dsh 0.1.1-rc.2: `dsh web: http://127.0.0.1:<port>`（无 auth）
 * dsh 0.1.2-rc.1+: `dsh web: http://127.0.0.1:<port>/?token=<launchToken>`（BrowserAuth）
 *
 * 启动 token 是 dsh 0.1.2+ 强制要求的认证输入，没有 token 时
 * `/` 会被 dsh 拦截返回 401；插件也拿不到合法 cookie。
 * 所以必须把完整 URL（含 token）传给健康检查与浏览器加载。
 */
object PortParser {
    /** 匹配 dsh web: URL（含 ?token=） */
    private val URL_RE = Regex("""dsh web: (http://127\.0\.0\.1:\d+/\?token=[^\s'"]+)""")
    /** 兼容旧版（无 token）：仅端口 */
    private val PORT_RE = Regex("""dsh web: http://127\.0\.0\.1:(\d+)""")

    /** 解析完整 URL（含 token）；旧版无 token 时返回 `http://127.0.0.1:<port>/`。 */
    fun parseUrl(line: String): String? =
        URL_RE.find(line)?.groupValues?.get(1)
        ?: PORT_RE.find(line)?.groupValues?.get(1)?.let { "http://127.0.0.1:$it/" }

    fun parsePort(line: String): Int? =
        PORT_RE.find(line)?.groupValues?.get(1)?.toIntOrNull()
        ?: URL_RE.find(line)?.groupValues?.get(1)?.let { url ->
            // 从完整 URL 里抓端口
            Regex(""":(\d+)/""").find(url)?.groupValues?.get(1)?.toIntOrNull()
        }
}
