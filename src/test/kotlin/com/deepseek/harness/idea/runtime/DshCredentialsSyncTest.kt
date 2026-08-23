package com.deepseek.harness.idea.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class DshCredentialsSyncTest {

    @TempDir
    lateinit var tmp: Path

    // ---- resolveSync：项目 key 与全局 key 比对，决定是否回写 ----

    @Test
    fun `resolveSync returns project key when differs from global`() {
        assertEquals("sk-new-1234567890", DshCredentialsSync(tmp.resolve(".credentials.yaml")).resolveSync("sk-new-1234567890", "sk-old-0000000000"))
    }

    @Test
    fun `resolveSync returns null when project and global agree`() {
        assertNull(DshCredentialsSync(tmp.resolve(".credentials.yaml")).resolveSync("sk-same-1234567890", "sk-same-1234567890"))
    }

    @Test
    fun `resolveSync returns null when project key empty`() {
        assertNull(DshCredentialsSync(tmp.resolve(".credentials.yaml")).resolveSync("", "sk-old-0000000000"))
        assertNull(DshCredentialsSync(tmp.resolve(".credentials.yaml")).resolveSync(null, "sk-old-0000000000"))
    }

    @Test
    fun `resolveSync returns project key when global absent`() {
        assertEquals("sk-first-1234567890", DshCredentialsSync(tmp.resolve(".credentials.yaml")).resolveSync("sk-first-1234567890", null))
    }

    // ---- start() 注册 watch（纯生命周期，不触发 Platform 心跳）----

    @Test
    fun `start registers watch service and closeProject is idempotent`() {
        val dir = tmp.resolve("iso")
        Files.createDirectories(dir)
        val file = dir.resolve(".credentials.yaml")
        Files.writeString(file, "version: 1\nrefs:\n  DEEPSEEK_API_KEY: sk-a\n", StandardCharsets.UTF_8)

        val sync = DshCredentialsSync(file)
        sync.start()
        // 重复 start 幂等（不抛）
        sync.start()
        // 关闭（幂等）
        sync.closeProject()
        sync.closeProject()
    }
}
