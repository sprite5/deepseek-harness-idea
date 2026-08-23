package com.deepseek.harness.idea.runtime

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * DSH 实例注册表（应用级，Step 5）。
 *
 * - 追踪各项目当前启动中的 Node 实例（幂等 per-project）；
 * - 并发上限 3（FR-02.6）：超限时 [tryAcquire] 返回 false，调用方给出提示；
 * - 全局计数供工具窗口/日志展示；IDE 退出时由 [ShutdownHook] 统一清理。
 */
@Service(Service.Level.APP)
class DshRuntimeRegistry : Disposable {

    /** 项目名 → 实例句柄（注册成功即视为占用一个并发名额）。 */
    private val running = ConcurrentHashMap<String, Any>()

    /** 并发上限（FR-02.6）。 */
    companion object {
        private val LOG = Logger.getInstance(DshRuntimeRegistry::class.java)
        const val MAX_INSTANCES = 3

        fun getInstance(): DshRuntimeRegistry =
            ApplicationManager.getApplication().getService(DshRuntimeRegistry::class.java)
    }

    /** 尝试登记项目实例；超限返回 false。 */
    fun tryAcquire(projectName: String, handle: Any): Boolean {
        val current = running.putIfAbsent(projectName, handle)
        if (current != null) return true // 该项目已有实例（幂等）
        if (running.size > MAX_INSTANCES) {
            running.remove(projectName)
            LOG.warn("DSH instance limit reached ($MAX_INSTANCES); rejected $projectName")
            return false
        }
        LOG.info("DSH instance registered: $projectName (total=${running.size})")
        return true
    }

    /** 释放项目实例名额。 */
    fun release(projectName: String) {
        if (running.remove(projectName) != null) {
            LOG.info("DSH instance released: $projectName (total=${running.size})")
        }
    }

    fun isRunning(projectName: String): Boolean = running.containsKey(projectName)

    fun runningCount(): Int = running.size

    /** IDE 退出兜底：全部实例由各自的 Disposable 清理，此处仅记录。 */
    override fun dispose() {
        running.clear()
        LOG.info("DSH runtime registry disposed (${running.size} instances)")
    }
}
