package com.deepseek.harness.idea.runtime

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RuntimeProvisionerTest {

    @TempDir
    lateinit var tmp: Path

    private val target: Platform.Target = Platform.current()
    private val nodeBin = target.nodeBinName
    private val dshBinRel = "dsh/node_modules/@deepseek-ai/dsh/lib/bin.js"

    @Test
    fun `provision skips fetch when runtime already present`() {
        val dest = tmp.resolve("rt")
        seedRuntime(dest)
        var downloaded = false
        val fetcher = fakeFetcher(dest) { downloaded = true }
        assertTrue(RuntimeProvisioner.provision(dest, spec(), "0.2.0", fetcher))
        assertTrue(!downloaded)
    }

    @Test
    fun `provision downloads verifies and extracts when present`() {
        val dest = tmp.resolve("rt2")
        val zip = buildRuntimeZip()
        val sha = RuntimeArchive.sha256(zip)
        val fetcher = object : RuntimeProvisioner.RuntimeFetcher {
            override fun download(url: String, dest: Path): Boolean {
                Files.copy(zip, dest)
                return true
            }
            override fun fetchText(url: String): String? = sha
        }
        assertTrue(RuntimeProvisioner.provision(dest, spec(), "0.2.0", fetcher))
        assertTrue(Files.isRegularFile(dest.resolve("node").resolve(nodeBin)))
        assertTrue(Files.isRegularFile(dest.resolve(dshBinRel)))
    }

    @Test
    fun `provision rejects on sha mismatch`() {
        val dest = tmp.resolve("rt3")
        val zip = buildRuntimeZip()
        val fetcher = object : RuntimeProvisioner.RuntimeFetcher {
            override fun download(url: String, dest: Path): Boolean {
                Files.copy(zip, dest)
                return true
            }
            override fun fetchText(url: String): String? = "DEADBEEF" // 匹配不上
        }
        assertFalse(RuntimeProvisioner.provision(dest, spec(), "0.2.0", fetcher))
        assertTrue(!Files.isRegularFile(dest.resolve(dshBinRel)))
    }

    @Test
    fun `provision fails when checksum sidecar unavailable`() {
        val dest = tmp.resolve("rt4")
        val fetcher = object : RuntimeProvisioner.RuntimeFetcher {
            override fun download(url: String, dest: Path): Boolean = true
            override fun fetchText(url: String): String? = null
        }
        assertFalse(RuntimeProvisioner.provision(dest, spec(), "0.2.0", fetcher))
    }

    @Test
    fun `provision fails when download fails`() {
        val dest = tmp.resolve("rt5")
        val fetcher = object : RuntimeProvisioner.RuntimeFetcher {
            override fun download(url: String, dest: Path): Boolean = false
            override fun fetchText(url: String): String? = "123"
        }
        assertFalse(RuntimeProvisioner.provision(dest, spec(), "0.2.0", fetcher))
    }

    @Test
    fun `provision fails when platform has no asset`() {
        val dest = tmp.resolve("rt6")
        val noAssetSpec = RuntimeAssetSpec("https://base.example", emptyMap())
        val fetcher = object : RuntimeProvisioner.RuntimeFetcher {
            override fun download(url: String, dest: Path): Boolean = true
            override fun fetchText(url: String): String? = "123"
        }
        assertFalse(RuntimeProvisioner.provision(dest, noAssetSpec, "0.2.0", fetcher))
    }

    @Test
    fun `provision fails when baseUrl empty`() {
        val dest = tmp.resolve("rt7")
        assertFalse(RuntimeProvisioner.provision(dest, RuntimeAssetSpec("", emptyMap()), "0.2.0", fakeFetcher(dest) {}))
    }

    // ---- helpers ----

    private fun spec(): RuntimeAssetSpec =
        RuntimeAssetSpec("https://base.example", mapOf(target.id to "runtime-${target.id}.zip"))

    private fun seedRuntime(dest: Path) {
        Files.createDirectories(dest.resolve("node"))
        Files.write(dest.resolve("node").resolve(nodeBin), "node".toByteArray())
        Files.createDirectories(dest.resolve(dshBinRel).parent)
        Files.write(dest.resolve(dshBinRel), "dsh".toByteArray())
    }

    private fun buildRuntimeZip(): Path {
        val zip = tmp.resolve("runtime-${target.id}.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { zos ->
            addEntry(zos, "node/$nodeBin", "node-bin")
            addEntry(zos, dshBinRel, "dsh-bin")
        }
        return zip
    }

    private fun addEntry(zos: ZipOutputStream, name: String, content: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray())
        zos.closeEntry()
    }

    private fun fakeFetcher(dest: Path, onDownload: () -> Unit) = object : RuntimeProvisioner.RuntimeFetcher {
        override fun download(url: String, dest: Path): Boolean {
            onDownload()
            return true
        }
        override fun fetchText(url: String): String? = "x"
    }
}
