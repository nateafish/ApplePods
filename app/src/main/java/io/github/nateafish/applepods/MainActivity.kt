package io.github.nateafish.applepods

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import io.github.libxposed.service.XposedService

class MainActivity : Activity(), ApplePodsApp.Listener {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(Color.rgb(247, 247, 247))

            addView(TextView(context).apply {
                setText(R.string.module_title)
                textSize = 26f
                setTextColor(Color.rgb(25, 25, 28))
            })
            addView(TextView(context).apply {
                setText(R.string.module_summary)
                textSize = 17f
                setTextColor(Color.rgb(68, 68, 74))
                setPadding(0, dp(18), 0, 0)
                setLineSpacing(0f, 1.18f)
            })
            addView(TextView(context).apply {
                status = this
                setText(R.string.status_connecting)
                textSize = 15f
                setTextColor(Color.rgb(188, 92, 35))
                setPadding(0, dp(22), 0, 0)
                setLineSpacing(0f, 1.18f)
            })
        })
    }

    override fun onStart() {
        super.onStart()
        ApplePodsApp.addListener(this)
    }

    override fun onStop() {
        ApplePodsApp.removeListener(this)
        super.onStop()
    }

    override fun onServiceChanged(service: XposedService?) = runOnUiThread {
        if (service == null) {
            status.setText(R.string.status_inactive)
            status.setTextColor(Color.rgb(188, 52, 52))
            return@runOnUiThread
        }

        val required = setOf(
            "com.android.settings",
            "com.xiaomi.bluetooth",
            "com.milink.service",
        )
        val selected = runCatching { service.scope.toSet() }.getOrDefault(emptySet())
        val missing = required - selected
        val running = if (service.apiVersion >= 102) {
            runCatching { service.runningTargets.map { it.processName }.toSet() }.getOrDefault(emptySet())
        } else emptySet()

        status.text = buildString {
            append("${service.frameworkName} ${service.frameworkVersion} · API ${service.apiVersion}\n")
            if (missing.isEmpty()) {
                append("三个必要作用域均已启用。\n")
            } else {
                append("作用域不完整，缺少：${missing.joinToString()}\n")
            }
            if (running.isEmpty()) {
                append("尚未检测到已注入目标进程；请重启手机后重新打开本页。")
            } else {
                append("当前已注入：${running.joinToString()}")
            }
        }
        status.setTextColor(if (missing.isEmpty() && running.isNotEmpty()) {
            Color.rgb(34, 139, 84)
        } else {
            Color.rgb(188, 92, 35)
        })
    }
}
