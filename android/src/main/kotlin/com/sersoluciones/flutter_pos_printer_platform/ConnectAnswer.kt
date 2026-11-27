package com.sersoluciones.flutter_pos_printer_platform

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

// See README.md, "Print results": a native connect answers once, and false at its deadline.
class ConnectAnswer(private val reply: ((Boolean) -> Unit)?, timeoutMs: Long) {
    private val mainThread = Handler(Looper.getMainLooper())
    private val answered = AtomicBoolean(false)
    private val expired = AtomicBoolean(false)

    @Volatile
    private var cancelAttempt: (() -> Unit)? = null

    private val deadline = Runnable {
        if (!answered.compareAndSet(false, true)) return@Runnable
        expired.set(true)
        reply?.invoke(false)
        cancelAttempt?.invoke()
    }

    init {
        mainThread.postDelayed(deadline, timeoutMs.coerceAtLeast(0L))
    }

    val isAnswered: Boolean
        get() = answered.get()

    fun give(connected: Boolean): Boolean {
        if (!answered.compareAndSet(false, true)) return false
        mainThread.removeCallbacks(deadline)
        reply?.let { mainThread.post { it(connected) } }
        return true
    }

    // The deadline can pass before the attempt registers its cancel, so both sides check: cancel may run twice and must be idempotent.
    fun onDeadline(cancel: () -> Unit) {
        cancelAttempt = cancel
        if (expired.get()) cancel()
    }
}
