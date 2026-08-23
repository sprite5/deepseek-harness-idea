package com.deepseek.harness.idea.ui

/**
 * 运行日志"一键解释"消息组装（纯函数，无平台依赖，可单测）。
 *
 * 最终发送给 DSH 的用户问题 = 本地化解释指令 + 空行 + 选中日志正文；
 * 超 [MAX_BYTES] 截断并注明（口径与 `SendSelectionAction` 一致）。
 */
object ExplainLogComposer {

    const val MAX_BYTES = 64 * 1024

    private const val TRUNCATED_NOTE = "\n…(已截断，超出 64KB)"

    /**
     * 组装发送消息。
     * - [log] 为空白 → 返回空串（调用方应已通过菜单可见性/守卫过滤，此处兜底）；
     * - 日志字节数 > [MAX_BYTES] → 按字符截断（`MAX_BYTES / 4`，与选中代码发送口径一致）并追加截断注明；
     * - 否则 `prefix + "\n\n" + log`。
     */
    fun buildMessage(prefix: String, log: String): String {
        if (log.isBlank()) return ""
        val body = if (log.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            log.take(MAX_BYTES / 4).trimEnd() + TRUNCATED_NOTE
        } else {
            log
        }
        return "$prefix\n\n$body"
    }
}
