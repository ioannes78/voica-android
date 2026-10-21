package io.github.ioannes78.voica

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import io.github.ioannes78.voica.sherpa.FunAsrRuntimeDiagnostics

/** Single QA/releaseQa launcher to avoid one desktop icon per Stage 16B benchmark activity. */
class Stage16BQaHubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as VoicaApplication
        check(VoicaModelChannel.isDebuggable(app)) {
            "Stage 16B QA Hub must never run in a production build"
        }
        setContentView(buildContentView())
    }

    private fun buildContentView(): ScrollView {
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        root.addView(TextView(this).apply {
            text = "Voica — Stage 16B QA Hub"
            textSize = 22f
        })
        root.addView(TextView(this).apply {
            text =
                "QA/releaseQa only. Post-Gate P1/P2 is active: validate FunASR Hotword OFF/ON completeness before any CTC timeline work. " +
                    "Earlier Gate B tools remain available for evidence recovery only."
        })

        root.addView(TextView(this).apply {
            setPadding(0, padding, 0, padding / 2)
            text = "Current — Post-Gate P1/P2"
            textSize = 18f
        })
        val diagnosticsText = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            text = FunAsrRuntimeDiagnostics.formatForQa()
        }
        root.addView(Button(this).apply {
            text = "Refresh FunASR Context Diagnostics"
            isAllCaps = false
            setOnClickListener {
                diagnosticsText.text = FunAsrRuntimeDiagnostics.formatForQa()
            }
        })
        root.addView(Button(this).apply {
            text = "Clear FunASR Diagnostics"
            isAllCaps = false
            setOnClickListener {
                FunAsrRuntimeDiagnostics.clear()
                diagnosticsText.text = FunAsrRuntimeDiagnostics.formatForQa()
            }
        })
        root.addView(diagnosticsText)

        root.addView(TextView(this).apply {
            setPadding(0, padding, 0, padding / 2)
            text = "Previous Stage 16B QA tools"
            textSize = 18f
        })
        addButton(root, "Gate B6 — Finalist Endurance", Stage16BGateB6LauncherActivity::class.java)
        addButton(root, "Gate B0/B1/B2 — ASR Matrix", Stage16BGateBBenchmarkActivity::class.java)
        addButton(root, "Gate B3 — Capability Profiles", Stage16BGateB3Activity::class.java)
        addButton(root, "Gate B3 — Hotword A/B", Stage16BGateB3HotwordActivity::class.java)
        addButton(root, "Gate B4 — Timeline", Stage16BGateB4TimelineActivity::class.java)
        addButton(root, "Gate B5 — Performance", Stage16BGateB5FreshLauncherActivity::class.java)

        return ScrollView(this).apply { addView(root) }
    }

    private fun addButton(
        parent: LinearLayout,
        label: String,
        activityClass: Class<out ComponentActivity>,
    ) {
        parent.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(this@Stage16BQaHubActivity, activityClass))
            }
        })
    }
}
