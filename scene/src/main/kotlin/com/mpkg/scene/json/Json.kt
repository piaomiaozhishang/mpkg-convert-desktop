package com.mpkg.scene.json

/**
 * 极简 JSON 读写（任务卡 T3.3 要求：手写序列化，不引三方库）。
 *
 * 只覆盖本项目需要的类型：对象 / 数组 / 字符串 / 数字 / 布尔 / null。
 * 解析器是标准的递归下降实现，遇到非法输入抛 [IllegalArgumentException]，调用方决定降级策略。
 */
sealed interface JsonValue {

    data class JsonString(val value: String) : JsonValue
    data class JsonNumber(val value: Double) : JsonValue
    data class JsonBoolean(val value: Boolean) : JsonValue
    data object JsonNull : JsonValue
    data class JsonArray(val items: List<JsonValue>) : JsonValue
    data class JsonObject(val fields: Map<String, JsonValue>) : JsonValue

    companion object {
        fun of(value: String?): JsonValue = if (value == null) JsonNull else JsonString(value)
        fun of(value: Int): JsonValue = JsonNumber(value.toDouble())
        fun of(value: Long): JsonValue = JsonNumber(value.toDouble())
        fun of(value: Boolean): JsonValue = JsonBoolean(value)
        fun of(value: Boolean?): JsonValue = if (value == null) JsonNull else JsonBoolean(value)
        fun of(items: List<JsonValue>): JsonValue = JsonArray(items)
        fun obj(vararg pairs: Pair<String, JsonValue>): JsonObject = JsonObject(pairs.toMap())
        fun arr(items: List<JsonValue>): JsonArray = JsonArray(items)
    }
}

// 下面这些扩展同时支持可空接收者：读配置时 `node["a"]["b"]` 一路可空，写起来不用层层判空。

/** 取对象字段；不是对象或字段缺失时返回 null。 */
operator fun JsonValue?.get(key: String): JsonValue? =
    (this as? JsonValue.JsonObject)?.fields?.get(key)

fun JsonValue?.asStringOrNull(): String? = (this as? JsonValue.JsonString)?.value

fun JsonValue?.asString(default: String = ""): String = (this as? JsonValue.JsonString)?.value ?: default

fun JsonValue?.asDoubleOrNull(): Double? = (this as? JsonValue.JsonNumber)?.value

fun JsonValue?.asInt(default: Int = 0): Int = (this as? JsonValue.JsonNumber)?.value?.toInt() ?: default

fun JsonValue?.asLong(default: Long = 0L): Long = (this as? JsonValue.JsonNumber)?.value?.toLong() ?: default

fun JsonValue?.asBoolean(default: Boolean = false): Boolean =
    (this as? JsonValue.JsonBoolean)?.value ?: default

/** 取布尔值；字段缺失、是 null 或不是布尔时返回 null（用于「从未检查」三态）。 */
fun JsonValue?.asBooleanOrNull(): Boolean? = (this as? JsonValue.JsonBoolean)?.value

fun JsonValue?.asList(): List<JsonValue> = (this as? JsonValue.JsonArray)?.items ?: emptyList()

/** 把 [JsonValue] 序列化成紧凑 JSON 文本。 */
object JsonWriter {

    fun write(value: JsonValue): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(sb: StringBuilder, value: JsonValue) {
        when (value) {
            is JsonValue.JsonNull -> sb.append("null")
            is JsonValue.JsonBoolean -> sb.append(if (value.value) "true" else "false")
            is JsonValue.JsonNumber -> appendNumber(sb, value.value)
            is JsonValue.JsonString -> appendString(sb, value.value)
            is JsonValue.JsonArray -> {
                sb.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    append(sb, item)
                }
                sb.append(']')
            }
            is JsonValue.JsonObject -> {
                sb.append('{')
                value.fields.entries.forEachIndexed { index, (key, item) ->
                    if (index > 0) sb.append(',')
                    appendString(sb, key)
                    sb.append(':')
                    append(sb, item)
                }
                sb.append('}')
            }
        }
    }

    private fun appendNumber(sb: StringBuilder, value: Double) {
        if (value.isFinite() && value == Math.floor(value) && Math.abs(value) < 1e15) {
            sb.append(value.toLong())
        } else {
            sb.append(value)
        }
    }

    private fun appendString(sb: StringBuilder, text: String) {
        sb.append('"')
        for (ch in text) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }
}

/** 递归下降 JSON 解析器。 */
object JsonParser {

    fun parse(text: String): JsonValue {
        val cursor = Cursor(text)
        cursor.skipWhitespace()
        val value = readValue(cursor)
        cursor.skipWhitespace()
        require(cursor.atEnd()) { "JSON 尾部存在多余内容（位置 ${cursor.index}）" }
        return value
    }

    /**
     * 解析失败时返回 null，便于调用方回退到默认值。
     *
     * 这里连 [StackOverflowError] 一起兜住：SettingsStore 的契约是「设置文件损坏也绝不能
     * 让应用起不来」，而 Error 不属于 Exception，只 catch Exception 会让它逃逸到启动流程。
     * [readValue] 的深度上限已经挡掉了绝大多数情况，这一层只是最后一道保险。
     */
    fun parseOrNull(text: String): JsonValue? = try {
        parse(text)
    } catch (e: Exception) {
        null
    } catch (e: StackOverflowError) {
        null
    }

