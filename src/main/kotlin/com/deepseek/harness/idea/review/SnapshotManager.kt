package com.deepseek.harness.idea.review

import com.deepseek.harness.idea.util.PathFilters
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 审查基线快照（Step 4，见 docs/DESIGN.md §3.8）。
 *
 * 工具窗口首次打开时对项目根建立基线：`相对路径 → content(MD5 + 原始字节)`。
 * - 忽略规则见 [PathFilters]（构建产物目录 + >1MB 文件）；
 * - 内存字节总容量 LRU ≤200MB：超限时最旧条目的字节落盘（`<md5>.bin`），
 *   md5 与元数据恒在内存；diff/还原按需回读，LRU 语义与磁盘互补；
 * - 落盘：插件临时目录（不污染项目），并保存元数据索引加速重建。
 */
class SnapshotManager(private val project: Project) {

    /** 快照条目：相对路径 → 内容。 */
    data class Entry(
        val relativePath: String,
        val md5: String,
        val bytes: ByteArray,
    )

    private val snapshot: LinkedHashMap<String, Entry> = object : LinkedHashMap<String, Entry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean {
            // 超限时由调用方（addEntry）显式处理（字节落盘后再移除内存字节），
            // 这里只做安全兜底：条目数上限。
            return size > MAX_SNAPSHOT_ENTRIES
        }
    }
    private val inMemoryBytes = AtomicLong(0)
    private val building = AtomicBoolean(false)

    fun snapshotDir(): Path =
        Path.of(FileUtil.getTempDirectory(), "dsh-idea", "snapshots", FileUtil.sanitizeFileName(project.name))

    /** 是否已有基线（内存或磁盘索引）。 */
    fun hasSnapshot(): Boolean = snapshot.isNotEmpty() || Files.exists(indexFile())

    /** 建立基线（幂等）：已有则复用，否则全量扫描 + 持久化。 */
    fun buildIfAbsent(): Map<String, Entry> {
        if (building.compareAndSet(false, true)) {
            try {
                if (snapshot.isEmpty() && loadFromDisk()) return snapshot
                if (snapshot.isEmpty()) scanProject()
                saveIndex()
            } finally {
                building.set(false)
            }
        }
        return snapshot
    }

    /** 全量重扫（重新基线/忽略后重建；丢弃旧快照与磁盘内容）。 */
    fun rebuild(): Map<String, Entry> {
        synchronized(snapshot) {
            snapshot.clear()
            inMemoryBytes.set(0)
        }
        deleteSnapshotFiles()
        return buildIfAbsent()
    }

    /** 当前基线条目（不可变视图）。 */
    fun entries(): Map<String, Entry> = synchronized(snapshot) { LinkedHashMap(snapshot) }

    /** 基线中某文件的内容字节（diff 对比与还原用；内存缺则从磁盘回读）。 */
    fun entryBytesFor(relativePath: String): ByteArray? {
        val e = synchronized(snapshot) { snapshot[relativePath] } ?: return null
        if (e.bytes.isNotEmpty()) return e.bytes
        return readSpilled(relativePath, e.md5)
    }

    /** 丢弃某文件的基线（用户"忽略"该改动）。 */
    fun drop(relativePath: String) {
        synchronized(snapshot) {
            snapshot.remove(relativePath)?.let { inMemoryBytes.addAndGet(-it.bytes.size.toLong()) }
        }
    }

    /** 基线文件总条数。 */
    fun size(): Int = synchronized(snapshot) { snapshot.size }

    // ---- 内部 ----

    private fun scanProject() {
        val base = project.basePath ?: return
        val root = LocalFileSystem.getInstance().findFileByPath(base) ?: return
        val visitor = object : com.intellij.openapi.vfs.VirtualFileVisitor<Any?>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (file.isDirectory) {
                    // false = 跳过该目录的 children
                    return !PathFilters.isIgnoredDir(file.name)
                }
                val rel = FileUtil.getRelativePath(root.path, file.path, '/')
                if (rel != null && !PathFilters.isIgnoredFile(file.name, file.length)) {
                    addEntry(rel, file)
                }
                return true
            }
        }
        com.intellij.openapi.vfs.VfsUtilCore.visitChildrenRecursively(root, visitor)
    }

    private fun addEntry(rel: String, file: VirtualFile) {
        val bytes = try { file.contentsToByteArray() } catch (e: Exception) { return }
        val md5 = md5(bytes)
        val entry = Entry(rel, md5, bytes)
        synchronized(snapshot) {
            snapshot[rel]?.let { inMemoryBytes.addAndGet(-it.bytes.size.toLong()) }
            snapshot[rel] = entry
            inMemoryBytes.addAndGet(bytes.size.toLong())
        }
        evictSpillIfNeeded()
    }

    /** 内存超 200MB：把最旧条目的字节落盘并置空（md5/元数据保留）。 */
    private fun evictSpillIfNeeded() {
        if (inMemoryBytes.get() <= MAX_TOTAL_BYTES) return
        synchronized(snapshot) {
            val it = snapshot.entries.iterator()
            while (it.hasNext() && inMemoryBytes.get() > MAX_TOTAL_BYTES) {
                val (rel, e) = it.next()
                if (e.bytes.isEmpty()) continue
                if (spill(rel, e.md5, e.bytes)) {
                    inMemoryBytes.addAndGet(-e.bytes.size.toLong())
                    snapshot[rel] = Entry(rel, e.md5, ByteArray(0))
                } else {
                    break // 磁盘写失败则停止剔除，避免内存无谓收缩
                }
            }
        }
    }

    private fun spill(rel: String, md5: String, bytes: ByteArray): Boolean = try {
        Files.createDirectories(contentsDir())
        Files.write(contentsDir().resolve("$md5.bin"), bytes)
        true
    } catch (e: Exception) {
        LOG.warn("failed to spill snapshot content $rel", e)
        false
    }

    private fun readSpilled(rel: String, md5: String): ByteArray? = try {
        val f = contentsDir().resolve("$md5.bin")
        if (Files.exists(f)) Files.readAllBytes(f) else null
    } catch (e: Exception) {
        LOG.warn("failed to read spilled content $rel", e)
        null
    }

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun indexFile(): Path = snapshotDir().resolve("index.txt")
    private fun contentsDir(): Path = snapshotDir().resolve("contents")

    private fun saveIndex() {
        try {
            val dir = snapshotDir()
            Files.createDirectories(dir)
            val sb = StringBuilder()
            synchronized(snapshot) {
                for (e in snapshot.values) {
                    sb.append(e.relativePath).append('\u0000').append(e.md5).append('\n')
                }
            }
            Files.writeString(indexFile(), sb.toString())
        } catch (e: Exception) {
            LOG.warn("failed to save snapshot index", e)
        }
    }

    private fun loadFromDisk(): Boolean {
        val index = indexFile()
        if (!Files.exists(index)) return false
        return try {
            var count = 0
            Files.readAllLines(index).forEach { line ->
                val parts = line.split('\u0000')
                if (parts.size == 2) {
                    synchronized(snapshot) {
                        snapshot[parts[0]] = Entry(parts[0], parts[1], ByteArray(0))
                    }
                    count++
                }
            }
            count > 0
        } catch (e: Exception) {
            LOG.warn("failed to load snapshot index", e)
            false
        }
    }

    private fun deleteSnapshotFiles() {
        try {
            val dir = snapshotDir()
            if (Files.exists(dir)) {
                Files.walk(dir).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
                }
            }
        } catch (e: Exception) {
            LOG.warn("failed to delete snapshot files", e)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(SnapshotManager::class.java)
        private const val MAX_TOTAL_BYTES = 200L * 1024 * 1024
        private const val MAX_SNAPSHOT_ENTRIES = 50_000
    }
}
