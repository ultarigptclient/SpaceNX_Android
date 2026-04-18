package net.spacenx.messenger.data.repository

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import net.spacenx.messenger.util.FileLogger
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.common.JsonStreamUtil
import net.spacenx.messenger.common.parseStream
import net.spacenx.messenger.common.AppConfig.Companion.EP_COMM_DELETE_MESSAGE
import net.spacenx.messenger.common.AppConfig.Companion.EP_COMM_READ_MESSAGE
import net.spacenx.messenger.common.AppConfig.Companion.EP_COMM_SEND_MESSAGE
import net.spacenx.messenger.common.AppConfig.Companion.EP_COMM_SYNC_MESSAGE
import net.spacenx.messenger.data.local.DatabaseProvider
import net.spacenx.messenger.data.local.entity.AttachEntity
import net.spacenx.messenger.data.local.entity.MessageEntity
import net.spacenx.messenger.data.local.entity.MessageEventEntity
import net.spacenx.messenger.data.local.entity.SyncMetaEntity
import net.spacenx.messenger.data.remote.api.ApiClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject

/**
 * 쪽지(Message/Note) 동기화 + REST API + 로컬 DB
 */
class MessageRepository(
    private val dbProvider: DatabaseProvider,
    private val appConfig: AppConfig,
    private val sessionManager: SocketSessionManager
) {
    companion object {
        private const val TAG = "MessageRepository"
        private const val SYNC_META_KEY = "messageEventOffset"
        // 무한 루프 방지: 한 번의 syncMessage 호출에서 최대 페이지 수 (hasMore stale 또는 서버 오류 시)
        private const val MAX_SYNC_PAGES = 200
    }

    private val syncMutex = Mutex()
    private val prefetchMutex = Mutex()

    // ── syncMessage: REST delta sync → DB 저장 ──

    suspend fun syncMessage(): Map<String, Any> {
        if (!syncMutex.tryLock()) { Log.d(TAG, "syncMessage already in progress, skipping"); return mapOf("errorCode" to 0, "skipped" to true) }
        try {
        return withContext(Dispatchers.IO) {
            try {
                val token = awaitToken()
                val messageDb = dbProvider.getMessageDatabase()
                val lastOffset = messageDb.syncMetaDao().getValue(SYNC_META_KEY) ?: 0L
                val userId = appConfig.getSavedUserId() ?: ""

                Log.d(TAG, "syncMessage: userId=$userId, lastOffset=$lastOffset")
                FileLogger.log(TAG, "syncMessage REQ userId=$userId offset=$lastOffset")

                val commApi = ApiClient.createCommApiFromBaseUrl(appConfig.getRestBaseUrl(), token)
                val endpoint = appConfig.getEndpoint(EP_COMM_SYNC_MESSAGE, "api/comm/syncmessage")

                var currentOffset = lastOffset
                var upsertCount = 0
                var readCount = 0
                var deleteCount = 0
                val newMessages = mutableListOf<JSONObject>()
                val pageSize = 500
                var pageCount = 0  // 무한 루프 방지용

                // 페이징 반복 sync (NHM-84: metaOnly + limit)
                do {
                    ensureActive()
                    val requestJson = JSONObject().apply {
                        put("userId", userId)
                        put("messageEventOffset", currentOffset)
                        put("reset", false)
                        put("metaOnly", true)
                        put("limit", pageSize)
                    }
                    val body = requestJson.toString()
                        .toRequestBody("application/json".toMediaType())

                    val response = commApi.post(endpoint, body)

                    if (!response.isSuccessful) {
                        Log.e(TAG, "syncMessage HTTP error: ${response.code()}")
                        return@withContext mapOf<String, Any>("errorCode" to -1, "errorMessage" to "HTTP ${response.code()}")
                    }

                    // ── 스트리밍 파싱 (OOM 방지) ──
                    // 메시지가 많은 계정의 첫 sync 응답은 수 MB~수십 MB. JSONObject(rawJson) 방식은 heap 초과 위험.
                    var errorCode = -1
                    var lastEventId = 0L
                    var hasMore = false
                    var eventsSeen = 0
                    val respBody = response.body() ?: return@withContext mapOf<String, Any>("errorCode" to -1, "errorMessage" to "empty body")
                    respBody.parseStream { reader ->
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "errorCode" -> errorCode = JsonStreamUtil.nextIntOrZero(reader)
                                "lastEventId" -> lastEventId = JsonStreamUtil.nextLongOrZero(reader)
                                "hasMore" -> hasMore = JsonStreamUtil.nextBooleanOrFalse(reader)
                                "events", "data" -> {
                                    // 이벤트 수집 후 일괄 처리 (N+1 방지)
                                    data class RawEvent(val eventId: Long, val type: String, val msgObj: JSONObject, val code: String)
                                    val rawEvents = mutableListOf<RawEvent>()
                                    reader.beginArray()
                                    while (reader.hasNext()) {
                                        val event = JsonStreamUtil.readObject(reader)
                                        eventsSeen++
                                        val eventType = event.optString("eventType", "").uppercase()
                                        val msgObj = event.optJSONObject("message") ?: event
                                        val messageCode = msgObj.optString("messageCode", event.optString("messageCode", ""))
                                        val eventId = event.optLong("eventId", 0L)
                                        rawEvents.add(RawEvent(eventId, eventType, msgObj, messageCode))
                                    }
                                    reader.endArray()

                                    // SEND/RECEIVE: 배치 조회 후 병합
                                    val sendReceive = rawEvents.filter { it.type == "SEND" || it.type == "RECEIVE" }
                                    if (sendReceive.isNotEmpty()) {
                                        val entities = sendReceive.map { jsonToMessageEntity(it.msgObj) }
                                        val existingMap = messageDb.messageDao()
                                            .getByMessageCodes(entities.map { it.messageCode })
                                            .associateBy { it.messageCode }
                                        val toInsert = entities.map { entity ->
                                            val ex = existingMap[entity.messageCode]
                                            if (ex != null) entity.copy(
                                                contents = entity.contents.ifEmpty { ex.contents },
                                                rtfContents = entity.rtfContents ?: ex.rtfContents,
                                                receivers = if (entity.receivers == "[]" && ex.receivers != "[]") ex.receivers else entity.receivers
                                            ) else entity
                                        }
                                        messageDb.messageDao().insertAll(toInsert)
                                        newMessages.addAll(sendReceive.map { it.msgObj })
                                        upsertCount += toInsert.size

                                        // attachs 저장
                                        val allAttachs = toInsert.flatMap { attachsFromJson(it.messageCode, it.attachInfo) }
                                        if (allAttachs.isNotEmpty()) messageDb.attachDao().insertAll(allAttachs)

                                        // messageEvents 기록
                                        val sendRecvEvents = sendReceive.mapNotNull { ev ->
                                            if (ev.eventId <= 0L) null
                                            else MessageEventEntity(ev.eventId, ev.type, ev.code, ev.msgObj.optString("sendUserId", ""), ev.msgObj.optLong("sendDate", 0L), if (ev.type == "RECEIVE") 1 else 0)
                                        }
                                        if (sendRecvEvents.isNotEmpty()) messageDb.messageEventDao().insertAll(sendRecvEvents)
                                    }

                                    // READ: 배치 상태 업데이트 + messageEvents 기록
                                    val readEvents = rawEvents.filter { it.type == "READ" }
                                    val readCodes = readEvents.map { it.code }
                                    if (readCodes.isNotEmpty()) {
                                        messageDb.messageDao().updateStateForCodes(readCodes, 1)
                                        readCount += readCodes.size
                                        val readMsgEvents = readEvents.mapNotNull { ev ->
                                            if (ev.eventId <= 0L) null
                                            else MessageEventEntity(ev.eventId, "READ", ev.code, "", 0L, 0)
                                        }
                                        if (readMsgEvents.isNotEmpty()) messageDb.messageEventDao().insertAll(readMsgEvents)
                                    }

                                    // DELETE: 배치 삭제 (messages + attachs + messageEvents)
                                    val deleteCodes = rawEvents.filter { it.type == "DEL" || it.type == "DELETE" }.map { it.code }
                                    if (deleteCodes.isNotEmpty()) {
                                        messageDb.messageDao().deleteByMessageCodes(deleteCodes)
                                        messageDb.attachDao().deleteByMessageCodes(deleteCodes)
                                        messageDb.messageEventDao().deleteByMessages(deleteCodes)
                                        deleteCount += deleteCodes.size
                                    }
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }

                    Log.d(TAG, "syncMessage page: events=$eventsSeen, lastEventId=$lastEventId, hasMore=$hasMore, errorCode=$errorCode")

                    if (errorCode != 0) {
                        return@withContext mapOf<String, Any>("errorCode" to errorCode)
                    }

                    // Update syncMeta offset
                    val prevOffset = currentOffset
                    if (lastEventId > currentOffset) {
                        messageDb.syncMetaDao().insert(SyncMetaEntity(SYNC_META_KEY, lastEventId))
                        currentOffset = lastEventId
                    }

                    // ── 무한 루프 방지 가드 ──
                    //   1) 서버가 hasMore=false 반환 → 정상 종료
                    //   2) 빈 페이지 (events 없음) → 더 받을 것 없음
                    //   3) offset이 전혀 진전 없음 (서버 hasMore stale) → 같은 페이지 반복 차단
                    //   4) MAX_SYNC_PAGES 초과 → 서버 응답 이상 시 네트워크/배터리 보호
                    pageCount++
                    val emptyPage = eventsSeen == 0
                    val offsetStalled = currentOffset == prevOffset
                    if (!hasMore || emptyPage || offsetStalled || pageCount >= MAX_SYNC_PAGES) {
                        if (hasMore && pageCount >= MAX_SYNC_PAGES) {
                            Log.w(TAG, "syncMessage: hit MAX_SYNC_PAGES=$MAX_SYNC_PAGES, stopping to avoid runaway")
                        }
                        if (hasMore && offsetStalled && !emptyPage) {
                            Log.w(TAG, "syncMessage: offset stalled at $currentOffset, stopping (server inconsistency)")
                        }
                        break
                    }
                } while (true)

                Log.d(TAG, "syncMessage complete: upsert=$upsertCount, read=$readCount, delete=$deleteCount, lastEventId=$currentOffset")
                FileLogger.log(TAG, "syncMessage DONE upsert=$upsertCount read=$readCount delete=$deleteCount lastOffset=$currentOffset")
                mapOf<String, Any>(
                    "errorCode" to 0,
                    "upsertCount" to upsertCount,
                    "readCount" to readCount,
                    "deleteCount" to deleteCount,
                    "newMessages" to newMessages
                )
            } catch (e: Exception) {
                Log.e(TAG, "syncMessage error: ${e.message}", e)
                FileLogger.log(TAG, "syncMessage ERROR ${e.message}")
                mapOf<String, Any>("errorCode" to -1, "errorMessage" to (e.message ?: "Unknown error"))
            }
        }
        } finally { syncMutex.unlock() }
    }

    // ── sendMessage: REST 전송 + 로컬 저장 ──

    suspend fun sendMessage(data: JSONObject): JSONObject {
        return withContext(Dispatchers.IO) {
            try {
                val token = awaitToken()
                val commApi = ApiClient.createCommApiFromBaseUrl(appConfig.getRestBaseUrl(), token)
                val endpoint = appConfig.getEndpoint(EP_COMM_SEND_MESSAGE, "api/comm/sendmessage")

                Log.d(TAG, "sendMessage: data=$data")

                val body = data.toString()
                    .toRequestBody("application/json".toMediaType())
                val response = commApi.post(endpoint, body)

                if (!response.isSuccessful) {
                    Log.e(TAG, "sendMessage HTTP error: ${response.code()}")
                    return@withContext JSONObject().apply {
                        put("errorCode", -1)
                        put("errorMessage", "HTTP ${response.code()}")
                    }
                }

                val rawJson = response.body()?.string() ?: "{}"
                val resJson = JSONObject(rawJson)
                Log.d(TAG, "sendMessage response: $rawJson")

                // 로컬 DB 저장은 SendMessageEvent push에서 처리 (중복 방지)

                // NHM-85: offset 갱신
                val eventId = resJson.optLong("eventId", 0L)
                if (eventId > 0L) {
                    dbProvider.getMessageDatabase().syncMetaDao()
                        .insert(SyncMetaEntity(SYNC_META_KEY, eventId))
                }

                resJson
            } catch (e: Exception) {
                Log.e(TAG, "sendMessage error: ${e.message}", e)
                JSONObject().apply {
                    put("errorCode", -1)
                    put("errorMessage", e.message ?: "Unknown error")
                }
            }
        }
    }

    // ── readMessage: REST 읽음 처리 + 로컬 상태 갱신 ──

    suspend fun readMessage(messageCode: String): JSONObject {
        return withContext(Dispatchers.IO) {
            try {
                val token = awaitToken()
                val userId = appConfig.getSavedUserId() ?: ""
                val commApi = ApiClient.createCommApiFromBaseUrl(appConfig.getRestBaseUrl(), token)
                val endpoint = appConfig.getEndpoint(EP_COMM_READ_MESSAGE, "api/comm/readmessage")

                val requestJson = JSONObject().apply {
                    put("userId", userId)
                    put("messageCode", messageCode)
                }
                val body = requestJson.toString()
                    .toRequestBody("application/json".toMediaType())

                Log.d(TAG, "readMessage: messageCode=$messageCode")
                val response = commApi.post(endpoint, body)

                if (!response.isSuccessful) {
                    Log.e(TAG, "readMessage HTTP error: ${response.code()}")
                    return@withContext JSONObject().apply {
                        put("errorCode", -1)
                        put("errorMessage", "HTTP ${response.code()}")
                    }
                }

                val rawJson = response.body()?.string() ?: "{}"
                val resJson = JSONObject(rawJson)
                Log.d(TAG, "readMessage response: $rawJson")

                if (resJson.optInt("errorCode", -1) == 0) {
                    val messageDb = dbProvider.getMessageDatabase()
                    messageDb.messageDao().updateState(messageCode, 1)
                    Log.d(TAG, "readMessage: marked as read in DB")

                    // NHM-85: offset 갱신
                    val eventId = resJson.optLong("eventId", 0L)
                    if (eventId > 0L) {
                        messageDb.syncMetaDao().insert(SyncMetaEntity(SYNC_META_KEY, eventId))
                    }
                }

                resJson
            } catch (e: Exception) {
                Log.e(TAG, "readMessage error: ${e.message}", e)
                JSONObject().apply {
                    put("errorCode", -1)
                    put("errorMessage", e.message ?: "Unknown error")
                }
            }
        }
    }

    // ── deleteMessage: REST 삭제 + 로컬 삭제 ──

    suspend fun deleteMessage(messageCode: String): JSONObject {
        return withContext(Dispatchers.IO) {
            try {
                val token = awaitToken()
                val userId = appConfig.getSavedUserId() ?: ""
                val commApi = ApiClient.createCommApiFromBaseUrl(appConfig.getRestBaseUrl(), token)
                val endpoint = appConfig.getEndpoint(EP_COMM_DELETE_MESSAGE, "api/comm/deletemessage")

                val requestJson = JSONObject().apply {
                    put("userId", userId)
                    put("messageCode", messageCode)
                }
                val body = requestJson.toString()
                    .toRequestBody("application/json".toMediaType())

                Log.d(TAG, "deleteMessage: messageCode=$messageCode")
                val response = commApi.post(endpoint, body)

                if (!response.isSuccessful) {
                    Log.e(TAG, "deleteMessage HTTP error: ${response.code()}")
                    return@withContext JSONObject().apply {
                        put("errorCode", -1)
                        put("errorMessage", "HTTP ${response.code()}")
                    }
                }

                val rawJson = response.body()?.string() ?: "{}"
                val resJson = JSONObject(rawJson)
                Log.d(TAG, "deleteMessage response: $rawJson")

                if (resJson.optInt("errorCode", -1) == 0) {
                    val messageDb = dbProvider.getMessageDatabase()
                    messageDb.messageDao().deleteByMessageCode(messageCode)
                    messageDb.attachDao().deleteByMessageCode(messageCode)
                    messageDb.messageEventDao().deleteByMessage(messageCode)
                    Log.d(TAG, "deleteMessage: deleted from DB")

                    // NHM-85: offset 갱신
                    val eventId = resJson.optLong("eventId", 0L)
                    if (eventId > 0L) {
                        messageDb.syncMetaDao().insert(SyncMetaEntity(SYNC_META_KEY, eventId))
                    }
                }

                resJson
            } catch (e: Exception) {
                Log.e(TAG, "deleteMessage error: ${e.message}", e)
                JSONObject().apply {
                    put("errorCode", -1)
                    put("errorMessage", e.message ?: "Unknown error")
                }
            }
        }
    }

    // ── getMessageList: 로컬 DB 조회 ──

    suspend fun getMessageList(sent: Boolean?, limit: Int, offset: Int): List<Map<String, Any?>> {
        return withContext(Dispatchers.IO) {
            try {
                val messageDb = dbProvider.getMessageDatabase()
                val userId = appConfig.getSavedUserId() ?: ""
                val allMessages = messageDb.messageDao().getAll()

                val filtered = if (sent == null) {
                    allMessages
                } else if (sent) {
                    allMessages.filter { it.sendUserId == userId }
                } else {
                    allMessages.filter { it.sendUserId != userId }
                }

                val paged = filtered.drop(offset).take(limit)

                paged.map { msg ->
                    mapOf<String, Any?>(
                        "messageCode" to msg.messageCode,
                        "sendUserId" to msg.sendUserId,
                        "title" to msg.title,
                        "contents" to msg.contents,
                        "rtfContents" to msg.rtfContents,
                        "sendDate" to msg.sendDate,
                        "scheduleDate" to msg.scheduleDate,
                        "state" to msg.state,
                        "receivers" to msg.receivers,
                        "messageType" to msg.messageType,
                        "important" to msg.important,
                        "individual" to msg.individual,
                        "silent" to msg.silent,
                        "retrieved" to msg.retrieved,
                        "attachInfo" to msg.attachInfo
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "getMessageList error: ${e.message}", e)
                emptyList()
            }
        }
    }

    // ── getMessagesByFolder: 폴더별 조회 ──

    suspend fun getMessagesByFolder(folder: String, limit: Int, offset: Int): List<Map<String, Any?>> {
        return withContext(Dispatchers.IO) {
            try {
                val dao = dbProvider.getMessageDatabase().messageDao()
                val userId = appConfig.getSavedUserId() ?: ""
                val messages = when (folder) {
                    "sent" -> dao.getBySender(userId, limit, offset)
                    "star" -> dao.getStarred(userId, limit, offset)
                    else -> dao.getReceived(userId, limit, offset) // inbox
                }
                messages.map { entityToMap(it) }
            } catch (e: Exception) {
                Log.e(TAG, "getMessagesByFolder error: ${e.message}", e)
                emptyList()
            }
        }
    }

    // ── getCounts: inbox/sent/star 카운트 ──

    suspend fun getCounts(): Map<String, Int> {
        return withContext(Dispatchers.IO) {
            try {
                val dao = dbProvider.getMessageDatabase().messageDao()
                val userId = appConfig.getSavedUserId() ?: ""
                mapOf(
                    "inbox" to dao.countReceived(userId),
                    "inboxUnread" to dao.countReceivedUnread(userId),
                    "sent" to dao.countSent(userId),
                    "star" to dao.countStarred()
                )
            } catch (e: Exception) {
                Log.e(TAG, "getCounts error: ${e.message}", e)
                mapOf("inbox" to 0, "inboxUnread" to 0, "sent" to 0, "star" to 0)
            }
        }
    }

    // ── getMessageDetail: 로컬 DB 상세 조회 ──

    suspend fun getMessageDetail(messageCode: String): Map<String, Any?>? {
        return withContext(Dispatchers.IO) {
            try {
                val db = dbProvider.getMessageDatabase()
                val entity = db.messageDao().getByMessageCode(messageCode) ?: return@withContext null
                val attachs = db.attachDao().getByMessageCode(messageCode)
                entityToMap(entity) + mapOf(
                    "attachs" to attachs.map { a ->
                        mapOf(
                            "fileId" to a.fileId,
                            "fileName" to a.fileName,
                            "originalFileName" to a.originalFileName,
                            "filePath" to a.filePath,
                            "fileLength" to a.fileLength,
                            "lastModifiedDate" to a.lastModifiedDate
                        )
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "getMessageDetail error: ${e.message}", e)
                null
            }
        }
    }

    // ── getCodesWithoutContents: contents가 비어있는 메시지 코드 조회 ──

    suspend fun getCodesWithoutContents(codes: List<String>): List<String> {
        return withContext(Dispatchers.IO) {
            try {
                dbProvider.getMessageDatabase().messageDao().getCodesWithoutContents(codes)
            } catch (e: Exception) {
                Log.e(TAG, "getCodesWithoutContents error: ${e.message}", e)
                emptyList()
            }
        }
    }

    // ── fetchContents: 서버에서 본문 가져와 로컬 DB 업데이트 ──

    suspend fun fetchContents(codes: List<String>) {
        withContext(Dispatchers.IO) {
            try {
                val token = awaitToken() ?: return@withContext
                val commApi = ApiClient.createCommApiFromBaseUrl(appConfig.getRestBaseUrl(), token)
                val endpoint = appConfig.getEndpointByPath("/comm/getmessagecontents")
                val requestJson = JSONObject().apply {
                    put("messageCodes", org.json.JSONArray(codes))
                }
                val body = requestJson.toString()
                    .toRequestBody("application/json".toMediaType())
                val response = commApi.post(endpoint, body)
                if (!response.isSuccessful) return@withContext
                val rawJson = response.body()?.string() ?: return@withContext
                val resJson = JSONObject(rawJson)
                if (resJson.optInt("errorCode", -1) != 0) return@withContext

                val dao = dbProvider.getMessageDatabase().messageDao()
                val messages = resJson.optJSONArray("messages") ?: return@withContext
                for (i in 0 until messages.length()) {
                    val m = messages.getJSONObject(i)
                    val code = m.optString("messageCode", "")
                    if (code.isEmpty()) continue
                    dao.updateContentsAndReceivers(
                        code,
                        m.optString("contents", ""),
                        if (m.has("rtfContents")) m.optString("rtfContents") else null,
                        m.optString("receivers", "[]")
                    )
                }
                Log.d(TAG, "fetchContents: updated ${messages.length()} messages")
            } catch (e: Exception) {
                Log.e(TAG, "fetchContents error: ${e.message}", e)
            }
        }
    }

    /**
     * 목록 조회 후 contents가 비어있는 메시지를 백그라운드에서 프리페치.
     * 중복 호출 방지 (prefetchMutex), 실패해도 무시 (클릭 시 재시도됨).
     */
    fun prefetchContents(scope: CoroutineScope, messageCodes: List<String>) {
        if (messageCodes.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            if (!prefetchMutex.tryLock()) return@launch
            try {
                val needFetch = getCodesWithoutContents(messageCodes)
                if (needFetch.isEmpty()) return@launch
                Log.d(TAG, "prefetchContents: ${needFetch.size} messages need contents")
                fetchContents(needFetch)
            } catch (e: Exception) {
                Log.e(TAG, "prefetchContents error: ${e.message}")
            } finally {
                prefetchMutex.unlock()
            }
        }
    }

    private fun entityToMap(msg: MessageEntity): Map<String, Any?> = mapOf(
        "messageCode" to msg.messageCode,
        "sendUserId" to msg.sendUserId,
        "title" to msg.title,
        "contents" to msg.contents,
        "rtfContents" to msg.rtfContents,
        "sendDate" to msg.sendDate,
        "scheduleDate" to msg.scheduleDate,
        "state" to msg.state,
        "receivers" to msg.receivers,
        "messageType" to msg.messageType,
        "important" to msg.important,
        "individual" to msg.individual,
        "silent" to msg.silent,
        "retrieved" to msg.retrieved,
        "attachInfo" to msg.attachInfo
    )

    // ── handlePushEvent: 소켓 push → 로컬 DB ──

    suspend fun handlePushEvent(data: JSONObject) {
        withContext(Dispatchers.IO) {
            try {
                val messageDb = dbProvider.getMessageDatabase()
                val entity = jsonToMessageEntity(data)
                messageDb.messageDao().insert(entity)

                // attachs 저장
                val attachs = attachsFromJson(entity.messageCode, entity.attachInfo)
                if (attachs.isNotEmpty()) messageDb.attachDao().insertAll(attachs)

                // messageEvent 기록
                val eventId = data.optLong("eventId", 0L)
                if (eventId > 0L) {
                    val command = data.optString("eventType", data.optString("command", "RECEIVE")).uppercase()
                    messageDb.messageEventDao().insert(
                        MessageEventEntity(
                            eventId = eventId,
                            command = command,
                            messageCode = entity.messageCode,
                            sendUserId = entity.sendUserId,
                            sendDate = entity.sendDate,
                            receive = 1
                        )
                    )
                }

                Log.d(TAG, "handlePushEvent: upserted messageCode=${entity.messageCode}")
            } catch (e: Exception) {
                Log.e(TAG, "handlePushEvent error: ${e.message}", e)
            }
        }
    }

    // ── JSON → Entity 변환 ──

    private fun jsonToMessageEntity(json: JSONObject): MessageEntity {
        return MessageEntity(
            messageCode = json.optString("messageCode", ""),
            sendUserId = json.optString("sendUserId", ""),
            title = json.optString("title", ""),
            contents = json.optString("contents", ""),
            rtfContents = if (json.has("rtfContents")) json.optString("rtfContents") else null,
            sendDate = json.optLong("sendDate", 0L).let { if (it == 0L) System.currentTimeMillis() else it },
            scheduleDate = json.optLong("scheduleDate", 0L),
            state = json.optInt("state", 0),
            receivers = (json.optJSONArray("receivers")?.toString()
                ?: json.optString("receivers", "[]").let { s -> if (s.startsWith("[")) s else "[]" }),
            messageType = json.optInt("messageType", 0),
            important = json.optBoolean("important", false),
            individual = json.optBoolean("individual", false),
            silent = json.optBoolean("silent", false),
            retrieved = json.optBoolean("retrieved", false),
            attachInfo = json.optJSONArray("attachInfo")?.toString()
                ?: if (json.has("attachInfo") && !json.isNull("attachInfo")) json.optString("attachInfo") else null
        )
    }

    // 서버 attachInfo 필드: fileId, name, size, url (레거시: fileName, filePath, fileLength, originalFileName)
    private fun attachsFromJson(messageCode: String, attachInfoJson: String?): List<AttachEntity> {
        if (attachInfoJson.isNullOrEmpty()) return emptyList()
        return try {
            val arr = org.json.JSONArray(attachInfoJson)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val name = obj.optString("name", obj.optString("fileName", ""))
                AttachEntity(
                    messageCode = messageCode,
                    fileId = obj.optString("fileId", ""),
                    fileName = name,
                    filePath = obj.optString("url", obj.optString("filePath", "")),
                    fileLength = obj.optLong("size", obj.optLong("fileLength", 0L)),
                    lastModifiedDate = obj.optLong("lastModifiedDate", 0L),
                    originalFileName = obj.optString("originalFileName", name)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "attachsFromJson parse error: ${e.message}")
            emptyList()
        }
    }

    private suspend fun awaitToken(): String? {
        if (sessionManager.jwtToken != null) return sessionManager.jwtToken
        Log.d(TAG, "Waiting for JWT token...")
        return withTimeoutOrNull(10_000L) { sessionManager.jwtTokenDeferred.await() }
    }
}
