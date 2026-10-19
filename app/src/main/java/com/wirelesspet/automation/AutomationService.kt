package com.wirelesspet.automation

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wirelesspet.WirelessPetApplication
import com.wirelesspet.data.AutomationMode
import com.wirelesspet.data.EventLog
import com.wirelesspet.platform.DeviceInfoProvider
import com.wirelesspet.policy.PresentationDecision
import com.wirelesspet.policy.PresentationPolicy
import com.wirelesspet.policy.PresentationRequest
import com.wirelesspet.policy.PresentationSource
import com.wirelesspet.presentation.PresentAttempt
import com.wirelesspet.session.SessionCommand
import com.wirelesspet.session.SessionUpdate
import com.wirelesspet.session.SuppressionReason
import com.wirelesspet.session.WirelessSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 立即展示模式的事件监听服务（设计文档 §4.1、§11）。
 *
 * - 由用户操作开启，前台服务类型为 specialUse，通知里始终显示运行状态与手动入口；
 * - 会话推进交给共享的 [SessionDriver]，本类只负责"是否展示、展示失败怎么办"；
 * - 不循环重试、不强制亮屏：启动失败按会话记账，后续事件不再尝试；
 * - 展示中用户息屏会被绑定为会话级抑制，不会再次自动点亮或拉起。
 */
class AutomationService : Service() {

