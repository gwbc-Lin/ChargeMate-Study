package com.wirelesspet.session

import com.wirelesspet.power.ChargingStatus
import com.wirelesspet.power.PowerSnapshot
import com.wirelesspet.power.PowerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设计文档 §15.2 里"充满"那一行要求：不因电池状态改变而误判电源断开。
 * 系统在无线充电器上会分别给出 CHARGING / FULL / NOT_CHARGING（充电保护暂停），
 * 这些都属于"仍在无线电源上"，只有拔离才会结束会话。
 */
class ChargingEdgeCaseTest {

    private var now = 10_000L
    private val timings = SessionTimings(connectConfirmMs = 900L, disconnectGraceMs = 1_500L)
    private val controller = ChargingSessionController(timings)

    private fun wireless(status: ChargingStatus, level: Int = 41) = PowerSnapshot(
        type = PowerType.WIRELESS,
        pluggedBitmask = 0x4,
        levelPercent = level,
        status = status,
        observedAtElapsedMs = now,
    )

    private fun unplugged() = PowerSnapshot(
        type = PowerType.NONE,
        pluggedBitmask = 0,
        levelPercent = 41,
        status = ChargingStatus.DISCHARGING,
        observedAtElapsedMs = now,
    )

    private fun activate() {
        controller.onPowerChanged(wireless(ChargingStatus.CHARGING), now)
        now += timings.connectConfirmMs
        controller.onDeadline(now)
        assertEquals(SessionPhase.ACTIVE, controller.state.value.phase)
    }

    @Test
    fun `满电后拔离仍然正常结束会话`() {
        activate()
        val paused = controller.onPowerChanged(wireless(ChargingStatus.NOT_CHARGING), now)
        assertEquals(SessionPhase.ACTIVE, paused.state.phase)
        assertTrue(paused.commands.isEmpty())
        now += 1_000
        val full = controller.onPowerChanged(wireless(ChargingStatus.FULL, level = 100), now)
        assertEquals(SessionPhase.ACTIVE, full.state.phase)
        assertTrue(full.commands.isEmpty())

        now += 1_000
        val leaving = controller.onPowerChanged(unplugged(), now)
        assertEquals(SessionPhase.CONFIRMING_DISCONNECT, leaving.state.phase)

        now += timings.disconnectGraceMs
        val ended = controller.onDeadline(now)
        assertEquals(SessionPhase.IDLE, ended.state.phase)
        assertNull(ended.state.sessionId)
        assertEquals(SuppressionReason.NONE, ended.state.suppression)
    }

}
