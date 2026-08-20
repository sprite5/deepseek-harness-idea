package com.deepseek.harness.idea.util

/**
 * 极简 JSON 编解码（无第三方/平台依赖）。
 *
 * 仅覆盖本插件所需的形状：对象（键为 String）、数组、字符串、数字、布尔、null；
 * 用于 IDE Bridge Server 的请求/响应序列化。不追求完整 JSON 规范，
 * 但保证与本插件写入/读出的数据双向一致，且不含任何平台库依赖（平台 Gson 已被
 * JetBrains 逐步移除，见 docs/DESIGN.md §3.1 与 docs/PROJECT_NOTES.md）。
 */
object JsonCodec {

    // ---- 编码 ----

    fun encode(value: Any?): String {
        val sb = StringBuilder()
        write(sb, value)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Number -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    write(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, item)
                }
                sb.append(']')
            }
            is Array<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, item)
                }
                sb.append(']')
            }
            else -> writeString(sb, v.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    // ---- 解码 ----

    /** 解析 JSON 对象；格式非法或顶层非对象时返回空 Map（调用方按缺省处理）。 */
    fun decodeObject(json: String): Map<String, Any?> {
        val parser = Parser(json)
        return try {
            parser.parseObject()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun parseObject(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            skipWs()
            expect('{')
            skipWs()
            if (peek() == '}') {
                pos++
                return result
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                skipWs()
                result[key] = parseValue()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return result
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun parseValue(): Any? {
            skipWs()
            return when (val c = peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> if (c == '-' || c in '0'..'9') parseNumber() else fail("unexpected char '$c'")
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') {
                pos++
                return result
            }
            while (true) {
                result.add(parseValue())
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return result
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = next()
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> sb.append(parseEscape())
                    c == '\u0000' -> fail("unterminated string")
                    else -> sb.append(c)
                }
            }
        }

        private fun parseEscape(): Char {
            return when (val c = next()) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (pos + 4 > s.length) fail("bad unicode escape")
                    val hex = s.substring(pos, pos + 4)
                    pos += 4
                    hex.toIntOrNull(16)?.toChar() ?: fail("bad unicode escape")
                }
                else -> fail("bad escape '\\$c'")
            }
        }

        private fun parseNumber(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && (s[pos] in '0'..'9' || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E' || s[pos] == '+' || s[pos] == '-')) pos++
            val text = s.substring(start, pos)
            return text.toLongOrNull() ?: text.toDoubleOrNull() ?: fail("bad number '$text'")
        }

        private fun parseLiteral(literal: String, value: Any?): Any? {
            if (!s.startsWith(literal, pos)) fail("expected '$literal'")
            pos += literal.length
            return value
        }

        private fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t' || s[pos] == '\n' || s[pos] == '\r')) pos++
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'

        private fun next(): Char {
            if (pos >= s.length) return '\u0000'
            return s[pos++]
        }

        private fun expect(c: Char) {
            if (pos >= s.length || s[pos] != c) fail("expected '$c'")
            pos++
        }

        private fun fail(msg: String): Nothing = throw IllegalArgumentException("JSON parse error at $pos: $msg")
    }
}
