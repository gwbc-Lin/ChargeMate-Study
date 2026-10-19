package com.wirelesspet.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClockStylePersistenceTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `style survives store reopen and updates preserve other preferences`() = runBlocking {
        val file = java.io.File(folder.root, "style.preferences_pb")
        val firstJob = SupervisorJob()
        val first = SettingsRepository(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + firstJob)) { file })
        try {
            first.update { it.copy(wiredChargingEnabled = true, spineLicenseAccepted = true, audioSpectrumEnabled = true, clockStyle = ClockStyle.AMBER, landscapeLayout = LandscapeLayout.BUST_SPLIT, selectedCharacterId = "user_custom") }
            first.update { it.copy(showBatteryInfo = false) }
            assertEquals(ClockStyle.AMBER, first.settings.first().clockStyle)
        } finally { firstJob.cancelAndJoin() }
        val secondJob = SupervisorJob()
        try {
            val second = SettingsRepository(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + secondJob)) { file })
            val restored = second.settings.first()
            assertEquals(true, restored.wiredChargingEnabled)
            assertEquals(true, restored.spineLicenseAccepted)
            assertEquals(true, restored.audioSpectrumEnabled)
            assertEquals(ClockStyle.AMBER, restored.clockStyle)
            assertEquals(LandscapeLayout.BUST_SPLIT, restored.landscapeLayout)
            assertEquals("user_custom", restored.selectedCharacterId)
            assertEquals(false, restored.showBatteryInfo)
        } finally { secondJob.cancelAndJoin() }
    }

}
