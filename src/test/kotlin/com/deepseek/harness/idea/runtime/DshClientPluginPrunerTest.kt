package com.deepseek.harness.idea.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 裁剪逻辑测试：把「IDEA 工具窗用不上的右侧栏 client 插件」从随包 dsh-web-app
 * bundle patch 里删掉，同时一个字节都不碰其它行。
 *
 * 纯逻辑（[DshClientPluginPruner.prune]）+ 可选的真实运行时校验，不依赖 IDE 上下文。
 */
class DshClientPluginPrunerTest {

    @TempDir
    lateinit var tempDir: Path

    /** 按 `@deepseek-ai/dsh-web-app/cordis.patch.yml`（0.1.5-rc.2）的实际排版构造的片段。 */
    private val fixture = """
        # The dsh-web-app bundle patch: the browser surface over the dsh-base layer.
        - insert:
            - id: ui-theme
              name: '@deepseek-ai/dsh-client-ui-theme'

            - id: ui-sidebar
              name: '@deepseek-ai/dsh-client-ui-sidebar'

            # The right Sidebar: one docking surface per session over ui-dockkit.
            - id: ui-sidebar-right
              name: '@deepseek-ai/dsh-client-ui-sidebar-right'


            # The right Sidebar's document tab: bounded file reads with selectable
            # Markdown, code, HTML, PDF, and plain-text renderers.
            - id: ui-sidebar-documentpreview
              name: '@deepseek-ai/dsh-client-ui-sidebar-documentpreview'

            # The right Sidebar's workspace file tree tab type.
            - id: ui-sidebar-files
              name: '@deepseek-ai/dsh-client-ui-sidebar-files'

            - id: ui-settings
              name: '@deepseek-ai/dsh-client-ui-settings'
    """.trimIndent()

    /** 期望结果：两个 tab 类型连同各自的注释一起消失，左侧栏/右栏本体/其它行原样保留。 */
    private val expected = """
        # The dsh-web-app bundle patch: the browser surface over the dsh-base layer.
        - insert:
            - id: ui-theme
              name: '@deepseek-ai/dsh-client-ui-theme'

            - id: ui-sidebar
              name: '@deepseek-ai/dsh-client-ui-sidebar'

            # The right Sidebar: one docking surface per session over ui-dockkit.
            - id: ui-sidebar-right
              name: '@deepseek-ai/dsh-client-ui-sidebar-right'

            - id: ui-settings
              name: '@deepseek-ai/dsh-client-ui-settings'
    """.trimIndent()

    @Test
    fun `removes the two right-sidebar tab types and their comments`() {
        assertEquals(expected, DshClientPluginPruner.prune(fixture))
    }

    @Test
    fun `keeps the left sidebar and the sidebarRight provider`() {
        val pruned = DshClientPluginPruner.prune(fixture)
        // 硬约束：删掉这两个会分别造成"没有会话切换/设置入口"和"聊天区不渲染"
        assertTrue(pruned.contains("- id: ui-sidebar\n"), "left sidebar must stay")
        assertTrue(pruned.contains("- id: ui-sidebar-right\n"), "sidebarRight provider must stay")
        assertTrue(
            pruned.contains("name: '@deepseek-ai/dsh-client-ui-sidebar-right'"),
            "sidebarRight provider module must stay",
        )
        assertFalse(pruned.contains("ui-sidebar-files"))
        assertFalse(pruned.contains("ui-sidebar-documentpreview"))
    }

    @Test
    fun `is idempotent`() {
        val once = DshClientPluginPruner.prune(fixture)
        assertEquals(once, DshClientPluginPruner.prune(once), "second pass must be a byte-identical no-op")
    }

    @Test
    fun `leaves a patch without the targeted rows untouched`() {
        val other = "- insert:\n    - id: mcp.ide\n      name: '@deepseek-ai/dsh-mcp-client'\n"
        assertEquals(other, DshClientPluginPruner.prune(other))
    }

    @Test
    fun `skips an id whose module does not match`() {
        val lookalike = """
            - insert:
                - id: ui-sidebar-files
                  name: '@some-vendor/other-package'
        """.trimIndent()
        assertEquals(lookalike, DshClientPluginPruner.prune(lookalike))
    }

    @Test
    fun `removes a row that has no comment block above it`() {
        val bare = """
            - insert:
                - id: ui-theme
                  name: '@deepseek-ai/dsh-client-ui-theme'
                - id: ui-sidebar-files
                  name: '@deepseek-ai/dsh-client-ui-sidebar-files'
        """.trimIndent()
        val want = """
            - insert:
                - id: ui-theme
                  name: '@deepseek-ai/dsh-client-ui-theme'
        """.trimIndent()
        assertEquals(want, DshClientPluginPruner.prune(bare))
    }

    @Test
    fun `preserves crlf line endings`() {
        val crlf = fixture.replace("\n", "\r\n")
        val want = expected.replace("\n", "\r\n")
        assertEquals(want, DshClientPluginPruner.prune(crlf))
    }

    @Test
    fun `pruneRuntime rewrites the patch once and leaves no temp file behind`() {
        val root = tempDir.resolve("runtime")
        val file = root.resolve(DshClientPluginPruner.WEB_APP_PATCH)
        Files.createDirectories(file.parent)
        Files.writeString(file, fixture, StandardCharsets.UTF_8)

        assertTrue(DshClientPluginPruner.pruneRuntime(root), "first call must prune")
        assertEquals(expected, Files.readString(file, StandardCharsets.UTF_8))
        assertFalse(DshClientPluginPruner.pruneRuntime(root), "second call must be a no-op")
        assertEquals(expected, Files.readString(file, StandardCharsets.UTF_8))
        assertFalse(
            Files.exists(file.resolveSibling("${file.fileName}.dsh-idea-tmp")),
            "the temp file used for the atomic replace must not survive",
        )
    }

    @Test
    fun `pruneRuntime is a no-op when the runtime tree has no patch file`() {
        assertFalse(DshClientPluginPruner.pruneRuntime(tempDir))
    }

    /**
     * 真实 patch 文件形态校验（仅在给了 `DSH_IDEA_RUNTIME` 时运行）：
     * 上游改了排版时，这条能在开发机上先红，而不是等用户看到插件列表里又冒出来。
     */
    @Test
    fun `prunes the real runtime patch when one is available`() {
        val override = System.getenv(DshHomeManager.RUNTIME_OVERRIDE_ENV)
        assumeTrue(override != null, "DSH_IDEA_RUNTIME not set; skipping real-patch check")
        val file: Path = Path.of(override!!).resolve(DshClientPluginPruner.WEB_APP_PATCH)
        assumeTrue(Files.isRegularFile(file), "no dsh-web-app patch at $file")

        val original = Files.readString(file, StandardCharsets.UTF_8)
        val pruned = DshClientPluginPruner.prune(original)
        assertFalse(pruned.contains("- id: ui-sidebar-files\n"), "real patch: row must be removed")
        assertFalse(pruned.contains("- id: ui-sidebar-documentpreview\n"), "real patch: row must be removed")
        assertTrue(pruned.contains("- id: ui-sidebar-right\n"), "real patch: sidebarRight provider must survive")
        assertEquals(pruned, DshClientPluginPruner.prune(pruned), "real patch: idempotent")
    }
}
