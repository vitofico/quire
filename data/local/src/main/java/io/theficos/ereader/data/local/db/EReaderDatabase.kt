package io.theficos.ereader.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DocumentEntity::class, ProgressEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class EReaderDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun progressDao(): ProgressDao

    companion object {
        fun build(context: Context): EReaderDatabase =
            Room.databaseBuilder(context, EReaderDatabase::class.java, "ereader.db")
                .fallbackToDestructiveMigration() // v1 only — replace with real migrations from v2 onward
                .build()
    }
}
