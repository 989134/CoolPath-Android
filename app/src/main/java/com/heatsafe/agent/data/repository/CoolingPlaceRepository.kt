package com.heatsafe.agent.data.repository

import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.CircularBounds
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.SearchNearbyRequest
import com.heatsafe.agent.domain.model.CoolingPlace
import com.heatsafe.agent.domain.model.LatLngPoint
import com.heatsafe.agent.util.PolylineUtils
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class CoolingPlaceRepository {
    private val types = listOf("convenience_store", "supermarket", "shopping_mall", "cafe", "library")

    suspend fun findAlongRoute(points: List<LatLngPoint>): List<CoolingPlace> {
        if (!Places.isInitialized() || points.isEmpty()) return emptyList()
        val client = Places.createClient(com.heatsafe.agent.AppContext.context)
        val all = linkedMapOf<String, CoolingPlace>()
        PolylineUtils.sample(points).take(8).forEach { sample ->
            val bounds = CircularBounds.newInstance(LatLng(sample.latitude, sample.longitude), 350.0)
            val fields = listOf(Place.Field.ID, Place.Field.DISPLAY_NAME, Place.Field.LOCATION, Place.Field.PRIMARY_TYPE)
            val request = SearchNearbyRequest.builder(bounds, fields).setIncludedTypes(types).setMaxResultCount(5).build()
            val places = suspendCancellableCoroutine<List<Place>> { continuation ->
                client.searchNearby(request).addOnSuccessListener { continuation.resume(it.places) }.addOnFailureListener { continuation.resume(emptyList()) }
            }
            places.forEach { place ->
                val id = place.id ?: return@forEach; val latLng = place.location ?: return@forEach
                val point = LatLngPoint(latLng.latitude, latLng.longitude)
                val distance = points.minOfOrNull { PolylineUtils.distanceMeters(it, point) }
                all[id] = CoolingPlace(id, place.displayName ?: "補給 / 避暑點", latLng.latitude, latLng.longitude, place.primaryType ?: "place", distance)
            }
        }
        return all.values.sortedBy { it.distanceFromRouteMeters ?: Double.MAX_VALUE }.take(5)
    }
}
