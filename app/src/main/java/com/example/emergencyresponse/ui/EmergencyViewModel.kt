package com.example.emergencyresponse.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import com.example.emergencyresponse.model.AppConfig
import com.example.emergencyresponse.model.BystanderCard
import com.example.emergencyresponse.model.BystanderLanguage
import com.example.emergencyresponse.model.EmergencyEvent
import com.example.emergencyresponse.model.EmergencyUiModel
import com.example.emergencyresponse.model.EmergencyUiState
import com.example.emergencyresponse.model.UserProfile
import com.example.emergencyresponse.util.OpenAiService
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
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

    data class BystanderUi(
        val cards: List<BystanderCard> = emptyList(),
        val language: BystanderLanguage = BystanderLanguage.ENGLISH
    )

    private val _bystanderUi = MutableStateFlow(BystanderUi())
    val bystanderUi: StateFlow<BystanderUi> = _bystanderUi.asStateFlow()

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

    /**
     * Preload bystander assistance cards using the user's profile. If the OpenAI
     * service is not configured or fails, we fall back to a small built-in set
     * of generic cards so the feature still works offline.
     */
    fun preloadBystanderCards(
        profile: UserProfile,
        language: BystanderLanguage,
        openAiService: OpenAiService?,
        forceRefresh: Boolean = false
    ) {
        // Don't re-fetch if we already have cards for this language (unless forced).
        val current = _bystanderUi.value
        if (!forceRefresh && current.cards.isNotEmpty() && current.language == language) {
            Log.d(TAG, "Bystander cards already loaded for ${language.code}; skipping.")
            return
        }

        Log.d(TAG, "preloadBystanderCards: lang=${language.code}, forceRefresh=$forceRefresh, " +
            "serviceConfigured=${openAiService?.isConfigured}")

        viewModelScope.launch {
            val cards = if (openAiService != null && openAiService.isConfigured) {
                try {
                    openAiService.generateBystanderCards(profile, language.code)
                } catch (e: Exception) {
                    Log.e(TAG, "OpenAI call failed: ${e.message}", e)
                    emptyList()
                }
            } else {
                Log.w(TAG, "OpenAI service not configured, using fallback cards.")
                emptyList()
            }

            Log.d(TAG, "OpenAI returned ${cards.size} cards")

            if (cards.isNotEmpty()) {
                _bystanderUi.value = BystanderUi(cards = cards, language = language)
            } else {
                Log.w(TAG, "Using fallback bystander cards.")
                // Hardware / network / config fallback.
                _bystanderUi.value = BystanderUi(
                    language = language,
                    cards = listOf(
                        BystanderCard(
                            "Call 995",
                            "This is an emergency. Please call 995 and tell them I collapsed and need urgent help."
                        ),
                        BystanderCard(
                            "I Can't Speak",
                            "I may not be able to speak clearly. Please stay with me and call 995."
                        ),
                        BystanderCard(
                            "Severe Allergy",
                            "I have a severe allergy. If I am having trouble breathing, please call 995 immediately."
                        )
                    )
                )
            }
        }
    }
}