    private class Cursor(val text: String) {
        var index = 0
        /** 当前嵌套层数，用于挡住损坏/恶意的超深 JSON（见 [readValue]）。 */
        var depth = 0
        fun atEnd() = index >= text.length
        fun peek(): Char = text[index]
        fun next(): Char = text[index++]
        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }
        fun expect(ch: Char) {
            require(index < text.length && text[index] == ch) {
                "位置 $index 期望 '$ch'，实际为 ${if (index < text.length) "'${text[index]}'" else "文件结尾"}"
            }
            index++
        }
    }

    /**
     * 读一个值。
     *
     * 递归下降天然受栈深限制：没有深度上限时，一个几 KB 的 `[[[[…]]]]` 就能让
     * `StackOverflowError` 逃逸出 `parseOrNull` 的 `catch (e: Exception)`，
     * 结果是设置文件一坏应用就起不来。这里显式限深，超限按**解析失败**处理。
     */
    private fun readValue(c: Cursor): JsonValue {
        require(!c.atEnd()) { "JSON 意外结束" }
        require(c.depth < MAX_DEPTH) { "JSON 嵌套层级超过 $MAX_DEPTH 层，疑似文件损坏" }
        c.depth++
        return try {
            when (c.peek()) {
                '{' -> readObject(c)
                '[' -> readArray(c)
                '"' -> JsonValue.JsonString(readString(c))
                't', 'f' -> readBoolean(c)
                'n' -> readNull(c)
                else -> readNumber(c)
            }
        } finally {
            c.depth--
        }
    }

    private fun readObject(c: Cursor): JsonValue.JsonObject {
        c.expect('{')
        val fields = LinkedHashMap<String, JsonValue>()
        c.skipWhitespace()
        if (!c.atEnd() && c.peek() == '}') {
            c.index++
            return JsonValue.JsonObject(fields)
        }
        while (true) {
            c.skipWhitespace()
            val key = readString(c)
            c.skipWhitespace()
            c.expect(':')
            c.skipWhitespace()
            fields[key] = readValue(c)
            c.skipWhitespace()
            when {
                c.atEnd() -> throw IllegalArgumentException("JSON 对象未闭合")
                c.peek() == ',' -> c.index++
                c.peek() == '}' -> {
                    c.index++
                    break
                }
                else -> throw IllegalArgumentException("位置 ${c.index} 出现非法字符 '${c.peek()}'")
            }
        }
        return JsonValue.JsonObject(fields)
    }

    private fun readArray(c: Cursor): JsonValue.JsonArray {
        c.expect('[')
        val items = ArrayList<JsonValue>()
        c.skipWhitespace()
        if (!c.atEnd() && c.peek() == ']') {
            c.index++
            return JsonValue.JsonArray(items)
        }
        while (true) {
            c.skipWhitespace()
            items.add(readValue(c))
            c.skipWhitespace()
            when {
                c.atEnd() -> throw IllegalArgumentException("JSON 数组未闭合")
                c.peek() == ',' -> c.index++
                c.peek() == ']' -> {
                    c.index++
                    break
                }
                else -> throw IllegalArgumentException("位置 ${c.index} 出现非法字符 '${c.peek()}'")
            }
        }
        return JsonValue.JsonArray(items)
    }

    private fun readString(c: Cursor): String {
        c.expect('"')
        val sb = StringBuilder()
        while (true) {
            require(!c.atEnd()) { "JSON 字符串未闭合" }
            when (val ch = c.next()) {
                '"' -> return sb.toString()
                '\\' -> {
                    require(!c.atEnd()) { "JSON 转义未完成" }
                    when (val esc = c.next()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            require(c.index + 4 <= c.text.length) { "JSON \\u 转义不完整" }
                            sb.append(c.text.substring(c.index, c.index + 4).toInt(16).toChar())
                            c.index += 4
                        }
                        else -> throw IllegalArgumentException("未知转义 \\$esc")
                    }
                }
                else -> sb.append(ch)
            }
        }
    }

    private fun readBoolean(c: Cursor): JsonValue.JsonBoolean = when {
        c.text.startsWith("true", c.index) -> {
            c.index += 4
            JsonValue.JsonBoolean(true)
        }
        c.text.startsWith("false", c.index) -> {
            c.index += 5
            JsonValue.JsonBoolean(false)
        }
        else -> throw IllegalArgumentException("位置 ${c.index} 期望布尔值")
    }

    /** 允许的最大嵌套层数；本项目 settings/history 的实际深度不超过 4 层。 */
    private const val MAX_DEPTH = 64

    private fun readNull(c: Cursor): JsonValue {
        require(c.text.startsWith("null", c.index)) { "位置 ${c.index} 期望 null" }
        c.index += 4
        return JsonValue.JsonNull
    }

    private fun readNumber(c: Cursor): JsonValue.JsonNumber {
        val start = c.index
        if (!c.atEnd() && (c.peek() == '-' || c.peek() == '+')) c.index++
        while (!c.atEnd() && (c.peek().isDigit() || c.peek() in ".eE+-")) c.index++
        val raw = c.text.substring(start, c.index)
        require(raw.isNotEmpty()) { "位置 $start 期望数字" }
        return JsonValue.JsonNumber(raw.toDouble())
    }
}
