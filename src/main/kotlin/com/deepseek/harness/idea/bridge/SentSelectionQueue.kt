package com.deepseek.harness.idea.bridge

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * sent-selection 环形队列（Step 4，见 docs/DESIGN.md §3.7/§4.1）。
 *
 * - 容量 ≤10 条，超出丢最旧；
 * - 单条 ≤64KB，超出截断并注明；
 * - 线程安全（ConcurrentLinkedDeque + 原子序号）。
 */
class SentSelectionQueue(
    private val maxEntries: Int = 10,
    private val maxBytes: Int = 64 * 1024,
) {

    data class Item(
        val id: String,
        val filePath: String?,
        val language: String?,
        val selection: String,
        val lineStart: Int,
        val lineEnd: Int,
        val ts: Long,
    )

    private val queue = ConcurrentLinkedDeque<Item>()
    private var seq = 0L

    /** 推送一条；返回 id。 */
    fun push(
        filePath: String?,
        language: String?,
        selection: String,
        lineStart: Int = 0,
        lineEnd: Int = 0,
        ts: Long = System.currentTimeMillis(),
    ): String {
        val id = "s${++seq}"
        val capped = if (selection.toByteArray(StandardCharsets.UTF_8).size > maxBytes) {
            selection.take(maxBytes / 4) + "\n…(已截断，超出 64KB)"
        } else selection
        queue.addLast(Item(id, filePath, language, capped, lineStart, lineEnd, ts))
        while (queue.size > maxEntries) queue.pollFirst()
        return id
    }

    /** 最近一条；空队列返回 null。 */
    fun latest(): Item? = queue.peekLast()

    fun size(): Int = queue.size
}
