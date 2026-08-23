package com.deepseek.harness.idea.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class LegacySessionMigratorTest {

    @TempDir
    lateinit var tmp: Path

    // ---- dshProjectKey 编码（与 dsh session-persistence-jsonl 的 projectKey 一致）----

    @Test
    fun `projectKey windows path matches dsh readable key`() {
        // 真实 dsh 会话目录名（实测）：D:\develop\deepSeekWorkSpace\code\deepSeekForIdea
        val key = LegacySessionMigrator.dshProjectKey("D:\\develop\\deepSeekWorkSpace\\code\\deepSeekForIdea")
        assertEquals("--D-develop-deepSeekWorkSpace-code-deepSeekForIdea--", key)
    }

    @Test
    fun `projectKey collapses consecutive separators and strips drive colon`() {
        assertEquals("--D-a-b--", LegacySessionMigrator.dshProjectKey("D:\\\\a\\\\b"))
    }

    @Test
    fun `projectKey escapes unsafe chars`() {
        // '#' 在安全字符集外 → ~0023；空格在安全字符集外 → ~0020
        assertEquals("--foo~0020bar~0023--", LegacySessionMigrator.dshProjectKey("foo bar#"))
    }

    @Test
    fun `projectKey empty becomes root`() {
        // 空 cwd 在 dsh 中会抛错；这里对空输入做防御性处理
        assertEquals("--root--", LegacySessionMigrator.dshProjectKey(""))
    }

    @Test
    fun `projectKey trims leading dashes and truncates`() {
        // '/a'：前导分隔符先转成一个 '-'，随后 replace(/^-+/) 去掉 → 'a'
        assertEquals("--a--", LegacySessionMigrator.dshProjectKey("/a"))
        // 超长路径截断到 251
        val long = "a".repeat(300)
        val key = LegacySessionMigrator.dshProjectKey(long)
        assertTrue(key.startsWith("--") && key.endsWith("--"))
        assertEquals(2 + 251 + 2, key.length)
    }

    // ---- migrateProject：复制 + 幂等合并 ----

    @Test
    fun `migrate copies project session dir into isolated home`() {
        val oldRoot = tmp.resolve("old")
        seedSession(oldRoot, "D:\\proj", "session-a", "line1")
        seedSession(oldRoot, "D:\\proj", "session-b", "line2")

        val isolated = tmp.resolve("iso")
        val copied = LegacySessionMigrator.migrateProject(oldRoot, isolated, "D:\\proj")
        assertEquals(2, copied)

        assertTrue(Files.isRegularFile(isolated.resolve("sessions/--D-proj--/session-a/session.jsonl")))
        assertEquals("line1", Files.readString(isolated.resolve("sessions/--D-proj--/session-a/session.jsonl")))
        assertTrue(Files.isRegularFile(isolated.resolve("sessions/--D-proj--/session-b/session.jsonl")))
    }

    @Test
    fun `migrate is idempotent already-copied sessions are skipped`() {
        val oldRoot = tmp.resolve("old")
        seedSession(oldRoot, "D:\\proj", "session-a", "line1")

        val isolated = tmp.resolve("iso")
        assertEquals(1, LegacySessionMigrator.migrateProject(oldRoot, isolated, "D:\\proj"))
        // 第二次调用：目标已存在 → 不再复制
        assertEquals(0, LegacySessionMigrator.migrateProject(oldRoot, isolated, "D:\\proj"))
    }

    @Test
    fun `migrate merges missing sessions while keeping existing`() {
        val oldRoot = tmp.resolve("old")
        seedSession(oldRoot, "D:\\proj", "session-a", "lineA")

        val isolated = tmp.resolve("iso")
        assertEquals(1, LegacySessionMigrator.migrateProject(oldRoot, isolated, "D:\\proj"))

        // 追加新 session，再迁移：新增复制、旧保留
        seedSession(oldRoot, "D:\\proj", "session-b", "lineB")
        assertEquals(1, LegacySessionMigrator.migrateProject(oldRoot, isolated, "D:\\proj"))
        assertTrue(Files.isRegularFile(isolated.resolve("sessions/--D-proj--/session-a/session.jsonl")))
        assertTrue(Files.isRegularFile(isolated.resolve("sessions/--D-proj--/session-b/session.jsonl")))
    }

    @Test
    fun `migrate other project session is not copied`() {
        val oldRoot = tmp.resolve("old")
        seedSession(oldRoot, "D:\\projA", "session-a", "lineA")
        seedSession(oldRoot, "D:\\projB", "session-b", "lineB")

        val isolated = tmp.resolve("iso")
        val copied = LegacySessionMigrator.migrateProject(oldRoot, isolated, "D:\\projB")
        assertEquals(1, copied)
        assertTrue(Files.isRegularFile(isolated.resolve("sessions/--D-projB--/session-b/session.jsonl")))
        assertTrue(!Files.exists(isolated.resolve("sessions/--D-projA--")))
    }

    @Test
    fun `migrate absent project or missing sessions root does nothing`() {
        val oldRoot = tmp.resolve("old")
        val isolated = tmp.resolve("iso")
        // 无 sessions 目录
        assertEquals(0, LegacySessionMigrator.migrateProject(oldRoot, isolated, "D:\\proj"))
        // 有 sessions 但无该项目
        Files.createDirectories(oldRoot.resolve("sessions"))
        assertEquals(0, LegacySessionMigrator.migrateProject(oldRoot, isolated, "D:\\proj"))
    }

    // ---- migrateProjectionCache：标题投影迁移（dsh session.list 用零 I/O 缓存读标题）----

    @Test
    fun `projcache migrates only current project rows and preserves title`() {
        val oldRoot = tmp.resolve("old")
        seedProjCache(oldRoot, "D:\\projA", "session-a", "A 标题")
        seedProjCache(oldRoot, "D:\\projB", "session-b", "B 标题")
        seedProjCache(oldRoot, "D:\\projA", "session-c", "C 标题")

        val isolated = tmp.resolve("iso")
        val n = LegacySessionMigrator.migrateProjectionCache(oldRoot, isolated, "D:\\projA")
        assertEquals(2, n) // 只迁移 projA 的两个会话

        val doc = com.deepseek.harness.idea.util.JsonCodec.decodeObject(
            Files.readString(isolated.resolve("storages/session_projcache.json"))
        )
        val tables = doc["tables"] as Map<*, *>
        val sessions = tables["sessions"] as Map<*, *>
        assertEquals(setOf("session-a", "session-c"), sessions.keys)
        val rowA = sessions["session-a"] as Map<*, *>
        val rowsA = rowA["rows"] as Map<*, *>
        val titleA = rowsA["title"] as Map<*, *>
        assertEquals("A 标题", titleA["val"])
        // projB 的不应迁移
        assertTrue(!sessions.containsKey("session-b"))
    }

    @Test
    fun `projcache matches cwd regardless of separator`() {
        val oldRoot = tmp.resolve("old")
        // projectPath 用反斜杠，identity.cwd 用正斜杠 → 应仍匹配
        seedProjCache(oldRoot, "E:/code/my-spring-ai-mcp", "session-x", "标题X")
        val isolated = tmp.resolve("iso")
        val n = LegacySessionMigrator.migrateProjectionCache(oldRoot, isolated, "E:\\code\\my-spring-ai-mcp")
        assertEquals(1, n)
    }

    @Test
    fun `projcache merges into existing target without overwrite`() {
        val oldRoot = tmp.resolve("old")
        seedProjCache(oldRoot, "D:\\proj", "session-a", "A 标题")
        seedProjCache(oldRoot, "D:\\proj", "session-b", "B 标题")

        val isolated = tmp.resolve("iso")
        // 先建一个含 session-b 的目标缓存（模拟 dsh 已写）
        seedProjCache(isolated, "D:\\proj", "session-b", "旧 B 标题")
        val n = LegacySessionMigrator.migrateProjectionCache(oldRoot, isolated, "D:\\proj")
        assertEquals(2, n) // a 新加 + b 已存在

        val doc = com.deepseek.harness.idea.util.JsonCodec.decodeObject(
            Files.readString(isolated.resolve("storages/session_projcache.json"))
        )
        val sessions = ((doc["tables"] as Map<*, *>)["sessions"]) as Map<*, *>
        assertTrue(sessions.containsKey("session-a"))
        assertTrue(sessions.containsKey("session-b"))
        // 已存在条目保留原值（不覆盖）
        val rowB = sessions["session-b"] as Map<*, *>
        val titleB = (rowB["rows"] as Map<*, *>)["title"] as Map<*, *>
        assertEquals("旧 B 标题", titleB["val"])
    }

    @Test
    fun `projcache absent or no matching project does nothing`() {
        val oldRoot = tmp.resolve("old")
        val isolated = tmp.resolve("iso")
        // 无缓存文件
        assertEquals(0, LegacySessionMigrator.migrateProjectionCache(oldRoot, isolated, "D:\\proj"))
        // 有缓存但无匹配项目
        seedProjCache(oldRoot, "D:\\other", "session-o", "O 标题")
        assertEquals(0, LegacySessionMigrator.migrateProjectionCache(oldRoot, isolated, "D:\\proj"))
    }

    // ---- helpers ----

    /** 在 [root]/sessions/<projectKey(cwd)>/<sessionId>/ 下写入一个 session.jsonl。 */
    private fun seedSession(root: Path, cwd: String, sessionId: String, content: String) {
        val key = LegacySessionMigrator.dshProjectKey(cwd)
        val dir = root.resolve("sessions").resolve(key).resolve(sessionId)
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("session.jsonl"), content, StandardCharsets.UTF_8)
    }

    /** 在 [root]/storages/session_projcache.json 写入一个含 [sessionId] 的投影缓存（cwd=[cwd]，标题=[title]）。 */
    private fun seedProjCache(root: Path, cwd: String, sessionId: String, title: String) {
        val file = root.resolve("storages/session_projcache.json")
        val doc = if (Files.isRegularFile(file)) {
            @Suppress("UNCHECKED_CAST")
            com.deepseek.harness.idea.util.JsonCodec.decodeObject(Files.readString(file)) as LinkedHashMap<String, Any?>
        } else {
            LinkedHashMap<String, Any?>().apply {
                put("unit", LinkedHashMap<String, Any?>().apply {
                    put("name", "session_projcache")
                    put("version", 3)
                })
                put("global", null)
                put("tables", LinkedHashMap<String, Any?>().apply { put("sessions", LinkedHashMap<String, Any?>()) })
            }
        }
        @Suppress("UNCHECKED_CAST")
        val tables = doc["tables"] as LinkedHashMap<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val sessions = tables["sessions"] as LinkedHashMap<String, Any?>
        sessions[sessionId] = LinkedHashMap<String, Any?>().apply {
            put("identity", LinkedHashMap<String, Any?>().apply {
                put("createdAt", 1787190443315L)
                put("cwd", cwd)
            })
            put("rows", LinkedHashMap<String, Any?>().apply {
                put("title", LinkedHashMap<String, Any?>().apply {
                    put("ver", 1)
                    put("seq", 230)
                    put("val", title)
                })
            })
        }
        Files.createDirectories(file.parent)
        Files.writeString(file, com.deepseek.harness.idea.util.JsonCodec.encode(doc), StandardCharsets.UTF_8)
    }
}
