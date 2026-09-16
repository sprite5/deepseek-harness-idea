package com.deepseek.harness.idea.runtime

import com.intellij.openapi.diagnostic.Logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 随包 dsh 树里「IDEA 工具窗用不上」的 client 插件裁剪（幂等）。
 *
 * ## 为什么要在 bundle patch 上做手术
 *
 * dsh web 的浏览器插件名册由各 bundle 的 `cordis.patch.yml` 以 `- insert:` 列表声明
 * （这 4 个 sidebar 插件在 `@deepseek-ai/dsh-web-app/cordis.patch.yml`）。而 cordis 的
 * patch 语法只能「按 id 覆盖已有条目的字段」或「insert 新条目」，**没有删除条目的操作**
 * （见 `dsh-app-boot/lib/index.js` 的 `applyEntryPatches`）。因此从 `--patch` / profile 层
 * 无论怎么写都只能把它们标成 `disabled: true`——那样插件列表里仍会出现 4 张「已禁用」卡片。
 *
 * 「默认不包含」只有一条路：在合成之前把这 4 行从 bundle patch 里删掉。行不存在 → 插件不被
 * 加载 → 设置→插件 列表里也不再出现（列表是 Loader 条目的投影，不是包目录的投影）。
 * 等价于「不安装进包里」；磁盘上那两个包的 client.js 是惰性的，删目录只会引入 npm 树
 * 不一致的风险，收益接近零，故不删。
 *
 * ## 裁剪对象（右侧栏的两个 tab 类型）
 *
 * IDEA 自带工程文件树与编辑器，工具窗里再挂一份「工作区文件树 + Markdown/代码/PDF 预览」
 * 是纯冗余；而聊天区的文件点击已被插件用 DOM 捕获脚本拦下改为在 IDEA 编辑器中打开
 * （见 `DshToolWindowFactory.buildInterceptFileClickScript`，选择器覆盖 ui-chat/ui-tool/
 * ui-deliverables/ui-reference 实际使用的 `fileMention` / `filePath` / `_fileLink` 类名）。
 *
 * ## 绝不能裁的两个（硬约束，改动前务必读）
 *
 * - **`ui-sidebar`（左）**：承载会话多级树 + 搜索 + 工作区分组、新建会话、底部设置入口
 *   （`sidebar.workspaces` / `sidebar.settings` 两个座位由 ui-workspace / ui-settings 填充），
 *   是工具窗内**唯一**的会话切换与 Web 设置入口。
 * - **`ui-sidebar-right`**：`@deepseek-ai/dsh-client-ui-chat` 把该行提供的 `sidebarRight`
 *   服务写进了 `inject` 必需依赖（`lib/client.js`: `inject = [..., "sidebarRight"]`，
 *   源码注释为 "Services required by the Chat target"）。cordis 的必需注入未就绪时插件不会
 *   apply，所以**禁用或删除该行都会让聊天区整个不渲染**——不是少个面板，是没得聊。
 */
object DshClientPluginPruner {

    /** dsh-web-app bundle 的 patch 文件（相对运行时根目录）。 */
    internal const val WEB_APP_PATCH = "dsh/node_modules/@deepseek-ai/dsh-web-app/cordis.patch.yml"

    /**
     * 行 id → 期望的模块名。带上期望模块名是为了安全：只有 `- id:` 条目块里真的出现该
     * `name:` 时才删除，避免误伤同名 id 的其它行（例如以后有人手写的 disable 行）。
     */
    internal val PRUNED_ROWS: Map<String, String> = linkedMapOf(
        "ui-sidebar-files" to "@deepseek-ai/dsh-client-ui-sidebar-files",
        "ui-sidebar-documentpreview" to "@deepseek-ai/dsh-client-ui-sidebar-documentpreview",
    )

    /** `    - id: ui-sidebar-files`（group 1 = 缩进，group 2 = id）。 */
    private val ENTRY_ID = Regex("""^(\s*)-\s+id:\s*([A-Za-z0-9_.-]+)\s*$""")

    /** `      name: '@deepseek-ai/...'`。 */
    private val NAME_LINE = Regex("""^\s*name:\s*(.+?)\s*$""")

    /** 延迟到真正写盘时才解析 IDE 日志器：纯文本逻辑可在无 IDE 上下文的单测里运行。 */
    private val LOG by lazy { Logger.getInstance(DshClientPluginPruner::class.java) }

