package com.mpkg.scene.core

/**
 * 场景合成的布局数学（纯 JVM，可单测）。
 *
 * 一个图层在画布上的落点：四边形中心 = origin × exportScale，
 * 尺寸 = 画布尺寸 × scale × exportScale，即
 *     left = originX·s − canvasW·scaleX·s / 2
 * 旋转角 angleZDeg 以四边形中心为轴（屏幕坐标，正角度顺时针）。
 */
object SceneLayout {

    data class Placement(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    fun placement(
        sceneWidth: Int,
        sceneHeight: Int,
        layer: SceneLayer,
        exportScale: Float
    ): Placement {
        val dw = (layer.width ?: sceneWidth.toFloat()) * layer.scaleX * exportScale
        val dh = (layer.height ?: sceneHeight.toFloat()) * layer.scaleY * exportScale
        val cx = layer.originX * exportScale
        // Wallpaper Engine uses Y up; raster images use Y down.
        val cy = (sceneHeight - layer.originY) * exportScale
        return Placement(cx - dw / 2f, cy - dh / 2f, cx + dw / 2f, cy + dh / 2f)
    }

    /** 导出画布尺寸（scene 尺寸 × exportScale，至少 1px）。 */
    fun canvasSize(sceneWidth: Int, sceneHeight: Int, exportScale: Float): Pair<Int, Int> {
        val w = (sceneWidth * exportScale).toInt().coerceAtLeast(1)
        val h = (sceneHeight * exportScale).toInt().coerceAtLeast(1)
        return w to h
    }
}
