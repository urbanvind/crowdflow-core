# CrowdFlow Core SDK

Version: 0.0.1-alpha


## Features
- background BLE scanning with configurable parameters
- geo-restriction and schedule-based scanning rules
- data anonymisation and payload building
- periodic sync to backend via HTTP
- wake lock management and foreground service support
- React Native bridge event dispatching

## AndroidManifest requirements

The following permissions and components must be declared in your `AndroidManifest.xml`.

### Permissions
```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/>

<!-- Android 12+ BLE permissions -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" tools:targetApi="s"/>
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>
<!-- Android < 12 -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30"/>
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30"/>
```


## Usage

Integration is done by embedding a service that delegates all heavy lifting to `com.urbanvind.crowdflow.core.CrowdFlowCore`. The reference implementation of such a service is available in `ForegroundService.kt.example`.

This service should:
- instantiate `CrowdFlowCore` in `onCreate()`
- forward `onStartCommand`, `onDestroy`, and binding methods to `CrowdFlowCore`
- expose public methods such as `

### Service skeleton

```kotlin
class ForegroundService : Service() {

    companion object {
        private const val LOG_TAG = "ForegroundService"
    }

    inner class LocalBinder : Binder() {
        fun getService(): ForegroundService = this@ForegroundService
    }

    private val binder = LocalBinder()
    private lateinit var crowdFlowCore: CrowdFlowCore

    override fun onCreate() {
        super.onCreate()
        crowdFlowCore = CrowdFlowCore(this)
        crowdFlowCore.onCreate()
        Log.d(LOG_TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return crowdFlowCore.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        crowdFlowCore.onDestroy()
        Log.d(LOG_TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun onReactContextInitialized(context: ReactApplicationContext) {
        Log.d(LOG_TAG, "React context has been initialized (ForegroundService).")
        crowdFlowCore.onReactContextInitialized(context)
    }

    fun setReactContext(context: ReactApplicationContext) {
        crowdFlowCore.setReactContext(context)
    }
}
```

### Public API
```kotlin
fun startScan() {
    crowdFlowCore.startScan()
}

fun stopScan() {
    crowdFlowCore.stopScan()
}

fun isScanActive(): Boolean = crowdFlowCore.isScanActive()
```

### Key configuration helpers

```kotlin
fun setServer(value: String) {
    crowdFlowCore.setServer(value)
}

fun setIsScheduledScanEnabled(enabled: Boolean) {
    crowdFlowCore.setIsScheduledScanEnabled(enabled)
}

fun setIsGeoRestrictionEnabled(enabled: Boolean) {
    crowdFlowCore.setIsGeoRestrictionEnabled(enabled)
}
```


## Changelog

### 0.0.1-alpha – 22 May 2025
- Initial alpha release. May contain breaking changes in future versions.
