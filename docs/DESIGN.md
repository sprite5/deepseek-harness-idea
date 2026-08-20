# DeepSeek Harness IntelliJ IDEA 插件 — 详设文档（DESIGN）

| 项目 | 内容 |
|---|---|
| 文档版本 | v0.1 |
| 日期 | 2026-02-11 |
| 状态 | 草稿（随实现迭代更新） |
| 关联文档 | [PRD.md](./PRD.md) |

---

## 1. 术语与参考

| 术语 | 说明 |
|---|---|
| DSH | DeepSeek Harness，智能体工作台 CLI/服务（`@deepseek-ai/dsh`） |
| dsh web | DSH 的浏览器 UI 服务：`dsh --profile web`，默认 `http://127.0.0.1:3080` |
| DSH_HOME | dsh 的配置/数据目录（profiles、sessions、credentials），插件使用独立目录 |
| JCEF | JetBrains 内置 Chromium Embedded Framework（`com.intellij.ui.jcef.JBCefBrowser`） |
| IDE Bridge | 插件内的 Kotlin 本地 HTTP 服务，向 MCP server 暴露 IDE 能力 |
| MCP | Model Context Protocol；dsh 作为 MCP 客户端连接插件提供的 MCP server |

参考源码（本机 npx 缓存中的 `@deepseek-ai/dsh@0.1.0-rc.7`）：

- `dsh-web-app/lib/startup.js`：web 命令行 `--host/--port/--trusted-host`；`--port 0` 由 OS 分配
- `dsh-web-app/lib/index.js:107`：启动成功打印 `dsh web: http://127.0.0.1:<port>`（loopback）
- `dsh-client-connection/lib/index.js`：`/api` 浏览器信任围栏，loopback hostname 默认受信任；`--host 0.0.0.0` 被拒绝
- `dsh-mcp-client/lib/index.js:738-756`：mcp-client Config schema（`transport: streamable-http` 分支）
- `~/.dsh/.credentials.yaml`：凭据文件，键 `DEEPSEEK_API_KEY`
- `~/.dsh/profiles/web/`：profile 结构（`cordis.yml` = bundle 层 + `cordis.patch.yml` 用户层 + `--patch` 覆盖层；`package.json` 的 `dsh.profile.bundles` 声明 bundle）

## 2. 总体架构

### 2.1 架构图

```
┌───────────────────────────── IntelliJ IDEA 进程（JVM/EDT）────────────────────────────┐
│  Plugin (Kotlin)                                                                       │
│  ├─ Tool Window: JBCefBrowser ──loads──► http://127.0.0.1:<webPort>（DSH Web UI）      │
│  ├─ IDE Bridge Server（JDK HttpServer，127.0.0.1:<bridgePort>，X-DSH-IDE-Token 鉴权）  │
│  │    /health /selection /open-files /project-tree /sent-selection                     │
│  │    /open-file /reveal                                                               │
│  ├─ Snapshot & Review Manager（基线快照 → DiffManager diff → 还原/忽略）               │
│  └─ DshProcessManager（Node 子进程生命周期、stdout 端口解析、日志、崩溃重启）           │
└───────────────────────────────────┬───────────────────────────────────────────────────┘
                                    │ ProcessBuilder：node.exe dsh/bin.js
                                    │   --profile web --host 127.0.0.1 --port 0 --patch ide.yml
                                    │   cwd=<项目根目录>；env: DSH_HOME、DSH_IDE_BRIDGE_URL、DSH_IDE_TOKEN
┌───────────────────────────────────▼───────────────────────────────────────────────────┐
│  Node 子进程（DSH）                                                                     │
│  ├─ dsh web server（webPort，仅 loopback）                                              │
│  ├─ cordis 插件树：… + mcp-client(ide)                                                  │
│  │      └─ StreamableHTTPClientTransport ──http://127.0.0.1:<mcpPort>/mcp──► MCP Server│
│  └─ MCP Server（mcp-ide-server.mjs，复用 profile node_modules 的 @modelcontextprotocol/ │
│        sdk）：注册 ide_* 工具 ──fetch + token──► IDE Bridge Server                      │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 进程模型

- **每项目一个 Node 实例**：工具窗口首次打开时懒启动；项目关闭（`ProjectManagerListener` = `DshLifecycleManager` + `Disposer.register(project, panel)`）与 IDE 退出（`AppLifecycleListener` = `DshAppLifecycleListener`）时终止进程树（Step 5 落地）。
- 并发上限 3（`DshRuntimeRegistry.tryAcquire`，超出工具窗口提示），后续可优化为单实例多工作区。
- 插件侧与 Node 侧所有端口随机（`--port 0` / HttpServer 随机端口），无固定端口冲突。

### 2.3 关键技术依据（已通过本机源码/环境验证）

1. **端口发现**：`dsh web` 支持 `--port 0`，由 OS 分配；启动后 stdout 打印 `dsh web: http://127.0.0.1:<port>`（`dsh-web-app/lib/index.js:107`）。插件逐行读取 stdout 正则 `dsh web: http://127\.0\.0\.1:(\d+)` 得 webPort，随后健康检查。
2. **信任围栏**：`/api` 请求的浏览器信任围栏接受 loopback hostname（`dsh-client-connection` `isLoopbackHostname`），故 JCEF 从 `http://127.0.0.1:<webPort>` 加载可正常调用 API；无需 `--trusted-host`。`--host 0.0.0.0` 被 dsh 主动拒绝，天然防外网暴露。
3. **凭据**：`DSH_HOME/.credentials.yaml` 键 `DEEPSEEK_API_KEY`（本机 `~/.dsh/.credentials.yaml` 已确认）。设置页写入该文件即可。
4. **Profile 合成**：`profiles/<name>/cordis.yml` 初始为 `[]`，由 bundle 层（`package.json` 的 `dsh.profile.bundles`）+ `cordis.patch.yml` 用户层 + `--patch` 覆盖层合成。插件以 `--patch <ide.yml>` 注入 mcp-client，不污染用户层。
5. **MCP 客户端**：`@deepseek-ai/dsh-mcp-client` 支持 `transport: streamable-http`；每实例一个 serverName；模型侧工具名为 `mcp__<serverName>__<rawName>`（serverName 须匹配 `^[A-Za-z0-9_-]{1,32}$`）。其依赖 `@modelcontextprotocol/sdk` 存在于 profile 的 hoisted `node_modules`，可被插件附带的 MCP server 脚本 import（脚本置于 DSH_HOME 下按 node 向上查找规则解析）。
6. **运行时**：固定 `@deepseek-ai/dsh@0.1.0-rc.7` + Node.js 22.x win-x64（与当前环境一致），随插件打包。

