package com.wirelesspet.presentation.activity

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import com.wirelesspet.presentation.useFullDisplay
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.wirelesspet.WirelessPetApplication
import com.wirelesspet.automation.AutomationService
import com.wirelesspet.platform.DeviceInfoProvider
import com.wirelesspet.policy.PresentationDecision
import com.wirelesspet.policy.PresentationPolicy
import com.wirelesspet.policy.PresentationRequest
import com.wirelesspet.presentation.SessionEntry
import com.wirelesspet.presentation.PresentationLifetime
import com.wirelesspet.presentation.SwipeUpExit
import com.wirelesspet.data.EventLog
import com.wirelesspet.pet.PetRenderer
import com.wirelesspet.policy.EnergyPlan
import com.wirelesspet.policy.EnergyPolicy
import com.wirelesspet.policy.PresentationSource
import com.wirelesspet.power.ChargingStatus
import com.wirelesspet.power.ThermalLevel
import com.wirelesspet.presentation.HostKind
import com.wirelesspet.presentation.PetHostIntents
import com.wirelesspet.session.SessionPhase
import com.wirelesspet.session.SuppressionReason
import com.wirelesspet.session.WirelessSessionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * 全屏宠物宿主（设计文档 §7.3）。
 *
 * - 沉浸式黑底，菜单默认隐藏，交互时才出现明确的退出入口；
 * - 返回、关闭按钮与上滑手势都视为"用户退出本次展示"，只影响当前会话；
 *   上滑只是在提示文案里写明的一条顺手入口，不是唯一退出口；
 * - 失去可见性只暂停渲染，回到前台继续演，不重播欢迎动画；
 * - 断连确认窗口内不关闭页面，稳定断开后才退出；
 * - 温度等级变化时实时降载，达到极高温结束展示并绑定会话抑制；
 * - 不解除安全锁屏，只选择是否显示在锁屏之上。
 */
class PetActivity : ComponentActivity() {

    private val graph get() = (application as WirelessPetApplication).graph
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var renderer: PetRenderer
    private lateinit var closeButton: TextView
    private lateinit var hintText: TextView

    private var source: PresentationSource = PresentationSource.CHARGING_SESSION
    private var sessionId: Long? = null
    private var plan: EnergyPlan? = null
    private var userInitiated = false
    private var finished = false
    private val ownerToken = UUID.randomUUID().toString()
    private var prepareJob: Job? = null
    private var monitoring = false
    private var started = false
    private var collectorsStarted = false
    private var lifetime: PresentationLifetime? = null

    private val swipeUpExit = SwipeUpExit()
    private val hideMenuRunnable = Runnable { showMenu(false) }

