package com.verdure.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.verdure.R
import com.cactus.CactusContextInitializer
import com.verdure.core.CactusLLMEngine
import com.verdure.core.VerdureAI
import com.verdure.data.InstalledAppsManager
import com.verdure.data.NotificationRepository
import com.verdure.data.UserContextManager
import com.verdure.services.VerdureNotificationListener
import com.verdure.tools.AppPrioritizationTool
import com.verdure.tools.NotificationTool
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var requestPermissionButton: Button
    private lateinit var settingsButton: android.widget.ImageView

    // Chat components
    private lateinit var chatInput: EditText
    private lateinit var sendButton: Button
    private lateinit var chatHistoryContainer: LinearLayout
    private lateinit var chatScrollView: ScrollView

    // AI components
    private lateinit var llmEngine: CactusLLMEngine
    private lateinit var verdureAI: VerdureAI

    companion object {
        private const val CALENDAR_PERMISSION_REQUEST = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CactusContextInitializer.initialize(this)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        requestPermissionButton = findViewById(R.id.requestPermissionButton)
        settingsButton = findViewById(R.id.settingsButton)

        // Chat components
        chatInput = findViewById(R.id.chatInput)
        sendButton = findViewById(R.id.sendButton)
        chatHistoryContainer = findViewById(R.id.chatHistoryContainer)
        chatScrollView = findViewById(R.id.chatScrollView)

        // Prevent sends until async AI initialization completes.
        sendButton.isEnabled = false
        chatInput.isEnabled = false

        // Clean up old notifications on app startup (24h TTL)
        cleanupOldNotifications()

        // Initialize AI components
        initializeAI()

        // Start background summarization service
        startNotificationSummarizationService()

        requestPermissionButton.setOnClickListener {
            requestAllPermissions()
        }

        // Settings button handler
        settingsButton.setOnClickListener {
            openAppPrioritySettings()
        }

        // Chat send button handler
        sendButton.setOnClickListener {
            sendMessage()
        }

        checkPermissionsAndSetup()
    }

    /**
     * Start the background notification summarization service.
     */
    private fun startNotificationSummarizationService() {
        val serviceIntent = Intent(this, com.verdure.services.NotificationSummarizationService::class.java)
        startService(serviceIntent)
    }

    /**
     * Clean up notifications older than 24 hours from Room database.
     * Runs on app startup to maintain storage efficiency.
     */
    private fun cleanupOldNotifications() {
        lifecycleScope.launch {
            try {
                val repository = NotificationRepository.getInstance(applicationContext)
                repository.cleanupOldNotifications()
            } catch (e: Exception) {
                // Cleanup failure is non-critical, just log
                android.util.Log.e("MainActivity", "Failed to cleanup old notifications", e)
            }
        }
    }

    /**
     * Initialize the LLM engine and VerdureAI orchestrator.
     */
    private fun initializeAI() {
        lifecycleScope.launch {
            // Initialize Cactus engine (Qwen 3 0.6B)
            // Use Singleton instance for shared memory usage
            llmEngine = CactusLLMEngine.getInstance(applicationContext)
            val initialized = llmEngine.initialize()

            if (initialized) {
                // Initialize user context manager
                val contextManager = UserContextManager.getInstance(applicationContext)

                // Initialize installed apps manager
                val appsManager = InstalledAppsManager(applicationContext)

                // Create VerdureAI orchestrator with context
                verdureAI = VerdureAI(llmEngine, contextManager)

                // Register tools (NotificationTool needs context for Room access)
                verdureAI.registerTool(NotificationTool(applicationContext, llmEngine, contextManager))
                verdureAI.registerTool(AppPrioritizationTool(contextManager, appsManager))

                println("✅ Verdure AI initialized successfully")
                println("   Tools registered: ${verdureAI.getAvailableTools().size}")

                // Enable chat once AI is ready
                runOnUiThread {
                    sendButton.isEnabled = true
                    chatInput.isEnabled = true
                }
            } else {
                println("❌ Failed to initialize Verdure AI")
                runOnUiThread {
                    sendButton.isEnabled = false
                    chatInput.isEnabled = false
                    addMessageToChat("System", "Failed to initialize AI. Check model setup.")
                }
            }
        }
    }

    /**
     * Send user message and get V's response
     */
    private fun sendMessage() {
        if (!::verdureAI.isInitialized) {
            addMessageToChat("System", "AI is still initializing. Please wait a few seconds and try again.")
            return
        }

        val userMessage = chatInput.text.toString().trim()
        if (userMessage.isEmpty()) return

        // Clear input
        chatInput.text.clear()

        // Show user message
        addMessageToChat("You", userMessage)

        // Disable input while processing
        sendButton.isEnabled = false
        chatInput.isEnabled = false

        lifecycleScope.launch {
            try {
                // Show thinking indicator
                val thinkingView = addMessageToChat("V", "Thinking...")

                // Get response from VerdureAI
                val response = verdureAI.processRequest(userMessage)

                // Remove thinking indicator and show response
                runOnUiThread {
                    chatHistoryContainer.removeView(thinkingView)
                    addMessageToChat("V", response)
                    scrollToBottom()

                    // Re-enable input
                    sendButton.isEnabled = true
                    chatInput.isEnabled = true
                    chatInput.requestFocus()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    chatHistoryContainer.findViewWithTag<TextView>("thinking")?.let {
                        chatHistoryContainer.removeView(it)
                    }
                    addMessageToChat("System", "❌ Error: ${e.message}")
                    sendButton.isEnabled = true
                    chatInput.isEnabled = true
                }
            }
        }
    }

    /**
     * Add a message to the chat history with Verdure styling
     */
    private fun addMessageToChat(sender: String, message: String): TextView {
        val messageView = TextView(this).apply {
            text = when (sender) {
                "You" -> message
                "V" -> message
                else -> message
            }
            textSize = 14f
            setPadding(32, 24, 32, 24)
            setTextColor(resources.getColor(R.color.text_primary, null))

            // Style based on sender
            when (sender) {
                "You" -> {
                    setBackgroundResource(R.drawable.message_bubble_user)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, 16)
                        gravity = Gravity.END
                    }
                }
                "V" -> {
                    setBackgroundResource(R.drawable.message_bubble_ai)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, 16)
                    }
                    if (message == "Thinking...") {
                        tag = "thinking"
                        setTextColor(resources.getColor(R.color.text_secondary, null))
                    }
                }
                else -> {
                    setBackgroundResource(R.drawable.message_bubble_system)
                    setTextColor(resources.getColor(R.color.status_error, null))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, 16)
                    }
                }
            }
        }

        chatHistoryContainer.addView(messageView)
        scrollToBottom()
        return messageView
    }

    /**
     * Scroll chat to bottom
     */
    private fun scrollToBottom() {
        chatScrollView.post {
            chatScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check permissions when returning to the app
        checkPermissionsAndSetup()
    }

    /**
     * Check if calendar permission is granted.
     */
    private fun hasCalendarPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if notification listener permission is granted.
     */
    private fun isNotificationListenerEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )
        val componentName = ComponentName(this, VerdureNotificationListener::class.java)
        return enabledListeners?.contains(componentName.flattenToString()) == true
    }

    /**
     * Request all necessary permissions.
     */
    private fun requestAllPermissions() {
        // Request calendar permission
        if (!hasCalendarPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_CALENDAR),
                CALENDAR_PERMISSION_REQUEST
            )
        }

        // Open notification listener settings
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivity(intent)
    }

    /**
     * Handle permission request results.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CALENDAR_PERMISSION_REQUEST) {
            checkPermissionsAndSetup()
        }
    }

    /**
     * Open app priority settings
     */
    private fun openAppPrioritySettings() {
        val intent = Intent(this, AppPriorityActivity::class.java)
        startActivity(intent)
    }

    /**
     * Check all permissions and update status text.
     */
    private fun checkPermissionsAndSetup() {
        val hasCalendar = hasCalendarPermission()
        val hasNotifications = isNotificationListenerEnabled()

        when {
            hasCalendar && hasNotifications -> {
                statusText.text = "✅ All permissions granted • Ready"
                requestPermissionButton.isEnabled = false
                requestPermissionButton.text = "Permissions Granted"
            }
            hasCalendar && !hasNotifications -> {
                statusText.text = "⚠️ Notification access required"
                requestPermissionButton.isEnabled = true
            }
            !hasCalendar && hasNotifications -> {
                statusText.text = "⚠️ Calendar access required"
                requestPermissionButton.isEnabled = true
            }
            else -> {
                statusText.text = "⚠️ Permissions required to get started"
                requestPermissionButton.isEnabled = true
            }
        }
    }
}
