package io.theficos.ereader.data.local.db

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PR-η: round-trip coverage for [InsightDao]. Exercises the production Room
 * codegen against an in-memory database to catch column-name drift or
 * unexpected REPLACE behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InsightDaoTest {

    private lateinit var db: EReaderDatabase
    private lateinit var dao: InsightDao

    @Before fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        db = Room.inMemoryDatabaseBuilder(ctx, EReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.insightDao()
    }

    @After fun tearDown() = db.close()

    private fun row(
        identityKey: String = "m1",
        metadataId: String? = "m1",
        contentHash: String? = "h1",
        modelId: String = "llama3.1",
        promptVersion: String = "4",
        tone: String = "neutral",
        language: String = "auto",
        serverId: Long = 1L,
        generatedAt: Long = 1_000L,
        syncedAt: Long = 1_100L,
    ) = InsightEntity(
        identityKey = identityKey,
        metadataId = metadataId,
        contentHash = contentHash,
        modelId = modelId,
        promptVersion = promptVersion,
        tone = tone,
        language = language,
        payloadJson = "{}",
        sourcesJson = "[]",
        schemaVersion = 4,
        serverId = serverId,
        generatedAt = generatedAt,
        syncedAt = syncedAt,
    )

    @Test fun `upsert then getByIdentity returns the exact row`() = runTest {
        dao.upsert(row())
        val got = dao.getByIdentity("m1", "llama3.1", "4", "neutral", "auto")
        assertThat(got).isNotNull()
        assertThat(got!!.serverId).isEqualTo(1L)
    }

    @Test fun `upsert with same PK REPLACEs the row`() = runTest {
        dao.upsert(row(serverId = 1L, generatedAt = 1_000L))
        dao.upsert(row(serverId = 99L, generatedAt = 2_000L))
        val got = dao.getByIdentity("m1", "llama3.1", "4", "neutral", "auto")
        assertThat(got!!.serverId).isEqualTo(99L)
        assertThat(got.generatedAt).isEqualTo(2_000L)
        assertThat(dao.count()).isEqualTo(1)
    }

    @Test fun `findAnyForIdentity returns latest across styles`() = runTest {
        dao.upsert(row(tone = "neutral", generatedAt = 1_000L))
        dao.upsert(row(tone = "scholarly", generatedAt = 2_000L))
        dao.upsert(row(tone = "casual", generatedAt = 1_500L))
        val got = dao.findAnyForIdentity("m1")
        assertThat(got).isNotNull()
        assertThat(got!!.tone).isEqualTo("scholarly")
    }

    @Test fun `latestGeneratedAt and latestServerId return cursor tip`() = runTest {
        dao.upsert(row(serverId = 1L, generatedAt = 1_000L))
        dao.upsert(row(metadataId = "m2", identityKey = "m2", serverId = 2L, generatedAt = 1_000L))
        dao.upsert(row(metadataId = "m3", identityKey = "m3", serverId = 3L, generatedAt = 2_000L))
        assertThat(dao.latestGeneratedAt()).isEqualTo(2_000L)
        assertThat(dao.latestServerId()).isEqualTo(3L)
    }

    @Test fun `latestSyncedAt reflects most recent upsert`() = runTest {
        dao.upsert(row(syncedAt = 100L))
        dao.upsert(row(metadataId = "m2", identityKey = "m2", syncedAt = 5_000L))
        dao.upsert(row(metadataId = "m3", identityKey = "m3", syncedAt = 2_000L))
        assertThat(dao.latestSyncedAt()).isEqualTo(5_000L)
    }

    @Test fun `different promptVersion separates rows at same identity`() = runTest {
        dao.upsert(row(promptVersion = "4"))
        dao.upsert(row(promptVersion = "5", serverId = 99L))
        assertThat(dao.getByIdentity("m1", "llama3.1", "4", "neutral", "auto")?.serverId).isEqualTo(1L)
        assertThat(dao.getByIdentity("m1", "llama3.1", "5", "neutral", "auto")?.serverId).isEqualTo(99L)
        assertThat(dao.count()).isEqualTo(2)
    }
}
