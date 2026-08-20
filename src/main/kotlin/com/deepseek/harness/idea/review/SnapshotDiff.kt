package com.deepseek.harness.idea.review

/**
 * 基线 vs 当前盘面的差异判定（Step 4，见 docs/DESIGN.md §3.8）。
 *
 * 三类：
 * - MODIFIED：基线有、当前存在且内容（MD5）不同；
 * - NEW：基线无、当前存在；
 * - DELETED：基线有、当前不存在。
 */
object SnapshotDiff {

    enum class ChangeType { MODIFIED, NEW, DELETED }

    data class Change(
        val type: ChangeType,
        val relativePath: String,
        /** 基线内容 MD5（DELETED/MODIFIED 有值；NEW 为 null）。 */
        val baselineMd5: String?,
        /** 当前文件 MD5（MODIFIED/NEW 有值；DELETED 为 null）。 */
        val currentMd5: String?,
    )

    /**
     * 计算差异。
     *
     * @param baseline 基线条目（path → md5）
     * @param currentMd5 当前盘面（path → md5）；已按忽略规则过滤
     */
    fun diff(baseline: Map<String, String>, currentMd5: Map<String, String>): List<Change> {
        val changes = mutableListOf<Change>()
        // 基线中存在的路径
        for ((path, bMd5) in baseline) {
            val cMd5 = currentMd5[path]
            when {
                cMd5 == null -> changes.add(Change(ChangeType.DELETED, path, bMd5, null))
                cMd5 != bMd5 -> changes.add(Change(ChangeType.MODIFIED, path, bMd5, cMd5))
                // 相同 → 无变化
            }
        }
        // 当前新增的路径（基线没有）
        for ((path, cMd5) in currentMd5) {
            if (!baseline.containsKey(path)) {
                changes.add(Change(ChangeType.NEW, path, null, cMd5))
            }
        }
        return changes.sortedWith(compareBy({ it.type.ordinal }, { it.relativePath }))
    }
}
