package com.example.emergencyresponse.model

/**
 * Immutable snapshot of the UI state, observed by the Activity to render the screen.
 */
data class EmergencyUiModel(
    val state: EmergencyUiState = EmergencyUiState.IDLE,
    val selectedService: String? = null,
    val selectedContext: String? = null,
    val resolvedAddress: String? = null
)
