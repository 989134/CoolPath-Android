package com.heatsafe.agent

import android.app.Application
import com.google.android.libraries.places.api.Places
import com.heatsafe.agent.notification.HeatNotificationManager

class HeatSafeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContext.context = applicationContext
        if (BuildConfig.MAPS_API_KEY.isNotBlank() && !Places.isInitialized()) {
            runCatching { Places.initializeWithNewPlacesApiEnabled(this, BuildConfig.MAPS_API_KEY) }
        }
        HeatNotificationManager.createChannel(this)
    }
}
