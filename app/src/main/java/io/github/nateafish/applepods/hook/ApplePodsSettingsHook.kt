package io.github.nateafish.applepods.hook

import android.bluetooth.BluetoothDevice
import android.app.Activity
import android.content.Context
import android.content.ContentValues
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import java.lang.reflect.Proxy
import java.io.File
import java.util.WeakHashMap
import io.github.nateafish.applepods.protocol.ApplePodsAapProtocol
import io.github.nateafish.applepods.protocol.HyperOsAirPodsRepository

/** Lightly augments HyperOS' existing AirPods detail page; it does not replace the page. */
object ApplePodsSettingsHook : HookContext() {
    private const val TAG = "ApplePods-Settings"
    private const val ADAPTIVE_TAG = "applepods_adaptive_mode"
    private const val PREF_CONVERSATION = "applepods_conversation_awareness"
    private const val PREF_SLEEP = "applepods_sleep_detection"
    private const val PREF_FEATURE_CATEGORY = "applepods_airpods_features"
    private const val PREF_PROFILE_CATEGORY = "profile_container"
    private const val ADAPTIVE_SLIDER_TAG = "applepods_settings_adaptive_noise_slider"
    private const val ADAPTIVE_NOISE_CONFIRM_DELAY_MS = 5_000L
    private const val ADAPTIVE_NOISE_CONFIRM_RETRY_DELAY_MS = 5_000L
    private const val ADAPTIVE_NOISE_CONFIRM_WINDOW_MS = 10_500L
    private val adaptiveItems = WeakHashMap<Any, AdaptiveModeItem>()
    private val observedFragments = WeakHashMap<Any, Boolean>()
    private val nativeAncItems = WeakHashMap<Any, NativeAncItem>()
    private val nativeAdaptiveSliders = WeakHashMap<Any, NativeAdaptiveSlider>()
    private val hookedPluginLoaders = WeakHashMap<ClassLoader, Boolean>()
    private var ancControllerHooksInstalled = false
    private var resourceStackLogged = false

