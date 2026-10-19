package com.wirelesspet.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 启动闸门测试：对应审查 2.4"启动受限后可能随事件反复尝试"。
 *
 * 行为目标：
 * - 一次启动失败后，同一 session 的后续事件不再自动尝试；
 * - 新会话可以重新尝试（换了一次充电）；
 * - 用户主动"继续展示"允许一次受控恢复；
 * - 请求尚未确认时不允许再发一次（避免同一会话重复创建页面）。
 */
class LaunchGateTest {

    private val gate = LaunchGate()

    @Test
    fun `用户主动继续允许一次受控恢复`() {
        gate.onAttemptStarted(100L)
        gate.onAttemptFailed(100L)
        assertEquals(LaunchGate.Decision.ALREADY_BLOCKED, gate.canAttempt(100L))
        assertEquals(LaunchGate.Decision.ALLOW, gate.canAttempt(200L))
        gate.onUserResume()
        assertEquals(LaunchGate.Decision.ALLOW, gate.canAttempt(100L))
        assertNull(gate.blockedSession())
    }

    @Test
    fun `请求确认中不能重复发起`() {
        gate.onAttemptStarted(100L)
        assertEquals(LaunchGate.Decision.ALREADY_ACTIVE, gate.canAttempt(100L))
        // 宿主真的可见后，本会话解除受限
        gate.onAttemptSucceeded(100L)
        assertEquals(LaunchGate.Decision.ALLOW, gate.canAttempt(100L))
    }

}
