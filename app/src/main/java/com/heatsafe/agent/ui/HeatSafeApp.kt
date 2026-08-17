package com.heatsafe.agent.ui

import android.app.TimePickerDialog
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.widget.PlaceAutocomplete
import com.google.android.libraries.places.widget.PlaceAutocompleteActivity
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.*
import com.google.maps.android.compose.*
import com.heatsafe.agent.BuildConfig
import com.heatsafe.agent.data.mock.MockDataSource
import com.heatsafe.agent.data.repository.NearbyHeatMapRepository
import com.heatsafe.agent.data.repository.LocationRepository
import com.heatsafe.agent.data.repository.WeatherRepository
import com.heatsafe.agent.domain.risk.HeatRiskCalculator
import com.heatsafe.agent.domain.model.*
import com.heatsafe.agent.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import com.heatsafe.agent.worker.TripScheduler
import com.heatsafe.agent.overlay.HeatSafeOverlayService
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sqrt

@Composable
fun HeatSafeApp() {
    val nav = rememberNavController()
    val tripViewModel: TripViewModel = viewModel()
    NavHost(nav, startDestination = "home") {
        composable("home") { HomeScreen(tripViewModel) { destination, demo -> nav.navigate("analysis/${destination.ifBlank { "台北 101" }}/$demo") } }
        composable(
            "analysis/{destination}/{demo}",
            arguments = listOf(navArgument("destination") { type = NavType.StringType }, navArgument("demo") { type = NavType.BoolType })
        ) { backStack ->
            AnalysisScreen(backStack.arguments?.getString("destination").orEmpty(), backStack.arguments?.getBoolean("demo") ?: false, tripViewModel) {
                nav.navigate("result") { popUpTo("home") }
            }
        }
        composable("result") {
            val state by tripViewModel.state.collectAsState()
            ResultScreen(state.result ?: MockDataSource.analysis()) { nav.popBackStack("home", false) }
        }
    }
}

@Composable
private fun DemoBadge() {
    Surface(color = Color(0xFFFFE0B2), shape = RoundedCornerShape(20.dp)) {
        Text("DEMO MODE", modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp), color = Color(0xFF8A3B00), fontWeight = FontWeight.Bold)
    }
}

