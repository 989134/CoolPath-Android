package com.heatsafe.agent.worker

import android.content.Context
import androidx.work.*
import com.heatsafe.agent.domain.usecase.AnalyzeTripUseCase
import com.heatsafe.agent.notification.HeatNotificationManager
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class HeatRiskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        val analysis = AnalyzeTripUseCase(applicationContext)(inputData.getString(KEY_DESTINATION).orEmpty(), forceDemo = false) {}
        HeatNotificationManager.show(applicationContext, analysis.decision.riskLevel, analysis.decision.notificationText)
        Result.success()
    }.getOrElse { Result.retry() }

    companion object { const val KEY_DESTINATION = "destination" }
}

class TripScheduler(private val context: Context) {
    fun schedule(destination: String, departure: ZonedDateTime) {
        val analyzeAt = departure.minusMinutes(20)
        val delayMs = Duration.between(ZonedDateTime.now(), analyzeAt).toMillis().coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<HeatRiskWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(HeatRiskWorker.KEY_DESTINATION to destination))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("heat_trip_${departure.toInstant().toEpochMilli()}", ExistingWorkPolicy.REPLACE, request)
    }
}