    private val maxDurationRunnable = Runnable {
        val currentPlan = plan ?: return@Runnable
        graph.eventLog.log(
            EventLog.Category.PRESENTATION,
            "达到最长展示时长 ${currentPlan.maxDurationMs}ms，结束展示",
        )
        suppressIfSession(SuppressionReason.MAX_DURATION)
        finishAndRelease()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        graph.acquirePowerMonitor()
        monitoring = true
        lifecycleScope.launch {
            graph.coordinator.closeRequests.collect { finishAndRelease() }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { exitByUser() }
        })
        prepareIntent(intent)
    }

    private fun prepareIntent(newIntent: Intent) {
        prepareJob?.cancel()
        prepareJob = lifecycleScope.launch {
            try {
                val prepared = withTimeoutOrNull(5_000L) { prepare(newIntent); true }
                if (prepared == null) finishAndRelease()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                graph.eventLog.log(EventLog.Category.ERROR, "展示入口准备失败：${error.javaClass.simpleName}")
                finishAndRelease()
            }
        }
    }

    private suspend fun prepare(newIntent: Intent) {
        val previousSource = source
        val previousSessionId = sessionId
        source = PetHostIntents.sourceOf(newIntent)
        sessionId = PetHostIntents.sessionIdOf(newIntent)
        userInitiated = PetHostIntents.isUserInitiated(newIntent)
        if (!graph.coordinator.registerActivityHost(ownerToken, source, sessionId)) {
            finishAndRelease()
            return
        }
        val settings = graph.settingsRepository.settings.first()
        graph.sessionController.wiredChargingEnabled = settings.wiredChargingEnabled
        if (com.wirelesspet.BuildConfig.SPINE_ENABLED && !settings.spineLicenseAccepted) {
            finishAndRelease()
            return
        }
        // 在等待会话恢复前应用锁屏/亮屏配置，避免页面先被锁屏遮住。
        setShowWhenLocked(settings.showOnLockScreen)
        setTurnScreenOn(settings.wakeScreenWhenOff)
        graph.refreshThermal()
        if (source == PresentationSource.CHARGING_SESSION) {
            if (graph.sessionController.state.value.phase == SessionPhase.IDLE) {
                val persisted = graph.sessionStore.load()
                graph.sessionController.restore(
                    persisted, graph.powerMonitor.readSticky(), graph.now(), graph.bootCount(),
                )
            }
            val candidate = SessionEntry.forPresentation(
                graph.sessionController.state.value, sessionId, userInitiated,
            )
            val decision = candidate?.let {
                PresentationPolicy.decide(PresentationRequest(
                    source, settings, graph.powerMonitor.readSticky(), it,
                    DeviceInfoProvider.isScreenOn(this), DeviceInfoProvider.isKeyguardLocked(this),
                    graph.thermal.value, graph.localMinuteOfDay(), userInitiated,
                ))
            }
            if (decision !is PresentationDecision.Present) {
                graph.eventLog.log(EventLog.Category.PRESENTATION,
                    "入口校验未通过：session=$sessionId 实际=${graph.sessionController.state.value.sessionId} " +
                        "决策=$decision")
                finishAndRelease()
                return
            }
            if (userInitiated) {
                graph.coordinator.onUserResumeRequested()
                graph.sessionController.onResumeRequested(graph.now())
            }
            // 通知直达的冷进程也需要恢复后续会话监听和断电退出。
            if (!graph.automationRunning.value) {
                if (runCatching { AutomationService.start(this) }.isFailure) {
                    finishAndRelease()
                    return
                }
            }
        }
        if (finished) return
        val computedPlan = if (source == PresentationSource.PREVIEW && graph.thermal.value == ThermalLevel.CRITICAL) {
            EnergyPolicy.emergencyPreviewPlan(settings, graph.thermal.value)
        } else EnergyPolicy.planFor(settings, graph.thermal.value, source)
        plan = computedPlan

        configureWindow(settings.wakeScreenWhenOff, settings.showOnLockScreen, computedPlan)
        if (!::renderer.isInitialized) buildContentView()
        applyBrightness(computedPlan)

        if (lifetime == null || previousSource != source || previousSessionId != sessionId) {
            lifetime = PresentationLifetime(graph.now())
            renderer.start(computedPlan)
        } else {
            renderer.updatePlan(computedPlan)
            renderer.resume()
        }
        scheduleDuration()
        bindLabels()
        if (!collectorsStarted) {
            collectorsStarted = true
            startCollectors()
        }
        if (finished) return
        if (started) graph.coordinator.onHostVisible(HostKind.ACTIVITY, ownerToken)
        else renderer.pause()
        graph.eventLog.log(
            EventLog.Category.PRESENTATION,
            "宠物页面已创建：来源=${source.label} session=$sessionId " +
                "用户主动=$userInitiated 档位=${computedPlan.label}",
        )
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (finished || !::renderer.isInitialized) return
        graph.coordinator.onHostVisible(HostKind.ACTIVITY, ownerToken)
        // 被其他界面覆盖后回来：从原动作继续，不重播欢迎动画。
        renderer.resume()
    }

    override fun onStop() {
        super.onStop()
        started = false
        if (finished || !::renderer.isInitialized) return
        renderer.pause()
        graph.coordinator.onHostHidden(HostKind.ACTIVITY, ownerToken)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (::renderer.isInitialized) renderer.release()
        graph.coordinator.onHostFinished(HostKind.ACTIVITY, ownerToken)
        if (monitoring) {
            monitoring = false
            graph.releasePowerMonitor()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!finished) prepareIntent(intent)
    }

    /**
     * 上滑退出：与返回键、关闭按钮等价的"用户退出本次展示"。
     *
     * 只观察、不消费触摸事件：轻触互动、横向切换角色以及系统手势都保持原样。
     * 判定通过后把退出投递到下一次主线程循环，避免在本次事件分发的半途结束页面。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!finished) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> swipeUpExit.begin(ev.x, ev.y, ev.eventTime)
                // 双指及以上的手势交给系统与模型本身，不当作退出。
                MotionEvent.ACTION_POINTER_DOWN -> swipeUpExit.cancel()
                MotionEvent.ACTION_UP ->
                    if (swipeUpExit.finish(ev.x, ev.y, ev.eventTime, swipeAreaHeight())) {
                        graph.eventLog.log(
                            EventLog.Category.PRESENTATION,
                            "检测到上滑手势，按用户主动退出处理",
                        )
                        handler.post { exitByUser() }
                    }
                MotionEvent.ACTION_CANCEL -> swipeUpExit.cancel()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun swipeAreaHeight(): Int =
        window?.decorView?.height?.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

    private fun configureWindow(wakeScreen: Boolean, showOnLockScreen: Boolean, plan: EnergyPlan) {
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        setShowWhenLocked(showOnLockScreen)
        setTurnScreenOn(wakeScreen)
        setKeepScreenOn(plan.keepScreenOn)
        window.useFullDisplay()
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.BLACK
    }

    private fun setKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun applyBrightness(plan: EnergyPlan) {
        window.attributes = window.attributes.apply {
            screenBrightness = plan.brightness.coerceIn(0.01f, 1f)
        }
    }

    private fun buildContentView() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        renderer = PetRenderer(this).apply {
            clockHeaderHeightDp = 84f
            onPetTapped = {
                // 互动开关只控制角色动作，关闭入口始终可通过轻触唤出。
                showMenu(true)
            }
        }
        root.addView(
            renderer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        hintText = TextView(this).apply {
            text = "轻触互动 · 上滑退出"
            setTextColor(ContextCompat.getColor(this@PetActivity, android.R.color.darker_gray))
            textSize = 12f
            alpha = 0f
        }
        root.addView(
            hintText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = 120 },
        )

        closeButton = TextView(this).apply {
            text = "关闭"
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Color.WHITE)
            textSize = 15f
            val density = resources.displayMetrics.density
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            minimumHeight = (48 * density).toInt()
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(Color.parseColor("#33FFFFFF"))
                setStroke(2, Color.parseColor("#55FFFFFF"))
            }
            alpha = 0f
            isClickable = true
            setOnClickListener { exitByUser() }
        }
        root.addView(
            closeButton,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                topMargin = 72
                rightMargin = 40
            },
        )

        val name = TextView(this).apply {
            text = com.wirelesspet.pet.CharacterCatalog.find(graph.settings.value.selectedCharacterId).name; textSize = 14f; letterSpacing = 0.12f
            setTextColor(Color.parseColor("#C4B5FD"))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        renderer.onCharacterChanged = { name.text = it }
        val density = resources.displayMetrics.density
        root.addView(name, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            topMargin = (32 * density).toInt(); leftMargin = (24 * density).toInt()
        })
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsetsIgnoringVisibility(
                androidx.core.view.WindowInsetsCompat.Type.displayCutout() or androidx.core.view.WindowInsetsCompat.Type.systemBars())
            closeButton.layoutParams = (closeButton.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = safe.top + (24 * density).toInt()
                rightMargin = safe.right + (16 * density).toInt()
            }
            name.layoutParams = (name.layoutParams as FrameLayout.LayoutParams).apply {
                width = (((root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - safe.left - safe.right) * .5f).toInt().coerceAtLeast(0)
                topMargin = safe.top + (8 * density).toInt()
                leftMargin = safe.left + (24 * density).toInt()
            }
            hintText.layoutParams = (hintText.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = safe.bottom + (40 * density).toInt()
            }
            insets
        }
        setContentView(root)
        showMenu(true)
    }

    private fun bindLabels() {
        val power = graph.powerMonitor.current()
        if (graph.settings.value.showBatteryInfo && power != null) {
            renderer.updateBatteryLabel(power)
        } else {
            renderer.updateBatteryLabel(null)
        }
        renderer.showStatusLabel(buildStatusText(power?.describe() ?: "电源未知"))
    }

    private fun buildStatusText(powerText: String): String {
        val session = graph.sessionController.state.value
        return buildString {
            appendLine("角色显示")
            appendLine("来源：${source.label}${if (userInitiated) "（用户主动）" else ""}")
            appendLine("电源：$powerText")
            appendLine("会话：${session.describe()}")
            appendLine("节能：${plan?.label ?: "-"} · 温度：${graph.thermal.value}")
            append("自动化：立即模式")
        }
    }

    private fun startCollectors() {
        lifecycleScope.launch {
            graph.sessionController.state.collect { state -> onSessionChanged(state) }
        }
        lifecycleScope.launch {
            graph.powerMonitor.snapshot.collect { snapshot ->
                if (snapshot == null) return@collect
                if (graph.settings.value.showBatteryInfo) {
                    renderer.updateBatteryLabel(snapshot)
                }
                renderer.showStatusLabel(buildStatusText(snapshot.describe()))
                if (snapshot.status == ChargingStatus.FULL) {
                    if (graph.settings.value.fullChargeExit &&
                        source == PresentationSource.CHARGING_SESSION
                    ) {
                        // 充满退出必须绑定会话抑制，否则下一次电池广播又会把页面拉起来。
                        suppressIfSession(SuppressionReason.FULL_CHARGE)
                        finishAndRelease()
                    } else {
                        renderer.notifyFullyCharged()
                    }
                }
            }
        }
        lifecycleScope.launch {
            graph.thermal.collect { level -> onThermalChanged(level) }
        }
    }

    /**
     * 会话状态变化。
     *
     * 关键点：断连确认窗口（CONFIRMING_DISCONNECT）里**不关页面**，
     * 只放弃"持续常亮"，等稳定断开或会话结束命令再退出，
     * 避免线圈抖动导致页面闪断重开、重播欢迎动画。
     */
    private fun onSessionChanged(state: WirelessSessionState) {
        if (source != PresentationSource.CHARGING_SESSION) return
        if (sessionId != null && state.sessionId != null && state.sessionId != sessionId) {
            graph.eventLog.log(EventLog.Category.PRESENTATION, "会话已切换，关闭旧页面")
            finishAndRelease()
            return
        }
        if (!state.retainsHost(sessionId)) {
            finishAndRelease()
            return
        }
        when (state.phase) {
            SessionPhase.IDLE -> finishAndRelease()

            SessionPhase.CONFIRMING_DISCONNECT -> {
                // 电源暂时消失：保留页面与动作，只放弃持续亮屏。
                setKeepScreenOn(false)
                renderer.showStatusLabel(buildStatusText(graph.powerMonitor.current()?.describe() ?: "电源未知"))
            }

            else -> {
                if (plan?.keepScreenOn == true) setKeepScreenOn(true)
                renderer.showStatusLabel(buildStatusText(graph.powerMonitor.current()?.describe() ?: "电源未知"))
            }
        }
    }

    /** 温度等级变化：实时降载；极高温下自动展示结束，用户主动预览改为限时静态。 */
    private fun onThermalChanged(level: ThermalLevel) {
        if (finished) return
        if (level == ThermalLevel.CRITICAL) {
            if (source == PresentationSource.PREVIEW) {
                // 预览是用户自己点的：给一张低亮度静态图，最长 60 秒，而不是"点了没反应"。
                applyPreviewEmergencyPlan(level)
                return
            }
            graph.eventLog.log(
                EventLog.Category.PRESENTATION,
                "温度达到极高温，结束本次展示",
            )
            suppressIfSession(SuppressionReason.THERMAL_END)
            finishAndRelease()
            return
        }
        val newPlan = EnergyPolicy.planFor(graph.settings.value, level, source)
        if (newPlan == plan) return
        applyPlan(newPlan)
        graph.eventLog.log(
            EventLog.Category.PRESENTATION,
            "温度等级=$level，展示方案更新为：${newPlan.label}",
        )
    }

    private fun applyPreviewEmergencyPlan(level: ThermalLevel) {
        val emergency = EnergyPolicy.emergencyPreviewPlan(graph.settings.value, level)
        applyPlan(emergency)
        graph.eventLog.log(
            EventLog.Category.PRESENTATION,
            "极高温下预览改为限时静态：${emergency.maxDurationMs / 1000} 秒、亮度 ${emergency.brightness}",
        )
    }

    private fun applyPlan(newPlan: EnergyPlan) {
        plan = newPlan
        applyBrightness(newPlan)
        setKeepScreenOn(newPlan.keepScreenOn &&
            (source == PresentationSource.PREVIEW || graph.sessionController.state.value.phase == SessionPhase.ACTIVE))
        renderer.updatePlan(newPlan)
        scheduleDuration()
        renderer.showStatusLabel(buildStatusText(graph.powerMonitor.current()?.describe() ?: "电源未知"))
    }

    private fun scheduleDuration() {
        handler.removeCallbacks(maxDurationRunnable)
        val remaining = lifetime?.remainingMs(plan?.maxDurationMs ?: 0L, graph.now()) ?: return
        handler.postDelayed(maxDurationRunnable, remaining)
    }

    /** 只有真实会话中的页面才有资格写会话级抑制；预览不写。 */
    private fun suppressIfSession(reason: SuppressionReason) {
        if (source != PresentationSource.CHARGING_SESSION) return
        if (!reason.bindsToSession) return
        graph.sessionController.suppressForSession(reason, graph.now())
    }

    private fun showMenu(show: Boolean) {
        closeButton.animate().alpha(if (show) 1f else 0f)
            .setDuration(if (show) 120L else 260L).start()
        hintText.animate().alpha(if (show) 0.8f else 0f)
            .setDuration(if (show) 120L else 260L).start()
        handler.removeCallbacks(hideMenuRunnable)
        if (show) handler.postDelayed(hideMenuRunnable, 4_000L)
    }

    /** 用户主动退出：会话级抑制 + 结束页面，本次充电期间不再弹出。 */
    private fun exitByUser() {
        if (finished) return
        if (source == PresentationSource.CHARGING_SESSION) {
            graph.sessionController.onUserDismiss(graph.now())
            graph.eventLog.log(EventLog.Category.SESSION, "用户手动退出本次展示")
        } else {
            graph.eventLog.log(EventLog.Category.PRESENTATION, "用户退出预览")
        }
        finishAndRelease()
    }

    private fun finishAndRelease() {
        if (finished) return
        finished = true
        prepareJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        if (::renderer.isInitialized) renderer.stop()
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        finish()
        if (android.os.Build.VERSION.SDK_INT < 34) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
