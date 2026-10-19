package com.wirelesspet.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * 用户配置仓库。DataStore 单文件即可满足首版需求，不引入数据库。
 *
 * 读取使用 catch/IO 兜底，避免损坏的配置文件让整个应用无法启动。
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<UserSettings> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { it.toSettings() }

    suspend fun update(transform: (UserSettings) -> UserSettings) {
        dataStore.edit { preferences ->
            val next = transform(preferences.toSettings())
            preferences.clear()
            preferences.write(next)
        }
    }

    suspend fun setAutomationEnabled(enabled: Boolean) =
        update { it.copy(automationEnabled = enabled) }

    suspend fun setAutomationMode(mode: AutomationMode) =
        update { it.copy(automationMode = mode) }

    suspend fun markOnboardingCompleted() = update { it.copy(onboardingCompleted = true) }

    suspend fun resetToDefaults() {
        dataStore.edit { it.clear() }
    }

    companion object {
        fun create(context: Context): SettingsRepository =
            SettingsRepository(context.applicationContext.settingsDataStore)
    }
}

private object Keys {
    val spineLicenseAccepted = booleanPreferencesKey("spine_runtime_license_2025_accepted")
    val character = stringPreferencesKey("selected_character")
    val clockStyle = stringPreferencesKey("clock_style")
    val landscapeLayout = stringPreferencesKey("landscape_layout")
    val automationEnabled = booleanPreferencesKey("automation_enabled")
    val wiredCharging = booleanPreferencesKey("wired_charging_enabled")
    val automationMode = stringPreferencesKey("automation_mode")
    val allowInterrupt = booleanPreferencesKey("allow_interrupt_other_apps")
    val wakeScreen = booleanPreferencesKey("wake_screen_when_off")
    val lockScreen = booleanPreferencesKey("show_on_lock_screen")
    val showBattery = booleanPreferencesKey("show_battery_info")
    val interaction = booleanPreferencesKey("interaction_enabled")
    val audioSpectrum = booleanPreferencesKey("audio_spectrum_enabled")
    val energyProfile = stringPreferencesKey("energy_profile")
    val fullChargeExit = booleanPreferencesKey("full_charge_exit")
    val quietHoursEnabled = booleanPreferencesKey("quiet_hours_enabled")
    val quietStart = intPreferencesKey("quiet_start_minute")
    val quietEnd = intPreferencesKey("quiet_end_minute")
    val onboardingCompleted = booleanPreferencesKey("onboarding_completed")
}

private fun Preferences.toSettings(): UserSettings = UserSettings(
    spineLicenseAccepted = this[Keys.spineLicenseAccepted] ?: false,
    selectedCharacterId = this[Keys.character] ?: UserSettings.DEFAULT.selectedCharacterId,
    clockStyle = enumOr(this[Keys.clockStyle], UserSettings.DEFAULT.clockStyle),
    landscapeLayout = enumOr(this[Keys.landscapeLayout], UserSettings.DEFAULT.landscapeLayout),
    automationEnabled = this[Keys.automationEnabled] ?: UserSettings.DEFAULT.automationEnabled,
    wiredChargingEnabled = this[Keys.wiredCharging] ?: false,
    automationMode = enumOr(this[Keys.automationMode], UserSettings.DEFAULT.automationMode),
    allowInterruptOtherApps = this[Keys.allowInterrupt] ?: UserSettings.DEFAULT.allowInterruptOtherApps,
    wakeScreenWhenOff = this[Keys.wakeScreen] ?: UserSettings.DEFAULT.wakeScreenWhenOff,
    showOnLockScreen = this[Keys.lockScreen] ?: UserSettings.DEFAULT.showOnLockScreen,
    showBatteryInfo = this[Keys.showBattery] ?: UserSettings.DEFAULT.showBatteryInfo,
    interactionEnabled = this[Keys.interaction] ?: UserSettings.DEFAULT.interactionEnabled,
    audioSpectrumEnabled = this[Keys.audioSpectrum] ?: false,
    energyProfile = enumOr(this[Keys.energyProfile], UserSettings.DEFAULT.energyProfile),
    fullChargeExit = this[Keys.fullChargeExit] ?: UserSettings.DEFAULT.fullChargeExit,
    quietHoursEnabled = this[Keys.quietHoursEnabled] ?: UserSettings.DEFAULT.quietHoursEnabled,
    quietStartMinuteOfDay = this[Keys.quietStart] ?: UserSettings.DEFAULT.quietStartMinuteOfDay,
    quietEndMinuteOfDay = this[Keys.quietEnd] ?: UserSettings.DEFAULT.quietEndMinuteOfDay,
    onboardingCompleted = this[Keys.onboardingCompleted] ?: UserSettings.DEFAULT.onboardingCompleted,
)

private fun androidx.datastore.preferences.core.MutablePreferences.write(settings: UserSettings) {
    this[Keys.spineLicenseAccepted] = settings.spineLicenseAccepted
    this[Keys.character] = settings.selectedCharacterId
    this[Keys.clockStyle] = settings.clockStyle.name
    this[Keys.landscapeLayout] = settings.landscapeLayout.name
    this[Keys.automationEnabled] = settings.automationEnabled
    this[Keys.wiredCharging] = settings.wiredChargingEnabled
    this[Keys.automationMode] = settings.automationMode.name
    this[Keys.allowInterrupt] = settings.allowInterruptOtherApps
    this[Keys.wakeScreen] = settings.wakeScreenWhenOff
    this[Keys.lockScreen] = settings.showOnLockScreen
    this[Keys.showBattery] = settings.showBatteryInfo
    this[Keys.interaction] = settings.interactionEnabled
    this[Keys.audioSpectrum] = settings.audioSpectrumEnabled
    this[Keys.energyProfile] = settings.energyProfile.name
    this[Keys.fullChargeExit] = settings.fullChargeExit
    this[Keys.quietHoursEnabled] = settings.quietHoursEnabled
    this[Keys.quietStart] = settings.quietStartMinuteOfDay
    this[Keys.quietEnd] = settings.quietEndMinuteOfDay
    this[Keys.onboardingCompleted] = settings.onboardingCompleted
}

private inline fun <reified T : Enum<T>> enumOr(raw: String?, fallback: T): T {
    if (raw == null) return fallback
    return runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)
}
