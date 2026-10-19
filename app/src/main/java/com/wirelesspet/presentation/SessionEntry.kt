package com.wirelesspet.presentation

import com.wirelesspet.session.SuppressionReason
import com.wirelesspet.session.WirelessSessionState

/** 先验证通知所属会话，再构造策略输入；验证失败绝不清除真实会话的抑制。 */
object SessionEntry {
    fun forPresentation(
        state: WirelessSessionState,
        expectedSessionId: Long?,
        userInitiated: Boolean,
    ): WirelessSessionState? {
        if (!state.isActiveSession || expectedSessionId == null || state.sessionId != expectedSessionId) {
            return null
        }
        return if (userInitiated) state.copy(suppression = SuppressionReason.NONE) else state
    }
}
