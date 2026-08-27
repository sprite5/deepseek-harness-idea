package com.deepseek.harness.idea.runtime

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * dsh Web UI 改 `llm-pi-ai:` 配置的**文件监听同步器**（方案 A：跨项目共享第三方 LLM）。
 *
 * **背景**：用户在某个项目的 dsh Web Models page 里加 / 改 / 删第三方 provider（如
 * ark-plan、minimax-cn），DSH 会写到**当前项目** `$DSH_HOME/settings.yaml` 的
 * `llm-pi-ai:` 节。该改动**只对当前项目可见**——其它项目 / 新开项目都看不到。
 *
 * **作用**：对每个打开的 dsh 项目，用 `WatchService` 监听其 `DSH_HOME/settings.yaml`；
 * dsh Web UI 改 `llm-pi-ai:` 节 → 监听器捕获 → 调 [DshHomeManager.syncProvidersToGlobal]
 * 把该节**全量替换**到全局 `<config>/dsh-idea/dsh-home/settings.yaml` 真源。其它顶层节
 * （`ui-onboarding`、cordis 元数据 `$settings`/`$credentials`）原样保留。
 *
 * **无自激循环**：监听的是**子项目**文件；回写目标是**全局**文件（不同路径），全局那份
 * 不被监听，不会反向触发。已存在的 `DshCredentialsSync` 是同一模式。
 *
 * **生命周期**：与工具窗口项目 Disposable 绑定（[closeProject] 停止监听线程 + 释放
 * WatchService）。同一项目多次 register 幂等返回同一实例。
 */
class DshSettingsSync(private val projectSettingsFile: Path) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private var watchService: java.nio.file.WatchService? = null
    private var executor: java.util.concurrent.ExecutorService? = null

    /** 启动监听。在 [projectSettingsFile] 所在目录注册非递归 watch，开始轮询该文件。 */
    fun start() {
        if (closed.get()) return
        if (watchService != null) return
        val dir = projectSettingsFile.parent ?: return
        // 文件不存在时父目录可能也不存在（DSH_HOME 刚创建但 settings.yaml 还没 dsh 写入）
        if (!Files.isDirectory(dir)) {
            LOG.info("project settings dir not ready yet, skipping watch: $dir")
            return
        }
        try {
            val ws = FileSystems.getDefault().newWatchService()
            dir.register(ws, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE)
            watchService = ws
            val ex = Executors.newSingleThreadExecutor { r ->
                Thread(r, "dsh-settings-sync").apply { isDaemon = true }
            }
            executor = ex
            ex.execute { loop(ws) }
            LOG.info("watching project settings: $projectSettingsFile")
        } catch (e: Exception) {
            LOG.warn("failed to watch project settings $projectSettingsFile", e)
        }
    }

    private fun loop(ws: java.nio.file.WatchService) {
        while (!closed.get()) {
            try {
                val key: WatchKey = ws.take()
                if (closed.get()) break
                val fileName = projectSettingsFile.fileName.toString()
                var relevant = false
                for (event in key.pollEvents()) {
                    val ctx = event.context() as? Path
                    if (ctx != null && ctx.fileName.toString() == fileName) relevant = true
                }
                key.reset()
                if (relevant && Files.isRegularFile(projectSettingsFile)) {
                    runCatching { onFileChanged() }
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                if (!closed.get()) LOG.warn("settings watch loop error", e)
            }
        }
    }

    /**
     * 子项目 `settings.yaml` 变化：调 [DshHomeManager.syncProvidersToGlobal] 把 `llm-pi-ai:`
     * 节回写到全局。失败静默降级（LOG.warn 已由 syncProvidersToGlobal 内部记录）。
     */
    internal fun onFileChanged() {
        DshHomeManager.getInstance().syncProvidersToGlobal(projectSettingsFile)
    }

    /** 停止监听（幂等，可多次调用）。 */
    fun closeProject() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { watchService?.close() }
        watchService = null
        runCatching { executor?.shutdownNow() }
        // 兜底：shutdownNow 不保证线程立刻退出，但 WatchService 已 close，take() 抛 ClosedWatchServiceException，
        // loop 内 catch (Exception) 会再次回到 take() 然后立刻抛——加个 await 防止守护线程拖时间。
        runCatching { executor?.awaitTermination(1, TimeUnit.SECONDS) }
        executor = null
    }

    override fun close() = closeProject()

    companion object {
        private val LOG = Logger.getInstance(DshSettingsSync::class.java)

        /** 项目名 → 正在监听的同步器（供生命周期幂等登记/释放）。 */
        private val active = ConcurrentHashMap<String, DshSettingsSync>()

        /** 启动/登记某项目的 settings.yaml 监听（幂等）。 */
        fun register(projectName: String, settingsFile: Path): DshSettingsSync {
            val existing = active[projectName]
            if (existing != null) return existing
            val sync = DshSettingsSync(settingsFile)
            sync.start()
            active[projectName] = sync
            return sync
        }

        /** 释放某项目监听（幂等）。 */
        fun release(projectName: String) {
            active.remove(projectName)?.closeProject()
        }
    }
}
