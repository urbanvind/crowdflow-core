package com.urbanvind.crowdflow.core

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

class DataPayloadBuilder(
    private val context: Context,
    private val sharedPreferencesManager: SharedPreferencesManager,
    private val dataAnonymiser: DataAnonymiser
) {
    private val logTag = "DataPayloadBuilder"
    private val bleDataParser = BleDataParser(context)

    @SuppressLint("MissingPermission")
    fun createDataPayload(
        result: ScanResult,
        observedCount: Int,
        latestLatitude: Double,
        latestLongitude: Double,
        latestGpsFixTime: Long
    ): Map<String, Any> {
        val txPowerLevel =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) result.txPower else null
        val isConnectable =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) result.isConnectable else false

        // Anonymise the device_id using the current salt
        val originalDeviceId = result.device.address ?: ""
        val anonymisedDeviceId = if (originalDeviceId.isNotEmpty()) {
            dataAnonymiser.hashDeviceId(originalDeviceId)
        } else {
            "Unknown"
        }

        // Get raw_data
        val rawDataHex =
            result.scanRecord?.bytes?.joinToString("") { String.format("%02x", it) } ?: ""

        // Parse the raw_data to get BLE fields
        val bleData = bleDataParser.parse(rawDataHex)

        // Build the base payload
        val payload = mutableMapOf<String, Any>(
            "device_id" to anonymisedDeviceId,
            "device_name" to (result.device.name ?: ""),
            "observed_count" to observedCount,
            "rssi" to result.rssi,
            "manufacturer_data" to (result.scanRecord?.manufacturerSpecificData?.toString() ?: ""),
            "serviceData" to (result.scanRecord?.serviceData ?: emptyMap<Int, ByteArray>()),
            "serviceUUIDs" to (result.scanRecord?.serviceUuids?.map { it.uuid.toString() }
                ?: emptyList<String>()),
            "txPowerLevel" to (txPowerLevel ?: "N/A"),
            "localName" to (result.scanRecord?.deviceName ?: ""),
            "isConnectable" to isConnectable,
            "lat" to latestLatitude,
            "lon" to latestLongitude,
            "gpsFix" to latestGpsFixTime,
            "timestamp" to System.currentTimeMillis(),
            "scan_type" to "ble",
        )

        // Include parsed BLE data
        for ((key, value) in bleData) {
            payload[key] = value
        }

        // Conditionally add raw_data and device_id_raw if data privacy is disabled
        if (!sharedPreferencesManager.isDataPrivacyEnabled) {
            payload["raw_data"] = rawDataHex
            payload["device_id_raw"] = originalDeviceId
        }

        return payload
    }

    fun buildSyncData(
        devicesData: List<Map<String, Any>>,
        classicDevices: List<Map<String, Any>>,
        latestLatitude: Double,
        latestLongitude: Double,
        latestGpsFixTime: Long
    ): Map<String, Any> {
        Log.d(logTag, "Building sync data with:")
        Log.d(logTag, "userUUID: ${sharedPreferencesManager.userUUID}")
        Log.d(logTag, "vehicleType: ${sharedPreferencesManager.vehicleType}")
        Log.d(logTag, "calibrationTripName: ${sharedPreferencesManager.calibrationTripName}")

        return mapOf(
            "uuid" to sharedPreferencesManager.userUUID,
            "calibrationTripName" to sharedPreferencesManager.calibrationTripName,
            "vehicleType" to sharedPreferencesManager.vehicleType,
            "requireEstimationResult" to sharedPreferencesManager.requireEstimationResult,
            "periods" to mapOf(
                System.currentTimeMillis().toString() to mapOf(
                    "context" to mapOf(
                        "lat" to latestLatitude,
                        "lon" to latestLongitude,
                        "gpsFix" to latestGpsFixTime,
                        "vehicleType" to sharedPreferencesManager.vehicleType,
                        "calibrationCount" to sharedPreferencesManager.calibrationCount,
                        "calibrationBoarding" to sharedPreferencesManager.calibrationBoarding,
                        "calibrationAlighting" to sharedPreferencesManager.calibrationAlighting,
                        "calibrationBoardingTotal" to sharedPreferencesManager.calibrationBoardingTotal,
                        "calibrationAlightingTotal" to sharedPreferencesManager.calibrationAlightingTotal,
                        "calibrationTripName" to sharedPreferencesManager.calibrationTripName,
                    ),
                    "devices" to devicesData,
                    "classic_devices" to classicDevices,
                )
            ),
            "appVersionName" to getAppVersionName(context),
        )
    }

    private fun getAppVersionName(context: Context): String {
        return try {
            val packageManager = context.packageManager
            val packageName = context.packageName
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionName ?: "Unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "Unknown"
        }
    }

    fun createClassicPayload(
        intent: Intent,
        observedCount: Int,
        latestLatitude: Double,
        latestLongitude: Double,
        latestGpsFixTime: Long
    ): Map<String, Any> {
        val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)!!
        val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
        var rssi: Short? = null
        if (intent.hasExtra(BluetoothDevice.EXTRA_RSSI)) {
            rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, 0)
        }
        val deviceClass: BluetoothClass? = intent.getParcelableExtra(BluetoothDevice.EXTRA_CLASS)

        // Anonymise the device_id using the current salt
        val originalDeviceId = device.address ?: ""
        val anonymisedDeviceId = if (originalDeviceId.isNotEmpty()) {
            dataAnonymiser.hashDeviceId(originalDeviceId)
        } else {
            "Unknown"
        }

        // Build the base payload
        val payload = mutableMapOf<String, Any>(
            "device_id" to anonymisedDeviceId,
            "device_name" to (name ?: ""),
            "observed_count" to observedCount,
            "lat" to latestLatitude,
            "lon" to latestLongitude,
            "gpsFix" to latestGpsFixTime,
            "timestamp" to System.currentTimeMillis(),
            "scan_type" to "classic",
        )
        if (rssi != null) {
            payload["rssi"] = rssi
        }
        if (deviceClass != null) {
            payload["device_class"] = deviceClass.toString()
        }

        // Conditionally add raw_data and device_id_raw if data privacy is disabled
        if (!sharedPreferencesManager.isDataPrivacyEnabled) {
            payload["device_id_raw"] = originalDeviceId
            Log.d(logTag, "Data privacy is disabled. Including raw_data and device_id_raw.")
        } else {
            Log.d(logTag, "Data privacy is enabled. Excluding raw_data and device_id_raw.")
        }

        return payload
    }
}
