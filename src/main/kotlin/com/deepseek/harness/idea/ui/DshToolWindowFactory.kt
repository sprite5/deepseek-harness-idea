package com.deepseek.harness.idea.ui

import com.deepseek.harness.idea.bridge.DshBridgeManager
import com.deepseek.harness.idea.i18n.DshBundle
import com.deepseek.harness.idea.runtime.DshHomeManager
import com.deepseek.harness.idea.runtime.DshProcessManager
import com.deepseek.harness.idea.settings.DshSettingsConfigurable
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Desktop
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.net.URI
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * 工具窗口：启动内嵌 dsh → 状态流转 → JCEF 加载 Web UI。
 * 卡片：占位 / 启动中 / 浏览器 / 错误。
 */
class DshToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DshToolWindowPanel(project)
        // 主界面必须是第一个 content 且默认选中（否则日志 tab 抢焦点）
        val content = ContentFactory.getInstance().createContent(panel, DshBundle.message("toolwindow.title"), false)
        toolWindow.contentManager.addContent(content)
        toolWindow.contentManager.setSelectedContent(content, true)

        // 日志 tab 在主 content 之后添加（Step 5 FR-08.1）
        panel.installLogTab(toolWindow)

        toolWindow.setTitleActions(
            listOf(
                OpenSettingsAction(),
                OpenBrowserAction(panel),
                ReviewChangesAction(),
                RestartAction(panel)
            )
        )
    }
}

class DshToolWindowPanel(private val project: Project) : JPanel(CardLayout()), Disposable, DshProcessManager.Listener {

    companion object {
        private val LOG = Logger.getInstance(DshToolWindowPanel::class.java)
        private const val CARD_PLACEHOLDER = "placeholder"
        private const val CARD_LOADING = "loading"
        private const val CARD_BROWSER = "browser"
        private const val CARD_ERROR = "error"
    }

    private val cards = layout as CardLayout

    @Volatile
    private var processManager: DshProcessManager? = null
    private var bridgeManager: DshBridgeManager? = null
    private var browser: JBCefBrowser? = null
    private val statusLabel = JBLabel(DshBundle.message("status.stopped"), SwingConstants.CENTER)
    private val errorLabel = JBLabel(" ", SwingConstants.CENTER)
    private val retryLabel = JBLabel("<html><a href='#'>${DshBundle.message("action.restart")}</a></html>", SwingConstants.CENTER)

    /** 日志面板（Step 5 FR-08.1），null = 未打开。 */
    private var logPanel: DshLogPanel? = null

    init {
        add(buildPlaceholderCard(), CARD_PLACEHOLDER)
        add(buildLoadingCard(), CARD_LOADING)
        add(buildErrorCard(), CARD_ERROR)
        Disposer.register(project, this)
        com.deepseek.harness.idea.runtime.DshLifecycleManager.getInstance().registerPanel(project.name, this)
        start()
    }

    /** 由工厂在添加主 content 之后调用（日志 tab 不抢默认焦点）。 */
    fun installLogTab(toolWindow: ToolWindow) {
        val logPanel = DshLogPanel()
        this.logPanel = logPanel
        toolWindow.contentManager.addContent(
            com.intellij.ui.content.ContentFactory.getInstance().createContent(logPanel, DshBundle.message("log.tabTitle"), false)
        )
    }

    // ---- 生命周期 ----

