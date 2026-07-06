package com.verdure.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.verdure.R
import com.verdure.core.WhisperSTTEngine
import com.verdure.services.VoiceInputAccessibilityService
import kotlinx.coroutines.launch

/**
 * One-screen setup for Whisper voice typing. Walks the user through the three
 * things dictation needs:
 *   1. Microphone permission
 *   2. Accessibility access (so the floating mic can type into any app)
 *   3. Downloading the on-device Whisper model
 *
 * Deliberately built for a non-technical user: each step is a single button
 * that turns green when done.
 */
class VoiceSettingsActivity : AppCompatActivity() {

    private lateinit var micButton: Button
    private lateinit var accessibilityButton: Button
    private lateinit var overlayButton: Button
    private lateinit var downloadButton: Button
    private lateinit var statusText: TextView

    private var isDownloading = false

    companion object {
        private const val MIC_PERMISSION_REQUEST = 200
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_settings)

        micButton = findViewById(R.id.micPermissionButton)
        accessibilityButton = findViewById(R.id.accessibilityButton)
        overlayButton = findViewById(R.id.overlayButton)
        downloadButton = findViewById(R.id.downloadModelButton)
        statusText = findViewById(R.id.voiceStatusText)

        micButton.setOnClickListener {
            if (!hasMicPermission()) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    MIC_PERMISSION_REQUEST
                )
            }
        }

        accessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        overlayButton.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        downloadButton.setOnClickListener {
            downloadModel()
        }

        findViewById<Button>(R.id.testDictationButton).setOnClickListener {
            startActivity(Intent(this, DictationTestActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun downloadModel() {
        if (isDownloading) return
        isDownloading = true
        downloadButton.isEnabled = false
        lifecycleScope.launch {
            val engine = WhisperSTTEngine.getInstance(applicationContext)
            val ok = engine.initialize { progress ->
                runOnUiThread { statusText.text = progress }
            }
            runOnUiThread {
                isDownloading = false
                if (!ok) {
                    statusText.text = "❌ ${engine.getLastError() ?: "Model download failed"}"
                }
                refreshState()
            }
        }
    }

    private fun refreshState() {
        val hasMic = hasMicPermission()
        val hasA11y = isAccessibilityEnabled()
        val hasOverlay = Settings.canDrawOverlays(this)
        val modelReady = WhisperSTTEngine.getInstance(applicationContext).isReady()

        markButton(micButton, hasMic, "1. Allow microphone", "1. Microphone allowed ✓")
        markButton(
            accessibilityButton,
            hasA11y,
            "2. Turn on accessibility",
            "2. Accessibility on ✓"
        )
        markButton(
            overlayButton,
            hasOverlay,
            "3. Allow display over apps",
            "3. Display over apps ✓"
        )
        markButton(
            downloadButton,
            modelReady,
            "4. Download Whisper model",
            "4. Whisper ready ✓"
        )
        downloadButton.isEnabled = !modelReady && !isDownloading

        statusText.text = when {
            hasMic && hasA11y && hasOverlay && modelReady ->
                "✅ All set. Tap the floating mic anywhere to dictate."
            else ->
                "Complete the steps above to enable voice typing across all your apps."
        }
    }

    private fun markButton(button: Button, done: Boolean, todo: String, doneLabel: String) {
        button.text = if (done) doneLabel else todo
        val color = if (done) R.color.accent_primary else R.color.background_secondary
        button.setBackgroundColor(ContextCompat.getColor(this, color))
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val component = ComponentName(this, VoiceInputAccessibilityService::class.java)
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it) == component
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_PERMISSION_REQUEST) refreshState()
    }
}
