package com.verdure.services

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.PixelFormat
import android.os.Build
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
 * Renders a floating mic over other apps via an accessibility overlay — but
 * only while a text field has input focus, so the button appears exactly when
 * there is somewhere for dictated text to go. It is the only component
 * permitted to read the focused text field of *other* apps and write
 * Whisper's output into it.
 *
 * Tap the mic → the focused field is captured as the dictation target and
 * recording starts (delegated to [DictationForegroundService]). Tap again →
 * recording stops, Whisper transcribes, and the text is inserted at the
 * cursor of the captured target field.
 */
class VoiceInputAccessibilityService : AccessibilityService(), DictationCoordinator.Host {

    private var windowManager: WindowManager? = null
    private var overlay: View? = null
    private var micIcon: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    /**
     * The field dictation will type into, captured the moment the mic is
     * tapped. Injection prefers this (after [AccessibilityNodeInfo.refresh])
     * over a fresh focus search, so text lands in the field the user was in
     * when they started dictating even if focus wandered meanwhile.
     */
    private var dictationTarget: AccessibilityNodeInfo? = null

    private var debugReceiver: BroadcastReceiver? = null

    companion object {
        private const val TAG = "VoiceInputA11y"

        /** Delay before hiding the mic after focus loss, to avoid flicker. */
        private const val HIDE_DELAY_MS = 400L

        // Debug-build-only hooks so the injection path can be exercised from
        // adb without recording audio or running Whisper:
        //   adb shell am broadcast -a com.verdure.dictation.DEBUG_INJECT --es text "hello"
        //   adb shell am broadcast -a com.verdure.dictation.DEBUG_DUMP
        const val ACTION_DEBUG_INJECT = "com.verdure.dictation.DEBUG_INJECT"
        const val ACTION_DEBUG_DUMP = "com.verdure.dictation.DEBUG_DUMP"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        DictationCoordinator.registerHost(this)
        addFloatingMic()
        registerDebugHooksIfDebuggable()
        updateMicVisibility()
        Log.i(TAG, "Voice input accessibility service connected")
    }

    // ── Floating mic overlay ──────────────────────────────────────────────

