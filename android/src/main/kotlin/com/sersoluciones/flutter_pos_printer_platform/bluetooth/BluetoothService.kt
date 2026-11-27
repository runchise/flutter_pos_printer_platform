package com.sersoluciones.flutter_pos_printer_platform.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sersoluciones.flutter_pos_printer_platform.ConnectAnswer
import com.sersoluciones.flutter_pos_printer_platform.PrintOutcome
import com.sersoluciones.flutter_pos_printer_platform.PrintWorker
import com.sersoluciones.flutter_pos_printer_platform.models.LocalBluetoothDevice
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.Result


data class BluetoothPrinterIdentity(val address: String, val isBle: Boolean) {
    fun matches(connection: IBluetoothConnection): Boolean =
        (connection is BluetoothBleConnection) == isBle && address.equals(connection.address, ignoreCase = true)

    fun describe(): String = "${if (isBle) "BLE" else "Bluetooth"} $address"
}

class BluetoothService private constructor(private var bluetoothHandler: Handler?) {
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())
    private val mainThread = Handler(Looper.getMainLooper())
    private val mHandlerAutoConnect = Handler(Looper.getMainLooper())

    val mBluetoothAdapter: BluetoothAdapter by lazy {
        BluetoothAdapter.getDefaultAdapter()
    }

    private val bleScanner by lazy {
        mBluetoothAdapter.bluetoothLeScanner
    }
    private var devicesBle: MutableList<LocalBluetoothDevice> = mutableListOf()

    fun setHandler(handler: Handler?) {
        bluetoothHandler = handler
    }

    fun scanBluDevice(mChannel: MethodChannel) {
        bluetoothHandler?.obtainMessage(BluetoothConstants.MESSAGE_START_SCANNING, -1, -1)
            ?.sendToTarget()
        val pairedDevices: Set<BluetoothDevice>? = mBluetoothAdapter.bondedDevices
        pairedDevices?.forEach { device ->
            val deviceName =
                if (device.name == null) device.address else device.name
            val deviceHardwareAddress = device.address
            val deviceMap: HashMap<String?, String?> = HashMap()
            deviceMap["name"] = deviceName
            deviceMap["address"] = deviceHardwareAddress
            Log.d(TAG, "deviceName $deviceName deviceHardwareAddress $deviceHardwareAddress")

            mChannel.invokeMethod("ScanResult", deviceMap)
        }

        bluetoothHandler?.obtainMessage(BluetoothConstants.MESSAGE_STOP_SCANNING, -1, -1)
            ?.sendToTarget()
    }

    fun scanBleDevice(mChannel: MethodChannel) {
        if (bleScanner == null) return
        devicesBle.clear()
        handler.removeCallbacksAndMessages(null)
        val leScanCallback = MyScanCallback()
        leScanCallback.init(mChannel)

        if (!scanning) {
            handler.postDelayed({
                scanning = false
                bleScanner.stopScan(leScanCallback)
                bluetoothHandler?.obtainMessage(BluetoothConstants.MESSAGE_STOP_SCANNING, -1, -1)
                    ?.sendToTarget()
                Log.d(TAG, "----- stop scanning ble ------- ")
            }, SCAN_PERIOD)
            Log.d(TAG, "----- start scanning ble ------ ")
            scanning = true
            bleScanner.startScan(leScanCallback)
            bluetoothHandler?.obtainMessage(BluetoothConstants.MESSAGE_START_SCANNING, -1, -1)
                ?.sendToTarget()
        } else {
            scanning = false
            bleScanner.stopScan(leScanCallback)
            bluetoothHandler?.obtainMessage(BluetoothConstants.MESSAGE_STOP_SCANNING, -1, -1)
                ?.sendToTarget()
        }
    }

    fun cleanHandlerBtBle() {
        handler.removeCallbacksAndMessages(null)
    }

    inner class MyScanCallback : ScanCallback() {

        private var mmChannel: MethodChannel? = null
        fun init(channel: MethodChannel) {
            mmChannel = channel
        }

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)

            val deviceHardwareAddress = result.device?.address
            val deviceName =
                if (result.device?.name == null) result.device?.address else result.device?.name

            if (!devicesBle.any { e -> e.address == deviceHardwareAddress }) {
                val deviceBT = LocalBluetoothDevice(
                    name = deviceName ?: "Unknown",
                    address = deviceHardwareAddress
                )
                val deviceMap: HashMap<String?, String?> = HashMap()
                deviceMap["name"] = deviceName
                deviceMap["address"] = deviceHardwareAddress
                if (result.device?.name != null)
                    mmChannel?.invokeMethod("ScanResult", deviceMap)
                devicesBle.add(deviceBT)
                Log.d(TAG, "deviceName ${result.device.name} deviceHardwareAddress ${result.device.address}")

            }
        }
    }

    fun bluetoothDisconnect(onDone: (Boolean) -> Unit) {
        sender.execute {
            val stopped = try {
                reconnectBluetooth = false
                mHandlerAutoConnect.removeCallbacks(reconnect)
                bluetoothConnection?.stop()
                bluetoothConnection = null
                true
            } catch (error: Exception) {
                Log.e(TAG, "Could not stop the Bluetooth connection", error)
                false
            }
            mainThread.post { onDone(stopped) }
        }
    }

    fun onStartConnection(
        context: Context,
        address: String?,
        result: Result,
        isBle: Boolean,
        autoConnect: Boolean,
        connectTimeoutMs: Long,
    ) {
        val stateHandler = bluetoothHandler
        if (address == null || stateHandler == null) return result.success(false)

        val answer = ConnectAnswer({ connected -> result.success(connected) }, connectTimeoutMs)
        sender.execute {
            if (answer.isAnswered) return@execute
            try {
                startConnection(context, stateHandler, address, isBle, autoConnect, connectTimeoutMs, answer)
            } catch (error: Exception) {
                Log.e(TAG, "Could not start the Bluetooth connection to $address", error)
                answer.give(false)
            }
        }
    }

    private fun startConnection(
        context: Context,
        stateHandler: Handler,
        address: String,
        isBle: Boolean,
        autoConnect: Boolean,
        connectTimeoutMs: Long,
        answer: ConnectAnswer,
    ) {
        val existing = bluetoothConnection
        val otherKind = existing != null && (existing is BluetoothBleConnection) != isBle
        val otherPrinter = existing != null && existing.state != BluetoothConstants.STATE_NONE &&
            !address.equals(existing.address, ignoreCase = true)
        if (otherKind || otherPrinter) {
            mHandlerAutoConnect.removeCallbacks(reconnect)
            existing?.stop()
            bluetoothConnection = null
        }
        val connection = bluetoothConnection
            ?: (if (isBle) BluetoothBleConnection(mContext = context, stateHandler, autoConnect = autoConnect) else BluetoothConnection(stateHandler))
                .also { bluetoothConnection = it }

        reconnectBluetooth = connection is BluetoothConnection && autoConnect
        reconnectTimeoutMs = connectTimeoutMs
        val state = connection.state
        when {
            address.isNotEmpty() && state == BluetoothConstants.STATE_NONE -> connection.connect(address, answer)
            state == BluetoothConstants.STATE_CONNECTED -> {
                answer.give(true)
                stateHandler.obtainMessage(BluetoothConstants.MESSAGE_STATE_CHANGE, state, -1).sendToTarget()
            }
            else -> {
                answer.give(false)
                stateHandler.obtainMessage(BluetoothConstants.MESSAGE_STATE_CHANGE, state, -1).sendToTarget()
            }
        }
    }

    private val reconnect = Runnable { sender.execute { reconnectNow() } }

    private fun reconnectNow() {
        val connection = bluetoothConnection as? BluetoothConnection ?: return
        val address = connection.address
        if (!reconnectBluetooth || address.isNullOrEmpty() || connection.state != BluetoothConstants.STATE_NONE) return
        connection.connect(address, ConnectAnswer(null, reconnectTimeoutMs))
    }

    fun autoConnectBt() {
        sender.execute {
            val connection = bluetoothConnection
            if (connection is BluetoothConnection && reconnectBluetooth && connection.state == BluetoothConstants.STATE_NONE) {
                mHandlerAutoConnect.removeCallbacks(reconnect)
                mHandlerAutoConnect.postDelayed(reconnect, (1000 + Math.random() * 4000).toLong())
            }
        }
    }

    fun removeReconnectHandlers() {
        mHandlerAutoConnect.removeCallbacks(reconnect)
    }

    // See README.md, "Print results" (wrong printer).
    fun sendDataByte(
        bytes: ByteArray,
        confirmTimeoutMs: Long,
        writeTimeoutMs: Long,
        printer: BluetoothPrinterIdentity?,
        onReply: (Map<String, Any?>) -> Unit,
    ) {
        sender.send({ sendOnWorker(bytes, confirmTimeoutMs, writeTimeoutMs, printer) }, onReply)
    }

    private fun sendOnWorker(bytes: ByteArray, confirmTimeoutMs: Long, writeTimeoutMs: Long, printer: BluetoothPrinterIdentity?): Map<String, Any?> {
        val connection = bluetoothConnection ?: return PrintOutcome.failed("Bluetooth printer is not connected")
        if (printer != null && !printer.matches(connection)) {
            return if (connection.state == BluetoothConstants.STATE_CONNECTED) {
                PrintOutcome.failed("Connected to a different printer (${describe(connection)}), not to ${printer.describe()}. Nothing was sent.")
            } else {
                PrintOutcome.failed("Bluetooth printer ${printer.describe()} is not connected")
            }
        }
        return connection.send(bytes, confirmTimeoutMs, writeTimeoutMs)
    }

    private fun describe(connection: IBluetoothConnection): String =
        "${if (connection is BluetoothBleConnection) "BLE" else "Bluetooth"} ${connection.address}"

    companion object {

        private var mInstance: BluetoothService? = null

        // Read and changed only on sender, so a connect or disconnect from either engine waits for the send in progress.
        private var bluetoothConnection: IBluetoothConnection? = null
        private var reconnectBluetooth = false
        private var reconnectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS

        // Process-wide: the UI engine and the background engine send to the same printers through this one thread.
        private val sender = PrintWorker("bluetooth-printer")
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 20000L

        fun getInstance(bluetoothHandler: Handler): BluetoothService {
            if (mInstance == null) {
                mInstance = BluetoothService(bluetoothHandler)
            }
            return mInstance!!
        }

        private const val SCAN_PERIOD: Long = 4 * 1000

        const val TAG = "BluetoothPrinter"
    }
}
