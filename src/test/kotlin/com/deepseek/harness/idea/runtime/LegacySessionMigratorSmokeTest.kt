package com.deepseek.harness.idea.runtime

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 集成冒烟：真实 dsh 运行时下，验证"旧全局 session → 每项目隔离目录"迁移链路。
 *
 * 场景（对应 v0.1.2 → v0.1.3-dev 升级）：旧版把 session 存在全局 DSH_HOME 根的
 * `sessions/<projectKey(cwd)>/`；新版 DSH_HOME 改为隔离子目录。本测试构造一个真实格式的
 * 旧 session（header 含 cwd），用 [LegacySessionMigrator] 迁移到隔离 DSH_HOME，
 * 再启动真实 dsh，断言 dsh 的 workspace bootstrap 从迁移后 session 自动重建并挂接它。
 *
 * 未设置 DSH_IDEA_RUNTIME 时自动跳过（CI 无运行时环境）。
 */
class LegacySessionMigratorSmokeTest {

    @TempDir
    lateinit var tempDir: Path

    private var manager: DshProcessManager? = null

    @BeforeEach
    fun setUp() {
        assumeTrue(runtimeRoot() != null, "DSH_IDEA_RUNTIME not set; skipping real-boot smoke test")
    }

    @AfterEach
    fun tearDown() {
        manager?.dispose()
        manager = null
        unlinkJunctions(tempDir)
    }

