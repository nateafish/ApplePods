package io.github.nathanxie.applepods.hook

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import io.github.nathanxie.applepods.protocol.ApplePodsAapProtocol
import io.github.nathanxie.applepods.protocol.HyperOsAirPodsRepository

/** Adds only the two AAP commands missing from HyperOS' own AirPods repository. */
object ApplePodsBluetoothHook : HookContext() {
    private const val TAG = "ApplePods-Bluetooth"
    private val extendedInitDevices = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var connectionReceiverRegistered = false

    override fun onHook() {
        var installed = false
        runCatching {
            // HyperOS 4 moved the provider implementation from p0.a to k1.a. The latter is
            // the actual class called by MiuiBluetoothContentProvider on OS4.
            installRepositoryHook(findClass("k1.a"))
            installed = true
            Log.i(TAG, "HyperOS 4 AirPods repository hook installed")
        }.onFailure { Log.e(TAG, "HyperOS 4 AirPods repository hook unavailable", it) }
        runCatching {
            // Keep the HyperOS 3 implementation for older devices.
            installRepositoryHook(findClass("p0.a"))
            installed = true
            Log.i(TAG, "HyperOS 3 AirPods repository hook installed")
        }.onFailure { Log.d(TAG, "HyperOS 3 repository class not present") }
        runCatching {
            installIncomingStateHook(findClass("x2.b"), "f16671c")
            Log.i(TAG, "HyperOS 4 incoming AAP state hook installed")
        }.onFailure {
            Log.e(TAG, "HyperOS 4 incoming AAP state hook unavailable", it)
            runCatching {
                installIncomingStateHook(findClass("j1.e"), "c")
                Log.i(TAG, "HyperOS 3 incoming AAP state hook installed")
            }.onFailure { error -> Log.e(TAG, "incoming AAP state hook unavailable", error) }
        }
        if (!installed) Log.e(TAG, "no AirPods repository implementation found", IllegalStateException())
    }

    private fun installRepositoryHook(repository: Class<*>) {
        val sendCommand = repository.getDeclaredMethod("e", Context::class.java, Bundle::class.java)
            .apply { isAccessible = true }
        hookBefore(sendCommand) {
            val extras = args.getOrNull(1) as? Bundle ?: return@hookBefore
            val key = extras.getString("extra_key") ?: return@hookBefore
            val value = extras.getString("extra_value") ?: return@hookBefore
            val device = extras.getParcelable(
                "android.bluetooth.device.extra.DEVICE", BluetoothDevice::class.java,
            ) ?: return@hookBefore
            if (key == HyperOsAirPodsRepository.KEY_ANC) {
                AdaptiveStateTracker.explicitMode(
                    device.address, value.trimStart('0').toIntOrNull() ?: return@hookBefore,
                )
            }
            // Only replace adaptive. HyperOS' own three modes and all unrelated repository
            // commands continue through the OEM implementation unchanged.
            if (key != HyperOsAirPodsRepository.KEY_ANC || value.trimStart('0') !=
                ApplePodsAapProtocol.MODE_ADAPTIVE.toString()
            ) return@hookBefore
            val context = args.firstOrNull() as? Context ?: return@hookBefore
            val sent = sendThroughOemTransport(device, ApplePodsAapProtocol.listeningMode(
                ApplePodsAapProtocol.MODE_ADAPTIVE,
            ))
            extras.putInt("extra_command_state", if (sent) 1 else 0)
            result = extras
            Log.i(TAG, "raw command key=$key value=$value sent=$sent device=${device.address}")
        }
        // HyperOS' own get_state can briefly return AncOn after an adaptive command. CAPod keeps
        // the pending/confirmed value authoritative until a real AAP mode change arrives.
        runCatching {
            val getState = repository.getDeclaredMethod("b", Context::class.java, Bundle::class.java)
                .apply { isAccessible = true }
            hookBefore(getState) {
                val extras = args.getOrNull(1) as? Bundle ?: return@hookBefore
                if (extras.getString("extra_key") != HyperOsAirPodsRepository.KEY_ANC) return@hookBefore
                val device = extras.getParcelable(
                    "android.bluetooth.device.extra.DEVICE", BluetoothDevice::class.java,
                ) ?: return@hookBefore
                if (AdaptiveStateTracker.isAdaptive(device.address)) {
                    extras.putString("extra_value", "04")
                    result = extras
                }
            }
        }
        // Do not let the OEM's transient 01/02/03 notification overwrite a pending adaptive
        // selection. Explicit user changes clear the tracker in the send_command hook above.
        runCatching {
            val notifyState = repository.getDeclaredMethod("d", Context::class.java, Bundle::class.java)
                .apply { isAccessible = true }
            hookBefore(notifyState) {
                val extras = args.getOrNull(1) as? Bundle ?: return@hookBefore
                if (extras.getString("extra_key") != HyperOsAirPodsRepository.KEY_ANC) return@hookBefore
                val raw = extras.getString("extra_value")?.trimStart('0')?.toIntOrNull() ?: return@hookBefore
                val device = extras.getParcelable(
                    "android.bluetooth.device.extra.DEVICE", BluetoothDevice::class.java,
                ) ?: return@hookBefore
                if (raw in 1..3 && AdaptiveStateTracker.shouldHold(device.address)) {
                    result = extras
                    Log.i(TAG, "suppressed transient native ANC=$raw while adaptive pending/confirmed")
                }
            }
        }
    }

