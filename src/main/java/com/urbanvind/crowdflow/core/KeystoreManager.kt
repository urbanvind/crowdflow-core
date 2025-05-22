package com.urbanvind.crowdflow.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

object KeystoreManager {
    private const val KEYSTORE_ALIAS = "DataAnonymiserKey"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /**
     * Retrieves the secret key from the Android Keystore, generating it if it doesn't exist.
     *
     * @return The SecretKey object for HMAC operations.
     */
    fun getSecretKey(): SecretKey {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

            if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                // Retrieve the existing key
                val secretKeyEntry =
                    keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
                if (secretKeyEntry?.secretKey != null) {
                    Log.d("KeystoreManager", "Existing key with alias $KEYSTORE_ALIAS retrieved.")
                    return secretKeyEntry.secretKey
                } else {
                    Log.w(
                        "KeystoreManager",
                        "Key exists but could not be retrieved. Generating a new key."
                    )
                }
            }

            // Generate and return a new key if it doesn't exist or couldn't be retrieved
            return generateSecretKey()
        } catch (e: Exception) {
            Log.e("KeystoreManager", "Error retrieving or generating secret key: ${e.message}")
            throw RuntimeException("Failed to retrieve or generate secret key", e)
        }
    }

    /**
     * Generates a new HMAC SHA-256 secret key and stores it in the Android Keystore.
     *
     * @return The newly generated SecretKey.
     */
    private fun generateSecretKey(): SecretKey {
        try {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
                ANDROID_KEYSTORE
            )
            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setKeySize(256) // 256 bits for HMAC SHA-256
                .build()
            keyGenerator.init(keyGenParameterSpec)
            val secretKey = keyGenerator.generateKey()
            Log.d("KeystoreManager", "New HMAC SHA-256 secret key generated.")
            return secretKey
        } catch (e: Exception) {
            Log.e("KeystoreManager", "Error generating secret key: ${e.message}")
            throw RuntimeException("Failed to generate secret key", e)
        }
    }
}
