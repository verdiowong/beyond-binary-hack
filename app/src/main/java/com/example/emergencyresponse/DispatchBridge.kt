package com.example.emergencyresponse

import android.content.Context

class DispatchBridge(private val context: Context) {
    companion object {
        // private const val SCDF_NUMBER = "70995" // Real emergency number - keep commented in mock mode.
        private const val MOCK_DISPATCH_MODE = true
    }

    // Member 5 TODO: Build the SCDF 70995 string formatter.
    // String format: "[Service]. [Address], #[Unit]. [Context]."
    fun formatScdfMessage(
        service: String,
        address: String,
        unitNumber: String,
        contextText: String
    ): String {
        return "$service. $address, #$unitNumber. $contextText."
    }

    // Member 5 TODO: Use SmsManager to send the formatted message to 70995
    // and a secondary caregiver's phone number.
    fun dispatchSms(
        event: EmergencyEvent,
        onComplete: (Boolean, String) -> Unit
    ) {
        val message = formatScdfMessage(
            service = event.serviceType.ifBlank { "Ambulance" },
            address = event.address.ifBlank { "Unknown location" },
            unitNumber = event.unitNumber.ifBlank { "01-01" },
            contextText = event.context.ifBlank { "No context" }
        )

        if (MOCK_DISPATCH_MODE) {
            onComplete(
                true,
                "MOCK dispatch only. No real SMS sent. Payload: $message"
            )
            return
        }

        // Real SMS flow (disabled for now in mock mode):
        // val smsManager = SmsManager.getDefault()
        // smsManager.sendTextMessage(SCDF_NUMBER, null, message, null, null)
        // smsManager.sendTextMessage(
        //     event.caregiverNumber.ifBlank { "91234567" },
        //     null,
        //     message,
        //     null,
        //     null
        // )
        // onComplete(true, "SMS sent to SCDF and caregiver.")
        onComplete(false, "Real SMS dispatch disabled in current build.")
    }
}
