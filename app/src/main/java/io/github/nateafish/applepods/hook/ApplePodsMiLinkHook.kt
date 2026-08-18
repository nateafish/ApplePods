package io.github.nateafish.applepods.hook

import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import io.github.nateafish.applepods.protocol.ApplePodsAapProtocol
import io.github.nateafish.applepods.protocol.HyperOsAirPodsRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.WeakHashMap

/** Extends HyperOS' native control-center ANC controller with adaptive audio. */
object ApplePodsMiLinkHook : HookContext() {
    private const val TAG = "ApplePods-MiLink"
    private const val CONTROL_CENTER_ADAPTIVE = 3
    private const val ADAPTIVE_GRACE_MS = 3_000L
    private const val ADAPTIVE_NOISE_CONFIRM_DELAY_MS = 5_000L
    private const val ADAPTIVE_NOISE_CONFIRM_RETRY_DELAY_MS = 5_000L
    private const val ADAPTIVE_NOISE_CONFIRM_WINDOW_MS = 10_500L
    private const val ADAPTIVE_SLIDER_TAG = "applepods_adaptive_noise_slider_mock"
    private val adaptiveViews = WeakHashMap<Any, View>()
    private val adaptiveSliderViews = WeakHashMap<Any, View>()
    private val recentAdaptiveRequests = ConcurrentHashMap<String, Long>()

    /**
     * The OEM volume drawable is a one-sided fill and the Miuix balanced drawable applies the
     * system accent color. Keep the native SeekBar/thumb interaction, but draw this one track
     * ourselves so the bar reaches both card edges and remains the translucent white used by the
     * previous control-center version.
     */
    private class AdaptiveCenteredSeekBar(context: android.content.Context) : SeekBar(context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var thumbInsetPx = 0

        init {
            // The drawable is intentionally transparent; onDraw below owns only the track.
            progressDrawable = ColorDrawable(Color.TRANSPARENT)
            // Exact colors from the OEM XML: miplay_volume_bar_bg_color (#1AFFFFFF) and
            // miplay_volume_bar_primary_color (#EBFFFFFF).
            trackPaint.color = 0x1AFFFFFF
            // Adaptive strength is a centered control, not a volume fill; keep the selected
            // half at roughly 50% opacity instead of a solid white block.
            activePaint.color = 0x80FFFFFF.toInt()
        }

        fun setThumbInset(inset: Int) {
            thumbInsetPx = inset.coerceAtLeast(0)
            setPadding(thumbInsetPx, paddingTop, thumbInsetPx, paddingBottom)
            // Padding already insets both ends symmetrically. thumbOffset is an extra shift and
            // would make the two endpoints asymmetric, so keep it at zero.
            thumbOffset = 0
        }

        override fun onDraw(canvas: Canvas) {
            val width = width.toFloat()
            if (width > 0f) {
                val density = resources.displayMetrics.density
                // Match the previous control-center volume bar: a broad, softly translucent
                // capsule rather than the thin platform SeekBar rail.
                // The OEM volume row is 20dp high; use the same capsule height.
                val trackHeight = (20f * density).coerceAtLeast(2f)
                val top = (height - trackHeight) / 2f
                val radius = trackHeight / 2f
                val center = width / 2f
                val fraction = if (max > 0) progress.toFloat() / max else 0.5f
                val thumbTravel = (width - 2f * thumbInsetPx).coerceAtLeast(0f)
                val thumbCenter = thumbInsetPx + thumbTravel * fraction
                canvas.drawRoundRect(0f, top, width, top + trackHeight, radius, radius, trackPaint)
                if (fraction < 0.5f) {
                    canvas.drawRoundRect(thumbCenter, top, center, top + trackHeight, radius, radius, activePaint)
                } else if (fraction > 0.5f) {
                    canvas.drawRoundRect(center, top, thumbCenter, top + trackHeight, radius, radius, activePaint)
                }
            }
            // Draw the OEM thumb on top of the custom track.
            super.onDraw(canvas)
        }
    }

