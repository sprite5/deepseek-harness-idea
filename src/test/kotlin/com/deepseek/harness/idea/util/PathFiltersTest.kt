package com.deepseek.harness.idea.util

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class PathFiltersTest {

    @Test
    fun `ignores known build and vcs directories`() {
        for (name in listOf(".git", "node_modules", "build", "out", ".idea", "target", "dist", ".gradle")) {
            assertTrue(PathFilters.isIgnoredDir(name), "should ignore dir: $name")
        }
    }

    @Test
    fun `keeps normal directories`() {
        for (name in listOf("src", "main", "kotlin", "docs", "scripts")) {
            assertFalse(PathFilters.isIgnoredDir(name), "should keep dir: $name")
        }
    }

    @Test
    fun `ignores hidden files and dirs`() {
        assertTrue(PathFilters.isIgnoredDir(".hidden"))
        assertTrue(PathFilters.isIgnoredFile(".secret", 100))
        assertFalse(PathFilters.isIgnoredFile("Main.kt", 100))
    }

    @Test
    fun `ignores files above 1MB`() {
        assertTrue(PathFilters.isIgnoredFile("big.bin", PathFilters.MAX_FILE_BYTES + 1))
        assertFalse(PathFilters.isIgnoredFile("small.txt", PathFilters.MAX_FILE_BYTES))
        assertFalse(PathFilters.isIgnoredFile("exact.bin", PathFilters.MAX_FILE_BYTES))
    }

    @Test
    fun `ignored path matches any ancestor`() {
        assertTrue(PathFilters.isIgnoredPath(Path.of("src/node_modules/pkg/index.js")))
        assertTrue(PathFilters.isIgnoredPath(Path.of("build/out/app.js")))
        assertTrue(PathFilters.isIgnoredPath(Path.of(".idea/workspace.xml")))
        assertFalse(PathFilters.isIgnoredPath(Path.of("src/main/kotlin/App.kt")))
        assertFalse(PathFilters.isIgnoredPath(Path.of("README.md")))
    }
}
