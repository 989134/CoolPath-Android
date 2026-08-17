package com.heatsafe.agent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.heatsafe.agent.data.mock.MockDataSource
import com.heatsafe.agent.domain.model.TripAnalysis
import com.heatsafe.agent.domain.usecase.AnalyzeTripUseCase
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.heatsafe.agent.data.local.TripStore

data class AnalysisUiState(val running: Boolean = false, val completedSteps: Int = 0, val result: TripAnalysis? = null, val message: String? = null)

class TripViewModel(app: Application) : AndroidViewModel(app) {
    private val useCase = AnalyzeTripUseCase(app)
    private val _state = MutableStateFlow(AnalysisUiState(result = MockDataSource.analysis()))
    val state = _state.asStateFlow()
    private var selectedDestination: com.heatsafe.agent.domain.model.Destination? = null

    fun selectDestination(destination: com.heatsafe.agent.domain.model.Destination) {
        selectedDestination = destination
        TripStore.saveDestination(getApplication(), destination)
    }

    fun analyze(destination: String, demo: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            _state.value = AnalysisUiState(running = true)
            val result = useCase(destination, selectedDestination, demo) { step -> _state.update { it.copy(completedSteps = step) } }
            TripStore.saveAnalysis(getApplication(), result)
            _state.update { it.copy(running = false, result = result, message = result.warnings.firstOrNull()) }
            onDone()
        }
    }
}
