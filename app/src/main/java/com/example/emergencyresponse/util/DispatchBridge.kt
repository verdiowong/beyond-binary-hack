package com.example.emergencyresponse.util

import android.app.AlertDialog
import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import com.example.emergencyresponse.model.AppConfig
import com.example.emergencyresponse.model.EmergencyEvent

/**
 * Handles SMS dispatch to SCDF and caregiver contacts.
 * Respects [AppConfig.MOCK_DISPATCH_MODE] to prevent accidental sends.
 */
class DispatchBridge(private val context: Context) {
    companion object {
        private const val TAG = "DispatchBridge"
    }

    /**
     * Format the SCDF 70995 SMS payload per SCDF guidelines.
     * Pattern: "[Service]. [Address], #[Unit]. [Context]."
     */
    fun formatScdfMessage(event: EmergencyEvent): String {
        val service = event.serviceType.ifBlank { "Ambulance" }
        val address = event.address.ifBlank { "Unknown location" }
        val unit = event.unitNumber.ifBlank { AppConfig.DEFAULT_UNIT_NUMBER }
        val description = event.context.ifBlank { "Emergency assistance needed" }
        return "$service. $address, #$unit. $description."
    }

    /**
     * Format the caregiver notification with SCDF message + Google Maps link.
     */
    fun formatCaregiverMessage(event: EmergencyEvent): String {
        val name = event.userName.ifBlank { "User" }
        val scdfMsg = formatScdfMessage(event)
        val sb = StringBuilder()
        sb.append("EMERGENCY ALERT from $name.\n")
        sb.append(scdfMsg).append("\n")
        if (event.latitude != 0.0 || event.longitude != 0.0) {
            sb.append("Location: https://maps.google.com/?q=${event.latitude},${event.longitude}")
        }
        return sb.toString().trim()
    }

    /**
     * Dispatch emergency SMS to SCDF + caregiver + secondary contact.
     * When [AppConfig.MOCK_DISPATCH_MODE] is true, shows the full payload
     * on-screen via a dialog so it can be visually verified.
     */
    fun dispatchSms(
        event: EmergencyEvent,
        onComplete: (Boolean, String) -> Unit
    ) {
        val scdfMessage = formatScdfMessage(event)
        val caregiverMessage = formatCaregiverMessage(event)
        val caregiver = event.caregiverNumber.ifBlank { AppConfig.DEFAULT_CAREGIVER_NUMBER }

        if (AppConfig.MOCK_DISPATCH_MODE) {
            Log.i(TAG, "MOCK dispatch to SCDF (${AppConfig.SCDF_NUMBER}): $scdfMessage")

            // If TEST_CAREGIVER_SMS is on, send REAL SMS to caregiver/secondary
            // but still mock the SCDF send.
            var caregiverSent = false
            if (AppConfig.TEST_CAREGIVER_SMS) {
                try {
                    @Suppress("DEPRECATION")
                    val smsManager = android.telephony.SmsManager.getDefault()

                    sendLongSms(smsManager, caregiver, caregiverMessage)
                    Log.i(TAG, "TEST: Real SMS sent to caregiver ($caregiver)")
                    caregiverSent = true

                    if (event.secondaryContact.isNotBlank()) {
                        sendLongSms(smsManager, event.secondaryContact, caregiverMessage)
                        Log.i(TAG, "TEST: Real SMS sent to secondary (${event.secondaryContact})")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "TEST: Caregiver SMS failed: ${e.message}", e)
                }
            }

            // Build a readable summary for on-screen display
            val caregiverStatus = if (caregiverSent) "SENT" else "MOCK"
            val summary = buildString {
                append("--- SMS to SCDF (${AppConfig.SCDF_NUMBER}) [MOCK] ---\n")
                append(scdfMessage)
                append("\n\n--- SMS to Caregiver ($caregiver) [$caregiverStatus] ---\n")
                append(caregiverMessage)
                if (event.secondaryContact.isNotBlank()) {
                    append("\n\n--- SMS to Secondary (${event.secondaryContact}) [$caregiverStatus] ---\n")
                    append(caregiverMessage)
                }
            }

            // Show a dialog with the full payload
            try {
                val title = if (caregiverSent) "SMS Sent to Caregiver (SCDF mocked)" else "MOCK SMS Dispatch"
                AlertDialog.Builder(context)
                    .setTitle(title)
                    .setMessage(summary)
                    .setPositiveButton("OK", null)
                    .show()
            } catch (e: Exception) {
                Log.w(TAG, "Could not show mock dialog: ${e.message}")
            }

            val toastMsg = if (caregiverSent) "Real SMS sent to caregiver! SCDF mocked." else "Mock SMS dispatched (see dialog)"
            Toast.makeText(context, toastMsg, Toast.LENGTH_LONG).show()

            onComplete(true, summary)
            return
        }

        // Real SMS dispatch
        try {
            @Suppress("DEPRECATION")
            val smsManager = SmsManager.getDefault()

            // 1) Send to SCDF
            sendLongSms(smsManager, AppConfig.SCDF_NUMBER, scdfMessage)
            Log.i(TAG, "SMS sent to SCDF (${AppConfig.SCDF_NUMBER})")

            // 2) Send to primary caregiver
            sendLongSms(smsManager, caregiver, caregiverMessage)
            Log.i(TAG, "SMS sent to caregiver ($caregiver)")

            // 3) Send to secondary contact (if present)
            if (event.secondaryContact.isNotBlank()) {
                sendLongSms(smsManager, event.secondaryContact, caregiverMessage)
                Log.i(TAG, "SMS sent to secondary contact (${event.secondaryContact})")
            }

            onComplete(true, "Emergency SMS sent to SCDF and contacts.")
        } catch (e: Exception) {
            Log.e(TAG, "SMS dispatch failed: ${e.message}", e)
            onComplete(false, "SMS dispatch failed: ${e.message}")
        }
    }

    /**
     * Send a potentially long SMS by splitting into multipart if needed.
     */
    private fun sendLongSms(smsManager: SmsManager, number: String, message: String) {
        val parts = smsManager.divideMessage(message)
        if (parts.size > 1) {
            smsManager.sendMultipartTextMessage(number, null, parts, null, null)
        } else {
            smsManager.sendTextMessage(number, null, message, null, null)
        }
    }
}
