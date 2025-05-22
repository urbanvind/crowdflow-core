package com.urbanvind.crowdflow.core

import android.util.Base64
import java.security.SecureRandom

object SaltManager {
    private const val SALT_LENGTH = 16 // 16 bytes = 128 bits

    /**
     * Generates a new random salt.
     *
     * @return Base64-encoded salt string.
     */
    fun generateNewSalt(): String {
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)
        return Base64.encodeToString(salt, Base64.NO_WRAP)
    }
}
