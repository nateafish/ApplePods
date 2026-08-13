package io.github.nathanxie.applepods.protocol

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View

/** Stable content-provider facade exposed by HyperOS' BluetoothExtension package. */
object HyperOsAirPodsRepository {
    private val repositoryUri = Uri.parse(
        "content://com.android.bluetooth.ble.app.headsetdata.provider/airpodsRepository"
    )

    const val KEY_ANC = "air_anc"
    const val KEY_CONVERSATION_AWARENESS = "applepods_conversation_awareness"
    const val KEY_SLEEP_DETECTION = "applepods_sleep_detection"

    private const val EXTRA_KEY = "extra_key"
    private const val EXTRA_VALUE = "extra_value"
    private const val EXTRA_DEVICE = "android.bluetooth.device.extra.DEVICE"
    private const val EXTRA_SUPPORTED = "extra_feature_support"
    private const val EXTRA_COMMAND_STATE = "extra_command_state"

    fun isSupported(context: Context, device: BluetoothDevice): Boolean {
        val result = call(context, "check_feature_support", "ConnectL2cap", null, device)
        return result?.getBoolean(EXTRA_SUPPORTED, false) == true
    }

    fun getState(context: Context, device: BluetoothDevice, key: String): String? =
        call(context, "get_state", key, null, device)?.getString(EXTRA_VALUE)

    fun getBooleanState(context: Context, device: BluetoothDevice, key: String): Boolean =
        getState(context, device, key) == "1"

    fun sendAncMode(context: Context, device: BluetoothDevice, mode: Int): Boolean {
        val value = mode.toString()
        val sent = sendCommand(context, device, KEY_ANC, value)
        if (sent) setStateAndNotify(context, device, KEY_ANC, value)
        return sent
    }

    /** Publishes a state decoded directly from an incoming AAP frame. */
    fun setStateAndNotify(
        context: Context,
        device: BluetoothDevice,
        key: String,
        value: String,
    ) {
        call(context, "set_state", key, value, device)
        notifyStateChanged(context, device, key, value)
    }

    fun sendBooleanControl(
        context: Context,
        device: BluetoothDevice,
        key: String,
        enabled: Boolean,
    ): Boolean {
        val value = if (enabled) "1" else "2"
        val sent = sendCommand(context, device, key, value)
        if (sent) {
            setStateAndNotify(context, device, key, value)
        }
        return sent
    }

    private fun sendCommand(
        context: Context,
        device: BluetoothDevice,
        key: String,
        value: String,
    ): Boolean = call(context, "send_command", key, value, device)
        ?.getInt(EXTRA_COMMAND_STATE, 0) == 1

    private fun notifyStateChanged(
        context: Context,
        device: BluetoothDevice,
        key: String,
        value: String,
    ) {
        call(context, "notify_state_changed", key, value, device)
    }

    private fun call(
        context: Context,
        method: String,
        key: String,
        value: String?,
        device: BluetoothDevice,
    ): Bundle? = runCatching {
        val extras = Bundle().apply {
            putString(EXTRA_KEY, key)
            value?.let { putString(EXTRA_VALUE, it) }
            putParcelable(EXTRA_DEVICE, device)
        }
        // HyperOS 3 dispatches all AirPods operations through the single provider method
        // "airpodsRepository"; the concrete operation belongs in the arg parameter.
        context.contentResolver.call(repositoryUri, "airpodsRepository", method, extras)
    }.getOrNull()

    /** Keeps injected controls synchronized with changes made by either native HyperOS surface. */
    fun observe(view: View, device: BluetoothDevice, onState: (String, String) -> Unit) {
        val resolver = view.context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val changedUri = uri ?: return
                val address = changedUri.getQueryParameter("address")
                if (address != null && !address.equals(device.address, ignoreCase = true)) return
                changedUri.queryParameterNames.firstOrNull { it != "address" }?.let { key ->
                    changedUri.getQueryParameter(key)?.let { value -> onState(key, value) }
                }
            }
        }
        var registered = false
        fun register() {
            if (!registered) {
                resolver.registerContentObserver(repositoryUri, true, observer)
                registered = true
            }
        }
        fun unregister() {
            if (registered) {
                runCatching { resolver.unregisterContentObserver(observer) }
                registered = false
            }
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = register()
            override fun onViewDetachedFromWindow(v: View) = unregister()
        })
        if (view.isAttachedToWindow) register()
    }

    @SuppressLint("MissingPermission")
    fun connectedAirPods(context: Context): BluetoothDevice? {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
            ?.adapter ?: BluetoothAdapter.getDefaultAdapter() ?: return null
        return runCatching {
            adapter.bondedDevices.firstOrNull { device ->
                isConnected(device) && device.name?.contains("AirPods", ignoreCase = true) == true
            }
        }.getOrNull()
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun isConnected(device: BluetoothDevice): Boolean = runCatching {
        BluetoothDevice::class.java.getDeclaredMethod("isConnected").apply { isAccessible = true }
            .invoke(device) as? Boolean == true
    }.getOrDefault(false)
}
