package com.wirelesspet.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.wirelesspet.session.PersistedSession
import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionSnapshotStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `repeated battery updates preserve snapshot but suppression changes persist`() = runBlocking {
        val job = SupervisorJob()
        try {
            val store = SessionSnapshotStore(PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + job),
            ) { java.io.File(folder.root, "session.preferences_pb") })
            val original = PersistedSession(3L, "SHOWING", "NONE", "WIRELESS", 2, 100L)
            store.save(original)
            store.save(original.copy(savedAtWallMs = 200L))
            assertEquals(original, store.load())
            val suppressed = original.copy(suppression = "USER_DISMISSED", savedAtWallMs = 300L)
            store.save(suppressed)
            assertEquals(suppressed, store.load())
        } finally { job.cancelAndJoin() }
    }
}