@Composable
fun HomeScreen(tripViewModel: TripViewModel, onAnalyze: (String, Boolean) -> Unit) {
    var destination by remember { mutableStateOf("") }
    var departure by remember { mutableStateOf(LocalTime.now().plusHours(1)) }
    val context = LocalContext.current
    var refreshNonce by remember { mutableIntStateOf(0) }
    var homeCenter by remember { mutableStateOf<LatLngPoint?>(null) }
    var homeWeather by remember { mutableStateOf<WeatherInfo?>(null) }
    var homeHeatPoints by remember { mutableStateOf<List<HeatMapPoint>>(emptyList()) }
    var heatLoading by remember { mutableStateOf(false) }
    var heatError by remember { mutableStateOf<String?>(null) }
    var overlayEnabled by remember { mutableStateOf(context.getSharedPreferences(HeatSafeOverlayService.PREFS, android.content.Context.MODE_PRIVATE).getBoolean(HeatSafeOverlayService.KEY_ENABLED, false)) }
    var voiceEnabled by remember { mutableStateOf(context.getSharedPreferences(HeatSafeOverlayService.PREFS, android.content.Context.MODE_PRIVATE).getBoolean(HeatSafeOverlayService.KEY_VOICE_ENABLED, true)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) refreshNonce++
    }
    val placesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val intent = result.data
        if (result.resultCode == PlaceAutocompleteActivity.RESULT_OK && intent != null && Places.isInitialized()) {
            val prediction = PlaceAutocomplete.getPredictionFromIntent(intent) ?: return@rememberLauncherForActivityResult
            val fields = listOf(Place.Field.ID, Place.Field.DISPLAY_NAME, Place.Field.LOCATION)
            val request = FetchPlaceRequest.builder(prediction.placeId, fields)
                .setSessionToken(PlaceAutocomplete.getSessionTokenFromIntent(intent)).build()
            Places.createClient(context).fetchPlace(request).addOnSuccessListener { response ->
                val place = response.place; val location = place.location
                if (location != null) {
                    destination = place.displayName ?: prediction.getPrimaryText(null).toString()
                    tripViewModel.selectDestination(Destination(place.id ?: prediction.placeId, destination, LatLngPoint(location.latitude, location.longitude)))
                }
            }
        }
    }
    val overlayPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        overlayEnabled = if (Settings.canDrawOverlays(context)) HeatSafeOverlayService.start(context) else false
    }
    LaunchedEffect(Unit) {
        val permissions = buildList {
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            if (android.os.Build.VERSION.SDK_INT >= 33) add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
    LaunchedEffect(refreshNonce) {
        if (BuildConfig.GOOGLE_MAPS_WEB_API_KEY.isBlank()) return@LaunchedEffect
        heatLoading = true; heatError = null
        val center = LocationRepository(context).currentLocation()
        if (center == null) {
            heatError = "無法取得 GPS，請允許精確位置並設定模擬器位置。"
        } else {
            homeCenter = center
            val weatherDeferred = async { runCatching { WeatherRepository().getWeather(center) }.getOrNull() }
            val gridDeferred = async {
                NearbyHeatMapRepository().load(center, gridSize = 5, latitudeStep = 0.004, longitudeStep = 0.0045, forceRefresh = refreshNonce > 0)
            }
            homeWeather = weatherDeferred.await()
            val refreshed = gridDeferred.await()
            if (refreshed.isNotEmpty()) homeHeatPoints = refreshed else heatError = "Weather API 暫時無法取得附近採樣。"
        }
        heatLoading = false
    }
    LaunchedEffect(Unit) {
        while (isActive) { delay(10 * 60 * 1000L); refreshNonce++ }
    }
    val currentRisk = homeWeather?.let { HeatRiskCalculator.calculate(it, 0, 2).second }
    val riskColor = when(currentRisk) { RiskLevel.LOW -> RiskLow; RiskLevel.MEDIUM -> RiskMedium; RiskLevel.HIGH -> RiskHigh; null -> HeatOrange }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 18.dp, vertical = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(CoolBlue, CoolSky))).padding(22.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Column {
                            Text("CoolPath", color = Color.White, style = MaterialTheme.typography.headlineLarge)
                            Text("你身邊的即時熱風險導航", color = Color.White.copy(alpha = 0.9f))
                        }
                        if (BuildConfig.GOOGLE_MAPS_WEB_API_KEY.isBlank()) DemoBadge()
                    }
                    Surface(color = Color.White.copy(alpha = 0.18f), shape = RoundedCornerShape(50.dp)) {
                        Text(
                            homeCenter?.let { "● 定位完成  ${"%.4f".format(it.latitude)}, ${"%.4f".format(it.longitude)}" } ?: "○ 正在取得精確位置…",
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp), color = Color.White,
                            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            HomeLiveHeatMap(homeCenter, homeHeatPoints, heatLoading, heatError) { refreshNonce++ }
            ElevatedCard(
                Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = riskColor.copy(alpha = 0.09f)),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = riskColor.copy(alpha = 0.14f), shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Default.LocalFireDepartment, null, tint = riskColor, modifier = Modifier.padding(10.dp).size(28.dp))
                        }
                        val weather = homeWeather
                        Spacer(Modifier.width(10.dp)); Column {
                            Text(currentRisk?.let { "${it.name} HEAT RISK" } ?: "讀取即時氣象中", color = riskColor, fontWeight = FontWeight.Bold)
                            Text(weather?.temperature?.let { "%.1f°C".format(it) } ?: "--°C", fontSize = 36.sp, fontWeight = FontWeight.Black)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Metric("體感", homeWeather?.feelsLike?.let { "%.1f°C".format(it) } ?: "--", Modifier.weight(1f))
                        Metric("熱指數", homeWeather?.heatIndex?.let { "%.1f°C".format(it) } ?: "--", Modifier.weight(1f))
                        Metric("UV", homeWeather?.uvIndex?.toString() ?: "--", Modifier.weight(1f))
                    }
                }
            }
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.elevatedCardColors(containerColor = Color.White)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("CoolPath 背景守護", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("開啟 Google Maps 時持續分析熱風險", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = overlayEnabled, onCheckedChange = { enabled ->
                            if (enabled) {
                                if (Settings.canDrawOverlays(context)) overlayEnabled = HeatSafeOverlayService.start(context)
                                else overlayPermissionLauncher.launch(HeatSafeOverlayService.permissionIntent(context))
                            } else { HeatSafeOverlayService.stop(context); overlayEnabled = false }
                        })
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("中文語音提醒", fontWeight = FontWeight.SemiBold)
                            Text("風險升高時播報溫度與安全建議", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = voiceEnabled, onCheckedChange = {
                            voiceEnabled = it
                            context.getSharedPreferences(HeatSafeOverlayService.PREFS, android.content.Context.MODE_PRIVATE)
                                .edit().putBoolean(HeatSafeOverlayService.KEY_VOICE_ENABLED, it).apply()
                        })
                    }
                }
            }
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.elevatedCardColors(containerColor = Color.White)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("規劃安全路線", style = MaterialTheme.typography.titleLarge)
                    Text("我們會比較曝曬、補水點與步行時間", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(destination, { destination = it }, label = { Text("目的地") }, placeholder = { Text("例如：台北 101") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp))
                    if (BuildConfig.MAPS_API_KEY.isNotBlank()) {
                        OutlinedButton({ placesLauncher.launch(PlaceAutocomplete.IntentBuilder().setInitialQuery(destination).setCountries(listOf("TW")).setRegionCode("TW").build(context)) }, Modifier.fillMaxWidth()) {
                            Text("從 Google Maps 地點搜尋")
                        }
                    }
                    OutlinedButton(
                        onClick = { TimePickerDialog(context, { _, h, m -> departure = LocalTime.of(h, m) }, departure.hour, departure.minute, true).show() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("出發時間  ${departure.format(DateTimeFormatter.ofPattern("HH:mm"))}") }
                    Button({
                        var departureDateTime = ZonedDateTime.now().withHour(departure.hour).withMinute(departure.minute).withSecond(0)
                        if (departureDateTime.isBefore(ZonedDateTime.now())) departureDateTime = departureDateTime.plusDays(1)
                        TripScheduler(context).schedule(destination, departureDateTime)
                        onAnalyze(destination, false)
                    }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) { Text("開始安全路線分析", fontWeight = FontWeight.Bold) }
                    FilledTonalButton({ onAnalyze(destination, true) }, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) { Text("預覽模擬分析") }
                }
            }
            Text("CoolPath 提供路線決策輔助，並非醫療診斷。", modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = Color.White.copy(alpha = 0.72f), shape = RoundedCornerShape(15.dp)) {
        Column(Modifier.padding(vertical = 11.dp, horizontal = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HomeLiveHeatMap(
    center: LatLngPoint?,
    points: List<HeatMapPoint>,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit
) {
    val initial = center ?: MockDataSource.origin
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(initial.latitude, initial.longitude), 13.5f) }
    var mapLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(mapLoaded, center) {
        if (mapLoaded && center != null) runCatching {
            camera.animate(CameraUpdateFactory.newLatLngZoom(LatLng(center.latitude, center.longitude), 13.5f))
        }
    }
    val min = points.minOfOrNull { it.temperature }
    val max = points.maxOfOrNull { it.temperature }
    val thermalSurface = remember(points, min, max) {
        if (points.isEmpty() || min == null || max == null) null else createThermalSurface(points, min, max)
    }
    val previewMapSettings = remember {
        MapUiSettings(
            compassEnabled = false,
            indoorLevelPickerEnabled = false,
            mapToolbarEnabled = false,
            myLocationButtonEnabled = false,
            rotationGesturesEnabled = false,
            scrollGesturesEnabled = false,
            scrollGesturesEnabledDuringRotateOrZoom = false,
            tiltGesturesEnabled = false,
            zoomControlsEnabled = false,
            zoomGesturesEnabled = false
        )
    }
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.elevatedCardColors(containerColor = Color.White), elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("附近即時熱力圖", style = MaterialTheme.typography.titleLarge)
                    Text("Google Weather · 25 個即時座標採樣", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else TextButton(onRefresh) { Text("更新") }
            }
            GoogleMap(
                modifier = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(18.dp)),
                cameraPositionState = camera,
                googleMapOptionsFactory = { GoogleMapOptions().liteMode(false) },
                uiSettings = previewMapSettings,
                onMapLoaded = { mapLoaded = true }
            ) {
                thermalSurface?.let { surface ->
                    GroundOverlay(
                        position = GroundOverlayPosition.create(surface.bounds),
                        image = BitmapDescriptorFactory.fromBitmap(surface.bitmap),
                        transparency = 0f,
                        zIndex = 1f
                    )
                }
                center?.let {
                    val centerPoint = points.minByOrNull { point -> com.heatsafe.agent.util.PolylineUtils.distanceMeters(point.location, it) }
                    Marker(
                        state = rememberUpdatedMarkerState(LatLng(it.latitude, it.longitude)),
                        title = "目前位置",
                        snippet = centerPoint?.let { point -> "${"%.1f".format(point.temperature)}°C · 體感 ${"%.1f".format(point.feelsLike)}°C" }
                    )
                }
            }
            if (min != null && max != null) {
                Row(Modifier.fillMaxWidth().height(12.dp)) {
                    repeat(7) { index -> Box(Modifier.weight(1f).fillMaxHeight().background(relativeHeatMapColor(min + (max-min) * index/6.0, min, max))) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("較低 ${"%.1f".format(min)}°C", style = MaterialTheme.typography.bodySmall)
                    Text("較高 ${"%.1f".format(max)}°C", style = MaterialTheme.typography.bodySmall)
                }
                val updated = points.maxOf { it.sampledAtMillis }
                Text("25 個實際座標採樣 · 約 450m 間距 · 更新 ${SimpleDateFormat("HH:mm", Locale.TAIWAN).format(Date(updated))}", style = MaterialTheme.typography.bodySmall)
                Text("全圖半透明連續曲面由 25 個即時座標插值；實測數值來自 current conditions。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private data class ThermalSurface(val bitmap: Bitmap, val bounds: LatLngBounds)

private fun createThermalSurface(points: List<HeatMapPoint>, min: Double, max: Double): ThermalSurface {
    val sourceMinLat = points.minOf { it.location.latitude }
    val sourceMaxLat = points.maxOf { it.location.latitude }
    val sourceMinLng = points.minOf { it.location.longitude }
    val sourceMaxLng = points.maxOf { it.location.longitude }
    // Keep the bitmap boundary outside the camera so all visible map edges stay colorized.
    val latPadding = (sourceMaxLat - sourceMinLat) * 0.72
    val lngPadding = (sourceMaxLng - sourceMinLng) * 0.72
    val minLat = sourceMinLat - latPadding
    val maxLat = sourceMaxLat + latPadding
    val minLng = sourceMinLng - lngPadding
    val maxLng = sourceMaxLng + lngPadding
    val latSpan = (sourceMaxLat - sourceMinLat).coerceAtLeast(0.001)
    val lngSpan = (sourceMaxLng - sourceMinLng).coerceAtLeast(0.001)
    val size = 320
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val ny = y.toDouble() / (size - 1)
        val latitude = maxLat - ny * (maxLat - minLat)
        for (x in 0 until size) {
            val nx = x.toDouble() / (size - 1)
            val longitude = minLng + nx * (maxLng - minLng)
            var weightedTemperature = 0.0
            var totalWeight = 0.0
            points.forEach { point ->
                val dy = (latitude - point.location.latitude) / latSpan
                val dx = (longitude - point.location.longitude) / lngSpan
                val distance = sqrt(dx * dx + dy * dy).coerceAtLeast(0.008)
                val weight = 1.0 / distance.pow(2.15)
                weightedTemperature += point.temperature * weight
                totalWeight += weight
            }
            val temperature = weightedTemperature / totalWeight
            val color = relativeHeatMapColor(temperature, min, max)
            val alpha = (255 * 0.45).toInt()
            pixels[y * size + x] = android.graphics.Color.argb(
                alpha,
                (color.red * 255).toInt(),
                (color.green * 255).toInt(),
                (color.blue * 255).toInt()
            )
        }
    }
    return ThermalSurface(
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888),
        LatLngBounds(LatLng(minLat, minLng), LatLng(maxLat, maxLng))
    )
}

@Composable
fun AnalysisScreen(destination: String, demo: Boolean, viewModel: TripViewModel, onDone: () -> Unit) {
    val steps = listOf("取得目前位置", "分析天氣", "計算步行路線", "搜尋補給 / 避暑點", "計算高熱風險", "Gemini Agent 進行決策")
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) {
        viewModel.analyze(destination, demo, onDone)
    }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(50.dp)); if (demo || BuildConfig.GOOGLE_MAPS_WEB_API_KEY.isBlank()) DemoBadge(); Spacer(Modifier.height(28.dp))
        CircularProgressIndicator(); Spacer(Modifier.height(24.dp))
        Text("正在分析你的行程", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("前往 $destination", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(30.dp))
        steps.forEachIndexed { index, step ->
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                if (index < state.completedSteps) Icon(Icons.Default.CheckCircle, null, tint = RiskLow) else CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp)); Text(step)
            }
        }
    }
}

