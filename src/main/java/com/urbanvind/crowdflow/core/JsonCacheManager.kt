package com.urbanvind.crowdflow.core

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

class JsonCacheManager(private val context: Context) {

    private val cacheFileName = "crowdflow_requests_cache.json"
    private val gson = Gson()

    private companion object {
        private const val MAX_CACHED_ENTRIES = 200
    }

    private fun getCacheFile(): File {
        return File(context.filesDir, cacheFileName)
    }

    fun saveCachedData(data: List<Map<String, Any>>) {
        val existingData = loadCachedData().toMutableList()

        existingData.addAll(data)

        while (existingData.size > MAX_CACHED_ENTRIES) {
            existingData.removeAt(0) // remove oldest first
        }

        val json = gson.toJson(existingData)
        getCacheFile().writeText(json)
    }

    fun loadCachedData(): List<Map<String, Any>> {
        val file = getCacheFile()
        if (!file.exists()) {
            return emptyList()
        }
        return try {
            val content = file.readText()
            if (content.isNotEmpty()) {
                val type =
                    object : com.google.gson.reflect.TypeToken<Array<Map<String, Any>>>() {}.type
                gson.fromJson<Array<Map<String, Any>>>(content, type).toList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("JsonCacheManager", "Failed to load cached data: ${e.message}")
            emptyList()
        }
    }

    fun clearCache() {
        val file = getCacheFile()
        if (file.exists()) {
            file.delete()
        }
    }

    fun hasCachedData(): Boolean {
        return loadCachedData().isNotEmpty()
    }
}
