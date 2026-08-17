package com.heatsafe.agent.domain.model

enum class RiskLevel { LOW, MEDIUM, HIGH }
enum class AgentAction { KEEP_ROUTE, CAUTION, REROUTE }

data class LatLngPoint(val latitude: Double, val longitude: Double)
data class Destination(val placeId: String, val name: String, val location: LatLngPoint)
data class WeatherInfo(
    val temperature: Double,
    val feelsLike: Double,
    val heatIndex: Double?,
    val humidity: Int?,
    val uvIndex: Int?,
    val condition: String,
    val windKph: Double? = null
)
data class RouteOption(
    val id: String,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val encodedPolyline: String,
    val heatRiskScore: Int = 0,
    val riskLevel: RiskLevel = RiskLevel.LOW
)
data class CoolingPlace(
    val placeId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val type: String,
    val distanceFromRouteMeters: Double?
)
data class HeatMapPoint(
    val location: LatLngPoint,
    val temperature: Double,
    val feelsLike: Double,
    val sampledAtMillis: Long
)
data class AgentDecision(
    val riskLevel: RiskLevel,
    val action: AgentAction,
    val recommendedRouteIndex: Int,
    val reason: String,
    val tips: List<String>,
    val notificationText: String
)
data class TripAnalysis(
    val origin: LatLngPoint,
    val destination: Destination,
    val weather: WeatherInfo,
    val routes: List<RouteOption>,
    val coolingPlaces: List<CoolingPlace>,
    val decision: AgentDecision,
    val demoMode: Boolean,
    val warnings: List<String> = emptyList()
)