## 3. 模块设计

### 3.1 项目骨架与构建

- Gradle（Kotlin DSL），`org.jetbrains.intellij` **1.17.4**（2.x platform 线未在本网络插件门户解析到且 DSL 不兼容，升级列入技术债 C-1，见 build.gradle.kts 注释与 MILESTONE_REVIEW.md），platformVersion `2024.1`，Kotlin 2.0.x，JVM 17（toolchain）。
- 包根 `com.deepseek.harness.idea`，子包：
  - `runtime`：DshProcessManager、DshHomeManager、Bootstrap、PortParser、ProcessLog
  - `bridge`：IdeBridgeServer、BridgeApi（请求/响应模型）、BridgeAuth（token）
  - `mcp`：McpPatchGenerator、McpConfig（端口/工具清单）
  - `review`：SnapshotManager、SnapshotDiff、ReviewPanel（tool window 页）
  - `ui`：DshToolWindow、ToolbarActions、StatusIndicator、LogPanel
  - `settings`：DshSettingsState（`PersistentStateComponent`）、SettingsPage、CredentialImporter
  - `i18n`：DshBundle
  - `util`：PathFilters、IoUtil、VfsActions（EDT 封装）
- `plugin.xml`：toolWindow（id `dsh.toolWindow`）、actions（editor popup 等）、`projectService`/`applicationService` 声明、`ProjectManagerListener`、`AppLifecycleListener`。

#### 本机构建环境（实测，持续更新；详见 docs/PROJECT_NOTES.md §1）

| 项 | 结论 |
|---|---|
| JDK | **必须 JBR 21**：`D:\develop\IntelliJ IDEA 2024.3.4.1\jbr`（jdk-17 会让 `instrumentCode` 报 `Packages does not exist`） |
| Gradle | 用 `tooling/gradle-8.14/bin/gradle.bat`（自带发行版）；勿用系统 gradle-7.2 |
| Gradle 用户目录 | `GRADLE_USER_HOME=D:\develop\gradle-7.2\.gradle\repository`（缓存含 ideaIC 2024.1.7） |
| 运行时开发目录 | `tooling/runtime-dev`（`DSH_IDEA_RUNTIME`）；`build/runtime` 为构建产物 |
| 网络/沙箱 | 自动化环境 pwsh 沙箱拦截工作区外读写 → gradle/npm 需完整沙箱权限；npm 走 npmmirror，下载用 node fetch |
| 一键打包 | `scripts/build-plugin.bat`（双击；自动探测 JBR/Gradle 缓存，`--no-daemon` 防缓存锁） |
| Gradle 缓存坑 | `Failed to create Jar file ...jars-9\...` = 残留 daemon 锁缓存 → `gradle --stop` + 杀残留 java + 删 hash 目录 |

##### Step 1 实测结论（2026-02-11）

