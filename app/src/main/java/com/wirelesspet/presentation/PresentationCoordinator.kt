package com.wirelesspet.presentation

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.wirelesspet.automation.AutomationNotifications
import com.wirelesspet.core.MonoClock
import com.wirelesspet.data.EventLog
import com.wirelesspet.policy.PresentationSource
import com.wirelesspet.session.SuppressionReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class HostKind { ACTIVITY, DREAM }

data class ActiveHost(
    val kind: HostKind,
    val source: PresentationSource,
    val sessionId: Long?,
    val visible: Boolean,
    val startedAtMs: Long,
    val ownerToken: String? = null,
)

data class CloseRequest(val reason: SuppressionReason)

enum class PresentAttempt { STARTED, ALREADY_ACTIVE, BLOCKED, FAILED }

/**
 * 宿主协调器（设计文档 §5、§6.2、§13）。
 *
 * - 全局唯一宿主：Activity 与 DreamService 抢同一个槽位，重复请求被拒绝；
 * - 区分"已请求启动"与"宿主实际可见"：请求后等可见确认，超时按启动受限处理；
 * - 启动失败按 sessionId 记账（[LaunchGate]），本会话不再反复尝试，
 *   只有新会话或用户主动"继续展示"才允许再一次受控恢复；
 * - 被系统拦截时回调 [onLaunchBlocked]，让首页能把"需要去授权"讲清楚；
 * - 通知按（会话, 文案）去重，避免同一件事反复提醒。
 */
