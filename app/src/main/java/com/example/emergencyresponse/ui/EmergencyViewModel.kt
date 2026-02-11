package com.example.emergencyresponse.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import com.example.emergencyresponse.model.AppConfig
import com.example.emergencyresponse.model.EmergencyEvent
import com.example.emergencyresponse.model.EmergencyUiModel
import com.example.emergencyresponse.model.EmergencyUiState
import com.example.emergencyresponse.model.UserProfile
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Shared state holder for the emergency response flow.
 * Owns the canonical [EmergencyUiModel] (render state) and [EmergencyEvent] (dispatch payload).
 *
 * All state transitions go through this ViewModel so that the Activity, Service,
 * and future Compose/UI layers can observe a single source of truth.
 */
class EmergencyViewModel : ViewModel() {
    companion object {
        private const val TAG = "EmergencyViewModel"
    }

    private val _uiModel = MutableStateFlow(EmergencyUiModel())
    val uiModel: StateFlow<EmergencyUiModel> = _uiModel.asStateFlow()

    private val _emergencyEvent = MutableStateFlow(EmergencyEvent())
    val emergencyEvent: StateFlow<EmergencyEvent> = _emergencyEvent.asStateFlow()

    /** One-shot events for side-effects (location fetch, dispatch, etc.) */
    private val _stateEvents = MutableSharedFlow<EmergencyUiState>(extraBufferCapacity = 8)
    val stateEvents: SharedFlow<EmergencyUiState> = _stateEvents.asSharedFlow()

    private fun transitionTo(state: EmergencyUiState) {
        Log.d(TAG, "Transition -> $state")
        _uiModel.update { it.copy(state = state) }
        _stateEvents.tryEmit(state)
    }

    fun onEmergencyTrigger(source: String) {
        Log.i(TAG, "Emergency trigger received from source=$source")
        transitionTo(EmergencyUiState.DROP_COUNTDOWN)
    }

    fun onCountdownFinished() {
        transitionTo(EmergencyUiState.SERVICE_SELECTION)
    }

    fun onServiceSelected(service: String) {
        _emergencyEvent.update { it.copy(serviceType = service) }
        _uiModel.update { it.copy(selectedService = service) }
        transitionTo(EmergencyUiState.CONTEXT_SELECTION)
    }

    fun onContextSelected(context: String) {
        _emergencyEvent.update { it.copy(context = context) }
        _uiModel.update { it.copy(selectedContext = context) }
        transitionTo(EmergencyUiState.LOCATION_CONFIRM)
    }

    fun onLocationResolved(address: String, latitude: Double = 0.0, longitude: Double = 0.0) {
        _uiModel.update { it.copy(resolvedAddress = address, latitude = latitude, longitude = longitude) }
        _emergencyEvent.update { it.copy(address = address, latitude = latitude, longitude = longitude) }
    }

    fun onLocationConfirmed() {
        transitionTo(EmergencyUiState.DISPATCH_ACTIVE)
    }

    fun onCancel() {
        Log.i(TAG, "Emergency flow cancelled; resetting to IDLE")
        _uiModel.value = EmergencyUiModel()
        _emergencyEvent.value = EmergencyEvent()
        _stateEvents.tryEmit(EmergencyUiState.IDLE)
    }

    fun enrichFromProfile(profile: UserProfile) {
        _emergencyEvent.update {
            it.copy(
                unitNumber = profile.unitNumber.ifBlank { AppConfig.DEFAULT_UNIT_NUMBER },
                caregiverNumber = profile.caregiverNumber.ifBlank { AppConfig.DEFAULT_CAREGIVER_NUMBER },
                secondaryContact = profile.secondaryContact,
                userName = profile.name
            )
        }
    }
}
