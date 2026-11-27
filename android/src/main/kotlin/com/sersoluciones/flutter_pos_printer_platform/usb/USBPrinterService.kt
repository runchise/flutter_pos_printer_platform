package com.sersoluciones.flutter_pos_printer_platform.usb

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.sersoluciones.flutter_pos_printer_platform.ConnectAnswer
import com.sersoluciones.flutter_pos_printer_platform.PrintOutcome
import com.sersoluciones.flutter_pos_printer_platform.PrintWorker
import com.sersoluciones.flutter_pos_printer_platform.R

data class UsbPrinterIdentity(val vendorId: Int, val productId: Int) {
    fun matches(device: UsbDevice): Boolean = device.vendorId == vendorId && device.productId == productId

    fun describe(): String = "USB vendor $vendorId product $productId"
}

// init() sets mContext, mUSBManager and mPermissionIndent once, before any worker task runs.
// Every other field is read and changed only on worker, so a stuck bulkTransfer never blocks the main thread,
// and a connect, close or permission answer from either engine waits for the send in progress.
class USBPrinterService private constructor(@Volatile private var mHandler: Handler?) {
    private val worker = PrintWorker("usb-printer")
    private val mainThread = Handler(Looper.getMainLooper())
    private var mContext: Context? = null
    private var mUSBManager: UsbManager? = null
    private var mPermissionIndent: PendingIntent? = null
    private var mUsbDevice: UsbDevice? = null
    private var mUsbDeviceConnection: UsbDeviceConnection? = null
    private var mUsbInterface: UsbInterface? = null
    private var mEndPoint: UsbEndpoint? = null
    private var mInEndPoint: UsbEndpoint? = null
    private var pendingDevice: UsbDevice? = null
    private val permissionWaiters = mutableListOf<ConnectAnswer>()

    fun setHandler(handler: Handler?) {
        mHandler = handler
    }

