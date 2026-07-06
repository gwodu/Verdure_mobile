package com.verdure.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * Captures microphone audio in exactly the format Whisper expects:
 * 16 kHz, mono, 16-bit PCM (little-endian).
 *
 * Recording happens on a dedicated thread; [stop] returns the full buffer.
 * The caller is responsible for holding RECORD_AUDIO permission and running
 * inside a `microphone`-typed foreground service (Android 14 requirement).
 */
class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        // Roughly 60s cap so a forgotten session can't grow unbounded.
        private const val MAX_BYTES = SAMPLE_RATE * 2 * 60
    }

    @Volatile private var isRecording = false
    private var recordThread: Thread? = null
    private var record: AudioRecord? = null
    private val buffer = ByteArrayOutputStream()

    fun isActive(): Boolean = isRecording

    /**
     * Begin capturing. Returns false if the recorder could not be started
     * (e.g. AudioRecord failed to initialize, or permission missing).
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isRecording) return true

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) {
            Log.e(TAG, "Invalid min buffer size: $minBuf")
            return false
        }
        val readChunk = minBuf * 2

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                readChunk
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord construction failed", e)
            return false
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized (state=${recorder.state})")
            recorder.release()
            return false
        }

        synchronized(buffer) { buffer.reset() }
        record = recorder
        isRecording = true

        recordThread = thread(name = "verdure-audio") {
            val chunk = ByteArray(readChunk)
            try {
                recorder.startRecording()
                while (isRecording) {
                    val read = recorder.read(chunk, 0, chunk.size)
                    if (read > 0) {
                        synchronized(buffer) {
                            if (buffer.size() + read <= MAX_BYTES) {
                                buffer.write(chunk, 0, read)
                            } else {
                                isRecording = false
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording loop error", e)
            } finally {
                try {
                    recorder.stop()
                } catch (_: Exception) {
                }
                recorder.release()
            }
        }
        return true
    }

    /**
     * Stop capturing and return the recorded 16-bit PCM bytes.
     * Returns an empty array if nothing was recorded.
     */
    fun stop(): ByteArray {
        if (!isRecording && recordThread == null) {
            return synchronized(buffer) { buffer.toByteArray() }
        }
        isRecording = false
        try {
            recordThread?.join(2_000)
        } catch (_: InterruptedException) {
        }
        recordThread = null
        record = null
        return synchronized(buffer) { buffer.toByteArray() }
    }

    /** Abandon any in-progress recording without returning data. */
    fun cancel() {
        isRecording = false
        try {
            recordThread?.join(1_000)
        } catch (_: InterruptedException) {
        }
        recordThread = null
        record = null
        synchronized(buffer) { buffer.reset() }
    }
}
