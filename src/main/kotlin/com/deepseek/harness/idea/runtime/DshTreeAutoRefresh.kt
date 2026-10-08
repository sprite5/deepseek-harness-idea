package com.deepseek.harness.idea.runtime

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * dsh 批量改文件后 **IDEA 项目树/文件状态自动刷新** 的兜底监听器（WatchService 方案）。
 *
 * **背景**：dsh 智能体直接写工作区磁盘文件，IDEA 依赖原生 fsnotifier 自动刷新 VFS；
 * 实测长会话 + 批量写入场景下 fsnotifier 时好时坏（树/状态滞后，需手动 Ctrl+Alt+Y）。
 * agent 侧的 `ide_refresh_files` 历史上就不是稳定机制（日志显示每天 0~3 次）。
 * 本类对项目根做**递归** WatchService 监听，变更后防抖触发一次异步 VFS 刷新 +
 * ProjectView 刷新，使树与文件状态确定性地跟上。
 *
 * **防抖**：QUIET_MS 内的连续事件合并为一次刷新；两次实际刷新间隔不小于 MIN_INTERVAL_MS
 * （批量写入/构建风暴时最多每 2s 一次，避免刷新风暴）。
 *
 * **跳过目录**：.git / node_modules / build / target / dist / out / .idea —— 这些目录
 * 变更频繁且不进项目树语义（.git 由 git 插件自行处理），递归注册时整棵剪枝。
 *
 * **无自激循环**：刷新只读盘（markDirtyAndRefresh 不写文件），不会再次触发监听。
 *
 * **生命周期**：与 DshSettingsSync 同模式——面板启动时 [register]，panel dispose 时
 * [release]；同一项目多次 register 幂等返回同一实例。
 */
