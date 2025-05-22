package com.urbanvind.crowdflow.core

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class SharedPreferencesManager(private val prefs: SharedPreferences) {

    data class ScheduleRule(
        val day: String,
        val startTime: String,
        val endTime: String
    )

    data class CityGeoRestriction(
        val id: Int,
        val name: String,
        val minLat: Double,
        val maxLat: Double,
        val minLong: Double,
        val maxLong: Double
    )

    private val gson = Gson()

    var isScanning: Boolean
        get() = prefs.getBoolean("isScanning", false)
        set(value) = prefs.edit().putBoolean("isScanning", value).apply()

    var isFirstLaunch: Boolean
        get() = prefs.getBoolean("isFirstLaunch", true)
        set(value) = prefs.edit().putBoolean("isFirstLaunch", value).apply()

    var isLocked: Boolean
        get() = prefs.getBoolean("isLocked", true)
        set(value) = prefs.edit().putBoolean("isLocked", value).apply()

    var userUUID: String
        get() = prefs.getString("userUUID", null) ?: ""
        set(value) = prefs.edit().putString("userUUID", value).apply()

    var server: String
        get() = prefs.getString("server", "https://prod.urbanvind.com")
            ?: "https://prod.urbanvind.com"
        set(value) = prefs.edit().putString("server", value).apply()

    var devStep: String
        get() = prefs.getString("devStep", "prod") ?: "prod"
        set(value) = prefs.edit().putString("devStep", value).apply()

    var requireEstimationResult: Boolean
        get() = prefs.getBoolean("requireEstimationResult", true)
        set(value) = prefs.edit().putBoolean("requireEstimationResult", value).apply()

    var shouldRefreshSalt: Boolean
        get() = prefs.getBoolean("shouldRefreshSalt", true)
        set(value) = prefs.edit().putBoolean("shouldRefreshSalt", value).apply()

    var vehicleType: String
        get() = prefs.getString("vehicleType", null) ?: ""
        set(value) = prefs.edit().putString("vehicleType", value).apply()

    var isScheduledScanEnabled: Boolean
        get() = prefs.getBoolean("isScheduledScanEnabled", false)
        set(value) = prefs.edit().putBoolean("isScheduledScanEnabled", value).apply()

    var scheduleRules: List<ScheduleRule>
        get() {
            val json = prefs.getString("scheduleRules", null)
            return if (json != null) {
                val type = object : TypeToken<List<ScheduleRule>>() {}.type
                gson.fromJson(json, type)
            } else {
                emptyList()
            }
        }
        set(value) {
            val json = gson.toJson(value)
            prefs.edit().putString("scheduleRules", json).apply()
        }

    var isGeoRestrictionEnabled: Boolean
        get() = prefs.getBoolean("isGeoRestrictionEnabled", false)
        set(value) = prefs.edit().putBoolean("isGeoRestrictionEnabled", value).apply()

    var restrictedCities: List<CityGeoRestriction>
        get() {
            val json = prefs.getString("restrictedCities", null)
            return if (json != null) {
                val type = object : TypeToken<List<CityGeoRestriction>>() {}.type
                gson.fromJson(json, type)
            } else {
                emptyList()
            }
        }
        set(value) {
            val json = gson.toJson(value)
            prefs.edit().putString("restrictedCities", json).apply()
        }

    var isDataPrivacyEnabled: Boolean
        get() = prefs.getBoolean("isDataPrivacyEnabled", false)
        set(value) = prefs.edit().putBoolean("isDataPrivacyEnabled", value).apply()

    var calibrationCount: Int
        get() = prefs.getInt("calibrationCount", 0)
        set(value) = prefs.edit().putInt("calibrationCount", value).apply()

    var calibrationBoarding: Int
        get() = prefs.getInt("calibrationBoarding", 0)
        set(value) = prefs.edit().putInt("calibrationBoarding", value).apply()

    var calibrationAlighting: Int
        get() = prefs.getInt("calibrationAlighting", 0)
        set(value) = prefs.edit().putInt("calibrationAlighting", value).apply()

    var calibrationBoardingTotal: Int
        get() = prefs.getInt("calibrationBoardingTotal", 0)
        set(value) = prefs.edit().putInt("calibrationBoardingTotal", value).apply()

    var calibrationAlightingTotal: Int
        get() = prefs.getInt("calibrationAlightingTotal", 0)
        set(value) = prefs.edit().putInt("calibrationAlightingTotal", value).apply()

    var calibrationTripName: String
        get() = prefs.getString("calibrationTripName", "") ?: ""
        set(value) = prefs.edit().putString("calibrationTripName", value).apply()

    var isDebugModeEnabled: Boolean
        get() = prefs.getBoolean("isDebugModeEnabled", false)
        set(value) = prefs.edit().putBoolean("isDebugModeEnabled", value).apply()

    var isCalibrationModeEnabled: Boolean
        get() = prefs.getBoolean("isCalibrationModeEnabled", false)
        set(value) = prefs.edit().putBoolean("isCalibrationModeEnabled", value).apply()

    var shouldDisplayDebugInfo: Boolean
        get() = prefs.getBoolean("shouldDisplayDebugInfo", false)
        set(value) = prefs.edit().putBoolean("shouldDisplayDebugInfo", value).apply()

    // Calibration methods

    fun setManualCalibrationCount(value: Int) {
        calibrationCount = value
    }

    fun incrementCalibrationCount() {
        calibrationCount++
    }

    fun decrementCalibrationCount() {
        if (calibrationCount > 0) calibrationCount--
    }

    fun incrementCalibrationBoarding() {
        calibrationBoarding++
        calibrationBoardingTotal++
        calibrationCount++
    }

    fun decrementCalibrationBoarding() {
        if (calibrationCount > 0 && calibrationBoarding > 0) {
            calibrationBoarding--
            calibrationBoardingTotal--
            calibrationCount--
        }
    }

    fun incrementCalibrationAlighting() {
        if (calibrationCount > 0) {
            calibrationAlighting++
            calibrationAlightingTotal++
            calibrationCount--
        }
    }

    fun decrementCalibrationAlighting() {
        if (calibrationAlighting > 0) {
            calibrationAlighting--
            calibrationAlightingTotal--
            calibrationCount++
        }
    }

    fun nextStop() {
        calibrationBoarding = 0
        calibrationAlighting = 0
    }

    fun resetCalibration() {
        calibrationBoarding = 0
        calibrationAlighting = 0
        calibrationBoardingTotal = 0
        calibrationAlightingTotal = 0
        calibrationCount = 0
    }

    fun clearSharedPreferences() {
        prefs.edit().clear().apply()
    }

    fun clearScheduleRules() {
        prefs.edit().remove("scheduleRules").apply()
    }
}