- 构建链：`gradlew buildPlugin test verifyPlugin` 全部通过；插件 zip 约 1.6MB；`CredentialImporterTest` 4/4。
- **PasswordSafe（241 版 API，实测）**：经 `Application.getService(PasswordSafe::class.java)` 解析出的接口为
  `setPassword(CredentialAttributes, String?)` / `getPassword(CredentialAttributes): String?`；
  `PasswordSafe.getInstance()` 与旧三参 `setPassword(Project, attrs, String)` 均不可用（Kotlin 编译期验证）。
- **runIde**：沙箱 IDE 加载插件成功（日志 `Loaded custom plugins: DeepSeek Harness (0.1.0)`），平台完整启动、无异常；
  IDE 约 20s 后自行干净退出（自动化环境会话限制，非插件问题）。工具窗口/设置页的交互验证需用户手动 `gradlew runIde` 或安装 zip。

### 3.2 运行时打包与 DSH_HOME 管理

**构建期**（`scripts/build-runtime.ps1`，Gradle task `buildRuntime` 调用；已实现并实测通过）：

1. 下载 Node.js 22.x win-x64（固定版本，SHA-256 校验）→ `<OutputDir>/node/`。
2. 以 npm 安装 `@deepseek-ai/dsh@0.1.0-rc.7` 及其依赖到 `<OutputDir>/dsh/`（`--ignore-scripts`；
   win-x64 原生依赖均以 optionalDependencies 预编译产物提供，无需 postinstall）。
3. 冒烟验证：读取 `dsh` 版本；`-Bundle` 时打包 `runtime-bundle.zip`（**zip 根直接为 `node/` + `dsh/`**，
   排除源 zip 与 npm 缓存；Step 5 已实测 106.9MB、解压 62s）。
4. 下载/安装均为幂等（存在且校验通过则跳过；`-Force` 重建）。

**打包**（Step 5）：`buildRuntime -Bundle` → `build/runtime-bundle.zip` → Gradle `bundleRuntime` 复制到
`build/plugin-runtime/` → 作为插件资源打入 jar（`/runtime-bundle.zip`）；`processResources` 依赖 `bundleRuntime`。

**运行期**（`DshHomeManager`）：

- 运行时根（`node/` + `dsh/`）：`PathManager.getConfigDir()/dsh-idea/runtime/<version>/`
  （开发态用环境变量 `DSH_IDEA_RUNTIME` 覆盖，如 `tooling/runtime-dev`）。
- **首次使用自举**（FR-02.1）：`hasRuntime()` 在本地缺失且无 override 时，从插件资源 `/runtime-bundle.zip`
  解压（幂等；zip 兼容顶层单目录前缀剥离 + zip-slip 防护）。
- DSH_HOME：`PathManager.getConfigDir()/dsh-idea/dsh-home/`（与运行时分离，不随版本变化）。
  插件幂等生成 `profiles/web/`（package.json + cordis.yml）与 `ide.yml`；dsh 首次启动时
  自愈创建 `profiles/node_modules` junction 指向运行时 dsh 树（实测验证）。
- 生成运行期文件：`dsh-home/.credentials.yaml`（从设置页）、`ide.yml`（patch，含 mcp-client 配置与 bridge 地址/token）。
- 初始化顺序：校验运行时（必要时解压）→ 生成 DSH_HOME → 写凭据 → 写 patch → 启动进程 → 健康检查。

### 3.3 DshProcessManager

- `ProcessBuilder`：`[node.exe, <dsh>/lib/bin.js, --profile, web, --host, 127.0.0.1, --port, 0, --patch, <ide.yml>]`；`directory = 项目根目录`；env：`DSH_HOME=<dsh-home>`、`DSH_IDE_BRIDGE_URL=http://127.0.0.1:<bridgePort>`、`DSH_IDE_TOKEN=<random>`；`redirectErrorStream=true` 或分别捕获。
- **参数列表直传，不走 shell**（兼容路径含空格/中文）。
- stdout 逐行读取：匹配 `dsh web: http://127.0.0.1:(\d+)` → 记录 webPort → HTTP GET `/`（或 `/api`）健康检查（超时 10s，重试 ≤10 次间隔 500ms）→ 回调通知工具窗口加载。
- 崩溃/退出监听：非预期退出（无 `stop` 标记）→ 通知 + 指数退避自动重启（500ms/2s/5s，≤3 次）→ 手动"重启"按钮；日志写入插件日志 + 工具窗口日志页。
- 停止：`stop(reason)` → `process.destroy()` + 平台无关的进程树终止（Windows 用 `taskkill /PID <pid> /T /F` 兜底）→ 清理状态。
- 状态机：`STOPPED → STARTING → RUNNING → STOPPED | CRASHED`；`CRASHED` 可 `RESTARTING`。

### 3.4 JCEF 工具窗口

