# DeepSeek Harness for IntelliJ IDEA

把 [DeepSeek Harness](https://github.com/deepseek-ai) 智能体工作台完整嵌入 IntelliJ IDEA：
在 IDE 内嵌的 Web 界面中与智能体对话，让它读写你的项目文件，通过 MCP 桥接获取 IDE 上下文，
并用原生 diff 审查/还原它的改动。

> 本插件非 DeepSeek 官方产品，DeepSeek Harness 与 DeepSeek 商标归其各自所有者。

## 致谢 / Acknowledgments

- 感谢 **[dsh-mobile-hanui](https://github.com/Z-6354/dsh-mobile-hanui)**（MIT）：窄视口下把侧栏/详情
  面板变成抽屉 + 悬浮菜单按钮，内嵌工具窗里菜单不再挤占编辑空间。本插件已默认内置（源码直接打入插件）。
- 感谢原作者 **[tieJiangW/deepseek-harness-idea](https://github.com/tieJiangW/deepseek-harness-idea/)**
  （MIT）：本仓库 fork 自其开源版本，内嵌运行时、MCP 桥接、快照审查等核心能力源自该项目。

> **不再跟随上游 / No longer following upstream**：本仓库 fork 后已独立演进，**不再跟随
> `tieJiangW/deepseek-harness-idea` 的更新**，也不承诺与其保持一致。

## 与上游的差异 / Differences from upstream

- **刷新同步**：新增 `POST /refresh` 桥接 + `ide_refresh_files` MCP 工具。智能体写改/新建文件后 IDE VFS
  不会自动刷新（改动不显示、新文件看不到），现在打开/定位文件前会同步刷新，也可显式刷新整个项目。
- **菜单条优化**：默认内置 [dsh-mobile-hanui](https://github.com/Z-6354/dsh-mobile-hanui)，窄视口下
  侧栏/详情面板变抽屉 + FAB，聊天区占满全宽；同时移除了原先手动缩小侧栏图标的 CSS 注入。
- **文件在 IDEA 编辑器打开**：聊天里的文件链接（会话内文件胶囊/列表、行内文件引用）点击后直接在
  IDEA 编辑器打开，而**不再弹系统的"选择程序/打开方式"对话框**。实现上拦截止点击、取消原事件，
  通过 IDE Bridge `/open-file` 路由调用 `FileEditorManager.openFile`；并支持**相对路径以项目根目录解析**，
  解决"文件列表（已直接写入）"等区域传来的相对路径解析失败问题。Bridge 同时支持 CORS 预检与 `?token=`
  查询参数鉴权，附带 /open-file 诊断日志。
- **移动端 UI 调整**：在 `dsh-mobile-hanui` 内做高密度紧凑化——顶栏/会话头部压缩、底部指标（LLM 耗时、
  Token 等）单行展示、悬浮菜单按钮移到顶部居中（可拖拽）、消息操作栏横排并缩小图标；输入框整体
  保持 DSH 默认（不改动其镜像/光标逻辑，避免字距与高度错乱）。
- **配置同步**：多项目环境下的凭据与第三方 LLM 配置跨项目双向同步。在任意项目的 Web UI（Models / Settings）
  中添加、修改第三方 Provider（`llm-pi-ai`）或 API Key，文件监听器会自动回写到全局真源，新开/其他项目无需重复配置即可直接共享。
- **其它**：每项目独立工作区隔离、旧会话与投影缓存升级迁移、API Key 脱敏回显。

## 功能 / Features

- 内嵌 Web UI（JCEF 工具窗口）· 以项目为工作区 · MCP 桥接 IDE 上下文
- 发送选中代码 · 运行日志一键解释 · 审查与还原（快照 diff）
- 每项目独立工作区 · 全局配置共享 · 移动壳（默认内置）
- 自包含运行时（离线可用）· 每项目实例生命周期管理 · 中英双语

## 支持的平台 / Supported Platforms

本仓库的 `buildPlugin` 产物为 **universal zip**（单一文件，无平台后缀），包含所有平台的 native
二进制（sharp / koffi / node-addon-require-builtin / node-pty），覆盖：

| OS | 架构 | 状态 |
|----|------|------|
| **Windows** | x64 (Intel/AMD) | ✅ |
| **Windows** | ARM64 | ✅ |
| **macOS** | x64 (Intel) | ✅ |
| **macOS** | Apple Silicon (M1/M2/M3/M4) | ✅ |
| **Linux** | x64 | ✅ |

> 运行时由插件自动选择对应 native，无需用户手动切换。

### 兼容的 IntelliJ IDEA 版本

- **最低**：2024.1（build 241）
- **最高**：2026.2（build 262.*）

## 安装 / Install

### 1. 装插件

1. 从 [Releases](../../releases) 页面下载最新的 `dsh-idea-simple-universal-<version>.zip`（约 90-100 MB）。
2. `Settings → Plugins → ⚙ → Install Plugin from Disk…` 选择该 zip，重启 IDE。
3. 打开右侧 **DeepSeek Harness** 工具窗口（首次会自动解压内嵌运行时，可能需要 10-30 秒）。

### 2. 装 Node.js

插件首次启动时会调用系统 `node` 解压并启动内嵌的 dsh 树，所以**系统必须已经装 Node.js**。

请先在终端确认：

```bash
node --version
```

要求 **>= v18**。版本不符/没装：[Node.js 官网](https://nodejs.org/zh-cn/download) 下载 LTS（20 / 22）一键安装。

### 3. 配 API Key

进入 `Settings → Tools → DeepSeek Harness`：

- 填入 **DeepSeek API Key**，Apply
- 第三方 provider key 在 **DSH Web UI 的 Models / Settings** 页面配置，文件监听器自动跨项目同步

## 文档 / Docs

[docs/README.md](docs/README.md) · [PRD](docs/PRD.md) · [DESIGN](docs/DESIGN.md) ·
[ACCEPTANCE](docs/ACCEPTANCE.md) · [MILESTONE_REVIEW](docs/MILESTONE_REVIEW.md) ·
[PROJECT_NOTES](docs/PROJECT_NOTES.md)

## 许可 / License

[MIT](LICENSE) — 基于 [tieJiangW/deepseek-harness-idea](https://github.com/tieJiangW/deepseek-harness-idea)
(MIT) 与 [dsh-mobile-hanui](https://github.com/Z-6354/dsh-mobile-hanui) (MIT)。
