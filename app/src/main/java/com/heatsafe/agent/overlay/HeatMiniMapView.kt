package com.heatsafe.agent.overlay

import android.content.Context
import android.graphics.*
import android.view.View
import android.location.Location
import com.heatsafe.agent.domain.model.*
import java.text.SimpleDateFormat
import java.util.*

class HeatMiniMapView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var analysis: TripAnalysis? = null
    private var points: List<HeatMapPoint> = emptyList()
    private var liveLocation: Location? = null

    fun update(analysis: TripAnalysis, points: List<HeatMapPoint>, liveLocation: Location? = null) {
        this.analysis = analysis; this.points = points; this.liveLocation = liveLocation; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        paint.color = Color.argb(238, 24, 28, 34)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 22*density, 22*density, paint)
        val current = analysis
        paint.color = Color.WHITE; paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = 18*density
        canvas.drawText("CoolPath 即時高溫 HUD", 16*density, 30*density, paint)
        if (points.isNotEmpty()) {
            val left = 18*density; val top = 44*density; val mapSize = 160*density; val cell = mapSize/3
            val sorted = points.sortedWith(compareBy<HeatMapPoint> { it.location.latitude }.thenBy { it.location.longitude })
            sorted.take(9).forEachIndexed { index, point ->
                val row = 2 - index/3; val column = index%3
                paint.color = temperatureColor(point.temperature)
                canvas.drawRoundRect(left + column*cell, top + row*cell, left+(column+1)*cell-3, top+(row+1)*cell-3, 10f, 10f, paint)
                paint.color = Color.WHITE; paint.textSize = 13*density; paint.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText("${"%.1f".format(point.temperature)}°", left+column*cell+8, top+row*cell+cell/2, paint)
            }
            paint.color = Color.WHITE; canvas.drawCircle(left+mapSize/2, top+mapSize/2, 7*density, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3*density; paint.color = Color.BLACK
            canvas.drawCircle(left+mapSize/2, top+mapSize/2, 7*density, paint); paint.style = Paint.Style.FILL
        }
        val textX = 195*density
        paint.color = riskColor(current?.decision?.riskLevel ?: RiskLevel.LOW); paint.textSize = 19*density; paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("${current?.decision?.riskLevel ?: "WAIT"}", textX, 70*density, paint)
        paint.color = Color.WHITE; paint.textSize = 15*density
        canvas.drawText("現在 ${current?.weather?.temperature?.let { "%.1f°C".format(it) } ?: "--"}", textX, 100*density, paint)
        canvas.drawText("體感 ${current?.weather?.feelsLike?.let { "%.1f°C".format(it) } ?: "--"}", textX, 125*density, paint)
        canvas.drawText("UV ${current?.weather?.uvIndex ?: "--"}", textX, 150*density, paint)
        val nearest = current?.coolingPlaces?.minByOrNull { it.distanceFromRouteMeters ?: Double.MAX_VALUE }
        paint.textSize = 12*density; paint.color = Color.rgb(190, 235, 220)
        canvas.drawText("避暑 ${nearest?.name?.take(10) ?: "搜尋中"}", textX, 174*density, paint)
        val speed = liveLocation?.takeIf { it.hasSpeed() }?.speed?.times(3.6f)
        paint.color = Color.LTGRAY
        canvas.drawText("GPS ${speed?.let { "%.1f km/h".format(it) } ?: "定位中"}", textX, 194*density, paint)
        val updated = points.maxOfOrNull { it.sampledAtMillis } ?: System.currentTimeMillis()
        paint.color = Color.LTGRAY; paint.textSize = 12*density
        canvas.drawText("雲端更新 ${SimpleDateFormat("HH:mm", Locale.TAIWAN).format(Date(updated))}", 18*density, 226*density, paint)
        canvas.drawText("白點＝目前位置｜GPS 每 5 秒更新", 18*density, 246*density, paint)
        val buttonTop = 258*density
        paint.color = Color.rgb(232, 93, 42)
        canvas.drawRoundRect(18*density, buttonTop, width-18*density, buttonTop+42*density, 14*density, 14*density, paint)
        paint.color = Color.WHITE; paint.textSize = 15*density; paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("在 Google Maps 查看較安全路線", 43*density, buttonTop+27*density, paint)
    }

    private fun temperatureColor(value: Double) = when {
        value < 28 -> Color.rgb(46,134,222)
        value < 32 -> Color.rgb(27,138,90)
        value < 35 -> Color.rgb(255,176,0)
        else -> Color.rgb(229,57,53)
    }
    private fun riskColor(risk: RiskLevel) = when(risk) {
        RiskLevel.LOW -> Color.rgb(70,210,140); RiskLevel.MEDIUM -> Color.rgb(255,176,0); RiskLevel.HIGH -> Color.rgb(255,90,90)
    }
}
