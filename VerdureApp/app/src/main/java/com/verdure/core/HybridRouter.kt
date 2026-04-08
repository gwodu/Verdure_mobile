package com.verdure.core

import android.content.Context
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.util.Log

/**
 * Hybrid routing stub for local-vs-cloud inference.
 *
 * TODO: Add cloud fallback path in a follow-up PR.
 */
class HybridRouter(private val context: Context) {

    enum class Route {
        LOCAL,
        CLOUD
    }

    companion object {
        private const val TAG = "HybridRouter"
    }

    fun chooseRoute(promptTokenEstimate: Int): Route {
        val batteryPct = readBatteryPercent()
        val hasNetwork = isNetworkAvailable()

        Log.d(
            TAG,
            "Route decision inputs: batteryPct=$batteryPct hasNetwork=$hasNetwork promptTokens=$promptTokenEstimate"
        )

        // Stub behavior for v2: always local.
        return Route.LOCAL
    }

    private fun readBatteryPercent(): Int {
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        return batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.activeNetwork != null
    }
}
