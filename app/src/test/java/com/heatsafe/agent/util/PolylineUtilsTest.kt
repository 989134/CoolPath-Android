package com.heatsafe.agent.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineUtilsTest {
    @Test fun decodesGoogleReferencePolyline() {
        val points = PolylineUtils.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, points.size)
        assertEquals(38.5, points.first().latitude, 0.00001)
        assertEquals(-120.2, points.first().longitude, 0.00001)
    }

    @Test fun samplingAlwaysKeepsEndpoints() {
        val points = PolylineUtils.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        val sampled = PolylineUtils.sample(points, 300.0)
        assertEquals(points.first(), sampled.first())
        assertEquals(points.last(), sampled.last())
        assertTrue(sampled.size <= points.size)
    }
}
