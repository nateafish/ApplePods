package io.github.nateafish.applepods.hook

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
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
    private val adaptiveViews = WeakHashMap<Any, View>()
    private val recentAdaptiveRequests = ConcurrentHashMap<String, Long>()

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
                return@hookBefore
            }
            sendAdaptive(context, controller)
            result = null
        }

        hookBefore(controllerClass.getDeclaredMethod("M", Int::class.javaPrimitiveType)) {
            val controller = instance ?: return@hookBefore
            val mode = args.firstOrNull() as? Int ?: return@hookBefore
            if (!isAdaptiveState(controller, mode)) return@hookBefore
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
        normalizeWeights(parent)
        Log.i(TAG, "adaptive mode inserted into native control-center arrays")
    }

    private fun renderHyperOs3Adaptive(controller: Any) {
        val adaptive = adaptiveViews[controller] ?: return
        runCatching { callMethod(controller, "y", true) }
        val detail = getObjectField(controller, "a")
        runCatching { callMethod(detail, "setModeVisible", true) }
        (getObjectField(controller, "i") as? Array<*>)?.filterIsInstance<View>()?.forEach {
            it.isSelected = it === adaptive
        }
        setField(controller, "b", CONTROL_CENTER_ADAPTIVE)
        Log.i(TAG, "control-center adaptive state selected")
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
