package com.wirelesspet.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.wirelesspet.data.AutomationMode

/**
 * 能力检测（设计文档 §10）。
 *
 * 设计文档强调："能监听""能启动服务""能启动页面""能在锁屏上显示""能点亮屏幕"
 * 是五种能力，不能用同一个"已授权"状态概括。这里逐项检测并说明缺失的具体影响。
 */
class CapabilityDetector(private val context: Context) {

    enum class State(val label: String) {
        OK("可用"),
        MISSING("缺失"),
        UNKNOWN("待验证"),
        INFO("说明"),
        NOT_APPLICABLE("不适用"),
    }

    sealed interface Action {
        data object RequestNotificationPermission : Action
        data object OpenNotificationSettings : Action
        data object OpenBatteryOptimization : Action
        data object OpenOverlaySettings : Action
        data object OpenDreamSettings : Action
        data object OpenAppDetails : Action
        data class OpenOemPage(val pageId: String) : Action
    }

    data class Item(
        val id: String,
        val title: String,
        val state: State,
        val detail: String,
        val action: Action? = null,
        val manualPath: String? = null,
    )

    data class DeviceInfo(
        val manufacturer: String,
        val brand: String,
        val model: String,
        val sdkInt: Int,
        val androidRelease: String,
        val romLabel: String,
        val display: String,
        val incremental: String,
        val appVersion: String,
        val packageName: String,
        val targetSdk: Int,
    )

    data class Report(val device: DeviceInfo, val items: List<Item>)

