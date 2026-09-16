package com.softwaremine.dps.automationtarget

import android.app.Activity
import android.os.Bundle
import android.widget.Button

/**
 * M9 Phase 1's deterministic automation test target (see this module's own
 * `build.gradle.kts` doc — never shipped).
 *
 * ## Deterministic state, by construction
 * One screen, one button (`R.id.automation_target_button`), fixed English
 * text (`"Tap me"`). On click, the button's own displayed text changes to
 * the fixed string `"Tapped"` — no persisted state, no network call, no
 * animation beyond the platform's own default. A fresh launch always shows
 * `"Tap me"` again; there is nothing to explicitly reset.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.automation_target_button).setOnClickListener { button ->
            (button as Button).text = TAPPED_TEXT
        }
    }

    private companion object {
        const val TAPPED_TEXT = "Tapped"
    }
}
