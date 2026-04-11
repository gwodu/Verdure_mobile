package com.verdure.data

import android.util.Log
import androidx.room.RoomDatabase
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.room.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wrapper around sqlite-vec vec0 virtual table operations.
 *
 * Current configuration uses flat KNN with cosine metric via vec0.
 * TODO: Switch to HNSW when available in our sqlite-vec Android build.
 */
class VectorIndex(private val context: android.content.Context) {
    companion object {
        private const val TAG = "VectorIndex"
        private const val VEC_TABLE = "notification_embeddings_vec"
        private const val DEFAULT_VECTOR_DIMENSION = 1024
    }

    private val database: RoomDatabase by lazy {
        NotificationDatabase.getInstance(context)
    }
    @Volatile
    private var vectorDimension: Int = DEFAULT_VECTOR_DIMENSION

    private fun isDisabled(): Boolean = !SQLiteVecLoader.isVectorStoreAvailable()

    fun setVectorDimension(dimension: Int) {
        if (dimension <= 0) return
        vectorDimension = dimension
        Log.i(TAG, "Vector dimension set to $vectorDimension")
    }

    suspend fun ensureReady() {
        if (isDisabled()) {
            Log.w(TAG, "Skipping ensureReady; sqlite-vec vector store is disabled")
            return
        }
        withContext(Dispatchers.IO) {
            try {
                database.useWriterConnection { connection: androidx.room.Transactor ->
                    connection.execSQL(
                        """
                        CREATE VIRTUAL TABLE IF NOT EXISTS $VEC_TABLE USING vec0(
                          embedding_id INTEGER PRIMARY KEY,
                          embedding FLOAT[$vectorDimension] distance_metric=cosine
                        )
                        """.trimIndent()
                    )
                }
                Log.d(TAG, "sqlite-vec table ensured: $VEC_TABLE dim=$vectorDimension")
            } catch (e: Exception) {
                SQLiteVecLoader.disableVectorStore("ensureReady failed for vec0 table", e)
            }
        }
    }

    suspend fun insert(id: Long, vector: FloatArray) {
        require(vector.size == vectorDimension) {
            "Vector dimension mismatch. expected=$vectorDimension actual=${vector.size}"
        }
        if (isDisabled()) {
            Log.w(TAG, "Skipping vector insert id=$id; sqlite-vec vector store is disabled")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                database.useWriterConnection { connection: androidx.room.Transactor ->
                    val sql =
                        "INSERT OR REPLACE INTO $VEC_TABLE (embedding_id, embedding) VALUES (?, ?)"
                    connection.usePrepared(sql) { statement: androidx.sqlite.SQLiteStatement ->
                        statement.bindLong(1, id)
                        statement.bindBlob(2, vector.toSqliteVecBlob())
                        statement.step()
                    }
                }
            } catch (e: Exception) {
                SQLiteVecLoader.disableVectorStore("vector insert failed", e)
            }
        }
    }

    suspend fun delete(id: Long) {
        if (isDisabled()) return
        withContext(Dispatchers.IO) {
            try {
                database.useWriterConnection { connection: androidx.room.Transactor ->
                    connection.execSQL("DELETE FROM $VEC_TABLE WHERE embedding_id = $id")
                }
            } catch (e: Exception) {
                SQLiteVecLoader.disableVectorStore("vector delete failed", e)
            }
        }
    }

    suspend fun deleteMany(ids: List<Long>) {
        if (ids.isEmpty()) return
        if (isDisabled()) return
        withContext(Dispatchers.IO) {
            try {
                database.useWriterConnection { connection: androidx.room.Transactor ->
                    ids.forEach { id ->
                        connection.execSQL("DELETE FROM $VEC_TABLE WHERE embedding_id = $id")
                    }
                }
            } catch (e: Exception) {
                SQLiteVecLoader.disableVectorStore("vector bulk delete failed", e)
            }
        }
    }

    suspend fun searchTopK(queryVector: FloatArray, k: Int): List<Pair<Long, Float>> {
        require(queryVector.size == vectorDimension) {
            "Query vector dimension mismatch. expected=$vectorDimension actual=${queryVector.size}"
        }
        if (k <= 0) return emptyList()
        if (isDisabled()) {
            Log.w(TAG, "Skipping vector search; sqlite-vec vector store is disabled")
            return emptyList()
        }

        return withContext(Dispatchers.IO) {
            val results = mutableListOf<Pair<Long, Float>>()
            try {
                database.useReaderConnection { connection: androidx.room.Transactor ->
                    val sql =
                        """
                        SELECT embedding_id, distance
                        FROM $VEC_TABLE
                        WHERE embedding MATCH ?
                        AND k = ?
                        ORDER BY distance ASC
                        """.trimIndent()
                    connection.usePrepared(sql) { statement: androidx.sqlite.SQLiteStatement ->
                        statement.bindBlob(1, queryVector.toSqliteVecBlob())
                        statement.bindLong(2, k.toLong())
                        while (statement.step()) {
                            val embeddingId = statement.getLong(0)
                            val distance = statement.getDouble(1).toFloat()
                            results.add(embeddingId to distance)
                        }
                    }
                }
            } catch (e: Exception) {
                SQLiteVecLoader.disableVectorStore("vector search failed", e)
                return@withContext emptyList()
            }
            Log.d(TAG, "Vector search returned ${results.size} rows for k=$k")
            results
        }
    }
}
