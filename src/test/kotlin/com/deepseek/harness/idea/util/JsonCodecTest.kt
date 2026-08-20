package com.deepseek.harness.idea.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonCodecTest {

    @Test
    fun `encode map with primitives`() {
        val json = JsonCodec.encode(
            mapOf(
                "ok" to true,
                "project" to "demo",
                "pid" to 42L,
                "missing" to null,
                "ratio" to 0.5,
            )
        )
        assertTrue(json.contains("\"ok\":true"))
        assertTrue(json.contains("\"project\":\"demo\""))
        assertTrue(json.contains("\"pid\":42"))
        assertTrue(json.contains("\"missing\":null"))
        assertTrue(json.contains("\"ratio\":0.5"))
    }

    @Test
    fun `encode escapes quotes newline and backslash`() {
        val json = JsonCodec.encode(mapOf("sel" to "a\"b\nc\\d"))
        assertEquals("{\"sel\":\"a\\\"b\\nc\\\\d\"}", json)
    }

    @Test
    fun `encode keeps non-ascii as raw utf-8`() {
        val json = JsonCodec.encode(mapOf("sel" to "\u4e2d"))
        assertEquals("{\"sel\":\"\u4e2d\"}", json)
    }

    @Test
    fun `encode nested lists and maps`() {
        val json = JsonCodec.encode(
            mapOf(
                "roots" to listOf(
                    mapOf("name" to "a", "children" to emptyList<Any>())
                )
            )
        )
        assertEquals("{\"roots\":[{\"name\":\"a\",\"children\":[]}]}", json)
    }

    @Test
    fun `decode flat object with string number bool null`() {
        val map = JsonCodec.decodeObject("""{"filePath":"C:/a.kt","lineStart":1,"lineEnd":14,"ok":true,"x":null}""")
        assertEquals("C:/a.kt", map["filePath"])
        assertEquals(1L, map["lineStart"])
        assertEquals(14L, map["lineEnd"])
        assertEquals(true, map["ok"])
        assertNull(map["x"])
    }

    @Test
    fun `decode nested object and array`() {
        val map = JsonCodec.decodeObject("""{"roots":[{"name":"a","children":[]}],"empty":{}}""")
        val roots = map["roots"] as List<*>
        assertEquals(1, roots.size)
        val first = roots[0] as Map<*, *>
        assertEquals("a", first["name"])
        assertTrue((first["children"] as List<*>).isEmpty())
        assertEquals(emptyMap<String, Any?>(), map["empty"])
    }

    @Test
    fun `decode escapes`() {
        val map = JsonCodec.decodeObject("""{"sel":"a\"b\nc\\d"}""")
        assertEquals("a\"b\nc\\d", map["sel"])
    }

    @Test
    fun `decode malformed returns empty map`() {
        assertEquals(emptyMap<String, Any?>(), JsonCodec.decodeObject("not json"))
        assertEquals(emptyMap<String, Any?>(), JsonCodec.decodeObject(""))
        assertEquals(emptyMap<String, Any?>(), JsonCodec.decodeObject("[1,2]"))
    }

    @Test
    fun `round trip encode decode`() {
        val original = mapOf(
            "name" to "demo",
            "files" to listOf(
                mapOf("path" to "src/a.kt", "modified" to true),
                mapOf("path" to "src/b.kt", "modified" to false),
            ),
            "count" to 7,
        )
        val decoded = JsonCodec.decodeObject(JsonCodec.encode(original))
        assertEquals("demo", decoded["name"])
        assertEquals(7L, decoded["count"])
        val files = decoded["files"] as List<*>
        assertEquals(2, files.size)
        val first = files[0] as Map<*, *>
        assertEquals("src/a.kt", first["path"])
        assertEquals(true, first["modified"])
    }
}
