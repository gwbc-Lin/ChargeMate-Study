package com.wirelesspet.presentation

import kotlin.math.abs

/**
 * 全屏宠物页面的"上滑退出"手势判定。
 *
 * 设计约束（设计文档 §7.3）：
 * - 上滑只是返回键与关闭按钮之外更顺手的一条退出口，不是唯一入口；
 * - 提示文案里必须写明，不做需要用户自己猜的隐藏手势；
 * - 判定只看"够远、够快、以竖直向上为主"，避免轻触互动、横向切换角色时误退出。
 *
 * 纯逻辑、不依赖 Android 类型，可直接在 JVM 单元测试里覆盖边界。
 */
class SwipeUpExit(
    private val maxDurationMs: Long = 1_200L,
    private val minDistanceFraction: Float = 0.08f,
    private val minDistancePx: Float = 48f,
    private val verticalDominance: Float = 1.4f,
) {
    private var startX = 0f
    private var startY = 0f
    private var startTimeMs = 0L
    private var tracking = false

    fun begin(x: Float, y: Float, eventTimeMs: Long) {
        startX = x
        startY = y
        startTimeMs = eventTimeMs
        tracking = true
    }

    /** 结束一次手势；返回 true 表示应当按"用户主动退出"处理。判定后状态自动复位。 */
    fun finish(x: Float, y: Float, eventTimeMs: Long, viewHeight: Int): Boolean {
        if (!tracking) return false
        tracking = false
        return isUpwardSwipe(x - startX, y - startY, eventTimeMs - startTimeMs, viewHeight)
    }

    fun cancel() {
        tracking = false
    }

    fun isUpwardSwipe(dx: Float, dy: Float, elapsedMs: Long, viewHeight: Int): Boolean {
        if (elapsedMs < 0 || elapsedMs > maxDurationMs) return false
        val threshold = maxOf(minDistancePx, viewHeight * minDistanceFraction)
        // 屏幕坐标 y 向下为正：上滑得到负的 dy。
        return dy <= -threshold && -dy >= abs(dx) * verticalDominance
    }
}
