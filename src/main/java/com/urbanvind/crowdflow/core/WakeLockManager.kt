package com.urbanvind.crowdflow.core

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import android.util.Log

class WakeLockManager(private val context: Context) {

    private val logTag = "WakeLockManager"
    private var wakeLock: PowerManager.WakeLock? = null

    @SuppressLint("WakelockTimeout")
    fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ForegroundService::WakeLock"
            )
            wakeLock?.setReferenceCounted(false)
        }

        wakeLock?.let {
            if (!it.isHeld) {
                it.acquire()
                Log.d(logTag, "Wake lock acquired")
            } else {
                Log.d(logTag, "Wake lock is already held")
            }
        }
    }

    fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(logTag, "Wake lock released")
            } else {
                Log.d(logTag, "Wake lock is not held")
            }
        }
    }

    fun isWakeLockHeld(): Boolean {
        return wakeLock?.isHeld ?: false
    }
}
