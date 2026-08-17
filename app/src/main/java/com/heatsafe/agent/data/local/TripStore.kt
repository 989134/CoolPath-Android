package com.heatsafe.agent.data.local

import android.content.Context
import com.heatsafe.agent.domain.model.*

object TripStore {
    private const val PREFS = "active_trip"
    fun saveDestination(context: Context, destination: Destination) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("place_id", destination.placeId).putString("name", destination.name)
            .putLong("lat", destination.location.latitude.toBits()).putLong("lng", destination.location.longitude.toBits()).apply()
    }
    fun destination(context: Context): Destination? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = p.getString("name", null) ?: return null
        return Destination(p.getString("place_id", "active") ?: "active", name,
            LatLngPoint(Double.fromBits(p.getLong("lat", 0)), Double.fromBits(p.getLong("lng", 0))))
    }
    fun saveAnalysis(context: Context, analysis: TripAnalysis) {
        saveDestination(context, analysis.destination)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("risk", analysis.decision.riskLevel.name)
            .putString("reason", analysis.decision.reason)
            .putLong("updated_at", System.currentTimeMillis()).apply()
    }
    fun summary(context: Context): Triple<RiskLevel, String, Long> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val risk = runCatching { RiskLevel.valueOf(p.getString("risk", "LOW")!!) }.getOrDefault(RiskLevel.LOW)
        return Triple(risk, p.getString("reason", "等待首次同步分析…") ?: "等待首次同步分析…", p.getLong("updated_at", 0))
    }
}
