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
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Desktop
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter

/**
 * 工具窗口：启动内嵌 dsh → 状态流转 → JCEF 加载 Web UI。
 * 卡片：占位 / 启动中 / 浏览器 / 错误。
 */
class DshToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // 防御：同窗口切换项目时 IDEA 可能复用同一个工具窗口实例，旧项目 content
        // （上一项目的面板 + DSH 日志页）会残留，出现重复面板 + 旧工作区/旧进程。
        // 先把全部旧 content 移除并 dispose（触发旧面板 dispose → 杀其 dsh 进程），再建当前项目的。
        toolWindow.contentManager.contents.forEach { old ->
            toolWindow.contentManager.removeContent(old, true)
        }

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
        const val TOOL_WINDOW_ID = "DSH Simple"

        /** JBCefJSQuery 回传里标记"来自 dsh 弹窗的 API Key"的前缀（与一键发送结果区分）。 */
        const val APIKEY_PREFIX = "__apikey__"
        /** JBCefJSQuery 回传里标记"拦截到文件点击，需在 IDEA 编辑器中打开"的前缀。 */
        const val OPEN_FILE_PREFIX = "__openfile__"

        /**
         * CodeBuddy 风格的高密度紧凑工作区 CSS（注入到 dsh web 页面）。
         * 把代码块、行内代码、文件链接 / 产物胶囊、消息正文与列表整体收紧，
         * 适应 IDE 工具窗的窄视口。
         *
         * 选择器说明（DSH 0.1.1-rc.2 的 CSS Modules 哈希）：
         *   代码块容器：<div class="<hash>_block_178r4_4 md-code-block">
         *   代码块顶部：<div class="<hash>_header_178r4 ...">
         *   代码块 pre：<pre class="<hash>_shiki_178r4_84 ...">
         *   行内 code 容器：<p/li> 内嵌 code（受 markdown 样式影响）
         *   文件链接：<a class="<hash>_fileMention_1r4m5_288">
         *   文件胶囊（产物）：<button class="<hash>_file_<hash>">
         *
         * CSS 变量覆盖优先于 font-size 直接赋值（DSH 用 font: var(--xxx) shorthand 强制覆盖）：
         *   --dsw-font-markdown-code-block 决定代码块字体/大小/行高。
         *   --ds-font-family-code          决定代码字体族。
         */
        private const val COMPACT_WORKSPACE_CSS =
            "/* === CodeBuddy-like high-density workspace === */\n" +
            "/* 1) 重写核心 CSS 变量 */\n" +
            ":root {\n" +
            "  --dsw-font-markdown-code-block: 11px/1.45 'JetBrains Mono', 'SF Mono', Consolas, Menlo, monospace !important;\n" +
            "  --dsl-code-block-content-font: 11px/1.45 'JetBrains Mono', 'SF Mono', Consolas, Menlo, monospace !important;\n" +
            "  --dsw-font-markdown-code: 11px/1.3 'JetBrains Mono', 'SF Mono', Consolas, Menlo, monospace !important;\n" +
            "  --dsw-font-markdown-code-block-small: 10.5px/1.3 'JetBrains Mono', 'SF Mono', Consolas, Menlo, monospace !important;\n" +
            "}\n" +
            "/* 2) 聊天流最外层间距彻底收紧（流项间距从 16px 压缩为 4px） */\n" +
            "[class*='Md3f7G_column'], [data-chat-flow] {\n" +
            "  gap: 4px !important;\n" +
            "}\n" +
            "[class*='Md3f7G_flowItem'] {\n" +
            "  margin: 0 !important;\n" +
            "}\n" +
            "/* 3) 工具调用树与连续工具调用行 (Read / Grep / Edit / Bash 连续流) 行高与间距极致紧凑 */\n" +
            "[class*='ztWv_q_callRow'], [class*='ztWv_q_subCalls'], [class*='callRow'] {\n" +
            "  margin: 0 !important;\n" +
            "  gap: 0 !important;\n" +
            "}\n" +
            "[class*='o3BgMG_root'], [class*='_root_9cl6j'] {\n" +
            "  margin: 0 !important;\n" +
            "  gap: 0 !important;\n" +
            "}\n" +
            "[class*='o3BgMG_row'], [class*='_row_9cl6j'] {\n" +
            "  min-height: 18px !important;\n" +
            "  height: 18px !important;\n" +
            "  padding: 0 4px !important;\n" +
            "  font-size: 11px !important;\n" +
            "  line-height: 18px !important;\n" +
            "  letter-spacing: -0.02em !important;\n" +
            "}\n" +
            "[class*='o3BgMG_fileLink'], [class*='_fileLink'] {\n" +
            "  font-size: 11px !important;\n" +
            "  line-height: 18px !important;\n" +
            "  letter-spacing: -0.02em !important;\n" +
            "}\n" +
            "[class*='o3BgMG_summary'], [class*='_title_9cl6j'], [class*='_leading_9cl6j'] {\n" +
            "  font-size: 11px !important;\n" +
            "  line-height: 18px !important;\n" +
            "  letter-spacing: -0.02em !important;\n" +
            "}\n" +
            "[class*='o3BgMG_sep'] {\n" +
            "  margin: 0 4px !important;\n" +
            "}\n" +
            "/* 4) Markdown 代码块 (pre / code) 极致紧凑 */\n" +
            ".md-code-block, [class*='block_'][class*='md-code-block'] {\n" +
            "  padding: 0 !important;\n" +
            "  margin: 3px 0 !important;\n" +
            "  border-radius: 5px !important;\n" +
            "}\n" +
            ".md-code-block pre, [class*='md-code-block'] pre, pre[class*='shiki'], pre[class*='plain'], pre {\n" +
            "  font-size: 11px !important;\n" +
            "  line-height: 1.45 !important;\n" +
            "  padding: 5px 8px !important;\n" +
            "  margin: 0 !important;\n" +
            "  border-radius: 5px !important;\n" +
            "  font-family: 'JetBrains Mono', 'SF Mono', Consolas, Menlo, monospace !important;\n" +
            "  letter-spacing: -0.01em !important;\n" +
            "  word-break: break-word !important;\n" +
            "  white-space: pre !important;\n" +
            "  overflow-x: auto !important;\n" +
            "}\n" +
            ".md-code-block pre code, [class*='md-code-block'] pre code, pre code {\n" +
            "  font-size: 11px !important;\n" +
            "  line-height: 1.45 !important;\n" +
            "  font-family: inherit !important;\n" +
            "  background: transparent !important;\n" +
            "  padding: 0 !important;\n" +
            "}\n" +
            "/* 5) 代码块顶部工具条 (banner / Copy 按钮) */\n" +
            "[class*='bannerWrap_'], [class*='banner_178r4'], [class*='infostring_'],\n" +
            "[class*='action_178r4'], [class*='copyButton_'], [class*='header_178r4'] {\n" +
            "  font-size: 10px !important;\n" +
            "  line-height: 14px !important;\n" +
            "  padding: 1px 5px !important;\n" +
            "  min-height: 16px !important;\n" +
            "}\n" +
            "/* 6) 用户输入气泡 (User Bubble / Ref Chip) 保持易读字号，仅收紧 padding */\n" +
            "[class*='gdEzaW_userRow'] {\n" +
            "  gap: 4px !important;\n" +
            "}\n" +
            "[class*='gdEzaW_bubble'] {\n" +
            "  padding: 8px 12px !important;\n" +
            "  border-radius: 14px !important;\n" +
            "  font-size: 13.5px !important;\n" +
            "  line-height: 20px !important;\n" +
            "  letter-spacing: 0 !important;\n" +
            "}\n" +
            "[class*='_text_1pfhk'] {\n" +
            "  font-size: 13.5px !important;\n" +
            "  line-height: 20px !important;\n" +
            "  letter-spacing: 0 !important;\n" +
            "}\n" +
            "[class*='gdEzaW_refChip'] {\n" +
            "  font-size: 12px !important;\n" +
            "  padding: 1px 5px !important;\n" +
            "  margin: 0 1px !important;\n" +
            "  letter-spacing: 0 !important;\n" +
            "}\n" +
            "/* 7) 行内 code 与文件胶囊 */\n" +
            ":not(pre) > code, p code, li code, [class*='markdown_'] :not(pre) > code {\n" +
            "  font-family: 'JetBrains Mono', 'SF Mono', Consolas, Menlo, monospace !important;\n" +
            "  font-size: 10.5px !important;\n" +
            "  line-height: 1.25 !important;\n" +
            "  padding: 0 3px !important;\n" +
            "  margin: 0 1px !important;\n" +
            "  border-radius: 3px !important;\n" +
            "  letter-spacing: -0.01em !important;\n" +
            "}\n" +
            "[class*='fileMention'], [class*='fileHeader'], [class*='filePill'],\n" +
            "[class*='file_'], [class*='pill_'] {\n" +
            "  font-size: 10.5px !important;\n" +
            "  line-height: 13px !important;\n" +
            "  padding: 0 4px !important;\n" +
            "  min-height: 14px !important;\n" +
            "  border-radius: 3px !important;\n" +
            "  letter-spacing: -0.01em !important;\n" +
            "}\n" +
            "[class*='filePath'], [class*='path_'] {\n" +
            "  font-size: 10.5px !important;\n" +
            "  line-height: 13px !important;\n" +
            "  letter-spacing: -0.01em !important;\n" +
            "}\n" +
            "/* 8) 消息正文与段落 / 列表收紧 */\n" +
            "[class*='Sxvs8a_root'], [class*='Sxvs8a_body'] {\n" +
            "  font-size: 11.5px !important;\n" +
            "  line-height: 1.4 !important;\n" +
            "  gap: 4px !important;\n" +
            "}\n" +
            "[class*='markdown_'] {\n" +
            "  font-size: 11.5px !important;\n" +
            "  line-height: 1.4 !important;\n" +
            "  letter-spacing: -0.01em !important;\n" +
            "}\n" +
            "[class*='markdown_'] p { margin: 2px 0 !important; }\n" +
            "[class*='markdown_'] ul, [class*='markdown_'] ol { margin: 1px 0 2px 0 !important; padding-left: 12px !important; }\n" +
            "[class*='markdown_'] li { margin: 0 !important; }\n" +
            "[class*='markdown_'] h1 { font-size: 13.5px !important; margin: 5px 0 2px !important; }\n" +
            "[class*='markdown_'] h2 { font-size: 12.5px !important; margin: 4px 0 2px !important; }\n" +
            "[class*='markdown_'] h3, [class*='markdown_'] h4 { font-size: 11.5px !important; margin: 3px 0 1px !important; }\n" +
            "/* 9) 重试行 / 消息 footer / 性能行 */\n" +
            "[class*='retryRow'], [class*='retrySummary'], [class*='retryText'], [class*='timeEnd'], [class*='timeStart'] {\n" +
            "  font-size: 10px !important;\n" +
            "  line-height: 13px !important;\n" +
            "  letter-spacing: -0.01em !important;\n" +
            "}\n" +
            "[class*='p-xYUq_actions'] {\n" +
            "  gap: 4px !important;\n" +
            "  min-height: 18px !important;\n" +
            "  height: auto !important;\n" +
            "}\n"

        /** 通过工具窗口主 content（index 0）查找当前项目的面板（SendSelectionAction/SendLogExplanationAction 共用）。 */
        fun find(project: Project): DshToolWindowPanel? {
            val tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
                ?: return null
            if (tw.contentManager.contentCount == 0) return null
            return tw.contentManager.getContent(0)?.component as? DshToolWindowPanel
        }
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

    /** JCEF JS 回传通道（一键发送的结果验证；须早于 loadURL 创建，null = 创建失败走乐观降级）。 */
    @Volatile
    private var jsQuery: JBCefJSQuery? = null

    /** 当前发送的等待回调（token 防旧回调串台，见 [sendQuestion]）。 */
    @Volatile
    private var pendingSend: PendingSend? = null

    /** 自动发送在途守卫（防双击/连点重复提交）。 */
    private val sending = AtomicBoolean(false)

    /** 周期性注入紧凑样式的备用定时器（onLoadEnd 错过 / SPA 路由切换时仍能生效）。 */
    @Volatile
    private var compactRetryTimer: javax.swing.Timer? = null

    /** dispose 幂等位（项目切换 content 先移除 + 项目关闭 Disposer 双路径）。 */
    private val disposed = AtomicBoolean(false)

    /** 每次发送的单调 token，用于丢弃过期回调。 */
    private val sendToken = AtomicLong(0)

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
        // 系统 node 检测（v0.1.7 起：运行时不再打包 node，改用宿主系统的 node）
        val nodeInfo = com.deepseek.harness.idea.runtime.SystemNodeLocator.resolve()
        if (nodeInfo == null) {
            showError(com.deepseek.harness.idea.runtime.SystemNodeLocator.missingMessage())
            return
        }
        if (!nodeInfo.meetsMinimum()) {
            showError("系统 Node.js ${nodeInfo.version} 过低，需要 ≥ ${com.deepseek.harness.idea.runtime.SystemNodeLocator.MIN_NODE_MAJOR}。\n请升级 Node.js LTS：https://nodejs.org/，或通过环境变量 ${com.deepseek.harness.idea.runtime.SystemNodeLocator.OVERRIDE_ENV} 指定更高版本。")
            return
        }
        val nodeFile = nodeInfo.path.toFile()
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
                // 项目根目录：作为工作空间（dsh 注册）与 DSH_HOME 隔离标识（v0.1.3-dev 切换项目修复）
                val projectRoot = project.basePath ?: ""
                // 方案 A：先更新全局 .credentials.yaml，再 ensureHome 把全局配置同步到子目录
                // （key 真源 = PasswordSafe + 全局 .credentials.yaml；不再向 dsh 进程注入
                //   DEEPSEEK_API_KEY 环境变量 —— dsh-credentials-local 的 inherited env wins 会遮蔽
                //   Web UI 写入，并使 Web UI 改 key 被 assertUnshadowed 拒绝）。
                homeManager.syncCredentials()
                homeManager.ensureHome(projectRoot)
                val homePath = homeManager.homeDir(projectRoot)
                val home = homePath.toFile()

                // dsh Web UI 改 key 监听：dsh 写当前项目 DSH_HOME/.credentials.yaml → 回写
                // PasswordSafe + 全局，使其它项目下次启动/重启全局一致（方案 B）。
                com.deepseek.harness.idea.runtime.DshCredentialsSync.register(
                    project.name, homePath.resolve(".credentials.yaml")
                )

                // dsh Web Models page 改 llm-pi-ai 节（第三方 LLM provider）监听：dsh 写当前项目
                // DSH_HOME/settings.yaml 的 llm-pi-ai: 节 → 回写到全局 settings.yaml 真源，
                // 跨项目共享第三方 provider 配置（方案 A：只做 A，不做 UI）。
                com.deepseek.harness.idea.runtime.DshSettingsSync.register(
                    project.name, homePath.resolve("settings.yaml")
                )

                // Step 3：MCP 桥接编排（bridge + mcp-ide-server + ide.yml patch）
                val bridge = DshBridgeManager(
                    project = project,
                    nodeExe = nodeFile,
                    workDir = File(projectRoot.ifEmpty { System.getProperty("user.home") }),
                    homeDir = homePath,
                )
                bridgeManager = bridge
                Disposer.register(this, bridge)

                val patchFile = waitForMcpPatch(bridge, homePath)
                val manager = DshProcessManager(
                    nodeExe = nodeFile,
                    dshBin = homeManager.dshBin().toFile(),
                    workDir = File(projectRoot.ifEmpty { System.getProperty("user.home") }),
                    homeDir = home,
                    patchFile = patchFile.toFile(),
                    projectPath = projectRoot,
                    extraEnv = buildMap {
                        put("DSH_IDE_BRIDGE_URL", bridge.bridgeUrl())
                        put("DSH_IDE_TOKEN", bridge.bridgeToken())
                        put("DSH_LOG_LEVEL", com.deepseek.harness.idea.settings.DshSettingsState.getInstance().logLevel)
                        // 注意：不再注入 DEEPSEEK_API_KEY 环境变量。dsh-credentials-local 的
                        // resolve() 是 inherited env wins；一旦注入，dsh 永远读 env 旧值，且 Web UI
                        // 改 key 会被 assertUnshadowed 拒绝。key 真源为 PasswordSafe + 全局
                        // .credentials.yaml，由 DshCredentialsSync 在 Web UI 改动时回写全局。
                    },
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
        // 幂等：项目切换时旧 content 会经 removeContent 先 dispose，随后项目关闭的
        // Disposer 链可能再次调用；用原子位防重复销毁（重复杀进程/释放名额）。
        if (!disposed.compareAndSet(false, true)) return
        // processManager/bridgeManager 已注册到 Disposer(this)，此处显式停止保证顺序
        compactRetryTimer?.stop()
        compactRetryTimer = null
        processManager?.dispose()
        processManager = null
        bridgeManager?.dispose()
        bridgeManager = null
        jsQuery?.dispose()
        jsQuery = null
        pendingSend = null
        browser?.dispose()
        browser = null
        com.deepseek.harness.idea.runtime.DshLifecycleManager.getInstance().unregisterPanel(project.name)
        com.deepseek.harness.idea.runtime.DshRuntimeRegistry.getInstance().release(project.name)
        com.deepseek.harness.idea.runtime.DshCredentialsSync.release(project.name)
        com.deepseek.harness.idea.runtime.DshSettingsSync.release(project.name)
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
                .getToolWindow(TOOL_WINDOW_ID)?.activate(null)
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
        return executeInPage(script)
    }

    /**
     * 一键发送问题到 DSH（自动提交，不等待用户确认）：
     * 1. 守卫：在途防重；DSH 未运行 → 剪贴板 + 通知；浏览器缺失 → 剪贴板 + 通知；
     * 2. 激活工具窗口并切到对话页（主 content 是第一个，避免停在日志 tab）；
     * 3. JCEF 注入：composer 填入完整问题 + 派发回车自动提交；JBCefJSQuery 回传
     *    `submitted` / `blocked` / `no-composer` 结果（无通道时乐观提示）；
     * 4. 成功 → 通知已发送；blocked → 消息留在输入框 + 提示手动回车；失败 → 剪贴板兜底。
     */
    fun sendQuestion(text: String) {
        if (!sending.compareAndSet(false, true)) {
            LOG.debug("sendQuestion already in flight; ignore duplicate click")
            return
        }
        val token = sendToken.incrementAndGet()
        pendingSend = PendingSend(token, text)
        ApplicationManager.getApplication().invokeLater {
            try {
                if (!isRunning()) {
                    copyToClipboard(text)
                    showNotification(DshBundle.message("sendLogExplanation.notRunning"))
                    return@invokeLater
                }
                if (browser == null) {
                    copyToClipboard(text)
                    showNotification(DshBundle.message("sendLogExplanation.failed"))
                    return@invokeLater
                }
                // 聚焦工具窗口并确保对话页（content 0）被选中
                val tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
                tw?.activate(null)
                tw?.contentManager?.let { cm ->
                    val main = cm.getContent(0)
                    if (main != null) cm.setSelectedContent(main, true)
                }
                val funcName = jsQuery?.getFuncName()
                val script = buildSendQuestionScript(text, funcName)
                if (!executeInPage(script)) {
                    copyToClipboard(text)
                    showNotification(DshBundle.message("sendLogExplanation.failed"))
                    return@invokeLater
                }
                if (funcName == null) {
                    // 无 JBCefJSQuery 通道：无法验证，乐观提示
                    pendingSend = null
                    showNotification(DshBundle.message("sendLogExplanation.done"))
                }
            } finally {
                sending.set(false)
            }
        }
    }

    /** 一键发送注入脚本：填 composer → 派发回车 → 轮询判定结果 → window.<funcName> 回传。 */
    private fun buildSendQuestionScript(text: String, funcName: String?): String {
        val json = escapeJs(text)
        val report = if (funcName != null) {
            "const report = (o) => { try { window.$funcName({ request: o, onSuccess: () => {}, onFailure: () => {} }); } catch (e) {} };"
        } else {
            "const report = () => {};"
        }
        return """
            (() => {
              const deadline = Date.now() + 8000;
              const text = $json;
              $report
              const tryInject = () => {
                const ta = document.querySelector('textarea');
                if (!ta) { if (Date.now() < deadline) setTimeout(tryInject, 300); else report('no-composer'); return; }
                const proto = window.HTMLTextAreaElement.prototype;
                const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
                setter.call(ta, text);
                ta.dispatchEvent(new Event('input', { bubbles: true }));
                setTimeout(() => {
                  // 回车提交（dsh composer：非 shift 的 Enter → keyboard.submit；智能体忙时入队仍送达）
                  ta.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
                  const t0 = Date.now();
                  const clickSend = () => {
                    // 仅匹配"发送"按钮（发送/发送消息）；绝不用 class 通配，避免误点运行中的"停止"按钮
                    const btn = document.querySelector('button[aria-label="Send message"], button[aria-label="发送消息"], button[aria-label="Send"], button[aria-label="发送"]');
                    if (btn && !btn.disabled) { btn.click(); return true; }
                    return false;
                  };
                  const checkCleared = () => {
                    const cur = document.querySelector('textarea');
                    return !cur || cur.value.trim() === '';
                  };
                  const poll = () => {
                    if (checkCleared()) { report('submitted'); return; }
                    if (Date.now() - t0 < 3000) { setTimeout(poll, 250); return; }
                    if (clickSend()) {
                      setTimeout(() => { report(checkCleared() ? 'submitted' : 'blocked'); }, 500);
                    } else {
                      report('blocked');
                    }
                  };
                  setTimeout(poll, 400);
                }, 0);
              };
              tryInject();
            })();
        """.trimIndent()
    }

    /** JBCefJSQuery 结果处理（EDT）。 */
    private fun handleSendOutcome(text: String, outcome: String) {
        when (outcome) {
            "submitted" -> showNotification(DshBundle.message("sendLogExplanation.done"))
            "blocked" -> showNotification(DshBundle.message("sendLogExplanation.blocked"))
            else -> { // "no-composer" / 未知 → 剪贴板兜底
                copyToClipboard(text)
                showNotification(DshBundle.message("sendLogExplanation.failed"))
            }
        }
    }

    /** 在 dsh web 页面执行 JS（成功返回 true）。 */
    private fun executeInPage(script: String): Boolean {
        val b = browser ?: return false
        val cef = try { b.cefBrowser } catch (e: Throwable) { return false }
        return try {
            val pageUrl = runCatching { cef.url }.getOrNull() ?: "about:blank"
            cef.executeJavaScript(script, pageUrl, 0)
            true
        } catch (e: Throwable) {
            LOG.warn("JCEF executeJavaScript failed", e)
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
                "DSH Simple",
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
                // JBCefJSQuery 必须在 loadURL 之前创建：CEF message router 在页面加载时把
                // window.<funcName> 注入页面；之后创建则函数不存在（自动发送无法回传结果）。
                setupJsQuery(b)
                // 页面加载完成后注入"点掉内测声明 + 捕获 API Key"脚本（CEF load handler 触发，
                // 比 onUrlReady 立即注入可靠；onUrlReady 时页面尚未加载，脚本不会执行）。
                installLoadHandler(b)
                b.loadURL(url)
                cards.show(this, CARD_BROWSER)
            } catch (e: Throwable) {
                LOG.warn("JCEF failed to load web ui", e)
                showError(buildJcefError(e))
            }
        }
    }

    /** 注册 CEF load handler：主 frame 加载完成后注入前端辅助脚本（内测声明点掉 + API Key 捕获
     *  + CodeBuddy 高密度紧凑工作区样式）。dsh-mobile-hanui 在窄视口负责侧栏→抽屉 + FAB 的改造，
     *  这里负责聊天/代码块/文件胶囊的紧凑化（独立注入，不依赖 dsh 加载任何 client plugin）。
     *
     *  注入策略：onLoadEnd 触发时执行一次；onLoadEnd 不触发 / 失败不影响其他注入，
     *  因此也加了一个备用轮询（每 2s）覆盖 SPA 路由切换与首次失败重试的场景。 */
    private fun installLoadHandler(b: JBCefBrowser) {
        try {
            // 在 CEF 导航层拦截 file:// / 外部路径，避免落到 Windows 文件关联。
            b.getJBCefClient().addRequestHandler(object : CefRequestHandlerAdapter() {
                override fun onBeforeBrowse(cefBrowser: CefBrowser, frame: org.cef.browser.CefFrame, request: CefRequest, userGesture: Boolean, isRedirect: Boolean): Boolean {
                    if (!frame.isMain) return false
                    val url = request.url ?: return false
                    if (url.startsWith("file:", ignoreCase = true)) {
                        val raw = runCatching { java.net.URI(url).path }.getOrNull()
                            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                        if (!raw.isNullOrBlank()) {
                            ApplicationManager.getApplication().invokeLater { openFileInEditor(raw) }
                            return true
                        }
                    }
                    return false
                }
            }, b.cefBrowser)
            b.getJBCefClient().addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                    if (!frame.isMain()) return
                    ApplicationManager.getApplication().invokeLater {
                        runCatching {
                            injectCompactWorkspace(b)
                            executeInPage(buildDismissNoticeScript())
                            val fn = jsQuery?.getFuncName()
                            if (fn != null) {
                                executeInPage(buildCaptureApiKeyScript(fn))
                                executeInPage(buildInterceptFileClickScript(fn, bridgeManager?.bridgeUrl(), bridgeManager?.bridgeToken()))
                            }
                        }.onFailure { LOG.warn("onLoadEnd injection failed", it) }
                    }
                }
            }, b.cefBrowser)
            // 备用：每 2s 重试一次注入，覆盖 SPA 路由切换 / onLoadEnd 错过 / 页面被覆盖的场景。
            // 脚本自身幂等（每次都会先 remove 旧 style 再 append 新 style），不会重复堆积。
            startCompactRetryTimer(b)
            LOG.info("JCEF load handler installed")
        } catch (e: Throwable) {
            LOG.warn("failed to install JCEF load handler", e)
        }
    }

    /** 周期性重试注入（备用：onLoadEnd 路径失败或 SPA 路由切换时仍能生效）。 */
    private fun startCompactRetryTimer(b: JBCefBrowser) {
        if (compactRetryTimer != null) return
        compactRetryTimer = javax.swing.Timer(2000) {
            runCatching {
                injectCompactWorkspace(b)
                val fn = jsQuery?.getFuncName()
                if (fn != null) {
                    executeInPage(buildInterceptFileClickScript(fn, bridgeManager?.bridgeUrl(), bridgeManager?.bridgeToken()))
                }
            }
        }.also { it.isRepeats = true; it.start() }
    }

    /** 真正调 dsh web 注入 compact-workspace 的入口（onLoadEnd 与轮询都用它）。 */
    private fun injectCompactWorkspace(b: JBCefBrowser) {
        val ok = executeInPage(buildCompactWorkspaceScript())
        LOG.info("compact-workspace injection: ok=$ok")
    }

    /**
     * 构建"CodeBuddy 风格高密度紧凑工作区"注入脚本（幂等；每次 onLoadEnd 重补）。
     *
     * 思路：把 dsh web 的以下元素收紧到 IDE 工具窗内合适密度：
     *   1) Markdown 代码块 (pre / .md-code-block) — 字号 12.5px / 行高 1.45 / padding 10px 12px
     *   2) 行内代码 (inline code) — 字号 11.5px / 精致胶囊
     *   3) 文件链接 / 产物胶囊 (fileMention / pill / deliverables / file pill) — 紧凑小胶囊，避免被撑成大块卡片
     *   4) 消息正文与列表 (markdown_*) — 标题/段落/列表间距整体收紧
     *   5) 代码块顶部工具条 (Copy 按钮 / 语言标识) — 字号缩小
     *
     * 不影响 dsh-mobile-hanui 的抽屉/FAB 行为；范围限定在工作区与消息流内的"信息密度"问题。
     */
    private fun buildCompactWorkspaceScript(): String {
        val css = COMPACT_WORKSPACE_CSS
        return """
        (() => {
          if (window.location.search.indexOf('mobileShell=0') >= 0) return;
          if (localStorage.getItem('dsh-idea-compact-workspace') === '0') return;
          const STYLE_ID = 'dsh-idea-compact-workspace-v1';
          const head = document.head || document.documentElement;
          if (!head) return;
          const old = document.getElementById(STYLE_ID);
          if (old) old.remove();
          const s = document.createElement('style');
          s.id = STYLE_ID;
          s.type = 'text/css';
          s.appendChild(document.createTextNode(`__CSS__`));
          head.appendChild(s);
          document.documentElement.setAttribute('data-dsh-idea-compact', '1');
        })();
        """.trimIndent()
            .replace("__CSS__", css)
    }

    /**
     * 构建捕获 dsh "Add an API key" 弹窗用户输入的 Key 的注入脚本（回传 Java 写 PasswordSafe）。
     */
    private fun buildCaptureApiKeyScript(funcName: String): String = """
            (() => {
              const TITLE = 'Add an API key to get started';
              const SAVES = ['Save and continue', '保存并继续'];
              const findInput = () => {
                const inputs = document.querySelectorAll('input');
                for (const i of inputs) {
                  const ph = ((i.placeholder || '') + ' ' + (i.getAttribute('aria-label') || '')).toLowerCase();
                  if (ph.indexOf('api key') >= 0) return i;
                }
                return null;
              };
              const report = (key) => {
                try { window.${funcName}({ request: '__apikey__' + key, onSuccess: () => {}, onFailure: () => {} }); } catch (e) {}
              };
              const iv = setInterval(() => {
                const body = document.body ? document.body.innerText : '';
                if (body.indexOf(TITLE) < 0) return;
                const input = findInput();
                if (!input) return;
                const btns = document.querySelectorAll('button');
                for (const btn of btns) {
                  const txt = (btn.textContent || '').trim();
                  if (SAVES.indexOf(txt) >= 0) {
                    btn.addEventListener('click', () => {
                      const cur = findInput();
                      const k = (cur ? cur.value : input.value).trim();
                      if (k.length >= 8) report(k);
                    }, { once: true });
                    clearInterval(iv);
                    return;
                  }
                }
              }, 500);
              setTimeout(() => clearInterval(iv), 120000);
            })();
        """.trimIndent()

    /**
     * 在 IDEA 编辑器中打开指定路径文件（支持相对路径、绝对路径与智能模糊匹配，打开前自动同步刷新 VFS）。
     */
    private fun openFileInEditor(rawPath: String) {
        // 只剥离末尾的行号标记，不能按冒号切分，否则 Windows 的 C:/ 会变成 C。
        val cleanPath = rawPath.replace('\\', '/').trim()
            .removePrefix("@")
            .replace(Regex("(?:#L?\\d+(?:-L?\\d+)?|:\\d+(?::\\d+)?)$"), "")
            .trim()
        if (cleanPath.isBlank()) return

        val base = project.basePath?.replace('\\', '/') ?: ""
        val lfs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()

        // 1. 尝试绝对路径
        var targetFile = java.io.File(cleanPath)
        var vf = if (targetFile.isAbsolute) lfs.refreshAndFindFileByIoFile(targetFile) ?: lfs.refreshAndFindFileByPath(cleanPath) else null

        // 2. 尝试基于当前项目根目录拼接
        if (vf == null && base.isNotBlank()) {
            val full = "$base/$cleanPath"
            targetFile = java.io.File(full)
            vf = lfs.refreshAndFindFileByIoFile(targetFile) ?: lfs.refreshAndFindFileByPath(full)
        }

        // 3. 尝试去除常见的首部斜杠或多余前缀
        if (vf == null && base.isNotBlank()) {
            val stripped = cleanPath.trimStart('/')
            val full = "$base/$stripped"
            targetFile = java.io.File(full)
            vf = lfs.refreshAndFindFileByIoFile(targetFile) ?: lfs.refreshAndFindFileByPath(full)
        }

        // 4. 若仍找不到，尝试在当前项目虚拟文件系统中按文件名/相对路径末尾匹配（容错多模块相对路径）
        if (vf == null && base.isNotBlank()) {
            val projectBaseVf = lfs.refreshAndFindFileByPath(base)
            if (projectBaseVf != null) {
                val fileName = cleanPath.substringAfterLast('/')
                com.intellij.openapi.vfs.VfsUtilCore.visitChildrenRecursively(projectBaseVf, object : com.intellij.openapi.vfs.VirtualFileVisitor<Void>() {
                    override fun visitFile(f: com.intellij.openapi.vfs.VirtualFile): Boolean {
                        if (vf != null) return false
                        if (!f.isDirectory) {
                            val p = f.path.replace('\\', '/')
                            if (p.endsWith(cleanPath) || f.name == fileName) {
                                vf = f
                                return false
                            }
                        }
                        return true
                    }
                })
            }
        }

        LOG.info("openFileInEditor: rawPath=$rawPath, cleanPath=$cleanPath, base=$base, resolved=${vf?.path ?: "NOT_FOUND"}")
        if (vf != null && !vf!!.isDirectory) {
            val editors = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vf!!, true)
            LOG.info("openFileInEditor: openFile called for ${vf?.path}, editors=${editors.size}")
            // 聊天点开新文件后同步刷新项目树，使其立即出现在 Project View（新增文件的目录也要展开可见）
            try {
                com.intellij.ide.projectView.ProjectView.getInstance(project).refresh()
            } catch (_: Exception) {
                LOG.warn("openFileInEditor: failed to refresh project view for ${vf?.path}")
            }
        } else {
            LOG.warn("openFileInEditor: file not found for rawPath=$rawPath (base=$base)")
        }
    }

    /**
     * 构建拦截 DSH 聊天区/工作区中文件链接与文件胶囊点击的注入脚本。
     * 点击时阻止系统默认打开方式，通过 JBCefJSQuery 回传并在 IDEA 编辑器中打开。
     */
    private fun buildInterceptFileClickScript(funcName: String, bridgeUrl: String? = null, bridgeToken: String? = null): String {
        val endpoint = escapeJs("${bridgeUrl ?: ""}/open-file")
        val token = escapeJs(bridgeToken ?: "")
        return """
            (() => {
              if (window.__dsh_idea_file_click_installed) return;
              window.__dsh_idea_file_click_installed = true;
              const report = (p) => {
                try {
                  const endpoint = $endpoint;
                  const token = $token;
                  if (endpoint && token) {
                    fetch(endpoint + '?token=' + encodeURIComponent(token), {
                      method: 'POST',
                      headers: { 'Content-Type': 'application/json' },
                      body: JSON.stringify({ path: p })
                    }).catch(() => {});
                  }
                  const value = '$OPEN_FILE_PREFIX' + p;
                  const fn = window.$funcName;
                  if (typeof fn === 'function') fn({ request: value, onSuccess: () => {}, onFailure: () => {} });
                } catch (e) { console.warn('[dsh-idea] file callback failed', e); }
              };

              // 1. 劫持前端通过 API Client 调用的 host.openPath（精准满足 DSH Unary Envelope 回包格式）
              const hookHostApi = () => {
                if (window.fetch && !window.__dsh_orig_fetch) {
                  const origFetch = window.fetch;
                  window.__dsh_orig_fetch = origFetch;
                  window.fetch = async function(resource, init) {
                    try {
                      const url = typeof resource === 'string' ? resource : (resource ? resource.url : '');
                      if (url && (url.includes('/api/host.openPath') || url.includes('/api/workspaces.openFile') || url.includes('host.openPath'))) {
                        if (init && init.body) {
                          const bodyStr = typeof init.body === 'string' ? init.body : '';
                          try {
                            const reqData = JSON.parse(bodyStr);
                            const p = (reqData && (reqData.path || (reqData.payload && reqData.payload.path))) || '';
                            if (p) {
                              report(p);
                              // 满足 serverResponseSchema: { type: "server-response", rpcId, result: { ok: true, value: { opened: true } } }
                              const rpcId = reqData.rpcId || ('rpc_' + Date.now());
                              const respObj = {
                                type: 'server-response',
                                rpcId: rpcId,
                                result: {
                                  ok: true,
                                  value: { opened: true }
                                }
                              };
                              return new Response(JSON.stringify(respObj), {
                                status: 200,
                                headers: { 'Content-Type': 'application/json' }
                              });
                            }
                          } catch (e) {}
                        }
                      }
                    } catch (e) {}
                    return origFetch.apply(this, arguments);
                  };
                }
              };
              hookHostApi();

              // 2. 捕获 DOM 中的文件链接 / 产物胶囊点击（宽容匹配各种 class 与结构）
              window.addEventListener('click', (e) => {
                const target = e.target;
                if (!target || !target.closest) return;
                const fileEl = target.closest("[data-produced-files-row='true'] button[title], button[aria-label^='Open '], button[class$='_file'], button[class*='fileMention'], a[class*='fileMention'], [class*='filePill'], [class*='filePath'], [class*='path_'], [class*='fileLink'], [class*='_fileLink'], [class*='fileHeader'], [data-file-path]");
                if (fileEl) {
                  const aria = fileEl.getAttribute('aria-label') || '';
                  const pathAttr = fileEl.getAttribute('data-file-path') || fileEl.getAttribute('data-path') || fileEl.getAttribute('title') || aria.replace(/^Open\s+/, '') || fileEl.innerText || fileEl.textContent || '';
                  const clean = pathAttr.trim().replace(/^@+/, '');
                  if (clean && (clean.includes('.') || clean.includes('/') || clean.includes('\\'))) {
                    // 捕获阶段彻底阻断 DSH 原始 host.openPath，避免继续交给 Windows 文件关联。
                    e.preventDefault();
                    e.stopPropagation();
                    e.stopImmediatePropagation();
                    console.info('[dsh-idea-file] click intercepted', { path: clean, tag: fileEl.tagName, className: fileEl.className });
                    report(clean);
                  }
                }
              }, true);
            })();
        """.trimIndent()
    }

    /**
     * 构建"自动点掉内测声明（Internal Testing Notice / 内测声明）"的注入脚本：
     * 轮询检测模态出现，点击 Continue（en）/继续（zh）按钮一次。acknowledge 后 dsh 写入
     * settings.yaml（ui-onboarding.welcomeNoticeVersion），同项目后续不再显示。失败静默。
     */
    private fun buildDismissNoticeScript(): String = """
            (() => {
              const NOTICE_TEXTS = ['Internal Testing Notice', '内测声明'];
              const BTN_TEXTS = ['Continue', '继续'];
              const clicked = () => {
                const btns = document.querySelectorAll('button');
                for (const btn of btns) {
                  const t = (btn.textContent || '').trim();
                  if (BTN_TEXTS.indexOf(t) >= 0) { btn.click(); return true; }
                }
                return false;
              };
              const iv = setInterval(() => {
                const body = document.body ? document.body.innerText : '';
                if (NOTICE_TEXTS.some((t) => body.indexOf(t) >= 0)) {
                  if (clicked()) clearInterval(iv);
                }
              }, 500);
              setTimeout(() => clearInterval(iv), 15000);
            })();
        """.trimIndent()

    /** 创建 JBCefJSQuery 结果通道（失败不阻断：自动发送降级为无验证乐观提示）。 */
    private fun setupJsQuery(b: JBCefBrowserBase) {
        try {
            // create(JBCefBrowserBase) 为跨版本主 API（create(JBCefBrowser) 已弃用且 2026.2 可能移除）
            val query = JBCefJSQuery.create(b)
            query.addHandler { payload ->
                if (payload.startsWith(OPEN_FILE_PREFIX)) {
                    // 拦截 DSH Web 中点击的文件链接 / 产物胶囊，直接在 IDEA 编辑器中打开
                    val filePath = payload.removePrefix(OPEN_FILE_PREFIX).trim()
                    LOG.info("JCEF file click callback received: path=" + filePath)
                    if (filePath.isNotBlank()) {
                        ApplicationManager.getApplication().invokeLater {
                            openFileInEditor(filePath)
                        }
                    }
                } else if (payload.startsWith(APIKEY_PREFIX)) {
                    // dsh "Add an API key" 弹窗输入的 Key：写回插件 PasswordSafe（脱敏显示 + 下次透传）
                    val key = payload.removePrefix(APIKEY_PREFIX)
                    ApplicationManager.getApplication().invokeLater {
                        if (key.isNotBlank()) {
                            runCatching { com.deepseek.harness.idea.runtime.DshCredentials.writeApiKey(key) }
                            showNotification(DshBundle.message("settings.apiKey.importedFromDsh"))
                        }
                    }
                } else {
                    val pending = pendingSend
                    if (pending != null) {
                        ApplicationManager.getApplication().invokeLater {
                            if (pendingSend === pending) {
                                pendingSend = null
                                handleSendOutcome(pending.text, payload)
                            }
                        }
                    }
                }
                JBCefJSQuery.Response("ok")
            }
            jsQuery = query
        } catch (e: Throwable) {
            LOG.warn("JBCefJSQuery unavailable; auto-send runs without result verification", e)
            jsQuery = null
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
                "DSH Simple",
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

/** 一次"一键发送"的等待态（token 用于丢弃过期回调，text 用于失败时剪贴板兜底）。 */
private class PendingSend(val token: Long, val text: String)

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