    private fun start() {
        val homeManager = DshHomeManager.getInstance()
        if (!homeManager.hasRuntime()) {
            showError(DshBundle.message("error.runtimeMissing", DshHomeManager.RUNTIME_OVERRIDE_ENV))
            return
        }
        // Step 5 FR-02.6：并发上限 3
        if (!com.deepseek.harness.idea.runtime.DshRuntimeRegistry.getInstance()
                .tryAcquire(project.name, this)
        ) {
            showError(DshBundle.message("error.concurrencyLimit", com.deepseek.harness.idea.runtime.DshRuntimeRegistry.MAX_INSTANCES))
            return
        }
        showCard(CARD_LOADING)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                homeManager.ensureHome()
                homeManager.syncCredentials()
                val home = homeManager.homeDir().toFile()
                val homePath = homeManager.homeDir()

                // Step 3：MCP 桥接编排（bridge + mcp-ide-server + ide.yml patch）
                val bridge = DshBridgeManager(
                    project = project,
                    nodeExe = homeManager.nodeExe().toFile(),
                    workDir = File(project.basePath ?: System.getProperty("user.home")),
                    homeDir = homePath,
                )
                bridgeManager = bridge
                Disposer.register(this, bridge)

                val patchFile = waitForMcpPatch(bridge, homePath)
                val projectRoot = project.basePath ?: ""
                val manager = DshProcessManager(
                    nodeExe = homeManager.nodeExe().toFile(),
                    dshBin = homeManager.dshBin().toFile(),
                    workDir = File(projectRoot.ifEmpty { System.getProperty("user.home") }),
                    homeDir = home,
                    patchFile = patchFile.toFile(),
                    projectPath = projectRoot,
                    extraEnv = mapOf(
                        "DSH_IDE_BRIDGE_URL" to bridge.bridgeUrl(),
                        "DSH_IDE_TOKEN" to bridge.bridgeToken(),
                        "DSH_LOG_LEVEL" to com.deepseek.harness.idea.settings.DshSettingsState.getInstance().logLevel,
                    ),
                )
                processManager = manager
                manager.addListener(this)
                Disposer.register(this, manager)
                manager.start()
            } catch (e: Exception) {
                LOG.error("failed to bootstrap dsh", e)
                showError(e.message ?: e.toString())
            }
        }
    }

    /** 等待 MCP server 就绪（≤15s）并生成 ide.yml patch。 */
    private fun waitForMcpPatch(bridge: DshBridgeManager, homePath: java.nio.file.Path): java.nio.file.Path {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (bridge.mcpPort() > 0) return bridge.writePatch()
            Thread.sleep(200)
        }
        LOG.warn("mcp-ide-server not ready within 15s; proceeding without patch")
        // 兜底：MCP server 未就绪也继续启动 dsh（patch 保持占位，IDE 工具缺失但 web UI 可用）
        val fallback = homePath.resolve("ide.yml")
        java.nio.file.Files.writeString(fallback, "[]\n")
        return fallback
    }

    override fun dispose() {
        // processManager/bridgeManager 已注册到 Disposer(this)，此处显式停止保证顺序
        processManager?.dispose()
        processManager = null
        bridgeManager?.dispose()
        bridgeManager = null
        browser?.dispose()
        browser = null
        com.deepseek.harness.idea.runtime.DshLifecycleManager.getInstance().unregisterPanel(project.name)
        com.deepseek.harness.idea.runtime.DshRuntimeRegistry.getInstance().release(project.name)
    }

    fun restart() {
        val manager = processManager ?: return
        ApplicationManager.getApplication().executeOnPooledThread { manager.restart() }
    }

    fun webUrl(): String? = processManager?.webUrl()

    fun isRunning(): Boolean = processManager?.currentState() == DshProcessManager.State.RUNNING

    /**
     * 发送选中代码到 DSH（Step 4 + 紧凑引用）：
     * 1. 直接写入 Bridge 的 sent-selection 队列（智能体可随时经 ide_get_sent_selection 取回，必达；
     *    队列存完整代码，供智能体按需读取）；
     * 2. 聚焦工具窗口并尝试 JCEF 注入：输入框填入**紧凑文件引用** `@路径#L起始-结束` + 换行，
     *    光标自动落到下一行等待输入问题（无提示语、无代码本体）；
     * 3. 注入失败/未运行 → 剪贴板（同样紧凑引用）+ 通知降级。
     */
    fun sendSelection(filePath: String?, language: String?, selection: String, lineStart: Int, lineEnd: Int) {
        val bridge = bridgeManager
        if (bridge != null) {
            bridge.pushSentSelection(filePath, language, selection, lineStart, lineEnd)
        }
        val panel = this
        val ref = buildCompactReference(filePath, lineStart, lineEnd)
        ApplicationManager.getApplication().invokeLater {
            // 聚焦工具窗口
            com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow("DeepSeek Harness")?.activate(null)
            val injected = injectToBrowser(ref)
            if (!injected) {
                copyToClipboard(ref)
                showNotification(DshBundle.message("sendSelection.clipboard"))
            } else {
                showNotification(DshBundle.message("sendSelection.done"))
            }
        }
    }

    /** 构造紧凑引用：`@绝对路径#L起始-结束` + 尾随换行（光标落下一行，无提示语）。 */
    private fun buildCompactReference(filePath: String?, lineStart: Int, lineEnd: Int): String {
        if (filePath.isNullOrBlank()) return ""
        val sb = StringBuilder()
        sb.append('@').append(filePath.replace('\\', '/'))
        if (lineEnd > 0) {
            sb.append("#L").append(lineStart)
            if (lineEnd > lineStart) sb.append('-').append(lineEnd)
        }
        sb.append('\n')
        return sb.toString()
    }

    /** JCEF 注入：轮询 dsh web 的 composer textarea，设置值、触发 React input 事件、光标移到末尾（下一行）。 */
    private fun injectToBrowser(selection: String): Boolean {
        val b = browser ?: return false
        val cef = try { b.cefBrowser } catch (e: Throwable) { return false }
        val json = escapeJs(selection)
        val script = """
            (() => {
              const deadline = Date.now() + 8000;
              const text = $json;
              const tryInject = () => {
                const ta = document.querySelector('textarea');
                if (!ta) { if (Date.now() < deadline) setTimeout(tryInject, 300); return; }
                const proto = window.HTMLTextAreaElement.prototype;
                const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
                setter.call(ta, text);
                ta.dispatchEvent(new Event('input', { bubbles: true }));
                // 光标移到文本末尾（引用行之后的新行），等待直接输入问题
                const pos = ta.value.length;
                ta.setSelectionRange(pos, pos);
                ta.focus();
              };
              tryInject();
            })();
        """.trimIndent()
        return try {
            val pageUrl = runCatching { cef.url }.getOrNull() ?: "about:blank"
            cef.executeJavaScript(script, pageUrl, 0)
            true
        } catch (e: Throwable) {
            LOG.warn("JCEF injection failed", e)
            false
        }
    }

    private fun escapeJs(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private fun copyToClipboard(text: String) {
        try {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .setContents(java.awt.datatransfer.StringSelection(text), null)
        } catch (e: Exception) {
            LOG.warn("clipboard failed", e)
        }
    }

    private fun showNotification(content: String) {
        com.intellij.notification.Notifications.Bus.notify(
            com.intellij.notification.Notification(
                "DeepSeek Harness",
                "",
                content,
                com.intellij.notification.NotificationType.INFORMATION,
            ),
            project,
        )
    }

    // ---- DshProcessManager.Listener（后台线程回调，UI 更新切 EDT） ----

    override fun onStateChanged(oldState: DshProcessManager.State, newState: DshProcessManager.State) {
        ApplicationManager.getApplication().invokeLater {
            statusLabel.text = when (newState) {
                DshProcessManager.State.STARTING -> DshBundle.message("status.starting")
                DshProcessManager.State.RUNNING -> DshBundle.message("status.running")
                DshProcessManager.State.CRASHED -> DshBundle.message("status.crashed")
                DshProcessManager.State.STOPPED -> DshBundle.message("status.stopped")
            }
            // Step 5 FR-02.5：崩溃通知（自动重启已由 DshProcessManager 退避执行）
            if (newState == DshProcessManager.State.CRASHED && oldState != DshProcessManager.State.CRASHED) {
                notifyCrash()
            }
        }
    }

    override fun onUrlReady(url: String) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val b = browser ?: JBCefBrowser().also {
                    browser = it
                    add(it.component, CARD_BROWSER)
                }
                b.loadURL(url)
                cards.show(this, CARD_BROWSER)
            } catch (e: Throwable) {
                LOG.warn("JCEF failed to load web ui", e)
                showError(buildJcefError(e))
            }
        }
    }

    /**
     * JCEF 初始化失败提示（含根因线索，便于用户在真实 IDE 会话中排查）。
     * 2026.2 起 JCEF 是独立内置插件 com.intellij.modules.jcef（"Web Browser (JCEF)"），
     * 且需要 IDE 以带 JCEF 的 JBR 运行时启动；此处把异常信息与检查建议一并显示。
     */
    private fun buildJcefError(e: Throwable): String {
        val hint = DshBundle.message("error.jcef")
        val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        return "$hint<br><br><b>${escapeHtml(detail)}</b><br><br>" +
            DshBundle.message("error.jcef.hint")
    }

    override fun onLogLine(line: String) {
        // Step 5 FR-08.1：转发到日志面板（后台线程回调，切 EDT）
        val panel = logPanel
        if (panel != null) {
            ApplicationManager.getApplication().invokeLater { panel.append(line) }
        }
    }

    private fun notifyCrash() {
        com.intellij.notification.Notifications.Bus.notify(
            com.intellij.notification.Notification(
                "DeepSeek Harness",
                DshBundle.message("crash.title"),
                DshBundle.message("crash.autoRestarting"),
                com.intellij.notification.NotificationType.WARNING,
            ),
            project,
        )
    }

    // ---- 卡片 ----

    private fun showCard(card: String) {
        ApplicationManager.getApplication().invokeLater { cards.show(this, card) }
    }

    private fun showError(message: String) {
        ApplicationManager.getApplication().invokeLater {
            errorLabel.text = "<html>${escapeHtml(message)}</html>"
            cards.show(this, CARD_ERROR)
        }
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun buildPlaceholderCard(): JComponent = columnCard(
        JBLabel(DshBundle.message("toolwindow.placeholder.title"), SwingConstants.CENTER),
        JBLabel(DshBundle.message("toolwindow.placeholder.notStarted"), SwingConstants.CENTER)
    )

    private fun buildLoadingCard(): JComponent = columnCard(
        JBLabel(DshBundle.message("toolwindow.placeholder.title"), SwingConstants.CENTER),
        JBLabel(DshBundle.message("toolwindow.placeholder.starting"), SwingConstants.CENTER),
        statusLabel
    )

    private fun buildErrorCard(): JComponent = columnCard(
        JBLabel(DshBundle.message("toolwindow.placeholder.title"), SwingConstants.CENTER),
        errorLabel,
        retryLabel.apply {
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = restart()
            })
        }
    )

    private fun columnCard(vararg labels: JComponent): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(16)
        }
        labels.forEachIndexed { i, c ->
            if (i > 0) c.border = JBUI.Borders.emptyTop(8)
            panel.add(c)
        }
        return panel
    }
}

class OpenSettingsAction : AnAction(DshBundle.message("action.settings"), null, com.intellij.icons.AllIcons.General.Settings) {
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, DshSettingsConfigurable::class.java)
    }
}

class OpenBrowserAction(private val panel: DshToolWindowPanel) :
    AnAction(DshBundle.message("action.browse"), null, com.intellij.icons.AllIcons.Toolwindows.WebToolWindow) {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panel.isRunning()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val url = panel.webUrl() ?: return
        try {
            Desktop.getDesktop().browse(URI(url))
        } catch (ex: Exception) {
            LOG.warn("failed to open browser $url", ex)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(OpenBrowserAction::class.java)
    }
}

class RestartAction(private val panel: DshToolWindowPanel) : AnAction(DshBundle.message("action.restart"), null, com.intellij.icons.AllIcons.Actions.Restart) {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = true
    }

    override fun actionPerformed(e: AnActionEvent) = panel.restart()
}
