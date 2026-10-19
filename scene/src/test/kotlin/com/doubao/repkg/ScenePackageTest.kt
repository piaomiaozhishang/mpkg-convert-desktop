package com.doubao.repkg

import com.mpkg.scene.core.ScenePackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ScenePackage.parse 的单测：scene.json -> 图层列表。
 * org.json 由 testImplementation 提供（Android 平台内置的实现）。
 */
class ScenePackageTest {

    /** 简易内存条目表：path -> 内容；缺失返回 null。 */
    private fun reader(vararg pairs: Pair<String, String>): (String) -> ByteArray? =
        { path -> pairs.firstOrNull { it.first == path }?.second?.toByteArray() }

    private fun sceneJson(objects: String) = """
        {"camera": {"center": "960 540 0"}, "objects": [$objects], "version": 0}
    """.trimIndent().toByteArray()

    @Test
    fun parsesLayersInOrder() {
        val files = reader(
            "models/bg.json" to """{"autosize": true, "material": "materials/bg.json"}""",
            "materials/bg.json" to """{"passes": [{"textures": ["bg"]}]}""",
            "models/hero.json" to """{"autosize": true, "material": "materials/hero.json"}""",
            "materials/hero.json" to """{"passes": [{"textures": ["hero"]}]}"""
        )
        val info = ScenePackage.parse(
            sceneJson(
                """
                {"name": "bg", "image": "models/bg.json", "size": "1920.000 1080.000",
                 "origin": "960.000 540.000 0.000", "visible": true},
                {"name": "hero", "image": "models/hero.json", "size": "1920.000 1080.000",
                 "origin": "960.000 540.000 0.000"}
                """
            ),
            files
        )
        assertNotNull(info)
        info!!
        assertEquals(1920, info.canvasWidth)
        assertEquals(1080, info.canvasHeight)
        assertEquals(2, info.layers.size)
        assertEquals("bg", info.layers[0].objectName)
        assertEquals("bg", info.layers[0].textureName)
        assertEquals("hero", info.layers[1].textureName)
        assertEquals(2, info.visibleLayerCount)
        assertEquals(0, info.hiddenLayerCount)
        assertEquals(0, info.particleCount)
        assertTrue(info.notes.isEmpty())
    }

    @Test
    fun scaleOriginAngleAreParsed() {
        val files = reader(
            "models/a.json" to """{"autosize": true, "material": "materials/a.json"}""",
            "materials/a.json" to """{"passes": [{"textures": ["a"]}]}"""
        )
        val info = ScenePackage.parse(
            sceneJson(
                """
                {"name": "a", "image": "models/a.json", "size": "1920.000 1080.000",
                 "origin": "971.94098 540.00000 0.00000", "scale": "1.04728 1.04728 1.04728",
                 "angles": "0.00000 0.00000 -0.11600"}
                """
            ),
            files
        )!!
        val layer = info.layers[0]
        assertEquals(971.94098f, layer.originX, 1e-3f)
        assertEquals(540f, layer.originY, 1e-3f)
        assertEquals(1.04728f, layer.scaleX, 1e-4f)
        assertEquals(1.04728f, layer.scaleY, 1e-4f)
        assertEquals(-0.116f, layer.angleZDeg, 1e-3f)
    }

    @Test
    fun visibleFalseAndUserToggleAreParsed() {
        val files = reader(
            "models/a.json" to """{"autosize": true, "material": "materials/a.json"}""",
            "materials/a.json" to """{"passes": [{"textures": ["a"]}]}""",
            "models/b.json" to """{"autosize": true, "material": "materials/b.json"}""",
            "materials/b.json" to """{"passes": [{"textures": ["b"]}]}"""
        )
        val info = ScenePackage.parse(
            sceneJson(
                """
                {"name": "a", "image": "models/a.json", "size": "1920.000 1080.000", "visible": false},
                {"name": "b", "image": "models/b.json", "size": "1920.000 1080.000",
                 "visible": {"user": "blackandwhitecartoon", "value": false}}
                """
            ),
            files
        )!!
        assertFalse(info.layers[0].visible)
        assertNull(info.layers[0].userToggle)
        assertFalse(info.layers[1].visible)
        assertEquals("blackandwhitecartoon", info.layers[1].userToggle)
        assertEquals(0, info.visibleLayerCount)
        assertEquals(2, info.hiddenLayerCount)
    }

