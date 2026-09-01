package com.deepseek.harness.idea.runtime

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.file.Path

/**
 * 系统 Node.js 定位器（v0.1.7 起：运行时不再打包 node，改用宿主系统的 node）。
 *
 * 解析顺序：
 *   1. 环境变量 `DSH_IDEA_NODE`（显式路径或命令，覆盖 PATH）
 *   2. PATH 上的 `node` / `node.exe`
 *   3. 常见安装目录（Windows：Program Files\nodejs；macOS：Homebrew/opt；Linux：/usr/bin）
 *
 * 命中后校验版本 ≥ [MIN_NODE_MAJOR]（=20；sharp/koffi 用 NAPI v9，cordis 内部 API 需 ≥22 但有降级路径）。
 * 任何步骤失败返回 null，调用方给出可操作报错（带安装指引）。
 */
object SystemNodeLocator {

    private val LOG = Logger.getInstance(SystemNodeLocator::class.java)

    /** 最低 Node.js 主版本号（20 LTS）。 */
    const val MIN_NODE_MAJOR = 20

    /** 显式覆盖环境变量名。 */
    const val OVERRIDE_ENV = "DSH_IDEA_NODE"

    /**
     * 解析结果（路径 + 版本字符串）。
     */
    data class NodeInfo(val path: Path, val version: String) {
        /** 主版本号（如 `v20.10.0` → 20）。 */
        val major: Int get() = version.removePrefix("v").substringBefore('.').toIntOrNull() ?: 0

        /** 是否满足最低版本（≥ [MIN_NODE_MAJOR]）。 */
        fun meetsMinimum(): Boolean = major >= MIN_NODE_MAJOR
    }

    /**
     * 解析系统 node 可执行文件。
     * @return 解析成功返回 NodeInfo（已含版本）；失败返回 null（并在日志写原因）。
     */
    fun resolve(): NodeInfo? {
        val override = System.getenv(OVERRIDE_ENV)?.trim()?.takeIf { it.isNotEmpty() }
        if (override != null) {
            val p = File(override)
            if (!p.canExecute()) {
                LOG.warn("$OVERRIDE_ENV=$override is not executable")
                return null
            }
            return probe(p.toPath())
        }
        // PATH 上的 node
        val fromPath = findOnPath(Platform.current().nodeBinName)
        if (fromPath != null) return probe(fromPath)
        // 常见安装目录兜底
        for (candidate in knownInstallDirs()) {
            val f = File(candidate, Platform.current().nodeBinName)
            if (f.canExecute()) return probe(f.toPath())
        }
        LOG.warn("no system node found (expected ${Platform.current().nodeBinName} on PATH or $OVERRIDE_ENV)")
        return null
    }

    /** 友好错误信息（缺 node / 版本过低），调用方贴到 UI 报错。 */
    fun missingMessage(): String =
        "DeepSeek Harness 需要系统 Node.js ≥ $MIN_NODE_MAJOR.\n" +
            "请安装 Node.js LTS（https://nodejs.org/），或把 node 路径写入环境变量 $OVERRIDE_ENV。"

    private fun probe(path: Path): NodeInfo? = try {
        val proc = ProcessBuilder(path.toString(), "--version").redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        if (proc.exitValue() != 0) {
            LOG.warn("node --version failed for $path (exit ${proc.exitValue()})")
            null
        } else {
            NodeInfo(path, out)
        }
    } catch (e: Exception) {
        LOG.warn("failed to probe node at $path", e)
        null
    }

    /** 从 PATH 环境变量里找名为 [bin] 的可执行文件（Windows 还要按 PATHEXT 试 .cmd/.bat）。 */
    private fun findOnPath(bin: String): Path? {
        val pathEnv = System.getenv("PATH") ?: return null
        val sep = File.pathSeparator
        val isWin = Platform.current().os == Platform.Os.WINDOWS
        val candidates = if (isWin) candidatesForWindows(bin) else listOf(bin)
        for (dir in pathEnv.split(sep)) {
            if (dir.isBlank()) continue
            for (name in candidates) {
                val f = File(dir.trim(), name)
                if (f.canExecute()) return f.toPath()
            }
        }
        return null
    }

    private fun candidatesForWindows(bin: String): List<String> {
        if (bin.lowercase().endsWith(".exe")) return listOf(bin)
        val pathext = System.getenv("PATHEXT") ?: ".EXE;.CMD;.BAT;.COM"
        val exts = pathext.split(';').map { it.trim().lowercase() }.filter { it.startsWith(".") }
        // .exe 排第一，其余次之
        val ordered = exts.sortedBy { if (it == ".exe") 0 else 1 }
        return ordered.map { "$bin$it" } + listOf("$bin.exe", bin)
    }

    private fun knownInstallDirs(): List<String> = when (Platform.current().os) {
        Platform.Os.WINDOWS -> listOf(
            System.getenv("ProgramFiles")?.let { "$it\\nodejs" },
            System.getenv("ProgramFiles(x86)")?.let { "$it\\nodejs" },
            System.getenv("LOCALAPPDATA")?.let { "$it\\Programs\\nodejs" },
        ).filterNotNull()
        Platform.Os.MACOS -> listOf(
            "/opt/homebrew/bin",   // Apple Silicon Homebrew
            "/usr/local/bin",      // Intel Homebrew / 系统
            "/opt/local/bin",      // MacPorts
        )
        Platform.Os.LINUX -> listOf("/usr/bin", "/usr/local/bin")
        Platform.Os.UNKNOWN -> emptyList()
    }
}
