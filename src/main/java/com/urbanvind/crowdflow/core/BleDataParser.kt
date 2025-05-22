package com.urbanvind.crowdflow.core

import android.content.Context
import android.util.Base64
import android.util.Log
import java.util.Locale

class BleDataParser(private val context: Context) {

    private val logTag = "BleDataParser"

    private val companyIdentifiers: Map<Int, String> get() = BleMetadataProvider.companyIdentifiers
    private val adTypes: Map<Int, String> get() = BleMetadataProvider.adTypes
    private val commonCompanyIds = setOf(
        76, 117, 6, 257, 301, 224, 1447, 101, 135,
        89, 887, 911, 103, 147, 137, 3, 2000, 637, 343
    )

    fun parse(rawDataHex: String): Map<String, String> {
        // Ensure that data is loaded before parsing
        if (companyIdentifiers.isEmpty() || adTypes.isEmpty()) {
            Log.e(logTag, "Data not loaded yet.")
            return emptyMap()
        }

        // Check if rawDataHex is base64 encoded
        var rawDataHexProcessed = rawDataHex
        if (rawDataHex.contains("=")) {
            rawDataHexProcessed = base64ToHex(rawDataHex) ?: ""
        }

        val bleCompany = mutableSetOf<String>()
        val bleDeviceType = mutableSetOf<String>()
        val bleFields = mutableMapOf<String, Any?>()
        val manufacturerDataList = mutableListOf<String>()

        val rawDataBytes = hexStringToByteArray(rawDataHexProcessed)
        var currentPos = 0

        while (currentPos < rawDataBytes.size) {
            if (currentPos >= rawDataBytes.size) break
            val length = rawDataBytes[currentPos].toInt() and 0xFF
            if (length == 0) {
                break
            }
            currentPos++
            if (currentPos >= rawDataBytes.size) break
            val adType = rawDataBytes[currentPos].toInt() and 0xFF
            currentPos++
            val dataLength = length - 1

            if (currentPos + dataLength > rawDataBytes.size) {
                break // Malformed packet
            }
            val adData = rawDataBytes.copyOfRange(currentPos, currentPos + dataLength)

            // Get the AD type name from adTypes map
            val adTypeName = adTypes[adType] ?: "unknown"
            val fieldName = "ble_" + adTypeName.replace(" ", "_").replace("-", "")
                .lowercase(Locale.getDefault())

            val dataHexString = bytesToHexString(adData)

            when (adType) {
                0xFF -> { // Manufacturer Specific Data
                    if (adData.size >= 2) {
                        manufacturerDataList.add(dataHexString)
                        bleFields[fieldName] = dataHexString
                    }
                }

                else -> {
                    // Store other AD types
                    bleFields[fieldName] = dataHexString
                }
            }

            currentPos += dataLength
        }

        // Process manufacturer data to extract ble_company and ble_device_type
        for (hexString in manufacturerDataList) {
            val companyName = hexToCompany(hexString)
            bleCompany.add(companyName)
            val deviceType = hexToDeviceType(hexString)
            bleDeviceType.add(deviceType)
        }

        // Decode local name fields
        if (bleFields.containsKey("ble_shortened_local_name")) {
            val hexString = bleFields["ble_shortened_local_name"] as? String
            bleFields["ble_shortened_local_name"] = decodeHex(hexString)
        }

        if (bleFields.containsKey("ble_complete_local_name")) {
            val hexString = bleFields["ble_complete_local_name"] as? String
            bleFields["ble_complete_local_name"] = decodeHex(hexString)
        }

        // Add ble_company and ble_device_type to bleFields
        bleFields["ble_company"] = bleCompany.joinToString(",")
        bleFields["ble_device_type"] = bleDeviceType.joinToString(",")

        // Convert all values to strings, handling nulls
        return bleFields.mapValues { it.value?.toString() ?: "" }
    }

    private fun hexToCompany(hexString: String): String {
        if (hexString.length < 4) {
            return "unknown"
        }

        val companyId = Integer.parseInt(
            hexString.substring(2, 4) + hexString.substring(0, 2),
            16
        )

        return if (commonCompanyIds.contains(companyId)) {
            companyIdentifiers[companyId] ?: "unknown"
        } else {
            "other"
        }
    }

    private fun hexToDeviceType(hexString: String): String {
        if (hexString.length < 6) {
            return "unknown"
        }

        val companyId = Integer.parseInt(
            hexString.substring(2, 4) + hexString.substring(0, 2),
            16
        )
        val param = Integer.parseInt(hexString.substring(4, 6), 16)

        return when (companyId) {
            76 -> { // Apple
                if (param == 0x12 || param == 0x07) {
                    "apple_findmy"
                } else {
                    "apple_$param"
                }
            }

            117 -> "samsung_$param"
            6 -> "microsoft_$param"
            257 -> "speaker_$param"
            else -> "${companyId}_$param"
        }
    }

    private fun decodeHex(hexString: String?): String? {
        if (hexString == null) return null
        return try {
            val bytes = hexStringToByteArray(hexString)
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            "Decoding Error: ${e.message}"
        }
    }

    private fun base64ToHex(base64String: String): String? {
        return try {
            val decodedBytes = Base64.decode(base64String, Base64.DEFAULT)
            bytesToHexString(decodedBytes)
        } catch (e: Exception) {
            Log.e(logTag, "Decoding Error: ${e.message}")
            null
        }
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len - 1) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4)
                    + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun bytesToHexString(bytes: ByteArray): String {
        val hexArray = "0123456789abcdef".toCharArray()
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v: Int = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = hexArray[v ushr 4]
            hexChars[j * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }
}

