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
    private val SQLITE_VEC_SO_NAMES = listOf("vec0.so", "libvec0.so")
    private val SQLITE_VEC_ENTRIES = listOf("sqlite3_vec_init", "sqlite3_vec0_init", "sqlite3_extension_init")
    @Volatile
    private var vectorStoreAvailable: Boolean = true

    fun isVectorStoreAvailable(): Boolean = vectorStoreAvailable

    fun disableVectorStore(reason: String, throwable: Throwable? = null) {
        vectorStoreAvailable = false
        if (throwable != null) {
            Log.e(TAG, "Disabling sqlite-vec vector store: $reason", throwable)
        } else {
            Log.e(TAG, "Disabling sqlite-vec vector store: $reason")
        }
    }

    fun registerOnDriver(context: Context, driver: BundledSQLiteDriver): Boolean {
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val extensionFile = SQLITE_VEC_SO_NAMES
            .asSequence()
            .map { File(nativeLibDir, it) }
            .firstOrNull { it.exists() }

        if (extensionFile == null) {
            val available = nativeLibDir.list()?.sorted()?.joinToString(", ").orEmpty()
            Log.e(
                TAG,
                "sqlite-vec extension missing in ${nativeLibDir.absolutePath}. " +
                    "Looked for $SQLITE_VEC_SO_NAMES. Available: [$available]"
            )
            vectorStoreAvailable = false
            return false
        }

        SQLITE_VEC_ENTRIES.forEach { entrypoint ->
            try {
                driver.addExtension(extensionFile.absolutePath, entrypoint)
                vectorStoreAvailable = true
                Log.d(
                    TAG,
                    "Registered sqlite-vec extension: ${extensionFile.absolutePath} " +
                        "(entry=$entrypoint)"
                )
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "sqlite-vec registration attempt failed (entry=$entrypoint)", t)
            }
        }

        vectorStoreAvailable = false
        Log.e(
            TAG,
            "Failed to register sqlite-vec extension after trying entrypoints: $SQLITE_VEC_ENTRIES"
        )
        return false
    }
}