    override fun onHook() {
        val hyperOs4 = runCatching { installHyperOs4Hooks() }
        if (hyperOs4.isSuccess) {
            Log.i(TAG, "HyperOS 4 native ANC config injection installed")
            return
        }
        Log.e(TAG, "HyperOS 4 ANC implementation unavailable; trying HyperOS 3", hyperOs4.exceptionOrNull()!!)

        runCatching { installHyperOs3Hooks() }
            .onSuccess { Log.i(TAG, "HyperOS 3 native control-center ANC extension installed") }
            .onFailure { Log.e(TAG, "native control-center ANC extension unavailable", it) }
    }

    /**
     * HyperOS 4 builds the three ANC buttons from a List<AncModeConfig>. Injecting into Kotlin's
     * list factory lets the OEM loop create, weight and register the fourth item itself.
     */
    private fun installHyperOs4Hooks() {
        val controllerClass = findClass("com.miui.circulateplus.world.headset.r")
        val configClass = findClass("com.miui.circulateplus.world.headset.r\$a")
        val detailClass = findClass("com.miui.circulateplus.world.headset.HeadSetsDetail")
        val listFactoryClass = findClass("tl.s")

        hookBefore(listFactoryClass.getDeclaredMethod("o", Array<Any>::class.java)) {
            val configs = args.firstOrNull() as? Array<*> ?: return@hookBefore
            if (configs.size != 3 || configs.any { it?.javaClass != configClass }) return@hookBefore
            val transparency = configs.firstOrNull { configMode(it) == 1 } ?: return@hookBefore
            val adaptive = newAdaptiveConfig(configClass, transparency)
            result = configs.filterNotNull() + adaptive
            Log.i(TAG, "adaptive AncModeConfig appended to HyperOS 4 native list")
        }

        hookAfter(controllerClass.getDeclaredConstructor(detailClass)) {
            instance?.let(::finishHyperOs4Controller)
        }

        // Keep the OEM click pipeline (including feedback and analytics), replacing only its final
        // unsupported mode-3 command with the AirPods AAP adaptive command.
        hookBefore(controllerClass.getDeclaredMethod(
            "A", View::class.java, Int::class.javaPrimitiveType, String::class.java,
        )) {
            val controller = instance ?: return@hookBefore
            val mode = args.getOrNull(1) as? Int ?: return@hookBefore
            val context = (args.firstOrNull() as? View)?.context ?: return@hookBefore
            if (mode != CONTROL_CENTER_ADAPTIVE) {
                clearRecentAdaptive(context)
                hideAdaptiveSlider(controller)
                return@hookBefore
            }
            sendAdaptive(context, controller)
            result = null
        }

        hookBefore(controllerClass.getDeclaredMethod("M", Int::class.javaPrimitiveType)) {
            val controller = instance ?: return@hookBefore
            val mode = args.firstOrNull() as? Int ?: return@hookBefore
            if (!isAdaptiveState(controller, mode)) {
                hideAdaptiveSlider(controller)
                return@hookBefore
            }
            renderHyperOs4Adaptive(controller)
            result = null
        }
    }

    private fun newAdaptiveConfig(configClass: Class<*>, transparency: Any): Any {
        val iconRes = callMethod(transparency, "a") as Int
        val labelRes = callMethod(transparency, "b") as Int
        return configClass.getDeclaredConstructor(
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
        ).apply { isAccessible = true }
            .newInstance(CONTROL_CENTER_ADAPTIVE, iconRes, labelRes, "自适应")
    }

    private fun configMode(config: Any?): Int? =
        config?.let { runCatching { callMethod(it, "c") as? Int }.getOrNull() }

