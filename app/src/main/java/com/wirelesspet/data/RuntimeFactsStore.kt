package com.wirelesspet.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException

/**
 * 与用户配置分离的"运行事实"存储。
 *
 * 目前只记录"是否曾经识别到无线电源"：普通应用无法查询硬件是否支持无线充电，
 * 只能用历史观测结果回答。与 [SessionSnapshotStore] 分开存放，
 * 因为会话快照在会话结束时会整体清空，不能顺带清掉这条事实。
 */
class RuntimeFactsStore(private val dataStore: DataStore<Preferences>) {

    suspend fun markWirelessObserved() {
        dataStore.edit { it[Keys.wirelessEverSeen] = true }
    }

    suspend fun wirelessEverSeen(): Boolean {
        val preferences = dataStore.data
            .catch { throwable -> if (throwable is IOException) emit(emptyPreferences()) else throw throwable }
            .first()
        return preferences[Keys.wirelessEverSeen] ?: false
    }

    /**
     * 记录"上一次自动展示被系统拦截"的墙上时间。
     *
     * HyperOS 会拒绝后台拉起页面，而应用自己无法静默修复，只能引导用户授权；
     * 记住这件事，首页才能把"为什么没弹出"直接讲清楚。
     */
    suspend fun markLaunchBlocked(atWallMs: Long) {
        dataStore.edit { it[Keys.lastLaunchBlockedAt] = atWallMs }
    }

    suspend fun lastLaunchBlockedAt(): Long? {
        val preferences = dataStore.data
            .catch { throwable -> if (throwable is IOException) emit(emptyPreferences()) else throw throwable }
            .first()
        return preferences[Keys.lastLaunchBlockedAt]
    }

    /** 用户完成授权引导后清除提示。 */
    suspend fun clearLaunchBlocked() {
        dataStore.edit { it.remove(Keys.lastLaunchBlockedAt) }
    }

    private object Keys {
        val wirelessEverSeen = booleanPreferencesKey("wireless_ever_seen")
        val lastLaunchBlockedAt = longPreferencesKey("last_launch_blocked_at")
    }

    companion object {
        fun create(context: Context): RuntimeFactsStore =
            RuntimeFactsStore(context.applicationContext.runtimeFactsDataStore)
    }
}
