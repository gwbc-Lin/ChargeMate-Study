package com.wirelesspet.policy

import com.wirelesspet.data.EnergyProfile
import com.wirelesspet.data.UserSettings
import com.wirelesspet.power.ThermalLevel

/**
 * 一次展示的能耗方案：窗口亮度、动画帧率、静止节奏、最长时长与是否保持屏幕常亮。
 *
 * 亮度是"当前窗口覆盖值"，退出后由系统恢复，绝不修改全局亮度（设计文档 §9.1）。
 */
data class EnergyPlan(
    val brightness: Float,
    val activeFps: Int,
    val idleFps: Int,
    val idleAfterMs: Long,
    val sleepAfterMs: Long,
    val maxDurationMs: Long,
    val keepScreenOn: Boolean,
    val driftPxPerMinute: Float,
    val staticOnly: Boolean,
    val label: String,
) {
    val frameDelayMs: Long get() = if (activeFps <= 0) Long.MAX_VALUE else (1000L / activeFps)

    fun frameDelayFor(idle: Boolean): Long {
        val fps = if (idle) idleFps else activeFps
        return if (fps <= 0) Long.MAX_VALUE else 1000L / fps
    }
}

/**
 * 节能与温度策略（设计文档 §9）。
 *
 * 档位语义来自文档：省电优先=欢迎后长静止并允许正常息屏；持续陪伴=低亮度持续显示；
 * 流畅展示=持续高帧率。正常展示不设置时长上限。
 */
object EnergyPolicy {

    fun planFor(
        settings: UserSettings,
        thermal: ThermalLevel,
        source: PresentationSource,
    ): EnergyPlan {
        val base = when (settings.energyProfile) {
            EnergyProfile.BATTERY_SAVER -> EnergyPlan(
                brightness = 0.12f,
                activeFps = 24,
                idleFps = 15,
                idleAfterMs = 6_000L,
                sleepAfterMs = 25_000L,
                maxDurationMs = 0L,
                keepScreenOn = false,
                driftPxPerMinute = 6f,
                staticOnly = false,
                label = "省电优先",
            )

            EnergyProfile.COMPANION -> EnergyPlan(
                brightness = 0.28f,
                activeFps = 30,
                idleFps = 24,
                idleAfterMs = 12_000L,
                sleepAfterMs = 90_000L,
                maxDurationMs = 0L,
                keepScreenOn = true,
                driftPxPerMinute = 3f,
                staticOnly = false,
                label = "持续显示",
            )

            EnergyProfile.SMOOTH_PREVIEW -> EnergyPlan(
                brightness = 0.55f,
                activeFps = 30,
                idleFps = 30,
                idleAfterMs = 20_000L,
                sleepAfterMs = 120_000L,
                maxDurationMs = 0L,
                keepScreenOn = true,
                driftPxPerMinute = 2f,
                staticOnly = false,
                label = "流畅展示",
            )
        }

        // 用户主动预览提高帧率，不额外施加时长上限。
        val withSource = if (source == PresentationSource.PREVIEW) {
            base.copy(activeFps = 30, idleFps = 30, keepScreenOn = true)
        } else {
            base
        }

        // 预览是"用户自己要看的几秒钟动画"：严重升温时只按中度降载处理，
        // 不能因为耗电策略把它变成一张静止图（那等于什么都看不到）。
        // 极高温由宿主改用 emergencyPreviewPlan()：低亮度静态 + 60 秒上限。
        val effectiveThermal = if (source == PresentationSource.PREVIEW &&
            thermal == ThermalLevel.SEVERE
        ) {
            ThermalLevel.MODERATE
        } else {
            thermal
        }

        return degrade(withSource, effectiveThermal)
    }

    /**
     * 极高温下的预览方案：用户明确要看，就给一张低亮度静态图，最长 60 秒。
     *
     * 自动展示在极高温时仍然完全不做（见 PresentationPolicy），
     * 这里只服务于用户主动打开的预览。
     */
    fun emergencyPreviewPlan(settings: UserSettings, thermal: ThermalLevel): EnergyPlan {
        val base = planFor(settings, thermal, PresentationSource.PREVIEW)
        return base.copy(
            brightness = 0.12f,
            activeFps = 1,
            idleFps = 0,
            staticOnly = true,
            keepScreenOn = false,
            maxDurationMs = 60_000L,
            label = base.label + " · 极高温限时预览",
        )
    }

    /** 温度降载：帧率与亮度下调，静止时间前移；较高级别只留静态画面。 */
    fun degrade(plan: EnergyPlan, thermal: ThermalLevel): EnergyPlan = when (thermal) {
        ThermalLevel.NONE -> plan
        ThermalLevel.LIGHT -> plan.copy(
            activeFps = (plan.activeFps * 3 / 4).coerceAtLeast(8),
            idleFps = (plan.idleFps * 3 / 4).coerceAtLeast(4),
            brightness = plan.brightness * 0.85f,
            label = plan.label + " · 升温降载",
        )

        ThermalLevel.MODERATE -> plan.copy(
            activeFps = (plan.activeFps / 2).coerceAtLeast(6),
            idleFps = (plan.idleFps / 2).coerceAtLeast(3),
            brightness = plan.brightness * 0.65f,
            idleAfterMs = plan.idleAfterMs / 2,
            sleepAfterMs = plan.sleepAfterMs / 2,
            label = plan.label + " · 温度降载",
        )

        ThermalLevel.SEVERE -> plan.copy(
            activeFps = 1,
            idleFps = 0,
            brightness = plan.brightness * 0.5f,
            idleAfterMs = 0L,
            sleepAfterMs = 2_000L,
            keepScreenOn = false,
            staticOnly = true,
            label = plan.label + " · 仅静态展示",
        )

        ThermalLevel.CRITICAL -> plan.copy(
            activeFps = 0,
            idleFps = 0,
            brightness = 0.05f,
            keepScreenOn = false,
            staticOnly = true,
            maxDurationMs = 0L,
            label = plan.label + " · 温度过高",
        )
    }

}
