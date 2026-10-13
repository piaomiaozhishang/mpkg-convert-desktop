package com.mpkg.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 手写 JSON 的读写回归（任务卡 T3.3 要求不引三方库，因此必须自己测）。 */
class JsonTest {

    @Test
    fun `对象数组往返不丢字段`() {
        val original = JsonValue.obj(
            "name" to JsonValue.of("壁纸 with \"quotes\" and \\slash"),
            "count" to JsonValue.of(42),
            "ratio" to JsonValue.JsonNumber(1.5),
            "flag" to JsonValue.of(true),
            "none" to JsonValue.JsonNull,
            "list" to JsonValue.arr(listOf(JsonValue.of("a"), JsonValue.of("b")))
        )
        val text = JsonWriter.write(original)
        val parsed = JsonParser.parse(text)
        assertEquals("壁纸 with \"quotes\" and \\slash", parsed["name"].asString())
        assertEquals(42, parsed["count"].asInt())
        assertEquals(1.5, parsed["ratio"].asDoubleOrNull() ?: 0.0, 0.0001)
        assertTrue(parsed["flag"].asBoolean())
        assertEquals(2, parsed["list"].asList().size)
        assertNull(parsed["missing"].asStringOrNull())
    }

    @Test
    fun `换行与控制字符被正确转义`() {
        val text = JsonWriter.write(JsonValue.obj("k" to JsonValue.of("a\nb\tc\u0001d")))
        assertTrue(text.contains("\\n"))
        assertTrue(text.contains("\\t"))
        assertTrue(text.contains("\\u0001"))
        assertEquals("a\nb\tc\u0001d", JsonParser.parse(text)["k"].asString())
    }

    @Test
    fun `整数不带小数点写出`() {
        assertEquals("{\"n\":7}", JsonWriter.write(JsonValue.obj("n" to JsonValue.of(7))))
    }

    @Test
    fun `非法输入解析返回 null 而不是抛异常`() {
        assertNull(JsonParser.parseOrNull("{ 不是 json "))
        assertNull(JsonParser.parseOrNull(""))
    }

    @Test
    fun `中文路径与反斜杠往返一致`() {
        val path = """D:\pro-workbuddy\mpkg转换windows端\out\main.png"""
        val text = JsonWriter.write(JsonValue.obj("p" to JsonValue.of(path)))
        assertEquals(path, JsonParser.parse(text)["p"].asString())
    }

    @Test
    fun `嵌套取值链在缺字段时安全返回默认值`() {
        val root = JsonParser.parse("""{"window":{"width":960}}""")
        assertEquals(960, root["window"]["width"].asInt())
        assertEquals(1100, root["window"]["height"].asInt(1100))
        assertEquals(0, root["nothing"]["deep"]["deeper"].asInt())
    }
}
