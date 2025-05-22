package com.urbanvind.crowdflow.core

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class DataSyncManager(
    private val context: Context,
    private val sharedPreferencesManager: SharedPreferencesManager,
    private val jsonCacheManager: JsonCacheManager,
    private var eventDispatcher: EventDispatcher,
    private val locationManager: LocationManager,
    private val syncResultListener: SyncResultListener
) {

    companion object {
        private const val LOG_TAG = "DataSyncManager"
    }

    interface SyncResultListener {
        fun onSyncSuccessClearData()
        fun onSyncRequiresLocationRestart()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun setEventDispatcher(dispatcher: EventDispatcher) {
        this.eventDispatcher = dispatcher
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun syncData(
        data: Map<String, Any>,
        currentLatitude: Double,
        currentLongitude: Double
    ) {
        Log.d(LOG_TAG, "Data sync initiated. Server: ${sharedPreferencesManager.server}")

        withContext(Dispatchers.IO) {
            Log.d(LOG_TAG, "Running sync logic within IO context.")

            // Load cached data from the JSON file
            val cachedData = jsonCacheManager.loadCachedData()
            val combinedData: Map<String, Any>
            val isRetry = cachedData.isNotEmpty()

            if (isRetry) {
                Log.d(
                    LOG_TAG,
                    "Cached data found (${cachedData.size} items). Merging with current data."
                )
                val allData = cachedData.toMutableList().apply { add(data) }

                // Merge "periods" from all data maps
                val mergedPeriods = mutableMapOf<String, Any>()
                for (singleMap in allData) {
                    val periods = singleMap["periods"] as? Map<String, Any> ?: continue
                    for ((timestamp, periodData) in periods) {
                        if (mergedPeriods.containsKey(timestamp)) {
                            // Merge devices lists within the same timestamp
                            val existingPeriod = mergedPeriods[timestamp] as? Map<String, Any>
                            val newPeriod = periodData as? Map<String, Any>
                            if (existingPeriod != null && newPeriod != null) {
                                val existingDevices =
                                    existingPeriod["devices"] as? List<Map<String, Any>>
                                        ?: emptyList()
                                val newDevices =
                                    newPeriod["devices"] as? List<Map<String, Any>> ?: emptyList()

                                val mergedDevices = existingDevices + newDevices

                                val mergedPeriod = existingPeriod.toMutableMap().apply {
                                    put("devices", mergedDevices)
                                }
                                mergedPeriods[timestamp] = mergedPeriod
                            }
                        } else {
                            mergedPeriods[timestamp] = periodData
                        }
                    }
                }

                combinedData = mutableMapOf<String, Any>().apply {
                    put("uuid", data["uuid"] ?: allData.firstOrNull()?.get("uuid") ?: "")
                    put("periods", mergedPeriods)
                }
                Log.d(LOG_TAG, "Data merged. Total periods after merge: ${mergedPeriods.size}")

            } else {
                Log.d(LOG_TAG, "No cached data found. Syncing current data only.")
                combinedData = data
            }

            // Avoid sending empty data
            val periods = combinedData["periods"] as? Map<*, *>
            if (periods == null || periods.isEmpty()) {
                Log.d(
                    LOG_TAG,
                    "No data (periods are null or empty) to sync. Skipping network request."
                )
                if (isRetry) {
                    Log.d(LOG_TAG, "Clearing potentially empty/malformed cache.")
                    jsonCacheManager.clearCache()
                }
                return@withContext
            }


            val json = Gson().toJson(combinedData)
            Log.d(LOG_TAG, "JSON payload prepared: ${json.take(500)}...")

            val requestBody = json.toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("${sharedPreferencesManager.server}/api/prototype/save_discoveries")
                .post(requestBody)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build()

            try {
                Log.d(LOG_TAG, "Executing network request to ${request.url}")
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    response.body?.string()?.let { responseBody ->
                        Log.d(
                            LOG_TAG,
                            "Server response successful (${response.code}): ${responseBody.take(500)}..."
                        )

                        val type = object : TypeToken<Map<String, Any>>() {}.type
                        val responseData = try {
                            Gson().fromJson<Map<String, Any>>(responseBody, type) ?: emptyMap()
                        } catch (e: Exception) {
                            Log.e(LOG_TAG, "Failed to parse JSON response: ${e.message}")
                            emptyMap<String, Any>()
                        }


                        val serverStatus = responseData["status"]?.toString() ?: ""
                        val serverEstimation =
                            responseData["estimatedCrowd"]?.toString()?.toDoubleOrNull()?.toInt()
                                ?: 0
                        eventDispatcher.sendStringEvent("serverStatusChanged", serverStatus)
                        eventDispatcher.sendEvent("paxEstimatedChanged", serverEstimation)
                        Log.d(
                            LOG_TAG,
                            "paxEstimatedChanged event fired with value: $serverEstimation"
                        )
                        val recentlyCount =
                            responseData["recentlySeen"]?.toString()?.toDoubleOrNull()?.toInt() ?: 0
                        val recentlyCountWin =
                            responseData["recentlySeenWindowMs"]?.toString()?.toDoubleOrNull()
                                ?.toInt() ?: 0
                        eventDispatcher.sendEvent("recentlySeenCountChanged", recentlyCount)
                        eventDispatcher.sendEvent("recentlySeenWindowMsChanged", recentlyCountWin)

                        syncResultListener.onSyncSuccessClearData()

                        if (currentLatitude == 0.0 && currentLongitude == 0.0) {
                            Log.d(
                                LOG_TAG,
                                "Location was null during successful sync. Requesting location restart."
                            )
                            syncResultListener.onSyncRequiresLocationRestart()
                        }

                        if (isRetry) {
                            Log.d(
                                LOG_TAG,
                                "Sync including cached data was successful. Clearing cache."
                            )
                            jsonCacheManager.clearCache()
                            eventDispatcher.sendEvent("syncFailureRetrySucceeded", 1)
                            Log.d(LOG_TAG, "syncFailureRetrySucceeded event fired.")
                        }
                    } ?: run {
                        Log.w(
                            LOG_TAG,
                            "Server response successful (${response.code}) but body was null."
                        )
                        syncResultListener.onSyncSuccessClearData()
                        if (isRetry) {
                            Log.d(
                                LOG_TAG,
                                "Sync including cached data successful (null body). Clearing cache."
                            )
                            jsonCacheManager.clearCache()
                            eventDispatcher.sendEvent("syncFailureRetrySucceeded", 1)
                        }
                    }
                } else {
                    Log.e(
                        LOG_TAG,
                        "Server sync failed with code: ${response.code}. Response: ${
                            response.body?.string()?.take(500)
                        }"
                    )
                    Log.d(LOG_TAG, "Caching failed sync request.")
                    jsonCacheManager.saveCachedData(listOf(combinedData))
                    eventDispatcher.sendEvent("syncFailedAndCached", 1)
                    Log.d(LOG_TAG, "syncFailedAndCached event fired.")
                }
            } catch (e: IOException) {
                Log.e(LOG_TAG, "Network IOException during sync: ${e.message}")
                // Cache the combined data on network error
                Log.d(LOG_TAG, "Caching failed sync request due to network error.")
                jsonCacheManager.saveCachedData(listOf(combinedData))
                eventDispatcher.sendEvent("syncFailedAndCached", 1)
                Log.d(LOG_TAG, "syncFailedAndCached event fired.")
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Unexpected exception during sync: ${e.message}", e)
                Log.d(LOG_TAG, "Caching failed sync request due to unexpected exception.")
                jsonCacheManager.saveCachedData(listOf(combinedData))
                eventDispatcher.sendEvent("syncFailedAndCached", 1)
                Log.d(LOG_TAG, "syncFailedAndCached event fired.")
            }
        }
    }
}