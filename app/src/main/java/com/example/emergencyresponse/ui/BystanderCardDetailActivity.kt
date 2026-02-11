package com.example.emergencyresponse.ui

import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.emergencyresponse.BuildConfig
import com.example.emergencyresponse.R
import com.example.emergencyresponse.model.BystanderCard
import com.example.emergencyresponse.model.BystanderLanguage
import com.example.emergencyresponse.model.UserProfileRepository
import com.example.emergencyresponse.util.OpenAiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class BystanderCardDetailActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BystanderCardDetail"
        const val EXTRA_INDEX = "extra_index"
        const val EXTRA_TITLES = "extra_titles"
        const val EXTRA_MESSAGES = "extra_messages"
    }

    private var tts: TextToSpeech? = null
    private lateinit var messageView: TextView
    private lateinit var titleView: TextView

    private lateinit var langButtons: Map<BystanderLanguage, Button>

    private var currentCards: MutableList<BystanderCard> = mutableListOf()
    private var index: Int = 0
    private var currentLanguage: BystanderLanguage = BystanderLanguage.ENGLISH

    private lateinit var openAiService: OpenAiService
    private lateinit var profileRepo: UserProfileRepository
    private var fetchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bystander_card_detail)

        titleView = findViewById(R.id.detailTitle)
        messageView = findViewById(R.id.detailMessage)

        // Initialize services
        openAiService = OpenAiService(BuildConfig.OPENAI_API_KEY)
        profileRepo = UserProfileRepository(this)

        val titles = intent.getStringArrayExtra(EXTRA_TITLES) ?: emptyArray()
        val messages = intent.getStringArrayExtra(EXTRA_MESSAGES) ?: emptyArray()
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, messages.lastIndex.coerceAtLeast(0))

        // Populate initial cards
        currentCards = titles.zip(messages) { t, m -> BystanderCard(t, m) }.toMutableList()
        updateCard()

        langButtons = mapOf(
            BystanderLanguage.ENGLISH to findViewById(R.id.langEn),
            BystanderLanguage.MALAY to findViewById(R.id.langMs),
            BystanderLanguage.CHINESE to findViewById(R.id.langZh),
            BystanderLanguage.TAMIL to findViewById(R.id.langTa)
        )

        langButtons.forEach { (lang, button) ->
            button.setOnClickListener {
                if (lang != currentLanguage) {
                    switchLanguage(lang)
                }
            }
        }
        highlightLanguage(BystanderLanguage.ENGLISH)

        initTts()

        findViewById<Button>(R.id.btnSpeak).setOnClickListener {
            speakCurrentMessage()
            vibrateConfirmation()
        }
    }

    private fun updateCard() {
        if (currentCards.isNotEmpty() && index < currentCards.size) {
            titleView.text = currentCards[index].title
            messageView.text = currentCards[index].message
        }
    }

    private fun switchLanguage(language: BystanderLanguage) {
        currentLanguage = language
        highlightLanguage(language)

        // Set TTS locale for the selected language
        updateTtsLocale(language)

        // Show loading state
        messageView.text = "Loading ${language.displayName} cards..."

        // Cancel any in-progress fetch
        fetchJob?.cancel()

        fetchJob = CoroutineScope(Dispatchers.Main).launch {
            Log.d(TAG, "Fetching bystander cards in ${language.code}...")

            val profile = profileRepo.load()
            val cards = withContext(Dispatchers.IO) {
                try {
                    if (openAiService.isConfigured) {
                        openAiService.generateBystanderCards(profile, language.code)
                    } else {
                        Log.w(TAG, "OpenAI not configured, generating local fallback for ${language.code}")
                        emptyList()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching cards for ${language.code}: ${e.message}", e)
                    emptyList()
                }
            }

            if (cards.isNotEmpty()) {
                Log.i(TAG, "Got ${cards.size} cards for ${language.code}")
                currentCards.clear()
                currentCards.addAll(cards)
                // Reset index if out of range
                index = index.coerceIn(0, currentCards.lastIndex.coerceAtLeast(0))
                updateCard()
                Toast.makeText(
                    this@BystanderCardDetailActivity,
                    "Loaded ${language.displayName} cards",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Log.w(TAG, "No cards returned for ${language.code}, using fallback")
                currentCards.clear()
                currentCards.addAll(getFallbackCards(language))
                index = index.coerceIn(0, currentCards.lastIndex.coerceAtLeast(0))
                updateCard()
                Toast.makeText(
                    this@BystanderCardDetailActivity,
                    "Using fallback ${language.displayName} cards",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun getFallbackCards(language: BystanderLanguage): List<BystanderCard> {
        return when (language) {
            BystanderLanguage.ENGLISH -> listOf(
                BystanderCard("Call 995", "This is an emergency. Please call 995 and tell them I collapsed and need urgent help."),
                BystanderCard("I Can't Speak", "I may not be able to speak clearly. Please stay with me and call 995."),
                BystanderCard("Severe Allergy", "I have a severe allergy. If I am having trouble breathing, please call 995 immediately.")
            )
            BystanderLanguage.MALAY -> listOf(
                BystanderCard("Panggil 995", "Ini kecemasan. Sila panggil 995 dan beritahu mereka saya pengsan dan perlukan bantuan segera."),
                BystanderCard("Saya Tidak Boleh Bercakap", "Saya mungkin tidak dapat bercakap dengan jelas. Sila tunggu bersama saya dan panggil 995."),
                BystanderCard("Alahan Teruk", "Saya ada alahan teruk. Jika saya susah bernafas, sila panggil 995 segera.")
            )
            BystanderLanguage.CHINESE -> listOf(
                BystanderCard("请拨打995", "这是紧急情况。请拨打995，告诉他们我晕倒了，需要紧急帮助。"),
                BystanderCard("我无法说话", "我可能无法清楚说话。请留在我身边并拨打995。"),
                BystanderCard("严重过敏", "我有严重过敏。如果我呼吸困难，请立即拨打995。")
            )
            BystanderLanguage.TAMIL -> listOf(
                BystanderCard("995 அழைக்கவும்", "இது அவசரநிலை. தயவுசெய்து 995 அழைத்து நான் மயங்கி விழுந்ததாகவும் உடனடி உதவி தேவை என்றும் தெரிவிக்கவும்."),
                BystanderCard("என்னால் பேச முடியாது", "என்னால் தெளிவாக பேச முடியாமல் இருக்கலாம். தயவுசெய்து என்னுடன் இருங்கள், 995 அழைக்கவும்."),
                BystanderCard("கடுமையான ஒவ்வாமை", "எனக்கு கடுமையான ஒவ்வாமை உள்ளது. நான் சுவாசிக்க சிரமப்பட்டால், உடனடியாக 995 அழைக்கவும்.")
            )
        }
    }

    private fun updateTtsLocale(language: BystanderLanguage) {
        val locale = when (language) {
            BystanderLanguage.ENGLISH -> Locale.US
            BystanderLanguage.MALAY -> Locale("ms", "MY")
            BystanderLanguage.CHINESE -> Locale.SIMPLIFIED_CHINESE
            BystanderLanguage.TAMIL -> Locale("ta", "IN")
        }
        tts?.language = locale
        Log.d(TAG, "TTS locale set to: $locale")
    }

    private fun highlightLanguage(selected: BystanderLanguage) {
        langButtons.forEach { (lang, button) ->
            val alpha = if (lang == selected) 1.0f else 0.5f
            button.alpha = alpha
        }
    }

    private var ttsReady = false

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                // Clear and slightly slow for noisy environments.
                tts?.setPitch(0.95f)
                tts?.setSpeechRate(0.85f)
                ttsReady = true
                Log.d(TAG, "TTS initialized successfully")
            } else {
                Log.e(TAG, "TTS initialization failed with status: $status")
            }
        }
    }

    private fun speakCurrentMessage() {
        val msg = messageView.text?.toString().orEmpty()
        if (msg.isBlank()) {
            Log.w(TAG, "Nothing to speak - message is blank")
            return
        }
        if (!ttsReady) {
            Log.w(TAG, "TTS not ready yet")
            Toast.makeText(this, "Speech engine not ready yet", Toast.LENGTH_SHORT).show()
            return
        }

        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        // Bump volume to at least half so bystanders can hear.
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (current < max / 2) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max / 2, 0)
        }

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, params, "bystander_speak")
        Log.d(TAG, "Speaking message: ${msg.take(60)}...")
    }

    private fun vibrateConfirmation() {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(VibratorManager::class.java)
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        vibrator ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createWaveform(
                longArrayOf(0, 90, 70, 90),
                intArrayOf(0, 230, 0, 230),
                -1
            )
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 90, 70, 90), -1)
        }
    }

    override fun onDestroy() {
        fetchJob?.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
