package com.heatsafe.agent.data.repository

import com.heatsafe.agent.domain.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Samples Google Weather current conditions around the user. This is a coarse API-sampled
 * visualization, not street-level sensor data or sidewalk surface temperature.
 */
class NearbyHeatMapRepository(private val weather: WeatherRepository = WeatherRepository()) {
    suspend fun load(
        center: LatLngPoint,
        gridSize: Int = 3,
        latitudeStep: Double = 0.008,
        longitudeStep: Double = 0.009,
        forceRefresh: Boolean = false
    ): List<HeatMapPoint> = coroutineScope {
        require(gridSize in 3..7 && gridSize % 2 == 1) { "gridSize must be an odd number from 3 to 7" }
        val cacheKey = "${"%.3f".format(java.util.Locale.US, center.latitude)},${"%.3f".format(java.util.Locale.US, center.longitude)}:$gridSize:$latitudeStep:$longitudeStep"
        if (!forceRefresh) synchronized(cache) {
            cache[cacheKey]?.takeIf { System.currentTimeMillis() - it.savedAt < CACHE_TTL_MS }?.let { return@coroutineScope it.points }
        }
        val half = gridSize / 2
        val points = (-half..half).flatMap { row ->
            (-half..half).map { column ->
                LatLngPoint(center.latitude + row * latitudeStep, center.longitude + column * longitudeStep)
            }
        }
        val limiter = Semaphore(3)
        val loaded = points.map { point ->
            async {
                limiter.withPermit {
                    runCatching { weather.getWeather(point) }.getOrNull()?.let {
                        HeatMapPoint(point, it.temperature, it.feelsLike, System.currentTimeMillis())
                    }
                }
            }
        }.awaitAll().filterNotNull()
        if (loaded.isNotEmpty()) synchronized(cache) { cache[cacheKey] = CacheEntry(System.currentTimeMillis(), loaded) }
        loaded
    }

    private data class CacheEntry(val savedAt: Long, val points: List<HeatMapPoint>)
    private companion object {
        const val CACHE_TTL_MS = 5 * 60 * 1000L
        val cache = mutableMapOf<String, CacheEntry>()
    }
}
