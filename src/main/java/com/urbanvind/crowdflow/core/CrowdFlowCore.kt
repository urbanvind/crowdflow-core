package com.urbanvind.crowdflow.core

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.facebook.react.bridge.ReactApplicationContext
import kotlinx.coroutines.*

class CrowdFlowCore(private val service: Service) : DataSyncManager.SyncResultListener {

    companion object {
        private const val LOG_TAG = "CrowdFlowCore"
        private const val SYNC_PERIOD_MS = 10000L
        private const val FOREGROUND_NOTIFICATION_ID = 1
        private const val MIN_DEVICES_FOR_RESTART = 5
        private const val MIN_SCAN_DURATION_BEFORE_RESTART_CHECK_MS = 5 * 60 * 1000L
        private const val INITIAL_LOCATION_TIMEOUT_MS = 30000L
    }

    private val prefs by lazy {
        service.getSharedPreferences("ForegroundServicePrefs", Context.MODE_PRIVATE)
    }

    private lateinit var sharedPreferencesManager: SharedPreferencesManager
    private lateinit var notificationHelper: NotificationManager
    private lateinit var serviceChecker: ServiceChecker
    private lateinit var dataPayloadBuilder: DataPayloadBuilder
    private lateinit var locationManager: LocationManager
    private lateinit var dataAnonymiser: DataAnonymiser
    private lateinit var wakeLockManager: WakeLockManager
    private lateinit var dataSyncManager: DataSyncManager
    private lateinit var jsonCacheManager: JsonCacheManager
    private lateinit var bleScannerManager: BleScannerManager
    private var eventDispatcher: EventDispatcher? = null

    private lateinit var lifecycleReceiver: LifecycleReceiver

    private var latestLatitude: Double = 0.0
    private var latestLongitude: Double = 0.0
    private var latestGpsFixTime: Long = 0L
    private var scanStartedTimestamp: Long = 0L
    private var lastSaltRefreshTime: Long = 0L

    private lateinit var serviceJob: Job
    private lateinit var serviceScope: CoroutineScope

    private lateinit var syncRunnable: Runnable
    private val mainHandler = Handler(Looper.getMainLooper())

    private val locationTimeoutRunnable = Runnable {
        if (latestGpsFixTime == 0L && isScanActive()) {
            Log.w(LOG_TAG, "Timed out waiting for initial location. Stopping scan.")
            eventDispatcher?.sendEvent("geoLocationUnavailable", 1)
            stopScan()
        }
    }

    fun onCreate() {
        if (eventDispatcher == null) {
            val fallbackContext = service.applicationContext
            eventDispatcher = EventDispatcher(ReactApplicationContext(fallbackContext))
        }
        initialiseManagers()
        initialiseServices()
        initialiseReceivers()
        initialiseCoroutines()

        bleScannerManager = BleScannerManager(
            service.applicationContext, eventDispatcher!!, serviceChecker, jsonCacheManager
        )
        Log.d(LOG_TAG, "CrowdFlowCore created and managers initialized.")
    }

    fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureActiveServiceScope()
        handleIntentAction(intent)
        return Service.START_STICKY
    }

    fun onDestroy() {
        performCleanup()
        Log.d(LOG_TAG, "CrowdFlowCore onDestroy cleanup finished.")
    }

    fun setReactContext(context: ReactApplicationContext) {
        eventDispatcher = EventDispatcher(context)
        if (::bleScannerManager.isInitialized) {
            bleScannerManager.setEventDispatcher(eventDispatcher!!)
        }
        if (::dataSyncManager.isInitialized && eventDispatcher != null) {
            dataSyncManager.setEventDispatcher(eventDispatcher!!)
        }
        if (::serviceChecker.isInitialized && eventDispatcher != null) {
            serviceChecker = ServiceChecker(service, eventDispatcher!!)
        }
    }

    fun onReactContextInitialized(context: ReactApplicationContext) {
        Log.d(LOG_TAG, "React context has been initialized (CrowdFlowCore).")
        setReactContext(context)
        ensureActiveServiceScope()
        serviceScope.launch(Dispatchers.Default) {
            reinitialiseReactDependentComponents()
        }
    }

    fun startScan() {
        Log.d(LOG_TAG, "Attempting to start scan (CrowdFlowCore)...")
        if (isScanActive()) {
            Log.d(LOG_TAG, "Scan is already active. Ignoring start request.")
            return
        }
        if (!serviceChecker.ensureBluetoothIsEnabled()) {
            Log.d(LOG_TAG, "Bluetooth is off. Scan will not start.")
            return
        }
        Log.d(LOG_TAG, "Bluetooth check passed. Proceeding to start foreground service and checks.")
        ensureActiveServiceScope()
        mainHandler.post { proceedWithForegroundAndInitialChecks() }
    }

    private fun proceedWithForegroundAndInitialChecks() {
        ensureActiveServiceScope()
        val notification =
            notificationHelper.getPendingIntent()?.let { notificationHelper.createNotification(it) }
        if (notification != null) {
            try {
                service.startForeground(FOREGROUND_NOTIFICATION_ID, notification)
                Log.d(LOG_TAG, "Service started in foreground.")
                sharedPreferencesManager.isScanning = true
                scanStartedTimestamp = System.currentTimeMillis()
                latestGpsFixTime = 0L
                wakeLockManager.acquireWakeLock()
                startPeriodicSync()
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to start foreground service: ${e.message}", e)
                sharedPreferencesManager.isScanning = false
                wakeLockManager.releaseWakeLock()
                stopPeriodicSync()
                service.stopSelf()
                return
            }
        } else {
            Log.w(
                LOG_TAG,
                "Could not create notification, foreground service cannot start correctly. Stopping."
            )
            sharedPreferencesManager.isScanning = false
            service.stopSelf()
            return
        }
        Log.d(LOG_TAG, "Starting location updates and performing initial checks...")
        startLocationAndGeoChecks()
        mainHandler.removeCallbacks(locationTimeoutRunnable)
        mainHandler.postDelayed(locationTimeoutRunnable, INITIAL_LOCATION_TIMEOUT_MS)
    }

    private fun startLocationAndGeoChecks() {
        locationManager.startLocationUpdates { location ->
            mainHandler.removeCallbacks(locationTimeoutRunnable)
            latestLatitude = location.latitude
            latestLongitude = location.longitude
            latestGpsFixTime = location.time
            Log.d(
                LOG_TAG,
                "Location update received: Lat=$latestLatitude, Lon=$latestLongitude, Time=$latestGpsFixTime"
            )
            if (!isScanActive()) {
                Log.d(LOG_TAG, "Scan stopped before location check completed. Aborting checks.")
                locationManager.stopLocationUpdates()
                return@startLocationUpdates
            }
            val geoRestrictionManager = GeoRestrictionManager(service.applicationContext)
            var checksPassed = true
            if (geoRestrictionManager.isGeoRestrictionEnabled()) {
                if (!geoRestrictionManager.isLocationWithinGeoRestrictions(location)) {
                    Log.d(LOG_TAG, "Location outside approved boundaries. Stopping scan.")
                    eventDispatcher?.sendEvent("geoRestrictionFailed", 1)
                    checksPassed = false
                } else if (!geoRestrictionManager.isLocationNearTransitRoute(location)) {
                    Log.d(LOG_TAG, "Location not near any known transit route. Stopping scan.")
                    eventDispatcher?.sendEvent("transitRouteCheckFailed", 1)
                    checksPassed = false
                }
            }
            if (checksPassed) {
                Log.d(LOG_TAG, "Initial location and geo checks passed. Starting BLE Scan.")
                if (!bleScannerManager.startScan()) {
                    Log.w(LOG_TAG, "BleScannerManager failed to start scan after checks. Stopping.")
                    stopScan()
                } else {
                    Log.i(
                        LOG_TAG,
                        "Scan successfully started by BleScannerManager after initial checks."
                    )
                }
            } else {
                Log.w(LOG_TAG, "Initial checks failed. Stopping scan initiated earlier.")
                stopScan()
            }
        }
    }

    fun stopScan() {
        Log.d(LOG_TAG, "Stopping scan (CrowdFlowCore)...")
        mainHandler.removeCallbacks(locationTimeoutRunnable)
        if (::bleScannerManager.isInitialized) {
            bleScannerManager.stopScan()
        }
        if (::locationManager.isInitialized) {
            locationManager.stopLocationUpdates()
        }
        stopPeriodicSync()
        if (::wakeLockManager.isInitialized) {
            wakeLockManager.releaseWakeLock()
        }
        val wasScanning = sharedPreferencesManager.isScanning
        sharedPreferencesManager.isScanning = false
        if (wasScanning) {
            try {
                service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
                Log.d(LOG_TAG, "Service removed from foreground.")
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not stop foreground cleanly: ${e.message}")
            }
        }
        Log.d(LOG_TAG, "Scan stop process completed. Resources released (CrowdFlowCore).")
    }

    fun isScanActive(): Boolean = sharedPreferencesManager.isScanning

    private fun handleIntentAction(intent: Intent?) {
        intent?.action?.let { action ->
            when (action) {
                "com.urbanvind.crowdflow.START_SCAN" -> {
                    Log.d(LOG_TAG, "Received intent to start scanning.")
                    if (!isScanActive()) {
                        startScan()
                    } else {
                        Log.d(LOG_TAG, "Scan start intent received, but already scanning.")
                    }
                }

                "com.urbanvind.crowdflow.STOP_SCAN" -> {
                    Log.d(LOG_TAG, "Received intent to stop scanning.")
                    stopScan()
                }

                else -> {
                    if (sharedPreferencesManager.isScanning && !bleScannerManager.isScanning) {
                        Log.d(
                            LOG_TAG,
                            "Service restarted, attempting to resume scanning based on stored state."
                        )
                        startScan()
                    } else if (!sharedPreferencesManager.isScanning) {
                        Log.d(
                            LOG_TAG,
                            "Service started/restarted (unhandled action: $action), but scanning not indicated."
                        )
                    } else {
                        Log.d(
                            LOG_TAG,
                            "Service started/restarted (unhandled action: $action), scan already running."
                        )
                    }
                }
            }
        } ?: run {
            if (sharedPreferencesManager.isScanning && !bleScannerManager.isScanning) {
                Log.d(
                    LOG_TAG,
                    "Service restarted (null intent/action), attempting to resume scanning based on stored state."
                )
                startScan()
            } else if (!sharedPreferencesManager.isScanning) {
                Log.d(
                    LOG_TAG,
                    "Service started/restarted (null intent/action), but scanning not indicated."
                )
            } else {
                Log.d(
                    LOG_TAG, "Service started/restarted (null intent/action), scan already running."
                )
            }
        }
    }

    override fun onSyncSuccessClearData() {
        Log.d(LOG_TAG, "Sync successful, clearing scan results in manager.")
        bleScannerManager.clearScanResults()
    }

    override fun onSyncRequiresLocationRestart() {
        Log.d(LOG_TAG, "Received signal to restart location updates (CrowdFlowCore).")
        mainHandler.post {
            if (isScanActive()) {
                locationManager.stopLocationUpdates()
                startLocationAndGeoChecks()
                Log.d(LOG_TAG, "Location updates restarted by sync request.")
            } else {
                Log.d(LOG_TAG, "Skipping location restart requested by sync: Scan is not active.")
            }
        }
    }

    private fun reinitialiseReactDependentComponents() {
        (eventDispatcher?.reactContext)?.let { context ->
            eventDispatcher = EventDispatcher(context)
            bleScannerManager.setEventDispatcher(eventDispatcher!!)
            dataSyncManager.setEventDispatcher(eventDispatcher!!)
            serviceChecker = ServiceChecker(service, eventDispatcher!!)
            eventDispatcher?.sendEvent("serviceStarted", 1)
            Log.d(LOG_TAG, "React-dependent components reinitialised (CrowdFlowCore).")

            if (isScanActive() && context.hasActiveReactInstance()) {
                Log.d(LOG_TAG, "Scanning active, updating React Native UI state on context init.")
                serviceScope.launch(Dispatchers.Main) {
                    eventDispatcher?.sendEvent("bleScanStarted", 1)
                    val currentDeviceCount =
                        bleScannerManager.getBleScanResults().size + bleScannerManager.getClassicScanResults().size
                    eventDispatcher?.sendEvent("observedDeviceCountChanged", currentDeviceCount)
                    Log.d(LOG_TAG, "React context active, dispatched scan state events.")
                }
            } else if (isScanActive()) {
                Log.w(
                    LOG_TAG,
                    "React context available but not fully active, skipping initial event dispatch."
                )
            } else {

            }
        } ?: run {
            Log.w(LOG_TAG, "Attempted to reinitialise React components, but ReactContext was null.")
        }
    }

    private fun ensureActiveServiceScope() {
        if (!::serviceJob.isInitialized || serviceJob.isCancelled || !serviceJob.isActive) {
            Log.d(LOG_TAG, "Reinitializing service job and scope as it was found inactive.")
            serviceJob = Job()
            serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob(serviceJob))
            if (sharedPreferencesManager.isScanning) {
                Log.w(
                    LOG_TAG,
                    "Service scope was inactive but scanning was expected. Restarting periodic sync."
                )
                startPeriodicSync()
            }
        }
    }

    private fun initialiseManagers() {
        sharedPreferencesManager = SharedPreferencesManager(prefs)
        wakeLockManager = WakeLockManager(service)
        notificationHelper = NotificationManager(service)
        if (eventDispatcher == null) {
            eventDispatcher = EventDispatcher(ReactApplicationContext(service.applicationContext))
        }
        serviceChecker = ServiceChecker(service, eventDispatcher!!)
        dataAnonymiser = DataAnonymiser(KeystoreManager.getSecretKey()).apply {
            setSalt(SaltManager.generateNewSalt())
        }
        dataPayloadBuilder = DataPayloadBuilder(service, sharedPreferencesManager, dataAnonymiser)
        locationManager = LocationManager(service)
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            BleMetadataProvider.init(service.applicationContext)
        }
        jsonCacheManager = JsonCacheManager(service)
        dataSyncManager = DataSyncManager(
            service.applicationContext,
            sharedPreferencesManager,
            jsonCacheManager,
            eventDispatcher!!,
            locationManager,
            this
        )
    }

    private fun initialiseServices() {
        serviceJob = Job()
        serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob(serviceJob))
    }

    private fun initialiseReceivers() {
        lifecycleReceiver = LifecycleReceiver()
        val lifecycleFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                service.registerReceiver(
                    lifecycleReceiver, lifecycleFilter, Service.RECEIVER_NOT_EXPORTED
                )
            } else {
                service.registerReceiver(lifecycleReceiver, lifecycleFilter)
            }
            Log.d(LOG_TAG, "LifecycleReceiver registered.")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error registering LifecycleReceiver: ${e.message}", e)
        }
    }

    private fun initialiseCoroutines() {
        syncRunnable = createSyncRunnable()
    }

    private fun startPeriodicSync() {
        Log.d(LOG_TAG, "Starting periodic sync (CrowdFlowCore)...")
        mainHandler.removeCallbacks(syncRunnable)
        mainHandler.postDelayed(syncRunnable, SYNC_PERIOD_MS)
        Log.d(LOG_TAG, "Periodic sync scheduled.")
    }

    private fun stopPeriodicSync() {
        Log.d(LOG_TAG, "Stopping periodic sync callbacks (CrowdFlowCore)...")
        mainHandler.removeCallbacks(syncRunnable)
        Log.d(LOG_TAG, "Periodic sync callbacks removed.")
    }

    private fun createSyncRunnable(): Runnable = object : Runnable {
        override fun run() {
            if (!isScanActive()) {
                Log.d(LOG_TAG, "Sync task run check: Scan is not active. Stopping sync.")
                return
            }
            Log.d(LOG_TAG, "Running sync task (CrowdFlowCore)...")
            ensureActiveServiceScope()
            val currentTime = System.currentTimeMillis()

            // Refresh salt if location inactivity > 2 hours, and user enabled salt refresh
            if ((currentTime - latestGpsFixTime >= 2 * 60 * 60 * 1000) && (currentTime - lastSaltRefreshTime >= 2 * 60 * 60 * 1000)) {
                if (sharedPreferencesManager.shouldRefreshSalt) {
                    lastSaltRefreshTime = currentTime
                    dataAnonymiser.setSalt(SaltManager.generateNewSalt())
                    Log.i(LOG_TAG, "Salt automatically refreshed due to location inactivity.")
                } else {
                    Log.d(LOG_TAG, "Salt refresh skipped: shouldRefreshSalt is false.")
                }
            }

            val timeSinceStart =
                if (scanStartedTimestamp > 0) currentTime - scanStartedTimestamp else 0
            val currentDeviceCount =
                bleScannerManager.getBleScanResults().size + bleScannerManager.getClassicScanResults().size

            // Attempt to restart scan if device count remains very low for a certain time
            if (currentDeviceCount < MIN_DEVICES_FOR_RESTART && timeSinceStart > MIN_SCAN_DURATION_BEFORE_RESTART_CHECK_MS) {
                Log.w(
                    LOG_TAG,
                    "Low device count ($currentDeviceCount < $MIN_DEVICES_FOR_RESTART) for > 5 mins. Restarting scan..."
                )
                serviceScope.launch {
                    bleScannerManager.stopScan()
                    delay(500)
                    if (isScanActive()) {
                        if (!bleScannerManager.startScan()) {
                            Log.e(
                                LOG_TAG, "Failed to restart scan after low device count detected."
                            )
                            withContext(Dispatchers.Main) { stopScan() }
                        } else {
                            scanStartedTimestamp = System.currentTimeMillis()
                            Log.i(LOG_TAG, "Scan successfully restarted due to low device count.")
                        }
                    } else {
                        Log.d(
                            LOG_TAG, "Scan was stopped externally during low-count restart attempt."
                        )
                    }
                }
                rescheduleSync()
                return
            }

            serviceScope.launch(Dispatchers.IO) {
                try {
                    Log.d(LOG_TAG, "Gathering data for sync...")
                    val bleScanResults = bleScannerManager.getBleScanResults()
                    val classicScanResults = bleScannerManager.getClassicScanResults()
                    val bleObservations = bleScannerManager.getBleObservations()
                    val classicObservations = bleScannerManager.getClassicObservations()

                    if (bleScanResults.isEmpty() && classicScanResults.isEmpty()) {
                        Log.d(LOG_TAG, "No scan results found, skipping sync cycle.")
                        withContext(Dispatchers.Main) { rescheduleSync() }
                        return@launch
                    }

                    Log.d(
                        LOG_TAG,
                        "Processing ${bleScanResults.size} BLE and ${classicScanResults.size} Classic results for sync."
                    )
                    val currentLat = latestLatitude
                    val currentLon = latestLongitude
                    val currentFixTime = latestGpsFixTime

                    val devicesData: List<Map<String, Any>> =
                        bleScanResults.map { (address, scanResult) ->
                            dataPayloadBuilder.createDataPayload(
                                scanResult,
                                bleObservations[address] ?: 1,
                                currentLat,
                                currentLon,
                                currentFixTime
                            )
                        }
                    val classicData: List<Map<String, Any>> =
                        classicScanResults.map { (address, intent) ->
                            dataPayloadBuilder.createClassicPayload(
                                intent,
                                classicObservations[address] ?: 1,
                                currentLat,
                                currentLon,
                                currentFixTime
                            )
                        }
                    val dataToSync = dataPayloadBuilder.buildSyncData(
                        devicesData, classicData, currentLat, currentLon, currentFixTime
                    )
                    Log.d(LOG_TAG, "Data prepared, initiating sync via DataSyncManager...")
                    dataSyncManager.syncData(dataToSync, currentLat, currentLon)
                } catch (e: Exception) {
                    Log.e(
                        LOG_TAG,
                        "Error during data preparation or sync trigger: ${e.localizedMessage}",
                        e
                    )
                } finally {
                    withContext(Dispatchers.Main) {
                        rescheduleSync()
                    }
                }
            }
        }

        private fun rescheduleSync() {
            if (isScanActive()) {
                mainHandler.removeCallbacks(this)
                mainHandler.postDelayed(this, SYNC_PERIOD_MS)
            } else {
                Log.d(LOG_TAG, "Scan stopped, sync task not rescheduled.")
            }
        }
    }

    private fun performCleanup() {
        Log.d(LOG_TAG, "CrowdFlowCore performCleanup invoked (onDestroy).")
        try {
            stopScan()
            try {
                if (::lifecycleReceiver.isInitialized) {
                    service.unregisterReceiver(lifecycleReceiver)
                    Log.d(LOG_TAG, "LifecycleReceiver unregistered.")
                }
            } catch (e: IllegalArgumentException) {
                Log.w(
                    LOG_TAG,
                    "LifecycleReceiver unregister failed (may already be unregistered): ${e.message}"
                )
            }
            if (::serviceJob.isInitialized && serviceJob.isActive) {
                serviceJob.cancel("Service Destroyed")
                Log.d(LOG_TAG, "Service coroutine scope canceled (CrowdFlowCore).")
            } else {
                Log.d(LOG_TAG, "Service coroutine scope was already inactive or not initialized.")
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error during service cleanup: ${e.message}", e)
        } finally {
            Log.i(LOG_TAG, "Service cleanup finished (CrowdFlowCore).")
        }
    }

    fun setIsFirstLaunch(isFirstLaunch: Boolean) {
        sharedPreferencesManager.isFirstLaunch = isFirstLaunch
        Log.d(LOG_TAG, "First launch set to: $isFirstLaunch")
    }

    fun setIsLocked(isLocked: Boolean) {
        sharedPreferencesManager.isLocked = isLocked
        Log.d(LOG_TAG, "Is locked set to: $isLocked")
    }

    fun setUserUUID(value: String) {
        sharedPreferencesManager.userUUID = value
        Log.d(LOG_TAG, "User's UUID changed to: ${sharedPreferencesManager.userUUID}")
    }

    fun setServer(value: String) {
        sharedPreferencesManager.server = value
        Log.d(LOG_TAG, "Server changed to: ${sharedPreferencesManager.server}")
    }

    fun setVehicleType(value: String) {
        sharedPreferencesManager.vehicleType = value
        Log.d(LOG_TAG, "Vehicle type changed to: ${sharedPreferencesManager.vehicleType}")
    }

    fun setIsScheduledScanEnabled(enabled: Boolean) {
        sharedPreferencesManager.isScheduledScanEnabled = enabled
        Log.d(LOG_TAG, "Scheduled scan enabled set to: $enabled")
    }

    fun setRequireEstimationResult(enabled: Boolean) {
        sharedPreferencesManager.requireEstimationResult = enabled
        Log.d(LOG_TAG, "Require estimation enabled set to: $enabled")
    }

    fun setShouldRefreshSalt(enabled: Boolean) {
        sharedPreferencesManager.shouldRefreshSalt = enabled
        Log.d(LOG_TAG, "Should refresh salt set to: $enabled")
    }

    fun setScheduleRules(scheduleRules: List<SharedPreferencesManager.ScheduleRule>) {
        sharedPreferencesManager.scheduleRules = scheduleRules
        Log.d(LOG_TAG, "Schedule rules set: ${sharedPreferencesManager.scheduleRules}")
    }

    fun setIsGeoRestrictionEnabled(enabled: Boolean) {
        sharedPreferencesManager.isGeoRestrictionEnabled = enabled
        Log.d(LOG_TAG, "Geo restriction enabled set to: $enabled")
    }

    fun setRestrictedCities(cities: List<SharedPreferencesManager.CityGeoRestriction>) {
        sharedPreferencesManager.restrictedCities = cities
        Log.d(LOG_TAG, "Restricted cities set: ${sharedPreferencesManager.restrictedCities}")
    }

    fun setIsDataPrivacyEnabled(enabled: Boolean) {
        sharedPreferencesManager.isDataPrivacyEnabled = enabled
        Log.d(LOG_TAG, "Data privacy enabled set to: $enabled")
    }

    fun setDevStep(value: String) {
        sharedPreferencesManager.devStep = value
        Log.d(LOG_TAG, "Dev step changed to: ${sharedPreferencesManager.devStep}")
    }

    fun setIsDebugModeEnabled(enabled: Boolean) {
        sharedPreferencesManager.isDebugModeEnabled = enabled
        Log.d(LOG_TAG, "Debug mode enabled set to: $enabled")
    }

    fun setIsCalibrationModeEnabled(enabled: Boolean) {
        sharedPreferencesManager.isCalibrationModeEnabled = enabled
        Log.d(LOG_TAG, "Calibration mode enabled set to: $enabled")
    }

    fun setShouldDisplayDebugInfo(enabled: Boolean) {
        sharedPreferencesManager.shouldDisplayDebugInfo = enabled
        Log.d(LOG_TAG, "Should display debug info set to: $enabled")
    }

    fun startTrip() {
        Log.d(LOG_TAG, "Trip started. Resetting all calibration counts and totals.")
        dataAnonymiser.setSalt(SaltManager.generateNewSalt())
        resetCalibration()
        eventDispatcher?.sendEvent("paxEstimatedChanged", -1)
        eventDispatcher?.sendEvent(
            "calibrationBoardingChanged", sharedPreferencesManager.calibrationBoarding
        )
        eventDispatcher?.sendEvent(
            "calibrationAlightingChanged", sharedPreferencesManager.calibrationAlighting
        )
        eventDispatcher?.sendEvent(
            "calibrationBoardingTotalChanged", sharedPreferencesManager.calibrationBoardingTotal
        )
        eventDispatcher?.sendEvent(
            "calibrationAlightingTotalChanged", sharedPreferencesManager.calibrationAlightingTotal
        )
        eventDispatcher?.sendEvent(
            "calibrationCountChanged", sharedPreferencesManager.calibrationCount
        )
    }

    fun endTrip() {
        Log.d(LOG_TAG, "Trip ended. Resetting all calibration counts and totals.")
        sharedPreferencesManager.resetCalibration()
        eventDispatcher?.sendEvent(
            "calibrationBoardingChanged", sharedPreferencesManager.calibrationBoarding
        )
        eventDispatcher?.sendEvent(
            "calibrationAlightingChanged", sharedPreferencesManager.calibrationAlighting
        )
        eventDispatcher?.sendEvent(
            "calibrationBoardingTotalChanged", sharedPreferencesManager.calibrationBoardingTotal
        )
        eventDispatcher?.sendEvent(
            "calibrationAlightingTotalChanged", sharedPreferencesManager.calibrationAlightingTotal
        )
        eventDispatcher?.sendEvent(
            "calibrationCountChanged", sharedPreferencesManager.calibrationCount
        )
    }

    fun setCalibrationTripName(value: String) {
        sharedPreferencesManager.calibrationTripName = value
        Log.d(LOG_TAG, "Trip name set to: ${sharedPreferencesManager.calibrationTripName}")
        eventDispatcher?.sendStringEvent(
            "calibrationTripNameChanged", sharedPreferencesManager.calibrationTripName
        )
    }

    fun setManualCalibrationCount(value: Int) {
        sharedPreferencesManager.setManualCalibrationCount(value)
        Log.d(
            LOG_TAG, "Manual calibration count set to: ${sharedPreferencesManager.calibrationCount}"
        )
        eventDispatcher?.sendEvent(
            "calibrationCountChanged", sharedPreferencesManager.calibrationCount
        )
    }

    fun incrementCalibrationCount() {
        sharedPreferencesManager.incrementCalibrationCount()
        Log.d(
            LOG_TAG,
            "Calibration count incremented to: ${sharedPreferencesManager.calibrationCount}"
        )
        eventDispatcher?.sendEvent(
            "calibrationCountChanged", sharedPreferencesManager.calibrationCount
        )
    }

    fun decrementCalibrationCount() {
        sharedPreferencesManager.decrementCalibrationCount()
        Log.d(
            LOG_TAG,
            "Calibration count decremented to: ${sharedPreferencesManager.calibrationCount}"
        )
        eventDispatcher?.sendEvent(
            "calibrationCountChanged", sharedPreferencesManager.calibrationCount
        )
    }

    fun incrementCalibrationBoarding() {
        sharedPreferencesManager.incrementCalibrationBoarding()
        Log.d(
            LOG_TAG,
            "Calibration boarding incremented to: ${sharedPreferencesManager.calibrationBoarding}, " + "total: ${sharedPreferencesManager.calibrationBoardingTotal}"
        )
        eventDispatcher?.sendEvent(
            "calibrationBoardingChanged", sharedPreferencesManager.calibrationBoarding
        )
        eventDispatcher?.sendEvent(
            "calibrationBoardingTotalChanged", sharedPreferencesManager.calibrationBoardingTotal
        )
        eventDispatcher?.sendEvent(
            "calibrationCountChanged", sharedPreferencesManager.calibrationCount
        )
    }

    fun decrementCalibrationBoarding() {
        sharedPreferencesManager.decrementCalibrationBoarding()
        Log.d(
            LOG_TAG,
            "Calibration boarding decremented to: ${sharedPreferencesManager.calibrationBoarding}, " + "total: ${sharedPreferencesManager.calibrationBoardingTotal}"
        )
        eventDispatcher?.sendEvent(
            "calibrationBoardingChanged", sharedPreferencesManager.calibrationBoarding
        )
        eventDispatcher?.sendEvent(
            "calibrationBoardingTotalChanged", sharedPreferencesManager.calibrationBoardingTotal
        )
        eventDispatcher?.sendEvent(
            "calibrationCountChanged", sharedPreferencesManager.calibrationCount
        )
    }

    fun incrementCalibrationAlighting() {
        sharedPreferencesManager.incrementCalibrationAlighting()
        Log.d(
            LOG_TAG,
            "Calibration alighting incremented to: ${sharedPreferencesManager.calibrationAlighting}, " + "total: ${sharedPreferencesManager.calibrationAlightingTotal}"
        )
        eventDispatcher?.sendEvent(
            "calibrationAlightingChanged", sharedPreferencesManager.calibrationAlighting
        )
        eventDispatcher?.sendEvent(
            "calibrationAlightingTotalChanged", sharedPreferencesManager.calibrationAlightingTotal
        )
        eventDispatcher?.sendEvent(
            "calibrationCountChanged", sharedPreferencesManager.calibrationCount
        )
    }

    fun decrementCalibrationAlighting() {
        sharedPreferencesManager.decrementCalibrationAlighting()
        Log.d(
            LOG_TAG,
            "Calibration alighting decremented to: ${sharedPreferencesManager.calibrationAlighting}, " + "total: ${sharedPreferencesManager.calibrationAlightingTotal}"
        )
        eventDispatcher?.sendEvent(
            "calibrationAlightingChanged", sharedPreferencesManager.calibrationAlighting
        )
        eventDispatcher?.sendEvent(
            "calibrationAlightingTotalChanged", sharedPreferencesManager.calibrationAlightingTotal
        )
        eventDispatcher?.sendEvent(
            "calibrationCountChanged", sharedPreferencesManager.calibrationCount
        )
    }

    fun nextStop() {
        sharedPreferencesManager.nextStop()
        Log.d(LOG_TAG, "New stop set. Resetting calibration boarding and alighting counts.")
        eventDispatcher?.sendEvent(
            "calibrationBoardingChanged", sharedPreferencesManager.calibrationBoarding
        )
        eventDispatcher?.sendEvent(
            "calibrationAlightingChanged", sharedPreferencesManager.calibrationAlighting
        )
    }

    fun resetCalibration() {
        sharedPreferencesManager.resetCalibration()
        Log.d(LOG_TAG, "Calibration reset.")
    }

    fun clearSharedPreferences() {
        sharedPreferencesManager.clearSharedPreferences()
        Log.d(LOG_TAG, "SharedPreferences reset.")
    }
}
