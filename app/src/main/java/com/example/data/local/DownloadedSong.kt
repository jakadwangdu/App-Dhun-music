package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloaded_songs")
data class DownloadedSong(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val artist: String = "LanceBuddy Music",
    val sourceUrl: String = "",
    val localFilePath: String,
    val fileSizeBytes: Long = 0,
    val durationMs: Long = 0,
    val playlistName: String = "Downloaded",
    val downloadedAt: Long = System.currentTimeMillis()
)