    /** MIUI X thumb handling with the centered adaptive-strength track used in Control Center. */
    private class SettingsAdaptiveSeekBar(
        context: Context,
        trackColor: Int,
        activeColor: Int,
    ) : SeekBar(context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = trackColor }
        private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = activeColor }

        init {
            progressDrawable = ColorDrawable(Color.TRANSPARENT)
        }

        fun alignThumbToTrackEdges() {
            setPadding(0, paddingTop, 0, paddingBottom)
            thumbOffset = 0
        }

        override fun onDraw(canvas: Canvas) {
            val width = width.toFloat()
            if (width > 0f) {
                val trackHeight = 20f * resources.displayMetrics.density
                val top = (height - trackHeight) / 2f
                val radius = trackHeight / 2f
                val center = width / 2f
                val fraction = if (max > 0) progress.toFloat() / max else 0.5f
                val thumbCenter = width * fraction
                canvas.drawRoundRect(0f, top, width, top + trackHeight, radius, radius, trackPaint)
                when {
                    fraction < 0.5f -> canvas.drawRoundRect(
                        thumbCenter, top, center, top + trackHeight, radius, radius, activePaint,
                    )
                    fraction > 0.5f -> canvas.drawRoundRect(
                        center, top, thumbCenter, top + trackHeight, radius, radius, activePaint,
                    )
                }
            }
            super.onDraw(canvas)
        }
    }

    override fun onHook() {
        // JavaActivity is the entry point of the Qigsaw split. At Activity.onCreate entry its
        // split ClassLoader already exists, but the AirPods fragment has not started its own
        // lifecycle yet. Install the plugin-local hooks at that exact point.
        hookBefore(Activity::class.java.getDeclaredMethod(
            "onCreate", Bundle::class.java,
        ).apply { isAccessible = true }) {
            val activity = instance as? Activity ?: return@hookBefore
            if (activity.javaClass.name != "plugin.settings.java.JavaActivity") return@hookBefore
            installPluginLifecycleHooks(activity.javaClass.classLoader ?: return@hookBefore)
        }
        runCatching {
            val fragmentClass = findClass("com.android.settings.bluetooth.MiuiHeadsetFragment")
            hookAfter(
                fragmentClass.getDeclaredMethod(
                    "onCreateView",
                    LayoutInflater::class.java,
                    ViewGroup::class.java,
                    Bundle::class.java,
                ).apply { isAccessible = true }
            ) {
                val fragment = instance ?: return@hookAfter
                Log.i(TAG, "onCreateView callback hit: ${fragment.javaClass.name}")
                val root = result as? View ?: getObjectField(fragment, "mRootView") as? View
                    ?: run {
                        Log.i(TAG, "skip: root view unavailable")
                        return@hookAfter
                    }
                val device = getObjectField(fragment, "mDevice") as? BluetoothDevice
                    ?: run {
                        Log.i(TAG, "skip: BluetoothDevice unavailable")
                        return@hookAfter
                    }
                // Reaching MiuiHeadsetFragment with an mDevice is the OEM's own support decision.
                // Its provider-side ConnectL2cap probe is not reliable from the Settings process.
                Log.i(TAG, "injecting device=${device.name} address=${device.address}")
                installFeaturePreferences(fragment, root.context, device)
                HyperOsAirPodsRepository.observe(root, device) { key, value ->
                    when (key) {
                        HyperOsAirPodsRepository.KEY_ANC ->
                            adaptiveItems[fragment]?.setAdaptiveSelected(value == ApplePodsAapProtocol.MODE_ADAPTIVE.toString())
                        HyperOsAirPodsRepository.KEY_CONVERSATION_AWARENESS ->
                            findPreference(fragment, PREF_CONVERSATION)?.let {
                                invokeExact(it, "setChecked", arrayOf(Boolean::class.javaPrimitiveType!!), value == "1")
                            }
                        HyperOsAirPodsRepository.KEY_SLEEP_DETECTION ->
                            findPreference(fragment, PREF_SLEEP)?.let {
                                invokeExact(it, "setChecked", arrayOf(Boolean::class.javaPrimitiveType!!), value == "1")
                            }
                    }
                }
            }
            fragmentClass.declaredMethods.firstOrNull {
                it.name == "onResume" && it.parameterTypes.isEmpty()
            }?.apply { isAccessible = true }?.let { method ->
                hookAfter(method) {
                    val fragment = instance ?: return@hookAfter
                    Log.i(TAG, "onResume callback hit: ${fragment.javaClass.name}")
                    injectFromFragment(fragment)
                }
            }
            fragmentClass.declaredMethods.firstOrNull {
                it.name == "onServiceConnected" && it.parameterTypes.isEmpty()
            }?.apply { isAccessible = true }?.let { method ->
                hookAfter(method) {
                    val fragment = instance ?: return@hookAfter
                    Log.i(TAG, "onServiceConnected callback hit: ${fragment.javaClass.name}")
                    injectFromFragment(fragment)
                }
            }
            hookAfter(Activity::class.java.getDeclaredMethod("onResume").apply { isAccessible = true }) {
                val activity = instance as? Activity ?: return@hookAfter
                if (activity.javaClass.name != "plugin.settings.java.JavaActivity") return@hookAfter
                Log.i(TAG, "plugin JavaActivity resumed")
                // HyperOS 3 fallback. HyperOS 4 normally finishes through the plugin lifecycle
                // hooks installed before JavaActivity.onCreate.
                injectFromPluginActivity(activity)
            }
            Log.i(TAG, "MiuiHeadsetFragment light injection installed")
        }.onFailure { Log.e(TAG, "MiuiHeadsetFragment hook unavailable", it) }
    }

    private fun installPluginLifecycleHooks(loader: ClassLoader) {
        synchronized(hookedPluginLoaders) {
            if (hookedPluginLoaders.put(loader, true) == true) return
        }
        runCatching {
            val fragmentClass = Class.forName(
                "plugin.settings.java.airpods.MiuiAirpodsFragment", false, loader,
            )
            hookAfter(fragmentClass.getDeclaredMethod("onCreate", Bundle::class.java)) {
                val fragment = instance ?: return@hookAfter
                val activity = callMethod(fragment, "getActivity") as? Activity ?: return@hookAfter
                val device = getObjectField(fragment, "mDevice") as? BluetoothDevice ?: return@hookAfter
                installFeaturePreferences(fragment, activity, device)
                Log.i(TAG, "Qigsaw preference injection completed during fragment onCreate")
            }
            val controllerClass = Class.forName(
                "plugin.settings.java.airpods.AncController", false, loader,
            )
            val initAncView = controllerClass.declaredMethods.firstOrNull {
                it.parameterTypes.contentEquals(arrayOf(View::class.java)) &&
                    it.name.contains("lambda") && it.name.contains("new")
            } ?: throw NoSuchMethodException("AncController native initView callback")
            initAncView.isAccessible = true
            hookAfter(initAncView) {
                val controller = instance ?: return@hookAfter
                val activity = getObjectField(controller, "mActivity") as? Activity ?: return@hookAfter
                val fragment = findHeadsetFragment(activity) ?: return@hookAfter
                val root = args.firstOrNull() as? View ?: return@hookAfter
                val device = getObjectField(fragment, "mDevice") as? BluetoothDevice ?: return@hookAfter
                installNativeAncExtension(fragment, root)
                ensureLiveState(fragment, root, device)
                Log.i(TAG, "Qigsaw native ANC injection completed after controller initView")
            }
            Log.i(TAG, "Qigsaw AirPods fragment lifecycle hooks installed")
        }.onFailure {
            synchronized(hookedPluginLoaders) { hookedPluginLoaders.remove(loader) }
            Log.e(TAG, "Qigsaw AirPods fragment lifecycle hook unavailable", it)
        }
    }

    private fun injectFromPluginActivity(activity: Activity) {
        val extras = activity.intent?.extras
        val device = findBluetoothDevice(extras)
            ?: HyperOsAirPodsRepository.connectedAirPods(activity)
            ?: return
        val root = activity.window.decorView
        val fragment = findHeadsetFragment(activity) ?: return
        logOemResourceStack(root)
        installNativeAncExtension(fragment, root)
        installFeaturePreferences(fragment, root.context, device)
        ensureLiveState(fragment, root, device)
    }

    private fun installNativeAncExtension(fragment: Any, root: View) {
        val controller = runCatching { getObjectField(fragment, "mAncController") }.getOrNull() ?: run {
            Log.i(TAG, "native ANC skipped: mAncController is null")
            return
        }
        installAncControllerHooks(controller.javaClass)
        if (nativeAncItems.containsKey(controller)) return
        val transparent = getObjectField(controller, "mAncTransparent") as? View ?: run {
            Log.i(TAG, "native ANC skipped: mAncTransparent is null")
            return
        }
        val parent = transparent.parent as? LinearLayout ?: run {
            Log.i(TAG, "native ANC skipped: parent=${transparent.parent?.javaClass?.name}")
            return
        }
        val layoutId = pluginResource(fragment, root, "layout", "anc_transparent_basic")
        if (layoutId == 0) {
            Log.i(TAG, "native ANC skipped: anc_transparent_basic resource missing")
            return
        }
        val wrapper = LinearLayout(root.context).apply {
            gravity = (transparent as? LinearLayout)?.gravity ?: android.view.Gravity.CENTER
            tag = ADAPTIVE_TAG
        }
        val content = LayoutInflater.from(root.context).inflate(layoutId, wrapper, false)
        wrapper.addView(content)
        val old = transparent.layoutParams
        wrapper.layoutParams = LinearLayout.LayoutParams(0, old.height, 1f).apply {
            if (old is ViewGroup.MarginLayoutParams) {
                setMargins(old.leftMargin, old.topMargin, old.rightMargin, old.bottomMargin)
            }
        }
        normalizeWeights(parent)
        parent.addView(wrapper)
        val imageId = pluginResource(fragment, root, "id", "transparentAncImage")
        val textId = pluginResource(fragment, root, "id", "transparentAncText")
        val image = content.findViewById<ImageView>(imageId)
            ?: findDescendant(content, ImageView::class.java)
        val text = content.findViewById<android.widget.TextView>(textId)
            ?: findDescendant(content, android.widget.TextView::class.java)
        text?.text = AdaptiveModeItem.localized(root.context, "自适应", "Adaptive")
        val item = NativeAncItem(
            wrapper,
            image,
            text,
            pluginResource(fragment, root, "drawable", "transparent_on"),
            pluginResource(fragment, root, "drawable", "transparent_off"),
            pluginResource(fragment, root, "color", "anc_text_color"),
            pluginResource(fragment, root, "color", "first_text_color"),
        )
        nativeAncItems[controller] = item
        wrapper.setOnClickListener {
            val context = it.context
            val device = runCatching { getObjectField(fragment, "mDevice") as? BluetoothDevice }
                .getOrNull() ?: return@setOnClickListener
            val allowed = runCatching {
                controller.javaClass.getMethod(
                    "checkAncAccess",
                    Context::class.java,
                    BluetoothDevice::class.java,
                    Int::class.javaPrimitiveType,
                ).invoke(controller, context, device, ApplePodsAapProtocol.MODE_ADAPTIVE) as Boolean
            }.getOrDefault(false)
            if (!allowed) return@setOnClickListener
            // The native setAncForUser path also requires JavaActivity's HFP proxy, which is
            // populated after the view is created. Sending through the same OEM Repository used
            // by the rest of the module makes the first tap work while preserving native wear
            // access checks; selection still waits for the real 0x04 echo.
            if (!HyperOsAirPodsRepository.sendAncMode(
                    context, device, ApplePodsAapProtocol.MODE_ADAPTIVE,
                )
            ) showSendFailed(wrapper)
        }
        val current = getObjectField(controller, "mCurrentAnc") as? Int ?: 0
        renderNativeAnc(item, current == ApplePodsAapProtocol.MODE_ADAPTIVE)
        installNativeAdaptiveSlider(fragment, root, controller, parent, current)
        Log.i(TAG, "adaptive mode attached to native AncController")
    }

    private fun installAncControllerHooks(controllerClass: Class<*>) {
        if (ancControllerHooksInstalled) return
        synchronized(this) {
            if (ancControllerHooksInstalled) return
            hookBefore(controllerClass.getMethod("toAncCode", String::class.java)) {
                val wire = args.firstOrNull() as? String ?: return@hookBefore
                if (wire.trimStart('0') == "4") result = ApplePodsAapProtocol.MODE_ADAPTIVE
            }
            hookBefore(controllerClass.getMethod("toCommandCode", Int::class.javaPrimitiveType)) {
                if (args.firstOrNull() == ApplePodsAapProtocol.MODE_ADAPTIVE) result = "04"
            }
            val checkAccess = controllerClass.getMethod(
                "checkAncAccess",
                Context::class.java,
                BluetoothDevice::class.java,
                Int::class.javaPrimitiveType,
            )
            hookBefore(checkAccess) {
                if (args.getOrNull(2) != ApplePodsAapProtocol.MODE_ADAPTIVE) return@hookBefore
                // Adaptive has the same wear/access rules as transparency in the OEM controller.
                result = checkAccess.invoke(instance, args[0], args[1], 3) as Boolean
            }
            hookAfter(controllerClass.getMethod(
                "updateView", Context::class.java, Int::class.javaPrimitiveType,
            )) {
                val controller = instance ?: return@hookAfter
                val mode = args.getOrNull(1) as? Int ?: return@hookAfter
                nativeAncItems[controller]?.let {
                    val adaptive = mode == ApplePodsAapProtocol.MODE_ADAPTIVE
                    if (adaptive) {
                        clearNativeAncSelection(controller)
                        runCatching {
                            controllerClass.getDeclaredField("mCurrentAnc").apply {
                                isAccessible = true
                            }.setInt(controller, ApplePodsAapProtocol.MODE_ADAPTIVE)
                        }
                    }
                    renderNativeAnc(it, adaptive)
                    nativeAdaptiveSliders[controller]?.setAdaptiveSelected(adaptive)
                }
            }
            ancControllerHooksInstalled = true
        }
    }

    private fun clearNativeAncSelection(controller: Any) {
        listOf(
            "mAncOn", "mAncOff", "mAncTransparent",
            "mAncOnImage", "mAncOffImage", "mAncTransparentImage",
            "mAncOnText", "mAncOffText", "mAncTransparentText",
        ).forEach { field ->
            (runCatching { getObjectField(controller, field) }.getOrNull() as? View)
                ?.isSelected = false
        }
    }

    private fun renderNativeAnc(item: NativeAncItem, selected: Boolean) {
        item.wrapper.isSelected = selected
        item.image?.isSelected = selected
        item.text?.isSelected = selected
        val drawable = if (selected) item.drawableOn else item.drawableOff
        if (drawable != 0) item.image?.setImageResource(drawable)
        val color = if (selected) item.colorOn else item.colorOff
        if (color != 0) item.text?.setTextColor(item.wrapper.context.getColor(color))
    }

    /** Places adaptive strength under the four mode buttons, inside their existing CardView. */
    private fun installNativeAdaptiveSlider(
        fragment: Any,
        root: View,
        controller: Any,
        buttonRow: LinearLayout,
        currentMode: Int,
    ) {
        if (nativeAdaptiveSliders.containsKey(controller)) return
        val card = buttonRow.parent as? ViewGroup ?: return
        val context = root.context
        val device = getObjectField(fragment, "mDevice") as? BluetoothDevice ?: return

        val vertical = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = ADAPTIVE_SLIDER_TAG
        }
        val cardChildParams = buttonRow.layoutParams
        card.removeView(buttonRow)
        // The original row's 17.49dp top margin belonged inside the CardView. Reusing those
        // params on this new wrapper and then adding row padding would apply that margin twice.
        card.addView(vertical, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        vertical.addView(buttonRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            if (cardChildParams is ViewGroup.MarginLayoutParams) {
                setMargins(
                    cardChildParams.leftMargin,
                    dp(context, 17.5f),
                    cardChildParams.rightMargin,
                    0,
                )
            }
        })

        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val blueName = if (night) {
            "miuix_color_blue_dark_primary_default"
        } else {
            "miuix_color_blue_light_primary_default"
        }
        val blue = pluginColor(fragment, root, blueName, if (night) 0xFF277AF7.toInt() else 0xFF3482FF.toInt())
        val trackName = if (night) {
            "miuix_appcompat_progress_background_dark"
        } else {
            "miuix_appcompat_progress_background_light"
        }
        val gray = pluginColor(
            fragment,
            root,
            trackName,
            if (night) 0x33FFFFFF else 0x1A597098,
        )
        val dotColor = pluginColor(fragment, root, "device_settings_noise_reduction_seekbar_dot", 0xFF8C93B0.toInt())
        val iconColor = if (night) 0xB3FFFFFF.toInt() else 0x99000000.toInt()

        val slider = SettingsAdaptiveSeekBar(context, gray, blue).apply {
            max = 100
            contentDescription = AdaptiveModeItem.localized(context, "自适应强度", "Adaptive strength")
        }
        val initialLevel = HyperOsAirPodsRepository.getState(
            context,
            device,
            HyperOsAirPodsRepository.KEY_ADAPTIVE_AUDIO_NOISE,
        )?.toIntOrNull()?.coerceIn(0, 100) ?: 50
        slider.progress = initialLevel
        val thumbName = if (night) {
            "miuix_appcompat_default_seekbar_thumb_dark"
        } else {
            "miuix_appcompat_default_seekbar_thumb_light"
        }
        pluginResource(fragment, root, "drawable", thumbName).takeIf { it != 0 }?.let { thumbId ->
            context.getDrawable(thumbId)?.let { slider.thumb = withoutMiuixThumbInset(it) }
        }
        slider.alignThumbToTrackEdges()

        val scale = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val transparencyIcon = ImageView(context).apply {
            setImageDrawable(moduleDrawable(
                context,
                io.github.nateafish.applepods.R.drawable.applepods_headset_transparency,
                iconColor,
            ))
            contentDescription = AdaptiveModeItem.localized(context, "通透", "Transparency")
        }
        val noiseIcon = ImageView(context).apply {
            setImageDrawable(moduleDrawable(
                context,
                io.github.nateafish.applepods.R.drawable.applepods_headset_noise_cancel,
                iconColor,
            ))
            contentDescription = AdaptiveModeItem.localized(context, "降噪", "Noise cancellation")
        }
        val iconSize = dp(context, 20)
        scale.addView(FrameLayout(context).apply {
            addView(transparencyIcon, FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = android.view.Gravity.START
            })
        }, LinearLayout.LayoutParams(0, iconSize, 1f))
        scale.addView(View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(dotColor)
            }
        }, LinearLayout.LayoutParams(dp(context, 6), dp(context, 6)).apply {
            gravity = android.view.Gravity.CENTER
        })
        scale.addView(FrameLayout(context).apply {
            addView(noiseIcon, FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = android.view.Gravity.END
            })
        }, LinearLayout.LayoutParams(0, iconSize, 1f))

        val sliderBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(slider, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 32),
            ))
            addView(scale, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, 4) })
        }
        vertical.addView(sliderBlock, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = dp(context, 26)
            rightMargin = dp(context, 26)
            topMargin = 0
            bottomMargin = dp(context, 17.5f)
        })

        var dragging = false
        var lastSentLevel = initialLevel
        var pendingLevel: Int? = null
        var pendingUntil = 0L
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) = Unit

            override fun onStartTrackingTouch(bar: SeekBar) {
                dragging = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                val level = bar.progress.coerceIn(0, 100)
                if (level == lastSentLevel) return
                if (!HyperOsAirPodsRepository.sendAdaptiveAudioNoise(context, device, level)) {
                    showSendFailed(slider)
                    return
                }
                lastSentLevel = level
                pendingLevel = level
                pendingUntil = System.currentTimeMillis() + ADAPTIVE_NOISE_CONFIRM_WINDOW_MS
                verifyAdaptiveNoiseWrite(
                    context,
                    device,
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
        })
        HyperOsAirPodsRepository.observe(sliderBlock, device) { key, value ->
            if (key != HyperOsAirPodsRepository.KEY_ADAPTIVE_AUDIO_NOISE || dragging) return@observe
            value.toIntOrNull()?.coerceIn(0, 100)?.let { level ->
                val pending = pendingLevel
                if (pending != null) {
                    if (level == pending) {
                        pendingLevel = null
                        pendingUntil = 0L
                    } else if (System.currentTimeMillis() < pendingUntil) {
                        return@observe
                    } else {
                        pendingLevel = null
                    }
                }
                slider.progress = level
                lastSentLevel = level
            }
        }

        nativeAdaptiveSliders[controller] = NativeAdaptiveSlider(sliderBlock)
        nativeAdaptiveSliders[controller]?.setAdaptiveSelected(
            currentMode == ApplePodsAapProtocol.MODE_ADAPTIVE,
        )
        Log.i(TAG, "adaptive strength inserted inside native ANC card")
    }

    private fun pluginColor(fragment: Any, root: View, name: String, fallback: Int): Int {
        val id = pluginResource(fragment, root, "color", name)
        return if (id == 0) fallback else runCatching { root.context.getColor(id) }.getOrDefault(fallback)
    }

    private fun moduleDrawable(context: Context, drawableId: Int, tint: Int): Drawable? =
        runCatching {
            context.createPackageContext(
                "io.github.nateafish.applepods",
                Context.CONTEXT_IGNORE_SECURITY,
            ).getDrawable(drawableId)?.mutate()?.apply { setTint(tint) }
        }.onFailure {
            Log.e(TAG, "module adaptive icon unavailable", it)
        }.getOrNull()

    private fun withoutMiuixThumbInset(drawable: Drawable): Drawable =
        ((drawable as? LayerDrawable)?.getDrawable(0) ?: drawable).mutate()

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** Delayed confirmation and one retry for adaptive-noise AAP writes. */
    private fun verifyAdaptiveNoiseWrite(
        context: Context,
        device: BluetoothDevice,
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
                if (retried == expected) onConfirmed() else onRejected()
            }, ADAPTIVE_NOISE_CONFIRM_RETRY_DELAY_MS)
        }, ADAPTIVE_NOISE_CONFIRM_DELAY_MS)
    }

    /** Dynamic Settings plugins use their own relocated resource package. */
    private fun pluginResource(fragment: Any, root: View, type: String, name: String): Int {
        val reflected = runCatching {
            Class.forName(
                "plugin.settings.java.R\$$type",
                false,
                fragment.javaClass.classLoader,
            ).getField(name).getInt(null)
        }.getOrDefault(0)
        if (reflected != 0) return reflected

        val runtimePackage = runCatching {
            val controller = getObjectField(fragment, "mAncController")
            val transparent = controller?.let { getObjectField(it, "mAncTransparent") } as? View
            transparent?.id?.takeIf { it != View.NO_ID }?.let(root.resources::getResourcePackageName)
        }.getOrNull()
        return listOfNotNull(runtimePackage, "plugin.settings.java", root.context.packageName)
            .distinct()
            .firstNotNullOfOrNull { pkg ->
                root.resources.getIdentifier(name, type, pkg).takeIf { it != 0 }
            } ?: 0
    }

    private fun <T : View> findDescendant(root: View, type: Class<T>): T? {
        if (type.isInstance(root)) return type.cast(root)
        val group = root as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            findDescendant(group.getChildAt(index), type)?.let { return it }
        }
        return null
    }

    private data class NativeAncItem(
        val wrapper: View,
        val image: ImageView?,
        val text: android.widget.TextView?,
        val drawableOn: Int,
        val drawableOff: Int,
        val colorOn: Int,
        val colorOff: Int,
    )

    private data class NativeAdaptiveSlider(val container: View) {
        fun setAdaptiveSelected(selected: Boolean) {
            container.visibility = if (selected) View.VISIBLE else View.GONE
        }
    }

    private fun findBluetoothDevice(bundle: Bundle?, depth: Int = 0): BluetoothDevice? {
        if (bundle == null || depth > 2) return null
        for (key in bundle.keySet()) {
            val value = runCatching { bundle.get(key) }.getOrNull()
            when (value) {
                is BluetoothDevice -> return value
                is Bundle -> findBluetoothDevice(value, depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun findHeadsetFragment(activity: Activity): Any? {
        val manager = runCatching { callMethod(activity, "getSupportFragmentManager") }.getOrNull()
            ?: runCatching { callMethod(activity, "getFragmentManager") }.getOrNull()
            ?: return null
        return findHeadsetFragmentInManager(manager, mutableSetOf())
    }

    private fun findHeadsetFragmentInManager(manager: Any, visited: MutableSet<Any>): Any? {
        if (!visited.add(manager)) return null
        val fragments = runCatching { callMethod(manager, "getFragments") as? List<*> }
            .getOrNull().orEmpty()
        for (fragment in fragments.filterNotNull()) {
            if (
                fragment.javaClass.name == "com.android.settings.bluetooth.MiuiHeadsetFragment" ||
                fragment.javaClass.name == "plugin.settings.java.airpods.MiuiAirpodsFragment"
            ) {
                return fragment
            }
            val child = runCatching { callMethod(fragment, "getChildFragmentManager") }.getOrNull()
            if (child != null) findHeadsetFragmentInManager(child, visited)?.let { return it }
        }
        Log.i(TAG, "visible plugin fragments=${fragments.filterNotNull().joinToString { it.javaClass.name }}")
        return null
    }

    private fun injectFromFragment(fragment: Any) {
        val root = getObjectField(fragment, "mRootView") as? View ?: run {
            Log.i(TAG, "resume injection skipped: mRootView unavailable")
            return
        }
        val device = getObjectField(fragment, "mDevice") as? BluetoothDevice ?: run {
            Log.i(TAG, "resume injection skipped: mDevice unavailable")
            return
        }
        Log.i(TAG, "resume injecting device=${device.name} address=${device.address}")
        logOemResourceStack(root)
        installFeaturePreferences(fragment, root.context, device)
        ensureLiveState(fragment, root, device)
    }

    private fun ensureLiveState(fragment: Any, root: View, device: BluetoothDevice) {
        if (observedFragments.put(fragment, true) != true) {
            HyperOsAirPodsRepository.observe(root, device) { key, value ->
                when (key) {
                    HyperOsAirPodsRepository.KEY_ANC ->
                        updateModeSelection(
                            root,
                            adaptiveItems[fragment],
                            value == ApplePodsAapProtocol.MODE_ADAPTIVE.toString(),
                        )
                    HyperOsAirPodsRepository.KEY_CONVERSATION_AWARENESS ->
                        findPreference(fragment, PREF_CONVERSATION)?.let {
                            invokeExact(it, "setChecked", arrayOf(Boolean::class.javaPrimitiveType!!), value == "1")
                        }
                    HyperOsAirPodsRepository.KEY_SLEEP_DETECTION ->
                        findPreference(fragment, PREF_SLEEP)?.let {
                            invokeExact(it, "setChecked", arrayOf(Boolean::class.javaPrimitiveType!!), value == "1")
                        }
                }
            }
        }
    }

    private fun logOemResourceStack(root: View) {
        if (resourceStackLogged) return
        resourceStackLogged = true
        val apkAssets = runCatching {
            root.resources.assets.javaClass.getMethod("getApkAssets").invoke(root.resources.assets) as? Array<*>
        }.getOrNull().orEmpty()
        val paths = apkAssets.joinToString { asset ->
            runCatching { asset!!.javaClass.getMethod("getAssetPath").invoke(asset).toString() }
                .getOrDefault(asset.toString())
        }
        Log.i(TAG, "AirPods resource stack=$paths")
        apkAssets.mapNotNull { asset ->
            runCatching { asset!!.javaClass.getMethod("getAssetPath").invoke(asset).toString() }.getOrNull()
        }.firstOrNull { it.contains("/app_qigsaw/") && it.endsWith(".apk") }?.let { path ->
            exportPlugin(root.context, path)
        }
        root.walkViews().filterIsInstance<ImageView>().forEach { image ->
            val idName = image.id.takeIf { it != View.NO_ID }?.let {
                runCatching { root.resources.getResourceName(it) }.getOrNull()
            }
            val drawableName = image.drawable?.let { drawable ->
                runCatching { drawable.javaClass.name + ":" + drawable.constantState }.getOrNull()
            }
            if (idName != null) Log.i(TAG, "OEM ImageView id=$idName drawable=$drawableName")
        }
    }

    private fun exportPlugin(context: Context, path: String) = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "SettingsAirPodsPlugin.apk")
            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
        File(path).inputStream().use { input ->
            context.contentResolver.openOutputStream(uri)?.use { output -> input.copyTo(output) }
                ?: error("MediaStore output unavailable")
        }
        Log.i(TAG, "exported AirPods plugin to $uri")
    }.onFailure { Log.e(TAG, "AirPods plugin export failed", it) }

    private fun installAdaptiveMode(fragment: Any, root: View, device: BluetoothDevice) {
        if (adaptiveItems[fragment]?.parent != null) return
        val close = root.findNamedView("closeAnc")
            ?: root.findClickableMode(AdaptiveModeItem.localized(root.context, "关闭", "Off"))
            ?: run {
                Log.i(TAG, "adaptive skipped: close ANC view not found")
                return
            }
        val parent = close.parent as? LinearLayout ?: return
        if ((0 until parent.childCount).any { parent.getChildAt(it).tag == ADAPTIVE_TAG }) return

        normalizeWeights(parent)
        val item = AdaptiveModeItem(root.context).apply {
            tag = ADAPTIVE_TAG
            minimumHeight = close.height
            layoutParams = LinearLayout.LayoutParams(
                0,
                close.height.takeIf { it > 0 } ?: close.layoutParams.height,
                1f,
            )
            setOnClickListener {
                val sent = HyperOsAirPodsRepository.sendAncMode(
                    context,
                    device,
                    ApplePodsAapProtocol.MODE_ADAPTIVE,
                )
                // Keep the OEM state machine authoritative. Selection changes only after the
                // AirPods echoes 0x0D=0x04, avoiding a false fourth-state highlight on rejection.
                if (!sent) showSendFailed(root)
            }
        }
        parent.addView(item)
        Log.i(TAG, "adaptive control inserted; modeCount=${parent.childCount}")
        adaptiveItems[fragment] = item
        val current = HyperOsAirPodsRepository.getState(root.context, device, HyperOsAirPodsRepository.KEY_ANC)
        updateModeSelection(root, item, current == ApplePodsAapProtocol.MODE_ADAPTIVE.toString())
    }

    private fun updateModeSelection(root: View, adaptive: AdaptiveModeItem?, selected: Boolean) {
        adaptive?.setAdaptiveSelected(selected)
        if (!selected) return
        listOf(
            "openAnc" to "openanc_off",
            "transparentAnc" to "transparent_off",
            "closeAnc" to "closeanc_off",
        ).forEach { (viewName, offDrawableName) ->
            val mode = root.findNamedView(viewName) ?: return@forEach
            mode.isSelected = false
            mode.isActivated = false
            mode.walkViews().filterIsInstance<ImageView>().forEach { image ->
                val drawableId = root.resources.getIdentifier(
                    offDrawableName,
                    "drawable",
                    root.context.packageName,
                )
                if (drawableId != 0) image.setImageResource(drawableId)
                image.isSelected = false
                image.isActivated = false
            }
        }
    }

    private fun normalizeWeights(parent: LinearLayout) {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            val old = child.layoutParams
            child.layoutParams = LinearLayout.LayoutParams(0, old.height, 1f).apply {
                if (old is ViewGroup.MarginLayoutParams) {
                    setMargins(old.leftMargin, old.topMargin, old.rightMargin, old.bottomMargin)
                }
            }
        }
    }

    private fun installFeaturePreferences(fragment: Any, context: Context, device: BluetoothDevice) {
        val screen = invokeExact(fragment, "getPreferenceScreen", emptyArray()) ?: run {
            Log.i(TAG, "feature switches skipped: preference screen unavailable")
            return
        }
        val preferenceClass = findClass("androidx.preference.Preference")
        val featureCategory = getOrCreateFeatureCategory(screen, preferenceClass)
        movePreferenceIntoCategory(screen, featureCategory, PREF_CONVERSATION, 0)
        movePreferenceIntoCategory(screen, featureCategory, PREF_SLEEP, 1)
        if (invokeExact(screen, "findPreference", arrayOf(CharSequence::class.java), PREF_CONVERSATION) == null) {
            invokeExact(featureCategory, "addPreference", arrayOf(preferenceClass), featurePreference(
                screen,
                device,
                PREF_CONVERSATION,
                HyperOsAirPodsRepository.KEY_CONVERSATION_AWARENESS,
                AdaptiveModeItem.localized(context, "对话感知", "Conversation Awareness"),
                AdaptiveModeItem.localized(
                    context,
                    "检测到你开口说话时自动降低媒体音量并增强人声",
                    "Lowers media and emphasizes voices when you start speaking",
                ),
                0,
            ))
        }
        if (invokeExact(screen, "findPreference", arrayOf(CharSequence::class.java), PREF_SLEEP) == null) {
            invokeExact(featureCategory, "addPreference", arrayOf(preferenceClass), featurePreference(
                screen,
                device,
                PREF_SLEEP,
                HyperOsAirPodsRepository.KEY_SLEEP_DETECTION,
                AdaptiveModeItem.localized(context, "睡眠检测", "Sleep Detection"),
                AdaptiveModeItem.localized(
                    context,
                    "佩戴耳机入睡后，由 AirPods 自动暂停播放",
                    "Lets AirPods pause playback after you fall asleep",
                ),
                1,
            ))
        }
        Log.i(TAG, "conversation and sleep preferences grouped above call audio")
    }

    private fun getOrCreateFeatureCategory(
        screen: Any,
        preferenceClass: Class<*>,
    ): Any {
        invokeExact(screen, "findPreference", arrayOf(CharSequence::class.java), PREF_FEATURE_CATEGORY)?.let {
            configureFeatureCategory(it)
            return it
        }
        val profileCategory = invokeExact(
            screen, "findPreference", arrayOf(CharSequence::class.java), PREF_PROFILE_CATEGORY,
        )
        val profileOrder = profileCategory?.let {
            invokeExact(it, "getOrder", emptyArray()) as? Int
        } ?: (Int.MAX_VALUE - 10)
        val context = invokeExact(screen, "getContext", emptyArray()) as Context
        val categoryClass = findClass("androidx.preference.PreferenceCategory")
        val category = categoryClass.getConstructor(Context::class.java).newInstance(context)
        runCatching {
            // Respect the explicit order relative to the OEM call-audio category instead of
            // appending this late-injected group after all existing preferences.
            invokeExact(screen, "setOrderingAsAdded", arrayOf(Boolean::class.javaPrimitiveType!!), false)
        }
        // airpodslayout.xml uses consecutive top-level orders: switchConfig=2,
        // profile_container=3, findConfig=4, actions=5. Make one deterministic slot before the
        // native profile card instead of relying on when the late profile rows become visible.
        if (profileCategory != null) {
            val count = invokeExact(screen, "getPreferenceCount", emptyArray()) as Int
            for (index in 0 until count) {
                val child = invokeExact(
                    screen, "getPreference", arrayOf(Int::class.javaPrimitiveType!!), index,
                ) ?: continue
                val order = invokeExact(child, "getOrder", emptyArray()) as? Int ?: continue
                if (order >= profileOrder) {
                    invokeExact(child, "setOrder", arrayOf(Int::class.javaPrimitiveType!!), order + 1)
                }
            }
        }
        invokeExact(category, "setKey", arrayOf(String::class.java), PREF_FEATURE_CATEGORY)
        invokeExact(
            category,
            "setOrder",
            arrayOf(Int::class.javaPrimitiveType!!),
            profileOrder,
        )
        configureFeatureCategory(category)
        invokeExact(screen, "addPreference", arrayOf(preferenceClass), category)
        return category
    }

    private fun configureFeatureCategory(category: Any) {
        // Xiaomi's Preference renderer uses this flag for the internal card divider. The
        // fallback calls are harmless on older AndroidX plugin revisions.
        runCatching {
            invokeExact(category, "setOrderingAsAdded", arrayOf(Boolean::class.javaPrimitiveType!!), false)
        }
        runCatching {
            invokeExact(category, "setDividerAllowedInside", arrayOf(Boolean::class.javaPrimitiveType!!), false)
        }
        runCatching {
            invokeExact(category, "setDividerAllowedAbove", arrayOf(Boolean::class.javaPrimitiveType!!), false)
            invokeExact(category, "setDividerAllowedBelow", arrayOf(Boolean::class.javaPrimitiveType!!), false)
        }
    }

    private fun movePreferenceIntoCategory(screen: Any, category: Any, key: String, order: Int) {
        val preference = invokeExact(screen, "findPreference", arrayOf(CharSequence::class.java), key) ?: return
        runCatching {
            invokeExact(preference, "setOrder", arrayOf(Int::class.javaPrimitiveType!!), order)
            invokeExact(preference, "setDividerAllowedAbove", arrayOf(Boolean::class.javaPrimitiveType!!), false)
            invokeExact(preference, "setDividerAllowedBelow", arrayOf(Boolean::class.javaPrimitiveType!!), false)
        }
        val parent = runCatching { invokeExact(preference, "getParent", emptyArray()) }.getOrNull()
        if (parent === category) return
        runCatching {
            (parent ?: screen).let {
                invokeExact(it, "removePreference", arrayOf(findClass("androidx.preference.Preference")), preference)
            }
        }
        invokeExact(category, "addPreference", arrayOf(findClass("androidx.preference.Preference")), preference)
    }

    private fun featurePreference(
        screen: Any,
        device: BluetoothDevice,
        preferenceKey: String,
        repositoryKey: String,
        preferenceTitle: String,
        preferenceSummary: String,
        order: Int?,
    ): Any {
        val context = invokeExact(screen, "getContext", emptyArray()) as Context
        val preferenceClass = findClass("androidx.preference.CheckBoxPreference")
        val preference = preferenceClass.getConstructor(Context::class.java).newInstance(context)
        invokeExact(preference, "setKey", arrayOf(String::class.java), preferenceKey)
        invokeExact(preference, "setTitle", arrayOf(CharSequence::class.java), preferenceTitle)
        invokeExact(preference, "setSummary", arrayOf(CharSequence::class.java), preferenceSummary)
        invokeExact(
            preference,
            "setOrder",
            arrayOf(Int::class.javaPrimitiveType!!),
            order ?: (Int.MAX_VALUE - if (preferenceKey == PREF_CONVERSATION) 1 else 0),
        )
        invokeExact(preference, "setPersistent", arrayOf(Boolean::class.javaPrimitiveType!!), false)
        runCatching {
            invokeExact(preference, "setDividerAllowedAbove", arrayOf(Boolean::class.javaPrimitiveType!!), false)
            invokeExact(preference, "setDividerAllowedBelow", arrayOf(Boolean::class.javaPrimitiveType!!), false)
        }
        invokeExact(
            preference,
            "setChecked",
            arrayOf(Boolean::class.javaPrimitiveType!!),
            HyperOsAirPodsRepository.getBooleanState(context, device, repositoryKey),
        )
        val listenerClass = findClass("androidx.preference.Preference\$OnPreferenceChangeListener")
        val listener = Proxy.newProxyInstance(appClassLoader, arrayOf(listenerClass)) { _, method, args ->
            if (method.name != "onPreferenceChange") return@newProxyInstance null
            val enabled = args?.getOrNull(1) as? Boolean ?: return@newProxyInstance false
            val sent = HyperOsAirPodsRepository.sendBooleanControl(context, device, repositoryKey, enabled)
            if (!sent) showSendFailed(preference)
            sent
        }
        invokeExact(preference, "setOnPreferenceChangeListener", arrayOf(listenerClass), listener)
        return preference
    }

    private fun findPreference(fragment: Any, key: String): Any? =
        invokeExact(fragment, "getPreferenceScreen", emptyArray())?.let {
            invokeExact(it, "findPreference", arrayOf(CharSequence::class.java), key)
        }

    /** Selects the intended overload in HyperOS' own androidx classes. */
    private fun invokeExact(
        receiver: Any,
        name: String,
        parameterTypes: Array<Class<*>>,
        vararg args: Any?,
    ): Any? = receiver.javaClass.getMethod(name, *parameterTypes).invoke(receiver, *args)

    private fun View.findNamedView(name: String): View? {
        val id = resources.getIdentifier(name, "id", context.packageName)
        return if (id == 0) null else findViewById(id)
    }

    private fun View.findClickableMode(label: String): View? {
        if (this is android.widget.TextView && text?.toString() == label) {
            var candidate: View = this
            while (candidate.parent is View && !candidate.isClickable) {
                candidate = candidate.parent as View
            }
            if (candidate.isClickable) return candidate
        }
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                getChildAt(index).findClickableMode(label)?.let { return it }
            }
        }
        return null
    }

    private fun showSendFailed(view: Any) {
        val context = when (view) {
            is View -> view.context
            else -> runCatching {
                invokeExact(view, "getContext", emptyArray()) as? Context
            }.getOrNull() ?: return
        }
        Toast.makeText(
            context,
            AdaptiveModeItem.localized(context, "AirPods 指令发送失败，请确认耳机已连接", "AirPods command failed; check the connection"),
            Toast.LENGTH_SHORT,
        ).show()
    }
}

private fun View.walkViews(): Sequence<View> = sequence {
    yield(this@walkViews)
    if (this@walkViews is ViewGroup) {
        for (index in 0 until childCount) yieldAll(getChildAt(index).walkViews())
    }
}
