package com.wirelesspet.presentation

/**
 * 启动尝试闸门（设计文档 §6.2、§13："启动失败不循环重试"）。
 *
 * 职责很小但很关键：记住"本次会话已经因后台启动受限失败过"，
 * 让后续电池、屏幕、设置事件不再反复尝试拉起页面，直到：
 * - 用户主动点"继续展示"（明确意图，允许一次受控恢复）；
 * - 或者进入新的会话（新 sessionId）。
 *
 * 纯逻辑，不碰 Android API，便于单元测试。
 */
class LaunchGate {

    enum class Decision { ALLOW, ALREADY_BLOCKED, ALREADY_ACTIVE }

    private var blockedSessionId: Long? = null
    private var activeRequestSessionId: Long? = null

    /** 是否可以发起一次启动。 */
    fun canAttempt(sessionId: Long?): Decision {
        val id = sessionId ?: return Decision.ALLOW
        if (activeRequestSessionId != null) return Decision.ALREADY_ACTIVE
        if (blockedSessionId == id) return Decision.ALREADY_BLOCKED
        return Decision.ALLOW
    }

    /** 记录"已发出启动请求但尚未确认可见"。 */
    fun onAttemptStarted(sessionId: Long?) {
        activeRequestSessionId = sessionId
    }

    /** 宿主确认可见：清掉请求中标记，并解除本会话的受限记录。 */
    fun onAttemptSucceeded(sessionId: Long?) {
        activeRequestSessionId = null
        if (sessionId == blockedSessionId) blockedSessionId = null
    }

    /** 超时或异常：本会话标记为受限，后续普通事件不再尝试。 */
    fun onAttemptFailed(sessionId: Long?) {
        activeRequestSessionId = null
        blockedSessionId = sessionId
    }

    /** 宿主正常结束（用户关闭、会话结束）：只清请求中标记。 */
    fun onHostFinished() {
        activeRequestSessionId = null
    }

    /** 用户主动继续：允许一次受控恢复。 */
    fun onUserResume() {
        blockedSessionId = null
        activeRequestSessionId = null
    }

    /** 自动化关闭 / 全局重置。 */
    fun reset() {
        blockedSessionId = null
        activeRequestSessionId = null
    }

    fun blockedSession(): Long? = blockedSessionId
}
