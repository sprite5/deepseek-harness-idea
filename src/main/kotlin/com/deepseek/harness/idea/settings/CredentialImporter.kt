package com.deepseek.harness.idea.settings

import java.nio.file.Files
import java.nio.file.Path

/**
 * 从用户本机 `~/.dsh/.credentials.yaml` 导入凭据（一键迁移浏览器版 dsh 的配置）。
 * MVP 采用行级解析，仅读取 DEEPSEEK_API_KEY 一个键。
 */
object CredentialImporter {

    const val DEEPSEEK_API_KEY = "DEEPSEEK_API_KEY"

    /** 默认 DSH_HOME：`~/.dsh` */
    fun defaultDshHome(): Path = Path.of(System.getProperty("user.home"), ".dsh")

    /**
     * 读取 [dshHome]/.credentials.yaml 中的 DEEPSEEK_API_KEY。
     * @return 找到的 Key；文件缺失/不可读/无该键时返回 null。
     */
    fun importApiKey(dshHome: Path = defaultDshHome()): String? {
        val credentials = dshHome.resolve(".credentials.yaml")
        if (!Files.isReadable(credentials)) return null
        return Files.readAllLines(credentials).asSequence()
            .map { it.trim() }
            .filter { it.startsWith(DEEPSEEK_API_KEY) }
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx < 0) null else line.substring(idx + 1).trim().trim('"').trim('\'')
            }
            .firstOrNull { it.isNotEmpty() }
    }
}
