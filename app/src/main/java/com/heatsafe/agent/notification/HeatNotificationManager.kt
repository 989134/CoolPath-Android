package com.heatsafe.agent.notification

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.heatsafe.agent.MainActivity
import com.heatsafe.agent.R
import com.heatsafe.agent.domain.model.RiskLevel

object HeatNotificationManager {
    const val CHANNEL_ID = "heat_risk_alerts"
    const val SERVICE_CHANNEL_ID = "heatsafe_background_service"
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "CoolPath 高溫警示", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "出發前的路線熱風險與安全提醒"
                enableVibration(true)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(SERVICE_CHANNEL_ID, "CoolPath 背景服務", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "顯示使用者啟用的 CoolPath 懸浮球"
                    setShowBadge(false)
                }
            )
        }
    }

    @Suppress("MissingPermission")
    fun show(context: Context, risk: RiskLevel, body: String) {
        if (risk == RiskLevel.LOW) return
        val pending = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).putExtra("open_result", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(if (risk == RiskLevel.HIGH) "CoolPath 高溫警示" else "CoolPath 行程提醒")
            .setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(pending).setAutoCancel(true)
            .setPriority(if (risk == RiskLevel.HIGH) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .apply { if (risk == RiskLevel.HIGH) setVibrate(longArrayOf(0, 400, 200, 400)) }.build()
        runCatching { NotificationManagerCompat.from(context).notify(1001, notification) }
    }
}
