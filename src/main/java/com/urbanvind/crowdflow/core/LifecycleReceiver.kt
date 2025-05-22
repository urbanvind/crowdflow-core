package com.urbanvind.crowdflow.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class LifecycleReceiver : BroadcastReceiver() {
    private val logTag = "LifecycleReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> {
                Log.d(logTag, "Received lifecycle action: ${intent.action}")
            }
        }
    }
}