@Composable
fun ResultScreen(result: TripAnalysis, onBack: () -> Unit) {
    val original = result.routes.first()
    val recommended = result.routes[result.decision.recommendedRouteIndex.coerceIn(result.routes.indices)]
    val displayRisk = result.decision.riskLevel
    val riskIcon = when(displayRisk) { RiskLevel.LOW -> "❄"; RiskLevel.MEDIUM -> "⚠"; RiskLevel.HIGH -> "🔥" }
    val riskColor = when(displayRisk) { RiskLevel.LOW -> RiskLow; RiskLevel.MEDIUM -> RiskMedium; RiskLevel.HIGH -> RiskHigh }
    val durationDeltaSeconds = recommended.durationSeconds - original.durationSeconds
    val durationDeltaMinutes = (kotlin.math.abs(durationDeltaSeconds) + 30) / 60
    val durationComparison = when {
        recommended.id == original.id || durationDeltaSeconds == 0 -> "推薦路線與原路線時間相同"
        durationDeltaSeconds > 0 -> "多走 $durationDeltaMinutes 分鐘"
        else -> "少走 $durationDeltaMinutes 分鐘"
    }
    val context = LocalContext.current
    fun startMapsAndService() {
        HeatSafeOverlayService.start(context)
        val decoded = com.heatsafe.agent.util.PolylineUtils.decode(recommended.encodedPolyline)
        val waypoints = com.heatsafe.agent.util.PolylineUtils.sample(decoded, 800.0).drop(1).dropLast(1).take(3)
            .joinToString("|") { "${it.latitude},${it.longitude}" }
        val url = Uri.parse("https://www.google.com/maps/dir/").buildUpon()
            .appendQueryParameter("api", "1")
            .appendQueryParameter("origin", "${result.origin.latitude},${result.origin.longitude}")
            .appendQueryParameter("destination", "${result.destination.location.latitude},${result.destination.location.longitude}")
            .appendQueryParameter("travelmode", "walking")
            .apply { if (waypoints.isNotBlank()) appendQueryParameter("waypoints", waypoints) }.build()
        val mapsIntent = Intent(Intent.ACTION_VIEW, url).setPackage("com.google.android.apps.maps")
        runCatching { context.startActivity(mapsIntent) }.onFailure {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                .onFailure { Toast.makeText(context, "無法開啟 Google Maps", Toast.LENGTH_LONG).show() }
        }
    }
    val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(context)) startMapsAndService()
        else Toast.makeText(context, "需要懸浮視窗權限才能在導航上顯示 CoolPath", Toast.LENGTH_LONG).show()
    }
    Scaffold { padding ->
        Column(Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                when {
                    result.demoMode -> DemoBadge()
                    result.warnings.isNotEmpty() -> Text("DEGRADED DATA", color = RiskMedium, fontWeight = FontWeight.Bold)
                    else -> Text("LIVE DATA", color = RiskLow, fontWeight = FontWeight.Bold)
                }
                TextButton(onBack) { Text("重新分析") }
            }
            Text("$riskIcon ${displayRisk.name} RISK", color = riskColor, fontSize = 29.sp, fontWeight = FontWeight.Black)
            Text(result.decision.reason, fontSize = 17.sp, lineHeight = 25.sp)
            if (result.warnings.isNotEmpty()) {
                Surface(color = Color(0xFFFFF3D6), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("資料品質提醒", fontWeight = FontWeight.Bold, color = Color(0xFF805300))
                        result.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Color(0xFF805300)) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RouteCard("原路線", original, false, Modifier.weight(1f)); RouteCard("推薦路線", recommended, true, Modifier.weight(1f))
            }
            ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text(durationComparison, fontWeight = FontWeight.Bold); Text("Heat Risk ${original.heatRiskScore} → ${recommended.heatRiskScore}", color = if (recommended.heatRiskScore < original.heatRiskScore) RiskLow else riskColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            } }
            Button(onClick = {
                if (Settings.canDrawOverlays(context)) startMapsAndService()
                else overlayLauncher.launch(HeatSafeOverlayService.permissionIntent(context))
            }, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Text("在 Google Maps 開始步行導航")
            }
            Text("導航時 CoolPath 會在背景約每 5 分鐘同步分析；懸浮球顯示在 Google Maps 上方。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("補給 / 避暑點", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            result.coolingPlaces.take(4).forEach { Text("• ${it.name} · ${it.type} · 距路線約 ${it.distanceFromRouteMeters?.toInt()} m") }
            Text("安全提醒", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            result.decision.tips.forEach { AssistChip({}, { Text(it) }) }
            HeatSafeMap(result, recommended)
        }
    }
}

