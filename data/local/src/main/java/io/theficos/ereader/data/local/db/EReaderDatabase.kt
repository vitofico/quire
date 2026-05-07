package io.theficos.ereader.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DocumentEntity::class, ProgressEntity::class, SyncStateEntity::class],
    version = 3,
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

        fun build(context: Context): EReaderDatabase =
            Room.databaseBuilder(context, EReaderDatabase::class.java, "ereader.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
