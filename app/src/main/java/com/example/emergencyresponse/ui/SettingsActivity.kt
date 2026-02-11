package com.example.emergencyresponse.ui

import android.content.Intent
import android.os.Bundle
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.emergencyresponse.R
import com.example.emergencyresponse.model.UserProfile
import com.example.emergencyresponse.model.UserProfileRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/**
 * Scrollable settings form for editing [UserProfile] after onboarding.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var repo: UserProfileRepository

    private lateinit var settingsName: TextInputEditText
    private lateinit var settingsAddress: TextInputEditText
    private lateinit var settingsUnit: TextInputEditText
    private lateinit var settingsPostal: TextInputEditText
    private lateinit var settingsCaregiver: TextInputEditText
    private lateinit var settingsSecondary: TextInputEditText
    private lateinit var settingsMedical: TextInputEditText
    private lateinit var settingsAllergies: TextInputEditText
    private lateinit var settingsMedicalId: TextInputEditText
    private lateinit var settingsCommMode: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        repo = UserProfileRepository(this)

        settingsName = findViewById(R.id.settingsName)
        settingsAddress = findViewById(R.id.settingsAddress)
        settingsUnit = findViewById(R.id.settingsUnit)
        settingsPostal = findViewById(R.id.settingsPostal)
        settingsCaregiver = findViewById(R.id.settingsCaregiver)
        settingsSecondary = findViewById(R.id.settingsSecondary)
        settingsMedical = findViewById(R.id.settingsMedical)
        settingsAllergies = findViewById(R.id.settingsAllergies)
        settingsMedicalId = findViewById(R.id.settingsMedicalId)
        settingsCommMode = findViewById(R.id.settingsCommMode)

        loadProfile()

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { saveAndClose() }
        findViewById<MaterialButton>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnMedicalCard).setOnClickListener {
            startActivity(Intent(this, MedicalCardActivity::class.java))
        }
    }

    private fun loadProfile() {
        val p = repo.load()
        settingsName.setText(p.name)
        settingsAddress.setText(p.homeAddress)
        settingsUnit.setText(p.unitNumber)
        settingsPostal.setText(p.postalCode)
        settingsCaregiver.setText(p.caregiverNumber)
        settingsSecondary.setText(p.secondaryContact)
        settingsMedical.setText(p.medicalConditions)
        settingsAllergies.setText(p.allergies)
        settingsMedicalId.setText(p.medicalId)
        when (p.communicationMode) {
            UserProfile.COMM_MODE_VOICE -> settingsCommMode.check(R.id.settingsRadioVoice)
            UserProfile.COMM_MODE_BOTH -> settingsCommMode.check(R.id.settingsRadioBoth)
            else -> settingsCommMode.check(R.id.settingsRadioTouch)
        }
    }

    private fun saveAndClose() {
        val commMode = when (settingsCommMode.checkedRadioButtonId) {
            R.id.settingsRadioVoice -> UserProfile.COMM_MODE_VOICE
            R.id.settingsRadioBoth -> UserProfile.COMM_MODE_BOTH
            else -> UserProfile.COMM_MODE_TOUCH
        }
        val profile = UserProfile(
            name = settingsName.text?.toString()?.trim() ?: "",
            homeAddress = settingsAddress.text?.toString()?.trim() ?: "",
            unitNumber = settingsUnit.text?.toString()?.trim() ?: "",
            postalCode = settingsPostal.text?.toString()?.trim() ?: "",
            caregiverNumber = settingsCaregiver.text?.toString()?.trim() ?: "",
            secondaryContact = settingsSecondary.text?.toString()?.trim() ?: "",
            medicalConditions = settingsMedical.text?.toString()?.trim() ?: "",
            allergies = settingsAllergies.text?.toString()?.trim() ?: "",
            medicalId = settingsMedicalId.text?.toString()?.trim() ?: "",
            communicationMode = commMode
        )
        repo.save(profile)
        Toast.makeText(this, "Profile saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
