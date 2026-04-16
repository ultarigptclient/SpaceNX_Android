package net.spacenx.messenger.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.data.remote.api.ApiClient
import org.json.JSONObject

class StatusRepository(
    private val appConfig: AppConfig,
    private val sessionManager: SocketSessionManager
) {
    companion object {
        private const val TAG = "StatusRepository"
    }

    suspend fun setPresence(userId: String, status: Int): String {
        return withContext(Dispatchers.IO) {
            try {
                val token = awaitToken()
                val statusApi = ApiClient.createStatusApiFromBaseUrl(appConfig.getRestBaseUrl(), token)
                val response = statusApi.setPresence(userId, status)

                if (!response.isSuccessful) {
                    Log.e(TAG, "setPresence HTTP error: ${response.code()}")
                    return@withContext JSONObject().apply {
                        put("errorCode", -1)
                        put("errorMessage", "HTTP ${response.code()}")
                    }.toString()
                }

                val rawJson = response.body()?.string() ?: "{}"
                Log.d(TAG, "setPresence response: $rawJson")
                rawJson
            } catch (e: Exception) {
                Log.e(TAG, "setPresence error: ${e.message}", e)
                JSONObject().apply {
                    put("errorCode", -1)
                    put("errorMessage", e.message ?: "Unknown error")
                }.toString()
            }
        }
    }

    private suspend fun awaitToken(): String? {
        if (sessionManager.jwtToken != null) return sessionManager.jwtToken
        Log.d(TAG, "Waiting for JWT token...")
        return withTimeoutOrNull(10_000L) { sessionManager.jwtTokenDeferred.await() }
    }
}
