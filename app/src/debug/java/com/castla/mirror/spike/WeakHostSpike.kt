package com.castla.mirror.spike

/**
 * DEBUG-ONLY (issue #51 Phase 1). Pure gate logic for the weak-host synthesis
 * experiment. No Android deps so it is unit-testable off-device.
 */
object WeakHostSpike {

    /**
     * The address to synthesize. `192.0.0.8` is an IANA-registered special-use IPv4
     * (RFC 7600 dummy address / 464XLAT CLAT range) — a hypothesis a car reached once,
     * NOT a confirmed production address.
     */
    const val SYNTH_ADDR = "192.0.0.8"

    /**
     * True if [SYNTH_ADDR] already exists on the device (e.g. a CLAT interface).
     * Synthesizing it then is invalid — it would collide with a real interface, so the
     * experiment's result would be meaningless. Callers MUST abort as ADDRESS_CONFLICT.
     */
    fun addressConflict(localIps: List<String>): Boolean = localIps.contains(SYNTH_ADDR)
}
