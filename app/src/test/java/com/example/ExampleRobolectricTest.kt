package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DownloadedSong
import com.example.data.local.MusicDatabase
import com.example.data.repository.MusicRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var db: MusicDatabase
    private lateinit var repository: MusicRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MusicRepository(db.downloadedSongDao())
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("LanceBuddy Music", appName)
    }

    @Test
    fun `insert and retrieve downloaded song from playlist`() = runBlocking {
        val song = DownloadedSong(
            title = "Test Song",
            artist = "LanceBuddy",
            sourceUrl = "https://music.lancebuddy.in/stream/123",
            localFilePath = "/tmp/test.mp3",
            fileSizeBytes = 1024 * 1024,
            durationMs = 180000,
            playlistName = "Downloaded"
        )
        val id = repository.insertSong(song)
        val songs = repository.downloadedSongs.first()

        assertEquals(1, songs.size)
        assertEquals("Test Song", songs[0].title)
        assertEquals("Downloaded", songs[0].playlistName)
        assertEquals(id, songs[0].id)
    }

    @Test
    fun `delete song from downloaded playlist`() = runBlocking {
        val song = DownloadedSong(
            title = "Song To Delete",
            artist = "Artist",
            localFilePath = "/tmp/test_delete.mp3",
            playlistName = "Downloaded"
        )
        val id = repository.insertSong(song)
        repository.deleteSongById(id, "/tmp/test_delete.mp3")

        val songs = repository.downloadedSongs.first()
        assertEquals(0, songs.size)
    }
}
