package de.secpen.framesink.sim

/**
 * Mock liveness SDK sink. Deliberately mirrors Jumio's
 * `com.jumio.liveness.DaClient2.sendFrameNV21(long, byte[], int, int, int, int)` signature
 * so that a KYC-style Frida hook (which overwrites `data` in place before the real method
 * runs) can be tested against a controllable target.
 *
 * The harness calls [sendFrameNV21] with every camera frame (as NV21). We keep the last
 * frame the method *received* — after any injection — for the UI to display. That makes both
 * camera-level injection (the bytes arrive already-swapped) and SDK-method-level injection
 * (this method's `data` arg is overwritten) visible on screen.
 */
object FakeKycSdk {

    @Volatile private var lastFrame: ByteArray? = null
    @Volatile var lastWidth: Int = 0
        private set
    @Volatile var lastHeight: Int = 0
        private set
    @Volatile var frameCount: Long = 0
        private set

    /** Same shape as Jumio's native liveness entry point. Not native here — a plain method. */
    @JvmStatic
    fun sendFrameNV21(handle: Long, data: ByteArray, width: Int, height: Int, rotation: Int, format: Int) {
        // A real SDK would run its liveness model on `data`. We snapshot what we received so
        // the UI can show it. copyOf() captures the bytes *after* an in-place hook has run.
        lastFrame = data.copyOf()
        lastWidth = width
        lastHeight = height
        frameCount++
    }

    /** Snapshot of the last frame the "SDK" received, or null. */
    fun snapshot(): ByteArray? = lastFrame
}
