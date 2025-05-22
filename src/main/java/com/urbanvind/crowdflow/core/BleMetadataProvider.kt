package com.urbanvind.crowdflow.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.yaml.snakeyaml.Yaml
import java.io.InputStreamReader
import java.util.Locale

object BleMetadataProvider {
    private const val logTag = "BleMetadataProvider"
    val companyIdentifiers: MutableMap<Int, String> = HashMap()
    val adTypes: MutableMap<Int, String> = HashMap()

    suspend fun init(context: Context) {
        withContext(Dispatchers.IO) {
            loadCompanyIdentifiers(context)
            loadAdTypes(context)
            Log.i(logTag, "Data loading completed.")
        }
    }

    private fun loadCompanyIdentifiers(context: Context) {
        try {
            val assetManager = context.assets
            val inputStream = assetManager.open("company_identifiers.yaml")
            val yaml = Yaml()
            val data = yaml.load<Map<String, Any>>(InputStreamReader(inputStream))
            val companyIdentifiersList = data["company_identifiers"] as List<Map<String, Any>>
            for (item in companyIdentifiersList) {
                val value = item["value"]
                val name = item["name"] as String
                val valueInt = when (value) {
                    is Int -> value
                    is String -> value.toIntOrNull()
                    else -> null
                }
                if (valueInt != null) {
                    companyIdentifiers[valueInt] = name
                }
            }
            Log.i(logTag, "Loaded ${companyIdentifiers.size} company identifiers")
        } catch (e: Exception) {
            Log.e(logTag, "Error loading company identifiers: ${e.message}")
        }
    }

    private fun loadAdTypes(context: Context) {
        try {
            val assetManager = context.assets
            val inputStream = assetManager.open("ad_types.yaml")
            val yaml = Yaml()
            val data = yaml.load<Map<String, Any>>(InputStreamReader(inputStream))
            val adTypesList = data["ad_types"] as List<Map<String, Any>>
            for (item in adTypesList) {
                val value = item["value"]
                val name = item["name"] as String
                val valueInt = when (value) {
                    is Int -> value
                    is String -> value.toIntOrNull()
                    else -> null
                }
                if (valueInt != null) {
                    val formattedName =
                        name.replace(" ", "_").replace("-", "").lowercase(Locale.getDefault())
                    adTypes[valueInt] = formattedName
                }
            }
            Log.i(logTag, "Loaded ${adTypes.size} AD types")
        } catch (e: Exception) {
            Log.e(logTag, "Error loading AD types: ${e.message}")
        }
    }
}
