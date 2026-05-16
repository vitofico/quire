package io.theficos.ereader.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DocumentEntity::class, ProgressEntity::class, SyncStateEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class EReaderDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun progressDao(): ProgressDao
    abstract fun syncStateDao(): SyncStateDao

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

        fun build(context: Context): EReaderDatabase =
            Room.databaseBuilder(context, EReaderDatabase::class.java, "ereader.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}
