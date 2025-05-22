package com.urbanvind.crowdflow.core

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LocationManager(private val context: Context) {

    private val logTag = "LocationManagerHelper"
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var latestLocation: Location? = null
    private var locationCallback: LocationCallback? = null

    // Retrieves the current location. If the last known location is null, actively requests a new location.
    suspend fun getCurrentLocation(): Location? {
        Log.d(logTag, "getCurrentLocation: Attempting to retrieve location.")

        return try {
            val location = getFusedLocationCurrentLocation()
            if (location != null) {
                latestLocation = location
                Log.d(
                    logTag,
                    "getCurrentLocation: Location acquired: Lat=${location.latitude}, Lon=${location.longitude}"
                )
            } else {
                Log.d(logTag, "getCurrentLocation: Location is null.")
            }
            location
        } catch (e: Exception) {
            Log.e(logTag, "getCurrentLocation: Exception while acquiring location: ${e.message}")
            null
        }
    }


    @SuppressLint("MissingPermission")
    private suspend fun getFusedLocationCurrentLocation(): Location? {
        return suspendCancellableCoroutine { cont ->
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        Log.d(
                            logTag,
                            "getFusedLocationCurrentLocation: Success. Location: Lat=${location.latitude}, Lon=${location.longitude}"
                        )
                        cont.resume(location)
                    } else {
                        Log.d(logTag, "getFusedLocationCurrentLocation: Location is null.")
                        cont.resume(null)
                    }
                }
                .addOnFailureListener { exception ->
                    Log.e(
                        logTag,
                        "getFusedLocationCurrentLocation: Failure. Exception: ${exception.message}"
                    )
                    cont.resumeWithException(exception)
                }

            cont.invokeOnCancellation {
                // Note: getCurrentLocation() cannot be cancelled as per documentation.
                Log.d(logTag, "getFusedLocationCurrentLocation: Coroutine cancelled.")
            }
        }
    }

    // Starts continuous location updates.
    @SuppressLint("MissingPermission")
    fun startLocationUpdates(onLocationChanged: (Location) -> Unit) {
        if (locationCallback != null) {
            // Already requesting updates
            Log.d(logTag, "startLocationUpdates: Already requesting location updates.")
            return
        }

        Log.d(logTag, "startLocationUpdates: Starting continuous location updates.")
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            5000L // 5 seconds interval
        ).setMinUpdateDistanceMeters(5f).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                locationResult.lastLocation?.let { location ->
                    latestLocation = location
                    Log.d(
                        logTag,
                        "startLocationUpdates: Location update received: Lat=${location.latitude}, Lon=${location.longitude}"
                    )
                    onLocationChanged(location)
                }
            }

            override fun onLocationAvailability(locationAvailability: LocationAvailability) {
                super.onLocationAvailability(locationAvailability)
                if (!locationAvailability.isLocationAvailable) {
                    Log.w(logTag, "startLocationUpdates: Location is not available.")
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback!!,
            Looper.getMainLooper()
        )
        Log.d(logTag, "startLocationUpdates: Continuous location updates started.")
    }

    // Stops continuous location updates.
    fun stopLocationUpdates() {
        locationCallback?.let {
            Log.d(logTag, "stopLocationUpdates: Stopping continuous location updates.")
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
            Log.d(logTag, "stopLocationUpdates: Continuous location updates stopped.")
        } ?: run {
            Log.d(logTag, "stopLocationUpdates: No location updates to stop.")
        }
    }
}
