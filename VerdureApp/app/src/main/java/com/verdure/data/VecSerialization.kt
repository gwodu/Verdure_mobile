package com.verdure.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utility for serializing float vectors into little-endian blobs for sqlite-vec.
 */
object VecSerialization {

    fun toBlob(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach { value -> buffer.putFloat(value) }
        return buffer.array()
    }
}

fun FloatArray.toSqliteVecBlob(): ByteArray = VecSerialization.toBlob(this)
