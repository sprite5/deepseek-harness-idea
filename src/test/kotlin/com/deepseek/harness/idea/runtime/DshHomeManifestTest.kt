package com.deepseek.harness.idea.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 纯逻辑测试：web profile 的 package.json manifest 默认集成移动壳 bundle。
 *
 * 断言点：
 * - dsh-mobile-hanui 已默认进入 bundles（解决工具窗窄视口菜单占编辑空间）
 * - 两个既有默认 bundle（dsh-base / dsh-web-app）不被误删
 * - dependencies 保持空（bundle 由运行时 node_modules junction 解析，不靠 profile 安装）
 *
 * 不依赖 PathManager / ApplicationManager（仅读 companion 常量，无需 IDE 上下文）。
 */
class DshHomeManifestTest {

    @Test
    fun `web profile bundles include dsh-mobile-hanui by default`() {
        val m = DshHomeManager.WEB_PROFILE_MANIFEST
        assertTrue(m.contains("dsh-mobile-hanui"), "manifest must declare the mobile-shell bundle")
        assertTrue(m.contains(DshHomeManager.MOBILE_SHELL_BUNDLE), "named via MOBILE_SHELL_BUNDLE const")
    }

    @Test
    fun `default bundles are preserved alongside the mobile shell`() {
        val m = DshHomeManager.WEB_PROFILE_MANIFEST
        assertTrue(m.contains("\"@deepseek-ai/dsh-base\""), "@deepseek-ai/dsh-base must stay")
        assertTrue(m.contains("\"@deepseek-ai/dsh-web-app\""), "@deepseek-ai/dsh-web-app must stay")
    }

    @Test
    fun `profile dependencies stay empty`() {
        val m = DshHomeManager.WEB_PROFILE_MANIFEST
        assertTrue(m.contains("\"dependencies\":{}"), "profile must not drive npm deps (runtime junction resolves bundles)")
    }

    @Test
    fun `dsh profile section is well-formed`() {
        val m = DshHomeManager.WEB_PROFILE_MANIFEST
        // 粗粒度结构完整性：毕包的三段与整体括号成对
        assertTrue(m.startsWith("{\"name\":\"dsh-profile-web\""))
        assertTrue(m.contains("\"dsh\":{\"profile\":{\"bundles\":["))
        assertTrue(m.endsWith("}"))
        // bundles 元素数 = 3（base, web-app, mobile-shell）
        val bundles = m.substringAfter("bundles\":[").substringBefore("]}")
        assertEquals(3, bundles.split(",").size, "expected exactly 3 bundles: $bundles")
        assertFalse(bundles.contains(",,,"), "no empty slots in bundles")
    }
}