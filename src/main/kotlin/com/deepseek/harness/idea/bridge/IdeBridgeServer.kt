package com.deepseek.harness.idea.bridge

import com.deepseek.harness.idea.util.JsonCodec
import com.intellij.ide.projectView.ProjectView
import com.intellij.lang.LanguageUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * IDE Bridge Server（Step 3，见 docs/DESIGN.md §3.5/§4.1）。
 *
 * JDK HttpServer，绑定 127.0.0.1 随机端口；请求头 `X-DSH-IDE-Token` 常量时间鉴权。
 * VFS/PSI 读取经 [ReadAction]（桥接请求在后台线程，安全）；文件打开/定位经 EDT。
 * 生命周期与 Node 实例同生命周期（Disposable）；token 每次启动随机。
 */
class IdeBridgeServer(
    private val project: Project,
    private val token: String,
) : Disposable, HttpHandler {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "dsh-ide-bridge").apply { isDaemon = true } }
    private val disposed = AtomicBoolean(false)

    /** sent-selection 环形队列（容量 ≤10，单条 ≤64KB） */
    private val sentQueue = SentSelectionQueue()

    init {
        server.createContext("/", this)
        server.executor = executor
        server.start()
    }

    /** 实际监听端口（随机分配） */
    fun port(): Int = server.address.port

    fun baseUrl(): String = "http://127.0.0.1:${port()}"

    fun token(): String = token

    /** 主动推送选中代码（Step 4 编辑器动作调用），不经过 HTTP。 */
    fun pushSentSelection(filePath: String?, language: String?, selection: String, lineStart: Int = 0, lineEnd: Int = 0): String =
        sentQueue.push(filePath, language, selection, lineStart, lineEnd)

    override fun handle(exchange: HttpExchange) {
        try {
            LOG.info("IDE bridge request: method=" + exchange.requestMethod + ", path=" + exchange.requestURI + ", origin=" + (exchange.requestHeaders.getFirst("Origin") ?: "none"))
            exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
            exchange.responseHeaders.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            exchange.responseHeaders.set("Access-Control-Allow-Headers", "Content-Type, X-DSH-IDE-Token")
            if (exchange.requestMethod == "OPTIONS") {
                exchange.sendResponseHeaders(204, -1)
                return
            }
            val queryToken = exchange.requestURI.rawQuery?.split("&")?.firstOrNull { it.startsWith("token=") }?.substringAfter("token=")
            val authenticated = constantTimeEquals(exchange.requestHeaders.getFirst("X-DSH-IDE-Token") ?: queryToken, token)
            LOG.info("IDE bridge auth=" + authenticated + ", tokenSource=" + if (exchange.requestHeaders.getFirst("X-DSH-IDE-Token") != null) "header" else "query")
            if (!authenticated) {
                json(exchange, 401, mapOf("error" to "unauthorized", "code" to "unauthorized"))
                return
            }
            val path = exchange.requestURI.path
            when {
                exchange.requestMethod == "GET" && path == "/health" -> health(exchange)
                exchange.requestMethod == "GET" && path == "/selection" -> selection(exchange)
                exchange.requestMethod == "GET" && path == "/open-files" -> openFiles(exchange)
                exchange.requestMethod == "GET" && path == "/project-tree" -> projectTree(exchange)
                exchange.requestMethod == "GET" && path == "/sent-selection" -> latestSentSelection(exchange)
                exchange.requestMethod == "POST" && path == "/sent-selection" -> postSentSelection(exchange)
                exchange.requestMethod == "POST" && path == "/open-file" -> openFile(exchange)
                exchange.requestMethod == "POST" && path == "/reveal" -> reveal(exchange)
                exchange.requestMethod == "POST" && path == "/refresh" -> refresh(exchange)
                else -> json(exchange, 404, mapOf("error" to "not found", "code" to "not_found"))
            }
        } catch (e: Exception) {
            LOG.warn("bridge handler error", e)
            try { json(exchange, 500, mapOf("error" to (e.message ?: "internal error"), "code" to "internal")) } catch (_: IOException) {}
        } finally {
            exchange.close()
        }
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        server.stop(0)
        executor.shutdownNow()
    }

    // ---- 端点 ----

    private fun health(exchange: HttpExchange) {
        json(exchange, 200, mapOf("ok" to true, "project" to project.name, "pid" to ProcessHandle.current().pid()))
    }

    private fun selection(exchange: HttpExchange) {
        val editor = currentEditor()
        if (editor == null) {
            json(exchange, 200, mapOf("filePath" to null, "language" to null, "selection" to "", "lineStart" to 0, "lineEnd" to 0, "projectName" to project.name))
            return
        }
        val result = ReadAction.compute<Map<String, Any?>, Throwable> {
            val doc = editor.document
            val vf = FileDocumentManager.getInstance().getFile(doc)
            val sel = editor.selectionModel
            mapOf(
                "filePath" to vf?.path,
                "language" to vf?.let { languageOf(it) },
                "selection" to (if (sel.hasSelection()) sel.selectedText ?: "" else ""),
                "lineStart" to (if (sel.hasSelection()) doc.getLineNumber(sel.selectionStart) + 1 else 0),
                "lineEnd" to (if (sel.hasSelection()) doc.getLineNumber(sel.selectionEnd) + 1 else 0),
                "projectName" to project.name,
            )
        }
        json(exchange, 200, result)
    }

    private fun openFiles(exchange: HttpExchange) {
        val files = ReadAction.compute<List<Map<String, Any?>>, Throwable> {
            FileDocumentManager.getInstance().getUnsavedDocuments().map { doc ->
                val vf = FileDocumentManager.getInstance().getFile(doc)
                mapOf(
                    "path" to vf?.path,
                    "language" to vf?.let { languageOf(it) },
                    "modified" to FileDocumentManager.getInstance().isDocumentUnsaved(doc),
                )
            }
        }
        json(exchange, 200, mapOf("files" to files))
    }

    private fun projectTree(exchange: HttpExchange) {
        val depth = exchange.requestURI.query
            ?.split('&')
            ?.firstOrNull { it.startsWith("depth=") }
            ?.substringAfter("depth=")
            ?.toIntOrNull()
            ?.coerceIn(1, 10)
            ?: 4
        val basePath = project.basePath
        if (basePath == null) {
            json(exchange, 200, mapOf("roots" to emptyList<Any>()))
            return
        }
        val root = ReadAction.compute<Map<String, Any?>?, Throwable> {
            LocalFileSystem.getInstance().findFileByPath(basePath)?.let { buildNode(it, depth, 0) }
        }
        json(exchange, 200, mapOf("roots" to listOf(root ?: emptyMap<String, Any?>())))
    }

    private fun latestSentSelection(exchange: HttpExchange) {
        val latest = sentQueue.latest()
        if (latest == null) {
            json(exchange, 200, mapOf("error" to "empty", "code" to "empty"))
        } else {
            json(exchange, 200, latest.toMap())
        }
    }

    private fun postSentSelection(exchange: HttpExchange) {
        val body = readBody(exchange)
        val id = pushSentSelection(
            filePath = body["filePath"] as? String,
            language = body["language"] as? String,
            selection = body["selection"] as? String ?: "",
            lineStart = (body["lineStart"] as? Number)?.toInt() ?: 0,
            lineEnd = (body["lineEnd"] as? Number)?.toInt() ?: 0,
        )
        json(exchange, 200, mapOf("id" to id))
    }

    private fun openFile(exchange: HttpExchange) {
        val body = readBody(exchange)
        val path = body["path"] as? String
        LOG.info("IDE bridge /open-file path=" + path)
        if (path.isNullOrBlank()) {
            json(exchange, 400, mapOf("error" to "path required", "code" to "bad_request"))
            return
        }
        var found = false
        ApplicationManager.getApplication().invokeAndWait {
            val vf = refreshPath(path)
            found = vf != null
            LOG.info("IDE bridge /open-file resolved=" + (vf?.path ?: "NOT_FOUND"))
            if (vf != null) {
                val editors = FileEditorManager.getInstance(project).openFile(vf, true)
                LOG.info("IDE bridge /open-file openFile called, editors=" + editors.size)
            }
        }
        if (found) json(exchange, 200, mapOf("ok" to true, "path" to path))
        else json(exchange, 404, mapOf("ok" to false, "error" to "file not found", "code" to "file_not_found", "path" to path))
    }

    private fun reveal(exchange: HttpExchange) {
        val body = readBody(exchange)
        val path = body["path"] as? String
        if (path.isNullOrBlank()) {
            json(exchange, 400, mapOf("error" to "path required", "code" to "bad_request"))
            return
        }
        var found = false
        ApplicationManager.getApplication().invokeAndWait {
            val vf = refreshPath(path)
            found = vf != null
            if (vf != null) FileEditorManager.getInstance(project).openFile(vf, true)
        }
        if (found) json(exchange, 200, mapOf("ok" to true, "path" to path))
        else json(exchange, 404, mapOf("ok" to false, "error" to "file not found", "code" to "file_not_found", "path" to path))
    }

    private fun refresh(exchange: HttpExchange) {
        val body = readBody(exchange)
        val requested = (body["paths"] as? List<*>)?.filterIsInstance<String>()?.filter { it.isNotBlank() } ?: emptyList()
        val paths = if (requested.isEmpty()) listOfNotNull(project.basePath) else requested
        val refreshed = mutableListOf<String>()
        val missing = mutableListOf<String>()
        ApplicationManager.getApplication().invokeAndWait {
            for (path in paths) {
                if (!isProjectPath(path)) {
                    missing += path
                    continue
                }
                if (refreshPath(path) != null) refreshed += path else missing += path
            }
            // 新增/删除文件后同步刷新项目树（Project View），否则新文件不会立即出现在项目树中，
            // 需用户手动刷新。ProjectView.refresh() 必须在 EDT 调用。
            refreshProjectView()
        }
        json(exchange, 200, mapOf("ok" to true, "refreshed" to refreshed, "missing" to missing))
    }

    // ---- 辅助 ----

    /** 同步刷新磁盘路径对应的 VFS 文件/目录，必须在 EDT 执行。 */
    private fun refreshPath(path: String): VirtualFile? {
        val normalized = path.replace('\\', '/')
        val candidates = buildList {
            add(normalized)
            val base = project.basePath?.replace('\\', '/')
            if (!File(normalized).isAbsolute && !base.isNullOrBlank()) add("$base/${normalized.trimStart('/')}")
        }
        val vf = candidates.asSequence()
            .mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) }
            .firstOrNull() ?: return null
        VfsUtil.markDirtyAndRefresh(false, vf.isDirectory, true, vf)
        return vf
    }

    /** 刷新 Project View 树，使新增/删除的文件/目录立即出现在项目树中。必须在 EDT 调用。 */
    private fun refreshProjectView() {
        try {
            ProjectView.getInstance(project).refresh()
        } catch (e: Exception) {
            LOG.warn("failed to refresh project view", e)
        }
    }

    private fun isProjectPath(path: String): Boolean = try {
        val base = project.basePath ?: return false
        File(path).canonicalFile.toPath().startsWith(File(base).canonicalFile.toPath())
    } catch (_: IOException) {
        false
    }

    /** 当前活动编辑器（选中的文件编辑器；仅 TextEditor 有 Editor 句柄）。 */
    private fun currentEditor(): Editor? {
        val selected = FileEditorManager.getInstance(project).selectedEditor ?: return null
        return (selected as? TextEditor)?.editor
    }

    private fun buildNode(vf: VirtualFile, maxDepth: Int, depth: Int): Map<String, Any?> {
        val isDir = vf.isDirectory
        val children = if (isDir && depth < maxDepth) {
            vf.children
                .asSequence()
                .filter { !IGNORED.contains(it.name) && !it.name.startsWith(".") }
                .sortedWith(compareBy({ if (it.isDirectory) 0 else 1 }, { it.name }))
                .take(200)
                .map { buildNode(it, maxDepth, depth + 1) }
                .toList()
        } else emptyList()
        return mapOf(
            "path" to vf.path,
            "name" to vf.name,
            "type" to if (isDir) "dir" else "file",
            "children" to children,
        )
    }

    private fun languageOf(vf: VirtualFile): String? =
        LanguageUtil.getLanguageForPsi(project, vf)?.id?.takeIf { it.isNotBlank() } ?: vf.extension

    private fun readBody(exchange: HttpExchange): Map<String, Any?> {
        val raw = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        return try {
            JsonCodec.decodeObject(raw)
        } catch (e: Exception) {
            LOG.warn("bad json body", e)
            emptyMap()
        }
    }

    private fun json(exchange: HttpExchange, status: Int, body: Any) {
        val bytes = JsonCodec.encode(body).toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun constantTimeEquals(a: String?, b: String): Boolean {
        if (a == null) return false
        val digestA = MessageDigest.getInstance("SHA-256").digest(a.toByteArray(StandardCharsets.UTF_8))
        val digestB = MessageDigest.getInstance("SHA-256").digest(b.toByteArray(StandardCharsets.UTF_8))
        return MessageDigest.isEqual(digestA, digestB)
    }

    private fun SentSelectionQueue.Item.toMap(): Map<String, Any?> = mapOf(
        "id" to id, "filePath" to filePath, "language" to language,
        "selection" to selection, "lineStart" to lineStart, "lineEnd" to lineEnd, "ts" to ts,
    )

    companion object {
        private val LOG = Logger.getInstance(IdeBridgeServer::class.java)
        private val IGNORED = setOf("node_modules", ".git", "build", "out", ".idea", "target", "dist", ".gradle")

        fun randomToken(): String {
            val bytes = ByteArray(32)
            java.security.SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** 工具窗口/动作上下文持有 project；此处供需要活动项目的调用点使用。 */
        fun forActiveProject(): Project? =
            ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }
    }
}
