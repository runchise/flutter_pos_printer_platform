/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sersoluciones.flutter_pos_printer_platform.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.sersoluciones.flutter_pos_printer_platform.ConnectAnswer
import com.sersoluciones.flutter_pos_printer_platform.PrintOutcome
import com.sersoluciones.flutter_pos_printer_platform.R
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BluetoothConnection(private val mHandler: Handler) : IBluetoothConnection {

    private val mAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var mConnectThread: ConnectThread? = null
    private var mConnectedThread: ConnectedThread? = null
    private var mState: Int = BluetoothConstants.STATE_NONE
    private var mAddress: String? = null

    override val state: Int
        @Synchronized get() = mState

    override val address: String?
        @Synchronized get() = mAddress

    @Synchronized
    override fun connect(address: String, answer: ConnectAnswer) {
        val device = remoteDevice(address)
        if (device == null) {
            answer.give(false)
            return
        }
        Log.d(TAG, "connect to: $address")

        mConnectThread?.cancel()
        mConnectThread = null
        mConnectedThread?.close()
        mConnectedThread = null

        mAddress = address
        val thread = ConnectThread(device, answer)
        mConnectThread = thread
        changeState(BluetoothConstants.STATE_CONNECTING)
        thread.start()
        answer.onDeadline { connectTimedOut(thread) }
    }

    private fun connectTimedOut(thread: ConnectThread) {
        synchronized(this) {
            if (mConnectThread !== thread) return
            mConnectThread = null
            changeState(BluetoothConstants.STATE_NONE)
        }
        thread.cancel()
    }

    @Synchronized
    override fun stop() {
        mConnectThread?.cancel()
        mConnectThread = null
        mConnectedThread?.close()
        mConnectedThread = null
        changeState(BluetoothConstants.STATE_NONE)
    }

    // See README.md, "Print results".
    override fun send(bytes: ByteArray, confirmTimeoutMs: Long, writeTimeoutMs: Long): Map<String, Any?> {
        val link = synchronized(this) {
            if (mState == BluetoothConstants.STATE_CONNECTED) mConnectedThread else null
        }
        if (link == null || link.isLost) return PrintOutcome.failed("Bluetooth printer is not connected")

        val deadline = writeDeadlines.schedule(Runnable { link.closeAtDeadline() }, writeTimeoutMs, TimeUnit.MILLISECONDS)
        var written = 0
        var jobWriteStarted = false
        try {
            while (written < bytes.size) {
                val length = minOf(WRITE_CHUNK_BYTES, bytes.size - written)
                jobWriteStarted = true
                link.write(bytes, written, length)
                written += length
            }
            link.requestStatus()
        } catch (error: Exception) {
            connectionLost(link)
            return writeInterrupted(link, error, jobWriteStarted, written, bytes.size, writeTimeoutMs)
        } finally {
            deadline.cancel(false)
        }

        val status = link.awaitStatus(confirmTimeoutMs)
        link.stopAwaitingStatus()
        return when {
            status != null -> PrintOutcome.sent(status, null)
            link.isLost -> PrintOutcome.cutOff("Bluetooth connection dropped before the printer confirmed the job")
            else -> {
                dropLink(link, lostToast = null)
                PrintOutcome.sent(null, "Bluetooth printer took every byte but did not confirm the job within ${confirmTimeoutMs / 1000} s")
            }
        }
    }

    private fun writeInterrupted(
        link: ConnectedThread,
        error: Exception,
        jobWriteStarted: Boolean,
        written: Int,
        total: Int,
        writeTimeoutMs: Long,
    ): Map<String, Any?> {
        val reason = when {
            link.closedAtDeadline -> "Bluetooth printer did not take the job within ${writeTimeoutMs / 1000} s (stopped at byte $written of $total)"
            written == total -> "Bluetooth connection dropped after the job was written, before the printer confirmed it: ${error.message}"
            else -> "Bluetooth connection dropped at byte $written of $total: ${error.message}"
        }
        return if (jobWriteStarted) PrintOutcome.cutOff(reason) else PrintOutcome.failed(reason)
    }

    private fun remoteDevice(address: String): BluetoothDevice? {
        if (!address.matches(ADDRESS_PATTERN)) return null
        return try {
            mAdapter?.getRemoteDevice(address)
        } catch (error: IllegalArgumentException) {
            null
        }
    }

    @Synchronized
    private fun changeState(newState: Int) {
        mState = newState
        mHandler.obtainMessage(BluetoothConstants.MESSAGE_STATE_CHANGE, newState, -1).sendToTarget()
    }

    private fun connected(thread: ConnectThread, socket: BluetoothSocket, input: InputStream, output: OutputStream, device: BluetoothDevice) {
        val isCurrent = synchronized(this) {
            if (mConnectThread !== thread) return@synchronized false
            mConnectThread = null
            if (!thread.answer.give(true)) {
                changeState(BluetoothConstants.STATE_NONE)
                return@synchronized false
            }
            val link = ConnectedThread(socket, input, output)
            mConnectedThread = link
            link.start()
            changeState(BluetoothConstants.STATE_CONNECTED)
            true
        }
        if (!isCurrent) {
            closeQuietly(socket)
            thread.answer.give(false)
            return
        }
        val msg = mHandler.obtainMessage(BluetoothConstants.MESSAGE_DEVICE_NAME)
        msg.data = Bundle().apply { putString(BluetoothConstants.DEVICE_NAME, device.name) }
        mHandler.sendMessage(msg)
    }

    private fun connectionFailed(thread: ConnectThread) {
        val isCurrent = synchronized(this) {
            if (mConnectThread !== thread) return@synchronized false
            mConnectThread = null
            changeState(BluetoothConstants.STATE_NONE)
            true
        }
        if (isCurrent) showToast(R.string.fail_connect_bt)
        thread.answer.give(false)
    }

    private fun connectionLost(link: ConnectedThread) = dropLink(link, lostToast = R.string.lost_connection_bt)

    private fun dropLink(link: ConnectedThread, lostToast: Int?) {
        if (!link.markLost()) return
        link.close()
        val isCurrent = synchronized(this) {
            if (mConnectedThread !== link) return@synchronized false
            mConnectedThread = null
            changeState(BluetoothConstants.STATE_NONE)
            true
        }
        if (isCurrent && lostToast != null) showToast(lostToast)
    }

    private fun showToast(messageId: Int) {
        val msg = mHandler.obtainMessage(BluetoothConstants.MESSAGE_TOAST)
        msg.data = Bundle().apply { putInt(TOAST, messageId) }
        mHandler.sendMessage(msg)
    }

    private inner class ConnectThread(
        private val device: BluetoothDevice,
        val answer: ConnectAnswer,
    ) : Thread("BluetoothPrinterConnect") {
        private val socket: BluetoothSocket? = try {
            device.createInsecureRfcommSocketToServiceRecord(SampleGattAttributes.SPP_UUID)
        } catch (error: Exception) {
            Log.e(TAG, "Socket: create() failed", error)
            null
        }

        override fun run() {
            val socket = socket ?: return connectionFailed(this)
            try {
                mAdapter?.cancelDiscovery()
            } catch (error: SecurityException) {
                Log.w(TAG, "Could not cancel discovery before connecting", error)
            }
            val streams = try {
                socket.connect()
                socket.inputStream to socket.outputStream
            } catch (error: Exception) {
                closeQuietly(socket)
                return connectionFailed(this)
            }
            connected(this, socket, streams.first, streams.second, device)
        }

        fun cancel() {
            socket?.let { closeQuietly(it) }
        }
    }

    private inner class ConnectedThread(
        private val socket: BluetoothSocket,
        private val input: InputStream,
        private val output: OutputStream,
    ) : Thread("BluetoothPrinterLink") {
        private val buffer = ByteArray(1024)
        private val lost = AtomicBoolean(false)
        private val statusLock = Any()
        private val statusReplies = LinkedBlockingQueue<Int>()
        private var statusRequestsSent = 0L
        private var statusRepliesSeen = 0L
        private var awaitedStatusRequest = 0L

        @Volatile
        var closedAtDeadline = false
            private set

        val isLost: Boolean
            get() = lost.get()

        override fun run() {
            while (true) {
                val count = try {
                    input.read(buffer)
                } catch (error: IOException) {
                    -1
                }
                if (count < 0) break
                for (i in 0 until count) {
                    val value = buffer[i].toInt() and 0xFF
                    if (PrintOutcome.isOfflineCauseStatus(value)) takeStatusReply(value)
                }
                mHandler.obtainMessage(BluetoothConstants.MESSAGE_READ, count, -1, buffer.copyOf(count)).sendToTarget()
            }
            connectionLost(this)
        }

        @Throws(IOException::class)
        fun write(bytes: ByteArray, offset: Int, length: Int) {
            output.write(bytes, offset, length)
            output.flush()
        }

        // The printer answers status requests in the order it reads them, so the n-th status-shaped byte answers request n.
        // Counting them keeps a late reply to an earlier job's request from confirming this job.
        @Throws(IOException::class)
        fun requestStatus() {
            synchronized(statusLock) {
                statusReplies.clear()
                statusRequestsSent++
                awaitedStatusRequest = statusRequestsSent
            }
            val request = PrintOutcome.OFFLINE_CAUSE_STATUS_REQUEST
            write(request, 0, request.size)
        }

        private fun takeStatusReply(value: Int) {
            synchronized(statusLock) {
                if (statusRepliesSeen == statusRequestsSent) return
                statusRepliesSeen++
                if (statusRepliesSeen == awaitedStatusRequest) statusReplies.offer(value)
            }
        }

        fun awaitStatus(timeoutMs: Long): Int? {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (!isLost) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) return null
                statusReplies.poll(minOf(left, 200L), TimeUnit.MILLISECONDS)?.let { return it }
            }
            return statusReplies.poll()
        }

        fun stopAwaitingStatus() {
            synchronized(statusLock) { awaitedStatusRequest = 0 }
        }

        fun markLost(): Boolean = lost.compareAndSet(false, true)

        fun closeAtDeadline() {
            closedAtDeadline = true
            close()
        }

        fun close() {
            closeQuietly(socket)
        }
    }

    companion object {
        const val TOAST = "toast"
        private const val TAG = "BluetoothConnection"
        private const val WRITE_CHUNK_BYTES = 512
        private val ADDRESS_PATTERN = Regex(BluetoothConstants.BLUETOOTH_REGEX)

        // Closing the socket is the only way to end an OutputStream.write that waits for RFCOMM credits.
        private val writeDeadlines = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "BluetoothPrinterWriteDeadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

        private fun closeQuietly(socket: BluetoothSocket) {
            try {
                socket.close()
            } catch (error: IOException) {
                Log.e(TAG, "close() of socket failed", error)
            }
        }
    }
}
