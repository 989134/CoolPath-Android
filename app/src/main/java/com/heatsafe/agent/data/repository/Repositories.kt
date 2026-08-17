package com.heatsafe.agent.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.heatsafe.agent.BuildConfig
import com.heatsafe.agent.data.remote.*
import com.heatsafe.agent.domain.model.*
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class ApiResultException(message: String, cause: Throwable? = null) : Exception(message, cause)

class WeatherRepository {
    private val api = Retrofit.Builder().baseUrl("https://weather.googleapis.com/").client(client()).addConverterFactory(GsonConverterFactory.create()).build().create(WeatherApiService::class.java)
    suspend fun getWeather(point: LatLngPoint): WeatherInfo {
        require(BuildConfig.GOOGLE_MAPS_WEB_API_KEY.isNotBlank()) { "Weather API key missing" }
        return runCatching { api.current(BuildConfig.GOOGLE_MAPS_WEB_API_KEY, point.latitude, point.longitude) }.getOrElse { throw ApiResultException("Weather API unavailable", it) }.let {
            WeatherInfo(it.temperature?.degrees ?: throw ApiResultException("Weather temperature empty"), it.feelsLikeTemperature?.degrees ?: it.temperature.degrees!!, it.heatIndex?.degrees, it.relativeHumidity, it.uvIndex, it.weatherCondition?.description?.text ?: "未知", it.wind?.speed?.value)
        }
    }
}

class RoutesRepository {
    private val api = Retrofit.Builder().baseUrl("https://routes.googleapis.com/").client(client()).addConverterFactory(GsonConverterFactory.create()).build().create(RoutesApiService::class.java)
    suspend fun getRoutes(origin: LatLngPoint, destination: LatLngPoint): List<RouteOption> {
        require(BuildConfig.GOOGLE_MAPS_WEB_API_KEY.isNotBlank()) { "Routes API key missing" }
        val waypoint: (LatLngPoint) -> WaypointDto = { WaypointDto(LocationDto(ApiLatLng(it.latitude, it.longitude))) }
        val response = runCatching { api.compute(BuildConfig.GOOGLE_MAPS_WEB_API_KEY, request = RoutesRequest(waypoint(origin), waypoint(destination))) }.getOrElse { throw ApiResultException("Routes API unavailable", it) }
        return response.routes.orEmpty().mapIndexedNotNull { i, r ->
            val distance = r.distanceMeters ?: return@mapIndexedNotNull null
            RouteOption("route_$i", distance, r.duration?.removeSuffix("s")?.toDoubleOrNull()?.toInt() ?: 0, r.polyline?.encodedPolyline.orEmpty())
        }.ifEmpty { throw ApiResultException("No walking route") }
    }
}

class LocationRepository(private val context: Context) {
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): LatLngPoint? {
        val fine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        return suspendCancellableCoroutine { continuation ->
            val client = LocationServices.getFusedLocationProviderClient(context)
            val cancellation = CancellationTokenSource()
            continuation.invokeOnCancellation { cancellation.cancel() }
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
                .addOnSuccessListener { current ->
                    if (current != null) continuation.resume(LatLngPoint(current.latitude, current.longitude))
                    else client.lastLocation
                        .addOnSuccessListener { last -> continuation.resume(last?.let { LatLngPoint(it.latitude, it.longitude) }) }
                        .addOnFailureListener { continuation.resume(null) }
                }
                .addOnFailureListener { continuation.resume(null) }
        }
    }
}

private fun client() = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