    private val mUsbDeviceReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val deviceId = intent.usbDevice()?.deviceId
            when (intent.action) {
                ACTION_USB_PERMISSION -> worker.execute { onPermissionAnswer(deviceId) }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> if (deviceId != null) worker.execute { onDeviceDetached(deviceId) }
            }
        }
    }

    fun init(context: Context?) {
        if (mUSBManager != null) return
        val appContext = context ?: return
        mContext = appContext
        mUSBManager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager

        val permissionIntent = Intent(ACTION_USB_PERMISSION).apply {
            putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true)
            setPackage(appContext.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        mPermissionIndent = PendingIntent.getBroadcast(appContext, 0, permissionIntent, flags)

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.registerReceiver(mUsbDeviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(mUsbDeviceReceiver, filter)
        }
        Log.v(LOG_TAG, "ESC/POS Printer initialized")
    }

    val deviceList: List<UsbDevice>
        get() {
            if (mUSBManager == null) {
                Toast.makeText(mContext, mContext?.getString(R.string.not_usb_manager), Toast.LENGTH_LONG).show()
                return emptyList()
            }
            return ArrayList(mUSBManager!!.deviceList.values)
        }

    fun selectDevice(vendorId: Int, productId: Int, permissionTimeoutMs: Long, onAnswer: (Boolean) -> Unit) {
        val answer = ConnectAnswer(onAnswer, permissionTimeoutMs)
        answer.onDeadline { worker.execute { stopWaitingForPermission(answer) } }
        worker.execute {
            if (answer.isAnswered) return@execute
            try {
                chooseDevice(vendorId, productId, answer)
            } catch (error: Exception) {
                Log.e(LOG_TAG, "Could not select USB device $vendorId:$productId", error)
                answer.give(false)
            }
        }
    }

    fun close(onClosed: () -> Unit) {
        worker.execute {
            try {
                closeConnection()
            } finally {
                mainThread.post(onClosed)
            }
        }
    }

    // See README.md, "Print results".
    fun printBytes(bytes: ByteArray, statusTimeoutMs: Int, printer: UsbPrinterIdentity?, onReply: (Map<String, Any?>) -> Unit) {
        worker.send({ writeJob(bytes, statusTimeoutMs, printer) }, onReply)
    }

    private fun chooseDevice(vendorId: Int, productId: Int, answer: ConnectAnswer) {
        val manager = mUSBManager
        val usbDevice = manager?.deviceList?.values?.firstOrNull { it.vendorId == vendorId && it.productId == productId }
        if (manager == null || usbDevice == null) {
            answer.give(false)
            return
        }
        if (manager.hasPermission(usbDevice)) {
            if (mUsbDevice?.deviceId == usbDevice.deviceId) reportState(STATE_USB_CONNECTED) else useDevice(usbDevice)
            answerPermissionWaiters(pendingDevice?.deviceId == usbDevice.deviceId)
            answer.give(true)
            return
        }
        if (pendingDevice?.deviceId != usbDevice.deviceId) answerPermissionWaiters(false)
        closeConnection()
        Log.v(LOG_TAG, "Request for device: vendor_id: ${usbDevice.vendorId}, product_id: ${usbDevice.productId}")
        pendingDevice = usbDevice
        permissionWaiters.add(answer)
        reportState(STATE_USB_CONNECTING)
        manager.requestPermission(usbDevice, mPermissionIndent)
    }

    // The permission intent is created with EXTRA_PERMISSION_GRANTED = true, so only hasPermission tells the truth.
    private fun onPermissionAnswer(answeredDeviceId: Int?) {
        val usbDevice = pendingDevice ?: return
        if (answeredDeviceId != null && answeredDeviceId != usbDevice.deviceId) return
        if (mUSBManager?.hasPermission(usbDevice) == true) {
            Log.i(LOG_TAG, "Success get permission for device ${usbDevice.deviceId}, vendor_id: ${usbDevice.vendorId} product_id: ${usbDevice.productId}")
            useDevice(usbDevice)
            answerPermissionWaiters(true)
        } else {
            showToast(mContext?.getString(R.string.user_refuse_perm) + ": ${usbDevice.deviceName}", Toast.LENGTH_LONG)
            reportState(STATE_USB_NONE)
            answerPermissionWaiters(false)
        }
    }

    private fun onDeviceDetached(deviceId: Int) {
        if (pendingDevice?.deviceId == deviceId) {
            reportState(STATE_USB_NONE)
            answerPermissionWaiters(false)
        }
        if (mUsbDevice?.deviceId == deviceId) {
            showToast(mContext?.getString(R.string.device_off), Toast.LENGTH_LONG)
            closeConnection()
            reportState(STATE_USB_NONE)
        }
    }

    private fun useDevice(usbDevice: UsbDevice) {
        closeConnection()
        mUsbDevice = usbDevice
        reportState(STATE_USB_CONNECTED)
    }

    private fun closeConnection() {
        mUsbDeviceConnection?.let { connection ->
            mUsbInterface?.let { connection.releaseInterface(it) }
            connection.close()
        }
        mUsbInterface = null
        mEndPoint = null
        mInEndPoint = null
        mUsbDevice = null
        mUsbDeviceConnection = null
    }

    private fun reportState(state: Int) {
        mHandler?.obtainMessage(state)?.sendToTarget()
    }

    private fun answerPermissionWaiters(granted: Boolean) {
        val waiters = permissionWaiters.toList()
        permissionWaiters.clear()
        pendingDevice = null
        waiters.forEach { it.give(granted) }
    }

    private fun stopWaitingForPermission(expired: ConnectAnswer) {
        if (!permissionWaiters.remove(expired) || permissionWaiters.isNotEmpty()) return
        pendingDevice = null
        reportState(STATE_USB_NONE)
    }

    private fun showToast(text: String?, duration: Int) {
        mainThread.post { Toast.makeText(mContext, text, duration).show() }
    }

    private fun openConnection(): Boolean {
        val usbDevice = mUsbDevice
        if (usbDevice == null) {
            Log.e(LOG_TAG, "USB Device is not initialized")
            return false
        }
        val manager = mUSBManager
        if (manager == null) {
            Log.e(LOG_TAG, "USB Manager is not initialized")
            return false
        }
        if (mUsbDeviceConnection != null) {
            Log.i(LOG_TAG, "USB Connection already connected")
            return true
        }
        val usbInterface = usbDevice.getInterface(0)
        val endpoints = (0 until usbInterface.endpointCount)
            .map { usbInterface.getEndpoint(it) }
            .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        val outEndpoint = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
        if (outEndpoint == null) {
            Log.e(LOG_TAG, "USB device has no bulk OUT endpoint, so it is not a printer we can write to")
            return false
        }
        val usbDeviceConnection = manager.openDevice(usbDevice)
        if (usbDeviceConnection == null) {
            Log.e(LOG_TAG, "Failed to open USB Connection")
            return false
        }
        if (!usbDeviceConnection.claimInterface(usbInterface, true)) {
            usbDeviceConnection.close()
            Log.e(LOG_TAG, "Failed to retrieve usb connection")
            return false
        }
        showToast(mContext?.getString(R.string.connected_device), Toast.LENGTH_SHORT)
        mEndPoint = outEndpoint
        mInEndPoint = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
        mUsbInterface = usbInterface
        mUsbDeviceConnection = usbDeviceConnection
        return true
    }

    private fun writeJob(bytes: ByteArray, statusTimeoutMs: Int, printer: UsbPrinterIdentity?): Map<String, Any?> {
        val device = mUsbDevice
        if (printer != null && device != null && !printer.matches(device)) {
            return PrintOutcome.failed(
                "Connected to a different printer (${UsbPrinterIdentity(device.vendorId, device.productId).describe()}), " +
                    "not to ${printer.describe()}. Nothing was sent."
            )
        }
        if (!openConnection()) return PrintOutcome.failed("Could not open the USB printer. Check the cable and the USB permission.")
        val connection = mUsbDeviceConnection
            ?: return PrintOutcome.failed("USB printer connection closed before the job was sent")
        val endpoint = mEndPoint ?: return PrintOutcome.failed("USB printer has no endpoint to write to")
        val pieceSize = endpoint.maxPacketSize.coerceAtLeast(1)

        var offset = 0
        while (offset < bytes.size) {
            val length = minOf(pieceSize, bytes.size - offset)
            val written = connection.bulkTransfer(endpoint, bytes, offset, length, PIECE_TIMEOUT_MS)
            if (written != length) {
                Log.e(LOG_TAG, "USB write stopped at byte $offset of ${bytes.size}, bulkTransfer returned $written")
                return PrintOutcome.cutOff(
                    "USB printer stopped taking the job at byte ${offset + written.coerceAtLeast(0)} of ${bytes.size} (bulkTransfer returned $written)"
                )
            }
            offset += length
        }
        return PrintOutcome.delivered(readStatus(connection, endpoint, statusTimeoutMs))
    }

    private fun readStatus(connection: UsbDeviceConnection, out: UsbEndpoint, timeoutMs: Int): Int? {
        val input = mInEndPoint ?: return null
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val buffer = ByteArray(input.maxPacketSize.coerceAtLeast(1))
        while (true) {
            val left = millisUntil(deadline)
            if (left <= 0) return null
            if (connection.bulkTransfer(input, buffer, buffer.size, minOf(left, DRAIN_READ_TIMEOUT_MS)) <= 0) break
        }

        val request = PrintOutcome.OFFLINE_CAUSE_STATUS_REQUEST
        val writeLeft = millisUntil(deadline)
        if (writeLeft <= 0 || connection.bulkTransfer(out, request, request.size, writeLeft) != request.size) return null
        while (true) {
            val left = millisUntil(deadline)
            if (left <= 0) return null
            val read = connection.bulkTransfer(input, buffer, buffer.size, left)
            if (read < 0) return null
            for (i in 0 until read) {
                val value = buffer[i].toInt() and 0xFF
                if (PrintOutcome.isOfflineCauseStatus(value)) return value
            }
        }
    }

    private fun millisUntil(deadline: Long): Int =
        (deadline - SystemClock.elapsedRealtime()).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    companion object {
        @SuppressLint("StaticFieldLeak")
        private var mInstance: USBPrinterService? = null
        private const val LOG_TAG = "ESC POS Printer"
        private const val ACTION_USB_PERMISSION = "com.flutter_pos_printer.USB_PERMISSION"

        const val STATE_USB_NONE = 0
        const val STATE_USB_CONNECTING = 2
        const val STATE_USB_CONNECTED = 3

        private const val PIECE_TIMEOUT_MS = 60000
        private const val DRAIN_READ_TIMEOUT_MS = 20

        fun getInstance(handler: Handler): USBPrinterService {
            if (mInstance == null) {
                mInstance = USBPrinterService(handler)
            }
            return mInstance!!
        }
    }
}

private fun Intent.usbDevice(): UsbDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }
