package com.example.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SignalWifiConnectedNoInternet4
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.DownloadedSong
import com.example.data.local.MusicDatabase
import com.example.data.repository.MusicRepository
import com.example.network.NetworkMonitor
import com.example.service.MusicDownloadManager
import com.example.service.MusicPlaybackService
import com.example.ui.theme.MusicCyanSecondary
import com.example.ui.theme.MusicDarkBackground
import com.example.ui.theme.MusicDarkSurfaceContainer
import com.example.ui.theme.MusicPinkTertiary
import com.example.ui.theme.MusicVioletPrimary
import kotlinx.coroutines.launch

const val DEFAULT_MUSIC_URL = "https://music.lancebuddy.in"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LanceBuddyMusicScreen(
    onToggleKeepScreenOn: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Database and Repository
    val database = remember { MusicDatabase.getDatabase(context) }
    val repository = remember { MusicRepository(database.downloadedSongDao()) }
    val downloadManager = remember { MusicDownloadManager(context, repository) }

    // Reactive states
    val downloadedSongs by repository.downloadedSongs.collectAsStateWithLifecycle(initialValue = emptyList())
    val playbackState by MusicPlaybackService.playbackState.collectAsStateWithLifecycle()

    val networkMonitor = remember { NetworkMonitor(context) }
    val isOnline by networkMonitor.isOnline.collectAsState(initial = true)

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(DEFAULT_MUSIC_URL) }
    var pageTitle by remember { mutableStateOf("LanceBuddy Music") }
    var isLoading by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0.1f) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var showDownloadedSheet by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }

    var fileUploadCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var customView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    var lastBackPressTime by remember { mutableStateOf(0L) }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (fileUploadCallback != null) {
            val results: Array<Uri>? = if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.let { data ->
                    data.data?.let { arrayOf(it) } ?: data.clipData?.let { clipData ->
                        val uris = mutableListOf<Uri>()
                        for (i in 0 until clipData.itemCount) {
                            uris.add(clipData.getItemAt(i).uri)
                        }
                        uris.toTypedArray()
                    }
                }
            } else {
                null
            }
            fileUploadCallback?.onReceiveValue(results)
            fileUploadCallback = null
        }
    }

    // Back handler
    BackHandler {
        if (showDownloadedSheet) {
            showDownloadedSheet = false
        } else if (customView != null) {
            customViewCallback?.onCustomViewHidden()
            customView = null
            customViewCallback = null
        } else if (webViewRef?.canGoBack() == true) {
            webViewRef?.goBack()
        } else {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBackPressTime < 2000) {
                (context as? android.app.Activity)?.finish()
            } else {
                lastBackPressTime = currentTime
                scope.launch {
                    snackbarHostState.showSnackbar("Press back again to exit")
                }
            }
        }
    }

    // Download handler function
    fun startDownload(url: String, suggestedTitle: String? = null, suggestedArtist: String? = null) {
        scope.launch {
            isDownloading = true
            downloadProgress = 0f
            snackbarHostState.showSnackbar("Downloading track to 'Downloaded' playlist...")

            val result = downloadManager.downloadSong(
                url = url,
                suggestedTitle = suggestedTitle ?: pageTitle.takeIf { !it.contains("LanceBuddy", ignoreCase = true) },
                suggestedArtist = suggestedArtist ?: "LanceBuddy Music",
                onProgress = { p -> downloadProgress = p }
            )

            isDownloading = false
            result.onSuccess { song ->
                snackbarHostState.showSnackbar("Saved '${song.title}' to Downloaded playlist!")
            }.onFailure { err ->
                snackbarHostState.showSnackbar("Download failed: ${err.localizedMessage}")
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MusicDarkBackground
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {

                // Offline Notice Bar
                AnimatedVisibility(visible = !isOnline) {
                    Surface(
                        color = Color(0xFFB91C1C),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Wifi,
                                    contentDescription = "Offline",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Offline Mode - You can play from 'Downloaded' playlist",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = Color.White,
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                            }
                            TextButton(
                                onClick = { showDownloadedSheet = true },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = Color.White
                                )
                            ) {
                                Text("Open", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Loading Bar
                AnimatedVisibility(
                    visible = isLoading && progress < 1f,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MusicPinkTertiary,
                        trackColor = Color.Transparent
                    )
                }

                // Main Content (WebView & Overlays)
                Box(modifier = Modifier.weight(1f)) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                isVerticalScrollBarEnabled = true
                                isHorizontalScrollBarEnabled = false

                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    mediaPlaybackRequiresUserGesture = false
                                    allowFileAccess = true
                                    allowContentAccess = true
                                    loadWithOverviewMode = true
                                    useWideViewPort = true
                                    builtInZoomControls = true
                                    displayZoomControls = false
                                    setSupportZoom(true)
                                    cacheMode = WebSettings.LOAD_DEFAULT
                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                    userAgentString = settings.userAgentString + " LanceBuddyMusicNativeApp/1.1"
                                }

                                // Intercept audio file downloads to save into 'Downloaded' playlist
                                setDownloadListener { url, _, _, _, _ ->
                                    startDownload(url)
                                }

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        progress = newProgress / 100f
                                        isLoading = newProgress < 100
                                    }

                                    override fun onReceivedTitle(view: WebView?, title: String?) {
                                        if (!title.isNullOrBlank() && !title.contains("http", ignoreCase = true)) {
                                            pageTitle = title
                                        }
                                    }

                                    override fun onPermissionRequest(request: PermissionRequest?) {
                                        request?.grant(request.resources)
                                    }

                                    override fun onShowFileChooser(
                                        webView: WebView?,
                                        filePathCallback: ValueCallback<Array<Uri>>?,
                                        fileChooserParams: FileChooserParams?
                                    ): Boolean {
                                        fileUploadCallback?.onReceiveValue(null)
                                        fileUploadCallback = filePathCallback
                                        val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                            type = "*/*"
                                            addCategory(Intent.CATEGORY_OPENABLE)
                                        }
                                        try {
                                            filePickerLauncher.launch(intent)
                                            return true
                                        } catch (e: Exception) {
                                            fileUploadCallback = null
                                            return false
                                        }
                                    }

                                    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                                        customView = view
                                        customViewCallback = callback
                                    }

                                    override fun onHideCustomView() {
                                        customViewCallback?.onCustomViewHidden()
                                        customView = null
                                        customViewCallback = null
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        isLoading = true
                                        url?.let { currentUrl = it }
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        isLoading = false
                                        url?.let { currentUrl = it }
                                        MusicPlaybackService.startWebBackground(ctx)
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?
                                    ) {
                                        if (request?.isForMainFrame == true) {
                                            hasError = true
                                            errorMessage = error?.description?.toString() ?: "Failed to connect to music server."
                                        }
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val uri = request?.url ?: return false
                                        val scheme = uri.scheme ?: return false
                                        val path = uri.toString().lowercase()

                                        if (path.endsWith(".mp3") || path.endsWith(".m4a") || path.endsWith(".wav") || path.endsWith(".flac")) {
                                            startDownload(uri.toString())
                                            return true
                                        }

                                        if (scheme == "http" || scheme == "https") {
                                            return false
                                        }

                                        return try {
                                            val intent = Intent(Intent.ACTION_VIEW, uri)
                                            ctx.startActivity(intent)
                                            true
                                        } catch (_: Exception) {
                                            true
                                        }
                                    }
                                }

                                loadUrl(DEFAULT_MUSIC_URL)
                                webViewRef = this
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Error Screen overlay
                    if (hasError) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MusicDarkBackground
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(80.dp)
                                        .clip(CircleShape)
                                        .background(MusicDarkSurfaceContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SignalWifiConnectedNoInternet4,
                                        contentDescription = "Connection Error",
                                        tint = MusicPinkTertiary,
                                        modifier = Modifier.size(40.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(20.dp))

                                Text(
                                    text = "Cannot Load LanceBuddy Music",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = errorMessage ?: "Unable to connect to https://music.lancebuddy.in. You can still listen to your Downloaded playlist offline.",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )

                                Spacer(modifier = Modifier.height(24.dp))

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            hasError = false
                                            errorMessage = null
                                            webViewRef?.reload()
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MusicVioletPrimary
                                        ),
                                        modifier = Modifier.testTag("retry_button")
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Retry")
                                    }

                                    Button(
                                        onClick = { showDownloadedSheet = true },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MusicDarkSurfaceContainer
                                        ),
                                        modifier = Modifier.testTag("open_downloaded_from_error")
                                    ) {
                                        Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = MusicCyanSecondary, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Downloaded Playlist", color = Color.White)
                                    }
                                }
                            }
                        }
                    }

                    // Fullscreen Video / Media Overlay
                    if (customView != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                        ) {
                            AndroidView(
                                factory = {
                                    FrameLayout(it).apply {
                                        customView?.let { cv ->
                                            (cv.parent as? ViewGroup)?.removeView(cv)
                                            addView(
                                                cv,
                                                FrameLayout.LayoutParams(
                                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                                    FrameLayout.LayoutParams.MATCH_PARENT
                                                )
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )

                            IconButton(
                                onClick = {
                                    customViewCallback?.onCustomViewHidden()
                                    customView = null
                                    customViewCallback = null
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(16.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.6f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Exit Fullscreen",
                                    tint = Color.White
                                )
                            }
                        }
                    }

                    // Floating Downloaded Playlist Button
                    FloatingActionButton(
                        onClick = { showDownloadedSheet = true },
                        containerColor = MusicVioletPrimary,
                        contentColor = Color.White,
                        elevation = FloatingActionButtonDefaults.elevation(6.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = if (playbackState.currentSong != null) 76.dp else 16.dp)
                            .testTag("fab_downloaded_playlist")
                    ) {
                        BadgedBox(
                            badge = {
                                if (downloadedSongs.isNotEmpty()) {
                                    Badge(
                                        containerColor = MusicPinkTertiary,
                                        contentColor = Color.White
                                    ) {
                                        Text("${downloadedSongs.size}")
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.LibraryMusic,
                                contentDescription = "Downloaded Playlist",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                // Mini Player Bar (for active offline song playback & background controls)
                MiniPlayerBar(
                    playbackState = playbackState,
                    onTogglePlayPause = { MusicPlaybackService.togglePlayPause(context) },
                    onNext = { MusicPlaybackService.skipNext(context) },
                    onPrev = { MusicPlaybackService.skipPrev(context) },
                    onClickBar = { showDownloadedSheet = true }
                )
            }

            // Snackbar Host
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (playbackState.currentSong != null) 80.dp else 16.dp)
            )
        }
    }

    // Downloaded Playlist Sheet Modal
    DownloadedPlaylistSheet(
        isOpen = showDownloadedSheet,
        onDismiss = { showDownloadedSheet = false },
        songs = downloadedSongs,
        playbackState = playbackState,
        onPlaySong = { song ->
            MusicPlaybackService.playSong(context, song, downloadedSongs)
        },
        onPlayAll = {
            if (downloadedSongs.isNotEmpty()) {
                MusicPlaybackService.playSong(context, downloadedSongs.first(), downloadedSongs)
            }
        },
        onDeleteSong = { song ->
            scope.launch {
                repository.deleteSong(song)
                snackbarHostState.showSnackbar("Removed '${song.title}' from Downloaded")
            }
        },
        onDownloadManualUrl = { url, title ->
            startDownload(url, suggestedTitle = title.takeIf { it.isNotBlank() })
        },
        isDownloading = isDownloading,
        downloadProgress = downloadProgress
    )
}
