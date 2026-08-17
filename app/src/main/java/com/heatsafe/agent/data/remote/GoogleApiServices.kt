package com.heatsafe.agent.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.*

interface WeatherApiService {
    @GET("v1/currentConditions:lookup")
    suspend fun current(
        @Query("key") key: String,
        @Query("location.latitude") latitude: Double,
        @Query("location.longitude") longitude: Double,
        @Query("languageCode") language: String = "zh-TW",
        @Query("unitsSystem") units: String = "METRIC"
    ): WeatherResponse
}
data class TemperatureDto(val degrees: Double?)
data class DescriptionDto(val text: String?)
data class WeatherConditionDto(val description: DescriptionDto?)
data class WindSpeedDto(val value: Double?)
data class WindDto(val speed: WindSpeedDto?)
data class WeatherResponse(
    val temperature: TemperatureDto?,
    val feelsLikeTemperature: TemperatureDto?,
    val heatIndex: TemperatureDto?,
    val relativeHumidity: Int?,
    val uvIndex: Int?,
    val weatherCondition: WeatherConditionDto?,
    val wind: WindDto?
)

interface RoutesApiService {
    @POST("directions/v2:computeRoutes")
    suspend fun compute(
        @Header("X-Goog-Api-Key") key: String,
        @Header("X-Goog-FieldMask") fieldMask: String = "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline,routes.routeLabels",
        @Body request: RoutesRequest
    ): RoutesResponse
}
data class ApiLatLng(val latitude: Double, val longitude: Double)
data class LocationDto(val latLng: ApiLatLng)
data class WaypointDto(val location: LocationDto)
data class RoutesRequest(
    val origin: WaypointDto,
    val destination: WaypointDto,
    val travelMode: String = "WALK",
    val computeAlternativeRoutes: Boolean = true,
    val polylineQuality: String = "HIGH_QUALITY"
)
data class RoutePolylineDto(val encodedPolyline: String?)
data class RouteDto(val distanceMeters: Int?, val duration: String?, val polyline: RoutePolylineDto?, val routeLabels: List<String>?)
data class RoutesResponse(val routes: List<RouteDto>?)
