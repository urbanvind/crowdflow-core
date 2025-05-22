# CrowdFlow Core SDK

Version: 0.0.1-alpha

## Features
- background BLE scanning with configurable parameters
- geo-restriction and schedule-based scanning rules
- data anonymisation and payload building
- periodic sync to backend via HTTP
- wake lock management and foreground service support
- react Native bridge event dispatching

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

### 1. Implement a ForegroundService
Copy or adapt the example `ForegroundService` from the sample file. It should:
- instantiate `CrowdFlowCore` in `onCreate()`
- forward `onStartCommand`, `onDestroy`, and binding methods to `CrowdFlowCore`
- expose public methods such as `startScan()`, `stopScan()`, and configuration setters.

### 2. Start and bind the service
```kotlin
val intent = Intent(context, ForegroundService::class.java)
ContextCompat.startForegroundService(context, intent)
```

### 3. Call API methods
```kotlin
service.startScan()
service.stopScan()
service.setServer("https:prod.urbanvind.com")
```

### 4. React Native integration
If you have a React Native host app, notify the service when the RN context is ready:
```kotlin
foregroundService.onReactContextInitialized(reactContext)
```

## Changelog

### 0.0.1-alpha – 22 May 2025
- Initial alpha release. May contain breaking changes in future versions.
