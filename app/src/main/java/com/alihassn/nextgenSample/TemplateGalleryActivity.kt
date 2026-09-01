package com.alihassn.nextgenSample

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.alihassan.nextgenads.NextGenAds
import com.alihassan.nextgenads.nativead.NativeAdHelper
import com.alihassan.nextgenads.nativead.NativeTemplate
import com.alihassan.nextgenads.nativead.NativeTemplateView

/**
 * A live gallery of **every built-in [NativeTemplate]** — one real ad per template, each labelled
 * with the enum name and the `app:ngad_template` value that selects it, so you can see what a
 * template looks like and copy the name straight into your layout.
 *
 * The rows are built from [NativeTemplate.entries] rather than declared in XML, so a template added
 * to the library appears here automatically. Contrast [CustomNativeActivity], which showcases
 * *caller-supplied* layouts instead of the built-ins.
 *
 * Loads are **staggered** ([LOAD_STAGGER_MS] apart): fourteen simultaneous requests against one ad
 * unit is exactly the burst AdMob answers with no-fills, which would collapse half the gallery.
 */
class TemplateGalleryActivity : AppCompatActivity() {

    private companion object {
        /** Gap between consecutive template loads, so the unit isn't hit with 14 requests at once. */
        const val LOAD_STAGGER_MS = 250L

        /**
         * Height for [NativeTemplate.FULLSCREEN]. Its media is `layout_weight`-ed to eat the leftover
         * vertical space, so under a `wrap_content` slot it collapses to its `minHeight` — the
         * template is built for a host that gives it a real height, and the gallery plays that host.
         */
        const val FULLSCREEN_HEIGHT_DP = 560
    }

    private lateinit var status: TextView
    private lateinit var list: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val slots = mutableListOf<Slot>()

    private var loaded = 0
    private var noFill = 0

    /** One gallery row: the section label (mutated to report a no-fill) and the ad slot it titles. */
    private class Slot(
        val index: Int,
        val template: NativeTemplate,
        val label: TextView,
        val view: NativeTemplateView,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_template_gallery)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.templateGalleryRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        status = findViewById(R.id.tvGalleryStatus)
        list = findViewById(R.id.templateGalleryList)

        buildRows()

        findViewById<Button>(R.id.btnReloadGallery).setOnClickListener { loadAll() }

        loadAll()
    }

    /** Adds a label + usage hint + [NativeTemplateView] for every entry in [NativeTemplate]. */
    private fun buildRows() {
        NativeTemplate.entries.forEachIndexed { index, template ->
            val label = TextView(this, null, 0, R.style.Text_NextGen_SectionTitle).apply {
                layoutParams = rowParams(topMarginDp = if (index == 0) 6 else 28)
                text = titleFor(index, template)
            }

            // The whole point of the screen: the exact string to put in the layout.
            val hint = TextView(this, null, 0, R.style.Text_NextGen_Hint).apply {
                layoutParams = rowParams(topMarginDp = 2)
                text = "app:ngad_template=\"${template.name.lowercase()}\""
            }

            val slotView = NativeTemplateView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    heightFor(template),
                ).apply { topMargin = 10.dp }
                setTemplate(template)
            }

            list.addView(label)
            list.addView(hint)
            list.addView(slotView)
            slots += Slot(index, template, label, slotView)
        }
    }

    /** (Re)fills every slot, one [LOAD_STAGGER_MS] after the last. */
    private fun loadAll() {
        if (!NextGenAds.isInitialized()) {
            status.text = "Gather consent & initialize on the main screen first, then reload."
            return
        }

        handler.removeCallbacksAndMessages(null)
        loaded = 0
        noFill = 0
        slots.forEach { slot ->
            slot.label.text = titleFor(slot.index, slot.template)
            slot.view.showShimmer()
        }
        status.text = "Loading ${slots.size} built-in templates…"

        slots.forEach { slot ->
            handler.postDelayed({ populate(slot) }, slot.index * LOAD_STAGGER_MS)
        }
    }

    private fun populate(slot: Slot) {
        NativeAdHelper.populate(
            slot.view,
            AdUnits.NATIVE,
            // No refill: fourteen slots each warming a spare ad would keep the unit busy for nothing.
            refill = false,
            onLoaded = {
                loaded++
                reportProgress()
            },
            onFailed = {
                noFill++
                // populate() collapses the slot on failure, so say so on the label that's left behind.
                slot.label.text = "${titleFor(slot.index, slot.template)} — no fill"
                reportProgress()
            },
            remoteEnabled = AdsConfig.native,
        )
    }

    private fun reportProgress() {
        val settled = loaded + noFill
        status.text = when {
            settled < slots.size -> "$loaded of ${slots.size} loaded…"
            noFill == 0 -> "All ${slots.size} templates loaded."
            else -> "$loaded loaded · $noFill no fill."
        }
    }

    private fun titleFor(index: Int, template: NativeTemplate) = "${index + 1} · ${template.name}"

    /** Every template sizes itself from its content bar [NativeTemplate.FULLSCREEN]. */
    private fun heightFor(template: NativeTemplate): Int =
        if (template == NativeTemplate.FULLSCREEN) FULLSCREEN_HEIGHT_DP.dp
        else LinearLayout.LayoutParams.WRAP_CONTENT

    private fun rowParams(topMarginDp: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = topMarginDp.dp }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        // Drop the pending staggered loads first: they'd otherwise populate() a destroyed slot.
        handler.removeCallbacksAndMessages(null)
        slots.forEach { it.view.destroy() }
        super.onDestroy()
    }
}
