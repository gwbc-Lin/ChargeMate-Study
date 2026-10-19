package com.wirelesspet.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 厂商自动化设置入口。
 *
 * 设计文档 §10：厂商设置页跳转必须检查是否可处理，失败时展示手动路径；
 * 菜单名称会随 ROM 变化，不能把"跳转成功"当成"授权成功"，真实结论只能靠自动启动测试。
 * 因此这里只做"能否打开"的检测，并始终附带手工人路径文案。
 */
class OemAdapter(private val context: Context) {

    enum class Vendor { XIAOMI, OPPO, ONEPLUS, VIVO, HONOR, HUAWEI, SAMSUNG, GOOGLE, OTHER }

    data class OemPage(
        val id: String,
        val title: String,
        val manualPath: String,
        val components: List<ComponentName>,
    )

    val vendor: Vendor = detectVendor()

    val romLabel: String = DeviceInfoProvider.romLabel()

    val isXiaomi: Boolean get() = vendor == Vendor.XIAOMI

    /** 当前厂商可用于减少后台限制的页面；不一定都能打开，由 [availablePages] 过滤。 */
    fun pages(): List<OemPage> = when (vendor) {
        Vendor.XIAOMI -> listOf(
            OemPage(
                id = "xiaomi_autostart",
                title = "小米/红米：自启动",
                manualPath = "设置 → 应用设置 → 应用管理 → ChargeMate → 自启动",
                components = listOf(
                    ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity",
                    ),
                ),
            ),
            OemPage(
                id = "xiaomi_security_center",
                title = "小米/红米：安全中心（后台弹出界面）",
                manualPath = "设置 → 应用管理 → ChargeMate → 其他权限 → 后台弹出界面 / 锁屏显示",
                components = listOf(
                    // MainAcitvity 是 MIUI 的历史拼写，不存在时自动回退到权限编辑页。
                    ComponentName("com.miui.securitycenter", "com.miui.permcenter.MainAcitvity"),
                    ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity",
                    ),
                ),
            ),
            OemPage(
                id = "xiaomi_background_popup",
                title = "小米/红米：后台弹出界面权限",
                manualPath = "设置 → 应用管理 → ChargeMate → 其他权限 → 后台弹出界面 / 锁屏显示",
                components = listOf(
                    ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity",
                    ),
                ),
            ),
        )

        Vendor.OPPO, Vendor.ONEPLUS -> listOf(
            OemPage(
                id = "coloros_autostart",
                title = "ColorOS：自启动管理",
                manualPath = "设置 → 电池 → 应用耗电管理 → ChargeMate → 允许自启动 / 允许后台活动",
                components = listOf(
                    ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                    ),
                    ComponentName(
                        "com.oplus.safecenter",
                        "com.oplus.safecenter.permission.startup.StartupAppListActivity",
                    ),
                    ComponentName(
                        "com.coloros.oppoguardelf",
                        "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
                    ),
                ),
            ),
        )

        Vendor.VIVO -> listOf(
            OemPage(
                id = "originos_bgstart",
                title = "OriginOS：后台启动管理",
                manualPath = "i管家 → 应用管理 → 权限管理 → 自启动 → ChargeMate",
                components = listOf(
                    ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                    ),
                    ComponentName(
                        "com.iqoo.secure",
                        "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
                    ),
                ),
            ),
        )

        Vendor.HONOR -> listOf(
            OemPage(
                id = "magicos_startup",
                title = "MagicOS：应用启动管理",
                manualPath = "设置 → 应用 → 应用启动管理 → ChargeMate → 手动管理",
                components = listOf(
                    ComponentName(
                        "com.hihonor.systemmanager",
                        "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    ),
                ),
            ),
        )

        Vendor.HUAWEI -> listOf(
            OemPage(
                id = "harmony_startup",
                title = "EMUI：应用启动管理",
                manualPath = "设置 → 应用 → 应用启动管理 → ChargeMate → 手动管理",
                components = listOf(
                    ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    ),
                ),
            ),
        )

        else -> emptyList()
    }

    /** 本机确实可以打开的厂商页面。 */
    fun availablePages(): List<OemPage> = pages().filter { isAvailable(it) }

    fun isAvailable(page: OemPage): Boolean = page.components.any { component ->
        runCatching {
            context.packageManager.resolveActivity(Intent().setComponent(component), 0) != null
        }.getOrDefault(false)
    }

    /** 尝试打开厂商页面；返回是否真正启动了界面。失败时由调用方展示手动路径。 */
    fun open(page: OemPage): Boolean {
        // 小米权限编辑页必须携带目标包名，否则可能空白或立即退出。
        if (isXiaomi && page.id != "xiaomi_autostart") {
            val opened = runCatching {
                context.startActivity(
                    Intent("miui.intent.action.APP_PERM_EDITOR")
                        .setPackage("com.miui.securitycenter")
                        .putExtra("extra_pkgname", context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            if (opened) return true
        }
        page.components.forEach { component ->
            val started = runCatching {
                context.startActivity(
                    Intent().setComponent(component)
                        .putExtra("extra_pkgname", context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            if (started) return true
        }
        return false
    }

    /** 所有 ROM 通用的应用详情页，厂商页失败时的兜底入口。 */
    fun openAppDetails(): Boolean = runCatching {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess

    fun backgroundSettingsPath(): String = when (vendor) {
        Vendor.SAMSUNG -> "设置 → 应用程序 → ChargeMate → 电池；另检查电池设置中的休眠应用列表"
        Vendor.GOOGLE -> "设置 → 应用 → ChargeMate → 应用电池用量 → 允许后台使用"
        else -> pages().firstOrNull()?.manualPath ?: "设置 → 应用 → ChargeMate → 电池 / 后台运行"
    }

    private fun detectVendor(): Vendor {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        return when {
            manufacturer.contains("xiaomi") || brand.contains("xiaomi") ||
                brand.contains("redmi") || brand.contains("poco") -> Vendor.XIAOMI

            manufacturer.contains("oppo") || brand.contains("oppo") ||
                brand.contains("realme") -> Vendor.OPPO

            manufacturer.contains("oneplus") || brand.contains("oneplus") -> Vendor.ONEPLUS
            manufacturer.contains("vivo") || brand.contains("vivo") ||
                brand.contains("iqoo") -> Vendor.VIVO

            manufacturer.contains("honor") || brand.contains("honor") -> Vendor.HONOR
            manufacturer.contains("huawei") || brand.contains("huawei") -> Vendor.HUAWEI
            manufacturer.contains("samsung") || brand.contains("samsung") -> Vendor.SAMSUNG
            manufacturer.contains("google") || brand.contains("google") -> Vendor.GOOGLE
            else -> Vendor.OTHER
        }
    }
}
