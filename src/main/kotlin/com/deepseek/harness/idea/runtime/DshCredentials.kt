package com.deepseek.harness.idea.runtime

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * DeepSeek API Key 的统一存取入口（PasswordSafe，应用级）。
 * 设置页与 DshHomeManager 共用同一组 CredentialAttributes，避免两处维护。
 */
object DshCredentials {
    const val DEEPSEEK_API_KEY = "DEEPSEEK_API_KEY"
    const val USER_NAME = "deepseek-api-key"

    private val ATTRIBUTES = CredentialAttributes("DshSettings", USER_NAME)

    private fun passwordSafe(): PasswordSafe =
        ApplicationManager.getApplication().getService(PasswordSafe::class.java)

    fun readApiKey(): String? = passwordSafe().getPassword(ATTRIBUTES)

    fun writeApiKey(key: String) = passwordSafe().setPassword(ATTRIBUTES, key)

    /**
     * 从 `DEEPSEEK_API_KEY: <key>` 形式的 credentials YAML 中解析 Key（行级解析，与
     * [com.deepseek.harness.idea.settings.CredentialImporter] 一致；独立实现避免循环依赖）。
     * @return 找到的 Key；文件缺失/无该键时返回 null。
     */
    fun readApiKeyFromCredentialFile(file: Path): String? {
        if (!Files.isReadable(file)) return null
        return Files.readAllLines(file).asSequence()
            .map { it.trim() }
            .filter { it.startsWith(DEEPSEEK_API_KEY) }
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx < 0) null else line.substring(idx + 1).trim().trim('"').trim('\'')
            }
            .firstOrNull { it.isNotEmpty() }
    }

    /**
     * 读取凭据文件的**全部 refs**（不只是 DEEPSEEK_API_KEY）为 key -> value 映射。
     *
     * 兼容两种格式：插件旧式平铺（`DEEPSEEK_API_KEY: sk-...`）与 dsh 原生
     * （`version: 1` + `refs:` + 缩进条目如 `  MINIMAX_CN_API_KEY: sk-...`）。
     * 第三方 provider（llm-pi-ai）的 API key 以 apiKeyEnv 引用的名字（MINIMAX_CN_API_KEY、
     * AIYUNROUTER_API_KEY）存在 refs: 下，必须一并读写，否则 Web UI 配的 pi key 会被丢弃。
     */
    fun readAllRefs(file: Path): Map<String, String> {
        if (!Files.isReadable(file)) return emptyMap()
        val refs = LinkedHashMap<String, String>()
        for (raw in Files.readAllLines(file)) {
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("#")) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim().trim('"').trim('\'')
            if (key.isEmpty() || value.isEmpty()) continue
            if (key.equals("version", true) || key.equals("refs", true)) continue
            refs[key] = value
        }
        return refs
    }

    /** 把全部 refs 以 dsh 原生 version: 1 / refs: 格式写回凭据文件（幂等）。 */
    fun writeRefs(file: Path, refs: Map<String, String>) {
        if (refs.isEmpty()) {
            Files.deleteIfExists(file)
            return
        }
        Files.createDirectories(file.parent)
        val sb = StringBuilder("version: 1\nrefs:\n")
        for ((k, v) in refs) sb.append("  ").append(k).append(": ").append(v).append('\n')
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8)
    }

    /**
     * 统一读取当前 Key：**先 PasswordSafe，无则回退到 [credentialFile]（插件 DSH_HOME 的
     * `.credentials.yaml`）**。用于设置页脱敏回显等纯读场景——PasswordSafe 读不到（如 IDE 密码库
     * 未解锁）时仍能反显已在 `.credentials.yaml` 中的 Key。
     * @param credentialFile - 兜底凭据文件（如 [DshHomeManager.globalConfigHome]/.credentials.yaml）。
     */
    fun readApiKeyWithFallback(credentialFile: Path?): String? =
        readApiKey() ?: credentialFile?.let { readApiKeyFromCredentialFile(it) }

    /**
     * 脱敏显示：保留 key 的前 6 位与后 6 位，中间以 `******` 掩码。
     *
     * 用于设置页回显——不暴露完整 key，又能让用户辨认当前所存值。
     * - key 为空 → 空串（不显示任何占位）。
     * - key 长度 ≤ 12（无法同时保留前后各 6 位且中间有掩码）→ 整段显示为 `******`。
     * - 否则 → 前 6 位 + `******` + 后 6 位。
     *
     * 注意：这是**显示层**投影，不可逆；写回时须与原始 key 区分（见设置页 apply 逻辑）。
     */
    fun maskApiKey(key: String?): String {
        if (key.isNullOrEmpty()) return ""
        if (key.length <= 12) return "******"
        return key.take(6) + "******" + key.takeLast(6)
    }
}
