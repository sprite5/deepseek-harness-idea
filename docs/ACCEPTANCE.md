# PRD §7 验收清单走查（Step 5）

执行日期：2026-08-19 ｜ 依据：docs/PRD.md §7 成功标准

| # | 验收项 | 状态 | 验证方式 | 备注 |
|---|---|---|---|---|
| 1 | 从磁盘安装构建出的 zip 到独立 IDEA 2024.1+（Windows）实例，无报错 | ⏳ 手工 | `gradle buildPlugin` → `deepseek-harness-idea-0.1.0.zip`（98.1MB，含运行时） | 本环境无法启动独立 IDE 会话；zip 结构已验证（plugin.jar + kotlin-stdlib + annotations） |
| 2 | 设置页填入 DeepSeek API Key 后工具窗口内可对话；智能体可读项目文件 | ⏳ 手工 | 需真实 API Key + IDE 会话 | 凭据链路已自动化：设置页写 PasswordSafe → `.credentials.yaml`（`DshHomeManager.syncCredentials`，Step 2 实现） |
| 3 | 指示智能体"新建 `src/Hello.java`"，文件出现在项目树并可打开 | ⏳ 手工 | 真实对话 | 底层已就绪：dsh cwd=项目根（Step 2）+ fs 工具 |
| 4 | 指示智能体修改某文件，审查面板出现 diff，还原后恢复 | ✅ 自动（逻辑层） | `SnapshotDiffTest`（5/5）、`ReviewManager` 三类还原（MODIFIED 覆盖/NEW 删除/DELETED 重建） | UI 交互（DiffManager 面板）需 IDE 会话手工确认 |
| 5 | 选中代码右键发送，会话中出现提示，智能体可获取 | ✅ 自动（链路层） | `SentSelectionQueueTest`（5/5）+ `DshMcpBridgeSmokeTest`（`ide_get_sent_selection` 读回） | JCEF 注入部分（Step 4）需 IDE 会话确认；剪贴板降级已实现 |
| 6 | 提问"当前打开的文件是什么"，智能体经 MCP 工具正确回答 | ✅ 自动 | `DshMcpBridgeSmokeTest`：`tools/list` 6 个 `ide_*` 工具 + `tools/call` 桥接返回（22s，真实 dsh） | — |
| 7 | 关闭项目/IDE 后无残留 node 进程 | ✅ 自动（逻辑层） | `DshLifecycleManager`（ProjectManagerListener）+ `DshAppLifecycleListener`（AppLifecycleListener.appClosing 终止全部面板）；`DshRuntimeRegistry.release` | 进程树终止实测：`killTree` 用 `taskkill /T /F`（Step 2）；IDE 会话级确认需手工 |
| 8 | 切换 IDE 语言（中/英）后插件文案跟随 | ✅ 自动（结构） | `DshBundle`（DynamicBundle）+ `DshBundle.properties` + `DshBundle_zh_CN.properties` 双份齐全（Step 1 起） | — |

## 自动化测试覆盖合计

- 单元：SentSelectionQueue 5、McpPatchGenerator 4、SnapshotDiff 5、DshRuntimeRegistry 3、PortParser 4、CredentialImporter 4、PathFilters 5、WorkspaceInitializer 4 = **34**
- 集成冒烟（真实 dsh 运行时）：DshBootstrapSmokeTest 1、DshMcpBridgeSmokeTest 1 = **2**
- **合计 36/36 通过**（Step 6 复跑确认，2026-08-20）

## 需人工 IDE 会话确认的项

1. 安装 zip 到独立 IDE 实例无报错（含首次运行时解压自举）。
2. 真实 API Key 对话；智能体建/改文件。
3. 审查面板 DiffManager UI 交互与还原。
4. 发送选中代码的 JCEF 注入效果（注入失败剪贴板降级已就绪）。
5. 关闭项目/退出 IDE 的进程清理（自动化覆盖逻辑层）。

## Step 6 结论

以上 5 项已作为**遗留问题 A-1～A-5** 归档至 [MILESTONE_REVIEW.md](./MILESTONE_REVIEW.md)（v0.6 验收闭环 backlog）；
自动化（逻辑层）验收项 4/6/8 及第 5 项链路在 36/36 测试中持续守护。