class PresentationCoordinator(
    private val context: Context,
    private val notifications: AutomationNotifications,
    private val eventLog: EventLog,
    private val clock: MonoClock,
    private val scope: CoroutineScope,
    /** 后台启动被拦截时回调，用于持久化"需要用户授权"并在首页提示。 */
    private val onLaunchBlocked: (() -> Unit)? = null,
    private val launchVerifyMs: Long = 2_800L,
) {
    private val _host = MutableStateFlow<ActiveHost?>(null)
    val host: StateFlow<ActiveHost?> = _host.asStateFlow()

    private val _closeRequests = MutableSharedFlow<CloseRequest>(extraBufferCapacity = 8)
    val closeRequests: SharedFlow<CloseRequest> = _closeRequests.asSharedFlow()

    private val gate = LaunchGate()
    private var watchdog: Job? = null
    private var lastNotificationKey: Pair<Long?, String>? = null

    fun isHostActive(): Boolean = _host.value != null

    fun isHostVisible(): Boolean = _host.value?.visible == true

    fun isSessionBlocked(sessionId: Long?): Boolean =
        gate.canAttempt(sessionId) == LaunchGate.Decision.ALREADY_BLOCKED

    /** 请求启动全屏页面。返回真实处理结果，调用方可据此绑定会话抑制。 */
    fun tryPresent(source: PresentationSource, sessionId: Long?, userInitiated: Boolean = false): PresentAttempt {
        val existing = _host.value
        if (existing != null) {
            if (userInitiated && existing.kind == HostKind.ACTIVITY && !existing.visible) {
                // 用户明确请求继续时，需要把保留在后台的页面带回前台。
                return if (launchPetActivity(source, sessionId, true)) PresentAttempt.STARTED
                else PresentAttempt.FAILED
            }
            eventLog.log(
                EventLog.Category.PRESENTATION,
                "忽略重复展示请求：已存在宿主 ${existing.kind}（${existing.source.label}）",
            )
            return PresentAttempt.ALREADY_ACTIVE
        }

        when (gate.canAttempt(sessionId)) {
            LaunchGate.Decision.ALREADY_ACTIVE -> {
                eventLog.log(
                    EventLog.Category.PRESENTATION,
                    "忽略重复展示请求：上一次启动仍在确认中 session=$sessionId",
                )
                return PresentAttempt.ALREADY_ACTIVE
            }

            LaunchGate.Decision.ALREADY_BLOCKED -> {
                eventLog.log(
                    EventLog.Category.PRESENTATION,
                    "本会话已启动受限，不再自动尝试 session=$sessionId",
                )
                return PresentAttempt.BLOCKED
            }

            LaunchGate.Decision.ALLOW -> Unit
        }

        val host = ActiveHost(
            kind = HostKind.ACTIVITY,
            source = source,
            sessionId = sessionId,
            visible = false,
            startedAtMs = clock.elapsedRealtimeMs(),
        )
        _host.value = host
        gate.onAttemptStarted(sessionId)
        eventLog.log(
            EventLog.Category.PRESENTATION,
            "已请求启动宿主：${source.label} session=$sessionId",
        )

        val started = launchPetActivity(source, sessionId, userInitiated)

        if (!started) {
            _host.value = null
            gate.onAttemptFailed(sessionId)
            onLaunchBlocked?.invoke()
            eventLog.log(
                EventLog.Category.ERROR,
                "启动宿主抛出异常，本次会话不再自动重试：${source.label}",
            )
            postReadyOnce("无法自动打开，点击显示", sessionId)
            return PresentAttempt.FAILED
        }

        watchdog?.cancel()
        watchdog = scope.launch {
            delay(launchVerifyMs)
            val current = _host.value ?: return@launch
            if (!current.visible && current.startedAtMs == host.startedAtMs) {
                eventLog.log(
                    EventLog.Category.ERROR,
                    "已请求启动但宿主未可见（后台启动可能被拦截），本次会话不再自动重试",
                )
                gate.onAttemptFailed(sessionId)
                _host.value = null
                onLaunchBlocked?.invoke()
                postReadyOnce("后台启动受限，点击显示", sessionId)
            }
        }
        return PresentAttempt.STARTED
    }

    /**
     * 启动全屏页面。
     *
     * PendingIntent 的创建端与发送端分别声明使用已有的后台启动资格。
     * 声明本身不会授予资格；是否放行仍取决于系统条件和宿主可见回调。
     */
    private fun launchPetActivity(source: PresentationSource, sessionId: Long?, userInitiated: Boolean): Boolean {
        val intent = PetHostIntents.forSource(context, source, sessionId, userInitiated)
        return runCatching {
            val mode = if (Build.VERSION.SDK_INT >= 36) {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            } else {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
            val creatorOptions = if (Build.VERSION.SDK_INT >= 35) {
                ActivityOptions.makeBasic()
                    .setPendingIntentCreatorBackgroundActivityStartMode(mode).toBundle()
            } else null
            val pendingIntent = PendingIntent.getActivity(
                context,
                LAUNCH_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                creatorOptions,
            )
            val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        mode,
                    )
                    .toBundle()
            } else {
                null
            }
            eventLog.log(
                EventLog.Category.PRESENTATION,
                "请求启动方式：PendingIntent 创建端声明=${creatorOptions != null} " +
                    "发送端声明=${options != null} 悬浮窗授权=${Settings.canDrawOverlays(context)} " +
                    "用户主动=$userInitiated（声明不等于系统已允许）",
            )
            pendingIntent.send(context, 0, null, null, null, null, options)
        }.onFailure { error ->
            eventLog.log(EventLog.Category.ERROR, "启动异常：${error.javaClass.simpleName} ${error.message}")
        }.isSuccess
    }

    /** DreamService 自己注册宿主；已有其它宿主时返回 false，由屏保自行结束。 */
    fun registerActivityHost(ownerToken: String, source: PresentationSource, sessionId: Long?): Boolean {
        if (!HostOwnership.canClaim(_host.value, HostKind.ACTIVITY, ownerToken)) return false
        watchdog?.cancel()
        _host.value = ActiveHost(
            HostKind.ACTIVITY, source, sessionId, false, clock.elapsedRealtimeMs(), ownerToken,
        )
        return true
    }

    fun registerDreamHost(sessionId: Long?, ownerToken: String): Boolean {
        val existing = _host.value
        if (existing != null) {
            eventLog.log(
                EventLog.Category.PRESENTATION,
                "屏保放弃展示：已有宿主 ${existing.kind}（${existing.source.label}）",
            )
            return false
        }
        _host.value = ActiveHost(
            kind = HostKind.DREAM,
            source = PresentationSource.DREAM,
            sessionId = sessionId,
            visible = true,
            startedAtMs = clock.elapsedRealtimeMs(),
            ownerToken = ownerToken,
        )
        gate.onAttemptSucceeded(sessionId)
        eventLog.log(EventLog.Category.PRESENTATION, "屏保宿主已接管：session=$sessionId")
        return true
    }

    /** 宿主自己报告已可见；这是"真正展示成功"的唯一证据。 */
    fun onHostVisible(kind: HostKind, ownerToken: String) {
        val current = _host.value
        if (current == null || !HostOwnership.owns(current, kind, ownerToken)) return
        watchdog?.cancel()
        gate.onAttemptSucceeded(current.sessionId)
        if (!current.visible) {
            _host.value = current.copy(visible = true)
        }
        eventLog.log(EventLog.Category.PRESENTATION, "宿主可见：${current.kind}（${current.source.label}）")
    }

    /** 宿主暂时不可见（被其他界面覆盖），仍保留宿主槽位。 */
    fun onHostHidden(kind: HostKind, ownerToken: String) {
        val current = _host.value
        if (current == null || !HostOwnership.owns(current, kind, ownerToken) || !current.visible) return
        _host.value = current.copy(visible = false)
        eventLog.log(EventLog.Category.PRESENTATION, "宿主暂不可见：${current.kind}（${current.source.label}）")
    }

    fun onHostFinished(kind: HostKind, ownerToken: String) {
        val current = _host.value
        if (!HostOwnership.owns(current, kind, ownerToken)) return
        watchdog?.cancel()
        gate.onHostFinished()
        notifications.cancelChargingReady()
        lastNotificationKey = null
        if (current != null) {
            _host.value = null
            eventLog.log(EventLog.Category.PRESENTATION, "宿主已结束：${current.kind}")
        }
    }

    /** 请求当前宿主退出（会话结束、用户关闭、温度或时长限制）。 */
    fun requestClose(reason: SuppressionReason) {
        if (_host.value == null) return
        eventLog.log(EventLog.Category.PRESENTATION, "请求宿主退出：${reason.label}")
        _closeRequests.tryEmit(CloseRequest(reason))
    }

    /** 用户主动"继续展示"：解除本会话的启动受限记录，允许一次受控恢复。 */
    fun onUserResumeRequested() {
        gate.onUserResume()
        lastNotificationKey = null
        eventLog.log(EventLog.Category.PRESENTATION, "用户主动恢复：允许一次受控启动")
    }

    /** 强制清空（自动化关闭时使用）。 */
    fun reset() {
        watchdog?.cancel()
        _host.value = null
        gate.reset()
        lastNotificationKey = null
        notifications.cancelChargingReady()
    }

    private fun postReadyOnce(message: String, sessionId: Long?) {
        val key = sessionId to message
        if (lastNotificationKey == key) return
        lastNotificationKey = key
        notifications.postChargingReady(message, sessionId)
    }

    private companion object {
        const val LAUNCH_REQUEST_CODE = 3001
    }
}
