package io.theficos.ereader.data.local.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
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

    @Test fun `production database builder upgrades version two with reading data intact`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val filename = "ereader.db"
        context.deleteDatabase(filename)
        helper.createDatabase(filename, 2).use { old ->
            old.execSQL("INSERT INTO documents (id, metadataId, contentHash, title, author, downloadUrl, localPath, coverPath, downloadedAt) VALUES (1, NULL, 'h', 'Book', NULL, 'u', 'p', NULL, 0)")
            old.execSQL("INSERT INTO progress (id, documentId, locator, percent, updatedAt) VALUES (1, 1, 'loc', 0.5, 42)")
        }

        val db = EReaderDatabase.build(context)
        try {
            val sqlite = try {
                db.openHelper.writableDatabase
            } catch (e: IllegalStateException) {
                throw AssertionError("The production Room builder must open existing version-two data", e)
            }
            assertThat(sqlite.version).isEqualTo(15)
            assertThat(db.documentDao().findById(1)?.title).isEqualTo("Book")
            val progress = checkNotNull(db.progressDao().findByDocument(1))
            assertThat(progress.locator).isEqualTo("loc")
            assertThat(progress.localUpdatedAt).isEqualTo(42L)
        } finally {
            db.close()
            context.deleteDatabase(filename)
        }
    }

    @Test fun `migrate 11 to 12 preserves scoped highlights progress and adds local bookmark cascade`() {
        helper.createDatabase(DB, 11).use { db ->
            db.execSQL("INSERT INTO documents (id, metadataId, contentHash, title, author, downloadUrl, localPath, coverPath, downloadedAt) VALUES (91, NULL, 'h91', 'Book', NULL, 'u', 'p', NULL, 0)")
            db.execSQL("INSERT INTO progress (id, documentId, locator, percent, updatedAt, localUpdatedAt, syncedAt) VALUES (91, 91, 'original', 0.5, 42, 42, 42)")
            db.execSQL("INSERT INTO highlights (ownerScope, id, contentHash, locator, text, colour, bookTitle, createdAt, updatedAt, syncedAt, deletedAt) VALUES ('account', 'kept', 'h91', 'exact-highlight', 'Retained quote', 'GREEN', 'Book', 10, 20, 20, 20)")
            db.execSQL("INSERT INTO sync_state VALUES ('highlights:account', 123)")
        }
        helper.runMigrationsAndValidate(DB, 12, true, EReaderDatabase.MIGRATION_11_12).use { db ->
            db.query("SELECT locator, syncedAt FROM progress WHERE documentId=91").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(0)).isEqualTo("original")
                assertThat(c.getLong(1)).isEqualTo(42L)
            }
            db.query("SELECT locator, text, ownerScope, syncedAt, deletedAt FROM highlights WHERE id='kept'").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(0)).isEqualTo("exact-highlight")
                assertThat(c.getString(1)).isEqualTo("Retained quote")
                assertThat(c.getString(2)).isEqualTo("account")
                assertThat(c.getLong(3)).isEqualTo(20L)
                assertThat(c.getLong(4)).isEqualTo(20L)
            }
            db.query("SELECT lastPulledAt FROM sync_state WHERE tableName='highlights:account'").use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getLong(0)).isEqualTo(123L)
            }
            db.execSQL("INSERT INTO memory_bookmarks VALUES (91, 'owner', 'locator', 'question', 'passage', 100)")
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL("DELETE FROM documents WHERE id=91")
            db.query("SELECT * FROM memory_bookmarks").use { assertThat(it.count).isEqualTo(0) }
            db.query("SELECT * FROM highlights WHERE id='kept'").use { assertThat(it.count).isEqualTo(1) }
        }
    }

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

    @Test fun `production database builder upgrades version six with both legacy reading rows`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val filename = "ereader.db"
        context.deleteDatabase(filename)
        helper.createDatabase(filename, 6).use { old ->
            for ((id, percent) in listOf(31 to 0.7, 51 to 0.8)) {
                old.execSQL(
                    "INSERT INTO documents (id, metadataId, contentHash, title, author, downloadUrl, " +
                        "localPath, coverPath, downloadedAt, seriesName, seriesIndex, librarySyncedAt) " +
                        "VALUES (?, ?, ?, ?, NULL, 'u', 'p', NULL, ?, NULL, NULL, NULL)",
                    arrayOf(id, "m$id", "h$id", "Legacy book $id", id + 10),
                )
                old.execSQL(
                    "INSERT INTO progress (id, documentId, locator, percent, updatedAt, " +
                        "localUpdatedAt, syncedAt, finishedAt) VALUES (?, ?, 'loc', ?, 100, 100, 0, NULL)",
                    arrayOf(id, id, percent),
                )
            }
        }

        val db = EReaderDatabase.build(context)
        try {
            val sqlite = db.openHelper.writableDatabase
            assertThat(sqlite.version).isEqualTo(15)
            sqlite.query("SELECT identityHashVersion FROM book_insights").use { rows ->
                assertThat(rows.count).isEqualTo(0)
            }
            for ((id, percent) in listOf(31L to 0.7, 51L to 0.8)) {
                assertThat(db.documentDao().findById(id)?.identityHashVersion).isEqualTo(1)
                val saved = checkNotNull(db.progressDao().findByDocument(id))
                assertThat(saved.percent).isEqualTo(percent)
                assertThat(saved.abandonedAt).isNull()
                assertThat(saved.finishedAt).isNull()
            }
        } finally {
            db.close()
            context.deleteDatabase(filename)
        }
    }

    @Test fun `migrate 13 to 14 adds a catalogId that is null for every existing book`() {
        helper.createDatabase(DB, 13).use { db ->
            db.execSQL(
                "INSERT INTO documents (id, metadataId, contentHash, title, author, downloadUrl, localPath, coverPath, downloadedAt, identityHashVersion) " +
                    "VALUES (3, 'm3', 'h3', 'Before', NULL, 'u', 'p', NULL, 30, 1)"
            )
        }

        helper.runMigrationsAndValidate(DB, 14, true, EReaderDatabase.MIGRATION_13_14).use { db ->
            db.query("SELECT title, catalogId FROM documents WHERE id=3").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(0)).isEqualTo("Before")
                assertThat(c.isNull(1)).isTrue()
            }
            db.execSQL(
                "INSERT INTO documents (id, metadataId, contentHash, title, author, downloadUrl, localPath, coverPath, downloadedAt, identityHashVersion, catalogId) " +
                    "VALUES (4, 'm4', 'h4', 'After', NULL, 'u', 'p', NULL, 40, 1, 'c0ffee00-0000-4000-8000-000000000001')"
            )
            db.query("SELECT catalogId FROM documents WHERE id=4").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(0)).isEqualTo("c0ffee00-0000-4000-8000-000000000001")
            }
        }
    }

    @Test fun `migrate 14 to 15 adds book details beside every book, untouched`() {
        helper.createDatabase(DB, 14).use { db ->
            db.execSQL(
                "INSERT INTO documents (id, metadataId, contentHash, title, author, downloadUrl, localPath, coverPath, downloadedAt, identityHashVersion) " +
                    "VALUES (5, 'm5', 'h5', 'dune_frank_herbert', NULL, 'u', 'p', NULL, 50, 1)"
            )
        }

        helper.runMigrationsAndValidate(DB, 15, true, EReaderDatabase.MIGRATION_14_15).use { db ->
            db.query("SELECT COUNT(*) FROM book_details").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getInt(0)).isEqualTo(0)
            }
            db.execSQL(
                "INSERT INTO book_details (documentId, responseJson, coverPath, useOriginal, retryAt) " +
                    "VALUES (5, '{}', NULL, 0, NULL)"
            )
            db.query("SELECT d.title, b.responseJson FROM documents d JOIN book_details b ON b.documentId = d.id").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(0)).isEqualTo("dune_frank_herbert")
                assertThat(c.getString(1)).isEqualTo("{}")
            }
        }
    }

    private companion object { const val DB = "migration-test.db" }
}
