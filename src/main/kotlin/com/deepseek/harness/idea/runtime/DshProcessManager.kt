package com.deepseek.harness.idea.runtime

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * dsh Node 子进程生命周期管理。
 *
 * 启动命令（见 docs/DESIGN.md §4.3）：
 * `node <dsh>/lib/bin.js --profile web --patch <ide.yml> --host 127.0.0.1 --port 0`
 * cwd = 项目根目录；env: DSH_HOME=<home>。
 * 端口发现：逐行解析 stdout 的 `dsh web: http://127.0.0.1:<port>`，随后 HTTP 健康检查。
 * 崩溃：指数退避自动重启（≤3 次）；dispose() 时终止进程树。
 */
class DshProcessManager(
    private val nodeExe: File,
    private val dshBin: File,
    private val workDir: File,
    private val homeDir: File,
    private val patchFile: File,
    private val extraEnv: Map<String, String> = emptyMap(),
    /** 项目根目录绝对路径（用于启动后注册默认工作区，FR-04.2）。 */
    private val projectPath: String = "",
) : Disposable {

    enum class State { STOPPED, STARTING, RUNNING, CRASHED }

    interface Listener {
        fun onStateChanged(oldState: State, newState: State) = Unit
        fun onUrlReady(url: String) = Unit
        fun onLogLine(line: String) = Unit
    }

    companion object {
        private val LOG = Logger.getInstance(DshProcessManager::class.java)
        private const val MAX_RESTART_ATTEMPTS = 3
        private val RESTART_DELAYS_MS = longArrayOf(500, 2000, 5000)
        private const val HEALTH_MAX_TRIES = 20
        private const val HEALTH_INTERVAL_MS = 500L
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(2) { r ->
        Thread(r, "dsh-process").apply { isDaemon = true }
    }
    private val stopRequested = AtomicBoolean(false)
    private val restartScheduled = AtomicBoolean(false)

    @Volatile private var process: Process? = null
    @Volatile private var currentState: State = State.STOPPED
    @Volatile private var webPort: Int? = null
    /** dsh 0.1.2+ 启动时携带 ?token= 的完整 URL；浏览器加载与健康检查都要用它 */
    @Volatile private var webUrl: String? = null
    /** dsh 0.1.2+ BrowserAuth：换到的 cookie，给 workspace 初始化器等 RPC 调用复用 */
    @Volatile private var browserAuth: DshBrowserAuth? = null
    /** 从 URL 里 抽出来的 launchToken（不直接存 URL，避免泄漏给非必要调用方） */
    @Volatile private var launchToken: String? = null
    private var restartAttempts = 0

    fun currentState(): State = currentState
    fun webPort(): Int? = webPort
    fun webUrl(): String? = webUrl ?: webPort()?.let { "http://127.0.0.1:$it" }

    /** 已认证的 dsh BrowserAuth 实例；null = 未就绪 / 旧版本无 auth */
    fun browserAuth(): DshBrowserAuth? = browserAuth

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onStateChanged(State.STOPPED, currentState)
    }

    /** 从 STOPPED/CRASHED 启动。 */
    fun start() {
        synchronized(this) {
            if (currentState == State.RUNNING || currentState == State.STARTING) return
            setState(State.STARTING)
        }
        spawn()
    }

    /** 手动重启：无论当前状态，先停旧进程再拉起。 */
    fun restart() {
        synchronized(this) {
            stopProcessQuietly()
            restartAttempts = 0
            setState(State.STARTING)
        }
        spawn()
    }

    override fun dispose() {
        stopRequested.set(true)
        stopProcessQuietly()
        executor.shutdownNow()
    }

    // ---- 内部实现 ----

    private fun spawn() {
        // 注意：--patch 是启动器（launcher）选项，必须位于 web 应用自己的
        // --host/--port 之前；放在后面会被 web 应用当成未知选项拒绝。
        val cmd = listOf(
            nodeExe.absolutePath,
            dshBin.absolutePath,
            "--profile", "web",
            "--patch", patchFile.absolutePath,
            "--host", "127.0.0.1",
            "--port", "0",
            // dsh web 默认会把 Web UI 打开到系统默认浏览器；内嵌于 IDE 工具窗内，
            // 无需（也不应）弹外部浏览器 —— 显式禁用。
            "--no-open",
        )
        val pb = ProcessBuilder(cmd)
        pb.directory(workDir)
        pb.environment()["DSH_HOME"] = homeDir.absolutePath
        extraEnv.forEach { (k, v) -> pb.environment()[k] = v }
        pb.redirectErrorStream(true)

        val p = try {
            pb.start()
        } catch (e: Exception) {
            LOG.error("failed to start dsh process", e)
            synchronized(this) { setState(State.CRASHED) }
            scheduleRestart()
            return
        }
        process = p
        webPort = null
        webUrl = null
        browserAuth = null
        launchToken = null
        LOG.info("dsh process started pid=${p.pid()} cwd=${workDir.absolutePath} home=$homeDir")
        readAsync(p.inputStream)
        p.onExit().whenComplete { _, err -> onProcessExit(p, err) }
    }

    private fun readAsync(stream: InputStream) {
        executor.execute {
            stream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    if (line.contains("dsh web:")) {
                        LOG.info("[dsh] $line")
                    } else {
                        LOG.debug("[dsh] $line")
                    }
                    listeners.forEach { it.onLogLine(line) }
                    // 先尝试解析完整 URL（含 ?token=），仅回退到纯端口
                    val fullUrl = PortParser.parseUrl(line)
                    val port = PortParser.parsePort(line)
                    if (fullUrl != null || port != null) onUrlFound(port, fullUrl)
                }
            }
        }
    }

    private fun onUrlFound(port: Int?, fullUrl: String?) {
        if (webPort != null || stopRequested.get()) return
        val p = port ?: return
        webPort = p
        // 完整 URL 优先（含 dsh 0.1.2+ 的启动 token）；缺失时降级到无 token URL
        val resolvedUrl = fullUrl ?: "http://127.0.0.1:$p/"
        webUrl = resolvedUrl
        // 抽 token（dsh 0.1.2+ 才有），并实例化 BrowserAuth
        val token = extractToken(resolvedUrl)
        launchToken = token
        browserAuth = if (token != null) DshBrowserAuth("http://127.0.0.1:$p") else null
        executor.execute { waitHealthy(p) }
    }

    /** 提取 URL 中的 ?token= 值；返回 null 表示旧版本（无 token） */
    private fun extractToken(url: String): String? {
        val q = url.substringAfter('?', missingDelimiterValue = "")
        if (q.isEmpty()) return null
        return q.split('&').mapNotNull { pair ->
            val parts = pair.split('=', limit = 2)
            if (parts.size == 2 && parts[0] == "token") parts[1] else null
        }.firstOrNull()
    }

    private fun waitHealthy(port: Int) {
        val auth = browserAuth
        val token = launchToken
        // dsh 0.1.2+：先换 cookie，再用 cookie 做健康检查
        // dsh 0.1.2+: workspace.create needs a valid cookie. Block on BrowserAuth readiness,
        // otherwise the subsequent workspace RPC uses an unauthenticated connection and dsh rejects it with 401,
        // causing "DSH started but workspace not injected".
        if (auth != null && token != null) {
            for (attempt in 0 until HEALTH_MAX_TRIES) {
                if (auth.isReady) break
                if (stopRequested.get()) return
                if (auth.authenticate(token)) {
                    LOG.info("dsh BrowserAuth: cookie obtained for workspace injection")
                    break
                }
                LOG.warn("dsh BrowserAuth: token exchange failed (attempt " + (attempt + 1) + "), retrying...")
                try { Thread.sleep(HEALTH_INTERVAL_MS) } catch (_: InterruptedException) { return }
            }
            if (!auth.isReady) {
                LOG.warn("dsh BrowserAuth: gave up after retries; workspace injection will likely fail")
            }
        }
        repeat(HEALTH_MAX_TRIES) {
            if (stopRequested.get()) return
            if (isHealthy()) {
                synchronized(this) {
                    restartScheduled.set(false)
                    restartAttempts = 0
                    setState(State.RUNNING)
                }
                val readyUrl = webUrl ?: "http://127.0.0.1:$port/"
                LOG.info("dsh web ready: $readyUrl")
                listeners.forEach { it.onUrlReady(readyUrl) }
                // FR-04.2：把项目根注册为默认工作区（幂等；失败仅降级，不阻塞 UI）
                if (projectPath.isNotBlank()) {
                    WorkspaceInitializer.ensureWorkspace(readyUrl, projectPath, browserAuth)
                }
                return
            }
            try { Thread.sleep(HEALTH_INTERVAL_MS) } catch (_: InterruptedException) { return }
        }
        LOG.warn("dsh health check timed out on port $port")
        if (!stopRequested.get()) {
            synchronized(this) { setState(State.CRASHED) }
            scheduleRestart()
        }
    }

    private fun isHealthy(): Boolean = try {
        val auth = browserAuth
        val conn = if (auth != null && auth.isReady) {
            // dsh 0.1.2+：带 cookie 健康检查（GET / 期望 303 redirect-loopback 或 200）
            auth.open("/")
        } else {
            // 旧版本或 token 尚未换到：直接 GET /
            val url = webUrl?.removeSuffix("/") ?: webPort()?.let { "http://127.0.0.1:$it" } ?: return false
            URL(url).openConnection() as HttpURLConnection
        }
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        conn.requestMethod = "GET"
        val code = conn.responseCode
        conn.disconnect()
        code in 200..399
    } catch (_: Exception) {
        false
    }

    private fun onProcessExit(p: Process, err: Throwable?) {
        if (process !== p) return
        process = null
        if (stopRequested.get()) {
            synchronized(this) { setState(State.STOPPED) }
        } else {
            LOG.warn("dsh process exited unexpectedly pid=${p.pid()}", err)
            synchronized(this) { setState(State.CRASHED) }
            scheduleRestart()
        }
    }

    private fun scheduleRestart() {
        if (stopRequested.get()) return
        synchronized(this) {
            if (restartScheduled.get()) return
            if (restartAttempts >= MAX_RESTART_ATTEMPTS) return // 停在 CRASHED，等用户手动重启
            restartAttempts++
            restartScheduled.set(true)
        }
        val delay = RESTART_DELAYS_MS[(restartAttempts - 1).coerceAtMost(RESTART_DELAYS_MS.size - 1)]
        LOG.info("scheduling dsh restart attempt $restartAttempts in ${delay}ms")
        executor.schedule({
            restartScheduled.set(false)
            if (stopRequested.get()) return@schedule
            synchronized(this) { setState(State.STARTING) }
            spawn()
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun stopProcessQuietly() {
        val p = process ?: run { synchronized(this) { setState(State.STOPPED) }; return }
        process = null
        try {
            p.destroy()
            p.waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        if (p.isAlive) killTree(p.pid())
        synchronized(this) { setState(State.STOPPED) }
    }

    private fun killTree(pid: Long) {
        try {
            val os = System.getProperty("os.name", "").lowercase()
            val cmd = if (os.contains("win")) listOf("taskkill", "/PID", pid.toString(), "/T", "/F")
            else listOf("kill", "-9", pid.toString())
            ProcessBuilder(cmd).start().waitFor(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            LOG.warn("failed to kill process tree $pid", e)
        }
    }

    private fun setState(newState: State) {
        val old = currentState
        if (old == newState) return
        currentState = newState
        listeners.forEach { it.onStateChanged(old, newState) }
    }
}
