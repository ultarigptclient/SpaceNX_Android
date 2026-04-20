package net.spacenx.messenger.ui.bridge.handler

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.spacenx.messenger.data.remote.api.ApiClient
import net.spacenx.messenger.data.repository.MessageRepository
import net.spacenx.messenger.ui.bridge.BridgeContext
import org.json.JSONArray
import org.json.JSONObject

class MessageHandler(
    private val ctx: BridgeContext,
    private val messageRepo: MessageRepository
) {
    companion object {
        private const val TAG = "MessageHandler"
    }

    suspend fun handle(action: String, params: Map<String, Any?>) {
        when (action) {
            "sendMessage" -> handleSendMessage(params)
            "readMessage" -> handleReadMessage(params)
            "deleteMessage" -> handleDeleteMessage(params)
            "syncMessage" -> handleSyncMessage(params)
            "fullSync" -> handleFullSync()
            "loadMoreMessages" -> handleLoadMoreMessages(params)
            "getMessageDetail" -> handleGetMessageDetail(params)
            "getMessageCounts" -> handleGetMessageCounts()
            "retrieveMessage" -> handleRetrieveMessage(params)
        }
    }

    private suspend fun handleSendMessage(params: Map<String, Any?>) {
        try {
            val body = ctx.paramsToJson(params).apply {
                if (!has("sendUserId") || optString("sendUserId").isEmpty()) {
                    put("sendUserId", ctx.appConfig.getSavedUserId() ?: "")
                }
            }
            val result = withContext(Dispatchers.IO) {
                ApiClient.postJson(ctx.appConfig.getEndpointByPath("/comm/sendmessage"), body)
            }
            withContext(Dispatchers.IO) { ctx.updateCrudOffset("sendmessage", result) }
            ctx.resolveToJs("sendMessage", result)
        } catch (e: Exception) {
            ctx.rejectToJs("sendMessage", e.message)
        }
    }

    private suspend fun handleReadMessage(params: Map<String, Any?>) {
        try {
            val result = withContext(Dispatchers.IO) {
                ApiClient.postJson(ctx.appConfig.getEndpointByPath("/comm/readmessage"), ctx.paramsToJson(params))
            }
            val messageCode = ctx.paramStr(params, "messageCode")
            if (messageCode.isNotEmpty() && result.optInt("errorCode", -1) == 0) {
                withContext(Dispatchers.IO) {
                    try { ctx.dbProvider.getMessageDatabase().messageDao().updateState(messageCode, 1) } catch (_: Exception) {}
                }
                // 쪽지 알림 취소 → 런처 뱃지 감소
                ctx.activity.notificationGroupManager.cancel("MSG")
            }
            withContext(Dispatchers.IO) { ctx.updateCrudOffset("readmessage", result) }
            ctx.resolveToJs("readMessage", result)
        } catch (e: Exception) {
            ctx.rejectToJs("readMessage", e.message)
        }
    }

    private suspend fun handleDeleteMessage(params: Map<String, Any?>) {
        try {
            val result = withContext(Dispatchers.IO) {
                ApiClient.postJson(ctx.appConfig.getEndpointByPath("/comm/deletemessage"), ctx.paramsToJson(params))
            }
            val messageCode = ctx.paramStr(params, "messageCode")
            if (messageCode.isNotEmpty() && result.optInt("errorCode", -1) == 0) {
                withContext(Dispatchers.IO) {
                    try { ctx.dbProvider.getMessageDatabase().messageDao().deleteByMessageCode(messageCode) } catch (_: Exception) {}
                }
            }
            withContext(Dispatchers.IO) { ctx.updateCrudOffset("deletemessage", result) }
            ctx.resolveToJs("deleteMessage", result)
        } catch (e: Exception) {
            ctx.rejectToJs("deleteMessage", e.message)
        }
    }

    private suspend fun handleSyncMessage(params: Map<String, Any?>) {
        if (ctx.guardDbNotReady("syncMessage")) return
        try {
            val sent = params["sent"] as? Boolean
            val limit = ctx.paramInt(params, "limit", 500)
            val offset = ctx.paramInt(params, "offset", 0)
            val messages = withContext(Dispatchers.IO) { messageRepo.getMessageList(sent, limit, offset) }
            Log.d(TAG, "syncMessage: returning ${messages.size} messages from local DB")
            val eventsArray = JSONArray()
            for (msg in messages) {
                val obj = JSONObject()
                val stateVal = msg["state"] as? Int ?: 0
                obj.put("messageCode", msg["messageCode"] ?: "")
                obj.put("sendUserId", msg["sendUserId"] ?: "")
                obj.put("title", msg["title"] ?: "")
                obj.put("contents", msg["contents"] ?: "")
                obj.put("rtfContents", msg["rtfContents"] ?: JSONObject.NULL)
                obj.put("sendDate", msg["sendDate"] ?: 0L)
                obj.put("scheduleDate", msg["scheduleDate"] ?: 0L)
                obj.put("state", stateVal)
                obj.put("read", (stateVal and 1) != 0)
                obj.put("important", (stateVal and 2) != 0)
                obj.put("messageType", msg["messageType"] ?: 0)
                obj.put("command", "SendMessageEvent")
                val receiversStr = msg["receivers"] as? String ?: "[]"
                // receivers 각 항목에 userName 추가 (openUserDetail 중복 호출 방지)
                val enrichedReceivers = try {
                    val arr = JSONArray(receiversStr)
                    for (k in 0 until arr.length()) {
                        val r = arr.optJSONObject(k) ?: continue
                        val rUserId = r.optString("userId", "")
                        if (rUserId.isNotEmpty() && !r.has("userName")) {
                            r.put("userName", ctx.userNameCache.resolve(rUserId))
                        }
                    }
                    arr.toString()
                } catch (_: Exception) { receiversStr }
                obj.put("receivers", enrichedReceivers)
                val attachInfoStr = msg["attachInfo"] as? String
                obj.put("attachInfo", if (!attachInfoStr.isNullOrEmpty()) attachInfoStr else JSONObject.NULL)
                obj.put("retrieved", msg["retrieved"] as? Boolean ?: false)
                val sendUserId = msg["sendUserId"] as? String ?: ""
                if (sendUserId.isNotEmpty()) obj.put("sendUserName", ctx.userNameCache.resolve(sendUserId))
                eventsArray.put(obj)
            }
            val codes = messages.mapNotNull { it["messageCode"] as? String }
            messageRepo.prefetchContents(ctx.scope, codes)

            val counts = withContext(Dispatchers.IO) { messageRepo.getCounts() }
            val result = JSONObject().apply {
                put("errorCode", 0)
                put("messages", eventsArray)
                put("counts", JSONObject(counts))
            }
            ctx.resolveToJs("syncMessage", result)
        } catch (e: Exception) {
            ctx.rejectToJs("syncMessage", e.message)
        }
    }

    private fun handleFullSync() {
        try {
            ctx.completedSyncs.clear()
            ctx.notifyReactOnce("syncStart")

            ctx.loginViewModel.userNameCache.clear()
            ctx.loginViewModel.syncOrgAndBuddy(ctx.appConfig.getSavedUserId() ?: "")
            val projectRepo = (ctx as? net.spacenx.messenger.ui.bridge.BridgeDispatcher)?.projectRepo
            ctx.loginViewModel.startBackgroundSync(
                notifyCallback = { event ->
                    ctx.notifyReact(event)
                    val required = setOf("orgReady", "buddyReady", "channelReady", "chatReady", "messageReady", "notiReady")
                    if (ctx.completedSyncs.containsAll(required)) {
                        ctx.notifyReactOnce("syncComplete")
                    }
                },
                syncStatusCallback = { item, status, count, total ->
                    val json = JSONObject().apply {
                        put("event", "syncStatus")
                        put("item", item)
                        put("status", status)
                        if (count != null) put("count", count)
                        if (total != null) put("total", total)
                    }.toString()
                    ctx.evalJsMain("window.postMessage('${ctx.esc(json)}')")
                },
                projectRepo = projectRepo
            )
            ctx.resolveToJs("fullSync", JSONObject().put("errorCode", 0))
        } catch (e: Exception) {
            ctx.rejectToJs("fullSync", e.message)
        }
    }

    private suspend fun handleLoadMoreMessages(params: Map<String, Any?>) {
        try {
            val folder = ctx.paramStr(params, "folder").ifEmpty { "inbox" }
            val limit = ctx.paramInt(params, "limit", 50)
            val offset = ctx.paramInt(params, "offset", 0)
            val messages = withContext(Dispatchers.IO) { messageRepo.getMessagesByFolder(folder, limit, offset) }
            val messagesArray = JSONArray().apply { messages.forEach { put(JSONObject(it.mapValues { (_, v) -> v ?: JSONObject.NULL })) } }
            ctx.resolveToJs("loadMoreMessages", JSONObject().put("errorCode", 0).put("messages", messagesArray))
        } catch (e: Exception) {
            ctx.rejectToJs("loadMoreMessages", e.message)
        }
    }

    private suspend fun handleGetMessageDetail(params: Map<String, Any?>) {
        try {
            val messageCode = ctx.paramStr(params, "messageCode")
            if (messageCode.isEmpty()) {
                ctx.rejectToJs("getMessageDetail", "messageCode required")
                return
            }
            withContext(Dispatchers.IO) {
                val needFetch = messageRepo.getCodesWithoutContents(listOf(messageCode))
                if (needFetch.isNotEmpty()) {
                    messageRepo.fetchContents(listOf(messageCode))
                }
            }
            val detail = withContext(Dispatchers.IO) { messageRepo.getMessageDetail(messageCode) }
            val result = JSONObject().put("errorCode", 0)
            detail?.forEach { (k, v) -> result.put(k, v ?: JSONObject.NULL) }
            ctx.resolveToJs("getMessageDetail", result)
        } catch (e: Exception) {
            ctx.rejectToJs("getMessageDetail", e.message)
        }
    }

    private suspend fun handleGetMessageCounts() {
        try {
            val counts = withContext(Dispatchers.IO) { messageRepo.getCounts() }
            ctx.resolveToJs("getMessageCounts", JSONObject().put("errorCode", 0).put("counts", JSONObject(counts)))
        } catch (e: Exception) {
            ctx.rejectToJs("getMessageCounts", e.message)
        }
    }

    // ── retrieveMessage: 쪽지 회수 ──

    private suspend fun handleRetrieveMessage(params: Map<String, Any?>) {
        try {
            val messageCode = ctx.paramStr(params, "messageCode")
            val body = JSONObject().apply {
                put("messageCodeList", JSONArray().put(messageCode))
                put("retrieveUserId", ctx.appConfig.getSavedUserId() ?: "")
            }
            val result = withContext(Dispatchers.IO) {
                ApiClient.postJson(ctx.appConfig.getEndpointByPath("/comm/retrievemessage"), body)
            }
            // 성공 시 로컬 DB에 retrieved 상태 반영
            if (result.optInt("errorCode", -1) == 0) {
                withContext(Dispatchers.IO) {
                    val msgDb = ctx.dbProvider.getMessageDatabase()
                    val existing = msgDb.messageDao().getByMessageCode(messageCode)
                    if (existing != null) {
                        msgDb.messageDao().update(existing.copy(retrieved = true))
                    }
                }
            }
            withContext(Dispatchers.IO) { ctx.updateCrudOffset("retrievemessage", result) }
            ctx.resolveToJs("retrieveMessage", result)
        } catch (e: Exception) {
            ctx.rejectToJs("retrieveMessage", e.message)
        }
    }
}
