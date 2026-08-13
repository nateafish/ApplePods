package io.github.nathanxie.applepods.hook

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.nathanxie.applepods.protocol.ApplePodsAapProtocol
import io.github.nathanxie.applepods.protocol.HyperOsAirPodsRepository

/** Adds only the two AAP commands missing from HyperOS' own AirPods repository. */
object ApplePodsBluetoothHook : HookContext() {
    private const val TAG = "ApplePods-Bluetooth"
    private val extendedInitDevices = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun onHook() {
        runCatching {
            val repository = findClass("p0.a")
            val sendCommand = repository.getDeclaredMethod("e", Context::class.java, Bundle::class.java)
                .apply { isAccessible = true }
            hookBefore(sendCommand) {
                val extras = args.getOrNull(1) as? Bundle ?: return@hookBefore
                val key = extras.getString("extra_key") ?: return@hookBefore
                val identifier = when (key) {
                    HyperOsAirPodsRepository.KEY_ANC -> {
                        if (extras.getString("extra_value")?.trimStart('0') !=
                            ApplePodsAapProtocol.MODE_ADAPTIVE.toString()
                        ) {
                            return@hookBefore
                        }
                        ApplePodsAapProtocol.ID_LISTENING_MODE
                    }
                    HyperOsAirPodsRepository.KEY_CONVERSATION_AWARENESS ->
                        ApplePodsAapProtocol.ID_CONVERSATION_AWARENESS
                    HyperOsAirPodsRepository.KEY_SLEEP_DETECTION ->
                        ApplePodsAapProtocol.ID_SLEEP_DETECTION
                    else -> return@hookBefore
                }
                val device = extras.getParcelable(
                    "android.bluetooth.device.extra.DEVICE",
                    BluetoothDevice::class.java,
                ) ?: return@hookBefore
                val value = extras.getString("extra_value")
                val packet = if (identifier == ApplePodsAapProtocol.ID_LISTENING_MODE) {
                    ApplePodsAapProtocol.listeningMode(ApplePodsAapProtocol.MODE_ADAPTIVE)
                } else {
                    ApplePodsAapProtocol.booleanControl(identifier, value == "1")
                }
                val sent = sendThroughOemTransport(device, packet)
                extras.putInt("extra_command_state", if (sent) 1 else 0)
                result = extras
                Log.i(TAG, "raw command key=$key value=$value sent=$sent device=${device.address}")
            }
            installIncomingStateHook()
            Log.i(TAG, "HyperOS AirPods repository extension installed")
        }.onFailure { Log.e(TAG, "HyperOS AirPods repository extension unavailable", it) }
    }

    private fun installIncomingStateHook() {
        val handler = findClass("j1.e")
        val received = handler.getDeclaredMethod(
            "f",
            BluetoothDevice::class.java,
            ByteArray::class.java,
        ).apply { isAccessible = true }
        hookBefore(received) {
            val owner = instance ?: return@hookBefore
            val device = args.getOrNull(0) as? BluetoothDevice ?: return@hookBefore
            val data = args.getOrNull(1) as? ByteArray ?: return@hookBefore
            val context = getObjectField(owner, "c") as? Context ?: return@hookBefore
            scheduleExtendedInit(device)
            ApplePodsAapProtocol.controlStates(data).forEach { state ->
                val rawValue = state.value.toInt() and 0xff
                // HyperOS already publishes its three native ANC states using the
                // zero-padded values expected by AncController. Publishing them a
                // second time as 1/2/3 briefly clears the native selection.
                if (state.identifier == ApplePodsAapProtocol.ID_LISTENING_MODE &&
                    rawValue != ApplePodsAapProtocol.MODE_ADAPTIVE
                ) return@forEach
                val key = when (state.identifier) {
                    ApplePodsAapProtocol.ID_LISTENING_MODE -> HyperOsAirPodsRepository.KEY_ANC
                    ApplePodsAapProtocol.ID_CONVERSATION_AWARENESS ->
                        HyperOsAirPodsRepository.KEY_CONVERSATION_AWARENESS
                    ApplePodsAapProtocol.ID_SLEEP_DETECTION ->
                        HyperOsAirPodsRepository.KEY_SLEEP_DETECTION
                    else -> return@forEach
                }
                val value = if (state.identifier == ApplePodsAapProtocol.ID_LISTENING_MODE) {
                    rawValue.toString().padStart(2, '0')
                } else {
                    rawValue.toString()
                }
                HyperOsAirPodsRepository.setStateAndNotify(context, device, key, value)
                Log.i(TAG, "live AAP state key=$key value=$value device=${device.address}")
            }
        }
        Log.i(TAG, "incoming AAP state hook installed")
    }

    private fun scheduleExtendedInit(device: BluetoothDevice) {
        if (!extendedInitDevices.add(device.address)) return
        Handler(Looper.getMainLooper()).postDelayed({
            val sent = sendThroughOemTransport(device, ApplePodsAapProtocol.setSpecificFeatures)
            if (!sent) extendedInitDevices.remove(device.address)
            Log.i(TAG, "AAP extended init sent=$sent device=${device.address}")
        }, 600L)
    }

    private fun sendThroughOemTransport(device: BluetoothDevice, packet: ByteArray): Boolean = runCatching {
        val serviceClass = findClass("com.android.bluetooth.ble.app.headset.BluetoothHeadsetService")
        val service = serviceClass.getDeclaredMethod("i1").apply { isAccessible = true }.invoke(null)
            ?: return@runCatching false
        val manager = callMethod(service, "X0") ?: return@runCatching false
        val result = callMethod(manager, "s", device, packet, 0)
        Log.i(TAG, "OEM transport result=${result?.javaClass?.name}:$result packet=${packet.joinToString { "%02x".format(it) }}")
        when (result) {
            is Boolean -> result
            is Number -> result.toInt() != 0
            null -> true
            else -> false
        }
    }.onFailure { Log.e(TAG, "OEM AirPods transport send failed", it) }.getOrDefault(false)
}
