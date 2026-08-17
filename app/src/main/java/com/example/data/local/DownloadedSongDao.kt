package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadedSongDao {
    @Query("SELECT * FROM downloaded_songs WHERE playlistName = :playlistName ORDER BY downloadedAt DESC")
    fun getSongsByPlaylist(playlistName: String = "Downloads"): Flow<List<DownloadedSong>>

    @Query("SELECT * FROM downloaded_songs ORDER BY downloadedAt DESC")
    fun getAllDownloadedSongs(): Flow<List<DownloadedSong>>

    @Query("SELECT * FROM downloaded_songs WHERE id = :id LIMIT 1")
    suspend fun getSongById(id: Long): DownloadedSong?

    @Query("SELECT * FROM downloaded_songs WHERE sourceUrl = :url LIMIT 1")
    suspend fun getSongByUrl(url: String): DownloadedSong?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: DownloadedSong): Long

    @Update
    suspend fun updateSong(song: DownloadedSong)

    @Delete
    suspend fun deleteSong(song: DownloadedSong)

    @Query("DELETE FROM downloaded_songs WHERE id = :id")
    suspend fun deleteSongById(id: Long)
}
