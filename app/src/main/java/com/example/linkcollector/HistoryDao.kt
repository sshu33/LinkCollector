package com.example.linkcollector

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface HistoryDao {
    // 新しい履歴を一件追加する
    @Insert
    suspend fun insert(historyItem: HistoryItem)

    // 保存されている履歴をすべて、時間の新しい順に取得する
    @Query("SELECT * FROM history_table ORDER BY timestamp DESC")
    suspend fun getAllHistory(): List<HistoryItem>

    @Query("DELETE FROM history_table")
    suspend fun deleteAll()
}