    fun inspect(wirelessEverSeen: Boolean): Report {
        val oem = OemAdapter(context)
        val items = mutableListOf<Item>()

        // 1. 通知：立即模式的状态展示与手动入口。
        val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val postPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        items += Item(
            id = "notifications",
            title = "通知",
            state = if (notificationsEnabled && postPermissionGranted) State.OK else State.MISSING,
            detail = when {
                !postPermissionGranted -> "未授予通知权限：立即模式无法显示运行状态与手动入口"
                !notificationsEnabled ->
                    "权限已授予，但系统层的应用通知开关仍关闭（HyperOS 会单独管理）：" +
                        "展示被拦截时不会有任何提示"
                else -> "可用于运行状态与手动入口"
            },
            action = if (postPermissionGranted) Action.OpenNotificationSettings
            else Action.RequestNotificationPermission,
        )

        // 2. 前台服务：无运行时权限，但能否真正跑起来必须真机验证。
        items += Item(
            id = "foreground_service",
            title = "前台监听服务",
            state = State.UNKNOWN,
            detail = "已在清单声明 specialUse 前台服务；后台启动与厂商清理只能在真机上验证",
        )

        // 3. 屏幕熄灭时亮屏：普通应用没有直接权限，需要验证宿主可见性。
        items += Item(
            id = "wake_screen",
            title = "息屏时亮屏展示",
            state = State.UNKNOWN,
            detail = "依赖后台启动页面的实际结果，文档要求真机验证，不预先承诺",
        )

        // 4. 悬浮窗：仅作为后台启动评估条件，默认不作为必需权限。
        val overlay = Settings.canDrawOverlays(context)
        items += Item(
            id = "overlay",
            title = "显示在其他应用上层（可选后台启动增强）",
            state = if (overlay) State.OK else State.INFO,
            detail = if (overlay) {
                "已授予：可作为后台启动页面的豁免条件之一"
            } else {
                "未授予：如已允许厂商后台弹出但仍失败，可主动开启后测试；应用不绘制常驻悬浮窗"
            },
            action = if (overlay) null else Action.OpenOverlaySettings,
        )

        // 5. 系统是否限制本应用后台运行；被限制时后台启动页面必然失败。
        val backgroundRestricted = runCatching {
            context.getSystemService(android.app.ActivityManager::class.java)
                ?.isBackgroundRestricted == true
        }.getOrDefault(false)
        items += Item(
            id = "background_restriction",
            title = "后台运行限制",
            state = if (backgroundRestricted) State.MISSING else State.OK,
            detail = if (backgroundRestricted) {
                "系统已限制本应用后台运行：自动展示会被直接拦截，需要在应用信息里允许后台运行"
            } else {
                "系统未对本应用启用后台运行限制（仍可能被厂商清理）"
            },
            action = if (backgroundRestricted) Action.OpenAppDetails else null,
        )

        // 6. 电池优化：降低被限制概率，不承诺永不被回收。
        val ignoringBattery = runCatching {
            context.getSystemService(android.os.PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(false)
        items += Item(
            id = "battery_optimization",
            title = "电池优化",
            state = if (ignoringBattery) State.OK else State.MISSING,
            detail = if (ignoringBattery) {
                "本应用未受电池优化限制（仍可能被厂商清理）"
            } else {
                "系统可能随时限制后台运行；不会因此保证\"自动重启\""
            },
            action = if (ignoringBattery) null else Action.OpenBatteryOptimization,
        )

        // 6. 开机恢复：仅执行平台允许的恢复工作。
        items += Item(
            id = "boot_receiver",
            title = "开机自检",
            state = State.UNKNOWN,
            detail = "已声明 RECEIVE_BOOT_COMPLETED；开机后能否启动前台服务需在小米 14 上验证",
        )

        // 7. 屏保：系统是否看得到本应用的 DreamService，以及是否已被选中。
        val dreamVisible = dreamServiceVisible()
        val dreamSelected = isOurDreamSelected()
        items += Item(
            id = "dream",
            title = "系统屏保（闲置屏保模式）",
            state = when {
                dreamSelected -> State.OK
                dreamVisible -> State.MISSING
                else -> State.NOT_APPLICABLE
            },
            detail = when {
                dreamSelected -> "系统屏保已选择ChargeMate"
                dreamVisible -> "系统已识别本应用的屏保服务，但尚未在屏保设置中选中它"
                else -> "当前系统没有提供可用的屏保入口，本版本不把该模式当成可用能力"
            },
            action = if (dreamVisible) Action.OpenDreamSettings else null,
        )

        // 8. 无线电源历史：普通应用无法直接查询硬件是否支持无线充电。
        items += Item(
            id = "wireless_history",
            title = "无线电源识别",
            state = if (wirelessEverSeen) State.OK else State.UNKNOWN,
            detail = if (wirelessEverSeen) {
                "本机曾收到无线电源广播，识别链路已验证"
            } else {
                "尚无无线充电记录，需放上无线充电器后验证识别结果"
            },
        )

        // 9. 厂商自动化入口。
        val availableOemPages = oem.availablePages()
        if (availableOemPages.isEmpty()) {
            items += Item(
                id = "oem_autostart",
                title = "厂商自启动设置",
                state = State.INFO,
                detail = "使用系统应用详情检查后台限制；厂商独立入口未检测到",
                action = Action.OpenAppDetails,
                manualPath = oem.backgroundSettingsPath(),
            )
        } else {
            availableOemPages.forEach { page ->
                items += Item(
                    id = page.id,
                    title = page.title,
                    state = State.INFO,
                    detail = "跳转只代表入口存在，授权结果需用「测试自动启动」确认",
                    action = Action.OpenOemPage(page.id),
                    manualPath = page.manualPath,
                )
            }
        }

        return Report(device = deviceInfo(), items = items)
    }

    /** 当前系统屏保是否选择了本应用的 DreamService。 */
    fun isOurDreamSelected(): Boolean = runCatching {
        val components = Settings.Secure.getString(
            context.contentResolver,
            "screensaver_components",
        ).orEmpty()
        components.contains(context.packageName)
    }.getOrDefault(false)

    /**
     * 系统是否能看到本应用的屏保服务。
     *
     * 设计文档要求"屏保入口不存在时隐藏该模式或标为不可用，不依赖隐藏 API 强启"，
     * 因此这里用系统的服务查询结果回答"这套 ROM 到底有没有这个入口"。
     */
    fun dreamServiceVisible(): Boolean = runCatching {
        context.packageManager
            .queryIntentServices(Intent("android.service.dreams.DreamService"), 0)
            .any { it.serviceInfo.packageName == context.packageName }
    }.getOrDefault(false)

    fun openDreamSettings(): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_DREAM_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess

    fun openNotificationSettings(): Boolean = runCatching {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.isSuccess

    fun requestIgnoreBatteryOptimizations(): Boolean = runCatching {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.isSuccess

    fun openOverlaySettings(): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess

    /** 供设置页/诊断页决定是否显示"立即模式"相关条目。 */
    fun modeNeedsForegroundService(mode: AutomationMode): Boolean = mode == AutomationMode.IMMEDIATE

    private fun deviceInfo(): DeviceInfo {
        val packageInfo = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER ?: "unknown",
            brand = Build.BRAND ?: "unknown",
            model = Build.MODEL ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT,
            androidRelease = Build.VERSION.RELEASE ?: "unknown",
            romLabel = DeviceInfoProvider.romLabel(),
            display = Build.DISPLAY ?: "unknown",
            incremental = Build.VERSION.INCREMENTAL ?: "unknown",
            appVersion = packageInfo?.versionName ?: "unknown",
            packageName = context.packageName,
            targetSdk = context.applicationInfo.targetSdkVersion,
        )
    }
}