    /**
     * 裁剪运行时树里的 dsh-web-app patch 文件（幂等：已裁剪过则原样返回）。
     *
     * 写盘用「临时文件 + 原子替换」：运行时树是**所有项目共享**的，而 patch 文件是
     * dsh 启动时读取的合成输入 —— 并发启动的另一个项目若正好读到写了一半的文件会起不来。
     *
     * @return true = 文件被改写；false = 无需改动（行不存在 / 结构校验不通过 / 文件缺失）
     */
    fun pruneRuntime(runtimeRoot: Path): Boolean {
        val file = runtimeRoot.resolve(WEB_APP_PATCH)
        if (!Files.isRegularFile(file)) {
            LOG.info("no dsh-web-app bundle patch at $file; nothing to prune")
            return false
        }
        return try {
            val text = Files.readString(file, StandardCharsets.UTF_8)
            val pruned = prune(text)
            if (pruned == text) return false
            val tmp = file.resolveSibling("${file.fileName}.dsh-idea-tmp")
            Files.writeString(tmp, pruned, StandardCharsets.UTF_8)
            replaceAtomically(tmp, file)
            LOG.info(
                "pruned IDEA-redundant client plugin rows " +
                    "(${PRUNED_ROWS.keys.joinToString()}) from $file"
            )
            true
        } catch (e: Exception) {
            LOG.warn("failed to prune $file", e)
            false
        }
    }

    /** 原子替换；文件系统不支持原子移动（少数网络盘）时退回普通替换。 */
    private fun replaceAtomically(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            LOG.warn("atomic replace unsupported for $to; falling back to plain replace", e)
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * 从 patch 文本里删除 [targets] 声明的条目（纯函数，供单测直接喂文本）。
     *
     * 删除单位是「条目块 + 紧贴其上方的注释行」：
     * - 条目块 = `- id:` 行本身 + 后续缩进更深的续行（遇到空行或缩进不更深即结束）；
     * - 注释行 = 中间没有空行、直接贴在条目上方的连续 `#` 行（这两行的注释是各自的说明）。
     *
     * 有删除发生时顺带把因此产生的连续空行折叠为一个（保持文件整洁且结果稳定）；
     * 没有任何命中时**原样返回**——这保证了「第二次运行不写盘」的幂等性。
     */
    internal fun prune(text: String, targets: Map<String, String> = PRUNED_ROWS): String {
        if (targets.isEmpty()) return text
        val eol = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.split(eol)
        val removed = BooleanArray(lines.size)
        var hits = 0

        for (i in lines.indices) {
            val match = ENTRY_ID.matchEntire(lines[i]) ?: continue
            val expected = targets[match.groupValues[2]] ?: continue
            val indent = match.groupValues[1].length

            var end = i
            var nameMatched = false
            var j = i + 1
            while (j < lines.size) {
                val line = lines[j]
                if (line.isBlank()) break
                if (leadingIndent(line) <= indent) break
                NAME_LINE.matchEntire(line)?.let { name ->
                    if (unquote(name.groupValues[1]) == expected) nameMatched = true
                }
                end = j
                j++
            }
            // 结构不符（没有我们期望的 name）→ 保守跳过，绝不误删
            if (!nameMatched) continue

            var start = i
            var k = i - 1
            while (k >= 0) {
                val line = lines[k]
                if (line.isBlank() || !line.trimStart().startsWith("#")) break
                start = k
                k--
            }
            for (x in start..end) removed[x] = true
            hits++
        }

        if (hits == 0) return text

        val kept = lines.filterIndexed { index, _ -> !removed[index] }
        val collapsed = ArrayList<String>(kept.size)
        for (line in kept) {
            if (line.isBlank() && collapsed.isNotEmpty() && collapsed.last().isBlank()) continue
            collapsed.add(line)
        }
        return collapsed.joinToString(eol)
    }

    private fun leadingIndent(line: String): Int {
        var n = 0
        for (c in line) {
            if (c == ' ') n++ else if (c == '\t') n += 4 else break
        }
        return n
    }

    private fun unquote(value: String): String {
        val v = value.trim()
        if (v.length >= 2) {
            val first = v.first()
            if ((first == '\'' || first == '"') && v.last() == first) return v.substring(1, v.length - 1)
        }
        return v
    }
}
