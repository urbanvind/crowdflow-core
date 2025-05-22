package com.urbanvind.crowdflow.core

import android.util.Base64
import android.util.Log
import java.nio.charset.Charset
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import javax.crypto.Mac
import javax.crypto.SecretKey

class DataAnonymiser(
    private val secretKey: SecretKey
) {
    private val logTag = "DataAnonymiser"
    private val HMAC_SHA256 = "HmacSHA256"

    @Volatile
    private var salt: String = ""


    fun setSalt(newSalt: String) {
        salt = newSalt
        Log.d(logTag, "Salt has been reset.")
    }

    /**
     * Hashes the input using HMAC SHA-256 with the secret key and current salt.
     *
     * @param input The string to be hashed (e.g., device MAC address).
     * @return The Base64-encoded hashed string.
     */
    fun hashDeviceId(input: String): String {
        if (salt.isEmpty()) {
            Log.w(logTag, "Salt is not set. Hashing without salt.")
        }

        val saltedInput = salt + input

        return try {
            val mac = Mac.getInstance(HMAC_SHA256)
            mac.init(secretKey)
            val rawHmac = mac.doFinal(saltedInput.toByteArray(Charset.forName("UTF-8")))
            Base64.encodeToString(rawHmac, Base64.NO_WRAP)
        } catch (e: NoSuchAlgorithmException) {
            Log.e(logTag, "HMAC SHA256 algorithm not found.", e)
            ""
        } catch (e: InvalidKeyException) {
            Log.e(logTag, "Invalid secret key.", e)
            ""
        }
    }
}
