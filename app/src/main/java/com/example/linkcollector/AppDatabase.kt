package com.example.linkcollector

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [HistoryItem::class, FavoriteItem::class],
    version = 2,  // ★ FavoriteItem 追加のため 1 → 2 に上げる
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun historyDao(): HistoryDao
    abstract fun favoriteDao(): FavoriteDao  // ★ 追加

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "link_collector_database"
                )
                    .fallbackToDestructiveMigration() // ★ バージョンアップ時に既存DBを作り直す
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
