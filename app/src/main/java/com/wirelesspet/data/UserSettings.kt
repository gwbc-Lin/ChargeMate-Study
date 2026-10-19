package com.wirelesspet.data

import com.wirelesspet.pet.CharacterCatalog

/** 自动化入口。两种模式共用同一个会话控制器，互斥展示。 */
enum class AutomationMode(val label: String, val detail: String) {
    IMMEDIATE("立即展示", "用户开启前台监听服务，放上无线充电器后尽快打开展示页面"),
    DREAM("闲置屏保", "由系统在充电闲置时启动屏保，触发时间由系统决定"),
}

/** 节能档位（见设计文档 §9.1）。 */
enum class EnergyProfile(val label: String) {
    BATTERY_SAVER("省电优先"),
    COMPANION("持续显示"),
    SMOOTH_PREVIEW("流畅展示"),
}

/**
 * 用户配置。默认值遵循设计文档的"暂定默认"：纯本地、无线专用、节能优先、
 * 用户退出后本次不再弹出；不改写系统全局亮度与充电策略。
 */
enum class LandscapeLayout(val label: String, val cssName: String) {
    CENTERED("居中显示", "centered"),
    BUST_SPLIT("半身分栏", "bust-split"),
}

data class UserSettings(
    val spineLicenseAccepted: Boolean = false,
    val selectedCharacterId: String = CharacterCatalog.DEFAULT_ID,
    val clockStyle: ClockStyle = ClockStyle.AURORA,
    val landscapeLayout: LandscapeLayout = LandscapeLayout.CENTERED,
    val automationEnabled: Boolean = false,
    val wiredChargingEnabled: Boolean = false,
    val automationMode: AutomationMode = AutomationMode.IMMEDIATE,
    val allowInterruptOtherApps: Boolean = true,
    val wakeScreenWhenOff: Boolean = true,
    val showOnLockScreen: Boolean = true,
    val showBatteryInfo: Boolean = true,
    val interactionEnabled: Boolean = true,
    val audioSpectrumEnabled: Boolean = false,
    val energyProfile: EnergyProfile = EnergyProfile.BATTERY_SAVER,
    val fullChargeExit: Boolean = false,
    val quietHoursEnabled: Boolean = false,
    val quietStartMinuteOfDay: Int = 23 * 60,
    val quietEndMinuteOfDay: Int = 7 * 60,
    val onboardingCompleted: Boolean = false,
) {
    companion object {
        val DEFAULT = UserSettings()
    }
}