- `JBCefBrowser` 放入 tool window content；加载 URL = 运行中实例的 webPort（未启动先显示启动页/进度，就绪后 loadURL）。
- 工具栏动作：附加项目工作区（FR-04.2，MVP：启动后自动把项目根注册为默认工作区，见 §5 启动链路）、外部浏览器打开、重启 dsh、审查改动（打开 review 页）、设置、日志。
- JCEF 不可用（`JBCefBrowser` 初始化异常）：占位页 + "外部浏览器打开"。
- 关闭工具窗口不终止 Node（保留会话）；项目关闭才终止。

### 3.4.1 默认工作区预注册（FR-04.2 实测落地）

dsh 的 workspace 是**显式注册制**：`storages/workspace.json` 无记录时，web UI 顶部显示
"选择一个工作区开始"，不会自动把进程 cwd 设为工作区（实测 dsh 0.1.0-rc.7，`workspaceIds: []`）。

插件在 dsh 健康检查通过后调用内部 RPC `POST /api/workspace.create`：

```json
{"type":"client-request","rpcId":"<uuid>","method":"workspace.create","payload":{"path":"D:/proj/MyApp"}}
```

- 127.0.0.1 loopback 信任围栏放行，无需鉴权头（实测 200）；
- **幂等**：同路径重复调用返回既有 workspace（`created:false`），不重复创建；
- 实现：`WorkspaceInitializer.ensureWorkspace(webUrl, projectPath)`，在
  `DshProcessManager.waitHealthy` 置 RUNNING 后调用；失败仅日志降级，不阻塞 UI；
- 验证：`WorkspaceInitializerTest`（4 例）+ `DshBootstrapSmokeTest` 增强（真实 dsh 启动后
  断言 `workspace.json` 出现项目路径）。

### 3.5 IDE Bridge Server（Kotlin）

- JDK `com.sun.net.httpserver.HttpServer`，绑定 `127.0.0.1` 随机端口；`X-DSH-IDE-Token` 校验（常量时间比较，SHA-256 摘要后 `MessageDigest.isEqual`）。
- JSON 序列化用平台自带 Gson（`com.google.gson.Gson`，util-8.jar；**2024.1 无 `com.intellij.util.json.JsonUtil`**）。
- 线程模型：`Executors.newCachedThreadPool`；VFS/PSI 操作经 `ReadAction.compute` 切后台线程安全读取；文件打开经 `ApplicationManager.getApplication().invokeAndWait` 切 EDT。
- 端点（均为 JSON，见 §4.1）；`selection`/`open-files`/`project-tree` 基于活动编辑器与 VFS：
  - 活动编辑器取 `FileEditorManager.selectedEditor as? TextEditor` 的 `Editor`（`Document` 无 selectionModel，选中状态在 Editor）；
  - 语言取 `LanguageUtil.getLanguageForPsi(project, vf)`（2024.1 无 `getLanguageForFile`）；
  - 文档修改态用 `FileDocumentManager.isDocumentUnsaved(doc)`（2024.1 `Document` 无 `isModified`）。
- 生命周期：与 Node 实例同生命周期（项目维度）；token 每次启动随机。

### 3.6 MCP 桥接

**MCP Server（`mcp-ide-server.mjs`）**：

- 复用 profile hoisted `node_modules` 的 `@modelcontextprotocol/sdk`（脚本置于 `$DSH_HOME` 下，node 向上查找命中 `profiles/node_modules`；若构建期已验证失败，则改为将 sdk 一并打包进脚本目录）。
- 用 SDK `StreamableHTTPServerTransport` 起 `127.0.0.1:<mcpPort>`；mcpPort 随机（`--port 0` 或 HttpServer 自选）。
- 注册工具（raw name → 参数 → 调 IDE Bridge，带 token）：
  - `ide_get_selection` → `GET /selection`
  - `ide_get_open_files` → `GET /open-files`
  - `ide_get_project_tree`（参数 `depth?`）→ `GET /project-tree`
  - `ide_get_sent_selection` → `GET /sent-selection?latest=1`
  - `ide_open_file`（参数 `path`）→ `POST /open-file`
  - `ide_reveal_file`（参数 `path`）→ `POST /reveal`
- 错误语义：Bridge 不可达 → 工具返回结构化错误（`{error: "ide bridge unreachable"}`），不抛未捕获异常导致 MCP 连接中断。

**patch 注入（`ide.yml`）**：

- 由插件生成（`McpPatchGenerator`），内容为 cordis loader patch 条目数组。**实测语法**（dsh 0.1.0-rc.7）：`--patch` 覆盖层只能修改已有条目或 `insert` 新增；新增 mcp-client 实例须用 `insert` 列表，且 `name` 字段必须显式声明插件包名：

```yaml
# ide.yml（McpPatchGenerator 生成，mcpPort 动态填入）
- insert:
    - id: mcp.ide
      name: '@deepseek-ai/dsh-mcp-client'
      config:
        serverName: ide
        transport: streamable-http
        url: http://127.0.0.1:<mcpPort>/mcp
        toolCallTimeoutMs: 60000
        reconnect:
          enabled: true
          maxAttempts: 3
```

