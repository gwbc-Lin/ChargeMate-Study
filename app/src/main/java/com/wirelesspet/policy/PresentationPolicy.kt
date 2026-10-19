package com.wirelesspet.policy

import com.wirelesspet.data.AutomationMode
import com.wirelesspet.data.UserSettings
import com.wirelesspet.power.PowerSnapshot
import com.wirelesspet.power.PowerType
import com.wirelesspet.power.ThermalLevel
import com.wirelesspet.session.SessionPhase
import com.wirelesspet.session.SuppressionReason
import com.wirelesspet.session.WirelessSessionState

/** 展示请求来源。三种来源共用同一套策略与同一个宿主协调器。 */
enum class PresentationSource(val label: String) {
    CHARGING_SESSION("无线充电会话"),
    DREAM("系统屏保"),
    PREVIEW("手动预览"),
}

/** 策略输入：全部是普通数据类型，便于构造覆盖用例的单元测试。 */
data class PresentationRequest(
    val source: PresentationSource,
    val settings: UserSettings,
    val power: PowerSnapshot?,
    val session: WirelessSessionState,
    val screenOn: Boolean,
    val keyguardLocked: Boolean,
    val thermal: ThermalLevel,
    val localMinuteOfDay: Int,
    /**
     * 是否由用户主动发起（点"继续展示"、点通知）。
     *
     * 用户主动请求不受"自动打断其他应用"偏好限制：那个偏好约束的是"自动弹出"，
     * 不应该是用户自己点了之后还被降级成通知。但真实电源、会话有效性与温度仍然检查。
     */
    val userInitiated: Boolean = false,
)

/** 策略输出。UI 与日志直接使用 [reason] 与 [message] 解释结果。 */
sealed interface PresentationDecision {
    data class Present(val source: PresentationSource) : PresentationDecision

    /** 允许展示但不打断用户：保留会话、发出可点击通知。 */
    data class Notify(val reason: SuppressionReason, val message: String) : PresentationDecision

    /** 本次不展示，也不改变会话抑制状态（会话级抑制由状态机自己绑定）。 */
    data class Skip(val reason: SuppressionReason, val message: String) : PresentationDecision
}

/**
 * 展示策略（设计文档 §5、§6.2）。
 *
 * 只做判断：不启动页面、不改设置、不写会话状态。
 */
object PresentationPolicy {

    fun decide(request: PresentationRequest): PresentationDecision {
        val settings = request.settings

        // 预览是用户明确要看的一次短展示（最长 2 分钟、可随时关闭），
        // 不能被自动展示的节能/温控规则挡掉，否则"点了没反应"。
        // 极高温时由宿主换成低亮度静态图 + 60 秒上限（EnergyPolicy.emergencyPreviewPlan）。
        if (request.source == PresentationSource.PREVIEW) {
            return PresentationDecision.Present(request.source)
        }

        // 温度保护优先于其他自动化判断：极高温下不自动展示。
        if (request.thermal == ThermalLevel.CRITICAL) {
            return PresentationDecision.Skip(
                SuppressionReason.THERMAL,
                "电池温度过高，暂停自动展示以降低风险",
            )
        }

        if (!settings.automationEnabled) {
            return PresentationDecision.Skip(
                SuppressionReason.AUTOMATION_OFF,
                "自动显示已关闭",
            )
        }

        val power = request.power
        if (power == null || power.type == PowerType.UNKNOWN) {
            return PresentationDecision.Skip(
                SuppressionReason.UNKNOWN_POWER,
                "电源类型未知，按保守策略不自动展示",
            )
        }

        if (!power.isTriggerSource(settings.wiredChargingEnabled)) {
            return PresentationDecision.Skip(
                SuppressionReason.NOT_WIRELESS,
                "当前是${power.type.label}，该电源未启用自动显示",
            )
        }

        if (settings.quietHoursEnabled && isWithinQuietHours(
                minuteOfDay = request.localMinuteOfDay,
                startMinute = settings.quietStartMinuteOfDay,
                endMinute = settings.quietEndMinuteOfDay,
            )
        ) {
            return PresentationDecision.Skip(
                SuppressionReason.TIME_WINDOW,
                "当前处于设定的不展示时段",
            )
        }

        if (request.source == PresentationSource.DREAM) {
            if (settings.automationMode != AutomationMode.DREAM) {
                return PresentationDecision.Skip(
                    SuppressionReason.AUTOMATION_OFF,
                    "未选择闲置屏保模式",
                )
            }
            // 屏保由系统发起，屏幕已经是亮的，不再检查亮屏相关设置。
            if (!request.session.allowsPresentation) {
                val reason = request.session.suppression
                    .takeIf { it != SuppressionReason.NONE }
                    ?: SuppressionReason.SESSION_INACTIVE
                return PresentationDecision.Skip(reason, "会话未允许展示：${reason.label}")
            }
            return PresentationDecision.Present(request.source)
        }

        // 以下是无线充电会话触发的展示判断。
        if (settings.automationMode != AutomationMode.IMMEDIATE) {
            return PresentationDecision.Skip(SuppressionReason.AUTOMATION_OFF, "未选择立即展示模式")
        }
        val session = request.session
        if (session.phase != SessionPhase.ACTIVE || session.sessionId == null) {
            return PresentationDecision.Skip(
                SuppressionReason.SESSION_INACTIVE,
                "当前没有生效中的无线充电会话",
            )
        }

        if (session.suppression != SuppressionReason.NONE) {
            return PresentationDecision.Skip(
                session.suppression,
                "本次会话已抑制展示：${session.suppression.label}",
            )
        }

        // 严重升温不再直接"什么都不做"，而是交给宿主按"仅静态展示"的方案运行
        // （设计文档 §9.2 的"较高：仅静态展示或结束展示"，这里选择静态）。
        // 只有极高温（上方 CRITICAL 分支）才完全不自动展示。

        if (request.keyguardLocked && !settings.showOnLockScreen) {
            return PresentationDecision.Skip(
                SuppressionReason.LOCKED,
                "锁屏展示未开启，未在锁屏上弹出",
            )
        }

        if (!request.screenOn && !settings.wakeScreenWhenOff) {
            return PresentationDecision.Skip(
                SuppressionReason.SCREEN_OFF,
                "屏幕已熄灭，按设置不主动亮屏",
            )
        }

        // 屏幕亮着且用户不允许打断：保留会话，改用可点击通知，不抢占前台。
        // 用户主动点"继续展示"时不适用这一条：那是用户的明确意图，不是自动打断。
        // 普通应用在不申请"使用情况访问"时无法知道前台是哪个应用，
        // 因此这里采用可解释的规则，而不是猜测。
        if (!request.userInitiated && request.screenOn && !settings.allowInterruptOtherApps) {
            return PresentationDecision.Notify(
                SuppressionReason.INTERRUPTION_NOT_ALLOWED,
                "无线充电中，点击显示",
            )
        }

        return PresentationDecision.Present(request.source)
    }

    /**
     * 是否位于不展示时段。支持跨零点（例如 23:00 - 07:00）；起止相同视为未启用。
     */
    fun isWithinQuietHours(minuteOfDay: Int, startMinute: Int, endMinute: Int): Boolean {
        if (startMinute == endMinute) return false
        val minute = ((minuteOfDay % 1440) + 1440) % 1440
        return if (startMinute < endMinute) {
            minute in startMinute until endMinute
        } else {
            minute >= startMinute || minute < endMinute
        }
    }
}
