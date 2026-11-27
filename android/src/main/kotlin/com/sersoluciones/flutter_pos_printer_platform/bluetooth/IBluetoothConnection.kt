package com.sersoluciones.flutter_pos_printer_platform.bluetooth

import com.sersoluciones.flutter_pos_printer_platform.ConnectAnswer

interface IBluetoothConnection {
    val state: Int
    val address: String?
    fun connect(address: String, answer: ConnectAnswer)
    fun stop()
    fun send(bytes: ByteArray, confirmTimeoutMs: Long, writeTimeoutMs: Long): Map<String, Any?>
}
