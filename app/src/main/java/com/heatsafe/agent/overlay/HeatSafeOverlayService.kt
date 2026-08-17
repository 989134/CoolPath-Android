package com.heatsafe.agent.overlay

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.TextView
import android.widget.FrameLayout
import android.location.Location
import android.speech.tts.TextToSpeech
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.heatsafe.agent.MainActivity
import com.heatsafe.agent.notification.HeatNotificationManager
import kotlin.math.abs
import com.heatsafe.agent.data.local.TripStore
import com.heatsafe.agent.domain.usecase.AnalyzeTripUseCase
import com.heatsafe.agent.domain.model.RiskLevel
import com.heatsafe.agent.domain.model.TripAnalysis
import com.heatsafe.agent.domain.model.HeatMapPoint
import com.heatsafe.agent.data.repository.NearbyHeatMapRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.*

class HeatSafeOverlayService : Service() {
    private var bubble: View? = null
    private var windowManager: WindowManager? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var expanded = false
    private var params: WindowManager.LayoutParams? = null
    private var bubbleText: TextView? = null
    private var heatView: HeatMiniMapView? = null
    private var latestAnalysis: TripAnalysis? = null
    private var latestHeatPoints: List<HeatMapPoint> = emptyList()
    private var liveLocation: Location? = null
    private var lastAnalysisLocation: Location? = null
    private var lastAnalysisAt = 0L
    private val analysisMutex = Mutex()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var textToSpeech: TextToSpeech? = null
    private var speechReady = false
    private var lastSpokenAt = 0L
    private var lastSpokenRisk: RiskLevel? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        startForeground(NOTIFICATION_ID, serviceNotification())
        textToSpeech = TextToSpeech(this) { status ->
            speechReady = status == TextToSpeech.SUCCESS
            if (speechReady) {
                val result = textToSpeech?.setLanguage(Locale.TAIWAN)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    textToSpeech?.language = Locale.TRADITIONAL_CHINESE
                }
                textToSpeech?.setSpeechRate(0.92f)
            }
        }
        showBubble()
        startFastLocationUpdates()
        startSynchronizedAnalysis()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        bubble?.let { runCatching { windowManager?.removeView(it) } }
        bubble = null
        serviceScope.cancel()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        locationCallback?.let { if (::fusedLocationClient.isInitialized) fusedLocationClient.removeLocationUpdates(it) }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
        super.onDestroy()
    }

    private fun showBubble() {
        val size = (64 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            text = "❄\n--°"; textSize = 18f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(185, 22, 119, 210))
                setStroke((1.2f * resources.displayMetrics.density).toInt(), Color.argb(150, 150, 220, 255))
            }
            elevation = 12f; setPadding(14, 10, 14, 10)
        }
        val miniMap = HeatMiniMapView(this).apply { visibility = View.GONE }
        val view = FrameLayout(this).apply {
            addView(text, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(miniMap, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        bubbleText = text; heatView = miniMap
        val params = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = 24; y = 260
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        this.params = params
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { startX = params.x; startY = params.y; touchX = event.rawX; touchY = event.rawY; true }
                MotionEvent.ACTION_MOVE -> { params.x = startX + (event.rawX - touchX).toInt(); params.y = startY + (event.rawY - touchY).toInt(); windowManager?.updateViewLayout(view, params); true }
                MotionEvent.ACTION_UP -> {
                    if (abs(event.rawX - touchX) < 12 && abs(event.rawY - touchY) < 12) {
                        val actionTop = 252 * resources.displayMetrics.density
                        if (expanded && event.y >= actionTop) openSaferRoute() else toggleCard(view)
                    }
                    true
                }
                else -> false
            }
        }
        windowManager?.addView(view, params); bubble = view
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, true).apply()
    }

    private fun toggleCard(view: View) {
        expanded = !expanded
        val layout = params ?: return
        if (expanded) {
            layout.width = minOf((360 * resources.displayMetrics.density).toInt(), resources.displayMetrics.widthPixels - (20 * resources.displayMetrics.density).toInt())
            layout.height = (310 * resources.displayMetrics.density).toInt()
            bubbleText?.visibility = View.GONE; heatView?.visibility = View.VISIBLE
            latestAnalysis?.let { heatView?.update(it, latestHeatPoints, liveLocation) }
        } else {
            layout.width = (64 * resources.displayMetrics.density).toInt(); layout.height = layout.width
            heatView?.visibility = View.GONE; bubbleText?.visibility = View.VISIBLE
            bubbleText?.textSize = 18f; updateCollapsedHud()
        }
        windowManager?.updateViewLayout(view, layout)
    }

    private fun startSynchronizedAnalysis() {
        serviceScope.launch {
            while (isActive) {
                performCloudAnalysis()
                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    private fun startFastLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, GPS_INTERVAL_MS)
            .setMinUpdateIntervalMillis(GPS_MIN_INTERVAL_MS).setMinUpdateDistanceMeters(3f).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                liveLocation = location
                if (expanded) latestAnalysis?.let { heatView?.update(it, latestHeatPoints, location) } else updateCollapsedHud()
                val moved = lastAnalysisLocation?.distanceTo(location) ?: Float.MAX_VALUE
                if (moved >= REROUTE_DISTANCE_METERS && System.currentTimeMillis() - lastAnalysisAt >= MIN_EARLY_ANALYSIS_MS) {
                    serviceScope.launch { performCloudAnalysis() }
                }
            }
        }
        locationCallback = callback
        fusedLocationClient.requestLocationUpdates(request, callback, mainLooper)
    }

    private suspend fun performCloudAnalysis() = analysisMutex.withLock {
        val destination = TripStore.destination(this@HeatSafeOverlayService) ?: return@withLock
        runCatching { AnalyzeTripUseCase(applicationContext)(destination.name, destination, forceDemo = false) {} }
            .onSuccess { analysis ->
                val sampled = runCatching { NearbyHeatMapRepository().load(analysis.origin) }.getOrDefault(emptyList())
                TripStore.saveAnalysis(applicationContext, analysis)
                lastAnalysisAt = System.currentTimeMillis()
                lastAnalysisLocation = liveLocation
                withContext(Dispatchers.Main) {
                    latestAnalysis = analysis
                    if (sampled.isNotEmpty()) latestHeatPoints = sampled
                    updateBubble(analysis.decision.riskLevel)
                    maybeSpeakRisk(analysis)
                }
                HeatNotificationManager.show(applicationContext, analysis.decision.riskLevel, analysis.decision.notificationText)
            }
    }

    private fun updateBubble(risk: RiskLevel) {
        val view = bubble ?: return
        if (!expanded) updateCollapsedHud()
        else latestAnalysis?.let { heatView?.update(it, latestHeatPoints, liveLocation) }
    }

    private fun updateCollapsedHud() {
        val analysis = latestAnalysis
        bubbleText?.text = "${riskIcon(analysis?.decision?.riskLevel ?: TripStore.summary(this).first)}\n${analysis?.weather?.feelsLike?.let { "%.0f°".format(it) } ?: "--°"}"
    }

    private fun maybeSpeakRisk(analysis: TripAnalysis) {
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_VOICE_ENABLED, true) || !speechReady) return
        val risk = analysis.decision.riskLevel
        val now = System.currentTimeMillis()
        val riskChanged = lastSpokenRisk != risk
        val cooldownElapsed = now - lastSpokenAt >= VOICE_COOLDOWN_MS
        if (risk == RiskLevel.LOW && lastSpokenRisk == null) {
            lastSpokenRisk = risk
            return
        }
        if (!riskChanged && !cooldownElapsed) return
        val temperature = "%.0f".format(analysis.weather.feelsLike)
        val message = when (risk) {
            RiskLevel.HIGH -> "CoolPath 高溫警示。附近體感溫度約 $temperature 度。${analysis.decision.notificationText}"
            RiskLevel.MEDIUM -> "CoolPath 提醒。附近體感溫度約 $temperature 度。請補充水分，盡量行走陰影路段。"
            RiskLevel.LOW -> "CoolPath 提醒。目前熱風險已降低，可以繼續依照地圖行走。"
        }
        textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "heatsafe-risk-${now}")
        lastSpokenAt = now
        lastSpokenRisk = risk
    }

    private fun openSaferRoute() {
        val analysis = latestAnalysis ?: return
        val route = analysis.routes.getOrNull(analysis.decision.recommendedRouteIndex) ?: analysis.routes.firstOrNull()
        val waypoints = route?.encodedPolyline?.let { encoded ->
            com.heatsafe.agent.util.PolylineUtils.sample(com.heatsafe.agent.util.PolylineUtils.decode(encoded), 800.0)
                .drop(1).dropLast(1).take(3).joinToString("|") { "${it.latitude},${it.longitude}" }
        }.orEmpty()
        val uri = Uri.parse("https://www.google.com/maps/dir/").buildUpon()
            .appendQueryParameter("api", "1")
            .appendQueryParameter("origin", liveLocation?.let { "${it.latitude},${it.longitude}" } ?: "${analysis.origin.latitude},${analysis.origin.longitude}")
            .appendQueryParameter("destination", "${analysis.destination.location.latitude},${analysis.destination.location.longitude}")
            .appendQueryParameter("travelmode", "walking")
            .apply { if (waypoints.isNotBlank()) appendQueryParameter("waypoints", waypoints) }.build()
        val maps = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).setPackage("com.google.android.apps.maps")
        runCatching { startActivity(maps) }.onFailure { startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun riskIcon(risk: RiskLevel) = when (risk) { RiskLevel.LOW -> "❄"; RiskLevel.MEDIUM -> "⚠"; RiskLevel.HIGH -> "🔥" }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("from_overlay", true))
    }

    private fun serviceNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, HeatSafeOverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, HeatNotificationManager.SERVICE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("CoolPath 已在背景運作")
            .setContentText("點懸浮球快速查看熱風險").setContentIntent(open).setOngoing(true)
            .addAction(0, "關閉懸浮球", stop).build()
    }

    companion object {
        private const val NOTIFICATION_ID = 2001
        const val ACTION_STOP = "com.heatsafe.agent.STOP_OVERLAY"
        const val PREFS = "heatsafe_settings"
        const val KEY_ENABLED = "overlay_enabled"
        const val KEY_VOICE_ENABLED = "voice_enabled"
        private const val SYNC_INTERVAL_MS = 5 * 60 * 1000L
        private const val GPS_INTERVAL_MS = 5_000L
        private const val GPS_MIN_INTERVAL_MS = 2_000L
        private const val MIN_EARLY_ANALYSIS_MS = 2 * 60 * 1000L
        private const val REROUTE_DISTANCE_METERS = 250f
        private const val VOICE_COOLDOWN_MS = 10 * 60 * 1000L

        fun start(context: Context): Boolean {
            if (!Settings.canDrawOverlays(context)) return false
            ContextCompat.startForegroundService(context, Intent(context, HeatSafeOverlayService::class.java))
            return true
        }
        fun stop(context: Context) { context.stopService(Intent(context, HeatSafeOverlayService::class.java)) }
        fun permissionIntent(context: Context) = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
    }
}
