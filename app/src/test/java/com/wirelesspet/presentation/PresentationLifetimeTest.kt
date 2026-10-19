package com.wirelesspet.presentation

import com.wirelesspet.power.PowerSnapshot
import com.wirelesspet.power.PowerType
import com.wirelesspet.session.*
import org.junit.Assert.*
import org.junit.Test

class PresentationLifetimeTest {
    @Test fun `默认断连等待半秒后结束且短抖动保留会话`() {
        val controller = ChargingSessionController()
        val wireless = PowerSnapshot(type = PowerType.WIRELESS, pluggedBitmask = 4)
        val disconnected = PowerSnapshot(type = PowerType.NONE, pluggedBitmask = 0)
        controller.onPowerChanged(wireless, 1_000L)
        controller.onDeadline(1_900L)
        val id = controller.state.value.sessionId
        controller.onPowerChanged(disconnected, 2_000L)
        controller.onDeadline(2_499L)
        assertTrue(controller.state.value.retainsHost(id))
        controller.onPowerChanged(wireless, 2_499L)
        assertEquals(id, controller.state.value.sessionId)
        controller.onPowerChanged(disconnected, 3_000L)
        val ended = controller.onDeadline(3_500L)
        assertEquals(SessionPhase.IDLE, ended.state.phase)
        assertTrue(ended.commands.single() is SessionCommand.StopPresentation)
    }

    @Test fun `温控更新不会重新开始最长展示时长`() {
        val lifetime = PresentationLifetime(1_000L)
        assertEquals(59_000L, lifetime.remainingMs(60_000L, 2_000L))
        assertEquals(1_000L, lifetime.remainingMs(60_000L, 60_000L))
        assertEquals(0L, lifetime.remainingMs(60_000L, 61_000L))
    }

}
