package com.deepseek.harness.idea.runtime

import com.deepseek.harness.idea.bridge.IdeBridgeResources
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * DSH 运行时与 DSH_HOME 管理（应用级服务）。
 *
 * 目录布局（见 docs/DESIGN.md §4.4）：
 * - 运行时根：环境变量 `DSH_IDEA_RUNTIME`（开发态覆盖）或
 *   `<config>/dsh-idea/runtime/<version>`（生产态，Step 5 从插件资源解压）。
 *   内含 `node/`（Node.js）与 `dsh/`（npm 安装的 @deepseek-ai/dsh 树）。
 * - DSH_HOME：`<config>/dsh-idea/dsh-home`，其中 profiles/web/package.json 声明 bundle；
 *   dsh 首次启动时自愈创建 profiles/node_modules 的 junction 指向 dsh 树。
 */
@Service(Service.Level.APP)
class DshHomeManager : Disposable {

    companion object {
        private val LOG = Logger.getInstance(DshHomeManager::class.java)

        /** 固定 dsh 版本（升级 = 换版本 + 重建运行时，见 DESIGN §3.2） */
        const val DSH_VERSION = "0.1.7-rc.2"

        /** 开发态覆盖：DSH_IDEA_RUNTIME=<目录> 直接使用该目录下的 node/ 与 dsh/ */
        const val RUNTIME_OVERRIDE_ENV = "DSH_IDEA_RUNTIME"

        /**
         * 插件资源中的 dsh 树压缩包（build-dsh.mjs --bundle 产物，Step 5 打入 resources）。
         * v0.1.7 起不含 node；node 由宿主系统提供（见 [SystemNodeLocator]）。
         */
        const val DSH_BUNDLE_RESOURCE = "/dsh-bundle.zip"

        const val DEEPSEEK_API_KEY = "DEEPSEEK_API_KEY"

        /** 与启动 dsh web --patch 使用的 ide.yml 文件名 */
        const val IDE_PATCH_FILE = "ide.yml"

        /**
         * 工具窗窄视口下默认启用移动壳（dsh-mobile-hanui）的 bundle。
         * 作用：内嵌工具窗（JBCEF）宽度往往 ≤1023px，dsh-mobile-hanui 在此视口把
         * 侧栏/详情面板变成抽屉（由左缘固定菜单按钮唤出），聊天区占满全宽，从而根除"菜单/侧栏占用编辑空间"。
         * 包由 build-runtime.ps1 一并 npm 安装进运行时 dsh 树（profiles/node_modules junction
         * 可解析）。版本在 scripts/build-runtime.ps1 的 $HanuiVersion 固定。
         */
        const val MOBILE_SHELL_BUNDLE = "dsh-mobile-hanui"

        internal const val WEB_PROFILE_MANIFEST =
            """{"name":"dsh-profile-web","private":true,"dependencies":{},"dsh":{"profile":{"bundles":["@deepseek-ai/dsh-base","@deepseek-ai/dsh-web-app","$MOBILE_SHELL_BUNDLE"]}}}"""

        fun getInstance(): DshHomeManager =
            ApplicationManager.getApplication().getService(DshHomeManager::class.java)

        /** dsh 内测声明 acknowledge 版本（与 dsh 源码 WELCOME_NOTICE_VERSION 一致；变化需同步）。 */
        const val WELCOME_NOTICE_VERSION = "2026-08-13.1"
    }

    /** 运行时根目录（node/ + dsh/ 的父目录）。 */
    fun runtimeRoot(): Path {
        System.getenv(RUNTIME_OVERRIDE_ENV)?.takeIf { Files.isDirectory(Path.of(it)) }?.let { return Path.of(it) }
        return PathManager.getConfigDir().resolve("dsh-idea").resolve("runtime").resolve(DSH_VERSION)
    }

    /**
     * 系统 node 可执行文件（v0.1.7 起：node 不再打包）。
     * @return 解析成功返回 Path；未找到或 `node --version` 失败返回 null，调用方负责给出可操作报错。
     */
    fun nodeExe(): Path? = SystemNodeLocator.resolve()?.path

