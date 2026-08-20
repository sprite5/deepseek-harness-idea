# 项目知识库 / 开发备忘（跨会话参考）

> 本文汇总 DeepSeek Harness IDEA 插件开发过程中的**实测环境事实、踩坑记录、dsh 行为结论**，
> 供后续任务（Step 6 评审及之后的维护/升级）直接参考，避免重复调查。
> 最后更新：2026-08-20（Step 1–5 完成 + 手工测试修复 + 一键打包脚本 + Step 6 里程碑评审）

---

## 1. 本机构建 / 运行环境（实测）

| 项 | 结论 |
|---|---|
| 目标 IDE | IntelliJ IDEA Community/Ultimate **2024.1+**（`intellij.version = 2024.1.7`，since-build 241，until 251.*） |
| 构建 JDK | **必须 JBR 21**：`D:\develop\IntelliJ IDEA 2024.3.4.1\jbr`（`instrumentCode` 需要 JBR 布局；jdk-17 会报 `D:\develop\Java\jdk-17\Packages does not exist`） |
| Gradle | `tooling/gradle-8.14/bin/gradle.bat`（自带发行版）；**勿用系统 gradle-7.2**（native 库初始化失败且过旧） |
| Gradle 用户目录 | `GRADLE_USER_HOME=D:\develop\gradle-7.2\.gradle\repository`（缓存已就位，含 ideaIC 2024.1.7 约 1GB） |
| 运行时开发目录 | `tooling/runtime-dev`（`DSH_IDEA_RUNTIME` 指向它）；`build/runtime` 是构建产物（含 bundle） |
| 自动化沙箱 | pwsh 沙箱拦截工作区外读写与部分出站网络 → **gradle/npm 命令需完整沙箱权限**（仅自动化环境；用户本机无此限制） |
| 一键打包 | `scripts/build-plugin.bat`（双击；自动探测 JBR/Gradle 缓存，`--no-daemon`，输出产物路径） |
| 版本号 | 插件版本 = `build.gradle.kts` 第 13 行 `version`；**勿动** `DshHomeManager.DSH_VERSION`（= dsh 运行时版本 `0.1.0-rc.7`，决定运行时目录名） |

### 常用命令（自动化环境需完整权限）

```powershell
# 环境
$env:JAVA_HOME = "D:\develop\IntelliJ IDEA 2024.3.4.1\jbr"
$env:GRADLE_USER_HOME = "D:\develop\gradle-7.2\.gradle\repository"
$env:DSH_IDEA_RUNTIME = "D:\develop\deepSeekWorkSpace\code\deepSeekForIdea\tooling\runtime-dev"

# 全量测试（含真实 dsh 冒烟；无 DSH_IDEA_RUNTIME 时冒烟自动跳过）
tooling\gradle-8.14\bin\gradle.bat test
# 打包
tooling\gradle-8.14\bin\gradle.bat buildPlugin
# 构建并打包运行时（Bundle 产物 → 插件资源）
tooling\gradle-8.14\bin\gradle.bat bundleRuntime
```

### Gradle 缓存踩坑（重要）

- **`Failed to create Jar file ...\caches\jars-9\<hash>\xxx.jar`** = 有**残留 Gradle daemon**（常为 jdk-11 老 daemon）锁着缓存。
  解法：`gradle --stop` → 杀残留 java 进程（确认路径不是 IDE 的 JBR）→ 删 `jars-9` 对应 hash 目录 → 重试。
- 一键脚本用 `--no-daemon` 正是为避免此类锁冲突（每次构建单次 JVM，隔离干净）。
- `gradlew` wrapper 在本机不可用：wrapper 的 `GRADLE_USER_HOME` 指向不可写目录且未预下载 8.14 发行版。

---

## 2. 插件结构速览