    private fun installIncomingStateHook(handler: Class<*>, contextField: String) {
        val received = handler.getDeclaredMethod(
            "f", BluetoothDevice::class.java, ByteArray::class.java,
        ).apply { isAccessible = true }
        hookBefore(received) {
            val owner = instance ?: return@hookBefore
            val device = args.getOrNull(0) as? BluetoothDevice ?: return@hookBefore
            val data = args.getOrNull(1) as? ByteArray ?: return@hookBefore
            // The transport handler's context field is private and its obfuscated name changed
            // between OS builds. The service singleton is stable and is itself a Context.
            val context = runCatching {
                findClass("com.android.bluetooth.ble.app.headset.BluetoothHeadsetService")
                    .getDeclaredMethod("v1").apply { isAccessible = true }.invoke(null) as? Context
            }.getOrNull() ?: runCatching {
                getObjectField(owner, contextField) as? Context
            }.getOrNull() ?: return@hookBefore
            registerConnectionLifecycle(context)
            if (isConnectResponse(data)) extendedInitDevices.remove(device.address)
            scheduleExtendedInit(device)
            ApplePodsAapProtocol.controlStates(data).forEach { state ->
                val rawValue = state.value.toInt() and 0xff
                if (state.identifier == ApplePodsAapProtocol.ID_LISTENING_MODE) {
                    if (rawValue == ApplePodsAapProtocol.MODE_ADAPTIVE) {
                        AdaptiveStateTracker.confirmed(device.address)
                    } else {
                        AdaptiveStateTracker.explicitMode(device.address, rawValue)
                    }
                }
                // Native HyperOS owns the three regular modes. We publish only adaptive here,
                // avoiding a duplicate transient 1/2/3 notification from the extension.
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
                } else rawValue.toString()
                HyperOsAirPodsRepository.setStateAndNotify(context, device, key, value)
                Log.i(TAG, "live AAP state key=$key value=$value device=${device.address}")
            }
        }
        Log.i(TAG, "incoming AAP state hook installed on ${handler.name}")
    }

    private fun isConnectResponse(data: ByteArray): Boolean = data.size >= 6 &&
        data[0] == 0x04.toByte() && data[1] == 0x00.toByte() &&
        data[2] == 0x04.toByte() && data[3] == 0x00.toByte() &&
        data[4] == 0x01.toByte() && data[5] == 0x00.toByte()

    private fun registerConnectionLifecycle(context: Context) {
        if (connectionReceiverRegistered) return
        synchronized(this) {
            if (connectionReceiverRegistered) return
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    val device = intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java,
                    ) ?: return
                    if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED ||
                        intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED
                    ) {
                        extendedInitDevices.remove(device.address)
                        AdaptiveStateTracker.disconnected(device.address)
                        Log.i(TAG, "AAP init session reset for ${device.address} (${intent.action})")
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    context.registerReceiver(receiver, filter)
                }
                connectionReceiverRegistered = true
            }.onFailure { Log.e(TAG, "failed to register AAP connection lifecycle", it) }
        }
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
        // HyperOS 4 names are v1() -> k1() -> x2.a.s(...). Keep the old i1()/X0() lookup as a
        // compatibility fallback for HyperOS 3 builds where those names were used.
        val service = runCatching {
            serviceClass.getDeclaredMethod("v1").apply { isAccessible = true }.invoke(null)
        }.getOrElse {
            serviceClass.getDeclaredMethod("i1").apply { isAccessible = true }.invoke(null)
        } ?: return@runCatching false
        val manager = runCatching { callMethod(service, "k1") }
            .getOrElse { callMethod(service, "X0") }
            ?: return@runCatching false
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
