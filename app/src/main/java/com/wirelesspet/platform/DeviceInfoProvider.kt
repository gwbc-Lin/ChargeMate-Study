package com.wirelesspet.platform

import android.content.Context
import android.os.Build
import android.provider.Settings

/** 系统与设备信息的只读访问。用于诊断记录，不采集任何稳定设备标识。 */
object DeviceInfoProvider {

    fun bootCount(context: Context): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrDefault(-1)

    fun isScreenOn(context: Context): Boolean = runCatching {
        context.getSystemService(android.os.PowerManager::class.java).isInteractive
    }.getOrDefault(true)

    fun isKeyguardLocked(context: Context): Boolean = runCatching {
        context.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked ?: false
    }.getOrDefault(false)

    val androidRelease: String get() = Build.VERSION.RELEASE ?: "unknown"

    /**
     * 厂商 ROM 标识。HyperOS 版本来自构建号（例如 OS3.0.307.0.WNCCNXM），
     * 只作为诊断记录，不作为任何自动化逻辑的依据。
     */
    fun romLabel(): String {
        val display = Build.DISPLAY.orEmpty()
        val incremental = Build.VERSION.INCREMENTAL.orEmpty()
        val text = "$display $incremental"
        val manufacturer = Build.MANUFACTURER.orEmpty()

        if (manufacturer.equals("Xiaomi", ignoreCase = true)) {
            val hyperOs = HYPER_OS.find(text)
            if (hyperOs != null) {
                // 构建号形如 OS3.0.307.0.WNCCNXM，去掉前缀 OS 避免出现"HyperOS OS3.x"。
                val version = hyperOs.groupValues[1].removePrefix("OS")
                return "HyperOS $version"
            }
            if (text.contains("MIUI", ignoreCase = true)) return "MIUI（${Build.VERSION.INCREMENTAL}）"
            return "MIUI/HyperOS（未识别版本）"
        }
        if (manufacturer.equals("OPPO", ignoreCase = true)) {
            if (text.contains("coloros", ignoreCase = true)) return "ColorOS"
            return "ColorOS（未识别版本）"
        }
        return when {
            manufacturer.equals("OnePlus", ignoreCase = true) ||
                Build.BRAND.equals("OnePlus", ignoreCase = true) -> "OxygenOS/ColorOS"
            manufacturer.equals("vivo", ignoreCase = true) -> "OriginOS/FuntouchOS"
            manufacturer.equals("HONOR", ignoreCase = true) -> "MagicOS"
            manufacturer.equals("HUAWEI", ignoreCase = true) -> "EMUI/HarmonyOS"
            else -> manufacturer.ifBlank { "unknown" }
        }
    }

    private val HYPER_OS = Regex("""(OS\d+\.\d+(?:\.\d+)*(?:\.[A-Za-z]+)?)""")
}
