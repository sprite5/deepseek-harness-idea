# DeepSeek Harness for IntelliJ IDEA

把 [DeepSeek Harness](https://github.com/deepseek-ai) 智能体工作台完整嵌入 IntelliJ IDEA：
在 IDE 内嵌的 Web 界面中与智能体对话，让它读写你的项目文件，通过 MCP 桥接获取 IDE 上下文，
并用原生 diff 审查/还原它的改动。

> 本插件非 DeepSeek 官方产品，DeepSeek Harness 与 DeepSeek 商标归其各自所有者。

## 致谢 / Acknowledgments

- 感谢 **[dsh-mobile-hanui](https://github.com/Z-6354/dsh-mobile-hanui)**（MIT）：窄视口下把侧栏/详情
  面板变成抽屉 + 悬浮菜单按钮，内嵌工具窗里菜单不再挤占编辑空间。本插件已默认内置（源码直接打入插件）。
- 感谢原作者 **[fieJiangW/deepseek-harness-idea](https://github.com/fieJiangW/deepseek-harness-idea)**
  （MIT）：本仓库 fork 自其开源版本，内嵌运行时、MCP 桥接、快照审查等核心能力源自该项目。

> **不再跟随上游 / No longer following upstream**：本仓库 fork 后已独立演进，**不再跟随
> `fieJiangW/deepseek-harness-idea` 的更新**，也不承诺与其保持一致。

## 与上游的差异 / Differences from upstream

- **刷新同步**：新增 `POST /refresh` 桥接 + `ide_refresh_files` MCP 工具。智能体写改/新建文件后 IDE VFS
  不会自动刷新（改动不显示、新文件看不到），现在打开/定位文件前会同步刷新，也可显式刷新整个项目。
- **菜单条优化**：默认内置 [dsh-mobile-hanui](https://github.com/Z-6354/dsh-mobile-hanui)，窄视口下
  侧栏/详情面板变抽屉 + FAB，聊天区占满全宽；同时移除了原先手动缩小侧栏图标的 CSS 注入。
- **配置同步**：多项目环境下的凭据与第三方 LLM 配置跨项目双向同步。在任意项目的 Web UI（Models / Settings）
  中添加、修改第三方 Provider（`llm-pi-ai`）或 API Key，文件监听器会自动回写到全局真源，新开/其他项目无需重复配置即可直接共享。
- **其它**：每项目独立工作区隔离、旧会话与投影缓存升级迁移、API Key 脱敏回显。

## 功能 / Features

- 内嵌 Web UI（JCEF 工具窗口）· 以项目为工作区 · MCP 桥接 IDE 上下文
- 发送选中代码 · 运行日志一键解释 · 审查与还原（快照 diff）
- 每项目独立工作区 · 全局配置共享 · 移动壳（默认内置）
- 自包含运行时（离线可用）· 每项目实例生命周期管理 · 中英双语

## 安装 / Install

1. 下载 [Releases](../../releases) 的 `deepseek-harness-idea-<version>.zip`（约 98MB，含运行时）。
2. `Settings → Plugins → ⚙ → Install Plugin from Disk…` 选择该 zip，重启 IDE。
3. 打开右侧 **DeepSeek Harness** 工具窗口（首次自动解压内置运行时）。
4. `Settings → Tools → DeepSeek Harness` 填入 DeepSeek API Key，开始对话。

## 构建 / Build

```bash
gradlew test          # 全量测试（含真实 dsh 冒烟，需 DSH_IDEA_RUNTIME 否则跳过）
gradlew buildPlugin   # 打包插件 zip（输出 build/distributions/）
gradlew buildRuntime bundleRuntime   # 可选：构建/打包内嵌运行时
```

要求 JBR 21 / JDK 21 作为 `JAVA_HOME`。首次构建会下载 ideaIC 2024.1.7 平台与运行时依赖。

## 文档 / Docs

[docs/README.md](docs/README.md) · [PRD](docs/PRD.md) · [DESIGN](docs/DESIGN.md) ·
[ACCEPTANCE](docs/ACCEPTANCE.md) · [MILESTONE_REVIEW](docs/MILESTONE_REVIEW.md) ·
[PROJECT_NOTES](docs/PROJECT_NOTES.md)

## 许可 / License

[MIT](LICENSE) — 基于 [fieJiangW/deepseek-harness-idea](https://github.com/fieJiangW/deepseek-harness-idea)
(MIT) 与 [dsh-mobile-hanui](https://github.com/Z-6354/dsh-mobile-hanui) (MIT)。
