package com.deepseek.harness.idea.review

import com.deepseek.harness.idea.util.PathFilters
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 审查编排（Step 4，见 docs/DESIGN.md §3.8）。
 *
 * - [refreshChanges]：先 `VfsUtil.markDirtyAndRefresh` 再对比基线，返回三类差异；
 * - [showDiff]：用 `DiffManager` 打开"基线内容 vs 当前"逐文件 diff；
 * - [restoreFile]/[restoreAll]：用基线内容覆盖当前文件（VFS 写 + 刷新）；
 * - [ignoreChange]：丢弃该文件的基线（"接受改动"）；[rebuildBaseline]：重新基线。
 */
class ReviewManager(
    private val project: Project,
    private val snapshot: SnapshotManager,
) {

    private val diffs: MutableMap<String, DiffRequestPanel> = mutableMapOf()

    /** 刷新 VFS 并计算差异。 */
    fun refreshChanges(): List<SnapshotDiff.Change> {
        project.basePath?.let { base ->
            LocalFileSystem.getInstance().findFileByPath(base)?.let { root ->
                VfsUtil.markDirtyAndRefresh(false, false, true, root)
            }
        }
        val baseline = snapshot.entries().mapValues { it.value.md5 }
        val current = currentMd5Map()
        return SnapshotDiff.diff(baseline, current)
    }

    /** 打开 DiffManager 面板（基线 vs 当前）。 */
    fun showDiff(change: SnapshotDiff.Change) {
        val basePath = project.basePath ?: return
        val currentVf = LocalFileSystem.getInstance().findFileByPath("$basePath/${change.relativePath}")
        val baselineBytes = snapshot.entryBytesFor(change.relativePath)
        val baselineText = baselineBytes?.toString(StandardCharsets.UTF_8) ?: ""
        val factory = DiffContentFactory.getInstance()
        val title = when (change.type) {
            SnapshotDiff.ChangeType.MODIFIED -> "Modified: ${change.relativePath}"
            SnapshotDiff.ChangeType.NEW -> "New: ${change.relativePath}"
            SnapshotDiff.ChangeType.DELETED -> "Deleted: ${change.relativePath}"
        }
        val left: com.intellij.diff.contents.DiffContent = when (change.type) {
            SnapshotDiff.ChangeType.DELETED -> factory.createEmpty()
            else -> factory.create(baselineText)
        }
        val right: com.intellij.diff.contents.DiffContent = when (change.type) {
            SnapshotDiff.ChangeType.NEW -> factory.createEmpty()
            else -> currentVf?.let { factory.create(project, it) } ?: factory.createEmpty()
        }
        val request = SimpleDiffRequest(title, left, right, "Baseline (${change.baselineMd5?.take(8) ?: "—"})", "Current")
        DiffManager.getInstance().showDiff(project, request)
    }

    /** 还原单个文件（用基线内容覆盖当前）。 */
    fun restoreFile(change: SnapshotDiff.Change): Boolean {
        if (change.type == SnapshotDiff.ChangeType.NEW) {
            // 基线没有该文件：还原 = 删除（当前是新增的）
            deleteCurrent(change.relativePath)
            return true
        }
        val bytes = snapshot.entryBytesFor(change.relativePath) ?: return false
        val vf = currentVirtual(change.relativePath)
        if (vf == null) {
            // 基线有、当前被删 → 还原 = 重建文件
            createFile(change.relativePath, bytes)
            return true
        }
        return writeVirtual(vf, bytes)
    }

    /** 还原全部差异。 */
    fun restoreAll(changes: List<SnapshotDiff.Change>): Int {
        var ok = 0
        for (c in changes) if (restoreFile(c)) ok++
        project.basePath?.let { base ->
            LocalFileSystem.getInstance().findFileByPath(base)?.let { root ->
                VfsUtil.markDirtyAndRefresh(false, false, true, root)
            }
        }
        return ok
    }

    /** 丢弃某文件基线（"接受改动"，dsh 直接写盘无需回写）。 */
    fun ignoreChange(change: SnapshotDiff.Change) = snapshot.drop(change.relativePath)

    /** 重新基线（丢弃旧快照全量重扫）。 */
    fun rebuildBaseline() = snapshot.rebuild()

    // ---- 内部 ----

    private fun currentVirtual(rel: String): VirtualFile? =
        project.basePath?.let { LocalFileSystem.getInstance().findFileByPath("$it/$rel") }

    private fun writeVirtual(vf: VirtualFile, bytes: ByteArray): Boolean = try {
        FileDocumentManager.getInstance().saveAllDocuments()
        VfsUtil.saveText(vf, String(bytes, StandardCharsets.UTF_8))
        true
    } catch (e: Exception) {
        LOG.warn("failed to restore ${vf.path}", e)
        false
    }

    private fun deleteCurrent(rel: String): Boolean = try {
        val vf = currentVirtual(rel) ?: return false
        vf.delete(null)
        true
    } catch (e: Exception) {
        LOG.warn("failed to delete $rel", e)
        false
    }

    private fun createFile(rel: String, bytes: ByteArray): Boolean = try {
        val base = project.basePath ?: return false
        val file = java.io.File(base, rel)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        LocalFileSystem.getInstance().refreshAndFindFileByPath(file.absolutePath)
        true
    } catch (e: Exception) {
        LOG.warn("failed to create $rel", e)
        false
    }

    private fun currentMd5Map(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val base = project.basePath ?: return result
        val root = LocalFileSystem.getInstance().findFileByPath(base) ?: return result
        val visitor = object : com.intellij.openapi.vfs.VirtualFileVisitor<Any?>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (file.isDirectory) {
                    // false = 跳过该目录的 children
                    return !PathFilters.isIgnoredDir(file.name)
                }
                val rel = com.intellij.openapi.util.io.FileUtil.getRelativePath(base, file.path, '/')
                if (rel != null && !PathFilters.isIgnoredFile(file.name, file.length)) {
                    result[rel] = try {
                        md5(file.contentsToByteArray())
                    } catch (e: Exception) {
                        ""
                    }
                }
                return true
            }
        }
        com.intellij.openapi.vfs.VfsUtilCore.visitChildrenRecursively(root, visitor)
        return result
    }

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private val LOG = Logger.getInstance(ReviewManager::class.java)
    }
}