    @Test
    fun particleObjectsAndMissingFilesAreNoted() {
        val files = reader(
            "models/a.json" to """{"autosize": true, "material": "materials/a.json"}""",
            "materials/a.json" to """{"passes": [{"textures": ["a"]}]}"""
        )
        val info = ScenePackage.parse(
            sceneJson(
                """
                {"name": "a", "image": "models/a.json", "size": "1920.000 1080.000"},
                {"name": "dust", "particle": "particles/presets/dust.json"},
                {"name": "gone", "image": "models/missing.json", "size": "1920.000 1080.000"}
                """
            ),
            files
        )!!
        assertEquals(1, info.layers.size)
        assertEquals(1, info.particleCount)
        assertTrue(info.notes.any { it.contains("gone") })
    }

    @Test
    fun explicitGeometryModelIsSkipped() {
        val files = reader(
            "models/a.json" to """{"autosize": true, "material": "materials/a.json"}""",
            "materials/a.json" to """{"passes": [{"textures": ["a"]}]}""",
            "models/mesh.json" to """{"vertices": "0 0 0", "material": "materials/a.json"}"""
        )
        val info = ScenePackage.parse(
            sceneJson(
                """
                {"name": "a", "image": "models/a.json", "size": "1920.000 1080.000"},
                {"name": "mesh", "image": "models/mesh.json", "size": "1920.000 1080.000"}
                """
            ),
            files
        )!!
        assertEquals(1, info.layers.size)
        assertTrue(info.notes.any { it.contains("mesh") && it.contains("几何体") })
    }

    @Test
    fun firstNonNullTextureWinsAcrossPasses() {
        val files = reader(
            "models/a.json" to """{"autosize": true, "material": "materials/a.json"}""",
            // 第一个 pass 只有 null 纹理，第二个 pass 才是真正的画
            "materials/a.json" to """{"passes": [
                {"textures": [null, null]},
                {"textures": ["art"]}
            ]}"""
        )
        val info = ScenePackage.parse(
            sceneJson(
                """{"name": "a", "image": "models/a.json", "size": "1920.000 1080.000"}"""
            ),
            files
        )!!
        assertEquals("art", info.layers[0].textureName)
    }

    @Test
    fun malformedJsonReturnsNull() {
        assertNull(ScenePackage.parse("not json".toByteArray(), reader()))
    }

    @Test fun projectionOverridesFirstObjectAndOpacityEffectIsRetained() {
        val info = ScenePackage.parse("""{
            "general":{"orthogonalprojection":{"width":3840,"height":2160}},
            "objects":[{"image":"models/a.json","size":"100 200","alpha":0.5,
                "color":"1 0.5 0.25","effects":[
                    {"file":"effects/opacity/effect.json","passes":[{"textures":[null,"masks/a"]}]},
                    {"file":"effects/waterwaves/effect.json"}
                ]}]
        }""".toByteArray(), reader("models/a.json" to """{"material":"materials/a.json"}""",
            "materials/a.json" to """{"passes":[{"textures":["a"]}]}"""))!!
        assertEquals(3840, info.canvasWidth)
        assertEquals(2160, info.canvasHeight)
        assertEquals(100f, info.layers[0].width!!, 0f)
        assertEquals(.5f, info.layers[0].opacity, 0f)
        assertEquals("masks/a", info.layers[0].opacityMasks.single().texture)
        assertTrue(info.notes.any { "waterwaves" in it })
        assertFalse(info.notes.any { "opacity 尚未" in it })
    }

    @Test
    fun noSizedObjectsReturnsNull() {
        val info = ScenePackage.parse(
            sceneJson("""{"name": "p", "particle": "particles/x.json"}"""),
            reader()
        )
        assertNull(info)
    }
}
