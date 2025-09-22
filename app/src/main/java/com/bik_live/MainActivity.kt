package com.bik_live

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.mutableLongStateOf
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
import com.google.android.exoplayer2.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val TAG = "BikLiveApp"
    private lateinit var deviceId: String
    private val syncServerUrl = "http://109.195.134.244:3000/api/sync"
    private lateinit var exoPlayer: ExoPlayer
    private var playbackPosition: Long = 0

    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedPreferences = getSharedPreferences("BikLivePrefs", Context.MODE_PRIVATE)

        deviceId = sharedPreferences.getString("device_id", null) ?: run {
            val newDeviceId = UUID.randomUUID().toString()
            sharedPreferences.edit().putString("device_id", newDeviceId).apply()
            newDeviceId
        }

        Log.d(TAG, "Device ID: $deviceId")

        if (savedInstanceState != null) {
            playbackPosition = savedInstanceState.getLong("playback_position", 0)
        }

        exoPlayer = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
        }

        setContent {
            AppContent(deviceId)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("playback_position", exoPlayer.currentPosition)
    }

    @Composable
    fun AppContent(deviceId: String) {
        val view = LocalView.current
        DisposableEffect(Unit) {
            hideSystemUI(view)
            onDispose { }
        }

        MaterialTheme {
            StreamingAppUI(deviceId = deviceId)
        }
    }

    private fun hideSystemUI(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, view).let { controller ->
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Composable
    fun StreamingAppUI(deviceId: String) {
        val context = LocalContext.current
        val view = LocalView.current

        // 👇 Правильное объявление переменных состояния
        var isLoading by remember { mutableStateOf(true) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var isPlaying by remember { mutableStateOf(false) }
        var isSyncing by remember { mutableStateOf(false) }
        var syncStatus by remember { mutableStateOf("") }
        var lastSeekTime by remember { mutableLongStateOf(0L) } // 👈 Исправлено!

        LaunchedEffect(Unit) {
            hideSystemUI(view)
        }

        DisposableEffect(Unit) {
            val stateListener = object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    isLoading = state == Player.STATE_BUFFERING
                    isPlaying = state == Player.STATE_READY
                }

                override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
                    errorMessage = "Ошибка: ${error.errorCodeName}\n${error.message}"
                    Log.e(TAG, "Player error", error)

                    exoPlayer.playWhenReady = false
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(5000)
                        if (errorMessage != null) {
                            errorMessage = null
                            exoPlayer.stop()
                            exoPlayer.clearMediaItems()
                            val liveStream = "http://109.195.134.244:8096/stream.m3u8"
                            val mediaItem = MediaItem.fromUri(liveStream)
                            exoPlayer.setMediaItem(mediaItem)
                            exoPlayer.prepare()
                            exoPlayer.playWhenReady = true
                            Log.d(TAG, "Auto-restarted after error")
                        }
                    }
                }
            }

            exoPlayer.addListener(stateListener)
            onDispose {
                exoPlayer.removeListener(stateListener)
            }
        }

        suspend fun syncWithServer(): Long? {
            return try {
                isSyncing = true
                syncStatus = "Синхронизация с сервером..."
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()

                val currentPosition = exoPlayer.currentPosition
                val url = "$syncServerUrl?deviceId=$deviceId&position=$currentPosition"
                Log.d(TAG, "Sync URL: $url")

                val request = Request.Builder().url(url).build()
                val response = withContext(Dispatchers.IO) {
                    client.newCall(request).execute()
                }

                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: "{}"
                    Log.d(TAG, "Sync response: $responseBody")
                    val jsonResponse = JSONObject(responseBody)

                    val isMaster = jsonResponse.optBoolean("isMaster", false)
                    val shouldSync = jsonResponse.optBoolean("shouldSync", false)
                    val targetPosition = jsonResponse.optLong("targetPosition", -1)
                    val alreadySynced = jsonResponse.optBoolean("alreadySynced", false)
                    val masterDeviceId = jsonResponse.optString("masterDeviceId", "")

                    syncStatus = if (isMaster) {
                        "Ведущее устройство • Позиция: ${currentPosition / 1000}с"
                    } else if (alreadySynced) {
                        "Синхронизировано с ведущим"
                    } else if (shouldSync) {
                        "Первоначальная синхронизация..."
                    } else {
                        "Ожидание синхронизации"
                    }

                    Log.d(TAG, "Is master: $isMaster, Should sync: $shouldSync, Target: $targetPosition, Already synced: $alreadySynced")

                    // Синхронизируемся только один раз при первом подключении
                    if (shouldSync && targetPosition > 0) {
                        Log.d(TAG, "Performing one-time sync to: $targetPosition")
                        targetPosition
                    } else {
                        null // Не синхронизируемся
                    }
                } else {
                    syncStatus = "Ошибка синхронизации: ${response.code}"
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sync error", e)
                syncStatus = "Ошибка синхронизации: ${e.message}"
                null
            } finally {
                isSyncing = false
            }
        }
        suspend fun sendStatusToServer() {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()

                val currentPosition = exoPlayer.currentPosition
                val isPlaying = exoPlayer.isPlaying
                val url = "$syncServerUrl/status?deviceId=$deviceId&position=$currentPosition&playing=$isPlaying"

                val request = Request.Builder().url(url).build()
                withContext(Dispatchers.IO) {
                    client.newCall(request).execute().close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Status update error", e)
            }
        }

        LaunchedEffect(Unit) {
            Log.d(TAG, "Starting live stream with one-time sync")
            try {
                val liveStream = "http://109.195.134.244:8096/stream.m3u8"
                val mediaItem = MediaItem.fromUri(liveStream)
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()

                // Ждем подготовки плеера
                delay(2000)

                // Выполняем ОДНОРАЗОВУЮ синхронизацию при запуске
                val targetPosition = syncWithServer()
                if (targetPosition != null && targetPosition > 0) {
                    syncStatus = "Синхронизировано на позицию: ${targetPosition / 1000}с"
                    exoPlayer.seekTo(targetPosition)
                    Log.d(TAG, "One-time sync to position: $targetPosition")

                    // Даем время на буферизацию после seek
                    delay(1000)
                } else {
                    syncStatus = if (syncStatus.contains("Ведущее")) {
                        "Ведущее устройство"
                    } else {
                        "Уже синхронизировано"
                    }
                }

                if (playbackPosition > 0) {
                    exoPlayer.seekTo(playbackPosition)
                    Log.d(TAG, "Restored position: $playbackPosition")
                }

                exoPlayer.playWhenReady = true

                // Только отправляем статус, но не синхронизируемся повторно
                while (true) {
                    delay(5000) // Отправляем статус каждые 5 секунд
                    sendStatusToServer()

                    // Периодически проверяем, не сменился ли мастер
                    delay(10000)
                    val syncCheck = syncWithServer()
                    // syncCheck будет null для уже синхронизированных устройств
                }

            } catch (e: Exception) {
                Log.e(TAG, "Live stream failed: ${e.message}")
                errorMessage = "Ошибка подключения: ${e.message}"
            }
        }

        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { context ->
                        PlayerView(context).apply {
                            this.player = exoPlayer
                            useController = false
                            setBackgroundColor(Color.Black.hashCode())
                            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (isSyncing) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(40.dp),
                            color = Color.White
                        )
                        Text(
                            text = syncStatus,
                            color = Color.White,
                            modifier = Modifier.padding(top = 8.dp),
                            fontSize = 14.sp
                        )
                    }
                }

                if (isLoading && !isPlaying) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(60.dp),
                        color = Color.White
                    )
                }

                if (errorMessage != null) {
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
                            fontSize = 14.sp
                        )
                        Button(
                            onClick = {
                                errorMessage = null
                                exoPlayer.stop()
                                exoPlayer.clearMediaItems()
                                val liveStream = "http://109.195.134.244:8096/stream.m3u8"
                                val mediaItem = MediaItem.fromUri(liveStream)
                                exoPlayer.setMediaItem(mediaItem)
                                exoPlayer.prepare()
                                exoPlayer.playWhenReady = true
                            }
                        ) {
                            Text("Повторить")
                        }
                    }
                }

                Text(
                    text = "©•2025 SicretHOME•",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        exoPlayer.playWhenReady = true
        hideSystemUI(window.decorView)
    }

    override fun onPause() {
        super.onPause()
        exoPlayer.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer.release()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI(window.decorView)
        }
    }
}