- 模型侧工具名：`mcp__ide__ide_get_selection` 等（raw name 前缀 `mcp__<serverName>__`）。
- MCP server（`mcp-ide-server.mjs`）部署在 DSH_HOME 顶层；插件在 DSH_HOME 顶层创建 `node_modules` junction → runtime dsh 树，使 ESM 能向上解析 `@modelcontextprotocol/sdk`（dsh 自愈的 `profiles/node_modules` 不在 ESM 向上查找路径上，实测必需）。
- `failOnStartupError: true`（测试/诊断形态）：MCP 连接或工具同步失败即拒绝启动，用于冒烟验证。

### 3.7 代码上下文发送

- 编辑器右键动作"发送选中代码到 DSH"（`SendSelectionAction`，注册于 `EditorPopupMenu`，见 plugin.xml `<actions>`）：
  1. `ReadAction` 读选中文本/文件/语言（≤64KB，超出截断并注明 `…(已截断)`）；
  2. **直接写入 Bridge 的 sent-selection 队列**（`SentSelectionQueue`：容量 ≤10 条、单条 ≤64KB，环形淘汰）——智能体可随时经 `ide_get_sent_selection` 取回，**必达**；
  3. 聚焦工具窗口 + JCEF 注入预填 composer：轮询等待 dsh web 的 `<textarea>`（实测为 React 受控组件），原生 setter 设置 value + 派发 `input` 事件（触发 React onChange）；
  4. 注入失败/未运行 → 系统剪贴板 + 通知"请粘贴到输入框（代码已就绪）"。
- **紧凑文件引用**（v0.5.4，用户反馈迭代）：注入内容仅 `@绝对路径#L起始-结束` + 尾随换行
  （`buildCompactReference`），**无提示语、无代码本体**；注入后光标 `setSelectionRange` 移到
  文本末尾（引用行下一行），可直接输入问题。完整选中代码仍写入 Bridge sent-selection 队列
  （智能体可经 `ide_get_sent_selection` 取回，或经 fs 工具读文件对应行）。实测确认：dsh 输入
  触发菜单仅注册了 `/` 源，`@` 前缀（`roster.length===0`）不会弹菜单，可安全作为引用前缀。
- **技术边界（实测 dsh 0.1.0-rc.7）**：dsh 输入框**不支持**输入态"文件引用 chip（文件名+行号+X 删除）"——
  `@`/`/` 菜单仅注册了 workspace/command/skill/subagent 等源，无文件源；`fileMentions` 渲染仅匹配
  "本轮工具产出文件"（`producedFileMentions`），对用户选中发送的代码不生效。故采用紧凑引用文本方案。
- 智能体侧兜底：`ide_get_sent_selection` 随时可取最近推送的代码（即使注入失败也不丢上下文）。

### 3.8 审查面板（Review）

- **基线快照**（`SnapshotManager`）：工具窗口首次打开时执行；遍历项目根（`VfsUtilCore.visitChildrenRecursively` + `VirtualFileVisitor`，`visitFile` 返回 **Boolean**——false 跳过目录 children）：
  - 忽略：`.git`、`node_modules`、`build`、`out`、`.idea`、`target`、`dist`、`.gradle`、隐藏文件（`.` 前缀）及 >1MB 文件（`PathFilters`）；
  - 记录 `path → content(MD5 + 原始字节)`，内存字节 LRU ≤200MB（超限最旧条目字节落盘 `<md5>.bin`，md5/元数据恒在内存，diff/还原按需回读）；
  - 落盘：插件临时目录（`FileUtil.getTempDirectory()/dsh-idea/snapshots/<project>/`，不污染项目）；`index.txt` 存元数据加速重建。
- **审查**（`ReviewChangesAction` 打开 `ReviewDialog`）：对比当前盘面与基线 → 三类（modified/new/deleted）→ 按钮"查看 Diff"（`DiffManager.showDiff` + `SimpleDiffRequest`，基线文本 vs 当前文件，NEW/DELETED 用空侧）；动作：还原该文件（基线覆盖当前，NEW=删除、DELETED=重建）、还原全部、接受（忽略，丢弃基线）、重新基线（全量重扫）。
- **刷新**：打开审查前对项目根 `VfsUtil.markDirtyAndRefresh(false, false, true, root)`（4 参签名，2024.1）。
- **注意**：dsh 直接写盘，"接受改动"= 丢弃快照（无需回写）；还原 = 用快照覆盖当前文件（VFS 写）。

### 3.9 设置页

- `DshSettingsState`（`PersistentStateComponent`，application 级，跨项目共享）：
  - `apiKey`（`PasswordSafe`，`PasswordSafe.getInstance().setPassword`，state 只存引用名）
  - `model`（`deepseek-chat` 默认 / `deepseek-reasoner`）
  - `baseUrl`（默认 `https://api.deepseek.com`，可空）
  - `dshHomeOverride`（高级，默认 null → 用 `PathManager.getConfigDir()/dsh-idea/runtime/<version>/dsh-home`）
  - `logLevel`
