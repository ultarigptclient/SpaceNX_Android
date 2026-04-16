package net.spacenx.messenger.ui.bridge.handler

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.spacenx.messenger.data.remote.api.ApiClient
import net.spacenx.messenger.data.repository.NotiRepository
import net.spacenx.messenger.ui.bridge.BridgeContext
import org.json.JSONArray
import org.json.JSONObject

class NotiHandler(
    private val ctx: BridgeContext,
    private val notiRepo: NotiRepository
) {
    companion object {
        private const val TAG = "NotiHandler"
    }

    suspend fun handle(action: String, params: Map<String, Any?>) {
        when (action) {
            "syncNoti" -> handleSyncNoti()
            "loadMoreNotis" -> handleLoadMoreNotis(params)
            "getNotiCounts" -> handleGetNotiCounts()
            "readNoti" -> handleReadNoti(params)
            "deleteNoti" -> handleDeleteNoti(params)
        }
    }

    private suspend fun handleSyncNoti() {
        if (ctx.guardDbNotReady("syncNoti")) return
        try {
            withContext(Dispatchers.IO) {
                try { notiRepo.syncNoti() } catch (e: Exception) {
                    Log.w(TAG, "syncNoti REST failed, using cached data: ${e.message}")
                }
            }
            val notis = withContext(Dispatchers.IO) { notiRepo.getNotiListPaged(50, 0) }
            val counts = withContext(Dispatchers.IO) { notiRepo.getCounts() }
            val notisArray = JSONArray().apply { notis.forEach { put(JSONObject(it)) } }
            ctx.resolveToJs("syncNoti", JSONObject().apply {
                put("errorCode", 0)
                put("notis", notisArray)
                put("counts", JSONObject(counts))
            })
        } catch (e: Exception) {
            ctx.rejectToJs("syncNoti", e.message)
        }
    }

    private suspend fun handleLoadMoreNotis(params: Map<String, Any?>) {
        try {
            val limit = ctx.paramInt(params, "limit", 50)
            val offset = ctx.paramInt(params, "offset", 0)
            val notis = withContext(Dispatchers.IO) { notiRepo.getNotiListPaged(limit, offset) }
            val notisArray = JSONArray().apply { notis.forEach { put(JSONObject(it)) } }
            ctx.resolveToJs("loadMoreNotis", JSONObject().put("errorCode", 0).put("notis", notisArray))
        } catch (e: Exception) {
            ctx.rejectToJs("loadMoreNotis", e.message)
        }
    }

    private suspend fun handleGetNotiCounts() {
        try {
            val counts = withContext(Dispatchers.IO) { notiRepo.getCounts() }
            ctx.resolveToJs("getNotiCounts", JSONObject().put("errorCode", 0).put("counts", JSONObject(counts)))
        } catch (e: Exception) {
            ctx.rejectToJs("getNotiCounts", e.message)
        }
    }

    private suspend fun handleReadNoti(params: Map<String, Any?>) {
        try {
            val notiCodes = ctx.paramList(params, "notiCode")
            withContext(Dispatchers.IO) {
                notiCodes.forEach { notiRepo.markRead(it) }
            }
            val result = withContext(Dispatchers.IO) {
                try {
                    val readUserId = ctx.paramStr(params, "readUserId").ifEmpty { ctx.appConfig.getSavedUserId() ?: "" }
                    val body = JSONObject().apply {
                        put("notiCode", JSONArray(notiCodes))
                        put("readUserId", readUserId)
                    }
                    val res = ApiClient.postJson(ctx.appConfig.getEndpointByPath("/noti/readnoti"), body)
                    ctx.updateCrudOffset("readnoti", res)
                    res
                } catch (e: Exception) {
                    Log.w(TAG, "readNoti REST failed: ${e.message}")
                    JSONObject().put("errorCode", 0)
                }
            }
            ctx.resolveToJs("readNoti", result)
        } catch (e: Exception) {
            ctx.rejectToJs("readNoti", e.message)
        }
    }

    private suspend fun handleDeleteNoti(params: Map<String, Any?>) {
        try {
            val notiCodes = ctx.paramList(params, "notiCode")
            withContext(Dispatchers.IO) {
                notiCodes.forEach { notiRepo.deleteNoti(it) }
            }
            val result = withContext(Dispatchers.IO) {
                try {
                    val body = JSONObject().apply {
                        put("notiCode", JSONArray(notiCodes))
                    }
                    ApiClient.postJson(ctx.appConfig.getEndpointByPath("/noti/deletenoti"), body)
                } catch (e: Exception) {
                    Log.w(TAG, "deleteNoti REST failed: ${e.message}")
                    JSONObject().put("errorCode", 0)
                }
            }
            ctx.resolveToJs("deleteNoti", result)
        } catch (e: Exception) {
            ctx.rejectToJs("deleteNoti", e.message)
        }
    }
}
