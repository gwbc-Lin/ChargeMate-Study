package com.wirelesspet.power

/**
 * 标准化后的电源类型。
 *
 * [UNKNOWN] 与 [NONE] 是两种不同的情况：前者表示系统没有给出可识别的电源信息（例如读取失败），
 * 后者表示明确没有外接电源。两者都不允许触发自动展示。
 */
enum class PowerType(val label: String) {
    NONE("未连接电源"),
    AC("有线充电器"),
    USB("USB 供电"),
    WIRELESS("无线充电器"),
    DOCK("底座供电"),
    UNKNOWN("电源类型未知"),
}

enum class ChargingStatus(val label: String) {
    UNKNOWN("未知"),
    CHARGING("正在充电"),
    DISCHARGING("放电中"),
    FULL("已充满"),
    NOT_CHARGING("暂停充电"),
}

/**
 * 系统热状态。用于在电池温度之外叠加平台热降级信号。
 */
enum class ThermalLevel {
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
}

/**
 * 一次电池状态的标准化快照。
 *
 * 注意：连接无线电源不等于电池正在增加电量（满电、充电保护暂停都会出现
 * "无线电源在但 STATE 不是 CHARGING"），所以 [isWireless] 与 [status] 分开判断。
 */
data class PowerSnapshot(
    val type: PowerType = PowerType.UNKNOWN,
    val pluggedBitmask: Int = 0,
    val levelPercent: Int = -1,
    val status: ChargingStatus = ChargingStatus.UNKNOWN,
    val temperatureCelsius: Float = Float.NaN,
    val thermalLevel: ThermalLevel = ThermalLevel.NONE,
    val observedAtElapsedMs: Long = 0L,
) {
    val isWireless: Boolean get() = type == PowerType.WIRELESS

    fun isTriggerSource(includeWired: Boolean): Boolean = isWireless ||
        includeWired && type in setOf(PowerType.AC, PowerType.USB, PowerType.DOCK)

    val isPluggedIn: Boolean get() = pluggedBitmask != 0 || type == PowerType.AC ||
        type == PowerType.USB || type == PowerType.WIRELESS || type == PowerType.DOCK

    /** 放在无线充电器上但电池没有在充电（满电 / 充电保护 / 温度控制）。 */
    val isChargingPaused: Boolean
        get() = isPluggedIn && (status == ChargingStatus.NOT_CHARGING || status == ChargingStatus.FULL)

    fun describe(): String = buildString {
        append(type.label)
        if (levelPercent in 0..100) append(" · ").append(levelPercent).append("%")
        append(" · ").append(status.label)
        if (!temperatureCelsius.isNaN()) append(" · ").append(String.format("%.1f℃", temperatureCelsius))
    }

    companion object {
        val UNKNOWN = PowerSnapshot()

        fun pluggedType(bitmask: Int): PowerType = when {
            bitmask and 0x4 != 0 -> PowerType.WIRELESS // BATTERY_PLUGGED_WIRELESS
            bitmask and 0x1 != 0 -> PowerType.AC // BATTERY_PLUGGED_AC
            bitmask and 0x8 != 0 -> PowerType.DOCK // BATTERY_PLUGGED_DOCK
            bitmask and 0x2 != 0 -> PowerType.USB // BATTERY_PLUGGED_USB
            bitmask == 0 -> PowerType.NONE
            else -> PowerType.UNKNOWN
        }
    }
}
