package com.wirelesspet.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.wirelesspet.session.PersistedSession
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException

/**
 * 最小会话快照持久化（设计文档 §6.3）。
 *
 * 只存 sessionId、阶段、抑制原因、电源类型、启动周期与时间，不存任何其他应用信息。
 * 恢复时由 ChargingSessionController.restore 结合"当前真实电池状态"判断，不直接续用。
 */
class SessionSnapshotStore(private val dataStore: DataStore<Preferences>) {

    suspend fun save(persisted: PersistedSession) {
        dataStore.edit { preferences ->
            if (preferences[Keys.sessionId] == persisted.sessionId && preferences[Keys.phase] == persisted.phase &&
                preferences[Keys.suppression] == persisted.suppression && preferences[Keys.powerType] == persisted.powerType &&
                preferences[Keys.bootCount] == persisted.bootCount) return@edit
            preferences[Keys.sessionId] = persisted.sessionId
            preferences[Keys.phase] = persisted.phase
            preferences[Keys.suppression] = persisted.suppression
            preferences[Keys.powerType] = persisted.powerType
            preferences[Keys.bootCount] = persisted.bootCount
            preferences[Keys.savedAtWallMs] = persisted.savedAtWallMs
        }
    }

    suspend fun load(): PersistedSession? {
        val preferences = dataStore.data
            .catch { throwable -> if (throwable is IOException) emit(emptyPreferences()) else throw throwable }
            .first()
        val sessionId = preferences[Keys.sessionId] ?: return null
        return PersistedSession(
            sessionId = sessionId,
            phase = preferences[Keys.phase] ?: "IDLE",
            suppression = preferences[Keys.suppression] ?: "NONE",
            powerType = preferences[Keys.powerType] ?: "UNKNOWN",
            bootCount = preferences[Keys.bootCount] ?: -1,
            savedAtWallMs = preferences[Keys.savedAtWallMs] ?: 0L,
        )
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private object Keys {
        val sessionId = longPreferencesKey("session_id")
        val phase = stringPreferencesKey("session_phase")
        val suppression = stringPreferencesKey("suppression")
        val powerType = stringPreferencesKey("power_type")
        val bootCount = intPreferencesKey("boot_count")
        val savedAtWallMs = longPreferencesKey("saved_at_wall_ms")
    }

    companion object {
        fun create(context: Context): SessionSnapshotStore =
            SessionSnapshotStore(context.applicationContext.sessionDataStore)
    }
}
