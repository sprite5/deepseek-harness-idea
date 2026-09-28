package com.deepseek.harness.idea.runtime

import com.deepseek.harness.idea.util.JsonCodec
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * 工作区预注册（Step 5 手工测试反馈：dsh 启动后 UI 默认工作区不是当前项目）。
 *
 * dsh 的 workspace 是显式注册制：`storages/workspace.json` 没有记录时，UI 显示
 * "选择一个工作区开始"。插件在 dsh 健康检查通过后调用内部 RPC
 * `POST /api/workspace/create`（payload `{request: {path}}`）把项目根注册为工作区（幂等：
 * 同路径重复调用返回既有实体），使 UI 一打开即默认选中当前项目。
 *
 * **切换项目修复（v0.1.3-dev 实测）**：`workspace/create` 幂等、**不改变**注册表
 * 显示顺序（workspace.json `workspaceIds`）；UI 侧边栏/新建会话选择器按该顺序显示，
 * 默认落点 = 列表第一个 workspace。因此切换项目后新项目仍是既有实体时，UI 默认仍
 * 落在旧项目 → 新建会话绑定旧项目工作区。修复：create 成功后调用
 * `POST /api/workspace/insertBefore`（payload `{request: {workspaceId, beforeWorkspaceId}}`，
 * 把当前项目挪到列表最前。
 *
 * 实测：
 * - dsh 0.1.0-rc.7 ~ 0.1.1-rc.2：127.0.0.1 loopback 信任围栏放行，无需鉴权头
 * - dsh 0.1.2-rc.1+（当前 0.1.7-rc.2）：BrowserAuth，所有 `api` RPC 也要带 cookie（见 `DshBrowserAuth`）
 * - dsh 0.1.5-rc.2：`workspace/create` 返回 `{workspace, workspaceIds}`，顺序随 create 直接拿到
 * - dsh 0.1.7-rc.2：create 返回值变为 `{created, workspace}`（不再带顺序），且顺序查询
 *   remote 被移除（`workspace/list` 变成目录浏览器，404）→ 当前顺序从
 *   `DSH_HOME/storages/workspace.json` 的 `global.workspaceIds` 读取；
 *   **`insertBefore` 无锚点语义是"追加到末尾"**（实测），必须带 `beforeWorkspaceId` 才能挪到最前
 * - RPC gateway 要求 payload 结构为 `{type: "client-request", rpcId, method, payload: {args: {request: {...}}}}`
 */
object WorkspaceInitializer {

    private val LOG = Logger.getInstance(WorkspaceInitializer::class.java)

    /**
     * 调用 workspace.create + 把当前项目挪到显示顺序最前；成功返回 true。
     * 任一步失败不抛出（日志降级，UI 仍可用；最坏回退到旧行为）。
     * @param auth dsh 0.1.2+ 的 BrowserAuth 实例（已换到 cookie）；旧版本传 null
     * @param homeDir DSH_HOME（可选）。0.1.7 起 workspace 顺序查询 remote 被移除
     *   （`workspace/list` 变成了目录浏览器），挪到最前所需的当前顺序改从这里
     *   `storages/workspace.json` 读取。
     */
    fun ensureWorkspace(webUrl: String, projectPath: String, auth: DshBrowserAuth? = null, homeDir: File? = null): Boolean {
        if (projectPath.isBlank()) return false
        return try {
            val base = webUrl.trimEnd('/')
            val path = projectPath.replace('\\', '/')
            // 1. 注册/复用当前项目 workspace（幂等）
            val created = rpc(base, "workspace/create", mapOf("request" to mapOf("path" to path)), auth)
            if (!created.ok) {
                LOG.warn("workspace.create failed: ${created.errorText}")
                return false
            }
            // 2. 挪到最前：UI 默认落点 = 列表第一个 workspace
            val workspace = created.value["workspace"] as? Map<*, *>
            val workspaceId = workspace?.get("workspaceId") as? String
            val workspaceIds = (created.value["workspaceIds"] as? List<*>)
                ?.mapNotNull { it as? String }
                .orEmpty()
            if (workspaceId != null) {
                bringToFront(base, workspaceId, workspaceIds, auth, homeDir)
            }
            LOG.info("workspace.ensureWorkspace ok for $projectPath")
            true
        } catch (e: Exception) {
            LOG.warn("workspace.ensureWorkspace error for $projectPath", e)
            false
        }
    }

    /**
     * 纯逻辑（可单测）：给定当前 workspace 显示顺序与目标 id，
     * 返回 `(workspaceId, beforeWorkspaceId)` 使目标插到最前；已在最前/列表为空 → null。
     */
    fun computeBringToFront(currentOrder: List<String>, targetId: String): Pair<String, String>? {
        val first = currentOrder.firstOrNull() ?: return null
        return if (first == targetId) null else targetId to first
    }

    // ---- 内部实现 ----

