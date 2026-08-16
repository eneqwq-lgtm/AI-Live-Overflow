package com.example.deskpet.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object SupabaseClient {

    private var supabaseUrl: String? = null
    private var supabaseKey: String? = null

    fun configure(url: String, key: String) {
        supabaseUrl = url
        supabaseKey = key
    }

    fun isConfigured(): Boolean = !supabaseUrl.isNullOrEmpty() && !supabaseKey.isNullOrEmpty()

    suspend fun insert(table: String, data: JSONObject): Boolean {
        if (!isConfigured()) return false

        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$supabaseUrl/rest/v1/$table")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", supabaseKey)
                conn.setRequestProperty("Authorization", "Bearer $supabaseKey")
                conn.setRequestProperty("Prefer", "return=minimal")
                conn.doOutput = true
                conn.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
                val responseCode = conn.responseCode
                conn.disconnect()
                responseCode in 200..299
            } catch (e: Exception) {
                false
            }
        }
    }

    suspend fun fetchLatest(table: String, orderBy: String = "created_at", limit: Int = 1): JSONObject? {
        if (!isConfigured()) return null

        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$supabaseUrl/rest/v1/$table?order=$orderBy.desc&limit=$limit")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("apikey", supabaseKey)
                conn.setRequestProperty("Authorization", "Bearer $supabaseKey")
                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val arr = org.json.JSONArray(response)
                if (arr.length() > 0) arr.getJSONObject(0) else null
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun logGesture(type: String, x: Int? = null, y: Int? = null) {
        if (!isConfigured()) return
        val data = JSONObject().apply {
            put("gesture_type", type)
            x?.let { put("x", it) }
            y?.let { put("y", it) }
        }
        insert("gesture_log", data)
    }

    suspend fun logAppUsage(packageName: String) {
        if (!isConfigured()) return
        val data = JSONObject().apply {
            put("package_name", packageName)
        }
        insert("app_usage", data)
    }

    suspend fun getPetState(): JSONObject? {
        return fetchLatest("pet_state", "updated_at")
    }
}
