package com.example.emergencyresponse.model

/**
 * Central application configuration.
 *
 * All feature flags and default values live here so they are discoverable
 * from a single location and easy to toggle for testing / production.
 */
object AppConfig {

    // ── Dispatch ──────────────────────────────────────────────────────────

    /**
     * When `true`, [com.example.emergencyresponse.util.DispatchBridge] will log the dispatch payload but
     * will **NOT** send real SMS to SCDF or the caregiver.
     *
     * Set to `false` only when ready for production deployment.
     */
    const val MOCK_DISPATCH_MODE: Boolean = true

    /**
     * When `true` (and [MOCK_DISPATCH_MODE] is also `true`), sends a REAL SMS
     * to the caregiver and secondary contact but still MOCKS the SCDF send.
     *
     * Use this for testing caregiver SMS delivery without contacting SCDF.
     * Set to `false` when done testing.
     */
    const val TEST_CAREGIVER_SMS: Boolean = true

    // ── User-profile defaults ─────────────────────────────────────────────

    /** Fallback caregiver phone number when the user profile is empty. */
    const val DEFAULT_CAREGIVER_NUMBER: String = "91234567"

    /** Fallback unit number when the user profile is empty. */
    const val DEFAULT_UNIT_NUMBER: String = "01-01"

    /** SCDF emergency SMS number. */
    const val SCDF_NUMBER: String = "70995"

    // ── Countdown ─────────────────────────────────────────────────────────

    /** Duration of the DROP_COUNTDOWN timer in milliseconds. */
    const val COUNTDOWN_DURATION_MS: Long = 10_000L

    /** Tick interval for the countdown timer. */
    const val COUNTDOWN_TICK_MS: Long = 1_000L
}
