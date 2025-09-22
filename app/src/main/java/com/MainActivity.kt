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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class SyncData(
    val targetPosition: Long,
    val shouldRestart: Boolean,
    val shouldWait: Boolean,
    val isMaster: Boolean,
    val restartIn: Long,
    val confirmedDevices: Int,
    val requiredDevices: Int
)

class MainActivity : ComponentActivity() {

    private val TAG = "BikLiveApp"
    private lateinit var deviceId: String
    private val syncServerUrl = "http://109.195.134.244:3000/api/sync"
    private lateinit var exoPlayer: ExoPlayer

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

        exoPlayer = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
        }

        setContent {
            AppContent(deviceId)
        }
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

        var isLoading by remember { mutableStateOf(true) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var isPlaying by remember { mutableStateOf(false) }
        var syncStatus by remember { mutableStateOf("") }
        var restartCountdown by remember { mutableStateOf(0) }
        var confirmedCount by remember { mutableStateOf(0) }
        var requiredCount by remember { mutableStateOf(0) }
        var isWaitingForConfirmation by remember { mutableStateOf(false) }

        DisposableEffect(Unit) {
            val stateListener = object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    isLoading = state == Player.STATE_BUFFERING
                    isPlaying = state == Player.STATE_READY
                }

                override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
                    errorMessage = "Ошибка: ${error.errorCodeName}\n${error.message}"
                    Log.e(TAG, "Player error", error)
                }
            }

            exoPlayer.addListener(stateListener)
            onDispose {
                exoPlayer.removeListener(stateListener)
            }
        }

        suspend fun syncWithServer(): SyncData? {
            return try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()

                val currentPosition = exoPlayer.currentPosition
                val url = "$syncServerUrl?deviceId=$deviceId&position=$currentPosition"

                val request = Request.Builder().url(url).build()
                val response = withContext(Dispatchers.IO) {
                    client.newCall(request).execute()
                }

                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: "{}"
                    val jsonResponse = JSONObject(responseBody)

                    val targetPosition = jsonResponse.optLong("targetPosition", 0)
                    val isMaster = jsonResponse.optBoolean("isMaster", false)
                    val shouldRestart = jsonResponse.optBoolean("shouldRestart", false)
                    val shouldWait = jsonResponse.optBoolean("shouldWait", false)
                    val restartIn = jsonResponse.optLong("restartIn", 0)
                    val confirmedDevices = jsonResponse.optInt("confirmedDevices", 0)
                    val requiredDevices = jsonResponse.optInt("requiredDevices", 0)

                    restartCountdown = (restartIn / 1000).toInt()
                    confirmedCount = confirmedDevices
                    requiredCount = requiredDevices
                    isWaitingForConfirmation = shouldWait

                    syncStatus = when {
                        shouldWait -> "Ожидание готовности: $confirmedDevices/$requiredDevices"
                        shouldRestart -> "ЗАПУСК! Начинаем воспроизведение"
                        restartIn > 0 -> "Перезапуск через: ${restartIn / 1000}сек"
                        isMaster -> "Ведущее устройство • ${currentPosition / 1000}с"
                        else -> "Синхронизировано • ${targetPosition / 1000}с"
                    }

                    Log.d(TAG, "Sync: wait=$shouldWait, confirmed=$confirmedDevices/$requiredDevices")

                    SyncData(
                        targetPosition,
                        shouldRestart,
                        shouldWait,
                        isMaster,
                        restartIn,
                        confirmedDevices,
                        requiredDevices
                    )
                } else {
                    syncStatus = "Ошибка синхронизации"
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sync error", e)
                syncStatus = "Ошибка сети"
                null
            }
        }

        suspend fun confirmReadiness(): Boolean {
            return try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(3, TimeUnit.SECONDS)
                    .build()

                val json = JSONObject().apply {
                    put("deviceId", deviceId)
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("http://109.195.134.244:3000/api/confirm")
                    .post(body)
                    .build()

                val response = withContext(Dispatchers.IO) {
                    client.newCall(request).execute()
                }

                response.isSuccessful
            } catch (e: Exception) {
                Log.e(TAG, "Confirm readiness error", e)
                false
            }
        }

        LaunchedEffect(Unit) {
            Log.d(TAG, "Starting ready-confirm synchronization system")

            // Начальная загрузка видео
            val liveStream = "http://109.195.134.244:8096/stream.m3u8"
            val mediaItem = MediaItem.fromUri(liveStream)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()

            // Ждем готовности плеера
            delay(3000)
            exoPlayer.playWhenReady = true

            // Основной цикл синхронизации
            while (true) {
                try {
                    val result = syncWithServer()

                    if (result != null) {
                        if (result.shouldWait) {
                            // Фаза ожидания подтверждения готовности
                            exoPlayer.playWhenReady = false // Приостанавливаем воспроизведение
                            exoPlayer.seekTo(0) // Перематываем на начало

                            // Подтверждаем готовность
                            if (confirmReadiness()) {
                                Log.d(TAG, "Readiness confirmed")
                            }

                            // Ждем 1 секунду перед следующей проверкой
                            delay(1000)

                        } else if (result.shouldRestart) {
                            // Запускаем воспроизведение синхронно
                            Log.d(TAG, "STARTING SYNCHRONIZED PLAYBACK")
                            exoPlayer.seekTo(0)
                            exoPlayer.playWhenReady = true
                            delay(1000) // Даем время на запуск

                        } else {
                            // Обычный режим - продолжаем воспроизведение без синхронизации
                            exoPlayer.playWhenReady = true

                            // УБРАНА СИНХРОНИЗАЦИЯ ПРИ РАСХОЖДЕНИИ > 5 СЕКУНД
                            // Каждое устройство продолжает воспроизведение со своей текущей позиции
                            // Синхронизация происходит только при подключении нового устройства
                        }
                    }

                    delay(2000) // Проверяем каждые 2 секунды

                } catch (e: Exception) {
                    Log.e(TAG, "Sync loop error", e)
                    delay(5000)
                }
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

//                if (isLoading) {
//                    CircularProgressIndicator(
//                        modifier = Modifier
//                            .align(Alignment.Center)
//                            .size(60.dp),
//                        color = Color.White
//                    )
//                }

                // Статус синхронизации
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(16.dp)
                ) {
                    Text(
                        text = syncStatus,
                        color = when {
                            syncStatus.contains("ЗАПУСК") -> Color.Green
                            syncStatus.contains("Ожидание") -> Color.Yellow
                            else -> Color.White
                        },
                        fontSize = 14.sp
                    )

                    if (isWaitingForConfirmation) {
                        Text(
                            text = "Готовы: $confirmedCount/$requiredCount",
                            color = Color.Yellow,
                            fontSize = 12.sp
                        )
                    }

                    if (restartCountdown > 0) {
                        Text(
                            text = "До перезапуска: ${restartCountdown}сек",
                            color = Color.Yellow,
                            fontSize = 12.sp
                        )
                    }

                    Text(
                        text = "ID: ${deviceId.take(8)}",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 10.sp
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
                            Text("Перезапустить")
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
}