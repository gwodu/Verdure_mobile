package com.verdure.voice

/**
 * In-process hub that connects the two halves of dictation:
 *
 *  - [VoiceInputAccessibilityService] owns the floating mic + text injection
 *    (it is the only component allowed to read/write other apps' fields).
 *  - [DictationForegroundService] owns the microphone + Whisper transcription
 *    (a `microphone`-typed foreground service, as Android 14 requires).
 *
 * Neither service can hold a binder to the other cleanly, so they rendezvous
 * here. The accessibility service registers as [host]; the foreground service
 * pushes state + transcription results through this object.
 */
object DictationCoordinator {

    enum class State { IDLE, RECORDING, TRANSCRIBING, ERROR }

    /** Implemented by the accessibility service. */
    interface Host {
        /** Drive the floating-mic visuals. */
        fun onDictationState(state: State, message: String?)

        /** Write finished text into the currently focused field. */
        fun onTranscription(text: String)
    }

    @Volatile
    var host: Host? = null
        private set

    @Volatile
    var state: State = State.IDLE
        private set

    fun registerHost(h: Host) {
        host = h
    }

    fun unregisterHost(h: Host) {
        if (host === h) host = null
    }

    fun setState(newState: State, message: String? = null) {
        state = newState
        host?.onDictationState(newState, message)
    }

    fun deliverTranscription(text: String) {
        host?.onTranscription(text)
        setState(State.IDLE)
    }
}
