package com.deepseek.harness.idea.runtime

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * dsh Web UI 改 key 的**文件监听同步器**（方案 B）。
 *
 * **背景**：插件在启动 dsh 时不再注入 `DEEPSEEK_API_KEY` 环境变量（dsh-credentials-local 的
 * `inherited env wins` 会遮蔽 Web UI 写入，且 `assertUnshadowed` 会直接拒绝 Web UI 的 set）。
 * 去掉 env 后，dsh 读/写 **当前项目 `DSH_HOME/.credentials.yaml`**（dsh 进程内只能落盘到
 * `$DSH_HOME/.credentials.yaml`，无法透过 cordis 事件被插件感知）。
 *
 * **作用**：对每个打开的 dsh 项目，用 `WatchService` 监听其 `DSH_HOME/.credentials.yaml`；
 * dsh Web UI（Models page）改 key 会以 `version: 1` + `refs.DEEPSEEK_API_KEY` 写入该文件，
 * 监听器捕获此变化 → 解析出 key → 回写：
 *   1. `PasswordSafe`（`DshCredentials.writeApiKey`）—— 全局应用级真源；
 *   2. 全局 `.credentials.yaml`（`DshHomeManager.globalConfigHome()`）—— 其它项目下次启动，
 *      `syncCredentials()`/`ensureHome()` 从全局复制 + 透传，最终全局一致。
 *
 * **无自激循环**：监听的是**子项目**文件；回写目标是 PasswordSafe + **全局**文件（不同路径），
 * 不会反向触发本项目监听器。
 *
 * 生命周期：与工具窗口项目 Disposable 绑定（[closeProject] 停止监听线程 + 释放 WatchService）。
 */
class DshCredentialsSync(private val projectCredFile: Path) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private var watchService: java.nio.file.WatchService? = null
    private var executor: java.util.concurrent.ExecutorService? = null

    /**
     * 启动监听。在 [projectCredFile] 所在目录注册非递归 watch，开始轮询该文件。
     * 重复调用幂等（已启动则直接返回）。失败静默降级（不影响 dsh 运行）。
     */
    fun start() {
        if (closed.get()) return
        if (watchService != null) return
        val dir = projectCredFile.parent ?: return
        try {
            val ws = FileSystems.getDefault().newWatchService()
            dir.register(ws, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE)
            watchService = ws
            val ex = Executors.newSingleThreadExecutor { r -> Thread(r, "dsh-cred-sync").apply { isDaemon = true } }
            executor = ex
            ex.execute { loop(ws) }
            LOG.info("watching project credentials: $projectCredFile")
        } catch (e: Exception) {
            LOG.warn("failed to watch project credentials $projectCredFile", e)
        }
    }

    private fun loop(ws: java.nio.file.WatchService) {
        while (!closed.get()) {
            try {
                val key: WatchKey = ws.take()
                if (closed.get()) break
                val fileName = projectCredFile.fileName.toString()
                var relevant = false
                for (event in key.pollEvents()) {
                    val ctx = event.context() as? Path
                    if (ctx != null && ctx.fileName.toString() == fileName) relevant = true
                }
                key.reset()
                if (relevant && Files.isRegularFile(projectCredFile)) {
                    runCatching { onFileChanged() }
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                if (!closed.get()) LOG.warn("credential watch loop error", e)
            }
        }
    }

    /**
     * 子项目 `.credentials.yaml` 变化：把**全部 refs**（含第三方 pi provider 的
     * MINIMAX_CN_API_KEY / AIYUNROUTER_API_KEY 等）合并回写全局真源 + PasswordSafe。
     *
     * 旧实现只回写 DEEPSEEK_API_KEY，pi 的 apiKeyEnv 引用在每次重开时被
     * ensureHome 用全局（只有 DEEPSEEK）覆盖掉，导致反复提示重新输入密钥。
     * 现在：项目文件的所有 refs 与全局合并（项目值优先），DEEPSEEK_API_KEY 另同步 PasswordSafe。
     */
    internal fun onFileChanged() {
        val projectRefs = DshCredentials.readAllRefs(projectCredFile)
        if (projectRefs.isEmpty()) return
        val globalCred = DshHomeManager.getInstance().globalConfigHome().resolve(".credentials.yaml")
        val globalRefs = DshCredentials.readAllRefs(globalCred)
        // 合并：全局已有 + 项目全部（项目值优先，视为本次改动最新）
        val merged = LinkedHashMap<String, String>(globalRefs)
        var changed = false
        for ((k, v) in projectRefs) {
            if (merged[k] != v) changed = true
            merged[k] = v
        }
        // DEEPSEEK_API_KEY 同步到 PasswordSafe（设置页真源）
        projectRefs[DshCredentials.DEEPSEEK_API_KEY]?.let { key ->
            if (key != DshCredentials.readApiKey()) DshCredentials.writeApiKey(key)
        }
        if (!changed && merged == globalRefs) return // 一致 → 无需写回
        DshCredentials.writeRefs(globalCred, merged)
        LOG.info("dsh updated credentials (refs=${merged.keys}); synced to global ${projectRefs.keys}")
    }

    /** 纯逻辑（可单测）：比较项目 key 与全局 key，不同则返回应回写，否则返回 null。 */
    internal fun resolveSync(projectKey: String?, globalKey: String?): String? =
        if (projectKey.isNullOrEmpty() || projectKey == globalKey) null else projectKey

    /** 停止监听（幂等，可多次调用）。 */
    fun closeProject() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { watchService?.close() }
        watchService = null
        runCatching { executor?.shutdownNow() }
        executor = null
    }

    override fun close() = closeProject()

    companion object {
        private val LOG = Logger.getInstance(DshCredentialsSync::class.java)

        /** 项目名 → 正在监听的同步器（供生命周期幂等登记/释放）。 */
        private val active = ConcurrentHashMap<String, DshCredentialsSync>()

        /** 启动/登记某项目的 key 监听（幂等）。 */
        fun register(projectName: String, credFile: Path): DshCredentialsSync {
            val existing = active[projectName]
            if (existing != null) return existing
            val sync = DshCredentialsSync(credFile)
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
