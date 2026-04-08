package com.verdure.data

import android.content.Context
import android.util.Log
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File

/**
 * Helper to register sqlite-vec extension against BundledSQLiteDriver.
 */
object SQLiteVecLoader {
    private const val TAG = "SQLiteVecLoader"
    private const val SQLITE_VEC_SO_NAME = "vec0.so"
    private const val SQLITE_VEC_ENTRY = "sqlite3_vec_init"

    fun registerOnDriver(context: Context, driver: BundledSQLiteDriver): Boolean {
        val extensionFile = File(context.applicationInfo.nativeLibraryDir, SQLITE_VEC_SO_NAME)
        return if (extensionFile.exists()) {
            driver.addExtension(extensionFile.absolutePath, SQLITE_VEC_ENTRY)
            Log.d(TAG, "Registered sqlite-vec extension: ${extensionFile.absolutePath}")
            true
        } else {
            Log.e(TAG, "sqlite-vec extension missing at ${extensionFile.absolutePath}")
            false
        }
    }
}
