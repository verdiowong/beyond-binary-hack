package com.example.emergencyresponse.ui

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.emergencyresponse.R
import com.example.emergencyresponse.model.UserProfile
import com.example.emergencyresponse.model.UserProfileRepository
import com.google.android.material.button.MaterialButton

/**
 * Displays a clean, large-text "Medical Card" with the user's medical
 * information. Designed to be shown to first responders on arrival.
 *
 * Includes a share button that opens Android's share sheet so the user
 * can send their medical info digitally (WhatsApp, SMS, email, etc.).
 */
class MedicalCardActivity : AppCompatActivity() {

    private lateinit var profileRepo: UserProfileRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_medical_card)

        profileRepo = UserProfileRepository(this)
        val profile = profileRepo.load()

        // Bind views
        val medCardName: TextView = findViewById(R.id.medCardName)
        val medCardId: TextView = findViewById(R.id.medCardId)
        val medCardConditions: TextView = findViewById(R.id.medCardConditions)
        val medCardAllergies: TextView = findViewById(R.id.medCardAllergies)
        val medCardContact: TextView = findViewById(R.id.medCardContact)
        val medCardAddress: TextView = findViewById(R.id.medCardAddress)

        // Populate
        medCardName.text = profile.name.ifBlank { "Not set" }
        medCardId.text = profile.medicalId.ifBlank { "Not set" }
        medCardConditions.text = profile.medicalConditions.ifBlank { "None recorded" }
        medCardAllergies.text = profile.allergies.ifBlank { "None recorded" }
        medCardContact.text = profile.caregiverNumber.ifBlank { "Not set" }

        val address = buildString {
            if (profile.homeAddress.isNotBlank()) append(profile.homeAddress)
            if (profile.unitNumber.isNotBlank()) append(", #${profile.unitNumber}")
            if (profile.postalCode.isNotBlank()) append(", S${profile.postalCode}")
        }
        medCardAddress.text = address.ifBlank { "Not set" }

        // Close
        findViewById<MaterialButton>(R.id.btnCloseMedCard).setOnClickListener { finish() }

        // Share
        findViewById<MaterialButton>(R.id.btnShareMedCard).setOnClickListener {
            shareMedicalCard(profile)
        }
    }

    private fun shareMedicalCard(profile: UserProfile) {
        val text = buildString {
            appendLine("--- MEDICAL CARD ---")
            appendLine()
            appendLine("Name: ${profile.name.ifBlank { "N/A" }}")
            appendLine("Medical ID: ${profile.medicalId.ifBlank { "N/A" }}")
            appendLine()
            appendLine("Medical Conditions:")
            appendLine(profile.medicalConditions.ifBlank { "  None recorded" })
            appendLine()
            appendLine("Allergies:")
            appendLine(profile.allergies.ifBlank { "  None recorded" })
            appendLine()
            appendLine("Emergency Contact: ${profile.caregiverNumber.ifBlank { "N/A" }}")
            if (profile.secondaryContact.isNotBlank()) {
                appendLine("Secondary Contact: ${profile.secondaryContact}")
            }
            appendLine()
            val addr = buildString {
                if (profile.homeAddress.isNotBlank()) append(profile.homeAddress)
                if (profile.unitNumber.isNotBlank()) append(", #${profile.unitNumber}")
                if (profile.postalCode.isNotBlank()) append(", S${profile.postalCode}")
            }
            appendLine("Home Address: ${addr.ifBlank { "N/A" }}")
            appendLine()
            appendLine("--- Sent from Emergency Response App ---")
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Medical Card - ${profile.name}")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Medical Card"))
    }
}
