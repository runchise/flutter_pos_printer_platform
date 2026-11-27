package com.sersoluciones.flutter_pos_printer_platform

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// See README.md, "Print results". lib/src/print_result.dart reads these maps.
object PrintOutcome {
    val OFFLINE_CAUSE_STATUS_REQUEST = byteArrayOf(0x10, 0x04, 0x02)

    // ESC/POS DLE EOT 2 reply: bits 1 and 4 are always 1, bits 0 and 7 always 0.
    fun isOfflineCauseStatus(value: Int): Boolean = value and 0x93 == 0x12

    fun failed(reason: String): Map<String, Any?> = mapOf("outcome" to "failed", "reason" to reason)

    fun cutOff(reason: String): Map<String, Any?> = mapOf("outcome" to "cutOff", "reason" to reason)

    fun sent(status: Int?, reason: String?): Map<String, Any?> =
        mapOf("outcome" to "sent", "status" to status, "reason" to reason)

    fun delivered(status: Int?): Map<String, Any?> = mapOf("outcome" to "delivered", "status" to status)
}

class PrintWorker(private val threadName: String) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, threadName).apply { isDaemon = true }
    }
    private val mainThread = Handler(Looper.getMainLooper())

    fun execute(task: () -> Unit) {
        executor.execute {
            try {
                task()
            } catch (error: Throwable) {
                Log.e(threadName, "Printer task failed", error)
            }
        }
    }

    fun send(job: () -> Map<String, Any?>, onReply: (Map<String, Any?>) -> Unit) {
        executor.execute {
            val reply = try {
                job()
            } catch (error: Throwable) {
                Log.e(threadName, "Print job failed", error)
                PrintOutcome.cutOff("Printer plugin error: ${error.message ?: error.javaClass.simpleName}")
            }
            mainThread.post { onReply(reply) }
        }
    }
}