@Composable
private fun HeatSafeMap(result: TripAnalysis, recommended: RouteOption) {
    if (BuildConfig.MAPS_API_KEY.isBlank()) {
        Surface(color = Color(0xFFFFF0E9), shape = RoundedCornerShape(12.dp)) { Text("DEMO 地圖：設定 MAPS_API_KEY 後顯示互動式 Google Map", Modifier.padding(18.dp)) }
        return
    }
    val origin = LatLng(result.origin.latitude, result.origin.longitude)
    val destination = LatLng(result.destination.location.latitude, result.destination.location.longitude)
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(origin, 14f) }
    var mapLoaded by remember(result.destination) { mutableStateOf(false) }
    var heatPoints by remember(result.origin, result.demoMode) { mutableStateOf<List<HeatMapPoint>>(emptyList()) }
    var heatLayerLoading by remember(result.origin, result.demoMode) { mutableStateOf(!result.demoMode) }
    LaunchedEffect(result.origin, result.demoMode) {
        if (result.demoMode) {
            val offsets = listOf(-0.008, 0.0, 0.008)
            heatPoints = offsets.flatMapIndexed { row, latOffset -> offsets.mapIndexed { column, lngOffset ->
                HeatMapPoint(LatLngPoint(result.origin.latitude + latOffset, result.origin.longitude + lngOffset), result.weather.temperature + (row + column - 2) * 0.6, result.weather.feelsLike, System.currentTimeMillis())
            } }
            heatLayerLoading = false
        } else {
            val repository = NearbyHeatMapRepository()
            while (isActive) {
                val refreshed = repository.load(result.origin)
                if (refreshed.isNotEmpty()) heatPoints = refreshed // preserve last good layer on API failure
                heatLayerLoading = false
                delay(5 * 60 * 1000L)
            }
        }
    }
    LaunchedEffect(mapLoaded, origin, destination) {
        if (mapLoaded) {
            // CameraUpdateFactory is initialized by Maps SDK only after the renderer is ready.
            runCatching {
                val bounds = LatLngBounds.builder().include(origin).include(destination).build()
                camera.animate(CameraUpdateFactory.newLatLngBounds(bounds, 90))
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("附近即時氣溫", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            if (heatLayerLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        GoogleMap(
            modifier = Modifier.fillMaxWidth().height(360.dp),
            cameraPositionState = camera,
            onMapLoaded = { mapLoaded = true }
        ) {
            heatPoints.forEach { point ->
                Circle(
                    center = LatLng(point.location.latitude, point.location.longitude),
                    radius = 720.0,
                    fillColor = heatMapColor(point.temperature).copy(alpha = 0.25f),
                    strokeColor = heatMapColor(point.temperature).copy(alpha = 0.55f),
                    strokeWidth = 1.5f,
                    zIndex = 0f
                )
            }
            Marker(rememberUpdatedMarkerState(origin), title = "目前位置", snippet = "${result.weather.temperature}°C · 體感 ${result.weather.feelsLike}°C", zIndex = 5f)
            Marker(rememberUpdatedMarkerState(destination), title = result.destination.name, zIndex = 5f)
            result.routes.forEach { route ->
                val points = com.heatsafe.agent.util.PolylineUtils.decode(route.encodedPolyline).map { LatLng(it.latitude, it.longitude) }
                if (points.size > 1) Polyline(points, color = when(route.riskLevel) { RiskLevel.LOW -> RiskLow; RiskLevel.MEDIUM -> RiskMedium; RiskLevel.HIGH -> RiskHigh }, width = if (route.id == recommended.id) 15f else 7f, zIndex = if (route.id == recommended.id) 3f else 2f)
            }
            result.coolingPlaces.forEach { place -> Marker(rememberUpdatedMarkerState(LatLng(place.latitude, place.longitude)), title = place.name, snippet = "${place.type} · 距路線約 ${place.distanceFromRouteMeters?.toInt() ?: 0} m", zIndex = 5f) }
        }
        if (heatPoints.isNotEmpty()) {
            val min = heatPoints.minOf { it.temperature }; val max = heatPoints.maxOf { it.temperature }
            val updated = SimpleDateFormat("HH:mm", Locale.TAIWAN).format(Date(heatPoints.maxOf { it.sampledAtMillis }))
            Text("Google Weather 9 點採樣：${"%.1f".format(min)}–${"%.1f".format(max)}°C · 更新 $updated${if (result.demoMode) " · DEMO" else ""}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text("● <28", color = HeatCool); Text("● 28–31", color = RiskLow); Text("● 32–34", color = HeatWarm); Text("● ≥35°C", color = HeatHot) }
            Text("半透明色塊為約 0.9 km 間距的 current conditions 採樣近似，不代表人行道地表溫度。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (!heatLayerLoading) {
            Text("目前無法取得周邊氣溫採樣，路線分析仍可使用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable private fun RouteCard(title: String, route: RouteOption, selected: Boolean, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFFE4F5ED) else Color.White), border = if (selected) CardDefaults.outlinedCardBorder() else null) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.Bold); Text("${route.durationSeconds / 60} min", fontSize = 22.sp, fontWeight = FontWeight.Bold); Text("${route.distanceMeters} m"); Text("Heat Score ${route.heatRiskScore}"); Text(route.riskLevel.name, color = when(route.riskLevel) { RiskLevel.LOW -> RiskLow; RiskLevel.MEDIUM -> RiskMedium; RiskLevel.HIGH -> RiskHigh }, fontWeight = FontWeight.Bold)
        }
    }
}
