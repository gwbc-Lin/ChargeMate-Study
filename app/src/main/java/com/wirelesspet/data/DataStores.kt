package com.wirelesspet.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/** 用户配置的 DataStore（文件：datastore/settings.preferences_pb）。 */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 会话快照的 DataStore（文件：datastore/session.preferences_pb）。 */
val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

/** 运行事实的 DataStore（文件：datastore/runtime_facts.preferences_pb）。 */
val Context.runtimeFactsDataStore: DataStore<Preferences> by preferencesDataStore(name = "runtime_facts")
