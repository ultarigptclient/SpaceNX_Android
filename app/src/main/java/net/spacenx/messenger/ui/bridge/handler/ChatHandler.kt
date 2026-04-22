package net.spacenx.messenger.ui.bridge.handler

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.spacenx.messenger.data.local.entity.ChatEntity
import net.spacenx.messenger.data.local.entity.ChannelOffsetEntity
import net.spacenx.messenger.data.remote.api.ApiClient
import net.spacenx.messenger.data.repository.ChannelRepository
import net.spacenx.messenger.ui.bridge.BridgeContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/**
 * 채팅 메시지 송수신·조회·반응·투표·고정 브릿지 핸들러.
 * 채널/방 생성·멤버 관리는 ChannelActionHandler에서 처리.
 */
class ChatHandler(
    private val ctx: BridgeContext,
    private val channelRepo: ChannelRepository
) {
    companion object {
        private const val TAG = "ChatHandler"
    }

    // readChat 중복 서버 호출 방지 (in-flight dedup)
    private val pendingReads: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    suspend fun handle(action: String, params: Map<String, Any?>) {
        when (action) {
            "getChatList" -> handleGetChatList(params)
            "sendChat" -> handleSendChat(params)
            "readChat" -> handleReadChat(params)
            "deleteChat" -> handleDeleteChat(params)
            "toggleReaction" -> handleToggleReaction(params)
            "modChat" -> ctx.handleRestForward("modChat", "/comm/modchat", params)
            "addLocalSystemChat" -> handleAddLocalSystemChat(params)
            "toggleVote" -> handleToggleVote(params)
            "closeVote" -> handleCloseVote(params)
            "pinMessage" -> handlePinMessage(params)
            "unpinMessage" -> handleUnpinMessage(params)
            "getUnreadCount" -> handleGetUnreadCount(params)
        }
    }

    private suspend fun handleGetUnreadCount(params: Map<String, Any?>) {
        if (ctx.guardDbNotReady("getUnreadCount")) return
        val channelCode = ctx.paramStr(params, "channelCode")
        val myUserId = ctx.appConfig.getSavedUserId().orEmpty()
        if (channelCode.isEmpty() || myUserId.isEmpty()) {
            ctx.rejectToJs("getUnreadCount", "missing channelCode or userId")
            return
        }
        try {
            val count = withContext(Dispatchers.IO) {
                val chatDb = ctx.dbProvider.getChatDatabase()
                val offsetDate = chatDb.channelOffsetDao().get(channelCode, myUserId)?.offsetDate ?: 0L
                chatDb.chatDao().countUnread(channelCode, offsetDate, myUserId)
            }
            ctx.resolveToJs("getUnreadCount", JSONObject()
                .put("errorCode", 0)
                .put("channelCode", channelCode)
                .put("unreadCount", count))
        } catch (e: Exception) {
            Log.e(TAG, "getUnreadCount error: ${e.message}", e)
            ctx.rejectToJs("getUnreadCount", e.message)
        }
    }

    private suspend fun handleGetChatList(params: Map<String, Any?>) {
        if (ctx.guardDbNotReady("getChatList")) return
        try {
            val channelCode = ctx.paramStr(params, "channelCode")
            ctx.activeChannelCode = channelCode.ifEmpty { null }
            val limit = ctx.paramInt(params, "limit", 50)
            val beforeDate = ctx.paramLong(params, "beforeDate")

            val chatDb = ctx.dbProvider.getChatDatabase()
            // React 가 방 생성 화면에서 '__pending_group__' 같은 placeholder 로 getChatList 를 부르는 경우가 있어,
            // 실제 채널 코드가 아니면 서버 syncChat fallback 을 돌지 않도록 차단 (불필요한 네트워크 + 로그 노이즈 방지).
            val isRealChannelCode = channelCode.isNotEmpty() && !channelCode.startsWith("__")
            var chats = withContext(Dispatchers.IO) {
                if (beforeDate != null && beforeDate > 0) {
                    chatDb.chatDao().getBeforeDate(channelCode, beforeDate, limit) ?: emptyList()
                } else {
                    chatDb.chatDao().getRecent(channelCode, limit) ?: emptyList()
                }
            }
            if (chats.isEmpty() && beforeDate == null && isRealChannelCode) {
                withContext(Dispatchers.IO) {
                    try { channelRepo.syncChat(ctx.appConfig.getSavedUserId() ?: "") } catch (_: Exception) {}
                }
                chats = withContext(Dispatchers.IO) {
                    chatDb.chatDao().getRecent(channelCode, limit) ?: emptyList()
                }
            }

            val myUserId = ctx.appConfig.getSavedUserId() ?: ""
            if (myUserId.isNotEmpty() && channelCode.isNotEmpty()) {
                val myMember = withContext(Dispatchers.IO) {
                    chatDb.channelMemberDao().getMember(channelCode, myUserId)
                }
                Log.d(TAG, "getChatList: channelCode=$channelCode, chatsBeforeFilter=${chats.size}, myRegistDate=${myMember?.registDate}, chatDates=${chats.take(5).map { it.sendDate }}")
                if (myMember != null && myMember.registDate > 0) {
                    val before = chats.size
                    chats = chats.filter { it.sendDate >= myMember.registDate }
                    if (chats.size != before) {
                        Log.d(TAG, "getChatList: pre-join filter removed ${before - chats.size} chats (registDate=${myMember.registDate})")
                    }
                }
            }

            val activeMembers = withContext(Dispatchers.IO) {
                chatDb.channelMemberDao().getActiveMembersByChannel(channelCode)
            }
            val allOffsets = withContext(Dispatchers.IO) {
                chatDb.channelOffsetDao().getByChannel(channelCode)
            }
            val offsetMap = allOffsets.associate { it.userId to it.offsetDate }
            Log.d(TAG, "getChatList unread-debug: channelCode=$channelCode, activeMembers=${activeMembers.size} ${activeMembers.map { "${it.userId}(reg=${it.registDate})" }}, offsets=${offsetMap.map { "${it.key}=${it.value}" }}")

            // 채널의 chatThread 매핑 → chat 객체에 commentCount/threadCode 주입
            val threadMap = withContext(Dispatchers.IO) {
                try {
                    ctx.dbProvider.getProjectDatabase().chatThreadDao()
                        .getByChannel(channelCode)
                        .associateBy { it.chatCode }
                } catch (_: Exception) { emptyMap() }
            }

            val chatList = JSONArray()
            for (c in chats) {
                // 메시지 sendDate 시점에 채널 멤버였던 사람만 미확인 대상. registDate<=0 은 레거시 레코드 호환.
                val eligible = activeMembers.filter { it.registDate <= c.sendDate }
                val readByEligible = eligible.filter { (offsetMap[it.userId] ?: 0L) >= c.sendDate }
                val unreadCount = eligible.size - readByEligible.size
                Log.d(TAG, "getChatList unread-debug: chat=${c.chatCode} sendDate=${c.sendDate} sender=${c.sendUserId} eligible=${eligible.map { it.userId }} read=${readByEligible.map { "${it.userId}@${offsetMap[it.userId]}" }} unread=$unreadCount")
                val rawAdditional = c.additional ?: ""
                val sanitized = sanitizeAdditional(rawAdditional)
                if (rawAdditional != sanitized) {
                    Log.d(TAG, "sanitizeAdditional [${c.chatCode}] before=$rawAdditional after=$sanitized")
                } else if (rawAdditional.contains("file", ignoreCase = true)) {
                    Log.d(TAG, "sanitizeAdditional [${c.chatCode}] unchanged=$rawAdditional")
                }
                val thread = threadMap[c.chatCode]
                chatList.put(JSONObject().apply {
                    put("chatCode", c.chatCode)
                    put("channelCode", c.channelCode)
                    put("sendUserId", c.sendUserId)
                    put("sendUserName", ctx.userNameCache.resolve(c.sendUserId ?: ""))
                    val isDeleted = c.state == 1 || c.state == -1
                    put("contents", if (isDeleted) "삭제된 메시지" else c.contents)
                    put("sendDate", c.sendDate)
                    put("additional", if (!isDeleted && sanitized.isNotEmpty()) try { JSONObject(sanitized) } catch (_: Exception) { JSONObject() } else JSONObject())
                    put("chatType", c.chatType)
                    put("chatFont", c.chatFont ?: "")
                    put("state", c.state)
                    put("deleted", isDeleted)
                    put("unreadCount", unreadCount)
                    put("commentCount", thread?.commentCount ?: 0)
                    if (thread != null) put("threadCode", thread.threadCode)
                })
            }
            ctx.resolveToJs("getChatList", JSONObject()
                .put("errorCode", 0)
                .put("chats", chatList)
                .put("hasMore", chats.size >= limit))
        } catch (e: Exception) {
            ctx.rejectToJs("getChatList", e.message)
        }
    }

    private suspend fun handleSendChat(params: Map<String, Any?>) {
        try {
            var channelCode = ctx.paramStr(params, "channelCode")
            val members = ctx.paramList(params, "members")
            val myId = ctx.appConfig.getSavedUserId() ?: ""
            val chatType = ctx.paramStr(params, "chatType")
            val contents = ctx.paramStr(params, "contents")
            val additional = ctx.paramStr(params, "additional")
            Log.d(TAG, "sendChat: channelCode=$channelCode, chatType=$chatType, contents=${contents.take(50)}, additional=${additional.take(100)}, members=$members")

            if (channelCode.isEmpty() && members.isNotEmpty()) {
                val makeResult = withContext(Dispatchers.IO) {
                    val usersArr = JSONArray().apply { members.forEach { put(it) } }
                    val body = JSONObject().put("users", usersArr).put("sendUserId", myId)
                    ApiClient.postJson(ctx.appConfig.getEndpointByPath("/comm/makechannel"), body)
                }
                channelCode = makeResult.optString("channelCode", "")
                if (channelCode.isEmpty()) {
                    ctx.rejectToJs("sendChat", makeResult.toString())
                    return
                }
                withContext(Dispatchers.IO) { ctx.saveChannelLocally(channelCode, members) }
            }

            val body = ctx.paramsToJson(params).apply {
                put("channelCode", channelCode)
                if (!has("sendUserId") || optString("sendUserId").isEmpty()) put("sendUserId", myId)
                remove("members")
                val addStr = optString("additional", "")
                if (addStr.isNotEmpty() && addStr.startsWith("{")) {
                    try { put("additional", JSONObject(addStr)) } catch (_: Exception) {}
                }
            }
            Log.d(TAG, "sendChat: REST body channelCode=${body.optString("channelCode")}, chatType=${body.opt("chatType")}, additional=${body.optString("additional", "").take(100)}")
            val result = withContext(Dispatchers.IO) {
                ApiClient.postJson(ctx.appConfig.getEndpointByPath("/comm/sendchat"), body)
            }
            Log.d(TAG, "sendChat: REST result errorCode=${result.optInt("errorCode", -999)}, chatCode=${result.optString("chatCode")}, eventId=${result.optLong("eventId")}")

            withContext(Dispatchers.IO) {
                val chatCode = result.optString("chatCode", body.optString("tempChatCode", ""))
                val sendDate = result.optLong("dateTime", System.currentTimeMillis())
                if (channelCode.isNotEmpty() && chatCode.isNotEmpty()) {
                    ctx.dbProvider.getChatDatabase().chatDao()?.insert(ChatEntity(
                        channelCode = channelCode, chatCode = chatCode,
                        sendUserId = myId, contents = body.optString("contents", ""),
                        sendDate = sendDate, additional = body.optString("additional", ""),
                        chatType = parseChatType(body.opt("chatType")), chatFont = body.optString("chatFont", "")
                    ))
                    val chatDb = ctx.dbProvider.getChatDatabase()
                    val existingChannel = chatDb.channelDao().getByChannelCode(channelCode)
                    if (existingChannel != null) {
                        chatDb.channelDao().insert(existingChannel.copy(
                            lastChatDate = sendDate,
                            lastChatContents = body.optString("contents", ""),
                            lastSendUserId = myId
                        ))
                    }
                    // sendChat 성공: 본인 offset 저장 (서버 dateTime 기준)
                    val dateTime = result.optLong("dateTime", 0L)
                    if (myId.isNotEmpty() && dateTime > 0) {
                        chatDb.channelOffsetDao().upsert(
                            ChannelOffsetEntity(channelCode = channelCode, userId = myId, offsetDate = dateTime)
                        )
                    }
                }
                ctx.updateCrudOffset("sendchat", result)
            }
            ctx.resolveToJs("sendChat", result)
            // 채널 내/밖 모두 chatReady 알림 — React가 getChatList를 다시 호출해 unreadCount 갱신
            ctx.notifyReactOnce("chatReady")
        } catch (e: Exception) {
            ctx.rejectToJs("sendChat", e.message)
        }
    }

    private suspend fun handleReadChat(params: Map<String, Any?>) {
        val channelCode = ctx.paramStr(params, "channelCode")
        val userId = ctx.appConfig.getSavedUserId() ?: ""
        val offsetDate = System.currentTimeMillis()

        // 로컬 offset 먼저 저장 후 JS 응답 — 재로드 시 읽음 상태 일관성 보장
        if (channelCode.isNotEmpty() && userId.isNotEmpty()) {
            try {
                withContext(Dispatchers.IO) {
                    ctx.dbProvider.getChatDatabase().channelOffsetDao()?.upsert(
                        ChannelOffsetEntity(channelCode = channelCode, userId = userId, offsetDate = offsetDate)
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "readChat offset save error: ${e.message}")
            }
            // 서버 알림은 best-effort (백그라운드) — 동일 채널 중복 호출 방지
            if (pendingReads.add(channelCode)) {
                ctx.scope.launch(Dispatchers.IO) {
                    try {
                        val body = JSONObject().put("channelCode", channelCode).put("readUserId", userId)
                        ApiClient.postJson(ctx.appConfig.getEndpointByPath("/comm/readchat"), body)
                    } catch (_: Exception) {
                    } finally {
                        pendingReads.remove(channelCode)
                    }
                }
            }
            // 해당 채널 알림 취소 → 런처 뱃지 감소
            ctx.activity.notificationGroupManager.cancel(channelCode)
        }
        ctx.resolveToJs("readChat", JSONObject().put("errorCode", 0))
    }

    private suspend fun handleDeleteChat(params: Map<String, Any?>) {
        try {
            val result = withContext(Dispatchers.IO) {
                ApiClient.postJson(ctx.appConfig.getEndpointByPath("/comm/deletechat"), ctx.paramsToJson(params))
            }
            val chatCode = ctx.paramStr(params, "chatCode")
            if (chatCode.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    val chatDb = ctx.dbProvider.getChatDatabase()
                    val existing = chatDb.chatDao().getByChatCode(chatCode)
                    if (existing != null) {
                        chatDb.chatDao().insert(existing.copy(state = 1))
                        val channelCode = existing.channelCode
                        val fallback = chatDb.chatDao().getLastVisibleChatSync(channelCode)
                        chatDb.channelDao().updateLastChatSync(
                            channelCode = channelCode,
                            date = fallback?.sendDate ?: 0L,
                            contents = fallback?.contents ?: "",
                            lastSendUserId = fallback?.sendUserId ?: ""
                        )
                    }
                }
            }
            withContext(Dispatchers.IO) { ctx.updateCrudOffset("deletechat", result) }
            ctx.resolveToJs("deleteChat", result)
            ctx.notifyReactOnce("channelReady")
        } catch (e: Exception) {
            ctx.rejectToJs("deleteChat", e.message)
        }
    }

    private suspend fun handleToggleReaction(params: Map<String, Any?>) {
        try {
            val body = ctx.paramsToJson(params).apply {
                if (!has("userId") || optString("userId").isEmpty()) {
                    put("userId", ctx.appConfig.getSavedUserId() ?: "")
                }
            }
            val result = withContext(Dispatchers.IO) {
                ApiClient.postJson(ctx.appConfig.getEndpointByPath("/comm/togglereaction"), body)
            }

            val channelCode = body.optString("channelCode", "")
            val chatCode = body.optString("chatCode", "")
            val reactions = result.opt("reactions")
            if (channelCode.isNotEmpty() && chatCode.isNotEmpty() && reactions != null) {
                withContext(Dispatchers.IO) {
                    val chatDao = ctx.dbProvider.getChatDatabase().chatDao()
                    val existing = chatDao.getByChatCode(chatCode)
                    if (existing != null) {
                        val additional = try {
                            if (!existing.additional.isNullOrEmpty()) JSONObject(existing.additional) else JSONObject()
                        } catch (_: Exception) { JSONObject() }
                        additional.put("reactions", reactions)
                        chatDao.insert(existing.copy(additional = additional.toString()))
                    }
                }
            }

            withContext(Dispatchers.IO) { ctx.updateCrudOffset("reactionchat", result) }
            ctx.resolveToJs("toggleReaction", result)
        } catch (e: Exception) {
            ctx.rejectToJs("toggleReaction", e.message)
        }
    }

    private suspend fun handleAddLocalSystemChat(params: Map<String, Any?>) {
        try {
            val channelCode = ctx.paramStr(params, "channelCode")
            val contents = ctx.paramStr(params, "contents")
            if (channelCode.isEmpty() || contents.isEmpty()) {
                ctx.resolveToJs("addLocalSystemChat", JSONObject().put("errorCode", -1))
                return
            }
            val chatCode = "sys_${System.currentTimeMillis()}"
            val sendDate = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                ctx.dbProvider.getChatDatabase().chatDao().insert(ChatEntity(
                    channelCode = channelCode,
                    chatCode = chatCode,
                    sendUserId = "",
                    contents = contents,
                    sendDate = sendDate,
                    chatType = 32
                ))
            }
            ctx.resolveToJs("addLocalSystemChat", JSONObject().apply {
                put("errorCode", 0)
                put("chatCode", chatCode)
                put("sendDate", sendDate)
            })
        } catch (e: Exception) {
            ctx.rejectToJs("addLocalSystemChat", e.message)
        }
    }

    private suspend fun handleToggleVote(params: Map<String, Any?>) {
        try {
            val body = ctx.paramsToJson(params).apply {
                if (!has("userId") || optString("userId").isEmpty()) {
                    put("userId", ctx.appConfig.getSavedUserId() ?: "")
                }
            }
            val result = withContext(Dispatchers.IO) {
                ApiClient.postJson(ctx.appConfig.getEndpointByPath("/comm/togglevote"), body)
            }
            val channelCode = body.optString("channelCode", "")
            val chatCode = body.optString("chatCode", "")
            val vote = result.opt("vote")
            if (channelCode.isNotEmpty() && chatCode.isNotEmpty() && vote != null) {
                withContext(Dispatchers.IO) {
                    val chatDao = ctx.dbProvider.getChatDatabase().chatDao()
                    val existing = chatDao.getByChatCode(chatCode)
                    if (existing != null) {
                        val additional = try {
                            if (!existing.additional.isNullOrEmpty()) JSONObject(existing.additional) else JSONObject()
                        } catch (_: Exception) { JSONObject() }
                        additional.put("vote", vote)
                        chatDao.insert(existing.copy(additional = additional.toString()))
                    }
                }
            }
            withContext(Dispatchers.IO) { ctx.updateCrudOffset("togglevote", result) }
            ctx.resolveToJs("toggleVote", result)
        } catch (e: Exception) {
            ctx.rejectToJs("toggleVote", e.message)
        }
    }

    private suspend fun handleCloseVote(params: Map<String, Any?>) {
        try {
            val body = ctx.paramsToJson(params).apply {
                if (!has("userId") || optString("userId").isEmpty()) {
                    put("userId", ctx.appConfig.getSavedUserId() ?: "")
                }
            }
            val result = withContext(Dispatchers.IO) {
                ApiClient.postJson(ctx.appConfig.getEndpointByPath("/comm/closevote"), body)
            }
            val channelCode = body.optString("channelCode", "")
            val chatCode = body.optString("chatCode", "")
            val vote = result.opt("vote")
            if (channelCode.isNotEmpty() && chatCode.isNotEmpty() && vote != null) {
                withContext(Dispatchers.IO) {
                    val chatDao = ctx.dbProvider.getChatDatabase().chatDao()
                    val existing = chatDao.getByChatCode(chatCode)
                    if (existing != null) {
                        val additional = try {
                            if (!existing.additional.isNullOrEmpty()) JSONObject(existing.additional) else JSONObject()
                        } catch (_: Exception) { JSONObject() }
                        additional.put("vote", vote)
                        chatDao.insert(existing.copy(additional = additional.toString()))
                    }
                }
            }
            withContext(Dispatchers.IO) { ctx.updateCrudOffset("closevote", result) }
            ctx.resolveToJs("closeVote", result)
        } catch (e: Exception) {
            ctx.rejectToJs("closeVote", e.message)
        }
    }

    private suspend fun handlePinMessage(params: Map<String, Any?>) {
        try {
            val scope = ctx.paramStr(params, "scope").ifEmpty { "all" }
            val channelCode = ctx.paramStr(params, "channelCode")
            val chatCode = ctx.paramStr(params, "chatCode")
            val contents = ctx.paramStr(params, "contents")

            if (scope == "me") {
                withContext(Dispatchers.IO) {
                    val chatDb = ctx.dbProvider.getChatDatabase()
                    val ch = chatDb.channelDao().getByChannelCode(channelCode) ?: return@withContext
                    val add = try { JSONObject(ch.additional.ifEmpty { "{}" }) } catch (_: Exception) { JSONObject() }
                    val pins = add.optJSONArray("localPins") ?: JSONArray()
                    val newPins = JSONArray()
                    newPins.put(JSONObject().put("chatCode", chatCode).put("contents", contents).put("pinnedDate", System.currentTimeMillis()))
                    for (i in 0 until pins.length()) {
                        val p = pins.getJSONObject(i)
                        if (p.optString("chatCode") != chatCode) newPins.put(p)
                        if (newPins.length() >= 3) break
                    }
                    add.put("localPins", newPins)
                    chatDb.channelDao().updateAdditional(channelCode, add.toString())
                }
                ctx.resolveToJs("pinMessage", JSONObject().put("errorCode", 0).put("scope", "me"))
            } else {
                val mutableParams = params.toMutableMap()
                mutableParams.remove("scope")
                val body = ctx.paramsToJson(mutableParams).apply {
                    if (!has("userId") || optString("userId").isEmpty()) {
                        put("userId", ctx.appConfig.getSavedUserId() ?: "")
                    }
                }
                val result = withContext(Dispatchers.IO) {
                    ApiClient.postJson(ctx.appConfig.getEndpointByPath("/comm/pinmessage"), body)
                }
                withContext(Dispatchers.IO) { ctx.updateCrudOffset("pinmessage", result) }
                updatePinnedMessagesLocal(channelCode, result)
                ctx.resolveToJs("pinMessage", result)
            }
        } catch (e: Exception) {
            ctx.rejectToJs("pinMessage", e.message)
        }
    }

    private suspend fun handleUnpinMessage(params: Map<String, Any?>) {
        try {
            val scope = ctx.paramStr(params, "scope").ifEmpty { "all" }
            val channelCode = ctx.paramStr(params, "channelCode")
            val chatCode = ctx.paramStr(params, "chatCode")

            if (scope == "me") {
                withContext(Dispatchers.IO) {
                    val chatDb = ctx.dbProvider.getChatDatabase()
                    val ch = chatDb.channelDao().getByChannelCode(channelCode) ?: return@withContext
                    val add = try { JSONObject(ch.additional.ifEmpty { "{}" }) } catch (_: Exception) { JSONObject() }
                    val pins = add.optJSONArray("localPins") ?: JSONArray()
                    val newPins = JSONArray()
                    for (i in 0 until pins.length()) {
                        val p = pins.getJSONObject(i)
                        if (p.optString("chatCode") != chatCode) newPins.put(p)
                    }
                    add.put("localPins", newPins)
                    chatDb.channelDao().updateAdditional(channelCode, add.toString())
                }
                ctx.resolveToJs("unpinMessage", JSONObject().put("errorCode", 0).put("scope", "me"))
            } else {
                val mutableParams = params.toMutableMap()
                mutableParams.remove("scope")
                val body = ctx.paramsToJson(mutableParams)
                val result = withContext(Dispatchers.IO) {
                    ApiClient.postJson(ctx.appConfig.getEndpointByPath("/comm/unpinmessage"), body)
                }
                withContext(Dispatchers.IO) { ctx.updateCrudOffset("unpinmessage", result) }
                updatePinnedMessagesLocal(channelCode, result)
                ctx.resolveToJs("unpinMessage", result)
            }
        } catch (e: Exception) {
            ctx.rejectToJs("unpinMessage", e.message)
        }
    }

    private suspend fun updatePinnedMessagesLocal(channelCode: String, result: JSONObject) {
        try {
            val serverPins = result.optJSONArray("pinnedMessages") ?: return
            withContext(Dispatchers.IO) {
                val chatDb = ctx.dbProvider.getChatDatabase()
                val ch = chatDb.channelDao().getByChannelCode(channelCode) ?: return@withContext
                val add = try { JSONObject(ch.additional.ifEmpty { "{}" }) } catch (_: Exception) { JSONObject() }
                add.put("pinnedMessages", serverPins)
                chatDb.channelDao().updateAdditional(channelCode, add.toString())
                Log.d(TAG, "updatePinnedMessagesLocal: $channelCode → ${serverPins.length()} pins")
            }
            ctx.notifyReact("channelReady")
        } catch (e: Exception) {
            Log.w(TAG, "updatePinnedMessagesLocal error: ${e.message}")
        }
    }

    /**
     * additional JSON 정규화:
     * 1. {"file": null} → {"file": {}}
     * 2. file 객체 내부 null 필드 → 빈 문자열/0
     * 3. 구형 포맷 {"type":"file","fileName":"..."} → "file" 래퍼 추가
     *    (React 컴포넌트가 additional.file.fileName 접근하므로 file 키 필수)
     */
    private fun sanitizeAdditional(additional: String): String =
        ChannelRepository.sanitizeAdditional(additional)

    /** chatType: 프론트엔드가 문자열("FILE") 또는 숫자(8)로 보낼 수 있으므로 양쪽 대응 (비트마스크) */
    private fun parseChatType(value: Any?): Int {
        if (value is Number) return value.toInt()
        return when (value?.toString()?.uppercase()) {
            "TEXT" -> 1
            "IMAGE" -> 2
            "EMOTICON" -> 4
            "FILE" -> 8
            "REPLY" -> 16
            "SYSTEM" -> 32
            "DATE" -> 64
            "RTF" -> 128
            "CLOB" -> 256
            "POLARIS" -> 512 or 8
            "TRANSFER" -> 1024
            "DRM" -> 2048
            "IMPORTANT" -> 4096
            "HTML" -> 8192
            "", null -> 0
            else -> value?.toString()?.toIntOrNull() ?: 0
        }
    }
}
