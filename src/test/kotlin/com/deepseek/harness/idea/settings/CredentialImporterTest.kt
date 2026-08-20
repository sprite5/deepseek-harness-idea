package com.deepseek.harness.idea.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CredentialImporterTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `imports api key from credentials yaml`() {
        Files.writeString(tempDir.resolve(".credentials.yaml"), "DEEPSEEK_API_KEY: sk-test-123\n")
        assertEquals("sk-test-123", CredentialImporter.importApiKey(tempDir))
    }

    @Test
    fun `handles quoted value and ignores other keys`() {
        Files.writeString(
            tempDir.resolve(".credentials.yaml"),
            "OTHER_KEY: x\nDEEPSEEK_API_KEY: \"sk-quoted\"\n"
        )
        assertEquals("sk-quoted", CredentialImporter.importApiKey(tempDir))
    }

    @Test
    fun `returns null when file is missing`() {
        assertNull(CredentialImporter.importApiKey(tempDir))
    }

    @Test
    fun `returns null when key absent`() {
        Files.writeString(tempDir.resolve(".credentials.yaml"), "SOME_OTHER: 1\n")
        assertNull(CredentialImporter.importApiKey(tempDir))
    }
}