    private fun finishHyperOs4Controller(controller: Any) {
        if (adaptiveViews.containsKey(controller)) return
        val card = getObjectField(controller, "e") as? ViewGroup ?: return

        // Normally the list hook has already made the OEM constructor create item 4. The fallback
        // still calls the OEM factory; it never inflates or attaches a custom view itself.
        val adaptive = card.getChildAt(CONTROL_CENTER_ADAPTIVE) ?: run {
            val configs = getObjectField(controller, "h") as? List<*> ?: return
            val transparency = configs.firstOrNull { configMode(it) == 1 } ?: return
            val iconRes = callMethod(transparency, "a") as Int
            val labelRes = callMethod(transparency, "b") as Int
            val nativeItem = callMethod(card, "a", iconRes, labelRes) as? View ?: return
            nativeItem.setOnClickListener { sendAdaptive(it.context, controller) }
            Log.i(TAG, "adaptive item created through HyperOS 4 native item factory fallback")
            nativeItem
        }

        val textId = adaptive.resources.getIdentifier("tools_text", "id", adaptive.context.packageName)
        (adaptive.findViewById<TextView>(textId))?.apply {
            text = AdaptiveModeItem.localized(context, "自适应", "Adaptive")
            contentDescription = text
        }
        adaptiveViews[controller] = adaptive
        ensureAdaptiveSlider(controller, getObjectField(controller, "a"))
        Log.i(TAG, "HyperOS 4 native adaptive ANC item ready; itemCount=${card.childCount}")
    }

    private fun sendAdaptive(context: android.content.Context, controller: Any) {
        val device = HyperOsAirPodsRepository.connectedAirPods(context) ?: return
        // CAPod records the requested mode before transport returns; a response may arrive on a
        // different thread immediately after the command is queued.
        AdaptiveStateTracker.requested(device.address)
        if (HyperOsAirPodsRepository.sendAncMode(
                context, device, ApplePodsAapProtocol.MODE_ADAPTIVE,
            )
        ) {
            recentAdaptiveRequests[device.address] = System.currentTimeMillis()
            renderHyperOs4Adaptive(controller)
        } else {
            AdaptiveStateTracker.disconnected(device.address)
        }
    }

    private fun isAdaptiveState(controller: Any, mode: Int): Boolean {
        if (mode == CONTROL_CENTER_ADAPTIVE || mode == ApplePodsAapProtocol.MODE_ADAPTIVE) return true
        if (mode != -1) return false
        val context = adaptiveViews[controller]?.context ?: return false
        val device = HyperOsAirPodsRepository.connectedAirPods(context) ?: return false
        val reported = HyperOsAirPodsRepository.getState(
            context, device, HyperOsAirPodsRepository.KEY_ANC,
        )?.trimStart('0')
        if (reported == ApplePodsAapProtocol.MODE_ADAPTIVE.toString()) return true
        val requestedAt = recentAdaptiveRequests[device.address]
        if (requestedAt != null && System.currentTimeMillis() - requestedAt <= ADAPTIVE_GRACE_MS) {
            return true
        }
        if (reported in setOf("1", "2", "3")) recentAdaptiveRequests.remove(device.address)
        return false
    }

    private fun clearRecentAdaptive(context: android.content.Context) {
        HyperOsAirPodsRepository.connectedAirPods(context)?.address?.let(recentAdaptiveRequests::remove)
    }

    private fun renderHyperOs4Adaptive(controller: Any) {
        val adaptive = adaptiveViews[controller] ?: return
        adaptiveSliderViews[controller]?.visibility = View.VISIBLE
        val card = adaptive.parent ?: return
        runCatching { callMethod(controller, "J", true) }
        val detail = getObjectField(controller, "a")
        runCatching { callMethod(detail, "setModeVisible", true) }
        runCatching { callMethod(controller, "K") }
        callMethod(card, "b", CONTROL_CENTER_ADAPTIVE)
        setField(controller, "b", CONTROL_CENTER_ADAPTIVE)
        Log.i(TAG, "HyperOS 4 native adaptive ANC state selected")
    }

    private fun installHyperOs3Hooks() {
        val controllerClass = findClass("com.miui.circulateplus.world.headset.j")
        val detailClass = findClass("com.miui.circulateplus.world.headset.HeadSetsDetail")
        hookAfter(controllerClass.getDeclaredConstructor(detailClass)) {
            instance?.let(::extendHyperOs3Controller)
        }
        hookBefore(controllerClass.getDeclaredMethod("z", Int::class.javaPrimitiveType)) {
            val controller = instance ?: return@hookBefore
            val mode = args.firstOrNull() as? Int ?: return@hookBefore
            if (!isAdaptiveState(controller, mode)) return@hookBefore
            renderHyperOs3Adaptive(controller)
            result = null
        }
    }

