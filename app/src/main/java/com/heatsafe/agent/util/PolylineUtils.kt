package com.heatsafe.agent.util

import com.heatsafe.agent.domain.model.LatLngPoint
import kotlin.math.*

object PolylineUtils {
    fun decode(encoded: String): List<LatLngPoint> {
        val result = mutableListOf<LatLngPoint>(); var index = 0; var lat = 0; var lng = 0
        while (index < encoded.length) {
            fun next(): Int { var shift = 0; var value = 0; var b: Int; do { b = encoded[index++].code - 63; value = value or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20); return if (value and 1 != 0) (value shr 1).inv() else value shr 1 }
            lat += next(); lng += next(); result += LatLngPoint(lat / 1e5, lng / 1e5)
        }
        return result
    }

    fun sample(points: List<LatLngPoint>, intervalMeters: Double = 300.0): List<LatLngPoint> {
        if (points.size < 2) return points
        val sampled = mutableListOf(points.first()); var accumulated = 0.0
        points.zipWithNext().forEach { (a, b) -> accumulated += distanceMeters(a, b); if (accumulated >= intervalMeters) { sampled += b; accumulated = 0.0 } }
        if (sampled.last() != points.last()) sampled += points.last()
        return sampled
    }

    fun distanceMeters(a: LatLngPoint, b: LatLngPoint): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude); val dLng = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat/2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLng/2).pow(2)
        return 6_371_000 * 2 * atan2(sqrt(h), sqrt(1-h))
    }
}
