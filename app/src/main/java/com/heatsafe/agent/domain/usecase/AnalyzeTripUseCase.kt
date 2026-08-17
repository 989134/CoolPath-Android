package com.heatsafe.agent.domain.usecase

import android.content.Context
import com.heatsafe.agent.BuildConfig
import com.heatsafe.agent.data.mock.MockDataSource
import com.heatsafe.agent.data.remote.GeminiDecisionService
import com.heatsafe.agent.data.repository.*
import com.heatsafe.agent.domain.model.*
import com.heatsafe.agent.domain.risk.HeatRiskCalculator
import com.heatsafe.agent.util.PolylineUtils
import android.util.Log

class AnalyzeTripUseCase(context: Context) {
    private val location = LocationRepository(context)
    private val weather = WeatherRepository()
    private val routes = RoutesRepository()
    private val cooling = CoolingPlaceRepository()
    private val gemini = GeminiDecisionService()

    suspend operator fun invoke(destinationName: String, selectedDestination: Destination? = null, forceDemo: Boolean, onStep: (Int) -> Unit): TripAnalysis {
        if (forceDemo) return mock(destinationName, onStep, "使用者啟動 Demo Mode")
        if (BuildConfig.GOOGLE_MAPS_WEB_API_KEY.isBlank()) return mock(destinationName, onStep, "GOOGLE_MAPS_WEB_API_KEY 未設定")
        return runCatching {
            val origin = location.currentLocation() ?: throw ApiResultException("GPS unavailable"); onStep(1)
            val weatherInfo = weather.getWeather(origin); onStep(2)
            val destination = selectedDestination ?: throw ApiResultException("Please select a destination from Google Places")
            val rawRoutes = routes.getRoutes(origin, destination.location); onStep(3)
            val routePoints = rawRoutes.flatMap { PolylineUtils.decode(it.encodedPolyline) }
            val places = cooling.findAlongRoute(routePoints); onStep(4)
            val warnings = mutableListOf<String>()
            if (places.isEmpty()) warnings += "路線附近沒有搜尋到補給／避暑點"
            val scored = rawRoutes.map { route ->
                val (score, risk) = HeatRiskCalculator.calculate(weatherInfo, route.durationSeconds, places.size)
                route.copy(heatRiskScore = score, riskLevel = risk)
            }; onStep(5)
            val fallback = fallbackDecision(scored)
            val geminiResult = runCatching { gemini.decide(weatherInfo, scored, places) }
            val decision = geminiResult.getOrElse {
                Log.w(TAG, "Gemini failed; deterministic fallback used", it)
                warnings += "Gemini 暫時無法使用，已改用確定性風險規則"
                fallback
            }; onStep(6)
            TripAnalysis(origin, destination, weatherInfo, scored, places, decision, false, warnings)
        }.getOrElse { error ->
            Log.e(TAG, "Live trip analysis failed", error)
            mock(destinationName, onStep, readableError(error))
        }
    }

    private fun mock(name: String, onStep: (Int) -> Unit, reason: String): TripAnalysis {
        (1..6).forEach(onStep)
        return MockDataSource.analysis(name).copy(warnings = listOf(reason))
    }

    private fun readableError(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.mapNotNull { it.message }.distinct().take(3).toList()
        return chain.joinToString(" → ").ifBlank { error::class.java.simpleName }
    }

    private fun fallbackDecision(routes: List<RouteOption>): AgentDecision {
        val bestIndex = routes.indices.minByOrNull { routes[it].heatRiskScore } ?: 0
        val initial = routes.firstOrNull()?.riskLevel ?: RiskLevel.LOW
        return AgentDecision(initial, when(initial) { RiskLevel.LOW -> AgentAction.KEEP_ROUTE; RiskLevel.MEDIUM -> AgentAction.CAUTION; RiskLevel.HIGH -> AgentAction.REROUTE }, bestIndex,
            "AI 服務暫時無法使用，已依確定性熱風險分數推薦較安全路線。", listOf("攜帶飲用水", "留意身體不適"), "目前路線熱風險為 ${initial.name}，請查看安全路線建議。")
    }

    private companion object { const val TAG = "HeatSafeAnalysis" }
}
