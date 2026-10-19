package com.wirelesspet.session

import com.wirelesspet.power.ChargingStatus
import com.wirelesspet.power.PowerSnapshot
import com.wirelesspet.power.PowerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抑制语义测试：把"别再自动弹出"和"立刻关闭页面"拆成两件事。
 *
 * 对应审查 2.3（断连宽限期不该关页面）与 2.7（用户息屏只要求不再自动展示）。
 */
class SuppressionSemanticsTest {

    private var now = 5_000L
    private val timings = SessionTimings(connectConfirmMs = 900L, disconnectGraceMs = 1_500L)
    private val controller = ChargingSessionController(timings)

    private fun wireless() = PowerSnapshot(
        type = PowerType.WIRELESS,
        pluggedBitmask = 0x4,
        levelPercent = 60,
        status = ChargingStatus.CHARGING,
        observedAtElapsedMs = now,
    )

    private fun activate() {
        controller.onPowerChanged(wireless(), now)
        now += timings.connectConfirmMs
        controller.onDeadline(now)
        assertTrue(controller.state.value.isActiveSession)
    }

    @Test
    fun `用户息屏只要求不再自动展示而不关闭页面`() {
        activate()
        val update = controller.suppressForSession(SuppressionReason.USER_SCREEN_OFF, now)
        assertEquals(SuppressionReason.USER_SCREEN_OFF, update.state.suppression)
        assertTrue(update.state.suppression.bindsToSession)
        assertFalse("息屏不应要求立刻关闭页面", update.state.suppression.closesHost)
        assertFalse(update.state.allowsPresentation)
        assertTrue("不应产生停止展示指令，页面可以留着", update.commands.isEmpty())
    }

}
