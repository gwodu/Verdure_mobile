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
        private const val VECTOR_DIMENSION = 384
    }

    private val database: RoomDatabase by lazy {
        NotificationDatabase.getInstance(context)
    }

    suspend fun ensureReady() {
        withContext(Dispatchers.IO) {
            database.useWriterConnection { connection: androidx.room.Transactor ->
                connection.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS $VEC_TABLE USING vec0(
                      embedding_id INTEGER PRIMARY KEY,
                      embedding FLOAT[$VECTOR_DIMENSION] distance_metric=cosine
                    )
                    """.trimIndent()
                )
            }
            Log.d(TAG, "sqlite-vec table ensured: $VEC_TABLE dim=$VECTOR_DIMENSION")
        }
    }

    suspend fun insert(id: Long, vector: FloatArray) {
        require(vector.size == VECTOR_DIMENSION) {
            "Vector dimension mismatch. expected=$VECTOR_DIMENSION actual=${vector.size}"
        }

        withContext(Dispatchers.IO) {
            database.useWriterConnection { connection: androidx.room.Transactor ->
                val sql =
                    "INSERT OR REPLACE INTO $VEC_TABLE (embedding_id, embedding) VALUES (?, ?)"
                connection.usePrepared(sql) { statement: androidx.sqlite.SQLiteStatement ->
                    statement.bindLong(1, id)
                    statement.bindBlob(2, vector.toSqliteVecBlob())
                    statement.step()
                }
            }
        }
    }

    suspend fun delete(id: Long) {
        withContext(Dispatchers.IO) {
            database.useWriterConnection { connection: androidx.room.Transactor ->
                connection.execSQL("DELETE FROM $VEC_TABLE WHERE embedding_id = $id")
            }
        }
    }

    suspend fun deleteMany(ids: List<Long>) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) {
            database.useWriterConnection { connection: androidx.room.Transactor ->
                ids.forEach { id ->
                    connection.execSQL("DELETE FROM $VEC_TABLE WHERE embedding_id = $id")
                }
            }
        }
    }

    suspend fun searchTopK(queryVector: FloatArray, k: Int): List<Pair<Long, Float>> {
        require(queryVector.size == VECTOR_DIMENSION) {
            "Query vector dimension mismatch. expected=$VECTOR_DIMENSION actual=${queryVector.size}"
        }
        if (k <= 0) return emptyList()

        return withContext(Dispatchers.IO) {
            val results = mutableListOf<Pair<Long, Float>>()
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
            Log.d(TAG, "Vector search returned ${results.size} rows for k=$k")
            results
        }
    }
}
