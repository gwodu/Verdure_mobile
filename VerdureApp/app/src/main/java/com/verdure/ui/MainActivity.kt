package com.verdure.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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
import com.verdure.core.CactusEmbeddingEngine
import com.verdure.core.CactusLLMEngine
import com.verdure.core.VerdureAI
import com.verdure.data.InstalledAppsManager
import com.verdure.data.LLMResponse
import com.verdure.data.NotificationRepository
import com.verdure.data.UserContextManager
import com.verdure.data.ChatHistoryStore
import com.verdure.services.IngestionPipeline
import com.verdure.services.VerdureNotificationListener
import com.verdure.tools.AppPrioritizationTool
import com.verdure.tools.NotificationTool
import com.verdure.tools.SemanticRetrievalTool
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var modelSlugText: TextView
    private lateinit var requestPermissionButton: Button
    private lateinit var settingsButton: android.widget.ImageView
    private lateinit var incentivesButton: TextView

    // Chat components
    private lateinit var chatInput: EditText
    private lateinit var sendButton: Button
    private lateinit var chatHistoryContainer: LinearLayout
    private lateinit var chatScrollView: ScrollView
    private lateinit var chatHistoryStore: ChatHistoryStore

    // AI components
    private lateinit var llmEngine: CactusLLMEngine
    private lateinit var embeddingEngine: CactusEmbeddingEngine
    private lateinit var verdureAI: VerdureAI

    companion object {
        private const val TAG = "MainActivity"
        private const val CALENDAR_PERMISSION_REQUEST = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CactusContextInitializer.initialize(this)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        modelSlugText = findViewById(R.id.modelSlugText)
        requestPermissionButton = findViewById(R.id.requestPermissionButton)
        settingsButton = findViewById(R.id.settingsButton)
        incentivesButton = findViewById(R.id.incentivesButton)
        modelSlugText.text = "Model: ${CactusLLMEngine.getConfiguredModelSlug()}"
        Log.i(TAG, "LLM self-check configured model slug=${CactusLLMEngine.getConfiguredModelSlug()}")

        // Chat components
        chatInput = findViewById(R.id.chatInput)
        sendButton = findViewById(R.id.sendButton)
        chatHistoryContainer = findViewById(R.id.chatHistoryContainer)
        chatScrollView = findViewById(R.id.chatScrollView)
        chatHistoryStore = ChatHistoryStore(applicationContext)

        // Prevent sends until async AI initialization completes.
        sendButton.isEnabled = false
        chatInput.isEnabled = false

        // Restore persisted chat bubbles across app restarts.
        restoreChatHistory()

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

        // Incentives button handler
        incentivesButton.setOnClickListener {
            openIncentives()
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
     * Shows a live status message in chat while the model downloads/loads.
     */
    private fun initializeAI() {
        // Show a status bubble immediately so the user knows something is happening
        val statusBubble = addMessageToChat(
            "System",
            "⏳ Loading AI model… (first launch may take a minute)",
            null
        )

        lifecycleScope.launch {
            llmEngine = CactusLLMEngine.getInstance(applicationContext)
            embeddingEngine = CactusEmbeddingEngine.getInstance(applicationContext)

            val initialized = llmEngine.initialize { progressMessage ->
                runOnUiThread { statusBubble.text = progressMessage }
            }

            runOnUiThread {
                if (initialized) {
                    modelSlugText.text = "Model: ${llmEngine.getActiveModelSlug() ?: CactusLLMEngine.getConfiguredModelSlug()} (active)"
                    // Initialize user context manager
                    val contextManager = UserContextManager.getInstance(applicationContext)

                    // Initialize installed apps manager
                    val appsManager = InstalledAppsManager(applicationContext)

                    // Create VerdureAI orchestrator with context
                    verdureAI = VerdureAI(applicationContext, llmEngine, contextManager)
                    verdureAI.setRecentConversationTurns(chatHistoryStore.getRecentForPrompt())

                    // Register tools (NotificationTool needs context for Room access)
                    verdureAI.registerTool(NotificationTool(applicationContext, llmEngine, contextManager))
                    verdureAI.registerTool(AppPrioritizationTool(contextManager, appsManager))
                    verdureAI.registerTool(SemanticRetrievalTool(applicationContext, contextManager))

                    // Initialize ingestion pipeline (runs independently in background).
                    IngestionPipeline.getInstance(applicationContext).warmup()

                    // Step 1 throwaway embedding self-check log.
                    lifecycleScope.launch {
                        val embeddingReady = embeddingEngine.initialize { message ->
                            Log.d(TAG, "Embedding init progress: $message")
                        }
                        if (embeddingReady) {
                            try {
                                val vec = embeddingEngine.embed("Verdure embedding self-check")
                                Log.d(
                                    TAG,
                                    "Embedding self-check success: dim=${vec.size}, model=${embeddingEngine.getActiveModelSlug()}"
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Embedding self-check failed after init", e)
                            }
                        } else {
                            Log.e(TAG, "Embedding init failed: ${embeddingEngine.getLastInitError()}")
                        }
                    }

                    // Remove the status bubble and enable chat
                    chatHistoryContainer.removeView(statusBubble.parent as? android.view.View ?: statusBubble)
                    sendButton.isEnabled = true
                    chatInput.isEnabled = true
                } else {
                    modelSlugText.text = "Model: ${CactusLLMEngine.getConfiguredModelSlug()} (failed)"
                    val details = llmEngine.getLastInitError()
                    statusBubble.text = if (details.isNullOrBlank()) {
                        "❌ Failed to load AI model. Check internet, free storage, then restart the app."
                    } else {
                        "❌ Failed to load AI model.\n$details"
                    }
                    sendButton.isEnabled = false
                    chatInput.isEnabled = false
                }
            }
        }
    }

    /**
     * Send user message and get V's response
     */
    private fun sendMessage() {
        if (!::verdureAI.isInitialized) {
            addMessageToChat("System", "AI is still initializing. Please wait a few seconds and try again.", null)
            return
        }

        val userMessage = chatInput.text.toString().trim()
        if (userMessage.isEmpty()) return

        // Clear input
        chatInput.text.clear()

        // Show user message
        addMessageToChat("You", userMessage, null)
        lifecycleScope.launch {
            chatHistoryStore.appendUserMessage(userMessage)
            if (::verdureAI.isInitialized) {
                verdureAI.setRecentConversationTurns(chatHistoryStore.getRecentForPrompt())
            }
        }

        // Disable input while processing
        sendButton.isEnabled = false
        chatInput.isEnabled = false

        lifecycleScope.launch {
            try {
                // Show thinking indicator
                val thinkingView = addMessageToChat("V", "Thinking...", null)

                // Get response from VerdureAI
                val rawResponse = verdureAI.processRequest(userMessage)
                
                // Parse response to separate thinking from actual response
                val parsedResponse = LLMResponse.parse(rawResponse)

                // Remove thinking indicator and show parsed response
                runOnUiThread {
                    chatHistoryContainer.removeView(thinkingView)
                    addMessageToChat("V", parsedResponse.response, parsedResponse.thinking)
                    lifecycleScope.launch {
                        chatHistoryStore.appendAssistantMessage(parsedResponse.response)
                        if (::verdureAI.isInitialized) {
                            verdureAI.setRecentConversationTurns(chatHistoryStore.getRecentForPrompt())
                        }
                    }
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
                    addMessageToChat("System", "❌ Error: ${e.message}", null)
                    sendButton.isEnabled = true
                    chatInput.isEnabled = true
                }
            }
        }
    }

    /**
     * Add a message to the chat history with Verdure styling
     * @param thinkingContent Optional thinking section to show in collapsible view
     */
    private fun addMessageToChat(sender: String, message: String, thinkingContent: String?): TextView {
        // Create container for message + optional thinking section
        val containerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
        }

        // Add thinking section if present (only for V's responses)
        if (sender == "V" && thinkingContent != null && message != "Thinking...") {
            val thinkingSection = createThinkingSection(thinkingContent)
            containerLayout.addView(thinkingSection)
        }

        // Create main message view
        val messageView = TextView(this).apply {
            text = message
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
                        gravity = Gravity.END
                    }
                }
                "V" -> {
                    setBackgroundResource(R.drawable.message_bubble_ai)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
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
                    )
                }
            }
        }

        // Add message to container
        containerLayout.addView(messageView)

        // Add container to chat
        chatHistoryContainer.addView(containerLayout)
        scrollToBottom()
        
        return messageView
    }

    /**
     * Create collapsible thinking section
     */
    private fun createThinkingSection(thinkingContent: String): LinearLayout {
        val thinkingContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 8)
            }
        }

        // Collapsible header
        val thinkingHeader = TextView(this).apply {
            text = "▶ View thinking process"
            textSize = 12f
            setPadding(24, 12, 24, 12)
            setTextColor(resources.getColor(R.color.text_secondary, null))
            setBackgroundResource(R.drawable.message_bubble_system)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
        }

        // Collapsible content (initially hidden)
        val thinkingBody = TextView(this).apply {
            text = thinkingContent
            textSize = 12f
            setPadding(32, 16, 32, 16)
            setTextColor(resources.getColor(R.color.text_secondary, null))
            setBackgroundResource(R.drawable.message_bubble_system)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 4, 0, 0)
            }
            visibility = android.view.View.GONE
        }

        // Toggle visibility on click
        var isExpanded = false
        thinkingHeader.setOnClickListener {
            isExpanded = !isExpanded
            if (isExpanded) {
                thinkingBody.visibility = android.view.View.VISIBLE
                thinkingHeader.text = "▼ Hide thinking process"
            } else {
                thinkingBody.visibility = android.view.View.GONE
                thinkingHeader.text = "▶ View thinking process"
            }
        }

        thinkingContainer.addView(thinkingHeader)
        thinkingContainer.addView(thinkingBody)

        return thinkingContainer
    }

    /**
     * Scroll chat to bottom
     */
    private fun scrollToBottom() {
        chatScrollView.post {
            chatScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    /**
     * Rehydrate chat UI from persisted history.
     */
    private fun restoreChatHistory() {
        val turns = chatHistoryStore.getAllForDisplay()
        if (turns.isEmpty()) return

        chatHistoryContainer.removeAllViews()
        turns.forEach { turn ->
            val sender = when (turn.role) {
                "user" -> "You"
                "assistant" -> "V"
                else -> "System"
            }
            addMessageToChat(sender, turn.content, null)
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
     * Open incentives/goals tracker
     */
    private fun openIncentives() {
        val intent = Intent(this, IncentivesActivity::class.java)
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
