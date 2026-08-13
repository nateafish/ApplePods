package io.github.nathanxie.applepods.hook

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.nathanxie.applepods.protocol.ApplePodsAapProtocol
import io.github.nathanxie.applepods.protocol.HyperOsAirPodsRepository
import java.util.WeakHashMap

/** Extends HyperOS' native control-center ANC controller with adaptive audio. */
object ApplePodsMiLinkHook : HookContext() {
    private const val TAG = "ApplePods-MiLink"
    private const val CONTROL_CENTER_ADAPTIVE = 3
    private val adaptiveViews = WeakHashMap<Any, View>()

    override fun onHook() {
        runCatching {
            val controllerClass = findClass("com.miui.circulateplus.world.headset.j")
            val detailClass = findClass("com.miui.circulateplus.world.headset.HeadSetsDetail")
            hookAfter(controllerClass.getDeclaredConstructor(detailClass)) {
                instance?.let(::extendNativeController)
            }
            hookBefore(controllerClass.getDeclaredMethod("z", Int::class.javaPrimitiveType)) {
                val controller = instance ?: return@hookBefore
                val mode = args.firstOrNull() as? Int ?: return@hookBefore
                val repositorySaysAdaptive = if (mode == -1) {
                    val view = adaptiveViews[controller]
                    val context = view?.context
                    val device = context?.let(HyperOsAirPodsRepository::connectedAirPods)
                    context != null && device != null &&
                        HyperOsAirPodsRepository.getState(
                            context, device, HyperOsAirPodsRepository.KEY_ANC,
                        )?.trimStart('0') == ApplePodsAapProtocol.MODE_ADAPTIVE.toString()
                } else false
                if (mode != CONTROL_CENTER_ADAPTIVE &&
                    mode != ApplePodsAapProtocol.MODE_ADAPTIVE &&
                    !repositorySaysAdaptive
                ) return@hookBefore
                renderAdaptive(controller)
                result = null
            }
            Log.i(TAG, "native control-center ANC extension installed")
        }.onFailure { Log.e(TAG, "native control-center ANC extension unavailable", it) }
    }

    private fun extendNativeController(controller: Any) {
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

    private fun renderAdaptive(controller: Any) {
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
