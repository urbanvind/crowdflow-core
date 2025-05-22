package com.urbanvind.crowdflow.core

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log

class ServiceChecker(private val context: Context, private val eventDispatcher: EventDispatcher?) {

    private val logTag = "ServiceChecker"

    fun ensureBluetoothIsEnabled(): Boolean {
        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        return if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.d(logTag, "Bluetooth is not enabled")
            eventDispatcher?.sendEvent("bluetoothNotEnabled", 1)
            false
        } else {
            Log.d(logTag, "Bluetooth is enabled")
            true
        }
    }
}
