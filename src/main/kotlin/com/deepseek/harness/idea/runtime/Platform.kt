package com.deepseek.harness.idea.runtime

/**
 * 主机平台探测（os + arch）。所有平台判断集中在这一处。
 *
 * - `.id` 用作按平台资源后缀（`dsh-bundle-win-x64.zip` 等，见 build.gradle.kts bundleDsh）。
 * - `.nodeBinName` 返回系统 node 可执行文件名（Windows=`node.exe`；Unix=`node`）。
 *
 * 运行时不再打包 node，node 由宿主系统提供——只需用文件名判断怎么在 PATH 上找它。
 */
object Platform {

    /** 主机 OS 家族。 */
    enum class Os { WINDOWS, MACOS, LINUX, UNKNOWN }

    /** CPU 架构家族。 */
    enum class Arch { X64, ARM64, UNKNOWN }

    /** 目标组合（os + arch）。 */
    data class Target(val os: Os, val arch: Arch) {
        val id: String
            get() = os.id + "-" + arch.id

        /** 系统 node 可执行文件名（用于 PATH 查找）。 */
        val nodeBinName: String
            get() = if (os == Os.WINDOWS) "node.exe" else "node"
    }

    /** 当前主机平台（读 os.name / os.arch）。 */
    fun current(): Target = Target(
        fromOsName(System.getProperty("os.name", "")),
        fromOsArch(System.getProperty("os.arch", "")),
    )

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
