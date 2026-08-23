package com.deepseek.harness.idea.runtime

import java.nio.file.Files
import java.nio.file.Path

/**
 * 解析 dsh web 启动日志中的端口行：`dsh web: http://127.0.0.1:<port>`。
 */
object PortParser {
    private val RE = Regex("""dsh web: http://127\.0\.0\.1:(\d+)""")

    fun parsePort(line: String): Int? =
        RE.find(line)?.groupValues?.get(1)?.toIntOrNull()
}
