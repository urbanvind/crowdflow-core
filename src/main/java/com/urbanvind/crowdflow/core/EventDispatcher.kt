package com.urbanvind.crowdflow.core

import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.modules.core.DeviceEventManagerModule

class EventDispatcher(val reactContext: ReactApplicationContext?) {

    private val logTag = "EventDispatcher"

    fun sendEvent(eventName: String, value: Int) {
        if (reactContext == null) {
            Log.w(logTag, "No React context. Skipping event: $eventName")
            return
        }

        // Check if the JS bridge is ready. If not, skip to avoid IllegalStateException.
        if (!reactContext.hasActiveCatalystInstance()) {
            Log.w(logTag, "React instance not ready. Skipping event: $eventName")
            return
        }

        val params = Arguments.createMap()
        params.putInt("value", value)
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }

    fun sendStringEvent(eventName: String, value: String) {
        if (reactContext == null) {
            Log.w(logTag, "No React context. Skipping event: $eventName")
            return
        }

        if (!reactContext.hasActiveCatalystInstance()) {
            Log.w(logTag, "React instance not ready. Skipping event: $eventName")
            return
        }

        val params = Arguments.createMap()
        params.putString("value", value)
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }
}
