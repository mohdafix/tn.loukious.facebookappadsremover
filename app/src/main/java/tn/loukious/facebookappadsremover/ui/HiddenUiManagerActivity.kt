package tn.loukious.facebookappadsremover.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import tn.loukious.facebookappadsremover.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HiddenUiManagerActivity : AppCompatActivity() {

    private lateinit var rulesContainer: LinearLayout
    private lateinit var emptyState: TextView
    private lateinit var btnRestoreAll: MaterialButton
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hidden_ui_manager)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        rulesContainer = findViewById(R.id.rulesContainer)
        emptyState = findViewById(R.id.emptyState)
        btnRestoreAll = findViewById(R.id.btnRestoreAll)

        btnRestoreAll.setOnClickListener {
            HiddenRuleReceiver.saveRules(this, emptyList())
            Toast.makeText(this, "All elements restored! Restart Facebook to apply.", Toast.LENGTH_SHORT).show()
            loadAndDisplayRules()
        }

        loadAndDisplayRules()
    }

    private fun loadAndDisplayRules() {
        val rules = HiddenRuleReceiver.getSavedRules(this)
        rulesContainer.removeAllViews()

        if (rules.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            btnRestoreAll.visibility = View.GONE
            return
        }

        emptyState.visibility = View.GONE
        btnRestoreAll.visibility = View.VISIBLE

        for (rule in rules) {
            val card = createRuleCard(rule, rules)
            rulesContainer.addView(card)
        }
    }

    private fun createRuleCard(rule: JSONObject, allRules: List<JSONObject>): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
            background = GradientDrawable().apply {
                setColor(0x15FFFFFF)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0x25FFFFFF)
            }
        }

        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(12)
            }
        }

        val label = rule.optString("label", "Unknown Element")
        val resId = rule.optString("resourceId")
        val timestamp = rule.optLong("timestamp", 0L)
        val timeStr = if (timestamp > 0) dateFormat.format(Date(timestamp)) else "Recently hidden"

        val titleView = TextView(this).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        textContainer.addView(titleView)

        val subtitleText = if (resId.isNotEmpty()) "ID: #$resId • $timeStr" else timeStr
        val subtitleView = TextView(this).apply {
            text = subtitleText
            textSize = 12f
            setTextColor(0xAAFFFFFF.toInt())
            setPadding(0, dp(4), 0, 0)
        }
        textContainer.addView(subtitleView)

        card.addView(textContainer)

        val restoreBtn = TextView(this).apply {
            text = "Restore"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF00E676.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                setColor(0x1A00E676)
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0xFF00E676.toInt())
            }
            setOnClickListener {
                val updated = allRules.filter { it != rule }
                HiddenRuleReceiver.saveRules(this@HiddenUiManagerActivity, updated)
                Toast.makeText(this@HiddenUiManagerActivity, "Element restored! Restart Facebook to apply.", Toast.LENGTH_SHORT).show()
                loadAndDisplayRules()
            }
        }
        card.addView(restoreBtn)

        return card
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
    }
}
