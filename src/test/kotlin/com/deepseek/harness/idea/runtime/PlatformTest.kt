package com.deepseek.harness.idea.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PlatformTest {

    @Test
    fun `windows amd64 maps to win-x64 with node-exe`() {
        val os = Platform.fromOsName("Windows 10")
        val arch = Platform.fromOsArch("amd64")
        assertEquals(Platform.Os.WINDOWS, os)
        assertEquals(Platform.Arch.X64, arch)
        val target = Platform.Target(os, arch)
        assertEquals("win-x64", target.id)
        assertEquals("node.exe", target.nodeBinName)
    }

    @Test
    fun `macos aarch64 maps to macos-arm64 with node`() {
        val target = Platform.Target(Platform.fromOsName("Mac OS X"), Platform.fromOsArch("aarch64"))
        assertEquals("macos-arm64", target.id)
        assertEquals("node", target.nodeBinName)
    }

    @Test
    fun `macos x86_64 maps to macos-x64 with node`() {
        val target = Platform.Target(Platform.fromOsName("Mac OS X"), Platform.fromOsArch("x86_64"))
        assertEquals("macos-x64", target.id)
        assertEquals("node", target.nodeBinName)
    }

    @Test
    fun `linux amd64 maps to linux-x64 with node`() {
        val target = Platform.Target(Platform.fromOsName("Linux"), Platform.fromOsArch("amd64"))
        assertEquals("linux-x64", target.id)
        assertEquals("node", target.nodeBinName)
    }

    @Test
    fun `linux aarch64 maps to linux-arm64 with node`() {
        val target = Platform.Target(Platform.fromOsName("Linux"), Platform.fromOsArch("aarch64"))
        assertEquals("linux-arm64", target.id)
        assertEquals("node", target.nodeBinName)
    }

    @Test
    fun `darwin sysname recognized as macos`() {
        assertEquals(Platform.Os.MACOS, Platform.fromOsName("darwin"))
    }

    @Test
    fun `x86 alias maps to x64`() {
        assertEquals(Platform.Arch.X64, Platform.fromOsArch("x86"))
        assertEquals(Platform.Arch.X64, Platform.fromOsArch("x86_64"))
        assertEquals(Platform.Arch.X64, Platform.fromOsArch("amd64"))
    }

    @Test
    fun `arm alias maps to arm64`() {
        assertEquals(Platform.Arch.ARM64, Platform.fromOsArch("arm"))
        assertEquals(Platform.Arch.ARM64, Platform.fromOsArch("arm64"))
        assertEquals(Platform.Arch.ARM64, Platform.fromOsArch("aarch64"))
    }

    @Test
    fun `unknown os or arch maps to unknown target`() {
        val target = Platform.Target(Platform.fromOsName("BeOS"), Platform.fromOsArch("sparc"))
        assertEquals("unknown-unknown", target.id)
        assertEquals("node", target.nodeBinName)
    }

    @Test
    fun `empty os or arch does not crash and yields unknown`() {
        assertEquals(Platform.Os.UNKNOWN, Platform.fromOsName(""))
        assertEquals(Platform.Arch.UNKNOWN, Platform.fromOsArch(""))
    }
}
