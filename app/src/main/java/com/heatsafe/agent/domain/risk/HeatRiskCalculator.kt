package com.heatsafe.agent.domain.risk

import com.heatsafe.agent.domain.model.*

/** This score is a prototype decision heuristic and is not a medical risk assessment. */
object HeatRiskCalculator {
    fun calculate(weather: WeatherInfo, durationSeconds: Int, coolingPlaceCount: Int): Pair<Int, RiskLevel> {
        val apparent = weather.heatIndex ?: weather.feelsLike
        val heat = when { apparent < 32 -> 0; apparent < 38 -> 1; apparent <= 41 -> 2; else -> 3 }
        val uv = when (weather.uvIndex) { null -> 0; in 0..5 -> 0; in 6..7 -> 1; in 8..10 -> 2; else -> 3 }
        val minutes = durationSeconds / 60.0
        val walking = when { minutes <= 10 -> 0; minutes <= 20 -> 1; else -> 2 }
        val resources = when { coolingPlaceCount >= 2 -> 0; coolingPlaceCount == 1 -> 1; else -> 2 }
        val score = heat + uv + walking + resources
        return score to when { score <= 3 -> RiskLevel.LOW; score <= 6 -> RiskLevel.MEDIUM; else -> RiskLevel.HIGH }
    }
}
