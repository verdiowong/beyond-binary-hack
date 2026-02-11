package com.example.emergencyresponse.model

/**
 * Centralized state machine for the emergency response flow.
 *
 * Transitions:
 *   IDLE -> DROP_COUNTDOWN        (fall/tremor trigger or manual)
 *   DROP_COUNTDOWN -> SERVICE_SELECTION  (countdown expires or "Help" pressed)
 *   DROP_COUNTDOWN -> IDLE               (user cancels)
 *   SERVICE_SELECTION -> CONTEXT_SELECTION
 *   CONTEXT_SELECTION -> LOCATION_CONFIRM
 *   LOCATION_CONFIRM -> DISPATCH_ACTIVE
 *   Any -> IDLE                          (cancel)
 */
enum class EmergencyUiState {
    IDLE,
    DROP_COUNTDOWN,
    SERVICE_SELECTION,
    CONTEXT_SELECTION,
    LOCATION_CONFIRM,
    DISPATCH_ACTIVE
}