```
src/main/kotlin/com/deepseek/harness/idea/
├── runtime/   DshHomeManager(运行时/DSH_HOME/解压自举) · DshProcessManager(进程+端口发现+重启)
│             PortParser · DshCredentials(PasswordSafe) · WorkspaceInitializer(默认工作区)
│             DshRuntimeRegistry(并发≤3) · DshLifecycleManager / DshAppLifecycleListener(生命周期)
├── bridge/    IdeBridgeServer(HTTP+token) · DshBridgeManager(编排) · SentSelectionQueue(环形队列)
│             IdeBridgeResources(读 mcp-ide-server.mjs)
├── mcp/       McpPatchGenerator(ide.yml patch)
├── review/    SnapshotManager(基线快照) · SnapshotDiff · ReviewManager
├── ui/        DshToolWindowFactory(工具窗口+JCEF+注入) · SendSelectionAction · ReviewChangesAction
│             DshLogPanel(日志页)
├── settings/  DshSettingsState · DshSettingsConfigurable · CredentialImporter
└── i18n/      DshBundle
src/main/resources/
├── mcp-ide-server.mjs        # MCP server（随插件部署到 DSH_HOME）
├── runtime-bundle.zip        # 构建期打入（buildRuntime -Bundle 产物）
├── icons/dsh-toolwindow.svg  # 插件图标（工具窗口/右键动作共用）
├── messages/DshBundle*.properties
└── META-INF/plugin.xml       # 工具窗口/动作/服务/监听器/Overview/What's New
```

---

## 3. dsh 行为事实（0.1.0-rc.7 实测结论）

### 3.1 启动与 patch

- 启动命令（**`--patch` 必须在 web 应用选项之前**，否则 `unknown option '--patch'`）：
  `node <dsh>/lib/bin.js --profile web --patch <ide.yml> --host 127.0.0.1 --port 0`
- stdout 打 `dsh web: http://127.0.0.1:<port>`；`--port 0` 随机端口。
- **patch 语法（关键）**：`--patch` 是覆盖层，**只能改已有条目或用 `insert:` 新增**；新增 mcp-client 必须：
  ```yaml
  - insert:
      - id: mcp.ide
        name: '@deepseek-ai/dsh-mcp-client'   # name 字段必须显式
        config: { serverName: ide, transport: streamable-http, url: http://127.0.0.1:<port>/mcp, ... }
  ```
- `failOnStartupError: true` 时 MCP 连接/同步失败即拒绝启动（冒烟测试用它验证链路）。
- DSH_HOME 首次启动会**自愈创建** `profiles/node_modules` junction → 运行时 dsh 树；
  但 **ESM 向上查找不会命中 `profiles/node_modules`** → 插件需在 DSH_HOME **顶层**另建 `node_modules` junction 供 mcp-ide-server.mjs 解析 SDK。

### 3.2 默认工作区（Workspace）

- workspace 是**显式注册制**：`storages/workspace.json` 无记录时 UI 显示"选择一个工作区开始"，**不会自动用 cwd**。
- 插件解法：健康检查后调内部 RPC `POST /api/workspace.create`，body
  `{"type":"client-request","rpcId":"<uuid>","method":"workspace.create","payload":{"path":"D:/proj"}}`；
  **127.0.0.1 loopback 信任围栏放行，无需鉴权头**；幂等（同路径返回既有 workspace）。
- 其他 RPC 同构：`POST /api/<method>`，`session.create` 接受 `cwd` 或 `workspaceId`。

### 3.3 输入框 / 文件引用（重要边界）

- **dsh 0.1.0-rc.7 输入框不支持"文件引用 chip（文件名+行号+X 删除）"**——`@`/`/` 输入触发菜单
  仅注册了 `/`（command）等源，**无文件源**；`@` 前缀无源时菜单不弹（`roster.length===0` → close），可安全作引用前缀。
- `fileMentions` 渲染（消息里反引号路径 → 可点击文件 chip）**只匹配"本轮工具产出文件"**（`producedFileMentions`），
  对用户手动发送的代码路径不生效。
