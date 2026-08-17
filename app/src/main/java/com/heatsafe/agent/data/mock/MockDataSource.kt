package com.heatsafe.agent.data.mock

import com.heatsafe.agent.domain.model.*

object MockDataSource {
    val origin = LatLngPoint(25.0330, 121.5654)
    val destination = Destination("demo_taipei_101", "台北 101", LatLngPoint(25.0338, 121.5646))
    val weather = WeatherInfo(35.0, 39.0, 40.0, 72, 9, "晴朗炎熱", 9.0)
    val places = listOf(
        CoolingPlace("p1", "信義便利商店", 25.0332, 121.5651, "便利商店", 35.0),
        CoolingPlace("p2", "市府公共圖書館", 25.0340, 121.5638, "圖書館", 62.0),
        CoolingPlace("p3", "信義購物中心", 25.0342, 121.5642, "購物中心", 48.0),
        CoolingPlace("p4", "街角咖啡", 25.0336, 121.5640, "咖啡店", 71.0)
    )
    val routes = listOf(
        RouteOption("default", 900, 660, "", 8, RiskLevel.HIGH),
        RouteOption("safer", 1050, 840, "", 5, RiskLevel.MEDIUM)
    )
    val decision = AgentDecision(
        RiskLevel.HIGH, AgentAction.REROUTE, 1,
        "目前紫外線及體感溫度偏高，替代路線雖增加約三分鐘，但沿途有較多補給與避暑場所，因此建議改走替代路線。",
        listOf("攜帶飲用水", "避免長時間曝曬"),
        "目前路線熱風險較高，建議查看較安全的替代路線。"
    )
    fun analysis(destinationName: String = destination.name) = TripAnalysis(
        origin, destination.copy(name = destinationName.ifBlank { destination.name }), weather, routes, places, decision, true
    )
}
