package com.urbanvind.crowdflow.core

import android.content.Context
import android.location.Location
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.BufferedReader
import java.io.File
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class GeoRestrictionManager(private val context: Context) {

    private val gson = Gson()
    private val logTag = "GeoRestrictionManager"

    fun isGeoRestrictionEnabled(): Boolean {
        val prefs = context.getSharedPreferences("ForegroundServicePrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("isGeoRestrictionEnabled", false)
    }

    fun isLocationWithinGeoRestrictions(location: Location?): Boolean {
        if (location == null) {
            Log.d(logTag, "Location is null, cannot check geo-restriction.")
            return false
        }

        val prefs = context.getSharedPreferences("ForegroundServicePrefs", Context.MODE_PRIVATE)
        val json = prefs.getString("restrictedCities", null)
        if (json != null) {
            val type =
                object : TypeToken<List<SharedPreferencesManager.CityGeoRestriction>>() {}.type
            val restrictedCities: List<SharedPreferencesManager.CityGeoRestriction> =
                gson.fromJson(json, type)

            for (city in restrictedCities) {
                if (location.latitude in city.minLat..city.maxLat &&
                    location.longitude in city.minLong..city.maxLong
                ) {
                    Log.d(logTag, "Current location is within the boundaries of ${city.name}.")
                    return true
                }
            }
        }
        Log.d(logTag, "Current location is outside all restricted cities.")
        return false
    }

    fun isLocationNearTransitRoute(location: Location?): Boolean {
        if (location == null) {
            Log.d(logTag, "Location is null, cannot check proximity to transit route.")
            return false
        }

        val prefs = context.getSharedPreferences("ForegroundServicePrefs", Context.MODE_PRIVATE)
        val json = prefs.getString("restrictedCities", null)
        if (json == null) {
            Log.d(logTag, "No restricted cities found in SharedPreferences.")
            return false
        }

        val type = object : TypeToken<List<SharedPreferencesManager.CityGeoRestriction>>() {}.type
        val restrictedCities: List<SharedPreferencesManager.CityGeoRestriction> =
            gson.fromJson(json, type)

        for (city in restrictedCities) {
            if (location.latitude in city.minLat..city.maxLat &&
                location.longitude in city.minLong..city.maxLong
            ) {
                val gtfsFileName = "gtfs_${city.id}.txt"
                val gtfsFile = File(context.filesDir, gtfsFileName)

                Log.d(logTag, "Checking GTFS file: $gtfsFileName")

                if (!gtfsFile.exists()) {
                    Log.d(logTag, "GTFS file $gtfsFileName does not exist.")
                    continue // Move to the next city if the file doesn't exist
                }

                var isNearby = false

                try {
                    BufferedReader(gtfsFile.reader()).use { reader ->
                        val header = reader.readLine() ?: return@use
                        val headers = header.split(",")

                        val latIndex = headers.indexOf("shape_pt_lat")
                        val lonIndex = headers.indexOf("shape_pt_lon")

                        Log.d(
                            logTag,
                            "Latitude column index: $latIndex, Longitude column index: $lonIndex"
                        )

                        if (latIndex == -1 || lonIndex == -1) {
                            Log.d(logTag, "Latitude or longitude column not found in $gtfsFileName")
                            return@use
                        }

                        reader.forEachLine { line ->
                            val columns = line.split(",")
                            if (columns.size > maxOf(latIndex, lonIndex)) {
                                val shapeLat = columns[latIndex].toDoubleOrNull()
                                val shapeLon = columns[lonIndex].toDoubleOrNull()

                                if (shapeLat != null && shapeLon != null) {
                                    // Calculate the distance between the current location and the GTFS shape point
                                    val distance = haversine(
                                        location.latitude,
                                        location.longitude,
                                        shapeLat,
                                        shapeLon
                                    )

                                    val distanceThreshold = 300

                                    if (distance <= distanceThreshold) {
                                        Log.d(
                                            logTag,
                                            "Location is within $distanceThreshold meters of a transit route in GTFS file: $gtfsFileName."
                                        )
                                        isNearby = true
                                        return@forEachLine
                                    }
                                } else {
                                    Log.d(
                                        logTag,
                                        "Invalid latitude or longitude values in GTFS file: $gtfsFileName"
                                    )
                                }
                            } else {
                                Log.d(logTag, "Malformed line in GTFS file: $gtfsFileName: $line")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(logTag, "Error reading GTFS file: $gtfsFileName", e)
                }


                if (isNearby) {
                    Log.d(logTag, "Location is near a transit route.")
                    return true // Exit as soon as a nearby route is found
                } else {
                    Log.d(logTag, "Location is not near any transit route for city ${city.name}.")
                }
            }
        }

        Log.d(logTag, "Location is not near any transit route for any restricted city.")
        return false
    }


    // Haversine formula to calculate the distance between two points
    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // Radius of Earth in meters
        val dLat = (lat2 - lat1).toRadians()
        val dLon = (lon2 - lon1).toRadians()
        val a = sin(dLat / 2).pow(2) +
                cos(lat1.toRadians()) * cos(lat2.toRadians()) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c // Distance in meters
    }

    private fun Double.toRadians(): Double = this * (PI / 180)
}
