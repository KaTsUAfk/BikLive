// MainActivity.kt
package com.bik_live

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.util.Util
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppContent()
        }
    }

    @Composable
    fun AppContent() {
        val view = LocalView.current
        DisposableEffect(Unit) {
            hideSystemUI(view)
            onDispose { }
        }

        MaterialTheme {
            StreamingAppUI()
        }
    }

    private fun hideSystemUI(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, view).let { controller ->
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Composable
    fun StreamingAppUI() {
        val context = LocalContext.current
        val view = LocalView.current
        val exoPlayer = remember {
            ExoPlayer.Builder(context).build().apply {
                // Настраиваем бесконечное повторение
                repeatMode = Player.REPEAT_MODE_ALL
            }
        }
        var isLoading by remember { mutableStateOf(true) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var retryTrigger by remember { mutableStateOf(0) }
        var isPlaying by remember { mutableStateOf(false) }
        var debugInfo by remember { mutableStateOf("") }

        // Пробуем разные форматы
        val streamsToTry = listOf(
            "http://109.195.134.244:8096/stream.m3u8", // HLS
            "http://109.195.134.244:8096/input.mp4",   // Прямое MP4
            "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8" // Резервный
        )

        // Скрываем системный UI
        LaunchedEffect(Unit) {
            hideSystemUI(view)
        }

        // Запускаем потоки с диагностикой
        LaunchedEffect(retryTrigger) {
            if (retryTrigger > 0) {
                exoPlayer.stop()
                errorMessage = null
                debugInfo = ""
            }

            debugInfo += "Начинаем проверку потоков...\n"

            for ((index, streamUrl) in streamsToTry.withIndex()) {
                debugInfo += "Попытка $index: $streamUrl\n"

                // Сначала проверяем доступность
                val isAvailable = checkStreamAvailability(streamUrl)
                debugInfo += "Доступность: $isAvailable\n"

                if (isAvailable) {
                    try {
                        initializePlayer(exoPlayer, context, streamUrl)
                        // Ждем 5 секунд для подключения
                        for (i in 1..5) {
                            delay(1000)
                            if (exoPlayer.playbackState == Player.STATE_READY) {
                                debugInfo += "Успешно подключились к: $streamUrl\n"
                                return@LaunchedEffect
                            }
                        }
                        exoPlayer.stop()
                        debugInfo += "Таймаут подключения к: $streamUrl\n"
                    } catch (e: Exception) {
                        debugInfo += "Ошибка подключения: ${e.message}\n"
                    }
                } else {
                    debugInfo += "Поток недоступен, пропускаем\n"
                }
            }

            errorMessage = "Все потоки недоступны\n$debugInfo"
        }

        DisposableEffect(Unit) {
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    isLoading = state == Player.STATE_BUFFERING
                    isPlaying = state == Player.STATE_READY
                    if (state == Player.STATE_READY) {
                        errorMessage = null
                    }

                    // Автоматически перезапускаем при окончании видео
                    if (state == Player.STATE_ENDED) {
                        exoPlayer.seekTo(0)
                        exoPlayer.playWhenReady = true
                    }
                }

                override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
                    if (!isPlaying) {
                        errorMessage = "Ошибка плеера: ${error.errorCodeName}\n${error.message}\n$debugInfo"
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    // Автоматически перезапускаем если видео остановилось
                    if (!isPlaying && exoPlayer.playbackState == Player.STATE_ENDED) {
                        exoPlayer.seekTo(0)
                        exoPlayer.playWhenReady = true
                    }
                }
            }

            exoPlayer.addListener(listener)
            onDispose {
                exoPlayer.removeListener(listener)
                exoPlayer.release()
            }
        }

        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { context ->
                        PlayerView(context).apply {
                            this.player = exoPlayer
                            useController = false // Отключаем контроллер для чистого видео
                            setBackgroundColor(Color.Black.hashCode())
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (isLoading && !isPlaying && errorMessage == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center).size(60.dp),
                        color = Color.White
                    )
                }

                if (errorMessage != null && !isPlaying) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color.Black.copy(alpha = 0.8f))
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = errorMessage ?: "Неизвестная ошибка",
                            color = Color.Red,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 16.dp),
                            fontSize = 10.sp
                        )
                        Button(
                            onClick = { retryTrigger++ }
                        ) {
                            Text("Повторить")
                        }
                    }
                }

                // Копирайт всегда виден поверх видео
                Text(
                    text = "©2025 SicretHOME",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)

                )
            }
        }
    }

    private suspend fun checkStreamAvailability(streamUrl: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(streamUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val responseCode = connection.responseCode
                connection.disconnect()

                responseCode == 200
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun initializePlayer(
        player: ExoPlayer,
        context: android.content.Context,
        streamUrl: String
    ) {
        try {
            player.stop()
            player.clearMediaItems()

            val dataSourceFactory = DefaultHttpDataSource.Factory().apply {
                setUserAgent(Util.getUserAgent(context, "BikLiveApp"))
                setAllowCrossProtocolRedirects(true)
            }

            val mediaItem = MediaItem.fromUri(streamUrl)

            if (streamUrl.endsWith(".m3u8")) {
                val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem)
                player.setMediaSource(mediaSource)
            } else {
                val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem)
                player.setMediaSource(mediaSource)
            }

            // Настраиваем бесконечное повторение
            player.repeatMode = Player.REPEAT_MODE_ALL
            player.playWhenReady = true
            player.prepare()

        } catch (e: Exception) {
            throw e
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI(window.decorView)
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI(window.decorView)
        // При возобновлении активности перезапускаем видео
        val exoPlayer = ExoPlayer.Builder(this).build()
        exoPlayer.seekTo(0)
        exoPlayer.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        // При паузе останавливаем видео чтобы сохранить батарею
        val exoPlayer = ExoPlayer.Builder(this).build()
        exoPlayer.playWhenReady = false
    }
}