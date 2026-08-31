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
        const val DSH_VERSION = "0.1.1-rc.2"

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
         * 侧栏/详情面板变成抽屉 + FAB，聊天区占满全宽，从而根除"菜单/侧栏占用编辑空间"。
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
            unzip(tmpZip, target)
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
        ensureTopLevelNodeModules(home)
        deployMcpServer(home)
        // 方案 A：把全局唯一配置复制到本子目录（dsh 从子目录读；全局为真源；dsh 内改动下次启动被全局覆盖）
        copyGlobalConfigTo(home)
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
            val clientFile = targetDir.resolve("src/client.js")

            writeUtf8IfChanged(pkgJson, MobileShellResources.PACKAGE_JSON)
            writeUtf8IfChanged(patchYml, MobileShellResources.CORDIS_PATCH_YML)
            writeUtf8IfChanged(indexJs, MobileShellResources.INDEX_JS)
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

    /** 顶层 node_modules junction（缺失才建；指向运行时 dsh 树，供 mcp-ide-server.mjs 解析 SDK）。 */
    private fun ensureTopLevelNodeModules(home: Path) {
        val link = home.resolve("node_modules")
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
    // 方案 A：dsh Web UI 改的 llm-pi-ai 节回写到全局 settings.yaml
    // （参见方案 A 设计；监听器在 DshSettingsSync）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 把子项目 `settings.yaml` 里的 `llm-pi-ai:` 节回写到全局 settings.yaml 真源。
     *
     * **语义**：全量替换（不是合并）—— 全局 `llm-pi-ai.providers` 与项目该节内容一致，
     * 其它顶层节原样保留。最简单也最符合用户预期（用户通常在一个项目里配齐所有
     * 第三方 provider，然后跨项目共享）。删除语义由 V1「最近一次胜出」承担；
     * 多项目并集合并留 V2。
     *
     * **回写条件**（任一不满足则 noop）：
     * 1. project 文件存在且可读；
     * 2. project 文件含 `llm-pi-ai:` 节（即 Web UI 这次改动了 provider）；
     * 3. 解析后的内容与全局**当前** `llm-pi-ai:` 节文本不同（避免无谓 IO + 自激循环）。
     *
     * **不动的东西**：`ui-onboarding`（`prefillAcknowledgeWelcomeNotice` 写入的内测声明）、
     * cordis 元数据（`$settings` / `$credentials`）、其它顶层节；YAML 内容按行搬运，
     * 不做 schema 校验——schema 校验由 dsh 自己加载时完成（`settings-rejected` 在 dsh 那边报）。
     *
     * @return true 表示全局文件被写入；false 表示 noop 或失败（失败已 LOG.warn）。
     */
    fun syncProvidersToGlobal(projectSettingsFile: Path): Boolean {
        return try {
            if (!Files.isReadable(projectSettingsFile)) return false
            val projectText = Files.readString(projectSettingsFile, StandardCharsets.UTF_8)
            val projectSection = extractTopLevelSection(projectText, "llm-pi-ai:")
                ?: return false

            // 确保全局 settings.yaml 存在（与 prefillAcknowledgeWelcomeNotice 风格一致）
            prefillAcknowledgeWelcomeNotice()
            val globalFile = globalConfigHome().resolve("settings.yaml")
            val globalText = if (Files.isReadable(globalFile)) {
                Files.readString(globalFile, StandardCharsets.UTF_8)
            } else {
                ""
            }
            val merged = replaceTopLevelSection(globalText, "llm-pi-ai:", projectSection.body)
            if (Files.exists(globalFile) && Files.readString(globalFile, StandardCharsets.UTF_8) == merged) {
                false // 已一致 → 无需写
            } else {
                writeUtf8(globalFile, merged)
                LOG.info("synced llm-pi-ai section from ${projectSettingsFile.fileName} " +
                    "(providers=${projectSection.body.lineSequence().count { it.trimStart().startsWith("-") || it.contains(':') }} lines)")
                true
            }
        } catch (e: Exception) {
            LOG.warn("failed to sync llm-pi-ai providers from $projectSettingsFile to global", e)
            false
        }
    }

    /**
     * 从 YAML 文本中抽出一个顶层节的（起点行 → 终点行的不含索引, 含头部键名）。
     * 「顶层」= 行首无前导空白且形如 `<key>:`；节的子内容由下一行缩进识别。
     * @return 节存在时返回 [Section]；不存在返回 null。
     */
    internal data class Section(val startLine: Int, val endLineExclusive: Int, val body: String)

    internal fun extractTopLevelSection(text: String, keyLine: String): Section? {
        val lines = text.split('\n')
        // 规范化：keyLine 形如 "llm-pi-ai:"，但文本里 key 前可能带 BOM/CR，统一清洗
        val targetKey = keyLine.trim().trimStart('\uFEFF').removeSuffix("\r")
        var startLine = -1
        for ((i, raw) in lines.withIndex()) {
            val line = raw.trimEnd('\r')
            if (isTopLevelKeyLine(line) && line.trim() == targetKey) {
                startLine = i
                break
            }
        }
        if (startLine < 0) return null
        // 节的子内容缩进：startLine 之后第一个非空、非顶层 key 行的首字符缩进；
        // 顶层 key（缩进=0）不能被当节内容，否则会把同文件下一个顶层节吞进来。
        var childIndent: Int? = null
        for (j in (startLine + 1) until lines.size) {
            val l = lines[j].trimEnd('\r')
            if (l.isBlank()) continue
            if (isTopLevelKeyLine(l)) break  // 撞到下一个顶层 key → 节为空
            childIndent = leadingSpaces(l)
            break
        }
        // 节终点：
        // - childIndent != null：找第一个缩进 < childIndent 的非空行（含下一顶层 key，其缩进=0）
        // - childIndent == null（节为空）：终点 = 下一个顶层 key 行 或 EOF
        val endLine = if (childIndent == null) {
            var e = lines.size
            for (j in (startLine + 1) until lines.size) {
                if (isTopLevelKeyLine(lines[j].trimEnd('\r'))) { e = j; break }
            }
            e
        } else {
            var e = lines.size
            for (j in (startLine + 1) until lines.size) {
                val l = lines[j].trimEnd('\r')
                if (l.isBlank()) continue
                if (leadingSpaces(l) < childIndent) { e = j; break }
            }
            e
        }
        // body: startLine..endLine（不含）的原文；每行 trimEnd('\r') 清掉 CRLF 残留
        val body = lines.subList(startLine, endLine).joinToString("\n") { it.trimEnd('\r') }
        return Section(startLine, endLine, body)
    }

    /**
     * 在 YAML 文本里把指定顶层节替换为 [newBody]。若 keyLine 不存在则追加到末尾。
     * - 替换时保留节原起点行之前的缩进风格（与 text 一致；不重排）；
     * - 节内文本完全由 newBody 决定（含 keyLine 这一行）；
     * - 节尾与后续内容用一个空行隔开（与 settings.yaml 实际书写风格一致）。
     */
    internal fun replaceTopLevelSection(text: String, keyLine: String, newBody: String): String {
        val lines = text.split('\n').toMutableList()
        val existing = extractTopLevelSection(text, keyLine)
        val normalizedNew = newBody.trimEnd('\n')
        return if (existing == null) {
            // 追加：空文件 → 直接写；有内容 → 在末尾追加 + 空行 + 节
            if (lines.isEmpty() || lines.all { it.isBlank() }) {
                normalizedNew + "\n"
            } else {
                val stripped = if (lines.last().isBlank()) lines.dropLast(1) else lines
                (stripped + listOf("", normalizedNew)).joinToString("\n") + "\n"
            }
        } else {
            // 替换：保留 existing.startLine 之前的内容（不含 startLine 行）+ newBody + 之后的内容
            val head = lines.subList(0, existing.startLine)
            val tail = lines.subList(existing.endLineExclusive, lines.size)
            val headText = if (head.isEmpty()) "" else head.joinToString("\n").trimEnd('\n')
            val tailText = if (tail.isEmpty()) "" else tail.joinToString("\n")
            buildString {
                if (headText.isNotEmpty()) {
                    append(headText); append('\n')
                }
                append(normalizedNew); append('\n')
                if (tailText.isNotEmpty()) {
                    append('\n') // 节尾与后续内容之间留一个空行
                    append(tailText)
                }
            }
        }
    }

    private fun isTopLevelKeyLine(line: String): Boolean {
        if (line.isBlank()) return false
        if (line.first() == ' ' || line.first() == '\t') return false
        // 形如 `key:` —— 顶层 key 后跟冒号；冒号前允许空格？不，顶层 key 不带前导空白
        val colon = line.indexOf(':')
        if (colon <= 0) return false
        // key 部分只能含字母数字/连字符/下划线/点（dsh 用 `llm-pi-ai` / `ui-onboarding` / `$settings`）
        val keyPart = line.substring(0, colon)
        return keyPart.all { it == '$' || it == '_' || it.isLetterOrDigit() || it == '-' || it == '.' }
    }

    private fun leadingSpaces(line: String): Int {
        var n = 0
        for (c in line) {
            if (c == ' ') n++
            else if (c == '\t') n += 4 // tab 不常见，宽松按 4 空格计；DSH 写出的都是空格
            else break
        }
        return n
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