    private fun addFloatingMic() {
        if (overlay != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_floating_mic, null)
        micIcon = view.findViewById(R.id.floatingMicIcon)
        // Hidden until a text field takes focus.
        view.visibility = View.GONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 24
            y = 420
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
                        // Gravity is BOTTOM|END, so x/y offsets grow leftward/upward.
                        params.x = initialX - dx
                        params.y = initialY - dy
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
            else -> {
                // Lock onto the field the user is dictating into BEFORE
                // recording starts — this is where the text will be inserted.
                val target = findEditableTarget()
                if (target == null) {
                    Toast.makeText(
                        this,
                        "Tap into a text field first, then tap the mic",
                        Toast.LENGTH_SHORT
                    ).show()
                    return
                }
                dictationTarget = target
                Log.i(TAG, "Dictation target locked: ${describeNode(target)}")
                DictationForegroundService.start(this)
            }
        }
    }

    // ── Show the mic only while a text field is focused ───────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> updateMicVisibility()
        }
    }

    private val hideRunnable = Runnable {
        if (!shouldShowMic()) {
            if (overlay?.visibility == View.VISIBLE) Log.i(TAG, "Floating mic hidden")
            overlay?.visibility = View.GONE
        }
    }

    private fun updateMicVisibility() {
        val view = overlay ?: return
        if (shouldShowMic()) {
            view.removeCallbacks(hideRunnable)
            if (view.visibility != View.VISIBLE) {
                Log.i(TAG, "Floating mic shown (text field focused)")
                view.visibility = View.VISIBLE
            }
        } else {
            // Small grace period: focus flickers when switching between
            // fields or when the IME animates; don't blink the button.
            view.removeCallbacks(hideRunnable)
            view.postDelayed(hideRunnable, HIDE_DELAY_MS)
        }
    }

    private fun shouldShowMic(): Boolean {
        // Never hide mid-dictation — the user needs the button to stop it,
        // and the transcription still needs somewhere to land.
        when (DictationCoordinator.state) {
            DictationCoordinator.State.RECORDING,
            DictationCoordinator.State.TRANSCRIBING -> return true
            else -> {}
        }
        return findFocusedEditableTarget() != null
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
            updateMicVisibility()
        }
    }

    override fun onTranscription(text: String) {
        runOnMain { injectText(text) }
    }

    // ── Text injection into the focused field of any app ──────────────────

    private fun injectText(text: String) {
        // Clipboard backstop first: even if injection fails, the user can paste.
        copyToClipboard(text)

        val node = resolveInjectionTarget()
        dictationTarget = null
        if (node == null) {
            Log.w(TAG, "Injection failed: no editable target found")
            Toast.makeText(
                this,
                "No text field focused — copied to clipboard, long-press to paste",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        Log.i(TAG, "Injecting into ${describeNode(node)}")

        // An empty field often *reports* its hint ("Type a message…") as text.
        // Treat hint text as empty or we'd splice the dictation into the hint.
        val showingHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText
        val existing = if (showingHint) "" else node.text?.toString() ?: ""
        val selStart = node.textSelectionStart
        val selEnd = node.textSelectionEnd
        val hasSelection = !showingHint &&
            selStart in 0..existing.length && selEnd in 0..existing.length
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
        Log.i(TAG, "ACTION_SET_TEXT result=$ok (${newText.length} chars)")
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

    /**
     * The field to type into: prefer the target captured when dictation
     * started (refreshed, in case the view updated), then fall back to a
     * live focus search.
     */
    private fun resolveInjectionTarget(): AccessibilityNodeInfo? {
        dictationTarget?.let { captured ->
            val fresh = try {
                captured.refresh()
            } catch (e: Exception) {
                Log.w(TAG, "Captured target refresh threw", e)
                false
            }
            if (fresh && captured.isEditable && captured.isVisibleToUser) {
                Log.i(TAG, "Using captured dictation target")
                return captured
            }
            Log.i(TAG, "Captured target stale (refresh=$fresh), falling back to focus search")
        }
        return findEditableTarget()
    }

    /**
     * Find the editable field to type into. The focused field may live in the
     * active window or any other interactive window (some apps host the editor
     * in a child window), so we search broadly before giving up.
     */
    private fun findEditableTarget(): AccessibilityNodeInfo? {
        findFocusedEditableTarget()?.let { return it }

        // Last resort: the first editable node in the active window.
        return rootInActiveWindow?.let { findFirstEditable(it) }
    }

    /** Strictly focus-based search (also drives mic visibility). */
    private fun findFocusedEditableTarget(): AccessibilityNodeInfo? {
        // 1) Input focus in the active window.
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { it.isEditable }
            ?.let { return it }

        // 2) Input focus in any interactive window.
        for (window in windows) {
            val root = window.root ?: continue
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.takeIf { it.isEditable }
                ?.let { return it }
        }

        // 3) Any focused editable node we can find.
        rootInActiveWindow?.let { root ->
            findFocusedEditable(root)?.let { return it }
        }
        return null
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findFocusedEditable(child)?.let { return it }
        }
        return null
    }

    private fun pasteFallback(node: AccessibilityNodeInfo, text: String) {
        copyToClipboard(text)
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.i(TAG, "ACTION_PASTE fallback result=$pasted")
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

    private fun describeNode(node: AccessibilityNodeInfo): String =
        "class=${node.className} pkg=${node.packageName} " +
            "id=${node.viewIdResourceName} editable=${node.isEditable} " +
            "focused=${node.isFocused} visible=${node.isVisibleToUser}"

    private fun runOnMain(block: () -> Unit) {
        val icon = micIcon
        if (icon != null) {
            icon.post(block)
        } else {
            block()
        }
    }

    // ── Debug hooks (debuggable builds only) ──────────────────────────────

    private fun registerDebugHooksIfDebuggable() {
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable || debugReceiver != null) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_DEBUG_INJECT -> {
                        val text = intent.getStringExtra("text") ?: "debug injection test"
                        Log.i(TAG, "DEBUG_INJECT: '$text' via production delivery path")
                        // Exact production path: coordinator → host → injectText.
                        DictationCoordinator.deliverTranscription(text)
                    }
                    ACTION_DEBUG_DUMP -> {
                        val focused = findFocusedEditableTarget()
                        val target = focused ?: rootInActiveWindow?.let { findFirstEditable(it) }
                        val msg = if (target != null) {
                            "target: ${describeNode(target)} (focused=${focused != null})"
                        } else {
                            "no editable target found; activeWindowRoot=" +
                                "${rootInActiveWindow != null} windows=${windows.size}"
                        }
                        Log.i(TAG, "DEBUG_DUMP $msg")
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_DEBUG_INJECT)
            addAction(ACTION_DEBUG_DUMP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        debugReceiver = receiver
        Log.i(TAG, "Debug dictation hooks registered (DEBUG_INJECT / DEBUG_DUMP)")
    }

    // ── Required AccessibilityService overrides ───────────────────────────

    override fun onInterrupt() {
        // No-op.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        DictationCoordinator.unregisterHost(this)
        debugReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        debugReceiver = null
        overlay?.let { v ->
            v.removeCallbacks(hideRunnable)
            try {
                windowManager?.removeView(v)
            } catch (_: Exception) {
            }
        }
        overlay = null
        micIcon = null
    }
}