- 因此"发送选中代码"采用**紧凑引用文本**：注入 `@绝对路径#L起始-结束\n`（无代码本体、无提示语），
  光标 `setSelectionRange` 移到末尾下一行；完整代码存 Bridge `sent-selection` 队列（`ide_get_sent_selection` 兜底）。

### 3.4 其他

- composer 是标准 React 受控 `<textarea>`：外部注入需原生 setter + `input` 事件（`dispatchEvent(new Event('input',{bubbles:true}))`）。
- MCP SDK：`@modelcontextprotocol/sdk@1.30.0`（ESM；`StreamableHTTPServerTransport` + `createMcpExpressApp`，stateless 模式 `sessionIdGenerator: undefined`）。
- 网络：本机 npm 走 `registry.npmmirror.com`（`npm_config_registry`）；curl/Invoke-WebRequest 常失败，**用 node fetch 最稳**（`scripts/download-node.mjs` 即如此）。

---

## 4. 踩坑记录（含修复）

| 坑 | 现象 | 根因 / 修复 |
|---|---|---|
| runtime 树被清空 | `@deepseek-ai/dsh` 等包目录全空、boot 报 MODULE_NOT_FOUND | **junction 陷阱**：递归删除含 junction 的目录会跟随删掉目标（`Remove-Item -Recurse` 与 JUnit `@TempDir` 清理均如此）。修复：删除前先断链（Windows junction 需 `LinkOption.NOFOLLOW_LINKS` 检测 `isOther`）；测试 tearDown 先 `unlinkJunctions` |
| bat 中文乱码 | `'A' is not recognized` / 命令被拆 | write 工具产出 UTF-8 无 BOM 的 bat，cmd/GBK 解析中文错乱。修复：**bat 全英文纯 ASCII**（见 build-plugin.bat） |
| PowerShell 变量 | `$home` 赋值报"read-only" | `$HOME` 是只读变量，测试/脚本变量名避开 `home`（用 `$dshHome`） |
| UTF-8 BOM | dsh 读 `package.json` 报 JSON 解析失败 | PowerShell `Set-Content -Encoding UTF8` 会写 BOM；用 `[System.IO.File]::WriteAllText(..., UTF8Encoding($false))` |
| 2024.1 API 勘误 | 编译失败 | 见下表 |
| 沙箱 spawn EPERM | npm/子进程 `spawn EPERM` | 沙箱禁管道 stdio；npm 用 `--ignore-scripts`（原生依赖预编译无需 postinstall），Node 子进程用 `stdio:'ignore'`+轮询端口 |
| Gradle 缓存锁 | 见 §1 | `--no-daemon` + 杀残留 daemon |

### 2024.1 API 勘误（编译期验证）

- 无 `com.intellij.util.json.JsonUtil` → 用 Gson（`com.google.gson.Gson`，平台自带）。
- `LanguageUtil.getLanguageForFile(vf)` 不存在 → `getLanguageForPsi(project, vf)`。
- `Document` 无 `isModified` → `FileDocumentManager.isDocumentUnsaved(doc)`；Document 无 `selectionModel` → 用 `(FileEditorManager.selectedEditor as? TextEditor)?.editor`。
- `VfsUtil.visitChildrenRecursively` 不存在 → `VfsUtilCore.visitChildrenRecursively` + `VirtualFileVisitor`（**`visitFile` 返回 `Boolean`**，false=跳过 children；不是 Result）。
- `VfsUtil.markDirtyAndRefresh` 是 **4 参** `(async, recursive, sync, vararg files)`。
- `Notification` 内容版是 **4 参** `(groupId, title, content, type)`（3 参无内容）。
- `AppLifecycleListener.appClosing()`（`applicationListeners` 注册）；`ProjectManagerListener.projectClosed(project)`。
- PasswordSafe 241：`setPassword(CredentialAttributes, String?)` / `getPassword(...)`；旧三参不可用。

---

## 5. 打包 / 运行时（Step 5 实测）

