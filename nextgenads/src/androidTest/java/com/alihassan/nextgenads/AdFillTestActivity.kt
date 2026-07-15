package com.alihassan.nextgenads

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/**
 * Minimal host Activity for instrumented ad-load tests. Plain [Activity] (no AppCompat/Material
 * theme requirement) with a single [FrameLayout] content view — enough of a real Activity context
 * for adaptive-banner sizing. Declared in the androidTest manifest; never shipped.
 */
class AdFillTestActivity : Activity() {

    lateinit var container: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = FrameLayout(this)
        setContentView(container)
    }
}
