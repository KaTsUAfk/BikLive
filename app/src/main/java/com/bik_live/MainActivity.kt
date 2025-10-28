// MainActivity.kt
package com.bik_live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Window
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.util.Util
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "SyncPlayer"
        private const val SYNC_URL = "http://109.195.134.244:3000/api/sync"
        private const val CONFIRM_URL = "http://109.195.134.244:3000/api/confirm"
        private const val RESTART_URL = "http://109.195.134.244:3000/api/restart"
        private const val LIVE_URL = "http://109.195.134.244:8096/ekat/stream.m3u8"

        // Пороги коррекции
        private const val DRIFT_SOFT_MS = 500
        private const val DRIFT_SPEED_MS = 2000
        private const val DRIFT_SEEK_MS = 2000

        // Ограничения скорости
        private const val SPEED_UP = 1.03f
        private const val SPEED_DOWN = 0.97f
        private const val SPEED_NORMAL = 1.0f
    }
    private val deviceCity: String by lazy {
        LIVE_URL.split("/")[3]
    }
    private lateinit var exoPlayer: ExoPlayer
    private val deviceId: String by lazy { UUID.randomUUID().toString() }
    private val networkClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    private val syncMutex = Mutex()
    private val currentCity: String by lazy { extractCityFromUrl(LIVE_URL) }

    // Состояния UI
    private var errorMessage by mutableStateOf<String?>(null)
    private var isNetworkAvailable by mutableStateOf(true)
    private var retryCount by mutableStateOf(0)
    private var networkJob: Job? = null

    private fun extractCityFromUrl(url: String): String {
        // LIVE_URL = "http://109.195.134.244:8096/kurgan/stream.m3u8"
        val path = url.removePrefix("http://").removePrefix("https://")
        val parts = path.split("/")
        // parts = ["109.195.134.244:8096", "kurgan", "stream.m3u8"]
        return if (parts.size >= 2) parts[1] else "kurgan"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prepareWindow(window)
        exoPlayer = buildPlayer()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_NETWORK_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            // Запросите разрешение или обработайте случай
        }

        setContent {
            val context = LocalContext.current
            AppUI(
                player = exoPlayer,
                deviceId = deviceId,
                onRestartClick = { manualRestart() },
                errorMessage = errorMessage,
                onClearError = {
                    errorMessage = null
                    retryCount = 0
                },
                isNetworkAvailable = isNetworkAvailable,
                retryCount = retryCount,
                onCheckNetwork = { checkNetworkAvailability(context) }
            )
        }
        hideSystemUI()
        // Запускаем проверку сети
        startNetworkMonitoring()
    }

    private fun prepareWindow(window: Window) {
        // Отключаем отображение декора по умолчанию
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Убираем статус-бар и навигационную панель
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK

        // Устанавливаем флаги для полного экрана
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR)

        // Для Android 11+ используем новый API для скрытия системных окон
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
    }

    private fun buildPlayer(): ExoPlayer {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(Util.getUserAgent(this, "SyncPlayer"))
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(15000)

        val mediaSourceFactory = HlsMediaSource.Factory(dataSourceFactory)

        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                playWhenReady = false
                repeatMode = Player.REPEAT_MODE_ALL
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "Player error: ${error.errorCodeName}", error)
                        lifecycleScope.launch {
                            handlePlayerError(error)
                        }.invokeOnCompletion { throwable ->
                            throwable?.let { Log.e(TAG, "Error handling failed", it) }
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                errorMessage = null
                            }
                            Player.STATE_BUFFERING -> {
                            }
                        }
                    }
                })
            }
    }

    private suspend fun handlePlayerError(error: PlaybackException) {
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                errorMessage = "Ошибка сети: не удалось подключиться к видеопотоку"
                delay(3000)
                reloadStream()
            }
            else -> {
                errorMessage = "Ошибка воспроизведения: ${error.errorCodeName}"
                delay(2000)
                reloadStream()
            }
        }
    }

    private fun buildLiveMediaSource(): MediaSource {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(Util.getUserAgent(this, "SyncPlayer"))
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(15000)
        val hlsFactory = HlsMediaSource.Factory(dataSourceFactory)
        val mediaItem = MediaItem.fromUri(LIVE_URL)
        return hlsFactory.createMediaSource(mediaItem)
    }

    private fun reloadStream() {
        try {
            if (!::exoPlayer.isInitialized) {
                Log.w(TAG, "Player not initialized")
                return
            }

            exoPlayer.apply {
                stop()
                clearMediaItems()
                val mediaSource = buildLiveMediaSource()
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
            }
            errorMessage = null
        } catch (e: Exception) {
            Log.e(TAG, "Reload stream failed", e)
            errorMessage = "Ошибка перезагрузки потока: ${e.message}"
        }
    }

    private fun startNetworkMonitoring() {
        networkJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    checkNetworkAvailability(this@MainActivity)
                    delay(5000)
                }
            }
        }
    }

    private fun checkNetworkAvailability(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)

        val available = capabilities != null &&
                (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))

        isNetworkAvailable = available

        if (!available) {
            errorMessage = "Нет подключения к интернету. Проверьте сеть."
        } else if (errorMessage?.contains("интернету") == true) {
            errorMessage = null
        }

        return available
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        checkNetworkAvailability(this)
    }

    override fun onPause() {
        super.onPause()
        exoPlayer.playWhenReady = false
    }

    override fun onDestroy() {
        networkJob?.cancel()
        exoPlayer.release()
        super.onDestroy()
    }

    @Composable
    private fun AppUI(
        player: ExoPlayer,
        deviceId: String,
        onRestartClick: () -> Unit,
        errorMessage: String?,
        onClearError: () -> Unit,
        isNetworkAvailable: Boolean,
        retryCount: Int,
        onCheckNetwork: () -> Boolean
    ) {
        var syncStatus by remember { mutableStateOf("Инициализация...") }
        var phase by remember { mutableStateOf("idle") }
        var isMaster by remember { mutableStateOf(false) }
        var confirmed by remember { mutableStateOf(0) }
        var required by remember { mutableStateOf(0) }
        var startAt by remember { mutableStateOf<Long?>(null) }
        var serverTimeOffset by remember { mutableStateOf(0L) }
        var hasConfirmed by remember { mutableStateOf(false) }
        var isLoading by remember { mutableStateOf(true) }
        var hasStarted by remember { mutableStateOf(false) }

        LaunchedEffect(key1 = isNetworkAvailable) {
            if (isNetworkAvailable) {
                reloadStream()
                delay(2000)
            }
            isLoading = false
        }

        LaunchedEffect(Unit) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    var backoff = 1000L
                    while (isActive) {
                        if (!onCheckNetwork()) {
                            syncStatus = "Ожидание сети..."
                            delay(5000)
                            continue
                        }

                        try {
                            val pos = player.currentPosition.coerceAtLeast(0L)
                            val resp = syncWithServer(deviceId, pos)

                            // === Проверка на город===
                            val sessionCity = resp.sessionId.split("_").getOrNull(1) ?: "kurgan"
                            if (sessionCity != currentCity && resp.phase != "idle") {
                                syncStatus = "Ожидание сессии для города $currentCity"
                                delay(2000)
                                continue
                            }
                            // ======================

                            val localNow = System.currentTimeMillis()
                            val offset = resp.serverTime - localNow
                            serverTimeOffset = offset

                            phase = resp.phase
                            isMaster = resp.isMaster
                            confirmed = resp.confirmedDevices
                            required = resp.requiredDevices
                            startAt = resp.startAtMillis

                            // Улучшенная обработка команд
                            if (resp.remoteCommand != null) {
                                when (resp.remoteCommand) {
                                    "reload" -> {
                                        reloadStream()
                                        Log.d(TAG, "Remote command executed: reload")
                                    }
                                    "restart" -> {
                                        // Сбрасываем флаг начала воспроизведения
                                        hasStarted = false
                                        player.seekTo(0)
                                        player.playWhenReady = false
                                        player.setPlaybackSpeed(SPEED_NORMAL)
                                        Log.d(TAG, "Remote command executed: restart - reset to beginning")
                                    }
                                    "pause" -> {
                                        player.playWhenReady = false
                                        Log.d(TAG, "Remote command executed: pause")
                                    }
                                    "play" -> {
                                        player.playWhenReady = true
                                        Log.d(TAG, "Remote command executed: play")
                                    }
                                }
                            }

                            when (resp.phase) {
                                "countdown" -> {
                                    if (player.isPlaying) player.playWhenReady = false
                                    if (abs(player.currentPosition) > 50) {
                                        player.seekTo(0)
                                    }
                                    if (!hasConfirmed) {
                                        hasConfirmed = confirmReadiness(deviceId)
                                    }
                                    val delayMs = (resp.startAtMillis ?: 0L) - (System.currentTimeMillis() + serverTimeOffset)
                                    val delaySec = (delayMs.coerceAtLeast(0L) / 1000)
                                    syncStatus = "Ожидание старта • через ${delaySec}с • Готовы: $confirmed/$required"
                                }
                                "start" -> {
                                    val target = resp.startAtMillis ?: System.currentTimeMillis()
                                    val wait = target - (System.currentTimeMillis() + serverTimeOffset)
                                    if (wait > 10) {
                                        delay(wait)
                                    }
                                    // ✅ Только если еще не стартовали — делаем seekTo(0)
                                    if (!hasStarted) {
                                        player.seekTo(0)
                                        player.playWhenReady = true
                                        player.setPlaybackSpeed(SPEED_NORMAL)
                                        hasStarted = true // ← помечаем, что стартовали
                                        syncStatus = "ЗАПУСК! Начинаем воспроизведение"
                                    } else {
                                        // ✅ Если уже стартовали — просто играем
                                        player.playWhenReady = true
                                        player.setPlaybackSpeed(SPEED_NORMAL)
                                        syncStatus = "Воспроизведение"
                                    }
                                }
                                "playing" -> {
                                    player.playWhenReady = true
                                    player.setPlaybackSpeed(SPEED_NORMAL)
                                    hasStarted = true // ← убедимся, что флаг установлен
                                    syncStatus = if (isMaster) {
                                        "Ведущее устройство"
                                    } else {
                                        "Воспроизведение"
                                    }
                                }
                                "idle" -> {
                                    player.playWhenReady = true
                                    hasStarted = false // ← сбрасываем, если нужно
                                    syncStatus = "Готово"
                                }
                                else -> {
                                    syncStatus = "Неизвестная фаза"
                                }
                            }

                            backoff = 1000L
                            delay(2000)
                        } catch (e: Exception) {
                            handleSyncError(e, backoff)
                            syncStatus = when (e) {
                                is ConnectException -> "Сервер недоступен"
                                is SocketTimeoutException -> "Таймаут подключения"
                                else -> "Ошибка синхронизации"
                            }
                            delay(backoff)
                            backoff = (backoff * 2).coerceAtMost(30000)
                        }
                    }
                }
            }
        }
        LaunchedEffect(isNetworkAvailable, phase, errorMessage) {
            if (errorMessage != null && isNetworkAvailable && phase != "idle") {
                delay(1000)
                if (isNetworkAvailable && phase != "idle") {
                    onClearError()
                }
            }
        }
        Box(modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)) {

            AndroidPlayerView(player = player, modifier = Modifier.fillMaxSize())

//            Column(
//                modifier = Modifier
//                    .align(Alignment.TopStart)
//                    .padding(16.dp)
//                    .background(Color.Black.copy(alpha = 0.5f))
//                    .padding(12.dp)
//            ) {
//                if (!isNetworkAvailable) {
//                    Text(
//                        text = "⚠️ НЕТ ПОДКЛЮЧЕНИЯ К ИНТЕРНЕТУ",
//                        color = Color.Red,
//                        fontSize = 14.sp
//                    )
//                    Spacer(Modifier.height(4.dp))
//                }
//
//                Text(
//                    text = syncStatus,
//                    color = when {
//                        !isNetworkAvailable -> Color.Red
//                        syncStatus.contains("ЗАПУСК") -> Color.Green
//                        syncStatus.contains("Ожидание") -> Color.Yellow
//                        syncStatus.contains("Ошибка") -> Color.Red
//                        else -> Color.White
//                    },
//                    fontSize = 14.sp
//                )
//                Spacer(Modifier.height(4.dp))
//                Text(
//                    text = "Фаза: $phase • Master: ${if (isMaster) "да" else "нет"}",
//                    color = Color.White,
//                    fontSize = 12.sp
//                )
//                if (phase == "countdown") {
//                    val rem = (startAt?.let { it - System.currentTimeMillis() } ?: 0L) / 1000
//                    Text(
//                        text = "Старт через: ${rem.coerceAtLeast(0)}с • Готовы: $confirmed/$required",
//                        color = Color.Yellow,
//                        fontSize = 12.sp
//                    )
//                }
//                val offsetMs = serverTimeOffset
//                Text(
//                    text = "Сеть: ${if (isNetworkAvailable) "✓" else "✗"} • Offset: ${offsetMs}мс • ID: ${deviceId.take(8)}",
//                    color = if (isNetworkAvailable) Color.White.copy(alpha = 0.7f) else Color.Red,
//                    fontSize = 10.sp
//                )
//            }
//
//            Column(
//                modifier = Modifier
//                    .align(Alignment.BottomStart)
//                    .padding(16.dp),
//                verticalArrangement = Arrangement.spacedBy(8.dp)
//            ) {
//                Button(onClick = onRestartClick) {
//                    Text("Принудительный перезапуск")
//                }
//
//                Button(onClick = { reloadStream() }) {
//                    Text("Переподключиться к потоку")
//                }
//
//                if (isLoading) {
//                    CircularProgressIndicator(
//                        color = Color.White,
//                        modifier = Modifier.size(28.dp)
//                    )
//                }
//            }

            if (errorMessage != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.9f))
                        .padding(20.dp)
                        .width(300.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "⚠️ ОШИБКА",
                        color = Color.Red,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Text(
                        text = errorMessage ?: "Неизвестная ошибка",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp),
                        fontSize = 14.sp
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = {
                            onClearError()
                            reloadStream()
                        }) {
                            Text("Повторить")
                        }

                        Button(
                            onClick = { onClearError() }
                        ) {
                            Text("Закрыть")
                        }
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

    private fun handleSyncError(e: Exception, backoff: Long) {
        Log.e(TAG, "Sync loop error (backoff: ${backoff}ms)", e)
        retryCount++

        when (e) {
            is ConnectException -> {
                errorMessage = "Не удалось подключиться к серверу синхронизации. Проверьте:\n• Доступность сервера\n• Настройки сети\n• Попытка #$retryCount"
            }
            is SocketTimeoutException -> {
                errorMessage = "Таймаут подключения к серверу. Сервер не отвечает.\nПопытка #$retryCount"
            }
            else -> {
                errorMessage = "Ошибка синхронизации: ${e.message}\nПопытка #$retryCount"
            }
        }
    }

    @Composable
    private fun AndroidPlayerView(player: ExoPlayer, modifier: Modifier = Modifier) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    this.player = player
                    useController = false
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            modifier = modifier
        )
    }

    private data class SyncResponse(
        val serverTime: Long,
        val sessionId: String,
        val phase: String,
        val startAtMillis: Long?,
        val requiredDevices: Int,
        val confirmedDevices: Int,
        val deviceId: String,
        val isMaster: Boolean,
        val activeDevices: Int,
        val recommendedAction: String,
        val targetPositionMillis: Long,
        val remoteCommand: String? = null
    )

    private suspend fun syncWithServer(deviceId: String, position: Long): SyncResponse {
        return try {
            syncMutex.withLock {
                val url = "$SYNC_URL?deviceId=${deviceId}&position=${position}&city=${currentCity}"
                val req = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val response = withContext(Dispatchers.IO) {
                    try {
                        networkClient.newCall(req).execute()
                    } catch (e: ConnectException) {
                        throw ConnectException("Сервер синхронизации недоступен: ${e.message}")
                    } catch (e: SocketTimeoutException) {
                        throw SocketTimeoutException("Таймаут подключения к серверу синхронизации")
                    } catch (e: Exception) {
                        throw Exception("Сетевая ошибка: ${e.message}")
                    }
                }

                if (!response.isSuccessful) {
                    response.close()
                    throw IllegalStateException("HTTP ${response.code}: ${response.message}")
                }

                val bodyStr = response.body?.string() ?: "{}"
                response.close()

                Log.d(TAG, "Sync response from server: $bodyStr")

                try {
                    val json = JSONObject(bodyStr)
                    SyncResponse(
                        serverTime = json.optLong("serverTime"),
                        sessionId = json.optString("sessionId"),
                        phase = json.optString("phase", "idle"),
                        startAtMillis = if (json.isNull("startAtMillis")) null else json.optLong("startAtMillis"),
                        requiredDevices = json.optInt("requiredDevices", 0),
                        confirmedDevices = json.optInt("confirmedDevices", 0),
                        deviceId = json.optString("deviceId"),
                        isMaster = json.optBoolean("isMaster", false),
                        activeDevices = json.optInt("activeDevices", 1),
                        recommendedAction = json.optString("recommendedAction", "play"),
                        targetPositionMillis = json.optLong("targetPositionMillis", position),
                        remoteCommand = if (json.has("remoteCommand")) json.optString("remoteCommand") else null
                    )
                } catch (e: Exception) {
                    throw Exception("Ошибка парсинга ответа сервера: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed completely", e)
            SyncResponse(
                serverTime = System.currentTimeMillis(),
                sessionId = "error",
                phase = "idle",
                startAtMillis = null,
                requiredDevices = 0,
                confirmedDevices = 0,
                deviceId = deviceId,
                isMaster = false,
                activeDevices = 0,
                recommendedAction = "play",
                targetPositionMillis = position
            )
        }
    }

    private suspend fun confirmReadiness(deviceId: String): Boolean {
        return try {
            val json = JSONObject().apply {
                put("deviceId", deviceId)
                put("city", currentCity)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url(CONFIRM_URL)
                .post(body)
                .build()
            val resp = withContext(Dispatchers.IO) {
                networkClient.newCall(req).execute()
            }
            val ok = resp.isSuccessful
            resp.close()
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Confirm readiness error", e)
            false
        }
    }

    private fun manualRestart() {
        lifecycleScope.launch {
            try {
                if (!isNetworkAvailable) {
                    errorMessage = "Нет сети для отправки запроса перезапуска"
                    return@launch
                }
                // Используем currentCity, который уже определен в классе
                val json = JSONObject().apply {
                    put("city", currentCity) // ← Используем существующее поле currentCity
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url(RESTART_URL).post(body).build()
                val resp = withContext(Dispatchers.IO) { networkClient.newCall(req).execute() }
                if (!resp.isSuccessful) {
                    errorMessage = "Не удалось запланировать перезапуск: HTTP ${resp.code}"
                } else {
                    errorMessage = "Запрос на перезапуск отправлен успешно для города $currentCity"
                    delay(3000)
                    errorMessage = null
                }
                resp.close()
            } catch (e: Exception) {
                errorMessage = "Ошибка сети при перезапуске: ${e.message}"
            }
        }
    }

    private fun hideSystemUI() {
        val window = window
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK

        // Скрываем статус-бар и навигационную панель
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 31+
            window.insetsController?.let { controller ->
                controller.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // API < 31
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
        }
    }

    private fun applyDriftCorrection(driftMs: Long) {
        val a = abs(driftMs)
        when {
            a <= DRIFT_SOFT_MS -> {
                exoPlayer.setPlaybackSpeed(SPEED_NORMAL)
            }
            a in (DRIFT_SOFT_MS + 1)..DRIFT_SPEED_MS -> {
                if (driftMs > 0) {
                    exoPlayer.setPlaybackSpeed(SPEED_UP)
                } else {
                    exoPlayer.setPlaybackSpeed(SPEED_DOWN)
                }
            }
            a > DRIFT_SEEK_MS -> {
                val newPos = (exoPlayer.currentPosition + driftMs).coerceAtLeast(0L)
                exoPlayer.seekTo(newPos)
                exoPlayer.setPlaybackSpeed(SPEED_NORMAL)
            }
        }
    }

    private fun ExoPlayer.setPlaybackSpeed(speed: Float) {
        val s = clamp(speed, 0.5f, 1.5f)
        this.setPlaybackParameters(PlaybackParameters(s))
    }

    private fun clamp(v: Float, min: Float, max: Float): Float {
        return kotlin.math.max(min, kotlin.math.min(max, v))
    }
}