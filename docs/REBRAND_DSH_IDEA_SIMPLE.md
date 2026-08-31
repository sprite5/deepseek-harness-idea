# dsh-idea-simple 表面命名改动备份

> 目的：**Java 包名保持上游 `com.deepseek.harness.idea`（便于后续合入原作者更新）**，
> 仅保留“断开 Marketplace 关联 + 表面命名”的小手术式改动。
> 本文件为恢复指南/备份，配合 `git restore .`（回到 HEAD）后重放下面改动。

## 规则
- 标题 → `DSH Simple`（大写 DSH）
- 其他命名 → `dsh-idea-simple`（小写 dsh）
- 插件内部 id → `cn.jascript.deepseek-harness-idea`（与原作断开，但不改包名）
- Java 包名 → 保持 `com.deepseek.harness.idea`（**不改**）

## 需要重放的改动（HEAD 原状上）

### 1. `src/main/resources/META-INF/plugin.xml`
- `<id>com.deepseek.harness.idea</id>` → `<id>cn.jascript.deepseek-harness-idea</id>`
- `<name>DeepSeek Harness</name>` → `<name>dsh-idea-simple</name>`
- `<toolWindow id="DeepSeek Harness"` → `<toolWindow id="DSH Simple"`（其余属性不变）
- class 属性保持 `com.deepseek.harness.idea.*`（与包名一致，**不改**）

### 2. `src/main/resources/messages/DshBundle.properties`（英文）
- `toolwindow.title=DeepSeek Harness` → `DSH Simple`
- `toolwindow.placeholder.title=DeepSeek Harness` → `DSH Simple`
- `settings.displayName=DeepSeek Harness` → `DSH Simple`
- `crash.title=DeepSeek Harness crashed` → `DSH Simple crashed`

### 3. `src/main/resources/messages/DshBundle_zh_CN.properties`（中文）
- `toolwindow.title=DeepSeek Harness` → `DSH Simple`
- `toolwindow.placeholder.title=DeepSeek Harness` → `DSH Simple`
- `settings.displayName=DeepSeek Harness` → `DSH Simple`
- `crash.title=DeepSeek Harness 已崩溃` → `DSH Simple 已崩溃`

### 4. UI 通知标题（在 `com.deepseek.harness.idea` 原包路径下）
- `ui/DshToolWindowFactory.kt`：
  - `const val TOOL_WINDOW_ID = "DeepSeek Harness"` → `"DSH Simple"`
  - 两处通知字面量 `"DeepSeek Harness",` → `"DSH Simple",`
- `ui/SendLogExplanationAction.kt`：`"DeepSeek Harness",` → `"DSH Simple",`
- `ui/SendSelectionAction.kt`：`"DeepSeek Harness",` → `"DSH Simple",`

### 5. `settings.gradle.kts`
- `rootProject.name = "deepseek-harness-idea"` → `"dsh-idea-simple"`（产物名 `dsh-idea-simple-<版本>.zip`）

### 6. `build.gradle.kts`
- `version = "0.1.5"` → `"0.1.6"`

## 明确不改
- Java 包名 / 目录 / import / class 全限定名 → 保持 `com.deepseek.harness.idea`
- 插件描述 Overview 里的 `DeepSeek Harness` 文案（如需统一为 dsh-idea-simple，另行处理）
- `<vendor>DeepSeek Harness Dev</vendor>`

## 构建
```
gradlew clean buildRuntime bundleRuntime buildPlugin
# 产物 build/distributions/dsh-idea-simple-0.1.6.zip（fat，含运行时；buildRuntime 首次需联网）
```