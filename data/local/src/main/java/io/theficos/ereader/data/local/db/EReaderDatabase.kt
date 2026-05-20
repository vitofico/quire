package io.theficos.ereader.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DocumentEntity::class,
        ProgressEntity::class,
        SyncStateEntity::class,
        InsightEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class EReaderDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun progressDao(): ProgressDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun insightDao(): InsightDao

    companion object {
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN coverPath TEXT")
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE progress ADD COLUMN localUpdatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE progress ADD COLUMN syncedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE progress SET localUpdatedAt = updatedAt")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sync_state (" +
                        "tableName TEXT NOT NULL PRIMARY KEY, " +
                        "lastPulledAt INTEGER NOT NULL)"
                )
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE progress ADD COLUMN finishedAt INTEGER")
            }
        }

        /**
         * Adds seriesName + seriesIndex columns to local `documents` and the
         * composite index that PR8's "Continue your series" shelf uses to find
         * sibling-in-series candidates.
         *
         * Note: PR1 (server-side library_items) was supposed to ship the Android
         * half of this change, but its diff only landed the server migration. PR8
         * picks it up.
         */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN seriesName TEXT")
                db.execSQL("ALTER TABLE documents ADD COLUMN seriesIndex REAL")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_documents_seriesName_seriesIndex " +
                        "ON documents(seriesName, seriesIndex)"
                )
            }
        }

        /**
         * Adds nullable `librarySyncedAt` to `documents`. The `/library/v1/items`
         * uploader treats `NULL` as "not yet uploaded", so this migration leaves
         * the column null for every existing row — the next app start backfills
         * the entire library to the server. No backfill SQL needed; the absence
         * of a value IS the signal.
         */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN librarySyncedAt INTEGER")
            }
        }

        /**
         * PR-η / Lock #8: adds the `book_insights` local cache table backing
         * `InsightEntity` / `InsightDao`. Purely additive — no existing data
         * touched. Indices: syncedAt (status display), metadataId & contentHash
         * (diagnostic lookups), and the composite cursor index over
         * `(generatedAt, serverId)` to make the bulk-sync tip query cheap.
         */
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS book_insights (
                        identityKey TEXT NOT NULL,
                        metadataId TEXT,
                        contentHash TEXT,
                        modelId TEXT NOT NULL,
                        promptVersion TEXT NOT NULL,
                        tone TEXT NOT NULL,
                        language TEXT NOT NULL,
                        payloadJson TEXT NOT NULL,
                        sourcesJson TEXT NOT NULL,
                        schemaVersion INTEGER NOT NULL,
                        serverId INTEGER NOT NULL,
                        generatedAt INTEGER NOT NULL,
                        syncedAt INTEGER NOT NULL,
                        PRIMARY KEY(identityKey, modelId, promptVersion, tone, language)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_book_insights_syncedAt " +
                        "ON book_insights(syncedAt)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_book_insights_metadataId " +
                        "ON book_insights(metadataId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_book_insights_contentHash " +
                        "ON book_insights(contentHash)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_book_insights_cursor " +
                        "ON book_insights(generatedAt, serverId)"
                )
            }
        }

        /**
         * pr-α / Bundle 3 / coordinator §3.16: adds nullable `abandonedAt`
         * column to the `progress` table for the terminal-state invariant.
         * Pairs with the server's `progress_002_abandoned_at` migration.
         * No backfill — Room stores `Long?` as nullable INTEGER and
         * pre-existing rows default to NULL.
         */
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE progress ADD COLUMN abandonedAt INTEGER")
            }
        }

        fun build(context: Context): EReaderDatabase =
            Room.databaseBuilder(context, EReaderDatabase::class.java, "ereader.db")
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                )
                .build()
    }
}
