package com.example.emergencyresponse.ui

import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.emergencyresponse.R
import com.example.emergencyresponse.model.UserProfile
import com.example.emergencyresponse.model.UserProfileRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/**
 * Multi-step onboarding wizard shown on first launch.
 * Collects user profile data and saves to [UserProfileRepository].
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var repo: UserProfileRepository
    private lateinit var flipper: ViewFlipper
    private lateinit var stepIndicator: TextView
    private lateinit var btnBack: MaterialButton
    private lateinit var btnNext: MaterialButton

    // Step 1
    private lateinit var inputName: TextInputEditText
    private lateinit var commModeGroup: RadioGroup

    // Step 2
    private lateinit var inputAddress: TextInputEditText
    private lateinit var inputUnit: TextInputEditText
    private lateinit var inputPostal: TextInputEditText

    // Step 3
    private lateinit var inputCaregiver: TextInputEditText
    private lateinit var inputSecondary: TextInputEditText

    // Step 4
    private lateinit var inputMedical: TextInputEditText
    private lateinit var inputAllergies: TextInputEditText
    private lateinit var inputMedicalId: TextInputEditText

    private val totalSteps = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        repo = UserProfileRepository(this)

        flipper = findViewById(R.id.stepFlipper)
        stepIndicator = findViewById(R.id.stepIndicator)
        btnBack = findViewById(R.id.btnBack)
        btnNext = findViewById(R.id.btnNext)

        inputName = findViewById(R.id.inputName)
        commModeGroup = findViewById(R.id.commModeGroup)

        inputAddress = findViewById(R.id.inputAddress)
        inputUnit = findViewById(R.id.inputUnit)
        inputPostal = findViewById(R.id.inputPostal)

        inputCaregiver = findViewById(R.id.inputCaregiver)
        inputSecondary = findViewById(R.id.inputSecondary)

        inputMedical = findViewById(R.id.inputMedical)
        inputAllergies = findViewById(R.id.inputAllergies)
        inputMedicalId = findViewById(R.id.inputMedicalId)

        // Pre-fill from any existing profile data
        prefill()

        updateStepUi()

        btnNext.setOnClickListener { onNext() }
        btnBack.setOnClickListener { onBack() }

        // Handle system back button via the modern OnBackPressedDispatcher
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentStep() > 0) {
                    onBack()
                } else {
                    // On the first step, allow default back behavior (finish activity)
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun prefill() {
        val profile = repo.load()
        inputName.setText(profile.name)
        inputAddress.setText(profile.homeAddress)
        inputUnit.setText(profile.unitNumber)
        inputPostal.setText(profile.postalCode)
        inputCaregiver.setText(profile.caregiverNumber)
        inputSecondary.setText(profile.secondaryContact)
        inputMedical.setText(profile.medicalConditions)
        inputAllergies.setText(profile.allergies)
        inputMedicalId.setText(profile.medicalId)
        when (profile.communicationMode) {
            UserProfile.COMM_MODE_VOICE -> commModeGroup.check(R.id.radioVoice)
            UserProfile.COMM_MODE_BOTH -> commModeGroup.check(R.id.radioBoth)
            else -> commModeGroup.check(R.id.radioTouch)
        }
    }

    private fun currentStep(): Int = flipper.displayedChild

    private fun onNext() {
        val step = currentStep()
        if (step < totalSteps - 1) {
            // On step 4 (index 3) -> save before showing confirmation
            if (step == totalSteps - 2) {
                saveProfile()
            }
            flipper.displayedChild = step + 1
            updateStepUi()
        } else {
            // Final step -> finish
            repo.isOnboardingComplete = true
            finish()
        }
    }

    private fun onBack() {
        val step = currentStep()
        if (step > 0) {
            flipper.displayedChild = step - 1
            updateStepUi()
        }
    }

    private fun updateStepUi() {
        val step = currentStep()
        stepIndicator.text = "Step ${step + 1} of $totalSteps"
        btnBack.visibility = if (step == 0) View.INVISIBLE else View.VISIBLE
        btnNext.text = when {
            step == totalSteps - 1 -> "Get Started"
            step == totalSteps - 2 -> "Save & Continue"
            else -> "Next"
        }
    }

    private fun saveProfile() {
        val commMode = when (commModeGroup.checkedRadioButtonId) {
            R.id.radioVoice -> UserProfile.COMM_MODE_VOICE
            R.id.radioBoth -> UserProfile.COMM_MODE_BOTH
            else -> UserProfile.COMM_MODE_TOUCH
        }

        val profile = UserProfile(
            name = inputName.text?.toString()?.trim() ?: "",
            homeAddress = inputAddress.text?.toString()?.trim() ?: "",
            unitNumber = inputUnit.text?.toString()?.trim() ?: "",
            postalCode = inputPostal.text?.toString()?.trim() ?: "",
            caregiverNumber = inputCaregiver.text?.toString()?.trim() ?: "",
            secondaryContact = inputSecondary.text?.toString()?.trim() ?: "",
            medicalConditions = inputMedical.text?.toString()?.trim() ?: "",
            allergies = inputAllergies.text?.toString()?.trim() ?: "",
            medicalId = inputMedicalId.text?.toString()?.trim() ?: "",
            communicationMode = commMode
        )
        repo.save(profile)
    }
}
