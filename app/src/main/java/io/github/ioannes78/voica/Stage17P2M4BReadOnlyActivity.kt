package io.github.ioannes78.voica

import androidx.activity.ComponentActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * QA-only read-only entry point. This activity is declared in qa/releaseQa
 * manifests only. It never seeds, arms, binds, exits, or clears journal data.
 */
class Stage17P2M4BReadOnlyActivity : ComponentActivity() {
    private var broker: Stage17P2M4BController? = null
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Stage17P2M4BContract.allowPackage(packageName) ||
            Stage17P2M4BContract.currentProcessName(this) != packageName
        ) {
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val heading = TextView(this).apply {
            text = "Stage 17 P2-M4B1 — READ ONLY"
            textSize = 20f
        }
        root.addView(heading)
        root.addView(TextView(this).apply {
            text = "DISPOSABLE PARENT ONLY / MAIN APP SURVIVES / MOCK / NO JNI / NO GPU" +
                "\nNo process termination. No IPC admission. No journal seed or reset." +
                "\nHistorical P2-M3 poison is never accessed."
            textSize = 14f
        })
        output = TextView(this).apply {
            text = "Reading the independent P2-M4B journal…"
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
            gravity = Gravity.TOP
            textSize = 14f
        }
        root.addView(output, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        val refresh = Button(this).apply {
            text = "Refresh read-only status"
            setOnClickListener { broker?.readOnlyDryRun() }
        }
        root.addView(refresh)
        setContentView(root)
        broker = Stage17P2M4BController(applicationContext) { report ->
            ui.post {
                if (!isFinishing && !isDestroyed) {
                    output.text = report
                }
            }
        }
        broker?.readOnlyDryRun()
    }

    override fun onDestroy() {
        broker?.close()
        broker = null
        super.onDestroy()
    }
}