- 应用行为：写 `.credentials.yaml`（`DEEPSEEK_API_KEY`）+ 可选 `.env`（`DSH_BASE_URL`/模型，键名 Step 3 spike 确认）；提示"重启会话生效"；"重启 dsh"按钮。
- `CredentialImporter`：读 `~/.dsh/.credentials.yaml` 的 `DEEPSEEK_API_KEY`（yaml 解析仅取该键），不存在/无键 → 提示。

### 3.10 国际化

- `messages/DshBundle.properties`（英文默认）+ `DshBundle_zh_CN.properties`（中文）；`DshBundle.message("key", args...)` 封装 `ResourceBundle`（UTF-8，`ResourceBundle.Control` 处理）。
- 覆盖：工具窗口标题/动作/状态、设置页、通知、审查面板、错误提示。Web UI 文案由 dsh 自带（不本地化）。

## 4. 接口契约

### 4.1 IDE Bridge HTTP API

| 方法 | 路径 | 鉴权 | 请求 | 响应 |
|---|---|---|---|---|
| GET | /health | token | — | `{ok, project, pid}` |
| GET | /selection | token | — | `{filePath, language, selection, lineStart, lineEnd}` |
| GET | /open-files | token | — | `{files:[{path, language, modified}]}` |
| GET | /project-tree | token | `?depth=4` | `{roots:[{path,name,type,children}]}` |
| POST | /sent-selection | token | `{id,filePath,language,selection}` | `{id}` |
| GET | /sent-selection | token | `?latest=1` | `{id,filePath,language,selection,ts}` 或 `{error:"empty"}` |
| POST | /open-file | token | `{path}` | `{ok}` |
| POST | /reveal | token | `{path}` | `{ok}` |

统一错误：`{error: string, code: string}`；未带/错 token → 401。

### 4.2 MCP 工具清单（模型侧名 `mcp__ide__*`）

| raw name | 参数 | 对应 Bridge | 说明 |
|---|---|---|---|
| ide_get_selection | — | GET /selection | 当前编辑器选中 |
| ide_get_open_files | — | GET /open-files | 打开文件列表 |
| ide_get_project_tree | `depth?` | GET /project-tree | 项目结构 |
| ide_get_sent_selection | — | GET /sent-selection?latest=1 | 最近发送的代码 |
| ide_open_file | `path` | POST /open-file | 在 IDE 打开（P1） |
| ide_reveal_file | `path` | POST /reveal | 项目树定位（P1） |

### 4.3 进程启动与环境

```
node.exe <dshHome>/profiles/node_modules/@deepseek-ai/dsh/lib/bin.js \
  --profile web --patch <dshHome>/ide.yml --host 127.0.0.1 --port 0
cwd      = <项目根目录>
env      = DSH_HOME=<dshHome>
           DSH_IDE_BRIDGE_URL=http://127.0.0.1:<bridgePort>
           DSH_IDE_TOKEN=<randomToken>
stdout   = 逐行读取；含 "dsh web: http://127.0.0.1:<webPort>"
```

> 注意：`--patch` 是启动器选项，必须位于 web 应用选项 `--host`/`--port` 之前；
> 放在后面会被 web 应用当作未知选项拒绝（实测 dsh 0.1.0-rc.7）。

### 4.4 运行时与 DSH_HOME 目录布局（插件独立）

```
<PathManager.getConfigDir()>/dsh-idea/
├── runtime/<version>/            # 运行时（随版本升级；开发态 DSH_IDEA_RUNTIME 覆盖）
│   ├── node/                     # Node.js 运行时（win-x64）
│   └── dsh/                      # npm 安装的 @deepseek-ai/dsh 树（含全部依赖）
├── dsh-home/                     # DSH_HOME（会话数据持久化，不随运行时版本变化）
│   ├── .credentials.yaml         # DEEPSEEK_API_KEY（设置页写入）
│   ├── .env                      # 可选：Base URL / 模型
│   ├── ide.yml                   # 运行期生成的 patch（mcp-client，McpPatchGenerator）
│   ├── mcp-ide-server.mjs        # MCP server 脚本（插件资源部署）
│   ├── node_modules/             # 顶层 junction → runtime/dsh/node_modules（MCP 脚本 ESM 解析 SDK）
│   ├── profiles/web/             # 物化的 web profile（package.json + cordis.yml）
│   ├── profiles/node_modules/    # dsh 首次启动自愈创建的 junction → runtime/dsh/node_modules
│   └── sessions/ storages/       # 会话数据（dsh 自动创建）
└── mcp-ide-server.mjs            # 运行期从插件资源部署到 dsh-home/（与上同，见 3.6）
```