class DshTreeAutoRefresh(
    private val project: Project,
    private val projectRoot: Path,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private var watchService: java.nio.file.WatchService? = null
    private var scheduler: ScheduledExecutorService? = null
    private var pendingTask: ScheduledFuture<*>? = null

    /** 最近一次事件时间戳（ms），防抖窗口参照物。 */
    private val lastEventAt = AtomicLong(0)

    /** 最近一次实际刷新时间戳（ms），限流用。 */
    private val lastFireAt = AtomicLong(0)

    /** 防抖任务是否已排队（只排队一个，到期时若窗口内仍有新事件则顺延）。 */
    private val scheduled = AtomicBoolean(false)

    fun start() {
        if (closed.get()) return
        if (watchService != null) return
        if (!Files.isDirectory(projectRoot)) {
            LOG.info("project root not ready, skipping tree watch: $projectRoot")
            return
        }
        try {
            val ws = FileSystems.getDefault().newWatchService()
            val dirs = registerRecursively(projectRoot, ws)
            if (dirs == 0) {
                runCatching { ws.close() }
                LOG.warn("no watchable dirs under $projectRoot, tree watch disabled")
                return
            }
            watchService = ws
            val events = Executors.newSingleThreadExecutor { r ->
                Thread(r, "dsh-tree-autorefresh-events").apply { isDaemon = true }
            }
            scheduler = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "dsh-tree-autorefresh-debounce").apply { isDaemon = true }
            }
            events.execute { loop(ws) }
            LOG.info("watching project tree: $projectRoot ($dirs dirs)")
        } catch (e: Exception) {
            LOG.warn("failed to watch project tree $projectRoot", e)
        }
    }

    /** 递归注册监听，返回注册成功的目录数；[SKIP_DIRS] 命中的目录整棵剪枝。 */
    private fun registerRecursively(dir: Path, ws: java.nio.file.WatchService): Int {
        if (closed.get()) return 0
        if (dir.fileName?.toString() in SKIP_DIRS) return 0
        var count = 0
        runCatching {
            dir.register(
                ws,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE,
            )
            count = 1
        }.onFailure {
            // 单目录注册失败（权限等）不阻断其余目录
            LOG.debug("skip unwatchable dir: $dir (${it.message})")
        }
        runCatching {
            Files.newDirectoryStream(dir) { entry -> Files.isDirectory(entry) }.use { stream ->
                for (child in stream) count += registerRecursively(child, ws)
            }
        }.onFailure { LOG.debug("skip unscannable dir: $dir (${it.message})") }
        return count
    }

    private fun loop(ws: java.nio.file.WatchService) {
        while (!closed.get()) {
            try {
                val key: WatchKey = ws.take()
                if (closed.get()) break
                var relevant = false
                for (event in key.pollEvents()) {
                    val ev = event as WatchEvent<*>
                    // OVERFLOW（事件队列溢出）也视为相关：宁可多刷一次也不漏
                    relevant = true
                    if (ev.context() == null) continue
                }
                key.reset()
                if (relevant) {
                    lastEventAt.set(System.currentTimeMillis())
                    scheduleDebounce()
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: java.nio.file.ClosedWatchServiceException) {
                return
            } catch (e: Exception) {
                if (!closed.get()) LOG.warn("tree watch loop error", e)
            }
        }
    }

    /** 防抖：QUIET_MS 无新事件才真正刷新；窗口内又来事件则顺延（单任务排队）。 */
    private fun scheduleDebounce() {
        if (closed.get()) return
        val sched = scheduler ?: return
        if (!scheduled.compareAndSet(false, true)) return
        pendingTask = sched.schedule({ maybeFire() }, QUIET_MS, TimeUnit.MILLISECONDS)
    }

    private fun maybeFire() {
        scheduled.set(false)
        if (closed.get()) return
        val now = System.currentTimeMillis()
        if (now - lastEventAt.get() < QUIET_MS - CLOCK_SLACK_MS) {
            // 窗口内还有新事件，顺延
            scheduleDebounce()
            return
        }
        if (now - lastFireAt.get() < MIN_INTERVAL_MS) {
            // 距上次刷新太近，跳过本次（后续事件会再触发）
            return
        }
        lastFireAt.set(now)
        fire()
    }

    private fun fire() {
        val root = projectRoot.toString().replace('\\', '/')
        ApplicationManager.getApplication().invokeLater {
            if (closed.get() || project.isDisposed) return@invokeLater
            runCatching {
                val vf = LocalFileSystem.getInstance().findFileByPath(root) ?: return@invokeLater
                // async=true：不阻塞 EDT；recursive=true：整棵子树重新对盘；
                // reloadFromDisk=true：已打开的编辑器同步 dsh 改动（未保存的用户缓冲不会被覆盖，
                // 与 /refresh 端点的 refreshPath 行为一致）
                VfsUtil.markDirtyAndRefresh(false, true, true, vf)
                ProjectView.getInstance(project).refresh()
                LOG.info("tree auto-refresh fired for $root")
            }.onFailure { LOG.warn("tree auto-refresh failed", it) }
        }
    }

    /** 停止监听（幂等，可多次调用）。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { pendingTask?.cancel(false) }
        runCatching { watchService?.close() }
        watchService = null
        runCatching { scheduler?.shutdownNow() }
        scheduler = null
    }

    companion object {
        private val LOG = Logger.getInstance(DshTreeAutoRefresh::class.java)

        /** 防抖静默窗口：连续写入合并 */
        private const val QUIET_MS = 700L

        /** 时钟容差（防抖判定余量） */
        private const val CLOCK_SLACK_MS = 50L

        /** 两次实际刷新的最小间隔（构建/批量写风暴限流） */
        private const val MIN_INTERVAL_MS = 2000L

        /** 递归注册时剪枝的目录名 */
        private val SKIP_DIRS = setOf(
            ".git", ".svn", ".hg", ".idea", ".gradle",
            "node_modules", "build", "target", "dist", "out", ".next", ".nuxt",
        )

        /** 项目名 → 正在监听的刷新器（供生命周期幂等登记/释放）。 */
        private val active = ConcurrentHashMap<String, DshTreeAutoRefresh>()

        /** 启动/登记某项目工作区监听（幂等）。 */
        fun register(project: Project, projectRoot: String): DshTreeAutoRefresh? {
            if (projectRoot.isBlank()) return null
            val existing = active[project.name]
            if (existing != null) return existing
            val sync = DshTreeAutoRefresh(project, Path.of(projectRoot))
            sync.start()
            active[project.name] = sync
            return sync
        }

        /** 释放某项目监听（幂等）。 */
        fun release(projectName: String) {
            active.remove(projectName)?.close()
        }
    }
}