    private fun unlinkJunctions(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { p ->
                runCatching {
                    if (Files.isSymbolicLink(p) || isJunction(p)) Files.delete(p)
                }
            }
        }
    }

    private fun isJunction(p: Path): Boolean = try {
        Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS).isOther
    } catch (e: Exception) {
        false
    }

    @Test
    fun `migrated legacy session appears in bootstrapped workspace`() {
        val root = runtimeRoot()!!
        val nodeExe = root.resolve("node/node.exe").toFile()
        val dshBin = root.resolve("dsh/node_modules/@deepseek-ai/dsh/lib/bin.js").toFile()
        assertTrue(nodeExe.isFile, "node.exe missing in runtime: $nodeExe")
        assertTrue(dshBin.isFile, "dsh bin missing in runtime: $dshBin")

        val workDir = File(System.getProperty("user.dir"))
        val projectPath = workDir.absolutePath.replace('\\', '/')

        // 1. 构造"旧全局 DSH_HOME"：模拟 v0.1.2 中 session 位于全局根的 sessions/<projectKey(cwd)>/
        val oldGlobal = tempDir.resolve("old-global")
        val sessionId = "session-11111111-2222-3333-4444-555555555555"
        // 真实旧数据是 zstd 压缩的 session.jsonl.zstd（dsh 默认 compression=zstd）；用 node 生成
        val key = LegacySessionMigrator.dshProjectKey(projectPath)
        val oldSessionDir = oldGlobal.resolve("sessions").resolve(key).resolve(sessionId)
        Files.createDirectories(oldSessionDir)
        writeZstdSession(oldSessionDir.resolve("session.jsonl.zstd"), projectPath, sessionId)

        // 2. 构造隔离 DSH_HOME（与 DshHomeManager.ensureHome 相同的骨架），并迁移旧 session
        val isolated = tempDir.resolve("dsh-home")
        val web = isolated.resolve("profiles/web")
        Files.createDirectories(web)
        Files.writeString(
            web.resolve("package.json"),
            """{"name":"dsh-profile-web","private":true,"dependencies":{},"dsh":{"profile":{"bundles":["@deepseek-ai/dsh-base","@deepseek-ai/dsh-web-app"]}}}""",
            StandardCharsets.UTF_8
        )
        Files.writeString(web.resolve("cordis.yml"), "[]\n", StandardCharsets.UTF_8)
        Files.writeString(web.resolve("cordis.patch.yml"), "[]\n", StandardCharsets.UTF_8)
        Files.writeString(isolated.resolve("ide.yml"), "[]\n", StandardCharsets.UTF_8)
        Files.writeString(isolated.resolve(".credentials.yaml"), "DEEPSEEK_API_KEY: sk-dummy-for-test\n", StandardCharsets.UTF_8)

        val migrated = LegacySessionMigrator.migrateProject(oldGlobal, isolated, projectPath)
        assertEquals(1, migrated, "exactly one legacy session dir should be migrated")
        assertTrue(
            Files.isRegularFile(isolated.resolve("sessions").resolve(key).resolve(sessionId).resolve("session.jsonl.zstd")),
            "migrated session artifact should exist under isolated home"
        )

        // 3. 启动真实 dsh（DSH_HOME = isolate 子目录）
        var url: String? = null
        val logLines = java.util.concurrent.ConcurrentLinkedQueue<String>()
        manager = DshProcessManager(
            nodeExe = nodeExe,
            dshBin = dshBin,
            workDir = workDir,
            homeDir = isolated.toFile(),
            patchFile = isolated.resolve("ide.yml").toFile(),
            projectPath = projectPath,
        ).also { m ->
            m.addListener(object : DshProcessManager.Listener {
                override fun onUrlReady(u: String) {
                    url = u
                }
                override fun onLogLine(line: String) {
                    logLines.add(line)
                }
            })
            m.start()
        }

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        while (System.nanoTime() < deadline) {
            if (manager?.currentState() == DshProcessManager.State.RUNNING) break
            Thread.sleep(500)
        }
        if (manager?.currentState() != DshProcessManager.State.RUNNING) {
            val dump = logLines.toList().joinToString("\n")
            throw AssertionError("dsh did not reach RUNNING (state=${manager?.currentState()}); log:\n$dump")
        }

        // 4. dsh workspace bootstrap 应从迁移后 session 重建 workspace，并挂接该 sessionId
        val wsFile = isolated.resolve("storages/workspace.json")
        val wsDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var listed = false
        while (System.nanoTime() < wsDeadline) {
            if (Files.exists(wsFile)) {
                val ws = Files.readString(wsFile, StandardCharsets.UTF_8)
                if (ws.contains(sessionId)) {
                    listed = true
                    break
                }
            }
            Thread.sleep(300)
        }
        assertTrue(listed, "migrated session $sessionId should be referenced by bootstrapped workspace.json")
    }

    /** 用 node 的 zlib 生成真实 zstd 压缩的 session.jsonl.zstd（header + 一条 user/message 事件，checksum 帧）。 */
    private fun writeZstdSession(target: Path, cwd: String, sessionId: String) {
        val nodeExe = runtimeRoot()!!.resolve("node/node.exe").toFile()
        val js = """
            const fs=require('fs'), zlib=require('zlib');
            const out=process.argv[2], cwd=process.argv[3], sid=process.argv[4];
            const header=JSON.stringify({type:'session',version:0,id:sid,createdAt:1755000000000,cwd,delegationDepth:0})+'\n';
            const ev=JSON.stringify({type:'user/message',id:'event-00000000-0000-0000-0000-000000000001',sessionId:sid,createdAt:1755000000001,data:{content:[{type:'text',text:'hello'}],source:{kind:'user'}}})+'\n';
            const frame=(b)=>zlib.zstdCompressSync(b,{params:{[zlib.constants.ZSTD_c_checksumFlag]:1}});
            fs.writeFileSync(out, Buffer.concat([frame(Buffer.from(header)), frame(Buffer.from(ev))]));
        """.trimIndent()
        val script = tempDir.resolve("gen-zstd-session.js")
        Files.writeString(script, js, StandardCharsets.UTF_8)
        val p = ProcessBuilder(nodeExe.absolutePath, script.toAbsolutePath().toString(), target.toAbsolutePath().toString(), cwd, sessionId)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText()
        val ok = p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
        assertTrue(ok, "node zstd session generation failed: $out")
    }

    private fun runtimeRoot(): Path? =
        System.getenv(DshHomeManager.RUNTIME_OVERRIDE_ENV)
            ?.let { Path.of(it) }
            ?.takeIf { Files.isDirectory(it) }
}
