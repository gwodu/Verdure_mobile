package com.verdure.services

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.Toast
import com.verdure.R
import com.verdure.voice.DictationCoordinator
import kotlin.math.abs

/**
 * The "across every app" half of Verdure dictation.
 *
 * Renders a draggable floating mic over all apps via an accessibility overlay,
 * and — crucially — is the only component permitted to read the focused text
 * field of *other* apps and write Whisper's output into it.
 *
 * Tap the mic → recording starts (delegated to [DictationForegroundService]).
 * Tap again → recording stops, Whisper transcribes, and the text is inserted
 * at the cursor of whatever field currently has focus.
 */
class VoiceInputAccessibilityService : AccessibilityService(), DictationCoordinator.Host {

    private var windowManager: WindowManager? = null
    private var overlay: View? = null
    private var micIcon: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    companion object {
        private const val TAG = "VoiceInputA11y"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        DictationCoordinator.registerHost(this)
        addFloatingMic()
        Log.i(TAG, "Voice input accessibility service connected")
    }

    // ── Floating mic overlay ──────────────────────────────────────────────

    private fun addFloatingMic() {
        if (overlay != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_floating_mic, null)
        micIcon = view.findViewById(R.id.floatingMicIcon)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 320
        }
        layoutParams = params

        attachTouchHandler(view, params, wm)
        try {
            wm.addView(view, params)
            overlay = view
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating mic overlay", e)
        }
    }

    /** Distinguish a tap (toggle dictation) from a drag (reposition the mic). */
    private fun attachTouchHandler(
        view: View,
        params: WindowManager.LayoutParams,
        wm: WindowManager
    ) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging = false
        val slop = 16

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > slop || abs(dy) > slop) dragging = true
                    if (dragging) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        try {
                            wm.updateViewLayout(view, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) onMicTapped()
                    true
                }
                else -> false
            }
        }
    }

    private fun onMicTapped() {
        when (DictationCoordinator.state) {
            DictationCoordinator.State.RECORDING -> DictationForegroundService.stop(this)
            DictationCoordinator.State.TRANSCRIBING -> {
                Toast.makeText(this, "Still transcribing…", Toast.LENGTH_SHORT).show()
            }
            else -> DictationForegroundService.start(this)
        }
    }

    // ── DictationCoordinator.Host ─────────────────────────────────────────

    override fun onDictationState(state: DictationCoordinator.State, message: String?) {
        runOnMain {
            val icon = micIcon ?: return@runOnMain
            when (state) {
                DictationCoordinator.State.RECORDING ->
                    icon.setImageResource(R.drawable.ic_mic_active)
                DictationCoordinator.State.TRANSCRIBING ->
                    icon.setImageResource(R.drawable.ic_mic_thinking)
                DictationCoordinator.State.IDLE ->
                    icon.setImageResource(R.drawable.ic_mic)
                DictationCoordinator.State.ERROR -> {
                    icon.setImageResource(R.drawable.ic_mic)
                    if (!message.isNullOrBlank()) {
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onTranscription(text: String) {
        runOnMain { injectText(text) }
    }

    // ── Text injection into the focused field of any app ──────────────────

    private fun injectText(text: String) {
        val root = rootInActiveWindow
        val node = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root?.let { findFirstEditable(it) }

        if (node == null || !node.isEditable) {
            // Nothing we can type into — stash on the clipboard so the user can paste.
            copyToClipboard(text)
            Toast.makeText(
                this,
                "No text field focused — copied to clipboard",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val existing = node.text?.toString() ?: ""
        val selStart = node.textSelectionStart
        val selEnd = node.textSelectionEnd
        val hasSelection = selStart in 0..existing.length && selEnd in 0..existing.length
        val insertAt = if (hasSelection) minOf(selStart, selEnd) else existing.length
        val replaceEnd = if (hasSelection) maxOf(selStart, selEnd) else existing.length

        val prefix = existing.substring(0, insertAt)
        val suffix = existing.substring(replaceEnd.coerceIn(0, existing.length))
        // Add a separating space when appending to existing words.
        val spacer = if (prefix.isNotEmpty() && !prefix.endsWith(" ") &&
            !prefix.endsWith("\n") && !text.startsWith(" ")
        ) " " else ""
        val inserted = prefix + spacer + text
        val newText = inserted + suffix

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                newText
            )
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            // Place the cursor right after the inserted text.
            val cursor = inserted.length
            val selArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
        } else {
            // Some fields reject SET_TEXT — try paste as a fallback.
            pasteFallback(node, text)
        }
    }

    private fun pasteFallback(node: AccessibilityNodeInfo, text: String) {
        copyToClipboard(text)
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (!pasted) {
            Toast.makeText(this, "Couldn't insert here — copied to clipboard", Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Verdure dictation", text))
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditable(child)
            if (found != null) return found
        }
        return null
    }

    private fun runOnMain(block: () -> Unit) {
        val icon = micIcon
        if (icon != null) {
            icon.post(block)
        } else {
            block()
        }
    }

    // ── Required AccessibilityService overrides ───────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't react to events; we act on explicit user taps.
    }

    override fun onInterrupt() {
        // No-op.
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        DictationCoordinator.unregisterHost(this)
        overlay?.let { v ->
            try {
                windowManager?.removeView(v)
            } catch (_: Exception) {
            }
        }
        overlay = null
        micIcon = null
    }
}
