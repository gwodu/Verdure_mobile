package com.verdure.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.verdure.R
import com.verdure.data.InstalledAppsManager
import com.verdure.data.UserContextManager
import com.verdure.data.VerdurePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App Prioritization & Settings UI
 *
 * Allows users to:
 * 1. Configure notification auto-dismissal settings
 * 2. Visually order apps by priority using drag-and-drop
 *
 * Architecture:
 * - Settings stored in VerdurePreferences (SharedPreferences)
 * - App order stored in UserContext.priorityRules.customAppOrder
 * - Synced with chat-based prioritization (both update the same data)
 */
class AppPriorityActivity : AppCompatActivity() {

    // Settings toggles
    private lateinit var autoDismissSwitch: SwitchCompat
    private lateinit var excludeCalendarSwitch: SwitchCompat

    // App priority components
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppPriorityAdapter
    private lateinit var saveButton: Button
    private lateinit var cancelButton: Button
    private lateinit var instructionsText: TextView

    private lateinit var appsManager: InstalledAppsManager
    private lateinit var contextManager: UserContextManager
    private lateinit var preferences: VerdurePreferences

    private var hasUnsavedChanges = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_priority)

        // Initialize settings toggles
        autoDismissSwitch = findViewById(R.id.autoDismissSwitch)
        excludeCalendarSwitch = findViewById(R.id.excludeCalendarSwitch)

        // Initialize app priority components
        recyclerView = findViewById(R.id.appPriorityRecyclerView)
        saveButton = findViewById(R.id.saveButton)
        cancelButton = findViewById(R.id.cancelButton)
        instructionsText = findViewById(R.id.instructionsText)

        appsManager = InstalledAppsManager(this)
        contextManager = UserContextManager.getInstance(this)
        preferences = VerdurePreferences.getInstance(this)

        // Load current settings
        loadSettings()

        // Setup RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = AppPriorityAdapter(emptyList()) { position ->
            // Mark as changed when user drags
            hasUnsavedChanges = true
            updateSaveButtonState()
        }
        recyclerView.adapter = adapter

        // Enable drag-and-drop
        val itemTouchHelper = ItemTouchHelper(DragCallback(adapter))
        itemTouchHelper.attachToRecyclerView(recyclerView)

        // Load apps asynchronously
        loadApps()

        // Settings toggle handlers
        autoDismissSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferences.autoDismissEnabled = isChecked
            hasUnsavedChanges = true
            updateSaveButtonState()
        }

        excludeCalendarSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferences.excludeCalendarFromDismiss = isChecked
            hasUnsavedChanges = true
            updateSaveButtonState()
        }

        // Button handlers
        saveButton.setOnClickListener {
            saveAppOrder()
        }

        cancelButton.setOnClickListener {
            finish()
        }

        updateSaveButtonState()
    }

    /**
     * Load current settings from preferences
     */
    private fun loadSettings() {
        autoDismissSwitch.isChecked = preferences.autoDismissEnabled
        excludeCalendarSwitch.isChecked = preferences.excludeCalendarFromDismiss
    }

    /**
     * Load installed apps and apply current custom ordering
     */
    private fun loadApps() {
        lifecycleScope.launch {
            instructionsText.text = "Loading apps..."

            val installedApps = withContext(Dispatchers.IO) {
                appsManager.getInstalledApps()
            }

            // Load current custom order from context
            val context = contextManager.loadContext()
            val customOrder = context.priorityRules.customAppOrder

            // Sort apps by custom order (apps in order first, then alphabetical)
            val sortedApps = if (customOrder.isNotEmpty()) {
                val ordered = customOrder.mapNotNull { packageName ->
                    installedApps.find { it.packageName == packageName }
                }
                val remaining = installedApps.filterNot { app ->
                    customOrder.contains(app.packageName)
                }
                ordered + remaining
            } else {
                installedApps
            }

            adapter.updateApps(sortedApps)
            instructionsText.text = "Drag apps to reorder • First = highest priority"
            hasUnsavedChanges = false
            updateSaveButtonState()
        }
    }

    /**
     * Save the current app order and settings
     */
    private fun saveAppOrder() {
        lifecycleScope.launch {
            saveButton.isEnabled = false
            saveButton.text = "Saving..."

            // Save app order
            val currentOrder = adapter.getAppOrder()
            contextManager.updateAppPriorityOrder(currentOrder)

            // Settings are auto-saved by toggle listeners, just mark as saved
            hasUnsavedChanges = false

            // Success feedback
            saveButton.text = "Saved!"

            // Return to main activity after brief delay
            kotlinx.coroutines.delay(500)
            finish()
        }
    }

    /**
     * Update save button state based on changes
     */
    private fun updateSaveButtonState() {
        saveButton.isEnabled = hasUnsavedChanges
        saveButton.text = if (hasUnsavedChanges) "Save Changes" else "No Changes"
    }

    /**
     * Callback for drag-and-drop functionality
     */
    private class DragCallback(
        private val adapter: AppPriorityAdapter
    ) : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN,
        0
    ) {
        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val fromPos = viewHolder.bindingAdapterPosition
            val toPos = target.bindingAdapterPosition
            adapter.moveItem(fromPos, toPos)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            // No swipe actions
        }

        override fun isLongPressDragEnabled(): Boolean = true
    }
}
