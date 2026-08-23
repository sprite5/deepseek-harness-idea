package com.deepseek.harness.idea.runtime

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import java.util.concurrent.ConcurrentHashMap

/**
 * DSH 生命周期管理（Step 5，见 docs/DESIGN.md §2.2/§6、PRD FR-02.2/FR-02.6）。
 *
 * - 每项目一个实例：工具窗口面板已按 `Disposer.register(project, panel)` 随项目关闭终止 Node；
 * - [projectClosed]：显式释放注册表名额 + 兜底（防 Disposer 顺序异常）；
 * - IDE 退出（[DshAppLifecycleListener] 在 plugin.xml 注册 `AppLifecycleListener`）：兜底终止全部存活面板；
 * - 并发上限 3：工具窗口启动前经 [DshRuntimeRegistry.tryAcquire] 检查，超限提示。
 */
@Service(Service.Level.APP)
class DshLifecycleManager : ProjectManagerListener {

    /** 存活面板（项目名 → 面板），供 IDE 退出兜底处置。 */
    private val livePanels = ConcurrentHashMap<String, com.deepseek.harness.idea.ui.DshToolWindowPanel>()

    fun register() {
        ProjectManager.getInstance().addProjectManagerListener(this)
    }

    /** 工具窗口面板创建时登记。 */
    fun registerPanel(projectName: String, panel: com.deepseek.harness.idea.ui.DshToolWindowPanel) {
        livePanels[projectName] = panel
    }

    /** 工具窗口面板销毁时注销。 */
    fun unregisterPanel(projectName: String) {
        livePanels.remove(projectName)
    }

    override fun projectClosed(project: Project) {
        LOG.info("project closed: ${project.name}")
        unregisterPanel(project.name)
    }

    /** IDE 退出兜底：终止所有存活面板（其 Disposable 链会杀 Node 进程树）。 */
    fun onAppClosing() {
        LOG.info("IDE closing: disposing ${livePanels.size} DSH panel(s)")
        livePanels.values.forEach { panel ->
            runCatching { panel.dispose() }
        }
        livePanels.clear()
        for (p in ProjectManager.getInstance().openProjects) {
            runCatching { DshRuntimeRegistry.getInstance().release(p.name) }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(DshLifecycleManager::class.java)

        fun getInstance(): DshLifecycleManager =
            ApplicationManager.getApplication().getService(DshLifecycleManager::class.java)
    }
}
