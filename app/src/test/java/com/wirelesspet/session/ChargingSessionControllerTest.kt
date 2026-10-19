package com.wirelesspet.session

import com.wirelesspet.power.ChargingStatus
import com.wirelesspet.power.PowerSnapshot
import com.wirelesspet.power.PowerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话状态机测试：覆盖设计文档 §15.2 中必须单测的部分
 * （防抖、重复事件、退出抑制、恢复与去重）。
 */
class ChargingSessionControllerTest {

    private var now = 1_000L
    private val timings = SessionTimings(connectConfirmMs = 900L, disconnectGraceMs = 1_500L)
    private val controller = ChargingSessionController(timings)

    private fun wireless() = PowerSnapshot(
        type = PowerType.WIRELESS,
        pluggedBitmask = 0x4,
        levelPercent = 50,
        status = ChargingStatus.CHARGING,
        observedAtElapsedMs = now,
    )

    private fun wired() = PowerSnapshot(
        type = PowerType.AC,
        pluggedBitmask = 0x1,
        levelPercent = 50,
        status = ChargingStatus.CHARGING,
        observedAtElapsedMs = now,
    )

    private fun nothing() = PowerSnapshot(type = PowerType.NONE, pluggedBitmask = 0, observedAtElapsedMs = now)

    /** 把会话推进到 ACTIVE，并返回是否产生 StartPresentation。 */
    private fun activate(): List<SessionCommand> {
        val first = controller.onPowerChanged(wireless(), now)
        assertEquals(SessionPhase.CONFIRMING_CONNECT, first.state.phase)
        assertTrue("确认窗口内不应产生展示命令", first.commands.isEmpty())
        now += timings.connectConfirmMs
        return controller.onDeadline(now).commands
    }

    @Test
    fun `无线连接需要经过确认窗口才建立会话`() {
        controller.onPowerChanged(wireless(), now)
        assertEquals(SessionPhase.CONFIRMING_CONNECT, controller.state.value.phase)

        now += timings.connectConfirmMs - 1
        val early = controller.onDeadline(now)
        assertEquals("未到确认窗口不应建立会话", SessionPhase.CONFIRMING_CONNECT, early.state.phase)
        assertTrue(early.commands.isEmpty())

        now += 1
        val due = controller.onDeadline(now)
        assertEquals(SessionPhase.ACTIVE, due.state.phase)
        assertEquals(1, due.commands.size)
        assertTrue(due.commands.first() is SessionCommand.StartPresentation)
    }

    @Test
    fun `有线充电不建立会话`() {
        val update = controller.onPowerChanged(wired(), now)
        assertEquals(SessionPhase.IDLE, update.state.phase)
        now += timings.connectConfirmMs * 2
        assertTrue(controller.onDeadline(now).commands.isEmpty())
        controller.wiredChargingEnabled = true
        controller.onPowerChanged(wired(), now)
        assertTrue(controller.onDeadline(now + timings.connectConfirmMs).commands.single() is SessionCommand.StartPresentation)
    }

    @Test
    fun `快速闪断被防抖吞掉并可撤销`() {
        controller.onPowerChanged(wireless(), now)
        now += 300
        controller.onPowerChanged(nothing(), now)
        assertEquals("闪断后应回到空闲", SessionPhase.IDLE, controller.state.value.phase)
        now += timings.connectConfirmMs * 2
        assertTrue(controller.onDeadline(now).commands.isEmpty())
    }

    @Test
    fun `重复电池广播不重复触发展示`() {
        val startCommands = activate()
        assertEquals(1, startCommands.size)

        repeat(5) {
            now += 100
            val update = controller.onPowerChanged(wireless(), now)
            assertTrue("重复广播不应产生命令", update.commands.isEmpty())
            assertEquals(SessionPhase.ACTIVE, update.state.phase)
        }
    }

    @Test
    fun `断连需要经过宽限期才结束会话`() {
        activate()
        val sessionId = controller.state.value.sessionId
        now += 500
        val unplugged = controller.onPowerChanged(nothing(), now)
        assertEquals(SessionPhase.CONFIRMING_DISCONNECT, unplugged.state.phase)
        assertEquals("宽限期内保持会话", sessionId, unplugged.state.sessionId)

        now += timings.disconnectGraceMs - 1
        assertTrue(controller.onDeadline(now).commands.isEmpty())

        now += 1
        val ended = controller.onDeadline(now)
        assertEquals(SessionPhase.IDLE, ended.state.phase)
        val stop = ended.commands.single()
        assertTrue(stop is SessionCommand.StopPresentation)
        assertEquals(sessionId, (stop as SessionCommand.StopPresentation).sessionId)
    }

    @Test
    fun `用户退出抑制绑定本次会话直到真正离开无线电源`() {
        activate()
        val sessionId = controller.state.value.sessionId

        val dismissed = controller.onUserDismiss(now)
        assertEquals(SuppressionReason.USER_DISMISSED, dismissed.state.suppression)
        assertFalse(controller.state.value.allowsPresentation)
        val stop = dismissed.commands.single() as SessionCommand.StopPresentation
        assertEquals(SuppressionReason.USER_DISMISSED, stop.reason)

        // 重复的电池广播不能清除抑制。
        now += 200
        controller.onPowerChanged(wireless(), now)
        assertEquals(SuppressionReason.USER_DISMISSED, controller.state.value.suppression)
        assertEquals(sessionId, controller.state.value.sessionId)

        // 拔掉充电器 -> 宽限期结束 -> 会话结束抑制清零。
        now += 200
        controller.onPowerChanged(nothing(), now)
        now += timings.disconnectGraceMs
        controller.onDeadline(now)
        assertEquals(SuppressionReason.NONE, controller.state.value.suppression)
        assertEquals(SessionPhase.IDLE, controller.state.value.phase)

        // 重新放回充电器 -> 新会话 -> 可以再次展示。
        now += 5_000
        controller.onPowerChanged(wireless(), now)
        now += timings.connectConfirmMs
        val resumed = controller.onDeadline(now)
        assertTrue(resumed.commands.single() is SessionCommand.StartPresentation)
        assertFalse(resumed.state.sessionId == sessionId)
    }

    @Test
    fun `进程恢复时跨启动周期直接丢弃`() {
        val persisted = PersistedSession(
            sessionId = 42L,
            phase = SessionPhase.ACTIVE.name,
            suppression = SuppressionReason.NONE.name,
            powerType = PowerType.WIRELESS.name,
            bootCount = 7,
            savedAtWallMs = 0,
        )
        controller.restore(persisted, wireless(), now, currentBootCount = 8)
        assertEquals(SessionPhase.IDLE, controller.state.value.phase)
    }

    @Test
    fun `进程恢复后保持保守策略等待手动继续`() {
        val persisted = PersistedSession(
            sessionId = 42L,
            phase = SessionPhase.ACTIVE.name,
            suppression = SuppressionReason.NONE.name,
            powerType = PowerType.WIRELESS.name,
            bootCount = 7,
            savedAtWallMs = 0,
        )
        val restored = controller.restore(persisted, wireless(), now, currentBootCount = 7)
        assertEquals(SessionPhase.ACTIVE, restored.state.phase)
        assertEquals(SuppressionReason.RECOVERY_CONSERVATIVE, restored.state.suppression)
        assertTrue("恢复阶段不能自动产生展示命令", restored.commands.isEmpty())

        val resumed = controller.onResumeRequested(now)
        assertTrue(resumed.commands.single() is SessionCommand.StartPresentation)
    }

}
