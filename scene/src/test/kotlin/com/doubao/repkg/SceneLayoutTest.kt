package com.doubao.repkg

import com.mpkg.scene.core.SceneLayer
import com.mpkg.scene.core.SceneLayout
import org.junit.Assert.assertEquals
import org.junit.Test

/** SceneLayout 的布局数学单测（纯 JVM）。 */
class SceneLayoutTest {

    private fun layer(
        originX: Float = 960f,
        originY: Float = 540f,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
        angleZDeg: Float = 0f
    ) = SceneLayer(
        objectName = "x",
        textureName = "t",
        originX = originX,
        originY = originY,
        scaleX = scaleX,
        scaleY = scaleY,
        angleZDeg = angleZDeg,
        visible = true,
        userToggle = null
    )

    @Test
    fun centeredLayerCoversWholeCanvas() {
        val p = SceneLayout.placement(1920, 1080, layer(), 1f)
        assertEquals(0f, p.left, 1e-3f)
        assertEquals(0f, p.top, 1e-3f)
        assertEquals(1920f, p.right, 1e-3f)
        assertEquals(1080f, p.bottom, 1e-3f)
    }

    @Test
    fun originOffsetShiftsQuad() {
        // origin 971.94098,540 -> 左移 11.94px，四边形中心就是 origin 本身
        val p = SceneLayout.placement(1920, 1080, layer(originX = 971.94098f), 1f)
        assertEquals(11.94f, p.left, 1e-2f)
        assertEquals(1931.94f, p.right, 1e-2f)
        assertEquals(971.94098f, p.centerX, 1e-3f)
    }

    @Test
    fun scaleMultipliesSizeAroundOrigin() {
        val p = SceneLayout.placement(1920, 1080, layer(scaleX = 1.5f, scaleY = 0.5f), 1f)
        assertEquals(2880f, p.width, 1e-3f)
        assertEquals(540f, p.height, 1e-3f)
        // 仍以 origin 为中心
        assertEquals(960f, p.centerX, 1e-3f)
        assertEquals(540f, p.centerY, 1e-3f)
    }

    @Test
    fun exportScaleShrinksEverything() {
        val p = SceneLayout.placement(3840, 2160, layer(originX = 1920f, originY = 1080f), 0.5f)
        assertEquals(0f, p.left, 1e-3f)
        assertEquals(0f, p.top, 1e-3f)
        assertEquals(1920f, p.right, 1e-3f)
        assertEquals(1080f, p.bottom, 1e-3f)
    }

    @Test
    fun canvasSizeNeverDropsBelowOnePixel() {
        val (w, h) = SceneLayout.canvasSize(1920, 1080, 0.0001f)
        assertEquals(1, w)
        assertEquals(1, h)
    }
}
