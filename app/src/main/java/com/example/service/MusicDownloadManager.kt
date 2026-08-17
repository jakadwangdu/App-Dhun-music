package com.example.service

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.example.data.local.DownloadedSong
import com.example.data.repository.MusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class MusicDownloadManager(
    private val context: Context,
    private val repository: MusicRepository
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val downloadsDir: File by lazy {
        val dir = File(context.filesDir, "music_downloads")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    suspend fun downloadSong(
        url: String,
        suggestedTitle: String? = null,
        suggestedArtist: String? = null,
        onProgress: (Float) -> Unit = {}
    ): Result<DownloadedSong> = withContext(Dispatchers.IO) {
        try {
            val existing = repository.getSongByUrl(url)
            if (existing != null && File(existing.localFilePath).exists()) {
                return@withContext Result.success(existing)
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) LanceBuddyMusic/1.1")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP error code: ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Response body is empty"))
            val contentLength = body.contentLength()

            // Generate clean filename
            val rawName = suggestedTitle ?: Uri.parse(url).lastPathSegment ?: "track_${System.currentTimeMillis()}"
            val cleanName = rawName.replace("[^a-zA-Z0-9.-]".toRegex(), "_")
            val extension = if (cleanName.contains(".")) "" else ".mp3"
            val targetFile = File(downloadsDir, "${cleanName}_${System.currentTimeMillis()}$extension")

            body.byteStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (contentLength > 0) {
                            val progress = (totalRead.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f)
                            onProgress(progress)
                        }
                    }
                    output.flush()
                }
            }

            // Extract metadata
            var duration = 0L
            var finalTitle = suggestedTitle ?: targetFile.nameWithoutExtension
            var finalArtist = suggestedArtist ?: "LanceBuddy Music"

            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(targetFile.absolutePath)
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                durStr?.toLongOrNull()?.let { duration = it }
                val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                if (!metaTitle.isNullOrBlank()) {
                    finalTitle = metaTitle
                }
                val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                if (!metaArtist.isNullOrBlank()) {
                    finalArtist = metaArtist
                }
                retriever.release()
            } catch (_: Exception) {}

            val downloadedSong = DownloadedSong(
                title = finalTitle,
                artist = finalArtist,
                sourceUrl = url,
                localFilePath = targetFile.absolutePath,
                fileSizeBytes = targetFile.length(),
                durationMs = duration,
                playlistName = "Downloaded",
                downloadedAt = System.currentTimeMillis()
            )

            val insertedId = repository.insertSong(downloadedSong)
            Result.success(downloadedSong.copy(id = insertedId))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
