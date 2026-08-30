package com.deepseek.harness.idea.runtime

/**
 * 目标平台探测（Windows / macOS / Linux 主机 IDEA 兼容）。
 *
 * 所有平台判断集中在这一处，避免散落的 `System.getProperty("os.name")`。
 * 运行时 bundle 按 [Target.id] 命名（如 `runtime-win-x64.zip`，见 docs/DESIGN.md §3.2）；
 * node 可执行文件名按平台区分 —— 构建期已把各平台 node 归一化到 `runtime/<ver>/node/<nodeBinName>`
 * 布局（Windows=`node.exe`，Unix=`node`，macOS/Linux tar 包的 `bin/node` 已在打包时上移）。
 */
object Platform {

    /** 主机 OS 家族。 */
    enum class Os { WINDOWS, MACOS, LINUX, UNKNOWN }

    /** CPU 架构家族。 */
    enum class Arch { X64, ARM64, UNKNOWN }

    /**
     * 目标组合（os + arch）。`.id` 用作运行时资产名后缀与平台关键字；
     * `.nodeBinName` 返回运行时 `node/` 下的可执行文件名。
     */
    data class Target(val os: Os, val arch: Arch) {
        val id: String
            get() = os.id + "-" + arch.id

        /** Node 可执行文件在 `runtime/<ver>/node/` 下的文件名。 */
        val nodeBinName: String
            get() = if (os == Os.WINDOWS) "node.exe" else "node"
    }

    /** 当前主机目标平台（读取 os.name / os.arch）。 */
    fun current(): Target = Target(fromOsName(System.getProperty("os.name", "")), fromOsArch(System.getProperty("os.arch", "")))

    fun fromOsName(osName: String): Os {
        val n = osName.trim().lowercase()
        return when {
            // 用前缀匹配，避免 "darwin" 与 "win"（"dar-WIN"）子串冲突
            n.startsWith("win") -> Os.WINDOWS
            n.startsWith("mac") || n.startsWith("darwin") -> Os.MACOS
            n.startsWith("linux") || n.contains("linux") -> Os.LINUX
            else -> Os.UNKNOWN
        }
    }

    fun fromOsArch(osArch: String): Arch {
        val a = osArch.lowercase()
        return when {
            a.contains("aarch64") || a.contains("arm64") || a == "arm" -> Arch.ARM64
            a.contains("x86_64") || a.contains("amd64") || a.contains("x64") || a == "x86" -> Arch.X64
            else -> Arch.UNKNOWN
        }
    }

    private val Os.id: String
        get() = when (this) {
            Os.WINDOWS -> "win"
            Os.MACOS -> "macos"
            Os.LINUX -> "linux"
            Os.UNKNOWN -> "unknown"
        }

    private val Arch.id: String
        get() = when (this) {
            Arch.X64 -> "x64"
            Arch.ARM64 -> "arm64"
            Arch.UNKNOWN -> "unknown"
        }
}
