package com.deepseek.harness.idea.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class DshCredentialsMaskTest {

    @Test
    fun `empty key masks to empty`() {
        assertEquals("", DshCredentials.maskApiKey(""))
    }

    @Test
    fun `null key masks to empty`() {
        assertEquals("", DshCredentials.maskApiKey(null))
    }

    @Test
    fun `long key keeps first and last six chips`() {
        // 32 位 key：前 6 位 + 掩码 + 后 6 位，中段全部隐藏
        val key = "sk-1234567890abcdefghijklmnopqrstuv"
        assertEquals("sk-123******qrstuv", DshCredentials.maskApiKey(key))
    }

    @Test
    fun `exactly 12 chained key is fully masked`() {
        // 长度恰为 12 → 无法同时保留前后各 6 位且中间有掩码 → 整段脱敏
        assertEquals("******", DshCredentials.maskApiKey("abcdefghijkl"))
    }

    @Test
    fun `short key is fully masked`() {
        assertEquals("******", DshCredentials.maskApiKey("abc"))
    }

    @Test
    fun `first and last six chips differ for a real key`() {
        val key = "sk-deepseek-someverylongkeyvalue-1234567890"
        val masked = DshCredentials.maskApiKey(key)
        assertEquals(key.take(6), masked.take(6))
        assertEquals(key.takeLast(6), masked.takeLast(6))
        assertEquals(6 + 6 + 6, masked.length) // 前6 + "******" + 后6
    }

    // ---- readApiKeyFromCredentialFile（从 DSH_HOME/.credentials.yaml 回退读取）----

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `reads key from credentials yaml`() {
        val f = tmp.resolve(".credentials.yaml")
        Files.writeString(f, "DEEPSEEK_API_KEY: sk-abcdef123456\n", StandardCharsets.UTF_8)
        assertEquals("sk-abcdef123456", DshCredentials.readApiKeyFromCredentialFile(f))
    }

    @Test
    fun `reads quoted key from credentials yaml`() {
        val f = tmp.resolve(".credentials.yaml")
        Files.writeString(f, "DEEPSEEK_API_KEY: \"sk-quoted-123456\"\n", StandardCharsets.UTF_8)
        assertEquals("sk-quoted-123456", DshCredentials.readApiKeyFromCredentialFile(f))
    }

    @Test
    fun `missing file or key returns null`() {
        // 文件不存在
        assertNull(DshCredentials.readApiKeyFromCredentialFile(tmp.resolve("none.yaml")))
        // 无该键
        val f = tmp.resolve(".credentials.yaml")
        Files.writeString(f, "other: value\n", StandardCharsets.UTF_8)
        assertNull(DshCredentials.readApiKeyFromCredentialFile(f))
    }

    @Test
    fun `ignores other lines and non-empty value`() {
        val f = tmp.resolve(".credentials.yaml")
        Files.writeString(
            f,
            "kind: api-key\nDEEPSEEK_API_KEY: sk-abc123456789\nother: x\n",
            StandardCharsets.UTF_8
        )
        assertEquals("sk-abc123456789", DshCredentials.readApiKeyFromCredentialFile(f))
    }

    @Test
    fun `readAllRefs parses dsh native refs format including pi keys`() {
        val f = tmp.resolve(".credentials.yaml")
        Files.writeString(
            f,
            "version: 1\nrefs:\n  DEEPSEEK_API_KEY: sk-ds-123456\n  MINIMAX_CN_API_KEY: sk-ms-654321\n  AIYUNROUTER_API_KEY: sk-ai-112233\n",
            StandardCharsets.UTF_8
        )
        val refs = DshCredentials.readAllRefs(f)
        assertEquals(3, refs.size)
        assertEquals("sk-ds-123456", refs[DshCredentials.DEEPSEEK_API_KEY])
        assertEquals("sk-ms-654321", refs["MINIMAX_CN_API_KEY"])
        assertEquals("sk-ai-112233", refs["AIYUNROUTER_API_KEY"])
    }

    @Test
    fun `readAllRefs parses legacy flat format`() {
        val f = tmp.resolve(".credentials.yaml")
        Files.writeString(f, "DEEPSEEK_API_KEY: sk-flat-123456\n", StandardCharsets.UTF_8)
        assertEquals(mapOf("DEEPSEEK_API_KEY" to "sk-flat-123456"), DshCredentials.readAllRefs(f))
    }

    @Test
    fun `writeRefs then readAllRefs roundtrips all keys`() {
        val f = tmp.resolve(".credentials.yaml")
        DshCredentials.writeRefs(f, mapOf(
            "DEEPSEEK_API_KEY" to "sk-ds-123456",
            "MINIMAX_CN_API_KEY" to "sk-ms-654321",
        ))
        val refs = DshCredentials.readAllRefs(f)
        assertEquals(2, refs.size)
        assertEquals("sk-ds-123456", refs["DEEPSEEK_API_KEY"])
        assertEquals("sk-ms-654321", refs["MINIMAX_CN_API_KEY"])
    }
}
