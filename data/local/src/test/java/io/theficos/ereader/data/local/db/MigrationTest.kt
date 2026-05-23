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

    @Test fun `migrate 4 to 5 adds seriesName and seriesIndex columns plus index`() {
        helper.createDatabase(DB, 4).use { db ->
            db.execSQL(
                "INSERT INTO documents (id, metadataId, contentHash, title, author, downloadUrl, localPath, coverPath, downloadedAt) " +
                    "VALUES (7, 'm7', 'h7', 'Pre-migration title', NULL, 'u', 'p', NULL, 17)"
            )
        }

        helper.runMigrationsAndValidate(DB, 5, true, EReaderDatabase.MIGRATION_4_5).use { db ->
            // Existing row survives with NULLs for the new columns.
            db.query("SELECT seriesName, seriesIndex, title FROM documents WHERE id=7").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.isNull(0)).isTrue()
                assertThat(c.isNull(1)).isTrue()
                assertThat(c.getString(2)).isEqualTo("Pre-migration title")
            }
            // The new index exists by the contracted name.
            db.query(
                "SELECT name FROM sqlite_master " +
                    "WHERE type='index' AND name='index_documents_seriesName_seriesIndex'"
            ).use { c ->
                assertThat(c.moveToFirst()).isTrue()
            }
            // New columns round-trip.
            db.execSQL(
                "INSERT INTO documents (id, metadataId, contentHash, title, author, downloadUrl, localPath, coverPath, downloadedAt, seriesName, seriesIndex) " +
                    "VALUES (8, 'm8', 'h8', 'Post-migration title', NULL, 'u', 'p', NULL, 18, 'Foundation', 2.5)"
            )
            db.query("SELECT seriesName, seriesIndex FROM documents WHERE id=8").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(0)).isEqualTo("Foundation")
                assertThat(c.getDouble(1)).isEqualTo(2.5)
            }
        }
    }

    @Test fun `migrate 5 to 6 adds librarySyncedAt column defaulting to null`() {
        helper.createDatabase(DB, 5).use { db ->
            db.execSQL(
                "INSERT INTO documents (id, metadataId, contentHash, title, author, downloadUrl, localPath, coverPath, downloadedAt, seriesName, seriesIndex) " +
                    "VALUES (9, 'm9', 'h9', 'Pre-PR title', NULL, 'u', 'p', NULL, 19, NULL, NULL)"
            )
        }

        helper.runMigrationsAndValidate(DB, 6, true, EReaderDatabase.MIGRATION_5_6).use { db ->
            // Existing row survives with NULL librarySyncedAt — that's the
            // signal the uploader uses to backfill.
            db.query("SELECT librarySyncedAt FROM documents WHERE id=9").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.isNull(0)).isTrue()
            }
            // New rows can write a non-null librarySyncedAt and read it back.
            db.execSQL(
                "INSERT INTO documents (id, metadataId, contentHash, title, author, downloadUrl, localPath, coverPath, downloadedAt, seriesName, seriesIndex, librarySyncedAt) " +
                    "VALUES (10, 'm10', 'h10', 'Post-PR title', NULL, 'u', 'p', NULL, 20, NULL, NULL, 12345)"
            )
            db.query("SELECT librarySyncedAt FROM documents WHERE id=10").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getLong(0)).isEqualTo(12345L)
            }
        }
    }

    @Test fun `migrate 6 to 7 creates book_insights table and indices`() {
        helper.createDatabase(DB, 6).use { db ->
            // Seed a row in `documents` to assert it survives the migration.
            db.execSQL(
                "INSERT INTO documents (id, metadataId, contentHash, title, author, downloadUrl, " +
                    "localPath, coverPath, downloadedAt, seriesName, seriesIndex, librarySyncedAt) " +
                    "VALUES (11, 'm11', 'h11', 'Pre-η title', NULL, 'u', 'p', NULL, 21, NULL, NULL, NULL)"
            )
        }

        helper.runMigrationsAndValidate(DB, 7, true, EReaderDatabase.MIGRATION_6_7).use { db ->
            // book_insights table exists with the expected columns.
            db.query("PRAGMA table_info('book_insights')").use { c ->
                val cols = mutableSetOf<String>()
                while (c.moveToNext()) {
                    cols += c.getString(c.getColumnIndexOrThrow("name"))
                }
                assertThat(cols).containsAtLeast(
                    "identityKey", "metadataId", "contentHash", "modelId", "promptVersion",
                    "tone", "language", "payloadJson", "sourcesJson", "schemaVersion",
                    "serverId", "generatedAt", "syncedAt",
                )
            }
            // All four indices present by their contracted names.
            db.query(
                "SELECT name FROM sqlite_master WHERE type='index' " +
                    "AND name IN ('index_book_insights_syncedAt'," +
                    " 'index_book_insights_metadataId'," +
                    " 'index_book_insights_contentHash'," +
                    " 'index_book_insights_cursor')"
            ).use { c ->
                val idx = mutableSetOf<String>()
                while (c.moveToNext()) idx += c.getString(0)
                assertThat(idx).hasSize(4)
            }
            // Pre-existing v6 row survives the additive migration.
            db.query("SELECT COUNT(*) FROM documents WHERE id=11").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getInt(0)).isEqualTo(1)
            }
            // New table accepts a round-trip insert at the PK shape.
            db.execSQL(
                "INSERT INTO book_insights (identityKey, metadataId, contentHash, modelId, " +
                    "promptVersion, tone, language, payloadJson, sourcesJson, schemaVersion, " +
                    "serverId, generatedAt, syncedAt) VALUES " +
                    "('m11', 'm11', 'h11', 'llama3.1', '4', 'neutral', 'auto', '{}', '[]', 4, " +
                    "42, 1000, 1100)"
            )
            db.query(
                "SELECT serverId, generatedAt FROM book_insights " +
                    "WHERE identityKey='m11' AND modelId='llama3.1' AND promptVersion='4'"
            ).use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getLong(0)).isEqualTo(42L)
                assertThat(c.getLong(1)).isEqualTo(1000L)
            }
        }
    }

    @Test fun `migrate 7 to 8 adds abandonedAt column defaulting to null`() {
        helper.createDatabase(DB, 7).use { db ->
            db.execSQL(
                "INSERT INTO documents (id, metadataId, contentHash, title, author, downloadUrl, " +
                    "localPath, coverPath, downloadedAt, seriesName, seriesIndex, librarySyncedAt) " +
                    "VALUES (21, 'm21', 'h21', 'Pre-α title', NULL, 'u', 'p', NULL, 31, NULL, NULL, NULL)"
            )
            db.execSQL(
                "INSERT INTO progress (id, documentId, locator, percent, updatedAt, " +
                    "localUpdatedAt, syncedAt, finishedAt) " +
                    "VALUES (21, 21, 'loc', 0.5, 100, 100, 0, NULL)"
            )
        }

        helper.runMigrationsAndValidate(DB, 8, true, EReaderDatabase.MIGRATION_7_8).use { db ->
            // Column exists.
            db.query("PRAGMA table_info('progress')").use { c ->
                val cols = mutableSetOf<String>()
                while (c.moveToNext()) cols += c.getString(c.getColumnIndexOrThrow("name"))
                assertThat(cols).contains("abandonedAt")
            }
            // Pre-existing row survives with abandonedAt = NULL.
            db.query("SELECT abandonedAt FROM progress WHERE id=21").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.isNull(0)).isTrue()
            }
            // Insert a second documents row so we can write a second progress
            // row (progress.documentId has a UNIQUE constraint).
            db.execSQL(
                "INSERT INTO documents (id, metadataId, contentHash, title, author, downloadUrl, " +
                    "localPath, coverPath, downloadedAt, seriesName, seriesIndex, librarySyncedAt) " +
                    "VALUES (22, 'm22', 'h22', 'Round-trip title', NULL, 'u', 'p', NULL, 32, NULL, NULL, NULL)"
            )
            // New rows round-trip a non-null abandonedAt.
            db.execSQL(
                "INSERT INTO progress (id, documentId, locator, percent, updatedAt, " +
                    "localUpdatedAt, syncedAt, finishedAt, abandonedAt) " +
                    "VALUES (22, 22, 'loc2', 0.6, 200, 200, 0, NULL, 12345)"
            )
            db.query("SELECT abandonedAt FROM progress WHERE id=22").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getLong(0)).isEqualTo(12345L)
            }
        }
    }

    @Test fun `migrate 6 to 8 chains via book_insights and abandonedAt`() {
        // v6 fixture: seed a documents row + a progress row. book_insights
        // does NOT exist at v6 (added by MIGRATION_6_7).
        helper.createDatabase(DB, 6).use { db ->
            db.execSQL(
                "INSERT INTO documents (id, metadataId, contentHash, title, author, downloadUrl, " +
                    "localPath, coverPath, downloadedAt, seriesName, seriesIndex, librarySyncedAt) " +
                    "VALUES (31, 'm31', 'h31', 'Pre-η title', NULL, 'u', 'p', NULL, 41, NULL, NULL, NULL)"
            )
            db.execSQL(
                "INSERT INTO progress (id, documentId, locator, percent, updatedAt, " +
                    "localUpdatedAt, syncedAt, finishedAt) " +
                    "VALUES (31, 31, 'loc', 0.7, 100, 100, 0, NULL)"
            )
        }

        helper.runMigrationsAndValidate(
            DB,
            8,
            true,
            EReaderDatabase.MIGRATION_6_7,
            EReaderDatabase.MIGRATION_7_8,
        ).use { db ->
            // book_insights (from MIGRATION_6_7) exists.
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='book_insights'").use { c ->
                assertThat(c.moveToFirst()).isTrue()
            }
            // progress.abandonedAt (from MIGRATION_7_8) exists.
            db.query("PRAGMA table_info('progress')").use { c ->
                val cols = mutableSetOf<String>()
                while (c.moveToNext()) cols += c.getString(c.getColumnIndexOrThrow("name"))
                assertThat(cols).contains("abandonedAt")
            }
            // Pre-existing v6 rows survive both additive migrations.
            db.query("SELECT COUNT(*) FROM documents WHERE id=31").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getInt(0)).isEqualTo(1)
            }
            db.query("SELECT abandonedAt FROM progress WHERE id=31").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.isNull(0)).isTrue()
            }
        }
    }

    @Test fun `migrate 8 to 9 stamps identityHashVersion=1 on documents and book_insights`() {
        // v8 fixture: seed one row in `documents` and one in `book_insights`
        // (both tables exist at v8). identityHashVersion does NOT exist yet.
        helper.createDatabase(DB, 8).use { db ->
            db.execSQL(
                "INSERT INTO documents (id, metadataId, contentHash, title, author, downloadUrl, " +
                    "localPath, coverPath, downloadedAt, seriesName, seriesIndex, librarySyncedAt) " +
                    "VALUES (41, 'm41', 'h41', 'Pre-F2 title', NULL, 'u', 'p', NULL, 51, NULL, NULL, NULL)"
            )
            db.execSQL(
                "INSERT INTO book_insights (identityKey, metadataId, contentHash, modelId, " +
                    "promptVersion, tone, language, payloadJson, sourcesJson, schemaVersion, " +
                    "serverId, generatedAt, syncedAt) VALUES " +
                    "('m41', 'm41', 'h41', 'llama3.1', '4', 'neutral', 'auto', '{}', '[]', 4, " +
                    "100, 1000, 1100)"
            )
        }

        helper.runMigrationsAndValidate(DB, 9, true, EReaderDatabase.MIGRATION_8_9).use { db ->
            // Both tables gained the column.
            for (table in listOf("documents", "book_insights")) {
                db.query("PRAGMA table_info('$table')").use { c ->
                    val cols = mutableSetOf<String>()
                    while (c.moveToNext()) cols += c.getString(c.getColumnIndexOrThrow("name"))
                    assertThat(cols).contains("identityHashVersion")
                }
            }
            // Existing rows backfill to 1 via the DEFAULT clause.
            db.query("SELECT identityHashVersion FROM documents WHERE id=41").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getInt(0)).isEqualTo(1)
            }
            db.query(
                "SELECT identityHashVersion FROM book_insights " +
                    "WHERE identityKey='m41' AND modelId='llama3.1' AND promptVersion='4'"
            ).use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getInt(0)).isEqualTo(1)
            }
            // New rows round-trip a non-default value.
            db.execSQL(
                "INSERT INTO documents (id, metadataId, contentHash, title, author, downloadUrl, " +
                    "localPath, coverPath, downloadedAt, seriesName, seriesIndex, librarySyncedAt, " +
                    "identityHashVersion) " +
                    "VALUES (42, 'm42', 'h42', 'Post-F2 title', NULL, 'u', 'p', NULL, 52, NULL, " +
                    "NULL, NULL, 2)"
            )
            db.query("SELECT identityHashVersion FROM documents WHERE id=42").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getInt(0)).isEqualTo(2)
            }
            db.execSQL(
                "INSERT INTO book_insights (identityKey, metadataId, contentHash, modelId, " +
                    "promptVersion, tone, language, payloadJson, sourcesJson, schemaVersion, " +
                    "serverId, generatedAt, syncedAt, identityHashVersion) VALUES " +
                    "('m42', 'm42', 'h42', 'llama3.1', '4', 'neutral', 'auto', '{}', '[]', 4, " +
                    "200, 2000, 2100, 3)"
            )
            db.query(
                "SELECT identityHashVersion FROM book_insights " +
                    "WHERE identityKey='m42' AND modelId='llama3.1' AND promptVersion='4'"
            ).use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getInt(0)).isEqualTo(3)
            }
        }
    }

    @Test fun `migrate 6 to 9 chains all additive migrations`() {
        // Long-tail upgrade path: a v6 install (pre-PR-η) jumps straight to
        // v9 (post-F-2). Every additive migration in between must survive.
        helper.createDatabase(DB, 6).use { db ->
            db.execSQL(
                "INSERT INTO documents (id, metadataId, contentHash, title, author, downloadUrl, " +
                    "localPath, coverPath, downloadedAt, seriesName, seriesIndex, librarySyncedAt) " +
                    "VALUES (51, 'm51', 'h51', 'Pre-F2 chain title', NULL, 'u', 'p', NULL, 61, NULL, NULL, NULL)"
            )
            db.execSQL(
                "INSERT INTO progress (id, documentId, locator, percent, updatedAt, " +
                    "localUpdatedAt, syncedAt, finishedAt) " +
                    "VALUES (51, 51, 'loc', 0.8, 100, 100, 0, NULL)"
            )
        }

        helper.runMigrationsAndValidate(
            DB,
            9,
            true,
            EReaderDatabase.MIGRATION_6_7,
            EReaderDatabase.MIGRATION_7_8,
            EReaderDatabase.MIGRATION_8_9,
        ).use { db ->
            // F-2 column landed on both tables.
            db.query("SELECT identityHashVersion FROM documents WHERE id=51").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getInt(0)).isEqualTo(1)
            }
            db.query("PRAGMA table_info('book_insights')").use { c ->
                val cols = mutableSetOf<String>()
                while (c.moveToNext()) cols += c.getString(c.getColumnIndexOrThrow("name"))
                assertThat(cols).contains("identityHashVersion")
            }
            // Earlier additive migrations still present.
            db.query("PRAGMA table_info('progress')").use { c ->
                val cols = mutableSetOf<String>()
                while (c.moveToNext()) cols += c.getString(c.getColumnIndexOrThrow("name"))
                assertThat(cols).contains("abandonedAt")
            }
        }
    }

    private companion object { const val DB = "migration-test.db" }
}
