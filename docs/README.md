# DeepSeek Harness IDEA 插件 — 项目文档

本仓库开发一个 IntelliJ IDEA 插件（类 Qoder）：在 IDE 内嵌入 DeepSeek Harness（DSH）Web UI，让智能体能读写当前项目文件、通过 MCP 调用 IDE 能力，并提供代码上下文发送与 diff 审查/还原等原生集成。

## 文档索引

| 文档 | 说明 | 状态 |
|---|---|---|
| [PRD.md](./PRD.md) | 规划需求文档：目标、用户故事、功能/非功能需求、验收标准、风险 | 草稿 |
| [DESIGN.md](./DESIGN.md) | 详设文档：架构、模块设计、接口契约、数据流、测试策略 | 草稿 |
| [ACCEPTANCE.md](./ACCEPTANCE.md) | PRD §7 验收清单走查（Step 5 执行，自动化 vs 手工项） | 走查中 |
| [MILESTONE_REVIEW.md](./MILESTONE_REVIEW.md) | 里程碑评审（Step 6）：Step 0–5 总结、需求覆盖矩阵、遗留问题、风险回顾、后续规划 | ✅ 完成 |
| [PROJECT_NOTES.md](./PROJECT_NOTES.md) | 项目知识库：本机构建环境、dsh 行为实测、踩坑记录、2024.1 API 勘误、后续任务参考 | 维护中 |

## 文档约定

- 文档使用中文编写，代码与技术术语保留英文。
- PRD 与 DESIGN 为**活文档**：实现过程中如有决策变化，先更新文档再改代码。
- 每个实现步骤结束后，在下方变更记录追加一条，并同步更新对应章节。

## 变更记录

| 日期 | 版本 | 变更内容 |
|---|---|---|
| 2026-02-11 | v0.1 | 初稿：基于已确认的产品与技术决策生成 PRD 与 DESIGN（Step 0） |
| 2026-08-19 | v0.2 | Step 2 运行时自举实现：DshHomeManager/DshProcessManager/PortParser、JCEF 工具窗口、凭据同步、build-runtime.ps1 + buildRuntime 任务、冒烟测试（含真实 dsh web 启动） |
| 2026-08-19 | v0.3 | Step 3 MCP 桥接实现：IdeBridgeServer、mcp-ide-server.mjs（6 个 ide_* 工具）、McpPatchGenerator、DshBridgeManager、DSH_HOME 顶层 node_modules junction、MCP 冒烟测试；spike 实测 patch `insert:` 语法与 failOnStartupError 严格验证 |
| 2026-08-19 | v0.4 | Step 4 IDE 集成实现：SendSelectionAction（读选中 → sent-selection 队列 → JCEF 注入预填，失败降级剪贴板）、SnapshotManager/SnapshotDiff/ReviewManager/ReviewChangesAction（基线快照 + diff + 还原/忽略/重新基线）、PathFilters；新增 PathFiltersTest/SnapshotDiffTest/SentSelectionQueueTest |
| 2026-08-19 | v0.5 | Step 5 加固与发布：DshLifecycleManager/DshAppLifecycleListener（项目关闭 + IDE 退出终止进程树）、DshRuntimeRegistry（并发上限 3）、崩溃通知、日志页（DshLogPanel）、设置页 logLevel、运行时打包（runtime-bundle.zip 打入插件 + 首次解压自举）、DshRuntimeRegistryTest；PRD §7 验收走查 |
| 2026-08-20 | v0.5.1 | 手工测试修复：①工具窗口默认选中主界面（日志 tab 不再抢焦点）；②默认工作区预注册（WorkspaceInitializer 调 dsh RPC workspace.create，UI 打开即选中当前项目；真实启动冒烟断言） |
| 2026-08-20 | v0.5.2 | 发送选中代码增强为结构化引用文本（`文件路径`(行 a-b) + 代码块 + 提示语）；实测确认 dsh 输入框无原生文件引用 chip，采用结构化文本方案 |
| 2026-08-20 | v0.5.3 | 紧凑文件引用：输入框改为 `@路径#L起始-结束` + 提示语（不填充代码本体，避免内容过多）；完整代码仍入 sent-selection 队列 |
| 2026-08-20 | v0.5.4 | 输入框仅显示 `@路径#L起始-结束` + 换行（去掉提示语），光标自动落到下一行等待输入 |
| 2026-08-20 | v0.5.5 | 工具窗口标题动作区分图标：Settings(齿轮)/Open in browser(Web)/Review changes(Diff)/Restart(Restart) |
| 2026-08-20 | v0.5.6 | 右键"Send Selection to DSH"动作图标与插件一致（plugin.xml icon 指向 dsh-toolwindow.svg） |
| 2026-08-20 | v0.5.7 | 插件描述（Overview）与更新说明（What's New）中英双语完善（plugin.xml description/change-notes） |
| 2026-08-20 | v0.5.8 | 一键打包脚本 scripts/build-plugin.bat（自动探测 JBR/Gradle 缓存，--no-daemon）；新增 docs/PROJECT_NOTES.md 项目知识库（环境/踩坑/API 勘误/dsh 行为） |
| 2026-08-20 | v0.5.9 | Step 6 里程碑评审：新增 docs/MILESTONE_REVIEW.md（Step 0–5 总结、FR/US/非目标覆盖矩阵、遗留问题 A–D 分级、PRD §9 风险回顾、v0.6/v0.7/v1.x 规划）；同步修正 DESIGN.md（§3.1 intellij 1.17.4、补 v0.5.2–0.5.8 变更记录）与 ACCEPTANCE.md（测试合计 32→36）；测试 36/36 复跑通过 |
| 2026-08-20 | v1.0.0 开源 | 项目开源发布至 GitHub（MIT）：仓库 tieJiangW/deepseek-harness-idea（main + v0.1.0 tag）；新增根 README.md（中英）、LICENSE、.gitattributes；首次提交 47 文件；Release v0.1.0 含插件 zip 附件（98MB，含内嵌运行时） |
| 2026-08-20 | v0.1.1 | 兼容修复：`until-build` 251.* → 262.*（用户 IDEA 2026.2/build 262 安装报错，前向编译验证通过）；Gson → 自研 `JsonCodec`（移除平台 Gson 依赖）；新增 JsonCodecTest 9 例；测试 36→45 |

## 实施进度

| 步骤 | 内容 | 状态 |
|---|---|---|
| Step 0 | 文档（PRD + DESIGN + 索引） | ✅ 完成 |
| Step 1 | 项目骨架（Gradle / plugin.xml / 工具窗口壳 / 设置页骨架 / i18n） | ✅ 完成 |
| Step 2 | 运行时自举（打包 Node+dsh / 进程管理 / JCEF 加载 Web UI / 端到端对话） | ✅ 完成 |
| Step 3 | MCP 桥接（IDE Bridge Server / MCP server / patch 注入） | ✅ 完成 |
| Step 4 | IDE 集成（发送选中代码 / 审查面板 diff+还原） | ✅ 完成 |
| Step 5 | 加固与发布（生命周期 / 崩溃 UX / 日志页 / 打包 / 验收） | ✅ 完成 |
| Step 6 | 里程碑评审（总结 / 遗留问题 / 后续规划） | ✅ 完成 |
