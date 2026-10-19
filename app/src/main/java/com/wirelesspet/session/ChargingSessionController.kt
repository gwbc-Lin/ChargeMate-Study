package com.wirelesspet.session

import com.wirelesspet.power.PowerSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 无线充电会话状态机。
 *
 * 设计约束（见设计文档 §6）：
 * - 纯逻辑，不引用任何 Android API，可直接单元测试；
 * - 事件幂等：重复的电池广播只更新信息，不重复建立会话、不重复触发展示；
 * - 用户退出把抑制绑定到当前 sessionId，只有确认离开无线电源并开新会话才清除；
 * - 状态不明（进程恢复、电源类型未知）时保守处理，不反复弹出，等用户手动继续。
 */
class ChargingSessionController(
    private val timings: SessionTimings = SessionTimings(),
    private val onTransition: ((WirelessSessionState, List<SessionCommand>) -> Unit)? = null,
    var wiredChargingEnabled: Boolean = false,
) {
    private val _state = MutableStateFlow(WirelessSessionState())
    val state: StateFlow<WirelessSessionState> = _state.asStateFlow()

    /** 电池状态变化入口。重复事件在这里被合并。 */
    fun onPowerChanged(power: PowerSnapshot, nowElapsedMs: Long): SessionUpdate {
        val current = _state.value
        val next: WirelessSessionState

        if (power.isTriggerSource(wiredChargingEnabled)) {
            when (current.phase) {
                SessionPhase.IDLE -> {
                    next = current.copy(
                        phase = SessionPhase.CONFIRMING_CONNECT,
                        suppression = SuppressionReason.NONE,
                        lastPower = power,
                        pendingSinceElapsedMs = nowElapsedMs,
                        deadlineElapsedMs = nowElapsedMs + timings.connectConfirmMs,
                        updatedAtElapsedMs = nowElapsedMs,
                    )
                }

                SessionPhase.CONFIRMING_CONNECT -> {
                    // 已在确认窗口内：只刷新电源信息，不改时间窗，避免抖动无限顺延。
                    next = current.copy(lastPower = power, updatedAtElapsedMs = nowElapsedMs)
                }

                SessionPhase.ACTIVE -> {
                    next = current.copy(lastPower = power, updatedAtElapsedMs = nowElapsedMs)
                }

                SessionPhase.CONFIRMING_DISCONNECT -> {
                    // 断连确认期内电源回来了：撤销断开，保留会话与抑制状态。
                    next = current.copy(
                        phase = SessionPhase.ACTIVE,
                        lastPower = power,
                        pendingSinceElapsedMs = null,
                        deadlineElapsedMs = null,
                        updatedAtElapsedMs = nowElapsedMs,
                    )
                }
            }
        } else {
            when (current.phase) {
                SessionPhase.IDLE -> {
                    next = current.copy(lastPower = power, updatedAtElapsedMs = nowElapsedMs)
                }

                SessionPhase.CONFIRMING_CONNECT -> {
                    // 连接没有稳定成立，回到空闲；不产生任何展示命令。
                    next = current.copy(
                        phase = SessionPhase.IDLE,
                        suppression = SuppressionReason.NONE,
                        lastPower = power,
                        pendingSinceElapsedMs = null,
                        deadlineElapsedMs = null,
                        updatedAtElapsedMs = nowElapsedMs,
                    )
                }

                SessionPhase.ACTIVE -> {
                    next = current.copy(
                        phase = SessionPhase.CONFIRMING_DISCONNECT,
                        lastPower = power,
                        pendingSinceElapsedMs = nowElapsedMs,
                        deadlineElapsedMs = nowElapsedMs + timings.disconnectGraceMs,
                        updatedAtElapsedMs = nowElapsedMs,
                    )
                }

                SessionPhase.CONFIRMING_DISCONNECT -> {
                    next = current.copy(lastPower = power, updatedAtElapsedMs = nowElapsedMs)
                }
            }
        }

        return commit(next)
    }

    /** 到期检查入口：到达 [WirelessSessionState.deadlineElapsedMs] 时由上层调用。 */
    fun onDeadline(nowElapsedMs: Long): SessionUpdate {
        val current = _state.value
        val deadline = current.deadlineElapsedMs ?: return SessionUpdate(current)
        if (nowElapsedMs < deadline) return SessionUpdate(current)

        return when (current.phase) {
            SessionPhase.CONFIRMING_CONNECT -> {
                if (current.lastPower?.isTriggerSource(wiredChargingEnabled) == true) {
                    val sessionId = current.sessionId ?: nowElapsedMs
                    commit(
                        current.copy(
                            phase = SessionPhase.ACTIVE,
                            sessionId = sessionId,
                            pendingSinceElapsedMs = null,
                            deadlineElapsedMs = null,
                            updatedAtElapsedMs = nowElapsedMs,
                        ),
                        listOf(SessionCommand.StartPresentation(sessionId)),
                    )
                } else {
                    commit(
                        current.copy(
                            phase = SessionPhase.IDLE,
                            suppression = SuppressionReason.NONE,
                            pendingSinceElapsedMs = null,
                            deadlineElapsedMs = null,
                            updatedAtElapsedMs = nowElapsedMs,
                        ),
                    )
                }
            }

            SessionPhase.CONFIRMING_DISCONNECT -> {
                val endedSessionId = current.sessionId
                commit(
                    current.copy(
                        phase = SessionPhase.IDLE,
                        sessionId = null,
                        // 已确认离开无线电源：会话级抑制在这里清除，下一次充电重新开始。
                        suppression = SuppressionReason.NONE,
                        pendingSinceElapsedMs = null,
                        deadlineElapsedMs = null,
                        updatedAtElapsedMs = nowElapsedMs,
                    ),
                    listOf(SessionCommand.StopPresentation(endedSessionId, SuppressionReason.NOT_WIRELESS)),
                )
            }

            else -> commit(current.copy(deadlineElapsedMs = null, updatedAtElapsedMs = nowElapsedMs))
        }
    }

    /** 用户点关闭 / 返回：绑定当前会话抑制，并停止展示。 */
    fun onUserDismiss(nowElapsedMs: Long): SessionUpdate = suppress(
        reason = SuppressionReason.USER_DISMISSED,
        nowElapsedMs = nowElapsedMs,
    )

    /** 达到最长展示时长、温度退出等会话级抑制。 */
    fun suppressForSession(reason: SuppressionReason, nowElapsedMs: Long): SessionUpdate {
        require(reason.bindsToSession) { "只有会话级抑制才能绑定到当前会话：$reason" }
        return suppress(reason, nowElapsedMs)
    }

    /** 用户在首页或通知上点"继续展示"。 */
    fun onResumeRequested(nowElapsedMs: Long): SessionUpdate {
        val current = _state.value
        if (!current.isActiveSession || !current.isSuppressed) {
            return SessionUpdate(current)
        }
        val sessionId = requireNotNull(current.sessionId)
        return commit(
            current.copy(
                suppression = SuppressionReason.NONE,
                updatedAtElapsedMs = nowElapsedMs,
            ),
            listOf(SessionCommand.StartPresentation(sessionId)),
        )
    }

    /**
     * 进程恢复：重新读取当前真实电源状态，不用旧数据直接继续展示。
     *
     * 保守策略：同一启动周期内恢复出的会话保持"需手动继续"，只有当前确实处于无线电源
     * 且用户手动继续时才展示；跨启动周期的旧会话直接丢弃。
     */
    fun restore(
        persisted: PersistedSession?,
        power: PowerSnapshot?,
        nowElapsedMs: Long,
        currentBootCount: Int,
    ): SessionUpdate {
        val current = _state.value
        if (current.phase != SessionPhase.IDLE || persisted == null) {
            return SessionUpdate(current)
        }
        val valid = persisted.bootCount == currentBootCount &&
            persisted.phase == SessionPhase.ACTIVE.name &&
            power?.isTriggerSource(wiredChargingEnabled) == true
        if (!valid) {
            return SessionUpdate(current)
        }
        return commit(
            current.copy(
                phase = SessionPhase.ACTIVE,
                sessionId = persisted.sessionId,
                suppression = runCatching { SuppressionReason.valueOf(persisted.suppression) }
                    .getOrNull()?.takeIf { it.bindsToSession && it != SuppressionReason.MAX_DURATION }
                    ?: SuppressionReason.RECOVERY_CONSERVATIVE,
                lastPower = power,
                updatedAtElapsedMs = nowElapsedMs,
            ),
        )
    }

    /** 自动化关闭时必须清空会话与抑制，避免下次开启时残留。 */
    fun reset(nowElapsedMs: Long): SessionUpdate {
        val current = _state.value
        if (current == WirelessSessionState()) return SessionUpdate(current)
        return commit(WirelessSessionState(updatedAtElapsedMs = nowElapsedMs))
    }

    private fun suppress(reason: SuppressionReason, nowElapsedMs: Long): SessionUpdate {
        val current = _state.value
        val sessionId = current.sessionId
        if (sessionId == null || current.phase == SessionPhase.IDLE) {
            return SessionUpdate(current)
        }
        if (current.suppression == reason) return SessionUpdate(current)
        // 只有"要求关闭宿主"的抑制才发停止展示指令。
        // 例如用户息屏、启动受限只要求"本次别再自动弹出"，页面本身可以留着，
        // 否则会把"别再自动展示"误解成"立刻关掉正在看的页面"。
        val commands = if (reason.closesHost) {
            listOf(SessionCommand.StopPresentation(sessionId, reason))
        } else {
            emptyList()
        }
        return commit(
            current.copy(suppression = reason, updatedAtElapsedMs = nowElapsedMs),
            commands,
        )
    }

    private fun commit(
        next: WirelessSessionState,
        commands: List<SessionCommand> = emptyList(),
    ): SessionUpdate {
        _state.value = next
        onTransition?.invoke(next, commands)
        return SessionUpdate(next, commands)
    }
}

/** 需要跨进程死亡持久化的最小会话快照（见设计文档 §6.3、§12.1）。 */
data class PersistedSession(
    val sessionId: Long,
    val phase: String,
    val suppression: String,
    val powerType: String,
    val bootCount: Int,
    val savedAtWallMs: Long,
)
