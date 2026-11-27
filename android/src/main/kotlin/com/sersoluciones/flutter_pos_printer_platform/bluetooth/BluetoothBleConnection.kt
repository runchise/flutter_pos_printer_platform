package com.sersoluciones.flutter_pos_printer_platform.bluetooth

import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.sersoluciones.flutter_pos_printer_platform.ConnectAnswer
import com.sersoluciones.flutter_pos_printer_platform.PrintOutcome
import com.sersoluciones.flutter_pos_printer_platform.bluetooth.SampleGattAttributes.Companion.CLIENT_CHARACTERISTIC_CONFIG
import com.sersoluciones.flutter_pos_printer_platform.bluetooth.SampleGattAttributes.Companion.HEART_RATE_MEASUREMENT
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private const val TAG = "BluetoothBleConnection"

class BluetoothBleConnection(
    private val mContext: Context,
    private val mHandler: Handler,
    private var autoConnect: Boolean = false
) : IBluetoothConnection {

    private var bluetoothGatt: BluetoothGatt? = null
    private var session: GattSession? = null
    private var mAddress: String? = null
    private var mState: Int = BluetoothConstants.STATE_NONE

    @Volatile
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var payloadSize = DEFAULT_PAYLOAD_SIZE
    private val writeAcks = LinkedBlockingQueue<WriteAck>()
    private val writeLock = Any()

    override val state: Int
        @Synchronized get() = mState

    override val address: String?
        @Synchronized get() = mAddress

    @Synchronized
    private fun changeState(newState: Int) {
        mState = newState
        mHandler.obtainMessage(BluetoothConstants.MESSAGE_STATE_CHANGE, newState, -1).sendToTarget()
    }

    override fun connect(address: String, answer: ConnectAnswer) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (!address.matches(ADDRESS_PATTERN) || adapter == null) {
            if (adapter == null) Log.w(TAG, "BluetoothAdapter not initialized")
            answer.give(false)
            return
        }

        val started = synchronized(this) {
            if (mState == BluetoothConstants.STATE_CONNECTED && address.equals(mAddress, ignoreCase = true)) {
                answer.give(true)
                return
            }
            endSession()
            val attempt = GattSession(answer)
            session = attempt
            mAddress = address
            changeState(BluetoothConstants.STATE_CONNECTING)
            val gatt = try {
                val device = adapter.getRemoteDevice(address)
                val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(mContext, autoConnect, attempt, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(mContext, autoConnect, attempt)
                }
                val msg = mHandler.obtainMessage(BluetoothConstants.MESSAGE_DEVICE_NAME)
                msg.data = Bundle().apply { putString(BluetoothConstants.DEVICE_NAME, device.name) }
                mHandler.sendMessage(msg)
                gatt
            } catch (error: Exception) {
                Log.w(TAG, "Could not start a BLE connection to $address", error)
                null
            }
            if (gatt == null) {
                session = null
                changeState(BluetoothConstants.STATE_NONE)
                answer.give(false)
                return
            }
            bluetoothGatt = gatt
            attempt
        }
        answer.onDeadline { closeSession(started) }
    }

    @Synchronized
    override fun stop() {
        endSession()
        if (mState != BluetoothConstants.STATE_NONE) changeState(BluetoothConstants.STATE_NONE)
    }

    private fun closeSession(ended: GattSession) {
        synchronized(this) {
            if (session !== ended) return
            endSession()
            changeState(BluetoothConstants.STATE_NONE)
        }
    }

    @Synchronized
    private fun endSession() {
        val ended = session ?: return
        session = null
        writeCharacteristic = null
        val gatt = bluetoothGatt
        bluetoothGatt = null
        writeAcks.offer(WriteAck(ended, LINK_LOST))
        ended.answer.give(false)
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (error: SecurityException) {
            Log.w(TAG, "Could not close the BLE connection", error)
        }
    }

    // See README.md, "Print results". A BLE write carries at most MTU - 3 bytes, so the job goes in pieces.
    override fun send(bytes: ByteArray, confirmTimeoutMs: Long, writeTimeoutMs: Long): Map<String, Any?> {
        val link = synchronized(this) {
            val current = session
            val gatt = bluetoothGatt
            if (mState == BluetoothConstants.STATE_CONNECTED && current != null && gatt != null) current to gatt else null
        } ?: return PrintOutcome.failed("BLE printer is not connected")
        val (writingSession, gatt) = link
        val characteristic = writeCharacteristic
            ?: return PrintOutcome.failed("BLE printer has no characteristic the app can write to")
        val withResponse = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        characteristic.writeType =
            if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

        synchronized(writeLock) {
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + payloadSize, bytes.size)
                writeAcks.clear()
                characteristic.value = bytes.copyOfRange(offset, end)
                if (!writeWhenGattIsFree(gatt, characteristic)) {
                    return stoppedAt(offset, "BLE printer refused the job at byte $offset of ${bytes.size}")
                }
                when (val status = awaitAck(writingSession)) {
                    null -> {
                        closeSession(writingSession)
                        return PrintOutcome.cutOff(
                            "BLE printer did not answer at byte $offset of ${bytes.size} within ${PIECE_TIMEOUT_MS / 1000} s. " +
                                "The BLE connection was closed, so the next job connects again"
                        )
                    }
                    LINK_LOST -> return PrintOutcome.cutOff("BLE connection dropped at byte $offset of ${bytes.size}")
                    BluetoothGatt.GATT_SUCCESS -> offset = end
                    else -> return stoppedAt(offset, "BLE printer rejected the job at byte $offset of ${bytes.size}, GATT status $status")
                }
            }
        }
        return if (withResponse) PrintOutcome.delivered(null)
        else PrintOutcome.sent(null, "BLE printer took every byte, but it cannot confirm writes")
    }

    private fun awaitAck(writingSession: GattSession): Int? {
        val deadline = SystemClock.elapsedRealtime() + PIECE_TIMEOUT_MS
        while (true) {
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) return null
            val ack = writeAcks.poll(left, TimeUnit.MILLISECONDS) ?: return null
            if (ack.session === writingSession) return ack.status
        }
    }

    private fun stoppedAt(offset: Int, reason: String): Map<String, Any?> =
        if (offset == 0) PrintOutcome.failed(reason) else PrintOutcome.cutOff(reason)

    // writeCharacteristic returns false while another GATT operation, such as a descriptor write, is still running.
    private fun writeWhenGattIsFree(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        val deadline = SystemClock.elapsedRealtime() + GATT_BUSY_TIMEOUT_MS
        while (state == BluetoothConstants.STATE_CONNECTED) {
            if (gatt.writeCharacteristic(characteristic)) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            Thread.sleep(50)
        }
        return false
    }

    private class WriteAck(val session: BluetoothGattCallback, val status: Int)

    private inner class GattSession(val answer: ConnectAnswer) : BluetoothGattCallback() {
        private var ready = false

        private val isCurrent: Boolean
            get() = synchronized(this@BluetoothBleConnection) { session === this }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, " ---------- onConnectionStateChange: newState $newState status $status")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    synchronized(this@BluetoothBleConnection) {
                        if (session !== this) return
                        payloadSize = DEFAULT_PAYLOAD_SIZE
                        writeCharacteristic = null
                    }
                    if (!gatt.requestMtu(REQUESTED_MTU)) discoverServices(gatt)
                }
                BluetoothProfile.STATE_CONNECTING -> synchronized(this@BluetoothBleConnection) {
                    if (session === this) changeState(BluetoothConstants.STATE_CONNECTING)
                }
                BluetoothProfile.STATE_DISCONNECTED -> synchronized(this@BluetoothBleConnection) {
                    if (session !== this) return
                    if (ready) {
                        writeCharacteristic = null
                        writeAcks.offer(WriteAck(this, LINK_LOST))
                    } else {
                        endSession()
                    }
                    changeState(BluetoothConstants.STATE_NONE)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!isCurrent) return
            if (status == BluetoothGatt.GATT_SUCCESS) payloadSize = mtu - 3
            discoverServices(gatt)
        }

        private fun discoverServices(gatt: BluetoothGatt) {
            if (!gatt.discoverServices()) giveUp("BLE printer would not start service discovery")
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!isCurrent) return
            if (status != BluetoothGatt.GATT_SUCCESS) return giveUp("BLE service discovery failed, GATT status $status")
            val characteristics = gatt.services.orEmpty().flatMap { it.characteristics }
            val writable = characteristics.firstOrNull { it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 }
                ?: characteristics.firstOrNull { it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 }
                ?: return giveUp("BLE printer has no characteristic the app can write to")
            synchronized(this@BluetoothBleConnection) {
                if (session !== this) return
                if (!ready && !answer.give(true)) return
                ready = true
                writeCharacteristic = writable
                changeState(BluetoothConstants.STATE_CONNECTED)
            }
            characteristics.forEach { setCharacteristicNotification(gatt, it) }
        }

        private fun giveUp(reason: String) {
            Log.w(TAG, reason)
            closeSession(this)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(characteristic)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            broadcastUpdate(characteristic)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (isCurrent) writeAcks.offer(WriteAck(this, status))
            with(characteristic) {
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {
                        Log.i(
                            "BluetoothGattCallback",
                            "Wrote to characteristic $uuid | value: $value"
                        )
                    }
                    BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> {
                        Log.e("BluetoothGattCallback", "Write exceeded connection ATT MTU!")
                    }
                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> {
                        Log.e("BluetoothGattCallback", "Write not permitted for $uuid!")
                    }
                    else -> {
                        Log.e(
                            "BluetoothGattCallback",
                            "Characteristic write failed for $uuid, error: $status"
                        )
                    }
                }
            }
        }
    }

    private var buffer = ArrayList<Byte>()

    private fun broadcastUpdate(characteristic: BluetoothGattCharacteristic?) {
        if (characteristic == null || characteristic.uuid == UUID_HEART_RATE_MEASUREMENT) return
        val data: ByteArray? = characteristic.value
        if (data?.isNotEmpty() == true) {
            for (byte in data) {
                buffer.add(byte)
                if (byte.toInt() == 13) {
                    sendMsg()
                    break
                }
            }
        }
    }

    private fun sendMsg() {
        mHandler.obtainMessage(
            BluetoothConstants.MESSAGE_READ,
            buffer.size,
            -1,
            buffer.toByteArray()
        ).sendToTarget()

        buffer = arrayListOf()
    }

    private fun setCharacteristicNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor: BluetoothGattDescriptor =
            characteristic.getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG)) ?: return
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(descriptor)
    }

    companion object {

        val UUID_HEART_RATE_MEASUREMENT: UUID =
            UUID.fromString(HEART_RATE_MEASUREMENT)

        private const val DEFAULT_PAYLOAD_SIZE = 20
        private const val REQUESTED_MTU = 247
        private const val PIECE_TIMEOUT_MS = 5000L
        private const val GATT_BUSY_TIMEOUT_MS = 2000L
        private const val LINK_LOST = -1
        private val ADDRESS_PATTERN = Regex(BluetoothConstants.BLUETOOTH_REGEX)
    }
}
