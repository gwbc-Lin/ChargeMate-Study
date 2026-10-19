package com.wirelesspet.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeUpExitTest {
    private val gesture = SwipeUpExit()
    private val height = 2_000

    @Test
    fun `竖直上滑足够远时退出`() {
        assertTrue(gesture.isUpwardSwipe(dx = 12f, dy = -400f, elapsedMs = 350L, viewHeight = height))
    }

    @Test
    fun `下滑与横向滑动都不算上滑退出`() {
        assertFalse(gesture.isUpwardSwipe(dx = 0f, dy = 400f, elapsedMs = 350L, viewHeight = height))
        assertFalse(gesture.isUpwardSwipe(dx = 420f, dy = -60f, elapsedMs = 350L, viewHeight = height))
        // 斜向滑动以横向为主时交给横向切换角色，不触发退出。
        assertFalse(gesture.isUpwardSwipe(dx = 300f, dy = -300f, elapsedMs = 350L, viewHeight = height))
    }

    @Test
    fun `距离不足或太慢都不算上滑退出`() {
        assertFalse(gesture.isUpwardSwipe(dx = 0f, dy = -80f, elapsedMs = 350L, viewHeight = height))
        assertFalse(gesture.isUpwardSwipe(dx = 0f, dy = -400f, elapsedMs = 1_800L, viewHeight = height))
    }

    @Test
    fun `没有起点或已经消费过的手势不重复退出`() {
        assertFalse(gesture.finish(x = 100f, y = 300f, eventTimeMs = 400L, viewHeight = height))

        gesture.begin(100f, 900f, 1_000L)
        assertTrue(gesture.finish(110f, 300f, 1_300L, height))
        assertFalse(gesture.finish(110f, 200f, 1_500L, height))

        gesture.begin(100f, 900f, 2_000L)
        gesture.cancel()
        assertFalse(gesture.finish(110f, 200f, 2_300L, height))
    }

    @Test
    fun `横屏时阈值按可见高度换算`() {
        // 同一段位移在竖屏大屏上只占 6%，未达 8%；在横屏 720 高的窗口上就够长了。
        assertFalse(gesture.isUpwardSwipe(dx = 0f, dy = -120f, elapsedMs = 300L, viewHeight = 2_000))
        assertTrue(gesture.isUpwardSwipe(dx = 0f, dy = -120f, elapsedMs = 300L, viewHeight = 720))
    }
}