### 4.5 patch 模板（`ide.yml`）

见 3.6；由 `McpPatchGenerator` 以实际 cordis loader 语法生成，mcpPort/token 动态填入。

## 5. 数据流

1. **启动链路**：工具窗口打开 → `DshProcessManager.start()` → 解压/校验运行时 → 写凭据/patch → spawn node（cwd=项目）→ 逐行读 stdout 解析 webPort → 健康检查 → `toolWindow.loadUrl(webPort)` → JCEF 加载 Web UI；用户对话 → dsh 智能体（fs 工具以 cwd=项目目录读写文件）。
2. **MCP 链路**：智能体调用 `mcp__ide__ide_get_selection` → dsh mcp-client → streamable-http → mcp-ide-server.mjs → fetch+bridge token → IDE Bridge（EDT 读 VFS/PSI）→ JSON 原路返回 → 智能体。
3. **发送代码链路**：编辑器动作 → 读选中 → POST /sent-selection（Bridge 队列）→ 聚焦工具窗口 + JS 注入（失败→剪贴板）→ 智能体经 `ide_get_sent_selection` 或提示文本获取。
4. **审查链路**：打开工具窗口 → 基线快照 → 用户点"审查改动" → VFS 刷新 → 对比 → DiffManager diff → 还原（VFS 写回快照）/忽略/重新基线。

## 6. 边界情况与失败模式

| 场景 | 处理 |
|---|---|
| 端口冲突 | 全随机端口（`--port 0` / HttpServer 随机），无固定端口 |
| Node 崩溃 | 通知（Notifications，Step 5）+ 指数退避自动重启（≤3 次）+ 手动重启；状态机 CRASHED；日志页可查输出 |
| 运行时缺失 | `DSH_IDEA_RUNTIME` 覆盖缺失 → 报错；无覆盖 → 自动从插件资源 `/runtime-bundle.zip` 解压（Step 5） |
| API Key 缺失/无效 | 健康检查后会话创建失败 → 工具窗口横幅"请配置 API Key"→ 跳设置；设置应用后提示重启会话 |
| dsh 启动超时（>60s） | 终止并报错，附日志片段；建议检查网络/杀软 |
| 多项目 | 每项目实例（`DshRuntimeRegistry`），并发 ≤3，超出提示；项目关闭即终止（`DshLifecycleManager`） |
| IDE 退出 | `DshAppLifecycleListener.appClosing` 兜底终止全部存活面板（进程树） |
| 项目关闭时任务运行中 | 终止进程并提示"任务可能未完成" |
| JCEF 初始化失败 | 占位页 + 外部浏览器打开 |
| Web UI DOM 变化（注入失效） | 降级剪贴板；`ide_get_sent_selection` 兜底 |
| 大项目 | 快照忽略规则 + 1MB 上限 + 200MB LRU |
| 路径含空格/中文 | ProcessBuilder 参数列表直传 |
| Remote Dev / Gateway | 检测到远程开发环境 → 工具窗口提示不支持 |
| 杀软拦截 node.exe | 启动失败提示 + 日志 + 文档（白名单说明） |
| JCEF 中文输入法异常 | "外部浏览器打开"按钮 |

## 7. 测试策略

### 7.1 单元测试（JUnit，Gradle `test`）

- `PortParserTest`：stdout 行解析（含多行/前缀/异常格式）
- `SnapshotDiffTest`：modified/new/deleted 判定、忽略规则、容量上限
- `McpPatchGeneratorTest`：patch yaml 生成与占位符替换
- `CredentialImporterTest`：`~/.dsh/.credentials.yaml` 解析（临时文件）
- `PathFiltersTest`：忽略规则
- `SentSelectionQueueTest`：环形容量、64KB 截断、id 序（Step 4）
- `DshRuntimeRegistryTest`：并发上限 3、释放名额、幂等（Step 5）

### 7.2 集成冒烟（Gradle `test` + `DSH_IDEA_RUNTIME`）

- `DshBootstrapSmokeTest`：临时 DSH_HOME + 假凭据，spawn `dsh web --port 0`，断言 stdout 端口 + HTTP 200；
  设置了 `DSH_IDEA_RUNTIME` 时自动执行，否则跳过（CI 无运行时环境）。
- `DshMcpBridgeSmokeTest`（Step 3）：JDK mock bridge + mcp-ide-server.mjs（部署到临时 DSH_HOME，顶层 junction 解析 SDK）+
  `tools/list` 断言 6 个 `ide_*` 工具 + `tools/call` 桥接返回 + dsh web 带 `failOnStartupError: true` patch 启动（连接失败即拒绝启动，能起来即证明 MCP 链路通）。
- 本地执行：`$env:DSH_IDEA_RUNTIME="<runtime 目录>"; gradle test`（实测通过）。

