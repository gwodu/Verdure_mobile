package com.verdure.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.verdure.R

/**
 * A plain screen with ordinary text fields for verifying voice typing.
 *
 * Serves two audiences:
 *  - Users: a safe place to confirm the floating mic appears and dictated
 *    text lands where the cursor is, before trusting it in other apps.
 *  - CI: the dictation end-to-end workflow drives these fields with
 *    uiautomator and asserts injected text actually arrives.
 */
class DictationTestActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dictation_test)
    }
}
