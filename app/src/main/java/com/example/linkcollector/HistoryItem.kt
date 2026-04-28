package com.example.linkcollector

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "history_table")
data class HistoryItem(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0, // ユニークなID（自動で番号が振られる）

    val filename: String, // ファイル名
    val premiumLink: String, // 変換後のプレミアムリンク
    val timestamp: Long // 保存したときの時間
)