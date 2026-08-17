package com.heatsafe.agent.data.remote

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.heatsafe.agent.domain.model.*
import org.json.JSONObject

class GeminiDecisionService {
    suspend fun decide(weather: WeatherInfo, routes: List<RouteOption>, places: List<CoolingPlace>): AgentDecision {
        val prompt = buildString {
            appendLine("你是 CoolPath Decision Agent。只能根據以下資料判斷，不可自行產生氣象數值。只回傳有效 JSON，不要 markdown。")
            appendLine("JSON schema: {\"riskLevel\":\"LOW|MEDIUM|HIGH\",\"action\":\"KEEP_ROUTE|CAUTION|REROUTE\",\"recommendedRouteIndex\":0,\"reason\":\"中文\",\"tips\":[\"中文\"],\"notificationText\":\"中文\"}")
            appendLine("Current Weather: temperature=${weather.temperature}, feelsLike=${weather.feelsLike}, heatIndex=${weather.heatIndex}, UV=${weather.uvIndex}, humidity=${weather.humidity}")
            routes.forEachIndexed { i, r -> appendLine("Route $i: distance=${r.distanceMeters}m duration=${r.durationSeconds}s heatScore=${r.heatRiskScore} risk=${r.riskLevel} coolingPlaces=${places.size}") }
        }
        val text = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel("gemini-2.5-flash").generateContent(prompt).text
            ?: throw IllegalStateException("Gemini empty response")
        return parse(text, routes.lastIndex)
    }

    internal fun parse(raw: String, maxRouteIndex: Int): AgentDecision {
        val json = JSONObject(raw.trim().removePrefix("```json").removeSuffix("```").trim())
        val tipsArray = json.optJSONArray("tips")
        val tips = buildList { if (tipsArray != null) for (i in 0 until tipsArray.length()) add(tipsArray.optString(i)) }
        return AgentDecision(
            RiskLevel.valueOf(json.getString("riskLevel")), AgentAction.valueOf(json.getString("action")),
            json.optInt("recommendedRouteIndex", 0).coerceIn(0, maxRouteIndex.coerceAtLeast(0)),
            json.optString("reason", "已依熱風險選擇路線。"), tips,
            json.optString("notificationText", "請查看 CoolPath 路線分析。")
        )
    }
}
