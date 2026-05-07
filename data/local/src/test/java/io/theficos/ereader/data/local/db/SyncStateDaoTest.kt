package io.theficos.ereader.data.local.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncStateDaoTest {
    private lateinit var db: EReaderDatabase
    private lateinit var dao: SyncStateDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), EReaderDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.syncStateDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `lastPulled is null when unset`() = runTest {
        assertThat(dao.lastPulled("progress")).isNull()
    }

    @Test fun `set then read round-trips`() = runTest {
        dao.set(SyncStateEntity("progress", 12345L))
        assertThat(dao.lastPulled("progress")).isEqualTo(12345L)
    }

    @Test fun `set replaces existing`() = runTest {
        dao.set(SyncStateEntity("progress", 1L))
        dao.set(SyncStateEntity("progress", 2L))
        assertThat(dao.lastPulled("progress")).isEqualTo(2L)
    }

    @Test fun `clearAll removes every row`() = runTest {
        dao.set(SyncStateEntity("progress", 1234L))
        dao.set(SyncStateEntity("bookmarks", 5678L))

        dao.clearAll()

        assertThat(dao.lastPulled("progress")).isNull()
        assertThat(dao.lastPulled("bookmarks")).isNull()
    }
}
