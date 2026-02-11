package com.example.emergencyresponse.model

/**
 * User profile data for emergency dispatch and onboarding.
 * Stored in SharedPreferences via [UserProfileRepository].
 */
data class UserProfile(
    val name: String = "",
    val homeAddress: String = "",
    val unitNumber: String = "",
    val postalCode: String = "",
    val caregiverNumber: String = "",
    val secondaryContact: String = "",
    val medicalConditions: String = "",
    val bloodType: String = "",
    val allergies: String = "",
    val medications: String = "",
    val medicalId: String = "",
    val communicationMode: String = COMM_MODE_TOUCH // "touch", "voice", "both"
) {
    companion object {
        const val COMM_MODE_TOUCH = "touch"
        const val COMM_MODE_VOICE = "voice"
        const val COMM_MODE_BOTH = "both"
    }

    /** True when the minimum required fields are filled. */
    val isComplete: Boolean
        get() = name.isNotBlank() && caregiverNumber.isNotBlank()
}
