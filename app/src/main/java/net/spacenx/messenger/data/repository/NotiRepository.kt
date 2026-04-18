package net.spacenx.messenger.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.spacenx.messenger.BuildConfig
import net.spacenx.messenger.util.FileLogger
import kotlinx.coroutines.withTimeoutOrNull
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.common.AppConfig.Companion.EP_COMM_SYNC_NOTI
import net.spacenx.messenger.data.local.DatabaseProvider
import net.spacenx.messenger.data.local.entity.NotiEntity
import net.spacenx.messenger.data.local.entity.SyncMetaEntity
import net.spacenx.messenger.data.remote.api.ApiClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject

/**
 * 알림(Noti) 동기화 + 로컬 DB
 */
class NotiRepository(
    private val dbProvider: DatabaseProvider,
    private val appConfig: AppConfig,
    private val sessionManager: SocketSessionManager
) {
    companion object {
        private const val TAG = "NotiRepository"
        private const val SYNC_META_KEY = "notiEventOffset"
    }

    private val syncMutex = Mutex()

    // ── syncNoti: REST delta sync → DB 저장 ──

    suspend fun syncNoti(): Map<String, Any> {
        if (!syncMutex.tryLock()) { Log.d(TAG, "syncNoti already in progress, skipping"); return mapOf("errorCode" to 0, "skipped" to true) }
        try {
        return withContext(Dispatchers.IO) {
            try {
                val token = awaitToken()
                val notiDb = dbProvider.getNotiDatabase()
                val lastOffset = notiDb.syncMetaDao().getValue(SYNC_META_KEY) ?: 0L
                val userId = appConfig.getSavedUserId() ?: ""

                Log.d(TAG, "syncNoti: userId=$userId, lastOffset=$lastOffset")
                FileLogger.log(TAG, "syncNoti REQ userId=$userId offset=$lastOffset")

                val commApi = ApiClient.createCommApiFromBaseUrl(appConfig.getRestBaseUrl(), token)
                val endpoint = appConfig.getEndpoint(EP_COMM_SYNC_NOTI, "api/noti/syncnoti")

                val requestJson = JSONObject().apply {
                    put("userId", userId)
                    put("notiEventOffset", lastOffset)
                    put("reset", false)
                }
                val body = requestJson.toString()
                    .toRequestBody("application/json".toMediaType())

                val response = commApi.post(endpoint, body)

                if (!response.isSuccessful) {
                    if (response.code() == 404) {
                        Log.d(TAG, "syncNoti: endpoint not available (404), skipping")
                        return@withContext mapOf<String, Any>("errorCode" to 0, "skipped" to true)
                    }
                    Log.e(TAG, "syncNoti HTTP error: ${response.code()}")
                    return@withContext mapOf<String, Any>("errorCode" to -1, "errorMessage" to "HTTP ${response.code()}")
                }

                val rawJson = response.body()?.string() ?: "{}"
                val json = JSONObject(rawJson)
                val errorCode = json.optInt("errorCode", -1)

                if (BuildConfig.DEBUG) Log.d(TAG, "syncNoti get json=$json")

                if (errorCode != 0) {
                    return@withContext mapOf<String, Any>("errorCode" to errorCode)
                }

                val lastEventId = json.optLong("lastEventId", 0L)
                val dataArray = json.optJSONArray("data")
                var upsertCount = 0
                var readCount = 0
                var deleteCount = 0

                if (dataArray != null) {
                    for (i in 0 until dataArray.length()) {
                        val event = dataArray.getJSONObject(i)
                        val eventType = event.optString("eventType", "").uppercase()
                        val notiCode = event.optString("notiCode", "")

                        when (eventType) {
                            "ADD" -> {
                                val entity = jsonToNotiEntity(event)
                                notiDb.notiDao().insert(entity)
                                upsertCount++
                            }
                            "READ" -> {
                                notiDb.notiDao().markAsRead(notiCode)
                                readCount++
                            }
                            "DEL" -> {
                                notiDb.notiDao().deleteByNotiCode(notiCode)
                                deleteCount++
                            }
                        }
                    }
                }

                // Update syncMeta offset
                if (lastEventId > lastOffset) {
                    notiDb.syncMetaDao().insert(SyncMetaEntity(SYNC_META_KEY, lastEventId))
                }

                Log.d(TAG, "syncNoti complete: upsert=$upsertCount, read=$readCount, delete=$deleteCount, lastEventId=$lastEventId")
                FileLogger.log(TAG, "syncNoti DONE upsert=$upsertCount read=$readCount delete=$deleteCount lastEventId=$lastEventId")
                mapOf<String, Any>(
                    "errorCode" to 0,
                    "upsertCount" to upsertCount,
                    "readCount" to readCount,
                    "deleteCount" to deleteCount
                )
            } catch (e: Exception) {
                Log.e(TAG, "syncNoti error: ${e.message}", e)
                FileLogger.log(TAG, "syncNoti ERROR ${e.message}")
                mapOf<String, Any>("errorCode" to -1, "errorMessage" to (e.message ?: "Unknown error"))
            }
        }
        } finally { syncMutex.unlock() }
    }

    // ── getNotiList: 로컬 DB 조회 ──

    suspend fun getNotiList(): List<Map<String, Any>> {
        return withContext(Dispatchers.IO) {
            try {
                val notiDb = dbProvider.getNotiDatabase()
                val allNotis = notiDb.notiDao().getAll()

                allNotis.map { noti ->
                    mapOf<String, Any>(
                        "notiCode" to noti.notiCode,
                        "systemCode" to noti.systemCode,
                        "systemName" to noti.systemName,
                        "senderName" to noti.senderName,
                        "notiTitle" to noti.notiTitle,
                        "notiContents" to noti.notiContents,
                        "linkUrl" to (noti.linkUrl ?: ""),
                        "alertType" to noti.alertType,
                        "sendDate" to noti.sendDate,
                        "read" to noti.read
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "getNotiList error: ${e.message}", e)
                emptyList()
            }
        }
    }

    suspend fun getNotiListPaged(limit: Int, offset: Int): List<Map<String, Any>> {
        return withContext(Dispatchers.IO) {
            try {
                val notiDb = dbProvider.getNotiDatabase()
                notiDb.notiDao().getAll(limit, offset).map { noti ->
                    mapOf<String, Any>(
                        "notiCode" to noti.notiCode,
                        "systemCode" to noti.systemCode,
                        "systemName" to noti.systemName,
                        "senderName" to noti.senderName,
                        "notiTitle" to noti.notiTitle,
                        "notiContents" to noti.notiContents,
                        "linkUrl" to (noti.linkUrl ?: ""),
                        "alertType" to noti.alertType,
                        "sendDate" to noti.sendDate,
                        "read" to noti.read
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "getNotiListPaged error: ${e.message}", e)
                emptyList()
            }
        }
    }

    suspend fun getCounts(): Map<String, Int> {
        return withContext(Dispatchers.IO) {
            try {
                val dao = dbProvider.getNotiDatabase().notiDao()
                mapOf("total" to dao.countAll(), "unread" to dao.countUnread())
            } catch (e: Exception) {
                Log.e(TAG, "getCounts error: ${e.message}", e)
                mapOf("total" to 0, "unread" to 0)
            }
        }
    }

    suspend fun markRead(notiCode: String) {
        withContext(Dispatchers.IO) {
            try {
                dbProvider.getNotiDatabase().notiDao().markRead(notiCode)
            } catch (e: Exception) {
                Log.e(TAG, "markRead error: ${e.message}", e)
            }
        }
    }

    suspend fun deleteNoti(notiCode: String) {
        withContext(Dispatchers.IO) {
            try {
                dbProvider.getNotiDatabase().notiDao().deleteByNotiCode(notiCode)
            } catch (e: Exception) {
                Log.e(TAG, "deleteNoti error: ${e.message}", e)
            }
        }
    }

    // ── handlePushEvent: 소켓 push → 로컬 DB ──

    suspend fun handlePushEvent(data: JSONObject) {
        withContext(Dispatchers.IO) {
            try {
                val notiDb = dbProvider.getNotiDatabase()
                val entity = jsonToNotiEntity(data)
                notiDb.notiDao().insert(entity)
                Log.d(TAG, "handlePushEvent: upserted notiCode=${entity.notiCode}")
            } catch (e: Exception) {
                Log.e(TAG, "handlePushEvent error: ${e.message}", e)
            }
        }
    }

    // ── JSON → Entity 변환 ──

    private fun jsonToNotiEntity(json: JSONObject): NotiEntity {
        return NotiEntity(
            notiCode = json.optString("notiCode", ""),
            systemCode = json.optString("systemCode", ""),
            systemName = json.optString("systemName", ""),
            senderName = json.optString("senderName", ""),
            notiTitle = json.optString("notiTitle", ""),
            notiContents = json.optString("notiContents", ""),
            linkUrl = if (json.has("linkUrl")) json.optString("linkUrl") else null,
            alertType = json.optString("alertType", ""),
            sendDate = json.optLong("sendDate", 0L),
            read = json.optInt("read", 0)
        )
    }

    private suspend fun awaitToken(): String? {
        if (sessionManager.jwtToken != null) return sessionManager.jwtToken
        Log.d(TAG, "Waiting for JWT token...")
        return withTimeoutOrNull(10_000L) { sessionManager.jwtTokenDeferred.await() }
    }
}
