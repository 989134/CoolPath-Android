package com.heatsafe.agent.domain.risk

import com.heatsafe.agent.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class HeatRiskCalculatorTest {
    @Test fun highRiskMatchesPrototypeBands() {
        val weather = WeatherInfo(35.0, 39.0, 40.0, 70, 9, "晴")
        val (score, risk) = HeatRiskCalculator.calculate(weather, 21 * 60, 0)
        assertEquals(8, score)
        assertEquals(RiskLevel.HIGH, risk)
    }

    @Test fun nullOptionalWeatherDoesNotCrash() {
        val (score, risk) = HeatRiskCalculator.calculate(WeatherInfo(30.0, 30.0, null, null, null, "未知"), 600, 2)
        assertEquals(0, score)
        assertEquals(RiskLevel.LOW, risk)
    }
}