### 7.3 手工验收（`runIde`，Step 5 执行）

PRD §7 验收清单 8 条。

## 8. 实现步骤分解与验收

| 步骤 | 交付物 | 验收 |
|---|---|---|
| Step 0 文档 | docs/PRD.md、docs/DESIGN.md、docs/README.md | 覆盖 §2-§7 全部条目，无未决设计决策 |
| Step 1 骨架 | Gradle 工程、plugin.xml、工具窗口壳、设置页骨架、i18n | `buildPlugin` 成功；`runIde` 可打开工具窗口/设置 |
| Step 2 运行时 | build-runtime.ps1、DshHomeManager、DshProcessManager、JCEF 加载 | 全新实例端到端对话；API Key 生效；进程随项目关闭终止 |
| Step 3 MCP | IdeBridgeServer、mcp-ide-server.mjs、McpPatchGenerator、spike 验证 patch | "当前打开的文件是什么"可答；`tools/list` 6 工具 |
| Step 4 集成 | 发送选中代码动作、审查面板 | 上下文可送达；diff/还原可用 |
| Step 5 加固 | 生命周期、崩溃 UX、日志页、打包、验收清单 | PRD §7 八条全过 |
| Step 6 评审 | 总结、遗留问题、后续规划 | 文档收尾更新 |

## 9. 变更记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-02-11 | v0.1 | 初稿：依据已确认决策（JCEF 嵌入、内嵌运行时、独立 DSH_HOME、MCP 桥接、中英双语、Windows 优先）编写 |
| 2026-08-19 | v0.2 | Step 2 实现落地：运行时布局改为 runtime/node + runtime/dsh（npm 安装）与 DSH_HOME 分离（junction 自愈）；`--patch` 必须位于 web 应用选项之前（实测 dsh 0.1.0-rc.7）；新增 scripts/build-runtime.ps1 与 buildRuntime 任务 |
| 2026-08-19 | v0.3 | Step 3 MCP 桥接落地：patch 语法修正为 `insert` + 显式 `name` 字段（实测）；新增 IdeBridgeServer/mcp-ide-server.mjs/McpPatchGenerator/DshBridgeManager；DSH_HOME 顶层 node_modules junction 供 ESM 解析 SDK；2024.1 API 勘误（Gson、getLanguageForPsi、isDocumentUnsaved、TextEditor.editor） |
| 2026-08-19 | v0.4 | Step 4 IDE 集成落地：SendSelectionAction（sent-selection 队列直写 + JCEF textarea 注入预填 + 剪贴板降级）、SnapshotManager（200MB LRU + 字节落盘）、ReviewManager/ReviewChangesAction（diff/还原/忽略/重新基线）；API 勘误（VirtualFileVisitor.visitFile 返回 Boolean、markDirtyAndRefresh 4 参、Notification 4 参） |
| 2026-08-19 | v0.5 | Step 5 加固与发布落地：DshLifecycleManager/DshAppLifecycleListener（项目/IDE 关闭终止进程树）、DshRuntimeRegistry（并发 3）、崩溃通知、DshLogPanel 日志页、logLevel 设置、运行时打包自举（runtime-bundle.zip → 插件资源 → 首次解压；实测 106.9MB/解压 62s/可启动） |
| 2026-08-20 | v0.5.1 | 手工测试修复：①工具窗口默认选中主界面（日志 tab 不再抢焦点）；②默认工作区预注册（WorkspaceInitializer 调 dsh RPC workspace.create，UI 打开即选中当前项目；含 DshBootstrapSmokeTest 真实启动断言） |
| 2026-08-20 | v0.5.2 | 发送选中代码增强：输入框注入结构化引用文本（路径 + 行号 + 代码块 + 提示语） |
| 2026-08-20 | v0.5.3 | 紧凑文件引用：注入 `@路径#L起始-结束` + 提示语（不填代码本体） |
| 2026-08-20 | v0.5.4 | 输入框仅 `@路径#L起始-结束` + 换行（去提示语），光标自动落下一行；§3.7 同步更新 |
| 2026-08-20 | v0.5.5 | 工具窗口标题动作区分图标（Settings/Web/Diff/Restart） |
| 2026-08-20 | v0.5.6 | 右键动作图标与插件一致（plugin.xml icon → dsh-toolwindow.svg） |
| 2026-08-20 | v0.5.7 | 插件 Overview / What's New 中英双语（plugin.xml description/change-notes） |
| 2026-08-20 | v0.5.8 | 一键打包脚本 scripts/build-plugin.bat；新增 docs/PROJECT_NOTES.md 知识库 |
| 2026-08-20 | v0.5.9 | Step 6 里程碑评审：新增 docs/MILESTONE_REVIEW.md；§3.1 修正 intellij 版本为 1.17.4（2.x 为技术债）；补 v0.5.2–v0.5.8 变更记录；测试 36/36 复跑通过 |
