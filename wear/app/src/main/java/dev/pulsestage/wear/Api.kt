package dev.pulsestage.wear

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class ApiException(val status: Int, message: String) : Exception(message)

class Api(context: Context) {
    private val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    val origin: String get() = if (BuildConfig.DEBUG) preferences.getString("origin", BuildConfig.BACKEND_URL)!! else BuildConfig.BACKEND_URL
    val ingestToken: String get() = preferences.getString("ingestToken", "")!!
    val pairingCode: String get() = preferences.getString("pairingCode", "")!!
    val expiresAt: Long get() = preferences.getLong("expiresAt", 0)

    fun setOrigin(value: String) {
        val uri = URI(value.trim().trimEnd('/'))
        require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.isNullOrEmpty()) { "Masukkan alamat server tanpa path." }
        require(uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http")) { "Gunakan HTTPS." }
        preferences.edit().putString("origin", uri.toString()).apply()
    }

    fun request(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val connection = URL("$origin$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/json")
            if (ingestToken.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $ingestToken")
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            val result = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
            if (status !in 200..299) throw ApiException(status, result.optString("error", "Server tidak dapat diakses ($status)."))
            return result
        } finally { connection.disconnect() }
    }

    fun register(): JSONObject {
        val result = request("/api/devices", "POST")
        preferences.edit().putString("ingestToken", result.getString("ingestToken")).putString("deviceId", result.getString("deviceId")).apply()
        savePairing(result)
        return result
    }

    fun renewPairing() { savePairing(request("/api/device/pairing", "POST")) }
    private fun savePairing(data: JSONObject) {
        preferences.edit().putString("pairingCode", data.getString("pairingCode")).putLong("expiresAt", data.getLong("expiresAt")).apply()
    }
    fun clearRegistration() { preferences.edit().remove("ingestToken").remove("deviceId").remove("pairingCode").remove("expiresAt").apply() }
}
