package io.theficos.ereader.data.local.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        EReaderDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `migrate 2 to 3 backfills localUpdatedAt and creates sync_state`() {
        helper.createDatabase(DB, 2).use { db ->
            db.execSQL(
                "INSERT INTO documents (id, metadataId, contentHash, title, author, downloadUrl, localPath, coverPath, downloadedAt) " +
                    "VALUES (1, NULL, 'h', 't', NULL, 'u', 'p', NULL, 0)"
            )
            db.execSQL(
                "INSERT INTO progress (id, documentId, locator, percent, updatedAt) VALUES (1, 1, 'loc', 0.5, 42)"
            )
        }

        helper.runMigrationsAndValidate(DB, 3, true, EReaderDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT localUpdatedAt, syncedAt FROM progress WHERE id=1").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getLong(0)).isEqualTo(42L)
                assertThat(c.getLong(1)).isEqualTo(0L)
            }
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='sync_state'").use { c ->
                assertThat(c.moveToFirst()).isTrue()
            }
        }
    }

    private companion object { const val DB = "migration-test.db" }
}