    /** workspace.insertBefore：把 [workspaceId] 挪到显示顺序最前。 */
    private fun bringToFront(base: String, workspaceId: String, currentOrder: List<String>, auth: DshBrowserAuth?, homeDir: File?) {
        // 顺序来源（按 0.1.5 → 0.1.7 演进）：
        // 1. workspace/create 的返回值（0.1.5 带 workspaceIds；0.1.7 改为 {created, workspace}，不再带）
        // 2. workspace/list RPC（0.1.5 存在；0.1.7 该 remote 被目录浏览器占用，404）
        // 3. DSH_HOME/storages/workspace.json（0.1.7，结构实测：global.workspaceIds）
        val order = when {
            currentOrder.isNotEmpty() -> currentOrder
            else -> rpcWorkspaceList(base, auth) ?: readStoredOrder(homeDir).orEmpty()
        }
        val move = computeBringToFront(order, workspaceId)
        val targetPayload = if (move != null) {
            mapOf("workspaceId" to move.first, "beforeWorkspaceId" to move.second)
        } else if (order.isEmpty()) {
            // 如果列表为空，直接尝试挪动
            mapOf("workspaceId" to workspaceId)
        } else {
            return // 已在最前
        }
        val moved = rpc(
            base,
            "workspace/insertBefore",
            mapOf("request" to targetPayload),
            auth,
        )
        if (moved.ok) {
            LOG.info("workspace $workspaceId moved to front (order=${moved.value["workspaceIds"]})")
        } else {
            LOG.warn("workspace.insertBefore failed: ${moved.errorText}")
        }
    }

    /** 0.1.5 的 workspace/list fallback（0.1.7 已无此 remote，失败时返回 null 走存储读取）。 */
    private fun rpcWorkspaceList(base: String, auth: DshBrowserAuth?): List<String>? {
        val list = rpc(base, "workspace/list", emptyMap(), auth)
        if (!list.ok) return null
        return (list.value["items"] as? List<*>)
            ?.mapNotNull { (it as? Map<*, *>)?.get("workspaceId") as? String }
    }

    /** 从 DSH_HOME/storages/workspace.json 读当前显示顺序（0.1.7 实测结构：`global.workspaceIds`）。 */
    private fun readStoredOrder(homeDir: File?): List<String>? {
        if (homeDir == null) return null
        return try {
            val file = homeDir.resolve("storages").resolve("workspace.json")
            if (!file.isFile) return null
            val root = JsonCodec.decodeObject(file.readText())
            val global = root["global"] as? Map<*, *> ?: return null
            (global["workspaceIds"] as? List<*>)?.mapNotNull { it as? String }
        } catch (e: Exception) {
            LOG.warn("failed to read workspace order from storage", e)
            null
        }
    }

    private data class RpcResult(
        val ok: Boolean,
        val value: Map<String, Any?> = emptyMap(),
        val errorText: String = "",
    )

    /** 调用 dsh 内部 RPC（client-request 封装）；解析 `{ok, value?, error?}`。 */
    private fun rpc(base: String, method: String, payload: Map<String, Any?>, auth: DshBrowserAuth?): RpcResult {
        val rpcId = "dsh-idea-" + UUID.randomUUID().toString()
        val body = gson(
            mapOf(
                "type" to "client-request",
                "rpcId" to rpcId,
                "method" to method,
                "payload" to mapOf("args" to payload),
            )
        )
        val conn = if (auth != null) auth.open("/api/$method", "POST", body)
        else URL("$base/api/$method").openConnection() as HttpURLConnection
        try {
            if (auth == null) {
                conn.requestMethod = "POST"
                conn.connectTimeout = 5000
                conn.readTimeout = 8000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = conn.responseCode
            val resp = if (code in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            }
            if (code !in 200..299) return RpcResult(false, errorText = "http $code: ${resp.take(200)}")
            val parsed = runCatching { JsonCodec.decodeObject(resp) }.getOrNull()
                ?: return RpcResult(false, errorText = "unparseable response: ${resp.take(200)}")
            // dsh RPC 响应结构：{"type":"server-response","rpcId":"...","result":{"ok":...,"value":...|"error":...}}
            val result = parsed["result"] as? Map<*, *>
                ?: return RpcResult(false, errorText = "unexpected response: ${resp.take(200)}")
            return if (result["ok"] == true) {
                RpcResult(true, (result["value"] as? Map<*, *>)?.let { cast(it) } ?: emptyMap())
            } else {
                val errObj = result["error"]
                val errMsg = if (errObj is Map<*, *>) errObj["message"] as? String ?: errObj.toString() else errObj as? String ?: resp.take(200)
                RpcResult(false, errorText = errMsg)
            }
        } finally {
            conn.disconnect()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun cast(m: Map<*, *>): Map<String, Any?> = m as Map<String, Any?>

    private fun gson(payload: Map<String, Any?>): String {
        val sb = StringBuilder("{")
        payload.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(',')
            sb.append('"').append(k).append("\":")
            when (v) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    sb.append(gson(v as Map<String, Any?>))
                }
                is String -> sb.append('"').append(escape(v)).append('"')
                else -> sb.append(v)
            }
        }
        return sb.append('}').toString()
    }

    private fun escape(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }
}
