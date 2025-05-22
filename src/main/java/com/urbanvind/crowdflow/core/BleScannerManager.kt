package com.urbanvind.crowdflow.core

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.DeadObjectException
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class BleScannerManager(
    private val context: Context,
    @Volatile private var eventDispatcher: EventDispatcher,
    private val serviceChecker: ServiceChecker,
    private val jsonCacheManager: JsonCacheManager
) {

    companion object {
        private const val LOG_TAG = "BleScannerManager"
        private const val BATCH_INTERVAL_MS = 200L
        private const val INITIAL_SCAN_PERIOD_MS = 10000L
        private const val BT_SCANNER_INIT_RETRY_DELAY_MS = 1000L
        private const val BT_SCANNER_INIT_RETRIES = 3
        private const val CLASSIC_DISCOVERY_RESTART_DELAY_MS = 500L
        private const val BLE_RESTART_DELAY_MS = 1000L
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    @Volatile
    private var adapter: BluetoothAdapter? = bluetoothManager.adapter

    @Volatile
    private var bluetoothLeScanner: BluetoothLeScanner? = null

    private val _isScanning = AtomicBoolean(false)
    val isScanning: Boolean get() = _isScanning.get()

    private val deviceObservations = ConcurrentHashMap<String, Int>()
    private val classicObservations = ConcurrentHashMap<String, Int>()
    private val scanResultsMap = ConcurrentHashMap<String, ScanResult>()
    private val classicResultsMap = ConcurrentHashMap<String, Intent>()

    @Volatile
    private var managerJob = SupervisorJob()
        get() {
            if (field.isCancelled) {
                field = SupervisorJob()
            }
            return field
        }

    @Volatile
    private var managerScope = CoroutineScope(Dispatchers.Default + managerJob)
        get() {
            if (!field.isActive) {
                field = CoroutineScope(Dispatchers.Default + managerJob)
            }
            return field
        }


    @Volatile
    private var processingJob: Job? = null


    @Volatile
    private var scanResultChannel = Channel<ScanResult>(capacity = Channel.UNLIMITED)

    @Volatile
    private var classicResultChannel = Channel<Intent>(capacity = Channel.UNLIMITED)

    @Volatile
    private var isBtStateReceiverRegistered = false

    @Volatile
    private var isClassicDiscoveryReceiverRegistered = false

    init {
        initializeBluetoothLeScanner()
        registerBluetoothStateReceiver()
    }

    fun setEventDispatcher(newEventDispatcher: EventDispatcher) {
        Log.d(LOG_TAG, "Updating EventDispatcher.")
        this.eventDispatcher = newEventDispatcher
    }

    @SuppressLint("MissingPermission")
    fun startScan(): Boolean {
        Log.d(LOG_TAG, "Attempting to start scan...")
        if (!hasPermissions()) {
            Log.e(LOG_TAG, "Cannot start scan - required permissions missing.")
            return false
        }

        if (!serviceChecker.ensureBluetoothIsEnabled()) {
            Log.w(LOG_TAG, "Bluetooth is not enabled. Scan cannot start.")
            eventDispatcher.sendEvent("bluetoothNotEnabled", 1)
            return false
        }

        if (_isScanning.compareAndSet(false, true)) {
            Log.i(LOG_TAG, "Starting scan sequence...")

            ensureActiveManagerScope()


            clearScanResultsInternal()
            Log.d(LOG_TAG, "Clearing JSON cache at the start of the scan.")
            jsonCacheManager.clearCache()


            resetChannels()

            managerScope.launch {
                startBleScanInternal()
            }
            startClassicScanningInternal()
            startScanResultProcessing()

            Log.i(LOG_TAG, "Scan initiated (BLE + Classic).")
            return true
        } else {
            Log.w(LOG_TAG, "Scan already in progress.")
            return false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (_isScanning.compareAndSet(true, false)) {
            Log.i(LOG_TAG, "Stopping scan sequence...")

            stopBleScanInternal()
            stopClassicScanningInternal()

            processingJob?.cancel("Scan stopped")
            processingJob = null

            Log.i(LOG_TAG, "Scan stopped (BLE + Classic).")
        } else {
            Log.d(LOG_TAG, "Scan not active, no need to stop.")
        }
    }

    fun clearScanResults() {
        clearScanResultsInternal()
        Log.d(LOG_TAG, "External request to clear scan results completed.")
    }

    fun getBleScanResults(): Map<String, ScanResult> = ConcurrentHashMap(scanResultsMap)
    fun getClassicScanResults(): Map<String, Intent> = ConcurrentHashMap(classicResultsMap)
    fun getBleObservations(): Map<String, Int> = ConcurrentHashMap(deviceObservations)
    fun getClassicObservations(): Map<String, Int> = ConcurrentHashMap(classicObservations)

    fun shutdown() {
        Log.i(LOG_TAG, "Shutting down BleScannerManager...")
        stopScan()

        managerScope.cancel("Manager shutdown")

        unregisterBluetoothStateReceiver()
        unregisterClassicDiscoveryReceiver()

        bluetoothLeScanner = null
        adapter = null

        Log.i(LOG_TAG, "BleScannerManager shutdown complete.")
    }

    @SuppressLint("MissingPermission")
    private fun startBleScanInternal() {
        Log.d(LOG_TAG, "Starting internal BLE scan job...")
        ensureActiveManagerScope()

        managerScope.launch(Dispatchers.IO) {
            var scannerInitialized = false
            for (i in 1..BT_SCANNER_INIT_RETRIES) {
                if (!isActive) {
                    Log.w(LOG_TAG, "BLE Scan start cancelled during init.")
                    return@launch
                }
                initializeBluetoothLeScanner()
                if (bluetoothLeScanner != null) {
                    scannerInitialized = true
                    Log.d(LOG_TAG, "BluetoothLeScanner successfully initialized on attempt $i.")
                    break
                }
                Log.w(
                    LOG_TAG,
                    "BluetoothLeScanner null, retry attempt $i/$BT_SCANNER_INIT_RETRIES after delay."
                )
                delay(BT_SCANNER_INIT_RETRY_DELAY_MS)
            }

            if (!scannerInitialized) {
                Log.e(
                    LOG_TAG,
                    "Failed to initialize BluetoothLeScanner after $BT_SCANNER_INIT_RETRIES retries. BLE scan cannot start."
                )
                _isScanning.set(false)
                return@launch
            }

            if (!isScanning || !isActive) {
                Log.w(LOG_TAG, "Scan stopped or job cancelled before BLE scan could fully start.")
                bluetoothLeScanner?.stopScan(leScanCallback)
                return@launch
            }

            eventDispatcher.sendEvent("bleScanStarted", 1)
            val scanFilters = createScanFilters()
            val initialScanSettings = createInitialScanSettings()
            val batchScanSettings = createBatchScanSettings()

            try {
                Log.d(LOG_TAG, "Starting initial non-batch BLE scan...")
                bluetoothLeScanner?.startScan(scanFilters, initialScanSettings, leScanCallback)

                delay(INITIAL_SCAN_PERIOD_MS)

                if (!isScanning || !isActive) {
                    Log.w(
                        LOG_TAG,
                        "Scan stopped or job cancelled during initial BLE scan phase. Stopping scan."
                    )
                    bluetoothLeScanner?.stopScan(leScanCallback)
                    return@launch
                }

                Log.d(LOG_TAG, "Stopping initial non-batch BLE scan before switching to batch.")
                bluetoothLeScanner?.stopScan(leScanCallback)

                delay(100)

                if (!isScanning || !isActive) {
                    Log.w(
                        LOG_TAG, "Scan stopped or job cancelled before switching to batch BLE scan."
                    )
                    return@launch
                }

                Log.d(LOG_TAG, "Starting batch BLE scan...")
                bluetoothLeScanner?.startScan(scanFilters, batchScanSettings, leScanCallback)
                Log.i(LOG_TAG, "BLE scan successfully switched to batch mode.")

            } catch (e: DeadObjectException) {
                Log.e(
                    LOG_TAG, "BLE scan failed (DeadObjectException): ${e.message}. Stopping scan."
                )
                stopScan()
            } catch (e: IllegalStateException) {
                Log.e(
                    LOG_TAG,
                    "BLE scan failed (IllegalStateException): ${e.message}. Maybe BT off? Stopping scan."
                )
                stopScan()
            } catch (e: SecurityException) {
                Log.e(
                    LOG_TAG,
                    "BLE scan failed (SecurityException): ${e.message}. Check permissions. Stopping scan."
                )
                stopScan()
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Unexpected error during BLE scan operation: ${e.message}", e)
                stopScan()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScanInternal() {
        if (bluetoothLeScanner == null) {
            Log.d(LOG_TAG, "stopBleScanInternal: Scanner not initialized, nothing to stop.")
            return
        }
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            Log.w(LOG_TAG, "stopBleScanInternal: BLUETOOTH_SCAN permission missing.")
            return
        }
        if (adapter?.state != BluetoothAdapter.STATE_ON) {
            Log.w(LOG_TAG, "stopBleScanInternal: Bluetooth is not ON (state: ${adapter?.state}).")
            return
        }

        try {
            bluetoothLeScanner?.stopScan(leScanCallback)
            Log.d(LOG_TAG, "BLE scan stop requested.")
            eventDispatcher.sendEvent("bleScanStopped", 1)
        } catch (e: DeadObjectException) {
            Log.e(LOG_TAG, "Failed to stop BLE scan (DeadObjectException): ${e.message}")
        } catch (e: IllegalStateException) {
            Log.w(
                LOG_TAG,
                "Failed to stop BLE scan (IllegalStateException): ${e.message}. BT might be off or scanner invalid."
            )
        } catch (e: SecurityException) {
            Log.e(LOG_TAG, "Failed to stop BLE scan (SecurityException): ${e.message}")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Unexpected error stopping BLE scan: ${e.message}", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startClassicScanningInternal() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            Log.w(LOG_TAG, "Cannot start classic discovery: BLUETOOTH_SCAN permission missing.")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            Log.w(
                LOG_TAG,
                "Cannot start classic discovery: BLUETOOTH_CONNECT permission missing on API ${Build.VERSION.SDK_INT}."
            )
            return
        }

        if (adapter?.state == BluetoothAdapter.STATE_ON) {
            registerClassicDiscoveryReceiver()
            try {
                if (adapter?.isDiscovering == true) {
                    Log.d(LOG_TAG, "Classic discovery already running, cancelling and restarting.")
                    adapter?.cancelDiscovery()

                    managerScope.launch {
                        delay(CLASSIC_DISCOVERY_RESTART_DELAY_MS / 2)
                        if (isScanning && isActive) {
                            adapter?.startDiscovery()
                            Log.d(LOG_TAG, "Restarted classic Bluetooth discovery.")
                        }
                    }
                } else {
                    if (isScanning) {
                        adapter?.startDiscovery()
                        Log.d(LOG_TAG, "Started classic Bluetooth discovery.")
                    } else {
                        Log.d(LOG_TAG, "Scan stopped before classic discovery could start.")
                    }
                }
            } catch (e: SecurityException) {
                Log.e(
                    LOG_TAG,
                    "Cannot start/cancel classic discovery: SecurityException: ${e.message}"
                )
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error managing classic discovery: ${e.message}", e)
            }
        } else {
            Log.w(
                LOG_TAG,
                "Bluetooth adapter not enabled (state: ${adapter?.state}), cannot start classic discovery."
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopClassicScanningInternal() {
        unregisterClassicDiscoveryReceiver()

        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            return
        }

        if (adapter?.isDiscovering == true) {
            try {
                adapter?.cancelDiscovery()
                Log.d(LOG_TAG, "Classic Bluetooth discovery stopped.")
            } catch (e: SecurityException) {
                Log.e(LOG_TAG, "Cannot stop classic discovery: SecurityException: ${e.message}")
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error stopping classic discovery: ${e.message}", e)
            }
        } else {
            Log.d(LOG_TAG, "Classic discovery was not running.")
        }
    }

    private fun initializeBluetoothLeScanner() {
        adapter = bluetoothManager.adapter
        if (adapter?.state == BluetoothAdapter.STATE_ON) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                Log.e(LOG_TAG, "Cannot initialize BLE scanner: BLUETOOTH_SCAN permission missing.")
                bluetoothLeScanner = null
                return
            }

            try {
                bluetoothLeScanner = adapter!!.bluetoothLeScanner
                if (bluetoothLeScanner == null) {
                    Log.e(
                        LOG_TAG,
                        "BluetoothLeScanner is null even though adapter is ON and permissions seem granted."
                    )
                } else {
                    Log.d(LOG_TAG, "BluetoothLeScanner initialized/verified.")
                }
            } catch (e: SecurityException) {
                Log.e(LOG_TAG, "SecurityException initializing BLE Scanner: ${e.message}")
                bluetoothLeScanner = null
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Exception initializing BLE Scanner: ${e.message}", e)
                bluetoothLeScanner = null
            }
        } else {
            Log.w(
                LOG_TAG,
                "Bluetooth is not ON (state: ${adapter?.state}). Cannot get/use BLE scanner."
            )
            bluetoothLeScanner = null
        }
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            super.onScanResult(callbackType, result)

            if (managerScope.isActive && isScanning) {
                managerScope.launch {
                    try {
                        scanResultChannel.send(result)
                    } catch (e: Exception) {
                        when (e) {
                            is ClosedSendChannelException -> {
                                Log.w(
                                    LOG_TAG,
                                    "Tried sending BLE result to closed channel. Probably scan was stopped."
                                )
                            }

                            is CancellationException -> {
                                Log.w(
                                    LOG_TAG, "Scan canceled while sending BLE result."
                                )
                            }

                            else -> {
                                Log.e(
                                    LOG_TAG, "Error sending BLE result to channel: ${e.message}", e
                                )
                            }
                        }
                    }
                }
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results ?: return
            super.onBatchScanResults(results)

            if (managerScope.isActive && isScanning) {
                managerScope.launch {
                    for (result in results) {
                        try {
                            scanResultChannel.send(result)
                        } catch (e: Exception) {
                            when (e) {
                                is ClosedSendChannelException -> {
                                    Log.w(
                                        LOG_TAG,
                                        "Tried sending batch BLE result to closed channel. Probably scan was stopped."
                                    )
                                    break
                                }

                                is CancellationException -> {
                                    Log.w(
                                        LOG_TAG, "Scan canceled while sending batch BLE result."
                                    )
                                    break
                                }

                                else -> {
                                    Log.e(
                                        LOG_TAG,
                                        "Error sending batch BLE result to channel: ${e.message}",
                                        e
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(
                LOG_TAG,
                "BLE Scan failed with error code: $errorCode - ${decodeScanErrorCode(errorCode)}"
            )

            when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> {
                    Log.w(LOG_TAG, "Scan failed because it was already started. Ignoring.")
                }

                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> {
                    Log.e(
                        LOG_TAG,
                        "Scan failed: App registration failed. Critical error. Stopping scan."
                    )
                    stopScan()
                }

                SCAN_FAILED_INTERNAL_ERROR -> {
                    Log.e(LOG_TAG, "Scan failed: Internal BT error. Attempting restart.")
                    if (isScanning) {
                        managerScope.launch {
                            stopBleScanInternal()
                            delay(BLE_RESTART_DELAY_MS)
                            if (isScanning && isActive) {
                                startBleScanInternal()
                            }
                        }
                    }
                }

                SCAN_FAILED_FEATURE_UNSUPPORTED -> {
                    Log.e(
                        LOG_TAG, "Scan failed: Feature unsupported on this device. Stopping scan."
                    )
                    stopScan()
                }

                SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> {
                    Log.e(LOG_TAG, "Scan failed: Out of hardware resources. Stopping scan.")
                    stopScan()
                }

                else -> Log.e(LOG_TAG, "Scan failed with unhandled error code: $errorCode")
            }
        }

        private fun decodeScanErrorCode(errorCode: Int): String {
            return when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "SCAN_FAILED_ALREADY_STARTED"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED"
                SCAN_FAILED_INTERNAL_ERROR -> "SCAN_FAILED_INTERNAL_ERROR"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "SCAN_FAILED_FEATURE_UNSUPPORTED"
                SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES"
                6 -> "SCAN_FAILED_SCANNING_TOO_FREQUENTLY (API 31+)"
                else -> "UNKNOWN_ERROR_CODE"
            }
        }
    }

    private fun createScanFilters(): List<ScanFilter> {
        return emptyList()
    }

    private fun createInitialScanSettings(): ScanSettings {
        return ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT).setReportDelay(0).build()
    }

    private fun createBatchScanSettings(): ScanSettings {
        return ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .setReportDelay(BATCH_INTERVAL_MS).build()
    }

    private fun startScanResultProcessing() {
        if (processingJob?.isActive == true) {
            Log.w(
                LOG_TAG, "Existing processing job found active. Cancelling before starting new one."
            )
            processingJob?.cancel("Starting new processing job")
        }

        processingJob = managerScope.launch(Dispatchers.Default) {
            Log.d(LOG_TAG, "Starting scan result processing coroutine.")
            var bleBatchCount = 0
            var classicBatchCount = 0

            val debounceJob = launch {
                try {
                    while (isActive) {
                        delay(BATCH_INTERVAL_MS * 2)
                        if (bleBatchCount > 0 || classicBatchCount > 0 || scanResultsMap.isNotEmpty() || classicResultsMap.isNotEmpty()) {
                            val totalSize = scanResultsMap.size + classicResultsMap.size
                            eventDispatcher.sendEvent("observedDeviceCountChanged", totalSize)

                            if (bleBatchCount > 0 || classicBatchCount > 0) {
                                Log.d(
                                    LOG_TAG,
                                    "Dispatched observedDeviceCountChanged: $totalSize (${scanResultsMap.size} BLE, ${classicResultsMap.size} Classic)"
                                )
                            }
                            bleBatchCount = 0
                            classicBatchCount = 0
                        }
                    }
                } catch (e: CancellationException) {
                    Log.d(LOG_TAG, "Debounce job cancelled.")
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Error in debounce job: ${e.message}", e)
                }
            }

            val bleConsumerJob = launch {
                try {
                    scanResultChannel.consumeEach { scanResult ->
                        if (!isActive) return@consumeEach
                        val address = scanResult.device.address ?: return@consumeEach
                        scanResultsMap[address] = scanResult
                        deviceObservations[address] = (deviceObservations[address] ?: 0) + 1
                        bleBatchCount++
                    }
                } catch (e: CancellationException) {
                    Log.d(LOG_TAG, "BLE consumer job cancelled.")
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Error in BLE consumer job: ${e.message}", e)
                } finally {
                    Log.d(LOG_TAG, "BLE consumer job finished.")
                }
            }

            val classicConsumerJob = launch {
                try {
                    classicResultChannel.consumeEach { intent ->
                        if (!isActive) return@consumeEach
                        val device: BluetoothDevice? =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(
                                    BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
                                )
                            } else {
                                @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            }

                        val address = device?.address ?: return@consumeEach

                        classicResultsMap[address] = intent
                        classicObservations[address] = (classicObservations[address] ?: 0) + 1
                        classicBatchCount++
                    }
                } catch (e: CancellationException) {
                    Log.d(LOG_TAG, "Classic consumer job cancelled.")
                } catch (e: Exception) {
                    when (e) {
                        is ClosedSendChannelException -> {
                            Log.w(
                                LOG_TAG,
                                "Tried sending classic result to closed channel. Probably scan was stopped."
                            )
                        }

                        else -> {
                            Log.e(
                                LOG_TAG, "Error in Classic consumer job: ${e.message}", e
                            )
                        }
                    }
                } finally {
                    Log.d(LOG_TAG, "Classic consumer job finished.")
                }
            }

            try {
                joinAll(bleConsumerJob, classicConsumerJob)
                Log.d(LOG_TAG, "Both BLE and Classic consumer jobs completed.")
            } finally {
                debounceJob.cancelAndJoin()
                Log.d(LOG_TAG, "Scan result processing coroutine fully finished.")
            }
        }
        Log.d(LOG_TAG, "Processing job launched.")
    }

    /**
     * Close old channels and create new ones so that we don't send to closed channels
     * after a stop/start cycle.
     */
    private fun resetChannels() {
        try {
            scanResultChannel.close()
        } catch (_: Exception) {
        }
        try {
            classicResultChannel.close()
        } catch (_: Exception) {
        }

        scanResultChannel = Channel(capacity = Channel.UNLIMITED)
        classicResultChannel = Channel(capacity = Channel.UNLIMITED)
    }

    private fun clearScanResultsInternal() {
        scanResultsMap.clear()
        classicResultsMap.clear()
        deviceObservations.clear()
        classicObservations.clear()
        Log.d(LOG_TAG, "Internal scan results and observations cleared.")
        eventDispatcher.sendEvent("observedDeviceCountChanged", 0)
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            intent ?: return
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                Log.d(LOG_TAG, "Bluetooth state changed to: ${decodeBtState(state)}")
                when (state) {
                    BluetoothAdapter.STATE_OFF -> {
                        Log.w(LOG_TAG, "Bluetooth turned OFF. Stopping ongoing scan if active.")
                        eventDispatcher.sendEvent("bluetoothNotEnabled", 1)
                        stopScan()
                        bluetoothLeScanner = null
                    }

                    BluetoothAdapter.STATE_TURNING_OFF -> {
                        Log.d(LOG_TAG, "Bluetooth turning OFF. Stopping scan preemptively.")
                        stopScan()
                        bluetoothLeScanner = null
                    }

                    BluetoothAdapter.STATE_ON -> {
                        Log.i(LOG_TAG, "Bluetooth turned ON.")
                        initializeBluetoothLeScanner()
                    }

                    BluetoothAdapter.STATE_TURNING_ON -> {
                        Log.d(LOG_TAG, "Bluetooth turning ON.")
                    }
                }
            }
        }

        private fun decodeBtState(state: Int): String {
            return when (state) {
                BluetoothAdapter.STATE_OFF -> "STATE_OFF"
                BluetoothAdapter.STATE_TURNING_OFF -> "STATE_TURNING_OFF"
                BluetoothAdapter.STATE_ON -> "STATE_ON"
                BluetoothAdapter.STATE_TURNING_ON -> "STATE_TURNING_ON"
                BluetoothAdapter.ERROR -> "ERROR"
                else -> "UNKNOWN ($state)"
            }
        }
    }

    private val classicDiscoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent?) {
            intent ?: return
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    if (managerScope.isActive && isScanning) {
                        managerScope.launch {
                            try {
                                classicResultChannel.send(intent)
                            } catch (e: Exception) {
                                when (e) {
                                    is ClosedSendChannelException -> {
                                        Log.w(
                                            LOG_TAG,
                                            "Tried sending classic result to closed channel. Probably scan was stopped."
                                        )
                                    }

                                    is CancellationException -> {
                                        Log.w(
                                            LOG_TAG, "Scan canceled while sending classic result."
                                        )
                                    }

                                    else -> {
                                        Log.e(
                                            LOG_TAG,
                                            "Error sending classic result to channel: ${e.message}",
                                            e
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.d(LOG_TAG, "Classic discovery finished.")
                    if (isScanning && managerScope.isActive) {
                        Log.d(LOG_TAG, "Scan active, restarting classic discovery after delay.")
                        managerScope.launch {
                            delay(CLASSIC_DISCOVERY_RESTART_DELAY_MS)
                            if (isScanning && isActive) {
                                startClassicScanningInternal()
                            }
                        }
                    } else {
                        Log.d(
                            LOG_TAG,
                            "Scan not active or scope cancelled, not restarting classic discovery."
                        )
                    }
                }
            }
        }
    }

    private fun registerBluetoothStateReceiver() {
        synchronized(this) {
            if (!isBtStateReceiverRegistered) {
                val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.registerReceiver(
                            bluetoothStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED
                        )
                    } else {
                        context.registerReceiver(bluetoothStateReceiver, filter)
                    }
                    isBtStateReceiverRegistered = true
                    Log.d(LOG_TAG, "Bluetooth state receiver registered successfully.")
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Error registering Bluetooth state receiver: ${e.message}", e)
                }
            }
        }
    }

    private fun unregisterBluetoothStateReceiver() {
        synchronized(this) {
            if (isBtStateReceiverRegistered) {
                try {
                    context.unregisterReceiver(bluetoothStateReceiver)
                    isBtStateReceiverRegistered = false
                    Log.d(LOG_TAG, "Bluetooth state receiver unregistered successfully.")
                } catch (e: IllegalArgumentException) {
                    Log.w(
                        LOG_TAG,
                        "Bluetooth state receiver already unregistered or never registered."
                    )
                    isBtStateReceiverRegistered = false
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Error unregistering Bluetooth state receiver: ${e.message}", e)
                }
            }
        }
    }

    private fun registerClassicDiscoveryReceiver() {
        synchronized(this) {
            if (!isClassicDiscoveryReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.registerReceiver(
                            classicDiscoveryReceiver, filter, Context.RECEIVER_NOT_EXPORTED
                        )
                    } else {
                        context.registerReceiver(classicDiscoveryReceiver, filter)
                    }
                    isClassicDiscoveryReceiverRegistered = true
                    Log.d(LOG_TAG, "Classic discovery receiver registered successfully.")
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Error registering Classic discovery receiver: ${e.message}", e)
                }
            }
        }
    }

    private fun unregisterClassicDiscoveryReceiver() {
        synchronized(this) {
            if (isClassicDiscoveryReceiverRegistered) {
                try {
                    context.unregisterReceiver(classicDiscoveryReceiver)
                    isClassicDiscoveryReceiverRegistered = false
                    Log.d(LOG_TAG, "Classic discovery receiver unregistered successfully.")
                } catch (e: IllegalArgumentException) {
                    Log.w(
                        LOG_TAG,
                        "Classic discovery receiver already unregistered or never registered."
                    )
                    isClassicDiscoveryReceiverRegistered = false
                } catch (e: Exception) {
                    Log.e(
                        LOG_TAG, "Error unregistering Classic discovery receiver: ${e.message}", e
                    )
                }
            }
        }
    }

    private fun ensureActiveManagerScope() {
        if (!managerScope.isActive) {
            Log.w(LOG_TAG, "Manager scope was inactive. Recreating scope and job.")
            managerJob = SupervisorJob()
            managerScope = CoroutineScope(Dispatchers.Default + managerJob)
            Log.i(LOG_TAG, "Manager scope reactivated.")

            if (isScanning) {
                Log.w(LOG_TAG, "Restarting scan result processing after scope reactivation.")
                startScanResultProcessing()
            }
        }
    }

    private fun hasPermissions(): Boolean {
        val requiredPermissions = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        var allGranted = true
        for (permission in requiredPermissions) {
            if (!hasPermission(permission)) {
                Log.e(LOG_TAG, "Required permission missing: $permission")
                allGranted = false
            }
        }
        return allGranted
    }

    private fun hasPermission(permission: String): Boolean {
        val granted = ActivityCompat.checkSelfPermission(
            context, permission
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(LOG_TAG, "Permission check failed for: $permission")
        }
        return granted
    }
}
