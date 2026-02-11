package com.example.emergencyresponse.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.emergencyresponse.BuildConfig
import com.example.emergencyresponse.R
import com.example.emergencyresponse.model.BystanderCard
import com.example.emergencyresponse.model.UserProfileRepository
import com.example.emergencyresponse.util.OpenAiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BystanderCardsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BystanderCards"
    }

    private lateinit var recycler: RecyclerView
    private lateinit var loadingContainer: LinearLayout
    private lateinit var loadingText: TextView
    private lateinit var langLabel: TextView

    private lateinit var openAiService: OpenAiService
    private lateinit var profileRepo: UserProfileRepository

    private var cards: MutableList<BystanderCard> = mutableListOf()
    private var adapter: CardAdapter? = null
    private var fetchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bystander_cards)

        recycler = findViewById(R.id.bystanderRecycler)
        loadingContainer = findViewById(R.id.loadingContainer)
        loadingText = findViewById(R.id.loadingText)
        langLabel = findViewById(R.id.bystanderLanguageLabel)

        recycler.layoutManager = GridLayoutManager(this, 2)

        openAiService = OpenAiService(BuildConfig.OPENAI_API_KEY)
        profileRepo = UserProfileRepository(this)

        adapter = CardAdapter(cards) { index ->
            val detailIntent = Intent(this, BystanderCardDetailActivity::class.java).apply {
                putExtra(BystanderCardDetailActivity.EXTRA_INDEX, index)
                putExtra(BystanderCardDetailActivity.EXTRA_TITLES, cards.map { it.title }.toTypedArray())
                putExtra(BystanderCardDetailActivity.EXTRA_MESSAGES, cards.map { it.message }.toTypedArray())
            }
            startActivity(detailIntent)
        }
        recycler.adapter = adapter

        // Start fetching cards immediately
        fetchCards()
    }

    private fun fetchCards() {
        // Show loading state
        loadingContainer.visibility = View.VISIBLE
        recycler.visibility = View.GONE

        fetchJob?.cancel()
        fetchJob = CoroutineScope(Dispatchers.Main).launch {
            val profile = profileRepo.load()

            Log.d(TAG, "Fetching bystander cards from OpenAI...")
            loadingText.text = "Generating personalised help cards…"

            val apiCards = withContext(Dispatchers.IO) {
                try {
                    if (openAiService.isConfigured) {
                        openAiService.generateBystanderCards(profile, "en")
                    } else {
                        Log.w(TAG, "OpenAI not configured")
                        emptyList()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch cards: ${e.message}", e)
                    emptyList()
                }
            }

            if (apiCards.isNotEmpty()) {
                Log.i(TAG, "Loaded ${apiCards.size} cards from OpenAI")
                showCards(apiCards)
            } else {
                Log.w(TAG, "Using fallback cards")
                loadingText.text = "Using offline help cards"
                showCards(fallbackCards())
            }
        }
    }

    private fun showCards(newCards: List<BystanderCard>) {
        cards.clear()
        cards.addAll(newCards)
        adapter?.notifyDataSetChanged()

        // Swap loading → grid
        loadingContainer.visibility = View.GONE
        recycler.visibility = View.VISIBLE
    }

    private fun fallbackCards(): List<BystanderCard> = listOf(
        BystanderCard(
            "Call 995",
            "This is an emergency. Please call 995 and tell them I collapsed and need urgent help."
        ),
        BystanderCard(
            "I Can't Speak",
            "I may not be able to speak clearly. Please stay with me and call 995."
        ),
        BystanderCard(
            "Severe Allergy",
            "I have a severe allergy. If I am having trouble breathing, please call 995 immediately."
        ),
        BystanderCard(
            "Medical Info",
            "I have important medical information. Please check for a medical bracelet or card on my person."
        ),
        BystanderCard(
            "Do Not Move Me",
            "Please do not try to move me. Call 995 and wait for paramedics to arrive."
        ),
        BystanderCard(
            "Contact Caregiver",
            "Please check my phone for an emergency contact and call them. I may need help communicating."
        )
    )

    override fun onDestroy() {
        fetchJob?.cancel()
        super.onDestroy()
    }

    // ── Adapter & ViewHolder ───────────────────────────────────────────

    private class CardAdapter(
        private val items: List<BystanderCard>,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<CardViewHolder>() {

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): CardViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_bystander_card, parent, false)
            return CardViewHolder(view)
        }

        override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
            holder.bind(items[position], position, onClick)
        }

        override fun getItemCount(): Int = items.size
    }

    private class CardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.cardTitle)
        private val preview: TextView = itemView.findViewById(R.id.cardPreview)

        fun bind(card: BystanderCard, index: Int, onClick: (Int) -> Unit) {
            title.text = card.title
            preview.text = card.message

            // High-contrast color cycling across 6 colours
            val colors = intArrayOf(
                R.color.emergency_red,
                R.color.police_blue,
                R.color.safe_green,
                R.color.warning_amber,
                R.color.fire_orange,
                R.color.primary
            )
            val ctx = itemView.context
            val bgColor = ctx.getColor(colors[index % colors.size])
            (itemView as? com.google.android.material.card.MaterialCardView)
                ?.setCardBackgroundColor(bgColor)

            title.setTextColor(ctx.getColor(R.color.text_on_colored))
            preview.setTextColor(ctx.getColor(R.color.text_on_colored))

            itemView.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onClick(pos)
                }
            }
        }
    }
}