    private val graph get() = (application as WirelessPetApplication).graph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var driver: SessionDriver
    private var settingsJob: Job? = null
    private var sessionJob: Job? = null
    private var hostJob: Job? = null
    private var screenRegistered = false
    private var lastDecisionSignature = ""
    private var createdAtElapsedMs = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            graph.eventLog.log(EventLog.Category.POWER, "屏幕事件：${intent.action}")
            // Cached battery broadcasts can lag while the device is idle.
            graph.powerMonitor.refresh()
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                onScreenOff()
            }
            evaluate("屏幕事件 ${intent.action}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        graph.eventLog.log(EventLog.Category.SERVICE, "充电监听服务已创建")
        createdAtElapsedMs = graph.now()
        graph.notifications.ensureChannels()
        graph.automationRunning.value = true
        startInForeground(buildStatus(null, graph.sessionController.state.value))

        registerScreenReceiver()

        driver = SessionDriver(graph, scope) { update -> onSessionUpdate(update) }
        settingsJob = scope.launch { collectSettings() }
        // 会话状态变化（尤其是防抖窗口结束后建立会话）也要重新评估展示策略，
        // 否则"确认连接"阶段结束时会话已建立却没有人去请求展示。
        sessionJob = scope.launch {
            graph.sessionController.state.collect { state ->
                graph.notifications.updateStatus(buildStatus(null, state))
                evaluate("会话状态 ${state.phase.name}")
            }
        }
        hostJob = scope.launch {
            var previous: com.wirelesspet.presentation.ActiveHost? = null
            graph.coordinator.host.collect { host ->
                // A removed task can destroy the page without ending the charging session.
                // Recheck immediately instead of waiting minutes for another battery broadcast.
                val releasedAutomaticHost = host == null && previous?.source == PresentationSource.CHARGING_SESSION
                previous = host
                if (releasedAutomaticHost) evaluate("自动展示页面已释放")
            }
        }
        scope.launch {
            driver.start()
            evaluate("服务启动")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        graph.eventLog.log(EventLog.Category.SERVICE,
            "监听启动：${intent?.action ?: "系统粘性重启"} startId=$startId")
        when (intent?.action) {
            ACTION_STOP -> {
                graph.eventLog.log(EventLog.Category.SERVICE, "通知栏请求关闭自动化")
                scope.launch {
                    graph.settingsRepository.setAutomationEnabled(false)
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_RESUME -> resumeByUser()

            else -> evaluate("服务启动")
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        graph.eventLog.log(EventLog.Category.SERVICE, "最近任务已移除，充电监听服务仍运行")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        graph.eventLog.log(EventLog.Category.SERVICE, "监听服务已停止")
        graph.automationRunning.value = false
        scope.cancel()
        unregisterScreenReceiver()
        if (::driver.isInitialized) driver.stop()
        graph.coordinator.requestClose(SuppressionReason.AUTOMATION_OFF)
        graph.sessionController.reset(graph.now())
        graph.notifications.cancelStatus()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground(state: AutomationNotifications.StatusState) {
        val type = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            AutomationNotifications.NOTIFICATION_STATUS,
            graph.notifications.statusNotification(state),
            type,
        )
    }

    private suspend fun collectSettings() {
        var sawEnabled = false
        graph.settingsRepository.settings.collect { settings ->
            when {
                com.wirelesspet.BuildConfig.SPINE_ENABLED && !settings.spineLicenseAccepted -> stopSelf()
                settings.automationEnabled && settings.automationMode == AutomationMode.IMMEDIATE -> {
                    val firstEmission = !sawEnabled
                    sawEnabled = true
                    graph.notifications.updateStatus(buildStatus(null, graph.sessionController.state.value))
                    // 用户改了设置（例如刚允许打断/允许亮屏）后应立即重新评估。
                    if (!firstEmission) evaluate("设置变化")
                }

                settings.automationEnabled -> {
                    graph.eventLog.log(EventLog.Category.SERVICE, "已切换为屏保模式，停止监听服务")
                    stopSelf()
                }

                else -> {
                    // 首帧可能早于设置写入完成，给 2 秒宽限，避免服务刚启动就被旧配置关掉。
                    val withinGrace = graph.now() - createdAtElapsedMs < 2_000L
                    if (sawEnabled || !withinGrace) {
                        graph.eventLog.log(EventLog.Category.SERVICE, "自动化已关闭，停止监听服务")
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun onSessionUpdate(update: SessionUpdate) {
        applyCommands(update)
        evaluate("会话更新 ${update.state.phase.name}")
    }

    private fun applyCommands(update: SessionUpdate) {
        update.commands.forEach { command ->
            when (command) {
                is SessionCommand.StartPresentation -> {
                    graph.eventLog.log(
                        EventLog.Category.SESSION,
                        "无线会话建立 session=${command.sessionId}",
                    )
                }

                is SessionCommand.StopPresentation -> {
                    graph.eventLog.log(
                        EventLog.Category.SESSION,
                        "结束展示 session=${command.sessionId} 原因=${command.reason.label}",
                    )
                    graph.coordinator.requestClose(command.reason)
                    graph.notifications.cancelChargingReady()
                }
            }
        }
    }

    /**
     * 用户主动"继续展示"。
     *
     * 与自动触发分开处理：清除会话抑制、解除本会话的启动受限记录，
     * 然后按"用户主动"重新评估一次（不再受"允许打断其他应用"限制）。
     */
    private fun resumeByUser() {
        graph.coordinator.onUserResumeRequested()
        val update = graph.sessionController.onResumeRequested(graph.now())
        applyCommands(update)
        evaluate("用户主动继续展示", userInitiated = true)
    }

    /**
     * 展示期间用户息屏。
     *
     * 平台事件无法区分"用户按电源键"与"系统自动超时"，这里采用可解释的保守策略：
     * 只要展示进行中收到了息屏，就绑定本次会话抑制，之后不会再自动点亮屏幕或重新拉起；
     * 用户仍然可以手动"继续展示"。
     */
    private fun onScreenOff() {
        val state = graph.sessionController.state.value
        if (!state.isActiveSession) return
        // 只对"无线充电会话"的展示生效：用户自己开的预览被息屏不算本次充电的退出意图。
        if (graph.coordinator.host.value?.source != PresentationSource.CHARGING_SESSION) return
        if (state.suppression == SuppressionReason.USER_SCREEN_OFF) return
        graph.sessionController.suppressForSession(SuppressionReason.USER_SCREEN_OFF, graph.now())
        graph.eventLog.log(
            EventLog.Category.SESSION,
            "展示中收到息屏事件，按保守策略绑定本次会话抑制（不再自动点亮/拉起）",
        )
    }

    /** 重新评估一次展示策略；幂等，重复调用不会重复创建页面。 */
    private fun evaluate(reason: String, userInitiated: Boolean = false) {
        val settings = graph.settings.value
        if (com.wirelesspet.BuildConfig.SPINE_ENABLED && !settings.spineLicenseAccepted) return
        if (!settings.automationEnabled || settings.automationMode != AutomationMode.IMMEDIATE) {
            return
        }
        val power = graph.powerMonitor.current()
        val session = graph.sessionController.state.value
        val decision = PresentationPolicy.decide(
            PresentationRequest(
                source = PresentationSource.CHARGING_SESSION,
                settings = settings,
                power = power,
                session = session,
                screenOn = DeviceInfoProvider.isScreenOn(this),
                keyguardLocked = DeviceInfoProvider.isKeyguardLocked(this),
                thermal = graph.thermal.value,
                localMinuteOfDay = graph.localMinuteOfDay(),
                userInitiated = userInitiated,
            ),
        )
        handleDecision(decision, reason, userInitiated)
        graph.notifications.updateStatus(buildStatus(decision, session))
    }

    private fun handleDecision(decision: PresentationDecision, reason: String, userInitiated: Boolean) {
        when (decision) {
            is PresentationDecision.Present -> {
                val session = graph.sessionController.state.value
                val attempt = graph.coordinator.tryPresent(
                    source = PresentationSource.CHARGING_SESSION,
                    sessionId = session.sessionId,
                    userInitiated = userInitiated,
                )
                when (attempt) {
                    PresentAttempt.STARTED -> {
                        // 页面已经起来，就不再需要"点我查看"的通知；
                        // 用户主动恢复时，状态机先解除抑制会短暂走一次 Notify 分支。
                        graph.notifications.cancelChargingReady()
                        logDecision("展示", "触发来源：$reason", decision)
                    }

                    PresentAttempt.ALREADY_ACTIVE -> graph.notifications.cancelChargingReady()

                    PresentAttempt.BLOCKED, PresentAttempt.FAILED -> {
                        // 本会话不再自动尝试，靠用户手动继续或新会话恢复。
                        graph.sessionController.suppressForSession(
                            SuppressionReason.LAUNCH_BLOCKED,
                            graph.now(),
                        )
                        logDecision(
                            "启动受限",
                            "后台启动被拦截，本次会话不再自动重试（$reason）",
                            decision,
                        )
                    }
                }
            }

            is PresentationDecision.Notify -> {
                if (!graph.notifications.isUsable()) {
                    logDecision(
                        "通知不可用",
                        "无线充电中，但系统已关闭本应用通知，本次没有任何提示",
                        decision,
                    )
                }
                graph.notifications.postChargingReady(
                    decision.message,
                    graph.sessionController.state.value.sessionId,
                )
                logDecision(decision.reason.label, decision.message, decision)
            }

            is PresentationDecision.Skip -> {
                if (decision.reason == SuppressionReason.LAUNCH_BLOCKED ||
                    decision.reason == SuppressionReason.RECOVERY_CONSERVATIVE) {
                    val message = if (decision.reason == SuppressionReason.RECOVERY_CONSERVATIVE)
                        "监听已恢复，点击继续显示" else "后台启动受限，点按继续展示"
                    graph.notifications.postChargingReady(message, graph.sessionController.state.value.sessionId)
                } else graph.notifications.cancelChargingReady()
                logDecision(decision.reason.label, decision.message, decision)
            }
        }
    }

    private fun logDecision(tag: String, message: String, decision: PresentationDecision) {
        val session = graph.sessionController.state.value
        val signature = "${session.sessionId}|$tag|$message|${decision.javaClass.simpleName}"
        if (signature == lastDecisionSignature) return
        lastDecisionSignature = signature
        graph.eventLog.log(EventLog.Category.POLICY,
            "$tag：$message；session=${session.sessionId} 抑制=${session.suppression} " +
                "亮屏=${DeviceInfoProvider.isScreenOn(this)} 锁屏=${DeviceInfoProvider.isKeyguardLocked(this)}")
    }

    private fun buildStatus(
        decision: PresentationDecision?,
        session: WirelessSessionState,
    ): AutomationNotifications.StatusState {
        val power = graph.powerMonitor.current()
        val title = "ChargeMate · 充电自动显示已开启"
        val text = buildString {
            append(if (session.isActiveSession) power?.type?.label ?: "正在充电"
                else if (graph.settings.value.wiredChargingEnabled) "等待无线或有线充电" else "等待无线充电")
            append(" · ")
            append(session.describe())
            if (session.isSuppressed) {
                append(" · ")
                append(session.suppression.label)
            }
        }
        val detail = buildString {
            appendLine("模式：充电时自动显示")
            appendLine("节能：${graph.settings.value.energyProfile.label} · 温度：${graph.thermal.value}")
            append("最近决策：")
            append(
                when (decision) {
                    is PresentationDecision.Present -> "展示中"
                    is PresentationDecision.Notify -> decision.message
                    is PresentationDecision.Skip -> decision.reason.label
                    null -> "等待连接充电器"
                },
            )
        }
        return AutomationNotifications.StatusState(
            title = title,
            text = text,
            detail = detail,
            canResume = session.isActiveSession && session.isSuppressed,
            showPreview = true,
            source = PresentationSource.CHARGING_SESSION,
        )
    }

    private fun registerScreenReceiver() {
        if (screenRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                screenReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            screenRegistered = true
        }
    }

    private fun unregisterScreenReceiver() {
        if (!screenRegistered) return
        runCatching { unregisterReceiver(screenReceiver) }
        screenRegistered = false
    }

    companion object {
        const val ACTION_START = "com.wirelesspet.action.START"
        const val ACTION_STOP = "com.wirelesspet.action.STOP"
        const val ACTION_RESUME = "com.wirelesspet.action.RESUME"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AutomationService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AutomationService::class.java))
        }

        fun resume(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AutomationService::class.java).setAction(ACTION_RESUME),
                )
            }
        }
    }
}
