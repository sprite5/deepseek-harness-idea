package com.deepseek.harness.idea.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DshHomeManagerCredentialSyncTest {
    @Test
    fun `credential parser accepts existing dsh yaml format`() {
        val file = kotlin.io.path.createTempFile(suffix = ".yaml")
        try {
            java.nio.file.Files.writeString(file, "version: 1\nrefs:\n  DEEPSEEK_API_KEY: sk-test\n")
            assertEquals("sk-test", DshCredentials.readApiKeyFromCredentialFile(file))
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }
}
