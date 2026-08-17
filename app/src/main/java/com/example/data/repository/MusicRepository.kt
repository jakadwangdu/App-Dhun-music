package com.example.data.repository

import com.example.data.local.DownloadedSong
import com.example.data.local.DownloadedSongDao
import kotlinx.coroutines.flow.Flow
import java.io.File

class MusicRepository(private val dao: DownloadedSongDao) {
    val downloadedSongs: Flow<List<DownloadedSong>> = dao.getSongsByPlaylist("Downloaded")

    suspend fun insertSong(song: DownloadedSong): Long = dao.insertSong(song)

    suspend fun deleteSong(song: DownloadedSong) {
        try {
            val file = File(song.localFilePath)
            if (file.exists()) {
                file.delete()
            }
        } catch (_: Exception) {}
        dao.deleteSong(song)
    }

    suspend fun deleteSongById(id: Long, filePath: String?) {
        if (!filePath.isNullOrBlank()) {
            try {
                val file = File(filePath)
                if (file.exists()) {
                    file.delete()
                }
            } catch (_: Exception) {}
        }
        dao.deleteSongById(id)
    }

    suspend fun getSongByUrl(url: String): DownloadedSong? = dao.getSongByUrl(url)
}
