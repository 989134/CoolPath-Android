package com.heatsafe.agent.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val RiskLow = Color(0xFF1B8A5A)
val RiskMedium = Color(0xFFF28C28)
val RiskHigh = Color(0xFFD64045)
val HeatOrange = Color(0xFFE85D2A)
val HeatCool = Color(0xFF2E86DE)
val HeatWarm = Color(0xFFFFB000)
val HeatHot = Color(0xFFE53935)
val HeatNavy = Color(0xFF17212B)
val HeatCream = Color(0xFFFFF8F3)
val HeatSoft = Color(0xFFFFEEE5)
val HeatCoral = Color(0xFFFF6438)
val HeatGold = Color(0xFFFFB347)
val CoolBlue = Color(0xFF1677D2)
val CoolSky = Color(0xFF55B9F3)

fun heatMapColor(celsius: Double): Color = when {
    celsius < 28.0 -> HeatCool
    celsius < 32.0 -> RiskLow
    celsius < 35.0 -> HeatWarm
    else -> HeatHot
}

/** High-contrast relative scale for a single local sampling window. */
fun relativeHeatMapColor(celsius: Double, min: Double, max: Double): Color {
    val span = (max - min).coerceAtLeast(1.0)
    val t = ((celsius - min) / span).coerceIn(0.0, 1.0).toFloat()
    val stops = listOf(
        Color(0xFF5E2B97), Color(0xFF2455D6), Color(0xFF00AEEF),
        Color(0xFF00A86B), Color(0xFFFFD600), Color(0xFFFF7A00), Color(0xFFD7191C)
    )
    val position = t * (stops.size - 1)
    val index = position.toInt().coerceAtMost(stops.lastIndex - 1)
    return lerp(stops[index], stops[index + 1], position - index)
}

/** Fixed temperature scale: the same temperature always receives the same color. */
fun preciseTemperatureColor(celsius: Double): Color {
    val stops = listOf(
        20.0 to Color(0xFF4527A0),
        24.0 to Color(0xFF3155C6),
        27.0 to Color(0xFF00A6D6),
        29.0 to Color(0xFF00A878),
        31.0 to Color(0xFF9BC53D),
        33.0 to Color(0xFFFFD23F),
        35.0 to Color(0xFFFF8C1A),
        38.0 to Color(0xFFE53935),
        42.0 to Color(0xFF8E0038)
    )
    if (celsius <= stops.first().first) return stops.first().second
    if (celsius >= stops.last().first) return stops.last().second
    val upperIndex = stops.indexOfFirst { celsius <= it.first }
    val lower = stops[upperIndex - 1]
    val upper = stops[upperIndex]
    val fraction = ((celsius - lower.first) / (upper.first - lower.first)).toFloat()
    return lerp(lower.second, upper.second, fraction)
}

private val colors = lightColorScheme(
    primary = CoolBlue,
    secondary = Color(0xFF006B5E),
    background = HeatCream,
    surface = Color.White,
    surfaceVariant = HeatSoft,
    onBackground = HeatNavy,
    onSurface = HeatNavy,
    error = RiskHigh
)

private val typography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 35.sp, fontWeight = FontWeight.Black),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp)
)

private val shapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp)
)

@Composable fun HeatSafeTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = colors,
    typography = typography,
    shapes = shapes,
    content = content
)
