package io.github.nathanxie.applepods.hook

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView

/** Adaptive-audio button composed only from resources already shipped by HyperOS. */
class AdaptiveModeItem(context: Context) : LinearLayout(context) {
    private val icon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }
    private val label = TextView(context).apply {
        text = localized(context, "自适应", "Adaptive")
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val normalTextColor = resolveColor(context, android.R.attr.textColorPrimary, Color.WHITE)
    private val accentColor = resolveColor(context, android.R.attr.colorAccent, Color.rgb(52, 130, 255))
    private val adaptiveOff = findOemDrawable(
        "transparent_off",
    )
    private val adaptiveOn = findOemDrawable(
        "transparent_on",
    )

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        val iconSize = dp(54)
        addView(icon, LayoutParams(iconSize, iconSize))
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        setAdaptiveSelected(false)
        Log.i(
            "ApplePods-Settings",
            "adaptive OEM icon off=${resourceName(adaptiveOff)} on=${resourceName(adaptiveOn)}",
        )
    }

    fun setAdaptiveSelected(selected: Boolean) {
        isSelected = selected
        icon.imageTintList = null
        icon.setImageResource(if (selected) adaptiveOn else adaptiveOff)
        label.setTextColor(if (selected) accentColor else normalTextColor)
        contentDescription = label.text.toString() + if (selected) ", selected" else ", not selected"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun findOemDrawable(vararg names: String): Int {
        for (name in names) {
            for (packageName in arrayOf(context.packageName, "com.android.settings")) {
                val id = resources.getIdentifier(name, "drawable", packageName)
                if (id != 0) return id
            }
        }
        return android.R.drawable.ic_menu_view
    }

    private fun resourceName(id: Int): String =
        runCatching { resources.getResourceName(id) }.getOrDefault("0x${id.toString(16)}")

    companion object {
        fun localized(context: Context, chinese: String, english: String): String =
            if (context.resources.configuration.locales[0].language.startsWith("zh")) chinese else english

        fun resolveColor(context: Context, attr: Int, fallback: Int): Int {
            val value = TypedValue()
            return if (context.theme.resolveAttribute(attr, value, true)) {
                if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
            } else fallback
        }
    }
}
