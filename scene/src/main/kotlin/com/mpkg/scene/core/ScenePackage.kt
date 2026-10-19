package com.mpkg.scene.core

import com.mpkg.scene.json.*

/**
 * 场景壁纸（scene.json）中的一层：一个 autosize 全屏四边形 + 一张材质纹理。
 *
 * 坐标系：与画布像素一致（y 向下），origin 为四边形中心，
 * 尺寸 = 画布尺寸 × scale，angleZDeg 为绕中心的旋转角（度，屏幕坐标顺时针为正）。
 */
data class SceneLayer(
    /** scene.json 里的对象名（如 "背景" / "fondo zero"）。 */
    val objectName: String,
    /** 材质 passes[0].textures 的第一个非空纹理名；null 表示该层无法合成。 */
    val textureName: String?,
    val originX: Float,
    val originY: Float,
    val scaleX: Float,
    val scaleY: Float,
    val angleZDeg: Float,
    /** false = 用户在壁纸属性面板里关掉的层（或用户属性开关为关）。 */
    val visible: Boolean,
    /** visible 是用户属性对象时的属性名（如 "blackandwhitecartoon"），否则 null。 */
    val userToggle: String?,
    val width: Float? = null,
    val height: Float? = null,
    val opacity: Float = 1f,
    val tint: List<Float> = listOf(1f, 1f, 1f),
    val opacityMasks: List<SceneOpacityMask> = emptyList()
) {
    val canComposite: Boolean get() = textureName != null
}

data class SceneOpacityMask(val texture: String?, val opacity: Float = 1f)

/** 解析后的场景图定义。 */
data class SceneInfo(
    val canvasWidth: Int,
    val canvasHeight: Int,
    /** 按 scene.json 数组顺序（数组越靠前越先画，即底层）。 */
    val layers: List<SceneLayer>,
    /** 粒子发射器等无图像对象的数量（静态合成不渲染）。 */
    val particleCount: Int,
    /** 解析过程中的跳过/警告说明，供 UI 展示。 */
    val notes: List<String>,
    val backgroundArgb: Int = 0,
    val effects: List<String> = emptyList()
) {
    val visibleLayerCount: Int get() = layers.count { it.visible }
    val hiddenLayerCount: Int get() = layers.count { !it.visible }
}

/**
 * scene.json 解析器；使用桌面项目现有的纯 Kotlin JSON 实现，不依赖 Android 或第三方 JSON 库。
 *
 * [readEntry] 按 TOC 路径读取条目字节（models 与 materials 目录下的 JSON），
 * 解析失败返回 null 时该层记入 notes 并跳过。
 */
object ScenePackage {

