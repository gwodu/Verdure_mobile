package com.verdure.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cactus.CactusContextInitializer
import com.verdure.R
import com.verdure.core.CactusLLMEngine
import com.verdure.data.Incentive
import com.verdure.data.IncentiveStore
import com.verdure.tools.IncentiveTool
import kotlinx.coroutines.launch

class IncentivesActivity : AppCompatActivity() {

    private lateinit var incentivesList: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var addButton: TextView
    private lateinit var emptyStateAddButton: Button
    private lateinit var backButton: TextView

    private lateinit var incentiveStore: IncentiveStore
    private lateinit var incentiveTool: IncentiveTool
    private lateinit var adapter: IncentiveAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CactusContextInitializer.initialize(this)
        setContentView(R.layout.activity_incentives)

        // Initialize views
        incentivesList = findViewById(R.id.incentivesList)
        emptyState = findViewById(R.id.emptyState)
        addButton = findViewById(R.id.addIncentiveButton)
        emptyStateAddButton = findViewById(R.id.emptyStateAddButton)
        backButton = findViewById(R.id.backButton)

        // Initialize data
        incentiveStore = IncentiveStore(applicationContext)

        // Initialize LLM and tool
        lifecycleScope.launch {
            val llmEngine = CactusLLMEngine.getInstance(applicationContext)
            llmEngine.initialize()
            incentiveTool = IncentiveTool(applicationContext, llmEngine)
            
            // Setup UI after initialization
            runOnUiThread {
                setupRecyclerView()
                loadIncentives()
            }
        }

        // Click listeners
        addButton.setOnClickListener { showAddIncentiveDialog() }
        emptyStateAddButton.setOnClickListener { showAddIncentiveDialog() }
        backButton.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        if (::incentiveStore.isInitialized) {
            loadIncentives()
        }
    }

    private fun setupRecyclerView() {
        incentivesList.layoutManager = LinearLayoutManager(this)
        
        adapter = IncentiveAdapter(
            incentives = emptyList(),
            matchCounts = emptyMap(),
            onItemClick = { incentive -> showIncentiveDetails(incentive) },
            onToggleClick = { incentive -> toggleIncentive(incentive) },
            onDeleteClick = { incentive -> confirmDelete(incentive) }
        )
        
        incentivesList.adapter = adapter
    }

    private fun loadIncentives() {
        val incentives = incentiveStore.getAllIncentives()
        val matchCounts = incentives.associate { 
            it.id to incentiveStore.getMatches(it.id).size 
        }

        if (incentives.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            incentivesList.visibility = View.GONE
        } else {
            emptyState.visibility = View.GONE
            incentivesList.visibility = View.VISIBLE
            adapter.updateData(incentives, matchCounts)
        }
    }

    private fun showAddIncentiveDialog() {
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_add_incentive, null)
        
        val input = dialogView.findViewById<EditText>(R.id.incentiveDescriptionInput)
        val createButton = dialogView.findViewById<Button>(R.id.createButton)
        val cancelButton = dialogView.findViewById<Button>(R.id.cancelButton)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        createButton.setOnClickListener {
            val description = input.text.toString().trim()
            if (description.isEmpty()) {
                Toast.makeText(this, "Please enter a description", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            createIncentive(description)
            dialog.dismiss()
        }

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun createIncentive(description: String) {
        if (!::incentiveTool.isInitialized) {
            Toast.makeText(this, "AI is still initializing...", Toast.LENGTH_SHORT).show()
            return
        }

        // Show progress
        Toast.makeText(this, "Creating incentive...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val result = incentiveTool.execute(
                    mapOf(
                        "action" to "create",
                        "description" to description
                    )
                )

                runOnUiThread {
                    if (result.startsWith("Created incentive:")) {
                        Toast.makeText(this@IncentivesActivity, "Incentive created!", Toast.LENGTH_SHORT).show()
                        loadIncentives()
                    } else {
                        Toast.makeText(this@IncentivesActivity, result, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@IncentivesActivity,
                        "Error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showIncentiveDetails(incentive: Incentive) {
        val matches = incentiveStore.getMatches(incentive.id)
        
        val message = buildString {
            appendLine(incentive.name)
            appendLine()
            appendLine("Description:")
            appendLine(incentive.userDescription)
            appendLine()
            appendLine("Keywords tracked:")
            appendLine(incentive.keywords.joinToString(", "))
            appendLine()
            appendLine("Tracked notifications: ${matches.size}")
            
            if (matches.isNotEmpty()) {
                appendLine()
                appendLine("Recent matches:")
                matches.take(5).forEach { match ->
                    appendLine("• ${match.actionSummary}")
                }
            }
        }

        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun toggleIncentive(incentive: Incentive) {
        incentiveStore.toggleIncentiveActive(incentive.id)
        loadIncentives()
        
        val status = if (incentive.isActive) "paused" else "resumed"
        Toast.makeText(this, "${incentive.name} $status", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete(incentive: Incentive) {
        AlertDialog.Builder(this)
            .setTitle("Delete Incentive?")
            .setMessage("This will delete \"${incentive.name}\" and all its tracked notifications. This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                incentiveStore.deleteIncentive(incentive.id)
                loadIncentives()
                Toast.makeText(this, "Deleted ${incentive.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