- 链路：`buildRuntime`（build-runtime.ps1：下载 Node 22.23.2 + SHA256 校验 + npm 装 dsh）→ `-Bundle` 产出
  `build/runtime-bundle.zip`（**zip 根直接 `node/`+`dsh/`**，排除源 zip 与 npm 缓存；实测 106.9MB）→
  Gradle `bundleRuntime` 复制到 `build/plugin-runtime/` → `processResources` 依赖它打入 jar（`/runtime-bundle.zip`）。
- 运行期自举：`DshHomeManager.hasRuntime()` 在本地缺失且无 `DSH_IDEA_RUNTIME` 时从插件资源解压
  （幂等；`unzip` 兼容顶层单目录前缀剥离 + zip-slip 防护；实测解压 62s，解压后 dsh web 可启动）。
- 插件 zip 约 98MB（含 106.9MB 压缩运行时），符合 PRD 体积预期（150-300MB 内）。
- Node 版本：**v22.23.2**（npmmirror 二进制镜像；SHA256 `1177B413...`）；dsh 固定 `@deepseek-ai/dsh@0.1.0-rc.7`。

---

## 6. 测试体系

| 层 | 类 | 说明 |
|---|---|---|
| 单元 | PortParserTest / McpPatchGeneratorTest / SnapshotDiffTest / PathFiltersTest / SentSelectionQueueTest / WorkspaceInitializerTest / DshRuntimeRegistryTest / CredentialImporterTest | 纯 JUnit，无 IDE 依赖 |
| 集成冒烟 | DshBootstrapSmokeTest（真实 dsh 启动 + workspace 注册断言）/ DshMcpBridgeSmokeTest（mock bridge + MCP tools/list 6 工具 + failOnStartupError 严格启动） | 需 `DSH_IDEA_RUNTIME`，否则跳过 |

- 冒烟测试注意：临时 DSH_HOME 内 dsh 自愈 junction 指向 runtime → **tearDown 必须先 unlinkJunctions 再让 `@TempDir` 清理**，否则清空 runtime（见 §4）。
- 测试计数：**36 个**（截至 v0.5.9，Step 6 复跑通过；沙箱下需完整权限，否则 Gradle native 服务初始化失败）。

---

## 7. 后续任务参考（Step 6 之后）

1. ~~**Step 6 里程碑评审**~~ ✅ 已完成（2026-08-20）：见 docs/MILESTONE_REVIEW.md —— Step 0–5 总结、FR/US/非目标覆盖矩阵、
   遗留问题 A（手工验收 5 项）/B（功能缺口）/C（技术债）/D（文档债）分级、PRD §9 风险回顾、v0.6/v0.7/v1.x 规划。
2. **v0.6 验收闭环**（next）：PRD §7 手工验收项（见 docs/ACCEPTANCE.md 与 MILESTONE_REVIEW §3-A）——
   安装 zip、真实 API Key 对话、DiffManager UI、JCEF 注入效果、进程清理端到端——需真实 IDE 会话人工确认；
   另含 B-1"发送当前文件"动作、C-6 杀软白名单文档、C-5 版本号对齐。
3. **已知改进项**（MILESTONE_REVIEW §3-B/C 明细）：
   - `org.jetbrains.intellij` 1.17.4 → 2.x（`org.jetbrains.intellij.platform`）升级（C-1）。
   - dsh 版本升级：改 `DshHomeManager.DSH_VERSION` + 重建运行时（build-runtime.ps1 参数化）+ 回归 Step 3 patch 语法（C-2）。
   - 多项目并发 3 上限后续可优化为单实例多工作区（C-3）。
   - `ide_open_file`/`ide_reveal_file` 目前实现为"打开"，项目树定位（reveal）可再增强（B-3）。
   - MCP patch 的 `failOnStartupError` 仅测试形态；生产可用 `reconnect` 语义（C-4）。
   - 远程开发/Gateway 环境检测提示未实现（B-2）；输入框文件引用 chip 为上游 dsh 能力，随版本升级跟踪（B-4）。
4. **文档约定**：改代码前先更新 PRD/DESIGN（活文档）；每个实现步骤后在 README 变更记录追加。
