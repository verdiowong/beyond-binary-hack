package com.example.emergencyresponse.model

/**
 * Simple value objects for the Bystander Assistance feature.
 */
data class BystanderCard(
    val title: String,
    val message: String
)

enum class BystanderLanguage(val displayName: String, val code: String) {
    ENGLISH("English", "en"),
    MALAY("Malay", "ms"),
    CHINESE("中文", "zh"),
    TAMIL("தமிழ்", "ta")
}

