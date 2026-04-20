package net.spacenx.messenger.ui.bridge.handler

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.spacenx.messenger.data.remote.api.ApiClient
import net.spacenx.messenger.data.repository.MessageRepository
import net.spacenx.messenger.data.repository.ProjectRepository
import net.spacenx.messenger.ui.bridge.BridgeContext
import net.spacenx.messenger.ui.bridge.BridgeDispatcher
import org.json.JSONArray
import org.json.JSONObject

class NeoSendHandler(
    private val ctx: BridgeContext,
    private val messageRepo: MessageRepository,
    private val projectRepo: ProjectRepository
) {
    companion object {
        private const val TAG = "NeoSendHandler"
    }

    suspend fun handle(action: String, params: Map<String, Any?>) {
        when (action) {
            "apiPost" -> handleApiPost(params)
            "neoSend" -> handleNeoSend(params)
            "httpRequest" -> handleHttpRequest(params)
        }
    }

    // ── apiPost: 범용 REST POST 프록시 (이슈/프로젝트/스레드/바로가기 등) ──

    private suspend fun handleApiPost(params: Map<String, Any?>) {
        // _callbackId: JS bridge.js가 apiPost 동시 호출 충돌 방지용으로 고유 콜백 ID 사용
        // e.g. "apiPost_1", "apiPost_2" → window._apiPost_1Resolve / _apiPost_2Resolve
        val cbId = ctx.paramStr(params, "_callbackId").ifEmpty { "apiPost" }
        try {
            val path = ctx.paramStr(params, "path")
            val body = params["body"] as? JSONObject
                ?: (params["body"] as? Map<*, *>)?.let { ctx.paramsToJson(it as Map<String, Any?>) }
                ?: JSONObject()
            if (path.isEmpty()) {
                ctx.rejectToJs(cbId, "path is required")
                return
            }

            // 조회 API → SQLite 우선
            val localResult = withContext(Dispatchers.IO) { projectRepo.handleLocally(path, body) }
            if (localResult != null) {
                Log.d(TAG, "apiPost LOCAL: $path")
                ctx.resolveToJs(cbId, localResult)
                return
            }

            // CUD + fallback → REST
            val token = ctx.loginViewModel.sessionManager.jwtToken
            val result = withContext(Dispatchers.IO) {
                ApiClient.postJson(ctx.appConfig.getEndpointByPath(path), body, token)
            }

            // CUD 성공 후 SQLite에 즉시 반영 — resolveToJs 이전에 저장해야
            // React가 응답 직후 조회할 때 새 데이터가 DB에 있음
            withContext(Dispatchers.IO) { projectRepo.cacheAfterCud(path, body, result) }
            ctx.resolveToJs(cbId, result)

            // CUD 성공 후 백그라운드 sync 트리거
            val syncType = projectRepo.getSyncTypeForPath(path)
            if (syncType != null) {
                val capturedPath = path
                val capturedBody = body
                ctx.scope.launch {
                    try {
                        when (syncType) {
                            "project" -> { projectRepo.syncProject(); ctx.notifyReact("projectReady") }
                            "issue" -> { projectRepo.syncIssue(); ctx.notifyReact("issueReady") }
                            "calendar" -> { projectRepo.syncCalendar(); ctx.notifyReactOnce("calReady") }
                            "todo" -> { projectRepo.syncTodo(); ctx.notifyReactOnce("todoReady") }
                            "milestone" -> { ctx.notifyReactOnce("milestoneReady") }
                            "shortcut" -> { ctx.notifyReactOnce("shortcutReady") }
                            "thread" -> {
                                projectRepo.syncThread()
                                // threadReady 이벤트에 chatCode/channelCode/commentCount 포함
                                // → React가 채팅 말풍선 배지를 즉시 갱신할 수 있도록
                                val threadCode = capturedBody.optString("threadCode", "")
                                val threadData = if (threadCode.isNotEmpty()) {
                                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        projectRepo.getChatThreadByCode(threadCode)
                                    }
                                } else null

                                if (threadData != null) {
                                    val json = JSONObject()
                                        .put("event", "threadReady")
                                        .put("channelCode", threadData["channelCode"])
                                        .put("chatCode", threadData["chatCode"])
                                        .put("commentCount", threadData["commentCount"])
                                        .toString()
                                    ctx.completedSyncs.add("threadReady")
                                    ctx.evalJs("window.postMessage('${ctx.esc(json)}')")
                                } else {
                                    ctx.notifyReact("threadReady")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "background sync$syncType error: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "apiPost error: ${e.message}", e)
            ctx.rejectToJs(cbId, e.message)
        }
    }

    private suspend fun handleNeoSend(params: Map<String, Any?>) {
        val requestId = ctx.paramStr(params, "requestId")
        val commandName = ctx.paramStr(params, "commandName")
        val body = params["body"] as? JSONObject ?: ctx.paramsToJson(params["body"])

        if (commandName == "GetMessageList") {
            try {
                val sent = when (body.optString("type", "")) {
                    "sent" -> true
                    "received" -> false
                    else -> null
                }
                val limit = body.optInt("limit", 50)
                val offset = body.optInt("offset", 0)
                val messages = withContext(Dispatchers.IO) { messageRepo.getMessageList(sent, limit, offset) }
                val eventsArray = JSONArray()
                for (msg in messages) {
                    val obj = JSONObject()
                    obj.put("messageCode", msg["messageCode"] ?: "")
                    obj.put("sendUserId", msg["sendUserId"] ?: "")
                    obj.put("title", msg["title"] ?: "")
                    obj.put("contents", msg["contents"] ?: "")
                    obj.put("rtfContents", msg["rtfContents"] ?: JSONObject.NULL)
                    obj.put("sendDate", msg["sendDate"] ?: 0L)
                    obj.put("scheduledDate", msg["scheduleDate"] ?: 0L)
                    obj.put("state", msg["state"] ?: 0)
                    obj.put("read", (msg["state"] as? Int ?: 0) > 0)
                    obj.put("messageType", msg["messageType"] ?: 0)
                    val receiversStr = msg["receivers"] as? String ?: "[]"
                    try { obj.put("receivers", JSONArray(receiversStr)) } catch (_: Exception) { obj.put("receivers", JSONArray()) }
                    val attachInfoStr = msg["attachInfo"] as? String
                    if (attachInfoStr != null) {
                        try { obj.put("attachInfo", JSONArray(attachInfoStr)) } catch (_: Exception) { obj.put("attachInfo", JSONObject.NULL) }
                    } else obj.put("attachInfo", JSONObject.NULL)
                    obj.put("individual", msg["individual"] ?: false)
                    obj.put("important", msg["important"] ?: false)
                    obj.put("silent", msg["silent"] ?: false)
                    obj.put("retrieved", msg["retrieved"] ?: false)
                    obj.put("additional", JSONObject.NULL)
                    val sendUserId = msg["sendUserId"] as? String ?: ""
                    if (sendUserId.isNotEmpty()) obj.put("sendUserName", ctx.userNameCache.resolve(sendUserId))
                    eventsArray.put(obj)
                }
                val codes = messages.mapNotNull { it["messageCode"] as? String }
                messageRepo.prefetchContents(ctx.scope, codes)

                val result = JSONObject().put("errorCode", 0).put("events", eventsArray)
                Log.d(TAG, "GetMessageList local: count=${eventsArray.length()}")
                ctx.evalJs("window._neoSendCallback && window._neoSendCallback('$requestId', '${ctx.esc(result.toString())}')")
            } catch (e: Exception) {
                val err = JSONObject().put("errorCode", -1).put("errorMessage", e.message ?: "").toString()
                ctx.evalJs("window._neoSendError && window._neoSendError('$requestId', '${ctx.esc(err)}')")
            }
            return
        }

        try {
            var result: JSONObject
            val sm = ctx.loginViewModel.sessionManager

            if (sm.isConnected()) {
                try {
                    result = withContext(Dispatchers.IO) { sm.sendCommand(commandName, body) }
                    Log.d(TAG, "neoSend WS: $commandName OK")
                } catch (wsErr: Exception) {
                    Log.w(TAG, "neoSend WS failed ($commandName), REST fallback: ${wsErr.message}")
                    val restPath = BridgeDispatcher.NEOSEND_REST_MAP[commandName] ?: "/comm/${commandName.lowercase()}"
                    result = withContext(Dispatchers.IO) {
                        ApiClient.postJson(ctx.appConfig.getEndpointByPath(restPath), body)
                    }
                }
            } else {
                val restPath = BridgeDispatcher.NEOSEND_REST_MAP[commandName] ?: "/comm/${commandName.lowercase()}"
                result = withContext(Dispatchers.IO) {
                    ApiClient.postJson(ctx.appConfig.getEndpointByPath(restPath), body)
                }
            }

            withContext(Dispatchers.IO) { ctx.updateCrudOffset(commandName, result) }

            val json = result.toString()
            ctx.evalJs("window._neoSendCallback && window._neoSendCallback('$requestId', '${ctx.esc(json)}')")
        } catch (e: Exception) {
            val err = JSONObject().put("errorCode", -1).put("errorMessage", e.message ?: "").toString()
            ctx.evalJs("window._neoSendError && window._neoSendError('$requestId', '${ctx.esc(err)}')")
        }
    }

    private suspend fun handleHttpRequest(params: Map<String, Any?>) {
        val requestTag = ctx.paramStr(params, "requestTag")
        val method = ctx.paramStr(params, "method").ifEmpty { "GET" }
        val url = ctx.paramStr(params, "url")

        try {
            val result = withContext(Dispatchers.IO) {
                val body = params["body"] as? JSONObject
                ApiClient.httpRequest(method, url, body)
            }
            val resp = JSONObject().put("event", "httpResponse").put("requestTag", requestTag)
                .put("statusCode", 200).put("data", result).toString()
            ctx.evalJs("window.postMessage('${ctx.esc(resp)}')")
        } catch (e: Exception) {
            val resp = JSONObject().put("event", "httpResponse").put("requestTag", requestTag)
                .put("statusCode", 500).put("error", e.message ?: "").toString()
            ctx.evalJs("window.postMessage('${ctx.esc(resp)}')")
        }
    }
}
