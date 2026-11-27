package com.sersoluciones.flutter_pos_printer_platform

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityCompat.startActivityForResult
import com.sersoluciones.flutter_pos_printer_platform.bluetooth.BluetoothConnection
import com.sersoluciones.flutter_pos_printer_platform.bluetooth.BluetoothConstants
import com.sersoluciones.flutter_pos_printer_platform.bluetooth.BluetoothPrinterIdentity
import com.sersoluciones.flutter_pos_printer_platform.bluetooth.BluetoothService
import com.sersoluciones.flutter_pos_printer_platform.bluetooth.BluetoothService.Companion.TAG
import com.sersoluciones.flutter_pos_printer_platform.usb.USBPrinterService
import com.sersoluciones.flutter_pos_printer_platform.usb.UsbPrinterIdentity
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry

class FlutterPosPrinterPlatformPlugin : FlutterPlugin, MethodCallHandler, PluginRegistry.RequestPermissionsResultListener,
    PluginRegistry.ActivityResultListener,
    ActivityAware {
    private lateinit var channel: MethodChannel

    private var messageChannel: EventChannel? = null
    private var messageUSBChannel: EventChannel? = null
    private var eventSink: EventChannel.EventSink? = null
    private var eventUSBSink: EventChannel.EventSink? = null
    private var context: Context? = null
    private var currentActivity: Activity? = null
    private var requestPermissionBT: Boolean = false
    private var isBle: Boolean = false
    private var isScan: Boolean = false
    lateinit var adapter: USBPrinterService
    private lateinit var bluetoothService: BluetoothService


    private val usbHandler = object : Handler(Looper.getMainLooper()) {

        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            when (msg.what) {

                USBPrinterService.STATE_USB_CONNECTED -> {
                    eventUSBSink?.success(2)
                }
                USBPrinterService.STATE_USB_CONNECTING -> {
                    eventUSBSink?.success(1)
                }
                USBPrinterService.STATE_USB_NONE -> {
                    eventUSBSink?.success(0)
                }
            }
        }
    }


    private val bluetoothHandler = object : Handler(Looper.getMainLooper()) {

        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            when (msg.what) {
                BluetoothConstants.MESSAGE_STATE_CHANGE -> {
                    when (msg.arg1) {
                        BluetoothConstants.STATE_CONNECTED -> {
                            Log.w(TAG, " -------------------------- connection BT STATE_CONNECTED ")
                            eventSink?.success(2)
                            bluetoothService.removeReconnectHandlers()
                        }
                        BluetoothConstants.STATE_CONNECTING -> {
                            Log.w(TAG, " -------------------------- connection BT STATE_CONNECTING ")
                            eventSink?.success(1)
                        }
                        BluetoothConstants.STATE_NONE -> {
                            Log.w(TAG, " -------------------------- connection BT STATE_NONE ")
                            eventSink?.success(0)
                            bluetoothService.autoConnectBt()
                        }
                    }
                }
                BluetoothConstants.MESSAGE_READ -> {
                    val readBuf = msg.obj as ByteArray
                    var readMessage = String(readBuf, 0, msg.arg1)
                    readMessage = readMessage.trim { it <= ' ' }
                    Log.d("bluetooth", "receive bt: $readMessage")
                }
                BluetoothConstants.MESSAGE_DEVICE_NAME -> {
                    val deviceName = msg.data.getString(BluetoothConstants.DEVICE_NAME)
                    Log.d("bluetooth", " ------------- deviceName $deviceName -----------------")
                }

                BluetoothConstants.MESSAGE_TOAST -> {
                    val bundle = msg.data
                    bundle?.getInt(BluetoothConnection.TOAST)?.let {
                        Toast.makeText(context, context!!.getString(it), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        messageChannel?.setStreamHandler(null)
        messageUSBChannel?.setStreamHandler(null)

        messageChannel = null
        messageUSBChannel = null

        if (this::bluetoothService.isInitialized) {
            bluetoothService.setHandler(null)
        }

        adapter.setHandler(null)
    }

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, methodChannel)
        channel.setMethodCallHandler(this)

        messageChannel = EventChannel(flutterPluginBinding.binaryMessenger, eventChannelBT)
        messageChannel?.setStreamHandler(object : EventChannel.StreamHandler {

            override fun onListen(p0: Any?, sink: EventChannel.EventSink) {
                eventSink = sink
            }

            override fun onCancel(p0: Any?) {
                eventSink = null
            }
        })

        messageUSBChannel = EventChannel(flutterPluginBinding.binaryMessenger, eventChannelUSB)
        messageUSBChannel?.setStreamHandler(object : EventChannel.StreamHandler {

            override fun onListen(p0: Any?, sink: EventChannel.EventSink) {
                eventUSBSink = sink
            }

            override fun onCancel(p0: Any?) {
                eventUSBSink = null
            }
        })

        context = flutterPluginBinding.applicationContext
        adapter = USBPrinterService.getInstance(usbHandler)
        adapter.init(context)

        bluetoothService = BluetoothService.getInstance(bluetoothHandler)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        if (!this::bluetoothService.isInitialized) {
            bluetoothService = BluetoothService.getInstance(bluetoothHandler)
        }

        isScan = false
        when {
            call.method.equals("getBluetoothList") -> {
                isBle = false
                isScan = true
                if (verifyIsBluetoothIsOn()) {
                    bluetoothService.cleanHandlerBtBle()
                    bluetoothService.scanBluDevice(channel)
                }
                result.success(null)
            }
            call.method.equals("getBluetoothLeList") -> {
                isBle = true
                isScan = true
                if (verifyIsBluetoothIsOn()) {
                    bluetoothService.scanBleDevice(channel)
                }
                result.success(null)
            }

            call.method.equals("onStartConnection") -> {
                val address: String? = call.argument("address")
                val isBle: Boolean = call.argument("isBle") ?: false
                val autoConnect: Boolean = call.argument("autoConnect") ?: false
                val connectTimeoutMs = (call.argument<Number>("connectTimeoutMs") ?: DEFAULT_CONNECT_TIMEOUT_MS).toLong()
                val appContext = context
                if (appContext != null && verifyIsBluetoothIsOn()) {
                    bluetoothService.setHandler(bluetoothHandler)
                    bluetoothService.onStartConnection(appContext, address, result, isBle, autoConnect, connectTimeoutMs)
                } else {
                    result.success(false)
                }
            }

            call.method.equals("disconnect") -> {
                bluetoothService.setHandler(bluetoothHandler)
                bluetoothService.bluetoothDisconnect { stopped -> result.success(stopped) }
            }

            call.method.equals("sendDataByte") -> {
                if (verifyIsBluetoothIsOn()) {
                    bluetoothService.setHandler(bluetoothHandler)
                    val bytes = bytesArgument(call)
                    val confirmTimeoutMs = (call.argument<Number>("confirmTimeoutMs") ?: 30000).toLong()
                    val writeTimeoutMs = (call.argument<Number>("writeTimeoutMs") ?: 60000).toLong()
                    val printer = call.argument<String>("address")?.let { address ->
                        BluetoothPrinterIdentity(address, call.argument<Boolean>("isBle") ?: false)
                    }
                    bluetoothService.sendDataByte(bytes, confirmTimeoutMs, writeTimeoutMs, printer) { reply -> result.success(reply) }
                } else {
                    result.success(PrintOutcome.failed("Bluetooth is off or the app has no Bluetooth permission"))
                }
            }
            call.method.equals("getList") -> {
                bluetoothService.cleanHandlerBtBle()
                getUSBDeviceList(result)
            }
            call.method.equals("connectPrinter") -> {
                val vendor: Int? = call.argument("vendor")
                val product: Int? = call.argument("product")
                val permissionTimeoutMs = (call.argument<Number>("permissionTimeoutMs") ?: DEFAULT_PERMISSION_TIMEOUT_MS).toLong()
                connectPrinter(vendor, product, permissionTimeoutMs, result)
            }
            call.method.equals("close") -> {
                closeConn(result)
            }
            call.method.equals("printBytes") -> {
                val bytes = bytesArgument(call)
                val statusTimeoutMs = (call.argument<Number>("statusTimeoutMs") ?: 2000).toInt()
                val vendor: Int? = call.argument("vendor")
                val product: Int? = call.argument("product")
                val printer = if (vendor != null && product != null) UsbPrinterIdentity(vendor, product) else null
                adapter.setHandler(usbHandler)
                adapter.printBytes(bytes, statusTimeoutMs, printer) { reply -> result.success(reply) }
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    private fun verifyIsBluetoothIsOn(): Boolean {
        if (checkPermissions()) {
            if (!bluetoothService.mBluetoothAdapter.isEnabled) {
                if (requestPermissionBT) return false
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                currentActivity?.let { startActivityForResult(it, enableBtIntent, PERMISSION_ENABLE_BLUETOOTH, null) }
                requestPermissionBT = true
                return false
            }
        } else return false
        return true
    }

    private fun getUSBDeviceList(result: Result) {
        val usbDevices: List<UsbDevice> = adapter.deviceList
        val list = ArrayList<HashMap<*, *>>()
        for (usbDevice in usbDevices) {
            val deviceMap: HashMap<String?, String?> = HashMap()
            deviceMap["name"] = usbDevice.deviceName
            deviceMap["manufacturer"] = usbDevice.manufacturerName
            deviceMap["product"] = usbDevice.productName
            deviceMap["deviceId"] = usbDevice.deviceId.toString()
            deviceMap["vendorId"] = usbDevice.vendorId.toString()
            deviceMap["productId"] = usbDevice.productId.toString()
            list.add(deviceMap)
        }
        result.success(list)
    }

    private fun connectPrinter(vendorId: Int?, productId: Int?, permissionTimeoutMs: Long, result: Result) {
        if (vendorId == null || productId == null) return result.success(false)
        adapter.setHandler(usbHandler)
        adapter.selectDevice(vendorId, productId, permissionTimeoutMs) { granted -> result.success(granted) }
    }

    private fun closeConn(result: Result) {
        adapter.setHandler(usbHandler)
        adapter.close { result.success(true) }
    }

    private fun bytesArgument(call: MethodCall): ByteArray = when (val values = call.argument<Any>("bytes")) {
        is ByteArray -> values
        is List<*> -> ByteArray(values.size) { i -> (values[i] as Number).toByte() }
        else -> ByteArray(0)
    }

    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (!hasPermissions(context, *permissions.toTypedArray())) {
            currentActivity?.let { ActivityCompat.requestPermissions(it, permissions.toTypedArray(), PERMISSION_ALL) }
            return false
        }
        return true
    }

    private fun hasPermissions(context: Context?, vararg permissions: String?): Boolean {
        if (context != null) {
            for (permission in permissions) {
                if (ActivityCompat.checkSelfPermission(context, permission!!) != PackageManager.PERMISSION_GRANTED) {
                    return false
                }
            }
        }
        return true
    }


    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        currentActivity = binding.activity
        binding.addRequestPermissionsResultListener(this)
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        currentActivity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        currentActivity = binding.activity
        binding.addRequestPermissionsResultListener(this)
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivity() {
        currentActivity = null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        when (requestCode) {
            PERMISSION_ENABLE_BLUETOOTH -> {
                requestPermissionBT = false

                Log.d(TAG, "PERMISSION_ENABLE_BLUETOOTH PERMISSION_GRANTED resultCode $resultCode")
                if (resultCode == Activity.RESULT_OK)
                    if (isScan) {
                        if (!this::bluetoothService.isInitialized) {
                            bluetoothService = BluetoothService.getInstance(bluetoothHandler)
                        }

                        if (isBle) bluetoothService.scanBleDevice(channel) else bluetoothService.scanBluDevice(channel)
                    }

            }
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        Log.d(TAG, " --- requestCode $requestCode")
        when (requestCode) {

            PERMISSION_ALL -> {
                var grant = true
                grantResults.forEach { permission ->

                    val permissionGranted = grantResults.isNotEmpty() &&
                            permission == PackageManager.PERMISSION_GRANTED
                    Log.d(TAG, " --- requestCode $requestCode permission $permission permissionGranted $permissionGranted")
                    if (!permissionGranted) grant = false

                }
                if (!grant) {
                    Toast.makeText(context, R.string.not_permissions, Toast.LENGTH_LONG).show()
                } else {
                    if (verifyIsBluetoothIsOn() && isScan) {
                        if (!this::bluetoothService.isInitialized) {
                            bluetoothService = BluetoothService.getInstance(bluetoothHandler)
                        }
                        
                        if (isBle) bluetoothService.scanBleDevice(channel) else bluetoothService.scanBluDevice(channel)
                    }
                }
                return true
            }
        }
        return false
    }

    companion object {
        const val PERMISSION_ALL = 1
        const val PERMISSION_ENABLE_BLUETOOTH = 999
        const val methodChannel = "com.sersoluciones.flutter_pos_printer_platform"
        const val eventChannelBT = "com.sersoluciones.flutter_pos_printer_platform/bt_state"
        const val eventChannelUSB = "com.sersoluciones.flutter_pos_printer_platform/usb_state"
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 20000L
        private const val DEFAULT_PERMISSION_TIMEOUT_MS = 60000L
    }
}
