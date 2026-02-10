package com.example.emergencyresponse

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

class DispatchBridge(private val context: Context) {
    companion object {
        private const val SCDF_NUMBER = "70995"
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
        unitNumber: String,
        caregiverPhone: String,
        onComplete: (Boolean, String) -> Unit
    ) {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onComplete(false, "Missing SEND_SMS permission.")
            return
        }

        val message = formatScdfMessage(
            service = event.service ?: "Ambulance",
            address = event.location ?: "Unknown location",
            unitNumber = unitNumber,
            contextText = event.context ?: "No context"
        )

        try {
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(SCDF_NUMBER, null, message, null, null)
            smsManager.sendTextMessage(caregiverPhone, null, message, null, null)
            onComplete(true, "SMS sent to SCDF and caregiver.")
        } catch (ex: Exception) {
            onComplete(false, "SMS dispatch failed: ${ex.message}")
        }
    }
}
