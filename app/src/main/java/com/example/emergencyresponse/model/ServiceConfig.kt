package com.example.emergencyresponse.model

/**
 * A single context/sub-option within a service category.
 * [smsTemplate] uses `{name}` as a placeholder for the user's name.
 * Call [smsDescription] to get the final text.
 */
data class ContextOption(
    val id: String,
    val label: String,
    val emoji: String,
    val smsTemplate: String
) {
    /** Resolve the template with the user's name. */
    fun smsDescription(userName: String): String {
        val name = userName.ifBlank { "A person" }
        return smsTemplate.replace("{name}", name)
    }
}

/**
 * An emergency service type with its SCDF prefix and associated context options.
 */
data class ServiceOption(
    val id: String,
    val label: String,
    val emoji: String,
    val scdfPrefix: String,
    val contexts: List<ContextOption>
)

/**
 * Static registry of all available emergency services and their context sub-options.
 * Used by SERVICE_SELECTION and CONTEXT_SELECTION states.
 */
object ServiceConfig {

    val FIRE = ServiceOption(
        id = "fire",
        label = "Fire",
        emoji = "\uD83D\uDE92",  // fire engine
        scdfPrefix = "Fire Engine",
        contexts = listOf(
            ContextOption("fire_my_unit", "My Unit", "\uD83C\uDFE0", "{name}'s unit is on fire"),
            ContextOption("fire_neighbour", "Neighbour", "\uD83C\uDFD8\uFE0F", "{name}'s neighbour's unit is on fire"),
            ContextOption("fire_chute", "Rubbish Chute", "\uD83D\uDDD1\uFE0F", "The rubbish chute is on fire")
        )
    )

    val AMBULANCE = ServiceOption(
        id = "ambulance",
        label = "Ambulance",
        emoji = "\uD83D\uDE91",  // ambulance
        scdfPrefix = "Ambulance",
        contexts = listOf(
            ContextOption("amb_fall", "Fall", "\uD83E\uDDD1\u200D\uD83E\uDDBD", "{name} has fallen and needs help"),
            ContextOption("amb_chest", "Chest Pain", "\u2764\uFE0F", "{name} is experiencing chest pain"),
            ContextOption("amb_breathing", "Breathing", "\uD83D\uDCA8", "{name} is having difficulty breathing"),
            ContextOption("amb_bleeding", "Bleeding", "\uD83E\uDE78", "{name} is bleeding and needs help")
        )
    )

    val POLICE = ServiceOption(
        id = "police",
        label = "Police",
        emoji = "\uD83D\uDE94",  // police car
        scdfPrefix = "Police",
        contexts = listOf(
            ContextOption("pol_robbery", "Robbery", "\uD83D\uDCB0", "{name} is experiencing a robbery"),
            ContextOption("pol_assault", "Assault", "\u26A0\uFE0F", "{name} is being assaulted"),
            ContextOption("pol_suspicious", "Suspicious", "\uD83D\uDC41\uFE0F", "Suspicious activity observed near {name}"),
            ContextOption("pol_other", "Other", "\u2753", "{name} needs police assistance")
        )
    )

    /** All services in display order. */
    val ALL_SERVICES: List<ServiceOption> = listOf(FIRE, AMBULANCE, POLICE)

    /** Look up a [ServiceOption] by its id or label (case-insensitive). */
    fun findService(idOrLabel: String): ServiceOption? {
        val lower = idOrLabel.lowercase()
        return ALL_SERVICES.find { it.id == lower || it.label.lowercase() == lower || it.scdfPrefix.lowercase() == lower }
    }
}
