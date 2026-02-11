package com.example.emergencyresponse.model

/**
 * Accumulated data for a single emergency dispatch.
 * Built up progressively as the user moves through the state machine.
 */
data class EmergencyEvent(
    val serviceType: String = "",           // SCDF prefix, e.g. "Ambulance", "Fire Engine"
    val context: String = "",               // SMS description, e.g. "A person has fallen and needs help"
    val address: String = "",               // e.g. "Blk 889 Tampines St 81"
    val unitNumber: String = "",            // e.g. "05-123"
    val caregiverNumber: String = AppConfig.DEFAULT_CAREGIVER_NUMBER,
    val secondaryContact: String = "",
    val userName: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)
