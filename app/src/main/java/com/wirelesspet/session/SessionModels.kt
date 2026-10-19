package com.wirelesspet.session

import com.wirelesspet.power.PowerSnapshot

/**
 * 一次无线充电会话的阶段。
 *
 * 防抖策略采用"时间窗确认"而不是立即响应：
 * 连接需稳定经过 [SessionTimings.connectConfirmMs] 才建立会话，断开需稳定经过
 * [SessionTimings.disconnectGraceMs] 才结束会话，避免线圈偏移、接触不良导致的连续闪退重开。
 */
enum class SessionPhase {
    IDLE,
    CONFIRMING_CONNECT,
    ACTIVE,
    CONFIRMING_DISCONNECT,
}

/**
 * 展示抑制原因。既要驱动逻辑，也要能在首页与诊断里原样解释给用户。
 *
 * [bindsToSession] 为 true 的原因会绑定当前 sessionId，只有在确认离开无线电源并开启新会话后清除。
 * [closesHost] 表示这个原因同时要求"立刻关闭当前宿主"，与"仅仅是别再自动弹出"区分开：
 * 例如用户息屏只需要停止自动展示，页面本身可以留着等用户回来。
 */
enum class SuppressionReason(
    val label: String,
    val bindsToSession: Boolean = false,
    val closesHost: Boolean = false,
) {
    NONE("无"),
    USER_DISMISSED("用户已退出本次展示", bindsToSession = true, closesHost = true),
    MAX_DURATION("已达到最长展示时长", bindsToSession = true, closesHost = true),
    THERMAL_END("温度保护，已结束本次展示", bindsToSession = true, closesHost = true),
    FULL_CHARGE("已充满，按设置结束本次展示", bindsToSession = true, closesHost = true),
    USER_SCREEN_OFF("用户息屏，本次不再自动展示", bindsToSession = true),
    RECOVERY_CONSERVATIVE("进程恢复后未自动展示，等待手动继续"),
    SCREEN_OFF("屏幕已熄灭"),
    LOCKED("锁屏展示未开启"),
    AUTOMATION_OFF("自动化已关闭", closesHost = true),
    SESSION_INACTIVE("当前没有生效中的无线充电会话"),
    NOT_WIRELESS("当前电源未启用自动显示"),
    INTERRUPTION_NOT_ALLOWED("正在使用其他应用，仅提示不打断"),
    TIME_WINDOW("不在生效时段"),
    THERMAL("温度较高，暂不展示"),
    LAUNCH_BLOCKED("后台启动受限，本次不再自动尝试", bindsToSession = true),
    UNKNOWN_POWER("电源类型未知"),
}

/** 会话状态机对外暴露的完整状态。 */
data class WirelessSessionState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val sessionId: Long? = null,
    val suppression: SuppressionReason = SuppressionReason.NONE,
    val lastPower: PowerSnapshot? = null,
    val pendingSinceElapsedMs: Long? = null,
    val deadlineElapsedMs: Long? = null,
    val updatedAtElapsedMs: Long = 0L,
) {
    val isActiveSession: Boolean get() = phase == SessionPhase.ACTIVE && sessionId != null

    /** 会话允许展示：有会话、且没有被会话级抑制。 */
    val allowsPresentation: Boolean
        get() = isActiveSession && suppression == SuppressionReason.NONE

    /** 已经展示的宿主在断连确认期继续保留，不能用启动条件决定退出。 */
    fun retainsHost(expectedSessionId: Long?): Boolean =
        sessionId != null && sessionId == expectedSessionId && !suppression.closesHost &&
            (phase == SessionPhase.ACTIVE || phase == SessionPhase.CONFIRMING_DISCONNECT)

    val isSuppressed: Boolean get() = suppression != SuppressionReason.NONE

    fun describe(): String = when (phase) {
        SessionPhase.IDLE -> "无充电会话"
        SessionPhase.CONFIRMING_CONNECT -> "确认充电连接中"
        SessionPhase.ACTIVE -> "充电会话进行中"
        SessionPhase.CONFIRMING_DISCONNECT -> "确认充电断开中"
    }
}

/** 状态机产生的副作用指令，由上层（AutomationService）执行；状态机自身不碰 Android API。 */
sealed interface SessionCommand {
    data class StartPresentation(val sessionId: Long) : SessionCommand
    data class StopPresentation(val sessionId: Long?, val reason: SuppressionReason) : SessionCommand
}

data class SessionUpdate(
    val state: WirelessSessionState,
    val commands: List<SessionCommand> = emptyList(),
)

/** 防抖时间窗。初值来自设计文档（断连 0.5～2 秒），真机再调。 */
data class SessionTimings(
    val connectConfirmMs: Long = 900L,
    val disconnectGraceMs: Long = 500L,
)
