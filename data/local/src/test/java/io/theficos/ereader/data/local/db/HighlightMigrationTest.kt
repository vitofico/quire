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
class HighlightMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), EReaderDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `migration 9 to 10 preserves existing library progress insights and sync state`() {
        helper.createDatabase("highlights-9-10.db", 9).use { db ->
            db.execSQL(
                "INSERT INTO documents (id, metadataId, contentHash, title, author, downloadUrl, localPath, downloadedAt, identityHashVersion) " +
                    "VALUES (1, 'book', 'hash', 'Existing title', 'Existing author', 'u', 'p', 100, 2)"
            )
            db.execSQL(
                "INSERT INTO progress (id, documentId, locator, percent, updatedAt, localUpdatedAt, syncedAt, finishedAt, abandonedAt) " +
                    "VALUES (1, 1, 'existing-locator', 0.75, 101, 102, 100, NULL, 103)"
            )
            db.execSQL(
                "INSERT INTO book_insights (identityKey, metadataId, contentHash, modelId, promptVersion, tone, language, " +
                    "payloadJson, sourcesJson, schemaVersion, serverId, generatedAt, syncedAt, identityHashVersion) " +
                    "VALUES ('book', 'book', 'hash', 'model', '1', 'neutral', 'auto', '{\"kept\":true}', '[]', 1, 4, 104, 105, 2)"
            )
            db.execSQL("INSERT INTO sync_state (tableName, lastPulledAt) VALUES ('progress', 106)")
        }

        helper.runMigrationsAndValidate("highlights-9-10.db", 10, true, EReaderDatabase.MIGRATION_9_10).use { db ->
            db.query("SELECT title, author, identityHashVersion FROM documents WHERE id=1").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(0)).isEqualTo("Existing title")
                assertThat(c.getString(1)).isEqualTo("Existing author")
                assertThat(c.getInt(2)).isEqualTo(2)
            }
            db.query("SELECT locator, percent, updatedAt, localUpdatedAt, syncedAt, finishedAt, abandonedAt FROM progress WHERE id=1").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(0)).isEqualTo("existing-locator")
                assertThat(c.getDouble(1)).isEqualTo(0.75)
                assertThat(c.getLong(2)).isEqualTo(101L)
                assertThat(c.getLong(3)).isEqualTo(102L)
                assertThat(c.getLong(4)).isEqualTo(100L)
                assertThat(c.isNull(5)).isTrue()
                assertThat(c.getLong(6)).isEqualTo(103L)
            }
            db.query("SELECT payloadJson, generatedAt, syncedAt FROM book_insights WHERE identityKey='book'").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(0)).isEqualTo("{\"kept\":true}")
                assertThat(c.getLong(1)).isEqualTo(104L)
                assertThat(c.getLong(2)).isEqualTo(105L)
            }
            db.query("SELECT lastPulledAt FROM sync_state WHERE tableName='progress'").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getLong(0)).isEqualTo(106L)
            }
            db.query("PRAGMA foreign_key_list('highlights')").use { c -> assertThat(c.count).isEqualTo(0) }
            // An annotation may exist with no downloaded document; it is not an orphan to prune.
            db.execSQL(
                "INSERT INTO highlights (id, metadataId, contentHash, locator, text, colour, bookTitle, createdAt, updatedAt) " +
                    "VALUES ('annotation', 'absent-book', 'absent-hash', 'locator', 'quote', 'GREEN', 'Retained title', 110, 110)"
            )
            db.query("SELECT identityHashVersion, deletedAt, bookTitle FROM highlights WHERE id='annotation'").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getInt(0)).isEqualTo(1)
                assertThat(c.isNull(1)).isTrue()
                assertThat(c.getString(2)).isEqualTo("Retained title")
            }
        }
    }

    @Test fun `the complete 1 to 10 path keeps early documents and backfills progress stamps`() {
        helper.createDatabase("highlights-1-10.db", 1).use { db ->
            db.execSQL(
                "INSERT INTO documents (id, metadataId, contentHash, title, author, downloadUrl, localPath, downloadedAt) " +
                    "VALUES (1, NULL, 'early-hash', 'Early title', NULL, 'u', 'p', 12)"
            )
            db.execSQL("INSERT INTO progress (id, documentId, locator, percent, updatedAt) VALUES (1, 1, 'early-locator', 0.5, 42)")
        }

        helper.runMigrationsAndValidate(
            "highlights-1-10.db", 10, true,
            EReaderDatabase.MIGRATION_1_2, EReaderDatabase.MIGRATION_2_3,
            EReaderDatabase.MIGRATION_3_4, EReaderDatabase.MIGRATION_4_5,
            EReaderDatabase.MIGRATION_5_6, EReaderDatabase.MIGRATION_6_7,
            EReaderDatabase.MIGRATION_7_8, EReaderDatabase.MIGRATION_8_9,
            EReaderDatabase.MIGRATION_9_10,
        ).use { db ->
            db.query("SELECT title, coverPath, librarySyncedAt, identityHashVersion FROM documents WHERE id=1").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(0)).isEqualTo("Early title")
                assertThat(c.isNull(1)).isTrue()
                assertThat(c.isNull(2)).isTrue()
                assertThat(c.getInt(3)).isEqualTo(1)
            }
            db.query("SELECT locator, localUpdatedAt, syncedAt, abandonedAt FROM progress WHERE id=1").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(0)).isEqualTo("early-locator")
                assertThat(c.getLong(1)).isEqualTo(42L)
                assertThat(c.getLong(2)).isEqualTo(0L)
                assertThat(c.isNull(3)).isTrue()
            }
            db.query("SELECT COUNT(*) FROM highlights").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getInt(0)).isEqualTo(0)
            }
        }
    }
}
