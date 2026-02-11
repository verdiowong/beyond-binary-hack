package com.example.emergencyresponse.util

import android.util.Log
import com.example.emergencyresponse.model.BystanderCard
import com.example.emergencyresponse.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Very small, dependency-light client for the OpenAI Chat Completions API.
 *
 * NOTE: This is intentionally minimal for hackathon purposes. It expects the
 * model to return a JSON object with a top-level "cards" array; if parsing
 * fails, callers should fall back to hard-coded cards.
 */
class OpenAiService(
    private val apiKey: String
 ) {

    companion object {
        private const val TAG = "OpenAiService"
        private const val ENDPOINT = "https://api.openai.com/v1/chat/completions"
    }

    val isConfigured: Boolean
        get() = apiKey.isNotBlank()

    suspend fun generateBystanderCards(
        profile: UserProfile,
        languageCode: String
    ): List<BystanderCard> = withContext(Dispatchers.IO) {
        Log.d(TAG, "generateBystanderCards called. isConfigured=$isConfigured, keyLength=${apiKey.length}, lang=$languageCode")

        if (!isConfigured) {
            Log.w(TAG, "OPENAI_API_KEY is empty; skipping network call.")
            return@withContext emptyList()
        }

        // Log first/last 4 chars of key for verification (safe for debugging)
        val keyPreview = if (apiKey.length > 8) "${apiKey.take(4)}...${apiKey.takeLast(4)}" else "***"
        Log.d(TAG, "Using API key: $keyPreview")

        val systemPrompt = """
            You are writing emergency BYSTANDER help cards for a person in Singapore.
            You MUST output EXACTLY 6 cards in the language with ISO code '$languageCode'.
            Each card serves a DIFFERENT purpose. You MUST include one card for each of these 6 categories:
            1. "Call for Help" — ask bystander to call 995 (Singapore emergency number).
            2. "Medical Info" — share the person's conditions, allergies, blood type, and medications.
            3. "I Can't Speak" — explain that the person cannot communicate verbally.
            4. "Do Not Move Me" — instruct bystander not to move the person; wait for paramedics.
            5. "Contact My Caregiver" — ask bystander to find and call the emergency contact on the person's phone.
            6. "Stay With Me" — ask the bystander to remain present, keep the person calm, and monitor breathing.

            Output MUST be valid JSON with EXACTLY 6 objects in this shape:
            {"cards":[{"title":"short title","message":"1-2 sentences"},{"title":"...","message":"..."},...]}

            Rules:
            - Title: maximum 5 words.
            - Message: 1-2 short sentences, extremely direct, addressed to a stranger nearby.
            - Personalise cards using the person's name, condition(s), allergies, blood type, and medications when provided.
            - Always end each card's message with a clear action.
            - Do NOT return fewer than 6 cards.
        """.trimIndent()

        val userSummary = buildString {
            appendLine("Name: ${profile.name}")
            appendLine("Conditions: ${profile.medicalConditions.ifBlank { "None" }}")
            appendLine("Blood type: ${profile.bloodType.ifBlank { "Unknown" }}")
            appendLine("Allergies: ${profile.allergies.ifBlank { "None" }}")
            appendLine("Medications: ${profile.medications.ifBlank { "None" }}")
            appendLine("Medical ID: ${profile.medicalId.ifBlank { "None" }}")
        }

        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userSummary)
            })
        }

        val payload = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("temperature", 0.2)
            put("messages", messagesArray)
        }

        Log.d(TAG, "Request payload: ${payload.toString().take(500)}...")

        val url = URL(ENDPOINT)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 15000
            readTimeout = 30000
            doOutput = true
            outputStream.use { os ->
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
        }

        try {
            val code = conn.responseCode
            Log.d(TAG, "OpenAI response code: $code")
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader().use(BufferedReader::readText)
            if (code !in 200..299) {
                Log.e(TAG, "OpenAI error $code: $body")
                return@withContext emptyList()
            }

            Log.d(TAG, "OpenAI raw response (first 500): ${body.take(500)}")

            // Extract first choice.message.content and parse as JSON
            val root = JSONObject(body)
            val choices = root.getJSONArray("choices")
            if (choices.length() == 0) {
                Log.w(TAG, "OpenAI returned 0 choices")
                return@withContext emptyList()
            }
            var content = choices.getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            Log.d(TAG, "OpenAI content: ${content.take(500)}")

            // Strip markdown code fences if the model wrapped its JSON in ```json ... ```
            content = content.trim()
            if (content.startsWith("```")) {
                content = content.removePrefix("```json").removePrefix("```")
                    .removeSuffix("```").trim()
            }

            val cardsRoot = JSONObject(content)
            val cardsJson = cardsRoot.getJSONArray("cards")
            val cards = mutableListOf<BystanderCard>()
            for (i in 0 until cardsJson.length()) {
                val obj = cardsJson.getJSONObject(i)
                val title = obj.optString("title").trim()
                val msg = obj.optString("message").trim()
                if (title.isNotEmpty() && msg.isNotEmpty()) {
                    cards.add(BystanderCard(title, msg))
                }
            }
            Log.i(TAG, "Successfully parsed ${cards.size} bystander cards from OpenAI")
            cards
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to call OpenAI API: ${t.message}", t)
            emptyList()
        } finally {
            conn.disconnect()
        }
    }
}