    fun parse(sceneJson: ByteArray, readEntry: (String) -> ByteArray?): SceneInfo? {
        val root = try {
            JsonParser.parse(String(sceneJson, Charsets.UTF_8))
        } catch (e: Exception) {
            return null
        }
        val objects = (root["objects"] as? JsonValue.JsonArray)?.items ?: return null

        val general = root["general"]
        val projection = general["orthogonalprojection"]
        var canvasW = projection["width"].asInt()
        var canvasH = projection["height"].asInt()
        if (canvasW <= 0 || canvasH <= 0) {
            val firstSize = objects.firstNotNullOfOrNull { parseVec2(value(it["size"]).asString()) }
            canvasW = firstSize?.first?.toInt() ?: 0
            canvasH = firstSize?.second?.toInt() ?: 0
        }
        val layers = ArrayList<SceneLayer>(objects.size)
        val notes = ArrayList<String>()
        var particles = 0
        val effects = linkedSetOf<String>()
        if (general["cameraparallax"].asBoolean()) notes += "鼠标视差：静态图仅保留初始位置"
        if (general["bloom"].asBoolean()) notes += "全局泛光尚未渲染"
        if (general["camerashake"].asBoolean()) notes += "相机抖动尚未渲染"

        for (i in objects.indices) {
            val o = objects[i] as? JsonValue.JsonObject ?: continue
            val name = o["name"].asString("object_$i")
            val image = o["image"].asString()
            if (image.isEmpty() || image.startsWith("particles/")) {
                if (o["particle"] != null || image.startsWith("particles/")) {
                    particles++
                    notes += "「$name」粒子尚未渲染"
                } else if (o["sound"] != null) notes += "「$name」音频不包含在静态图片中"
                else notes += "「$name」非图片对象尚未渲染"
                continue
            }
            if (o["size"] == null) {
                notes.add("「$name」无 size 字段，已跳过")
                continue
            }
            val size = parseVec2(value(o["size"]).asString())
            if (size == null) {
                notes.add("「$name」size 无法解析，已跳过")
                continue
            }
            // 画布尺寸取第一个带 size 的对象（通常是背景层）
            if (canvasW == 0) {
                canvasW = size.first.toInt()
                canvasH = size.second.toInt()
            }
            val origin = parseVec2(value(o["origin"]).asString()) ?: (canvasW / 2f to canvasH / 2f)
            val scale = parseVec2(value(o["scale"]).asString()) ?: (1f to 1f)
            val angle = parseVec3Z(value(o["angles"]).asString()) ?: 0f
            if (o["parent"] != null) notes += "「$name」父子变换尚未还原"
            if (o["perspective"].asBoolean()) notes += "「$name」透视投影尚未还原"
            for (key in listOf("origin", "scale", "angles", "alpha", "color", "visible")) {
                if (o[key]["script"] != null) notes += "「$name」$key 脚本仅使用保存值"
            }

            var visible = true
            var userToggle: String? = null
            when (val v = o["visible"]) {
                is JsonValue.JsonBoolean -> visible = v.value
                is JsonValue.JsonObject -> {
                    userToggle = v["user"].asString().takeIf { it.isNotEmpty() }
                    visible = v["value"].asBoolean(false)
                }
                null, JsonValue.JsonNull, is JsonValue.JsonString, is JsonValue.JsonNumber, is JsonValue.JsonArray -> Unit
            }

            // 模型 JSON：autosize 才按全屏四边形合成；带显式几何体的不处理
            val modelBytes = readEntry(image)
            if (modelBytes == null) {
                notes.add("「$name」模型 JSON 缺失（$image），已跳过")
                continue
            }
            val model = try {
                JsonParser.parse(String(modelBytes, Charsets.UTF_8))
            } catch (e: Exception) {
                notes.add("「$name」模型 JSON 无法解析，已跳过")
                continue
            }
            val geoKeys = GEO_KEYS.filter { model[it] != null }
            if (geoKeys.isNotEmpty() || model["autosize"].asBoolean(true).not()) {
                notes.add("「$name」模型带显式几何体/非 autosize，暂不支持，已跳过")
                continue
            }

            // 材质 JSON：取 passes[0] 第一个非空纹理
            val materialRef = model["material"].asString().takeIf { it.isNotEmpty() }
                ?: image.replace("models/", "materials/")
            val materialBytes = readEntry(materialRef)
            if (materialBytes == null) {
                notes.add("「$name」材质 JSON 缺失（$materialRef），已跳过")
                continue
            }
            val textureName = try {
                val material = JsonParser.parse(String(materialBytes, Charsets.UTF_8))
                val passes = material["passes"].asList()
                if (passes.size > 1) notes += "「$name」多 pass 材质仅还原基础纹理"
                val blend = passes.firstOrNull()?.get("blending").asString("translucent")
                if (blend !in listOf("translucent", "normal")) notes += "「$name」混合模式 $blend 尚未还原"
                var found: String? = null
                if (passes.isNotEmpty()) {
                    outer@ for (pass in passes) {
                        val textures = pass["textures"].asList()
                        for (texture in textures) {
                            val s = texture.asString()
                            if (s.isNotEmpty()) {
                                found = s
                                break@outer
                            }
                        }
                    }
                }
                found
            } catch (e: Exception) {
                null
            }
            if (textureName == null) {
                notes.add("「$name」材质没有纹理引用，已跳过")
                continue
            }

            val masks = ArrayList<SceneOpacityMask>()
            for (effect in o["effects"].asList()) {
                if (!value(effect["visible"]).asBoolean(true)) continue
                val path = effect["file"].asString()
                val type = path.removePrefix("effects/").removeSuffix("/effect.json")
                effects += type
                if (type == "opacity") {
                    val pass = effect["passes"].asList().firstOrNull()
                    val mask = pass["textures"].asList().getOrNull(1).asString().takeIf { it.isNotBlank() && it != "util/white" }
                    val alpha = value(pass["constantshadervalues"]["alpha"]).asDoubleOrNull()?.toFloat() ?: 1f
                    masks += SceneOpacityMask(mask, alpha.coerceIn(0f, 1f))
                } else notes += "「$name」特效 $type 尚未渲染"
            }

            layers += SceneLayer(
                objectName = name,
                textureName = textureName,
                originX = origin.first,
                originY = origin.second,
                scaleX = scale.first,
                scaleY = scale.second,
                angleZDeg = angle,
                visible = visible,
                userToggle = userToggle,
                width = size.first,
                height = size.second,
                opacity = (value(o["alpha"]).asDoubleOrNull()?.toFloat() ?: 1f).coerceIn(0f, 1f),
                tint = color(value(o["color"]).asString("1 1 1")),
                opacityMasks = masks
            )
        }

        if (canvasW <= 0 || canvasH <= 0 || layers.isEmpty()) return null
        return SceneInfo(
            canvasWidth = canvasW,
            canvasHeight = canvasH,
            layers = layers,
            particleCount = particles,
            notes = notes.distinct(),
            backgroundArgb = if (general["clearenabled"].asBoolean()) {
                val c = color(general["clearcolor"].asString("0 0 0"))
                (0xff shl 24) or ((c[0] * 255).toInt() shl 16) or ((c[1] * 255).toInt() shl 8) or (c[2] * 255).toInt()
            } else 0,
            effects = effects.toList()
        )
    }

    private fun value(v: JsonValue?): JsonValue? = if (v is JsonValue.JsonObject) v["value"] else v
    private fun color(s: String): List<Float> = s.trim().split(Regex("\\s+"))
        .mapNotNull { it.toFloatOrNull()?.takeIf(Float::isFinite)?.coerceIn(0f, 1f) }
        .takeIf { it.size >= 3 }?.take(3) ?: listOf(1f, 1f, 1f)

    /** "3840.000 2160.000" -> (3840, 2160)；容忍多余空白。 */
    private fun parseVec2(s: String): Pair<Float, Float>? {
        val parts = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val x = parts[0].toFloatOrNull() ?: return null
        val y = parts[1].toFloatOrNull() ?: return null
        return if (x.isFinite() && y.isFinite()) x to y else null
    }

    /** "0.00000 0.00000 -0.11600" -> -0.116（取 Z 分量）。 */
    private fun parseVec3Z(s: String): Float? {
        val parts = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        return parts[2].toFloatOrNull()
    }

    private val GEO_KEYS = listOf("vertices", "uvs", "indices", "vertexcount")
}