    private fun extendHyperOs3Controller(controller: Any) {
        if (adaptiveViews.containsKey(controller)) return
        val nativeViews = getObjectField(controller, "i") as? Array<*> ?: return
        val reference = nativeViews.lastOrNull() as? View ?: return
        val parent = reference.parent as? ViewGroup ?: return
        val context = reference.context
        val layoutId = context.resources.getIdentifier(
            "circulate_headset_detail_anc_item", "layout", context.packageName,
        )
        if (layoutId == 0) return

        val adaptive = LayoutInflater.from(context).inflate(layoutId, parent, false)
        adaptive.layoutParams = copyEqualLayoutParams(reference.layoutParams)
        parent.addView(adaptive)

        val iconId = context.resources.getIdentifier("anc_icon", "id", context.packageName)
        val titleId = context.resources.getIdentifier("anc_title", "id", context.packageName)
        val drawables = getObjectField(controller, "l") as Array<*>
        adaptive.findViewById<ImageView>(iconId)?.setImageResource(drawables[0] as Int)
        adaptive.findViewById<TextView>(titleId)?.text =
            AdaptiveModeItem.localized(context, "自适应", "Adaptive")

        adaptive.setOnClickListener {
            val device = HyperOsAirPodsRepository.connectedAirPods(context) ?: return@setOnClickListener
            if (HyperOsAirPodsRepository.sendAncMode(
                    context, device, ApplePodsAapProtocol.MODE_ADAPTIVE,
                )
            ) {
                controller.javaClass.getDeclaredMethod("z", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                    .invoke(controller, CONTROL_CENTER_ADAPTIVE)
            }
        }

        setField(controller, "i", (nativeViews.filterIsInstance<View>() + adaptive).toTypedArray())
        setField(controller, "l", append(getObjectField(controller, "l") as Array<*>, drawables[0]))
        val titles = getObjectField(controller, "m") as Array<*>
        setField(controller, "m", append(titles, titles[0]))
        setField(controller, "n", append(getObjectField(controller, "n") as Array<*>, CONTROL_CENTER_ADAPTIVE))
        setField(controller, "o", append(getObjectField(controller, "o") as Array<*>, "自适应"))
        adaptiveViews[controller] = adaptive
        ensureAdaptiveSlider(controller, getObjectField(controller, "a"))
        normalizeWeights(parent)
        Log.i(TAG, "adaptive mode inserted into native control-center arrays")
    }

    private fun renderHyperOs3Adaptive(controller: Any) {
        val adaptive = adaptiveViews[controller] ?: return
        adaptiveSliderViews[controller]?.visibility = View.VISIBLE
        runCatching { callMethod(controller, "y", true) }
        val detail = getObjectField(controller, "a")
        runCatching { callMethod(detail, "setModeVisible", true) }
        (getObjectField(controller, "i") as? Array<*>)?.filterIsInstance<View>()?.forEach {
            it.isSelected = it === adaptive
        }
        setField(controller, "b", CONTROL_CENTER_ADAPTIVE)
        Log.i(TAG, "control-center adaptive state selected")
    }

    /** Reuses the exact earphone volume_bar XML as a visual-only adaptive-noise mock. */
    private fun ensureAdaptiveSlider(controller: Any, detail: Any?) {
        if (adaptiveSliderViews.containsKey(controller)) return
        val root = detail as? ViewGroup ?: return
        val context = root.context
        val ancId = context.resources.getIdentifier("anc_select_card", "id", context.packageName)
        val anc = if (ancId != 0) root.findViewById<View>(ancId) else null
        val parent = anc?.parent as? ViewGroup ?: return
        val templateId = context.resources.getIdentifier(
            "circulate_headset_detail_root_layout", "layout", context.packageName,
        )
        val volumeId = context.resources.getIdentifier("volume_bar", "id", context.packageName)
        if (templateId == 0 || volumeId == 0) {
            Log.e(
                TAG,
                "adaptive slider mock skipped: OEM earphone volume_bar XML missing",
                IllegalStateException("circulate_headset_detail_root_layout/volume_bar"),
            )
            return
        }
        val slider = runCatching {
            // The volume row is an inline block in the root XML rather than a standalone
            // layout. Inflate the OEM root once, detach only its volume_bar block, and reuse
            // that exact ViewGroup so the drawable, style and padding remain OEM-identical.
            val templateRoot = LayoutInflater.from(context).inflate(templateId, null, false) as ViewGroup
            val volume = templateRoot.findViewById<View>(volumeId) ?: error("volume_bar not found")
            (volume.parent as? ViewGroup)?.removeView(volume)
            applyOemVolumeCardStyle(volume)
            volume
        }.getOrElse {
            Log.e(TAG, "adaptive slider mock inflate failed", it)
            return
        }
        slider.tag = ADAPTIVE_SLIDER_TAG
        slider.visibility = View.GONE
        // The OEM 91dp minimum reserves room for its title + track. Our mock removes the title,
        // so keeping that minimum would leave an oversized empty bottom area.
        slider.minimumHeight = 0
        slider.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dimension(context, "miplay_detail_header_volume_bar_margin_top", 12)
            bottomMargin = -2
        }

        val title = slider.findViewById<TextView>(resourceId(context, "volume_title"))
        title?.text = "自适应强度"
        slider.findViewById<View>(resourceId(context, "seekbar_title_container"))?.visibility = View.GONE

        val seekBar = replaceWithCenteredSeekBar(slider)
        val device = HyperOsAirPodsRepository.connectedAirPods(context)
        val initialLevel = device?.let {
            HyperOsAirPodsRepository.getState(
                context,
                it,
                HyperOsAirPodsRepository.KEY_ADAPTIVE_AUDIO_NOISE,
            )?.toIntOrNull()
        }?.coerceIn(0, 100) ?: 50
        var dragging = false
        var lastSentLevel = initialLevel
        var pendingLevel: Int? = null
        var pendingUntil = 0L
        seekBar?.apply {
            max = 100
            progress = initialLevel
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) = Unit
                override fun onStartTrackingTouch(bar: SeekBar) {
                    dragging = true
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    dragging = false
                    val level = bar.progress.coerceIn(0, 100)
                    if (level != lastSentLevel) {
                        device?.let {
                            if (HyperOsAirPodsRepository.sendAdaptiveAudioNoise(context, it, level)) {
                                lastSentLevel = level
                                pendingLevel = level
                                pendingUntil = System.currentTimeMillis() + ADAPTIVE_NOISE_CONFIRM_WINDOW_MS
                                verifyAdaptiveNoiseWrite(
                                    context,
                                    it,
                                    bar,
                                    level,
                                    onConfirmed = {
                                        pendingLevel = null
                                        pendingUntil = 0L
                                    },
                                    onRejected = {
                                        pendingLevel = null
                                        pendingUntil = 0L
                                    },
                                )
                            }
                        }
                    }
                }
            })
        }
        device?.let { connectedDevice ->
            HyperOsAirPodsRepository.observe(slider, connectedDevice) { key, value ->
                if (key != HyperOsAirPodsRepository.KEY_ADAPTIVE_AUDIO_NOISE || dragging) return@observe
                value.toIntOrNull()?.coerceIn(0, 100)?.let { level ->
                    val pending = pendingLevel
                    if (pending != null) {
                        if (level == pending) {
                            pendingLevel = null
                            pendingUntil = 0L
                        } else if (System.currentTimeMillis() < pendingUntil) {
                            // A stale notification from before the drag must not overwrite the
                            // user's new value while the AAP write is still in flight.
                            return@observe
                        } else {
                            pendingLevel = null
                        }
                    }
                    seekBar?.progress = level
                    lastSentLevel = level
                }
            }
        }

        // Keep the native track free of icons: its progress fill is intentionally light/white
        // in HyperOS. Put the transparency and NC icons below the track instead, with a small
        // translucent center dot for the neutral point.
        slider.findViewById<ImageView>(resourceId(context, "volume_icon"))?.visibility = View.GONE
        val transparencyIcon = ImageView(context).also { icon ->
            icon.setImageResource(
                configIcon(controller, 1).takeIf { it != 0 }
                    ?: firstResource(context, "headset_transparency_selector", "transparency_selector"),
            )
            icon.contentDescription = "通透"
        }
        val noiseIcon = ImageView(context).apply {
            setImageResource(
                configIcon(controller, 0).takeIf { it != 0 }
                    ?: firstResource(context, "headset_noise_cancel_selector", "noise_cancel_selector"),
            )
            contentDescription = "降噪"
        }
        // The requested adaptive scale is symmetric around neutral: icons at -50/+50 and a
        // translucent dot at the center instead of rendering a literal "0".
        val scale = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(FrameLayout(context).apply {
                addView(transparencyIcon, FrameLayout.LayoutParams(
                    dimension(context, "miplay_detail_header_volume_bar_icon_width", 20),
                    dimension(context, "miplay_detail_header_volume_bar_icon_height", 20),
                ).apply { gravity = android.view.Gravity.START })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x80FFFFFF.toInt())
                }
            }, LinearLayout.LayoutParams(
                (dimension(context, "miplay_detail_header_volume_bar_icon_size", 12) / 2).coerceAtLeast(4),
                (dimension(context, "miplay_detail_header_volume_bar_icon_size", 12) / 2).coerceAtLeast(4),
            ).apply { gravity = android.view.Gravity.CENTER })
            addView(FrameLayout(context).apply {
                addView(noiseIcon, FrameLayout.LayoutParams(
                    dimension(context, "miplay_detail_header_volume_bar_icon_width", 20),
                    dimension(context, "miplay_detail_header_volume_bar_icon_height", 20),
                ).apply { gravity = android.view.Gravity.END })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        (slider as? RelativeLayout)?.addView(scale, RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            addRule(RelativeLayout.BELOW, resourceId(context, "volume_row_sliderr"))
            topMargin = dimension(context, "miplay_detail_header_volume_bar_icon_margin_top", 4)
        })

        val insertAt = parent.indexOfChild(anc) + 1
        parent.addView(slider, insertAt.coerceAtLeast(0))
        adaptiveSliderViews[controller] = slider
        Log.i(TAG, "adaptive slider mock inserted below native ANC card")
    }

    /** CAPod-style delayed confirmation and one retry for the 0x2E write. */
    private fun verifyAdaptiveNoiseWrite(
        context: android.content.Context,
        device: android.bluetooth.BluetoothDevice,
        seekBar: SeekBar,
        expected: Int,
        onConfirmed: () -> Unit,
        onRejected: () -> Unit,
    ) {
        Handler(Looper.getMainLooper()).postDelayed({
            val reported = HyperOsAirPodsRepository.getState(
                context,
                device,
                HyperOsAirPodsRepository.KEY_ADAPTIVE_AUDIO_NOISE,
            )?.toIntOrNull()
            if (reported == expected) {
                onConfirmed()
                return@postDelayed
            }
            if (HyperOsAirPodsRepository.getState(
                    context,
                    device,
                    HyperOsAirPodsRepository.KEY_ANC,
                )?.trimStart('0') != ApplePodsAapProtocol.MODE_ADAPTIVE.toString()
            ) return@postDelayed
            if (!HyperOsAirPodsRepository.sendAdaptiveAudioNoise(context, device, expected)) {
                onRejected()
                return@postDelayed
            }
            Handler(Looper.getMainLooper()).postDelayed({
                val retried = HyperOsAirPodsRepository.getState(
                    context,
                    device,
                    HyperOsAirPodsRepository.KEY_ADAPTIVE_AUDIO_NOISE,
                )?.toIntOrNull()
                if (retried == expected) {
                    onConfirmed()
                } else {
                    onRejected()
                }
            }, ADAPTIVE_NOISE_CONFIRM_RETRY_DELAY_MS)
        }, ADAPTIVE_NOISE_CONFIRM_DELAY_MS)
    }

    private fun hideAdaptiveSlider(controller: Any) {
        adaptiveSliderViews[controller]?.visibility = View.GONE
    }

    /** v1 applies this during its constructor; do the same for the detached XML template. */
    private fun applyOemVolumeCardStyle(volume: View) {
        runCatching {
            findClass("com.milink.util.MaterialUtils")
                .getDeclaredMethod("j", View::class.java, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(null, volume, 2)
        }.onFailure {
            Log.d(TAG, "OEM volume card style helper unavailable; keeping XML background")
        }
    }

    private fun resourceId(context: android.content.Context, name: String): Int =
        context.resources.getIdentifier(name, "id", context.packageName)

    private fun configIcon(controller: Any, mode: Int): Int = runCatching {
        (getObjectField(controller, "h") as? List<*>)
            ?.firstOrNull { configMode(it) == mode }
            ?.let { callMethod(it, "a") as? Int }
            ?: 0
    }.getOrDefault(0)

    /** Replace the volume-only left-to-right MiPlay bar with the centered adaptive SeekBar. */
    private fun replaceWithCenteredSeekBar(slider: View): SeekBar? = runCatching {
        val old = slider.findViewById<SeekBar>(resourceId(slider.context, "volume_row_sliderr"))
            ?: return@runCatching null
        val parent = old.parent as? ViewGroup ?: return@runCatching old
        val centered = AdaptiveCenteredSeekBar(slider.context)
        centered.id = old.id
        centered.layoutParams = old.layoutParams
        centered.max = 100
        centered.progress = 50
        val thumbId = slider.context.resources.getIdentifier(
            "miuix_appcompat_default_seekbar_thumb_light",
            "drawable",
            slider.context.packageName,
        )
        if (thumbId != 0) centered.thumb = slider.context.getDrawable(thumbId)
        // No extra endpoint margin anywhere: thumb centers align with the track edges.
        val thumbInset = 0
        centered.setThumbInset(thumbInset)
        parent.clipToPadding = false
        parent.clipChildren = false
        val index = parent.indexOfChild(old)
        parent.removeViewAt(index)
        parent.addView(centered, index)
        centered
    }.onFailure {
        Log.d(TAG, "Miuix balanced SeekBar unavailable; using OEM volume SeekBar: ${it.message}")
    }.getOrNull()

    private fun firstResource(context: android.content.Context, vararg names: String): Int =
        names.asSequence()
            .map { context.resources.getIdentifier(it, "drawable", context.packageName) }
            .firstOrNull { it != 0 }
            ?: android.R.drawable.ic_menu_view

    private fun dimension(context: android.content.Context, name: String, fallbackDp: Int): Int =
        context.resources.getIdentifier(name, "dimen", context.packageName).let { id ->
            if (id == 0) (fallbackDp * context.resources.displayMetrics.density).toInt()
            else context.resources.getDimensionPixelSize(id)
        }

    private fun copyEqualLayoutParams(source: ViewGroup.LayoutParams): ViewGroup.LayoutParams =
        if (source is LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams(0, source.height, 1f).apply {
                setMargins(source.leftMargin, source.topMargin, source.rightMargin, source.bottomMargin)
            }
        } else {
            ViewGroup.LayoutParams(source.width, source.height)
        }

    private fun normalizeWeights(parent: ViewGroup) {
        if (parent !is LinearLayout) return
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            val old = child.layoutParams
            child.layoutParams = copyEqualLayoutParams(old)
        }
    }

    private fun append(source: Array<*>, value: Any?): Any {
        val result = java.lang.reflect.Array.newInstance(
            source.javaClass.componentType,
            source.size + 1,
        )
        for (index in source.indices) java.lang.reflect.Array.set(result, index, source[index])
        java.lang.reflect.Array.set(result, source.size, value)
        return result
    }

    private fun setField(instance: Any, name: String, value: Any?) {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val current = type
            runCatching {
                current.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
                return
            }
            type = type.superclass
        }
        throw NoSuchFieldException(name)
    }
}
