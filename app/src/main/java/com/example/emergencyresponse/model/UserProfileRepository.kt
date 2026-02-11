package com.example.emergencyresponse.model

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences-backed storage for [UserProfile].
 * Single source of truth for user data across the app.
 */
class UserProfileRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "emergency_user_profile"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_NAME = "name"
        private const val KEY_HOME_ADDRESS = "home_address"
        private const val KEY_UNIT_NUMBER = "unit_number"
        private const val KEY_POSTAL_CODE = "postal_code"
        private const val KEY_CAREGIVER_NUMBER = "caregiver_number"
        private const val KEY_SECONDARY_CONTACT = "secondary_contact"
        private const val KEY_MEDICAL_CONDITIONS = "medical_conditions"
        private const val KEY_ALLERGIES = "allergies"
        private const val KEY_MEDICAL_ID = "medical_id"
        private const val KEY_COMMUNICATION_MODE = "communication_mode"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isOnboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, value).apply()

    fun save(profile: UserProfile) {
        prefs.edit()
            .putString(KEY_NAME, profile.name)
            .putString(KEY_HOME_ADDRESS, profile.homeAddress)
            .putString(KEY_UNIT_NUMBER, profile.unitNumber)
            .putString(KEY_POSTAL_CODE, profile.postalCode)
            .putString(KEY_CAREGIVER_NUMBER, profile.caregiverNumber)
            .putString(KEY_SECONDARY_CONTACT, profile.secondaryContact)
            .putString(KEY_MEDICAL_CONDITIONS, profile.medicalConditions)
            .putString(KEY_ALLERGIES, profile.allergies)
            .putString(KEY_MEDICAL_ID, profile.medicalId)
            .putString(KEY_COMMUNICATION_MODE, profile.communicationMode)
            .apply()
    }

    fun load(): UserProfile {
        return UserProfile(
            name = prefs.getString(KEY_NAME, "") ?: "",
            homeAddress = prefs.getString(KEY_HOME_ADDRESS, "") ?: "",
            unitNumber = prefs.getString(KEY_UNIT_NUMBER, "") ?: "",
            postalCode = prefs.getString(KEY_POSTAL_CODE, "") ?: "",
            caregiverNumber = prefs.getString(KEY_CAREGIVER_NUMBER, "") ?: "",
            secondaryContact = prefs.getString(KEY_SECONDARY_CONTACT, "") ?: "",
            medicalConditions = prefs.getString(KEY_MEDICAL_CONDITIONS, "") ?: "",
            allergies = prefs.getString(KEY_ALLERGIES, "") ?: "",
            medicalId = prefs.getString(KEY_MEDICAL_ID, "") ?: "",
            communicationMode = prefs.getString(KEY_COMMUNICATION_MODE, UserProfile.COMM_MODE_TOUCH)
                ?: UserProfile.COMM_MODE_TOUCH
        )
    }
}
