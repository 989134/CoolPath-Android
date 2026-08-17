package com.heatsafe.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.heatsafe.agent.ui.HeatSafeApp
import com.heatsafe.agent.ui.theme.HeatSafeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HeatSafeTheme { HeatSafeApp() } }
    }
}