    fun dshBin(): Path = runtimeRoot().resolve("dsh/node_modules/@deepseek-ai/dsh/lib/bin.js")

    /**
     * 运行时可用性检查（v0.1.7 起：仅校验 dsh 树，node 由系统提供、启动时另行检测）：
     * - `DSH_IDEA_RUNTIME` 覆盖存在 → 用之（且 dsh 树就绪）；
     * - 否则若配置目录缺 dsh 树，从插件资源 `dsh-bundle.zip` 解压（首次使用自举）。
     */
    fun hasRuntime(): Boolean {
        if (Files.isRegularFile(dshBin())) return true
        if (System.getenv(RUNTIME_OVERRIDE_ENV) != null) return false // 覆盖显式指向但缺失 → 报错
        return extractBundledDshBundle()
    }

    /** 从插件资源解压 dsh 树（幂等：dshBin 已存在则跳过；失败返回 false）。v0.1.7 起不含 node。 */
    private fun extractBundledDshBundle(): Boolean {
        val target = runtimeRoot()
        if (Files.isRegularFile(target.resolve("dsh/node_modules/@deepseek-ai/dsh/lib/bin.js"))) {
            return true
        }
        val resource = DSH_BUNDLE_RESOURCE
        val stream = try {
            DshHomeManager::class.java.getResourceAsStream(resource)
        } catch (e: Exception) {
            null
        }
        if (stream == null) {
            LOG.info("no bundled dsh resource ($resource); dev mode expects DSH_IDEA_RUNTIME")
            return false
        }
        LOG.info("extracting bundled dsh tree to $target")
        return try {
            Files.createDirectories(target)
            val tmpZip = target.resolveSibling("dsh-bundle-${System.nanoTime()}.zip")
            stream.use { src -> Files.copy(src, tmpZip, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            // zip 顶层带 dsh/ 前缀（build-dsh.mjs 把 build/dsh/ 内容打包成 dsh/ 根），
            // 所以解压到 target/dsh/，让解压路径 = target/dsh/node_modules/...
            unzip(tmpZip, target.resolve("dsh"))
            Files.deleteIfExists(tmpZip)
            Files.isRegularFile(dshBin())
        } catch (e: Exception) {
            LOG.warn("failed to extract bundled dsh tree", e)
            false
        }
    }

    private fun unzip(zip: Path, dest: Path) {
        java.util.zip.ZipFile(zip.toFile()).use { zf ->
            // 兼容 zip 顶层带单目录前缀（如 runtime/）的情况：剥掉第一层
            val entries = zf.entries().asSequence().filter { !it.isDirectory }.toList()
            val topPrefix = entries.mapNotNull { entry ->
                entry.name.split('/').firstOrNull()?.takeIf { it.isNotEmpty() }
            }.distinct().let { if (it.size == 1) it.first() + "/" else "" }

            for (entry in entries) {
                val rel = entry.name.removePrefix(topPrefix)
                val out = dest.resolve(rel).normalize()
                // 防 zip-slip
                if (!out.startsWith(dest)) continue
                Files.createDirectories(out.parent)
                zf.getInputStream(entry).use { input ->
                    Files.copy(input, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    /**
     * 独立 DSH_HOME（会话数据持久化；按项目隔离，不随运行时版本变化）。
     *
     * v0.1.3-dev（切换项目工作区修复）：每个项目使用独立目录（MD5(projectPath) 前 16 位），
     * 使 dsh 的工作区注册表（workspace.json）与会话数据按项目隔离——切换项目后 dsh 进程的
     * 工作区从当前项目"白纸"开始，从机制上杜绝"显示其他项目工作区"（用户实测：仅旧项目复现，
     * 全新项目无问题，因为 dsh 记住了既有 workspace 的历史会话状态）。
     */
    /**
     * 全局配置目录（方案 C：dsh 配置全局化）——`.credentials.yaml` / `settings.yaml` 的
     * **唯一真源**，所有项目共享；每项目启动时通过 ide.yml patch 把 dsh 的
     * `settings-file.path` / `credentials-local.path` 指向这里，实现"配置共享 + 数据隔离"。
     */
    fun globalConfigHome(): Path = PathManager.getConfigDir().resolve("dsh-idea").resolve("dsh-home")

    fun homeDir(projectPath: String): Path {
        val safe = if (projectPath.isBlank()) "default" else md5(projectPath).take(16)
        return globalConfigHome().resolve(safe)
    }

    private fun md5(s: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /**
     * 幂等创建 DSH_HOME 骨架：
     * - profiles/web/（package.json + cordis.yml + cordis.patch.yml）
     * - ide.yml（--patch 覆盖层占位）
     * - 顶层 node_modules junction → runtime dsh 树（mcp-ide-server.mjs 从 DSH_HOME 顶层
     *   解析 @modelcontextprotocol/sdk；dsh 自愈的 profiles/node_modules 不会被 ESM 向上查找命中）
     * - mcp-ide-server.mjs（插件资源部署）
     */
    fun ensureHome(projectPath: String): Path {
        // 全局配置目录：.credentials.yaml / settings.yaml 唯一真源（所有项目共享，由 ide.yml patch 指向）
        val ghome = globalConfigHome()
        Files.createDirectories(ghome)
        prefillAcknowledgeWelcomeNotice()

        // 每项目子目录 DSH_HOME（数据隔离；storages/sessions 由 dsh 创建；不写独立配置）
        val home = homeDir(projectPath)
        val web = home.resolve("profiles/web")
        Files.createDirectories(web)

        // 确保 profiles/web/package.json 包含最新的 manifest（如果已有老 manifest 则更新）
        updateWebManifestIfNeeded(web.resolve("package.json"))
        writeIfAbsent(web.resolve("cordis.yml"), "[]\n")
        writeIfAbsent(web.resolve("cordis.patch.yml"), "# 本层由插件通过 --patch 覆盖，不在此修改\n[]\n")
        writeIfAbsent(home.resolve(IDE_PATCH_FILE), "[]\n")
        deployMobileShellPlugin()
        pruneRedundantClientPlugins()
        ensureTopLevelNodeModules(home)
        deployMcpServer(home)
        // 方案 A：把全局唯一配置复制到本子目录（dsh 从子目录读；全局为真源；dsh 内改动下次启动被全局覆盖）
        copyGlobalConfigTo(home)
        // 方案 A'（0.1.7）：把全局 providers.patch.yaml 的 provider 条目合并进本项目
        // profiles/web/cordis.patch.yml（跨项目共享第三方 LLM 配置）
        mergeGlobalProvidersInto(home)
        // 升级迁移：v0.1.2 全局 DSH_HOME 的 session 数据 → 当前项目隔离目录（幂等；workspace 由 dsh 自动重建）
        migrateLegacySessions(home, projectPath)
        return home
    }

    /**
     * 旧版（v0.1.2）在全局 DSH_HOME 根（= [globalConfigHome]）下存 session；新版改为每项目隔离目录。
     * 把旧全局 `sessions/<projectKey(projectPath)>` 复制到本子目录（含投影缓存 `session_projcache.json`），
     * 使用户升级后旧会话仍可见且标题正确（dsh 的 `session.list` 用零 I/O 投影缓存读标题，需一并迁移）。
     * 仅当全局根下存在对应项目目录且子目录数据尚未迁移时复制（幂等）。
     */
    private fun migrateLegacySessions(home: Path, projectPath: String) {
        if (projectPath.isBlank()) return
        val oldRoot = globalConfigHome()
        if (!Files.isDirectory(oldRoot.resolve("sessions"))) return
        try {
            LegacySessionMigrator.migrateProject(oldRoot, home, projectPath)
            LegacySessionMigrator.migrateProjectionCache(oldRoot, home, projectPath)
        } catch (e: Exception) {
            LOG.warn("legacy session migration failed for $projectPath", e)
        }
    }

    /** 把全局配置文件（.credentials.yaml / settings.yaml）同步到子目录（幂等；仅当全局存在）。 */
    private fun copyGlobalConfigTo(home: Path) {
        val g = globalConfigHome()
        for (name in listOf(".credentials.yaml", "settings.yaml")) {
            val src = g.resolve(name)
            if (!Files.exists(src)) continue
            Files.createDirectories(home)
            if (name == ".credentials.yaml") {
                // 凭据：合并而不是覆盖 —— 保留子目录里已有的第三方 provider（llm-pi-ai）key，
                // 同时补入全局的共享 refs。旧实现 REPLACE_EXISTING 会丢掉当前项目已配好的
                // MINIMAX/aiyunrouter 等 apiKeyEnv，导致每次重开反复要求重新输入密钥。
                copyCredRefsMerged(home, src)
            } else {
                Files.copy(src, home.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /** 全局凭据（可能含 pi provider key）合并进子目录凭据，保留子目录已有 refs，不覆盖。 */
    private fun copyCredRefsMerged(home: Path, globalSrc: Path) {
        val dest = home.resolve(".credentials.yaml")
        val destRefs = DshCredentials.readAllRefs(dest)
        val globalRefs = DshCredentials.readAllRefs(globalSrc)
        val merged = LinkedHashMap<String, String>(globalRefs)
        // 保留子目录已有、全局缺失的 key（如仅在本地项目配置过的 provider）
        for ((k, v) in destRefs) if (!merged.containsKey(k)) merged[k] = v
        DshCredentials.writeRefs(dest, merged)
    }

    /** 预写全局 settings.yaml：ui-onboarding.welcomeNoticeVersion = 已接受版本（文件已存在则不覆盖）。 */
    private fun prefillAcknowledgeWelcomeNotice() {
        val f = globalConfigHome().resolve("settings.yaml")
        if (Files.exists(f)) return
        writeUtf8(f, "ui-onboarding:\n  welcomeNoticeVersion: \"$WELCOME_NOTICE_VERSION\"\n")
        LOG.info("prefilled settings.yaml welcomeNoticeVersion=$WELCOME_NOTICE_VERSION")
    }

    /**
     * 更新已有 `profiles/web/package.json`：若缺失 `dsh-mobile-hanui` 等最新 bundle 则更新。
     */
    private fun updateWebManifestIfNeeded(manifestPath: Path) {
        if (!Files.exists(manifestPath)) {
            writeUtf8(manifestPath, WEB_PROFILE_MANIFEST)
            return
        }
        try {
            val content = Files.readString(manifestPath, StandardCharsets.UTF_8)
            if (!content.contains(MOBILE_SHELL_BUNDLE)) {
                writeUtf8(manifestPath, WEB_PROFILE_MANIFEST)
                LOG.info("updated $manifestPath with $MOBILE_SHELL_BUNDLE")
            }
        } catch (e: Exception) {
            LOG.warn("failed to check/update web profile manifest", e)
        }
    }

    /**
     * 从插件资源自动部署 `dsh-mobile-hanui` 到运行时 `dsh/node_modules/dsh-mobile-hanui`。
     * 保证无需外部 npm 安装，开箱即用，所有项目共享。
     */
    private fun deployMobileShellPlugin() {
        val clientJs = MobileShellResources.clientJs() ?: return
        val targetDir = runtimeRoot().resolve("dsh/node_modules/dsh-mobile-hanui")
        try {
            Files.createDirectories(targetDir.resolve("src"))
            val pkgJson = targetDir.resolve("package.json")
            val patchYml = targetDir.resolve("cordis.patch.yml")
            val indexJs = targetDir.resolve("src/index.js")
            val rootIndexJs = targetDir.resolve("index.js")
            val clientFile = targetDir.resolve("src/client.js")

            writeUtf8IfChanged(pkgJson, MobileShellResources.PACKAGE_JSON)
            writeUtf8IfChanged(patchYml, MobileShellResources.CORDIS_PATCH_YML)
            writeUtf8IfChanged(indexJs, MobileShellResources.INDEX_JS)
            writeUtf8IfChanged(rootIndexJs, MobileShellResources.ROOT_INDEX_JS)
            writeUtf8IfChanged(clientFile, clientJs)
            LOG.info("deployed dsh-mobile-hanui plugin to $targetDir")
        } catch (e: Exception) {
            LOG.warn("failed to deploy dsh-mobile-hanui plugin", e)
        }
    }

    private fun writeUtf8IfChanged(path: Path, content: String) {
        if (!Files.exists(path) || Files.readString(path, StandardCharsets.UTF_8) != content) {
            writeUtf8(path, content)
        }
    }

    /**
     * 裁剪随包 dsh 树里 IDEA 工具窗用不上的 client 插件行（幂等，见 [DshClientPluginPruner]）。
     *
     * 位置说明：必须在 [hasRuntime] 之后调用 —— 首次使用会从 `dsh-bundle.zip` 解压运行时树，
     * 只有解压完成后裁剪才作用在真实文件上（[ensureHome] 的调用点满足该顺序）。
     * 失败仅告警不阻断：最坏结果只是这几个插件照旧出现在 Web 设置→插件 列表里。
     */
    private fun pruneRedundantClientPlugins() {
        try {
            DshClientPluginPruner.pruneRuntime(runtimeRoot())
        } catch (e: Exception) {
            LOG.warn("client plugin pruning failed (non-fatal)", e)
        }
    }

    /**
     * 顶层 node_modules junction（缺失才建；指向运行时 dsh 树，供 mcp-ide-server.mjs 解析 SDK）。
     * 注意：若 profiles/node_modules/ 是空目录（遗留），会阻断 ESM 向上遍历找到 home/node_modules junction，
     * 导致 dsh-mobile-hanui 等包解析失败。发现空目录时先删掉再创建 junction。
     */
    private fun ensureTopLevelNodeModules(home: Path) {
        val link = home.resolve("node_modules")

        // 空 profiles/node_modules/ 阻断 ESM 向上遍历命中 home/node_modules junction，必须删除
        val profilesNm = home.resolve("profiles/node_modules")
        if (Files.isDirectory(profilesNm)) {
            try {
                Files.newDirectoryStream(profilesNm).use { stream ->
                    if (!stream.iterator().hasNext()) {
                        Files.delete(profilesNm)
                    }
                }
            } catch (_: Exception) { }
        }

        if (Files.exists(link)) return
        val target = runtimeRoot().resolve("dsh/node_modules")
        if (!Files.isDirectory(target)) {
            LOG.warn("runtime dsh tree missing: $target")
            return
        }
        try {
            Files.createSymbolicLink(link, target)
            LOG.info("created DSH_HOME/node_modules junction -> $target")
        } catch (e: Exception) {
            // 沙箱/权限受限时退回 cmd mklink /J（junction 不需要管理员）
            try {
                val p = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true)
                    .start()
                p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
                if (Files.exists(link)) LOG.info("created DSH_HOME/node_modules junction via mklink -> $target")
                else LOG.warn("mklink junction failed for $link -> $target")
            } catch (e2: Exception) {
                LOG.warn("failed to create node_modules junction $link", e2)
            }
        }
    }

    /** 从插件资源部署 mcp-ide-server.mjs 到 DSH_HOME（内容变化时覆盖）。 */
    private fun deployMcpServer(home: Path) {
        val target = home.resolve("mcp-ide-server.mjs")
        try {
            val resource = IdeBridgeResources.mcpServerScript() ?: return
            if (!Files.exists(target) || Files.readString(target) != resource) {
                writeUtf8(target, resource)
                LOG.info("deployed mcp-ide-server.mjs to $target")
            }
        } catch (e: Exception) {
            LOG.warn("failed to deploy mcp-ide-server.mjs", e)
        }
    }

    /** MCP server 脚本路径（DSH_HOME 顶层，ESM 可解析顶层 node_modules junction）。 */
    fun mcpServerScript(projectPath: String): Path = homeDir(projectPath).resolve("mcp-ide-server.mjs")

    /** 将 PasswordSafe 中的 API Key 同步到全局 .credentials.yaml（所有项目共享，由 ide.yml patch 指向）。 */
    fun syncCredentials(): Boolean {
        val key = DshCredentials.readApiKey() ?: return false
        val credFile = globalConfigHome().resolve(".credentials.yaml")
        return try {
            val refs = LinkedHashMap<String, String>(DshCredentials.readAllRefs(credFile))
            val changed = refs[DEEPSEEK_API_KEY] != key
            // 保存成 dsh 原生 refs 格式（保留已有的第三方 pi provider key，不丢）
            if (changed || !Files.exists(credFile)) {
                refs[DEEPSEEK_API_KEY] = key
                DshCredentials.writeRefs(credFile, refs)
            }
            changed || !Files.exists(credFile)
        } catch (e: Exception) {
            LOG.warn("failed to sync credentials to DSH_HOME", e)
            false
        }
    }

    /** 设置页 apply：把 API Key 同步到全局 .credentials.yaml（运行中的会话需重启生效）。 */
    fun syncCredentialsAll() {
        syncCredentials()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 方案 A'（dsh 0.1.7 适配）：dsh Web UI 改的 llm-pi-ai provider 配置回写全局真源
    // 0.1.5：配置在 $DSH_HOME/settings.yaml 的 `llm-pi-ai:` 顶层节；
    // 0.1.7：改为 profiles/web/cordis.patch.yml 里的 cordis 条目
    //        `- id: llm-pi-ai`（@deepseek-ai/dsh-llm-pi-ai，providers 配置）与
    //        `- id: agent-default-model`（默认 provider 选择）；
    //        旧 settings.yaml 被迁移留档为 settings.yaml.imported，不再承载。
    // 全局真源 = globalConfigHome()/providers.patch.yaml（只存这两个条目的列表）。
    // 监听器在 DshSettingsSync（监听项目 cordis.patch.yml，变化 → syncProvidersToGlobal）。
    // ─────────────────────────────────────────────────────────────────────────

    /** 0.1.7 provider 配置所在的 cordis 条目 id。 */
    internal val PROVIDER_ENTRY_IDS = listOf("llm-pi-ai", "agent-default-model")

    /** 全局 provider 真源文件（顶层 YAML 列表，只含 [PROVIDER_ENTRY_IDS] 条目）。 */
    fun globalProvidersFile(): Path = globalConfigHome().resolve("providers.patch.yaml")

    /** 取一个顶层条目的 `- id:` 值（容忍单/双引号与多余空白）；非 id 条目返回 null。 */
    private fun entryIdOf(item: String): String? {
        if (!item.startsWith("- id:")) return null
        // 先取 token 再去引号：反过来的话，多行条目的尾部引号会粘在 token 后面切不掉
        val token = item.removePrefix("- id:").trim().takeWhile { !it.isWhitespace() }
        return token.trim('\'', '"').takeIf { it.isNotEmpty() }
    }

    /** 把顶层列表文本按列 0 的 `- ` 条目切块（忽略首块之前的注释/空行/`[]` 占位）。 */
    internal fun splitTopLevelItems(text: String): List<String> {
        val items = ArrayList<String>()
        val cur = ArrayList<String>()
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            if (line.startsWith("- ") || line == "-") {
                if (cur.isNotEmpty()) {
                    items.add(cur.joinToString("\n").trimEnd())
                    cur.clear()
                }
                cur.add(line)
            } else if (cur.isNotEmpty()) {
                cur.add(line)
            }
        }
        if (cur.isNotEmpty()) items.add(cur.joinToString("\n").trimEnd())
        return items.filter { it.isNotBlank() }
    }

    /** 序列化条目列表为 patch 文件内容（空列表 → `[]`；保留原文头部注释）。 */
    private fun serializePatchItems(originalText: String, items: List<String>): String {
        val header = originalText.split('\n')
            .takeWhile { it.trimStart('\uFEFF').startsWith("#") || it.isBlank() }
            .joinToString("\n")
            .trimEnd('\r')
        val body = if (items.isEmpty()) "[]" else items.joinToString("\n")
        return (if (header.isBlank()) "" else header + "\n") + body + "\n"
    }

    /**
     * 在 patch 文本里按条目 id upsert（`entry == null` 表示删除该条目）：
     * 已有同 id 条目则整体替换，否则追加（删除时不存在则原样返回）。幂等。
     */
    internal fun upsertPatchEntry(text: String, id: String, entry: String?): String {
        val items = splitTopLevelItems(text).toMutableList()
        val idx = items.indexOfFirst { entryIdOf(it) == id }
        if (entry == null) {
            if (idx < 0) return text
            items.removeAt(idx)
        } else if (idx >= 0) {
            if (items[idx] == entry) return text
            items[idx] = entry
        } else {
            items.add(entry)
        }
        return serializePatchItems(text, items)
    }

    /**
     * 把子项目 `profiles/web/cordis.patch.yml` 里的 provider 条目（`- id: llm-pi-ai` /
     * `- id: agent-default-model`）回写到全局 providers.patch.yaml 真源。
     *
     * **语义**：全量镜像（最近一次胜出）—— 全局这两个条目与项目当前内容一致；
     * 项目里删掉的条目全局同样删掉。内容已一致则不写（防自激循环：
     * [mergeGlobalProvidersInto] 回写项目文件会触发监听器，走回这里因内容相同而 noop）。
     *
     * @return true 表示全局文件被写入；false 表示 noop 或失败（失败已 LOG.warn）。
     */
    fun syncProvidersToGlobal(projectCordisPatchFile: Path): Boolean {
        return try {
            if (!Files.isReadable(projectCordisPatchFile)) return false
            val projectText = Files.readString(projectCordisPatchFile, StandardCharsets.UTF_8)
            val projectItems = splitTopLevelItems(projectText)
            val entries = PROVIDER_ENTRY_IDS.mapNotNull { id ->
                projectItems.firstOrNull { entryIdOf(it) == id }
            }
            val globalFile = globalProvidersFile()
            val merged = serializePatchItems("", entries)
            if (Files.exists(globalFile) && Files.readString(globalFile, StandardCharsets.UTF_8) == merged) {
                false // 已一致 → 无需写
            } else {
                Files.createDirectories(globalFile.parent)
                writeUtf8(globalFile, merged)
                LOG.info("synced ${entries.size} provider entries from $projectCordisPatchFile to global")
                true
            }
        } catch (e: Exception) {
            LOG.warn("failed to sync provider entries from $projectCordisPatchFile to global", e)
            false
        }
    }

    /**
     * 项目启动（[ensureHome] 调用）：把全局 providers.patch.yaml 的 provider 条目合并进
     * 项目 `profiles/web/cordis.patch.yml`（按条目 id upsert；幂等），使其它项目在 Web UI
     * 里配的 provider 在本项目启动即可用。全局真源文件尚不存在时，先以当前项目为种子
     * （老用户升级后第一个打开的已配置项目成为 donor）。
     */
    fun mergeGlobalProvidersInto(home: Path) {
        try {
            val patchFile = home.resolve("profiles/web/cordis.patch.yml")
            if (!Files.exists(patchFile)) return
            if (!Files.exists(globalProvidersFile())) {
                syncProvidersToGlobal(patchFile)
            }
            val globalFile = globalProvidersFile()
            if (!Files.isReadable(globalFile)) return
            val globalText = Files.readString(globalFile, StandardCharsets.UTF_8)
            if (globalText.isBlank() || globalText.trim() == "[]") return
            val globalItems = splitTopLevelItems(globalText)
            var projectText = Files.readString(patchFile, StandardCharsets.UTF_8)
            for (id in PROVIDER_ENTRY_IDS) {
                val entry = globalItems.firstOrNull { entryIdOf(it) == id } ?: continue
                projectText = upsertPatchEntry(projectText, id, entry)
            }
            if (projectText != Files.readString(patchFile, StandardCharsets.UTF_8)) {
                writeUtf8(patchFile, projectText)
                LOG.info("merged global provider entries into $patchFile")
            }
        } catch (e: Exception) {
            LOG.warn("failed to merge global provider entries into $home", e)
        }
    }

    private fun writeIfAbsent(path: Path, content: String) {
        if (!Files.exists(path)) writeUtf8(path, content)
    }

    private fun writeUtf8(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content, StandardCharsets.UTF_8)
    }

    override fun dispose() = Unit
}
