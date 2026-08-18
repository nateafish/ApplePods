package io.github.nathanxie.applepods.hook

import java.util.concurrent.ConcurrentHashMap

/** CAPod-style pending/confirmed state for the one mode HyperOS does not understand. */
object AdaptiveStateTracker {
    private const val PENDING_GRACE_MS = 1_500L
    private data class Entry(var requestedAt: Long, var confirmed: Boolean = false)
    private val states = ConcurrentHashMap<String, Entry>()

    fun requested(address: String) {
        states[address] = Entry(System.currentTimeMillis())
    }

    fun confirmed(address: String) {
        states.compute(address) { _, old -> (old ?: Entry(System.currentTimeMillis())).apply { confirmed = true } }
    }

    fun explicitMode(address: String, mode: Int) {
        if (mode == io.github.nathanxie.applepods.protocol.ApplePodsAapProtocol.MODE_ADAPTIVE) {
            requested(address)
        } else {
            states.remove(address)
        }
    }

    fun disconnected(address: String) {
        states.remove(address)
    }

    fun shouldHold(address: String): Boolean {
        val entry = states[address] ?: return false
        if (entry.confirmed) return true
        if (System.currentTimeMillis() - entry.requestedAt <= PENDING_GRACE_MS) return true
        states.remove(address, entry)
        return false
    }

    fun isAdaptive(address: String): Boolean = states[address]?.let { it.confirmed ||
        System.currentTimeMillis() - it.requestedAt <= PENDING_GRACE_MS } == true
}
