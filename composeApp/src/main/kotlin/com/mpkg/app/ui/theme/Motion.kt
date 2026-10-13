package com.mpkg.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween

/**
 * 动效令牌（任务卡 T3.2）。
 *
 * 报告要求：单个动效不超过 300ms；「减少动态效果」开关可一键全部关闭；
 * 动效不阻塞解码与交互。所有时长集中在这里，组件不得写死毫秒数。
 */
object Motion {

    /** 时长（毫秒）。全部 ≤ 300ms。 */
    const val INSTANT = 0
    const val FAST = 120
    const val NORMAL = 180
    const val SLOW = 240
    const val MAX_ALLOWED = 300

    private val standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    private val emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 通用进入动效。 */
    fun <T> enter(reduceMotion: Boolean): FiniteAnimationSpec<T> =
        tween(durationMillis = if (reduceMotion) INSTANT else NORMAL, easing = standard)

    /** 通用退出动效。 */
    fun <T> exit(reduceMotion: Boolean): FiniteAnimationSpec<T> =
        tween(durationMillis = if (reduceMotion) INSTANT else FAST, easing = standard)

    /** 进度条推进（要平滑，禁止 0% 直接跳 100%）。 */
    fun <T> progress(reduceMotion: Boolean): FiniteAnimationSpec<T> =
        tween(durationMillis = if (reduceMotion) INSTANT else SLOW, easing = emphasized)
}
