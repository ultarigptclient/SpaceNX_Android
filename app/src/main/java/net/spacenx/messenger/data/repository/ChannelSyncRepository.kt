package net.spacenx.messenger.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.data.local.SyncLocks
import net.spacenx.messenger.common.AppConfig.Companion.EP_COMM_SYNC_CHANNEL
import net.spacenx.messenger.common.AppConfig.Companion.EP_COMM_SYNC_CHAT
import net.spacenx.messenger.common.JsonStreamUtil
import net.spacenx.messenger.common.parseStream
import net.spacenx.messenger.data.local.DatabaseProvider
import net.spacenx.messenger.data.local.entity.ChannelEntity
import net.spacenx.messenger.data.local.entity.ChannelMemberEntity
import net.spacenx.messenger.data.local.entity.ChannelOffsetEntity
import net.spacenx.messenger.data.local.entity.ChatEntity
import net.spacenx.messenger.data.local.entity.ChatEventEntity
import net.spacenx.messenger.data.local.entity.SyncMetaEntity
import net.spacenx.messenger.data.remote.api.ApiClient
import net.spacenx.messenger.data.remote.api.dto.SyncChannelRequestDTO
import net.spacenx.messenger.data.remote.api.dto.SyncChatRequestDTO
import net.spacenx.messenger.service.socket.SocketSessionManager
import org.json.JSONObject
import net.spacenx.messenger.BuildConfig
import net.spacenx.messenger.util.FileLogger

/**
 * 채널·채팅 동기화 (REST → DB)
 * syncChannel, syncChannelFull, syncChat
 */
class ChannelSyncRepository(
    private val databaseProvider: DatabaseProvider,
    private val appConfig: AppConfig,
    private val sessionManager: SocketSessionManager
) {
    companion object {
        private const val TAG = "ChannelSyncRepository"
        internal const val SYNC_META_KEY = "channel_last_sync_time"
        internal const val CHAT_SYNC_META_KEY = "chat_last_sync_time"
        // 무한 루프 방지: 한 번의 syncChannel/syncChat 호출에서 최대 페이지 수
        private const val MAX_SYNC_PAGES = 200
        private const val CHANNEL_PAGE_SIZE = 200
        private const val CHAT_PAGE_SIZE = 500
    }

    private val channelSyncMutex = Mutex()
    private val chatSyncMutex = Mutex()

    suspend fun syncChannelFull(userId: String): Boolean {
        Log.d(TAG, "syncChannelFull: resetting offset to 0 for userId=$userId")
        val chatDb = databaseProvider.getChatDatabase()
        chatDb.syncMetaDao().deleteByKey(SYNC_META_KEY)
        return syncChannel(userId)
    }

    suspend fun syncChannel(userId: String): Boolean {
        if (!channelSyncMutex.tryLock()) { Log.d(TAG, "syncChannel already in progress, skipping"); return true }
        try {
        return withContext(Dispatchers.IO) {
            try {
                val token = ChannelUtils.awaitToken(sessionManager)
                val chatDb = databaseProvider.getChatDatabase()
                val channelApi = ApiClient.createChannelApiFromBaseUrl(appConfig.getRestBaseUrl(), token)
                val endpoint = appConfig.getEndpoint(EP_COMM_SYNC_CHANNEL, "api/comm/syncchannel")

                var pageCount = 0
                do {
                val lastOffset = chatDb.syncMetaDao().getValueSync(SYNC_META_KEY) ?: 0L

                Log.d(TAG, "syncChannel: userId=$userId, lastOffset=$lastOffset, page=$pageCount")
                FileLogger.log(TAG, "syncChannel REQ userId=$userId offset=$lastOffset page=$pageCount")

                val request = SyncChannelRequestDTO(userId = userId, channelEventOffset = lastOffset, reset = false, limit = CHANNEL_PAGE_SIZE)
                val response = channelApi.syncChannel(endpoint, request)

                if (!response.isSuccessful) {
                    Log.e(TAG, "syncChannel HTTP error: ${response.code()}")
                    return@withContext false
                }

                var errorCode = -1
                var lastEventId = 0L
                var hasMore = false
                val eventsArray = mutableListOf<JSONObject>()
                val respBody = response.body() ?: return@withContext false
                respBody.parseStream { reader ->
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "errorCode" -> errorCode = JsonStreamUtil.nextIntOrZero(reader)
                            "lastEventId" -> lastEventId = JsonStreamUtil.nextLongOrZero(reader)
                            "hasMore" -> hasMore = JsonStreamUtil.nextBooleanOrFalse(reader)
                            "events" -> {
                                reader.beginArray()
                                while (reader.hasNext()) eventsArray.add(JsonStreamUtil.readObject(reader))
                                reader.endArray()
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                Log.d(TAG, "syncChannel page: events=${eventsArray.size}, lastEventId=$lastEventId, hasMore=$hasMore, errorCode=$errorCode")
                FileLogger.log(TAG, "syncChannel RES events=${eventsArray.size} lastEventId=$lastEventId hasMore=$hasMore errorCode=$errorCode")

                if (errorCode != 0) return@withContext false

                // 기존 채널의 state를 미리 로드: MAKE_CHANNEL 이외 이벤트에서 state가 낮아지지 않도록 보존
                val existingStateMap = chatDb.channelDao().getAllSync().associate { it.channelCode to it.state }

                val channels = mutableListOf<ChannelEntity>()
                val members = mutableListOf<ChannelMemberEntity>()
                val offsets = mutableListOf<ChannelOffsetEntity>()
                val destroyedChannelCodes = mutableListOf<String>()
                val removedChannelCodes = mutableListOf<String>()
                val removedMemberPairs = mutableListOf<Pair<String, String>>()

                data class AddGroup(val channelCode: String, val eventDate: Long, val userIds: MutableList<String> = mutableListOf())
                data class RemoveEntry(val channelCode: String, val userId: String, val unregistDate: Long)

                val addGroups = mutableMapOf<String, AddGroup>()
                val removeEntries = mutableListOf<RemoveEntry>()
                val myUserId = appConfig.getSavedUserId() ?: ""

                if (eventsArray.isNotEmpty()) {
                    for (event in eventsArray) {
                        val eventType = event.optString("eventType", "")
                        val eventDate = event.optLong("eventDate", 0L)
                        val ch = event.optJSONObject("channel") ?: JSONObject()
                        val channelCode = ch.optString("channelCode", "")
                        val eventMembers = event.optJSONArray("channelMembers")
                        if (BuildConfig.DEBUG) Log.d(TAG, "syncChannel event: type=$eventType, channel=$channelCode, raw=${event.toString().take(200)}")

                        when (eventType) {
                            "MAKE_CHANNEL", "ADD_MEMBER", "MOD_CHANNEL", "SET_CHANNEL" -> {
                                if (channelCode.isNotEmpty()) {
                                    val existingState = existingStateMap[channelCode] ?: 0
                                    val serverState = ch.optInt("state", -1)
                                    val channelState = when {
                                        serverState >= 0 -> maxOf(existingState, serverState)
                                        eventType == "MAKE_CHANNEL" -> if (eventMembers != null && eventMembers.length() > 2) 1 else 0
                                        else -> existingState  // MOD/SET/ADD_MEMBER: 기존 state 유지 (그룹→1:1 다운그레이드 방지)
                                    }
                                    channels.add(ChannelEntity(
                                        channelCode = channelCode,
                                        channelName = ch.optString("channelName", ""),
                                        channelType = ch.optString("channelType", ""),
                                        state = channelState,
                                        lastChatDate = eventDate,
                                        additional = ch.optString("additional", "")
                                    ))
                                }
                                if (eventMembers != null) {
                                    for (j in 0 until eventMembers.length()) {
                                        val m = eventMembers.getJSONObject(j)
                                        val mUserId = m.optString("userId", "")
                                        val mRegistDate = m.optLong("registDate", 0L)
                                        members.add(ChannelMemberEntity(
                                            channelCode = m.optString("channelCode", channelCode),
                                            userId = mUserId,
                                            registDate = mRegistDate,
                                            unregistDate = m.optLong("unregistDate", 0L)
                                        ))
                                        if (eventType == "ADD_MEMBER" && mUserId.isNotEmpty()
                                            && mUserId != myUserId && channelCode.isNotEmpty()
                                            && mRegistDate > eventDate) {
                                            val key = "${channelCode}_${eventDate}"
                                            addGroups.getOrPut(key) { AddGroup(channelCode, eventDate) }.userIds.add(mUserId)
                                        }
                                    }
                                }
                                val offsetList = event.optJSONArray("channelOffsetList")
                                    ?: ch.optJSONArray("channelOffsetList")
                                if (offsetList != null) {
                                    for (j in 0 until offsetList.length()) {
                                        val o = offsetList.getJSONObject(j)
                                        val oUserId = o.optString("userId", "")
                                        val oDate = o.optLong("offsetDate", 0L)
                                        if (oUserId.isNotEmpty() && oDate > 0) {
                                            offsets.add(ChannelOffsetEntity(channelCode = channelCode, userId = oUserId, offsetDate = oDate))
                                        }
                                    }
                                }
                            }
                            "REMOVE_MEMBER" -> {
                                if (eventMembers != null) {
                                    for (j in 0 until eventMembers.length()) {
                                        val m = eventMembers.getJSONObject(j)
                                        val mUserId = m.optString("userId", "")
                                        val mChannelCode = m.optString("channelCode", channelCode)
                                        val mUnregistDate = m.optLong("unregistDate", 0L)
                                        if (mUserId.isNotEmpty() && mChannelCode.isNotEmpty()) {
                                            removedMemberPairs.add(Pair(mChannelCode, mUserId))
                                        }
                                        if (mUserId.isNotEmpty() && mUserId != myUserId
                                            && channelCode.isNotEmpty() && mUnregistDate > 0) {
                                            removeEntries.add(RemoveEntry(channelCode, mUserId, mUnregistDate))
                                        }
                                    }
                                }
                            }
                            "DESTROY_CHANNEL" -> { if (channelCode.isNotEmpty()) destroyedChannelCodes.add(channelCode) }
                            "REMOVE_CHANNEL" -> { if (channelCode.isNotEmpty()) removedChannelCodes.add(channelCode) }
                        }
                    }
                }

                val systemChats = mutableListOf<ChatEntity>()
                // 2026-04-18: 서버가 syncChat ADD 이벤트로 chatType="SYSTEM" 입장/퇴장 메시지를 별도 내려주므로
                //             sync 경로에서도 자체 생성 비활성화 (중복 방지). 서버 경로 이상 시 복구 위해 주석 보존.
                /*
                if (addGroups.isNotEmpty() || removeEntries.isNotEmpty()) {
                    try {
                        val orgDb = databaseProvider.getOrgDatabase()
                        val allUserIds = (addGroups.values.flatMap { it.userIds } +
                            removeEntries.map { it.userId }).distinct()
                        val userNameMap = orgDb.userDao().getByUserIds(allUserIds)
                            .associate { u ->
                                u.userId to try {
                                    JSONObject(u.userInfo).optString("userName", u.userId)
                                } catch (_: Exception) { u.userId }
                            }
                        fun resolveUserName(userId: String) = userNameMap[userId] ?: userId
                        for ((_, group) in addGroups) {
                            val names = group.userIds.map { resolveUserName(it) }
                            systemChats.add(ChatEntity(
                                channelCode = group.channelCode,
                                chatCode = "sys_add_${group.channelCode}_${group.eventDate}",
                                sendUserId = "",
                                contents = "${names.joinToString(", ")}님이 대화에 참여했습니다.",
                                sendDate = group.eventDate,
                                chatType = 99
                            ))
                        }
                        for (entry in removeEntries) {
                            systemChats.add(ChatEntity(
                                channelCode = entry.channelCode,
                                chatCode = "sys_rm_${entry.channelCode}_${entry.userId}_${entry.unregistDate}",
                                sendUserId = "",
                                contents = "${resolveUserName(entry.userId)}님이 퇴장했습니다.",
                                sendDate = entry.unregistDate,
                                chatType = 99
                            ))
                        }
                    } catch (_: Exception) { /* org DB 미준비 시 무시 */ }
                }
                */

                SyncLocks.chatDbMutex.withLock { chatDb.runInTransaction {
                    if (channels.isNotEmpty()) {
                        val existingLastChat = mutableMapOf<String, Triple<Long, String, String>>()
                        val oldChannels = chatDb.channelDao().getAllSync()
                        for (old in oldChannels) {
                            if (old.lastChatDate > 0L) {
                                existingLastChat[old.channelCode] = Triple(old.lastChatDate, old.lastChatContents, old.lastSendUserId)
                            }
                        }
                        if (lastOffset == 0L) {
                            chatDb.channelDao().deleteAllSync()
                            chatDb.channelMemberDao().deleteAllSync()
                        }
                        for (i in channels.indices) {
                            val saved = existingLastChat[channels[i].channelCode]
                            if (saved != null) {
                                channels[i] = channels[i].copy(lastChatDate = saved.first, lastChatContents = saved.second, lastSendUserId = saved.third)
                            }
                        }
                    }
                    if (channels.isNotEmpty()) { chatDb.channelDao().insertAllSync(channels); Log.d(TAG, "syncChannel: ${channels.size} channels upserted") }
                    if (members.isNotEmpty()) { chatDb.channelMemberDao().insertAllSync(members); Log.d(TAG, "syncChannel: ${members.size} members upserted") }
                    if (offsets.isNotEmpty()) { chatDb.channelOffsetDao().insertAllSync(offsets); Log.d(TAG, "syncChannel: ${offsets.size} offsets saved") }
                    for ((rmChannelCode, rmUserId) in removedMemberPairs) {
                        chatDb.channelMemberDao().deleteMemberSync(rmChannelCode, rmUserId)
                        if (BuildConfig.DEBUG) Log.d(TAG, "syncChannel: member hard-deleted channelCode=$rmChannelCode userId=$rmUserId")
                    }
                    for (code in destroyedChannelCodes) {
                        chatDb.channelDao().deleteByChannelCodeSync(code)
                        chatDb.channelMemberDao().deleteByChannelCodeSync(code)
                        if (BuildConfig.DEBUG) Log.d(TAG, "syncChannel: destroyed channel=$code")
                    }
                    for (code in removedChannelCodes) {
                        chatDb.channelMemberDao().unregistMemberSync(code, myUserId, System.currentTimeMillis())
                        if (BuildConfig.DEBUG) Log.d(TAG, "syncChannel: removed channel=$code (unregist)")
                    }
                    if (systemChats.isNotEmpty()) { chatDb.chatDao().insertAllSync(systemChats); Log.d(TAG, "syncChannel: ${systemChats.size} system chats upserted") }
                    if (lastEventId > 0) { chatDb.syncMetaDao().insertSync(SyncMetaEntity(SYNC_META_KEY, lastEventId)) }
                } }

                Log.d(TAG, "syncChannel page done: lastEventId=$lastEventId, channels=${channels.size}, members=${members.size}")

                // ── 무한 루프 방지 가드 (MessageRepository.syncMessage 패턴과 동일) ──
                pageCount++
                val emptyPage = eventsArray.isEmpty()
                val offsetStalled = lastEventId <= lastOffset
                if (!hasMore || emptyPage || offsetStalled || pageCount >= MAX_SYNC_PAGES) {
                    if (hasMore && pageCount >= MAX_SYNC_PAGES) {
                        Log.w(TAG, "syncChannel: hit MAX_SYNC_PAGES=$MAX_SYNC_PAGES, stopping to avoid runaway")
                    }
                    if (hasMore && offsetStalled && !emptyPage) {
                        Log.w(TAG, "syncChannel: offset stalled at $lastOffset, stopping (server inconsistency)")
                    }
                    break
                }
                } while (true)

                FileLogger.log(TAG, "syncChannel DONE pages=$pageCount")
                true
            } catch (e: Exception) {
                Log.e(TAG, "syncChannel error: ${e.message}", e)
                FileLogger.log(TAG, "syncChannel ERROR ${e.message}")
                false
            }
        }
        } finally { channelSyncMutex.unlock() }
    }

    suspend fun syncChat(userId: String): Boolean {
        if (!chatSyncMutex.tryLock()) { Log.d(TAG, "syncChat already in progress, skipping"); return true }
        try {
        return withContext(Dispatchers.IO) {
            try {
                val token = ChannelUtils.awaitToken(sessionManager)
                val chatDb = databaseProvider.getChatDatabase()
                val channelApi = ApiClient.createChannelApiFromBaseUrl(appConfig.getRestBaseUrl(), token)
                val endpoint = appConfig.getEndpoint(EP_COMM_SYNC_CHAT, "api/comm/syncchat")

                var pageCount = 0
                do {
                val lastOffset = chatDb.syncMetaDao().getValueSync(CHAT_SYNC_META_KEY) ?: 0L

                Log.d(TAG, "syncChat: userId=$userId, lastOffset=$lastOffset, page=$pageCount")
                FileLogger.log(TAG, "syncChat REQ userId=$userId offset=$lastOffset page=$pageCount")

                val request = SyncChatRequestDTO(userId = userId, chatEventOffset = lastOffset, limit = CHAT_PAGE_SIZE)
                val response = channelApi.syncChat(endpoint, request)

                if (!response.isSuccessful) {
                    Log.e(TAG, "syncChat HTTP error: ${response.code()}")
                    return@withContext false
                }

                var errorCode = -1
                var lastEventId = lastOffset
                var hasMore = false
                val eventsArray = mutableListOf<JSONObject>()
                val respBody = response.body() ?: return@withContext false
                respBody.parseStream { reader ->
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "errorCode" -> errorCode = JsonStreamUtil.nextIntOrZero(reader)
                            "lastEventId" -> lastEventId = JsonStreamUtil.nextLongOrZero(reader)
                            "hasMore" -> hasMore = JsonStreamUtil.nextBooleanOrFalse(reader)
                            "events" -> {
                                reader.beginArray()
                                while (reader.hasNext()) eventsArray.add(JsonStreamUtil.readObject(reader))
                                reader.endArray()
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                Log.d(TAG, "syncChat page: events=${eventsArray.size}, lastEventId=$lastEventId, hasMore=$hasMore, errorCode=$errorCode")
                FileLogger.log(TAG, "syncChat RES events=${eventsArray.size} lastEventId=$lastEventId hasMore=$hasMore errorCode=$errorCode")

                if (errorCode != 0) return@withContext false

                val chats = mutableListOf<ChatEntity>()
                val chatEvents = mutableListOf<ChatEventEntity>()
                val deletedChats = mutableListOf<Pair<String, String>>()
                val reactionUpdates = mutableListOf<Pair<String, JSONObject>>()
                val lastChatPerChannel = mutableMapOf<String, JSONObject>()
                val readOffsets = mutableListOf<ChannelOffsetEntity>()
                // 내 채널 가입 시점 캐시 — 내가 방에 들어오기 전의 메시지(초대 SYSTEM 포함)는 skip 하기 위해 사용
                val myRegistDateCache = mutableMapOf<String, Long>()
                suspend fun getMyRegistDate(channelCode: String): Long {
                    myRegistDateCache[channelCode]?.let { return it }
                    val v = if (userId.isEmpty() || channelCode.isEmpty()) 0L
                            else chatDb.channelMemberDao().getMember(channelCode, userId)?.registDate ?: 0L
                    myRegistDateCache[channelCode] = v
                    return v
                }

                if (eventsArray.isNotEmpty()) {
                    var readEventCount = 0
                    for (event in eventsArray) {
                        val eventType = event.optString("eventType", "")

                        if (eventType == "READ") {
                            readEventCount++
                            val readUserId = event.optString("readUserId", "")
                            val readDate = event.optLong("readDate", 0L)
                            val readChannelCode = event.optString("channelCode", "")
                            if (readUserId.isNotEmpty() && readDate > 0 && readChannelCode.isNotEmpty()) {
                                readOffsets.add(ChannelOffsetEntity(channelCode = readChannelCode, userId = readUserId, offsetDate = readDate))
                            }
                            continue
                        }
                        if (BuildConfig.DEBUG) Log.d(TAG, "syncChat event: type=$eventType raw=${event.toString().take(600)}")

                        val c = event.optJSONObject("chat") ?: continue
                        val channelCode = c.optString("channelCode", "")
                        val chatCode = c.optString("chatCode", "")
                        val sendDate = c.optLong("sendDate", 0L)
                        val eventId = event.optLong("eventId", 0L)

                        if (eventId > 0) {
                            chatEvents.add(ChatEventEntity(
                                eventId = eventId,
                                command = eventType,
                                channelCode = channelCode,
                                chatCode = chatCode,
                                sendUserId = c.optString("sendUserId", ""),
                                sendDate = sendDate
                            ))
                        }

                        when (eventType) {
                            "ADD" -> {
                                val contents = c.optString("contents", "")
                                // chatType 매핑:
                                //  - 문자열: "AI"/"ai" → -99, "SYSTEM"/"system" → 99 (실시간 WS 경로)
                                //  - 숫자: 6 → 99 (서버 sync REST 에서 SYSTEM을 6으로 내려줌 — 입장/퇴장/초대)
                                //          그 외 숫자는 그대로 유지 (1=TALK 등)
                                val rawChatType = c.opt("chatType")
                                val resolvedChatType = when {
                                    rawChatType == "AI" || rawChatType == "ai" -> -99
                                    rawChatType == "SYSTEM" || rawChatType == "system" -> 99
                                    rawChatType is Number -> {
                                        val n = rawChatType.toInt()
                                        if (n == 6) 99 else n
                                    }
                                    else -> c.optInt("chatType", 0)
                                }
                                // 내가 방에 가입한 시점 이전(포함)의 메시지(초대 SYSTEM 포함)는 저장하지 않음.
                                // 서버는 멤버 합류 후 채널 history 를 모두 내려주지만, 클라이언트는 가입 이후 내용만 보여야 함.
                                // 나를 초대한 SYSTEM 메시지는 sendDate == 내 registDate 로 같으므로 `<=` 로 비교.
                                val myRegistDate = getMyRegistDate(channelCode)
                                if (myRegistDate > 0L && sendDate > 0L && sendDate <= myRegistDate) {
                                    if (BuildConfig.DEBUG) Log.d(TAG, "syncChat ADD: skipped (pre-join) chatCode=$chatCode, channel=$channelCode, sendDate=$sendDate <= myRegistDate=$myRegistDate, contents=${contents.take(40)}")
                                    continue
                                }
                                if (BuildConfig.DEBUG) Log.d(TAG, "syncChat ADD: chatCode=$chatCode, channel=$channelCode, contents=${contents.take(50)}, sendDate=$sendDate, rawChatType=$rawChatType(${rawChatType?.javaClass?.simpleName}), resolvedChatType=$resolvedChatType, chatKeys=${c.keys().asSequence().toList()}")
                                chats.add(ChatEntity(
                                    channelCode = channelCode,
                                    chatCode = chatCode,
                                    sendUserId = c.optString("sendUserId", ""),
                                    contents = contents,
                                    sendDate = sendDate,
                                    chatType = resolvedChatType,
                                    chatFont = c.optString("chatFont", ""),
                                    additional = c.optJSONObject("additional")?.toString() ?: c.optString("additional", ""),
                                    state = c.optInt("state", 0)
                                ))
                                val prev = lastChatPerChannel[channelCode]
                                if (prev == null || sendDate > prev.optLong("sendDate", 0L)) {
                                    lastChatPerChannel[channelCode] = c
                                }
                            }
                            "DEL" -> {
                                if (BuildConfig.DEBUG) Log.d(TAG, "syncChat DEL: chatCode=$chatCode, channel=$channelCode")
                                if (channelCode.isNotEmpty() && chatCode.isNotEmpty()) {
                                    deletedChats.add(channelCode to chatCode)
                                }
                            }
                            "REACTION", "MOD" -> {
                                if (BuildConfig.DEBUG) Log.d(TAG, "syncChat $eventType: chatCode=$chatCode")
                                if (chatCode.isNotEmpty()) reactionUpdates.add(chatCode to c)
                            }
                        }
                    }
                    if (readEventCount > 0) Log.d(TAG, "syncChat: $readEventCount READ events processed")
                }

                SyncLocks.chatDbMutex.withLock { chatDb.runInTransaction {
                    if (chatEvents.isNotEmpty()) { chatDb.chatEventDao().insertAllSync(chatEvents); Log.d(TAG, "syncChat: ${chatEvents.size} chatEvents saved") }
                    if (chats.isNotEmpty()) { chatDb.chatDao().insertAllSync(chats); Log.d(TAG, "syncChat: ${chats.size} chats saved") }
                    if (readOffsets.isNotEmpty()) { chatDb.channelOffsetDao().insertAllSync(readOffsets); Log.d(TAG, "syncChat: ${readOffsets.size} read offsets saved") }
                    for ((cc, chatCode) in deletedChats) { chatDb.chatDao().markDeletedByChatCodeSync(cc, chatCode) }
                    if (deletedChats.isNotEmpty()) Log.d(TAG, "syncChat: ${deletedChats.size} chats marked deleted")
                    for ((chatCode, chatObj) in reactionUpdates) {
                        val additional = chatObj.optJSONObject("additional")?.toString() ?: chatObj.optString("additional", "")
                        if (additional.isNotEmpty()) {
                            val existing = chatDb.chatDao().getByChatCodeSync(chatCode)
                            if (existing != null) {
                                val merged = try {
                                    val existingAdd = if (!existing.additional.isNullOrEmpty()) JSONObject(existing.additional) else JSONObject()
                                    val newAdd = JSONObject(additional)
                                    for (key in newAdd.keys()) { existingAdd.put(key, newAdd.get(key)) }
                                    existingAdd.toString()
                                } catch (_: Exception) { additional }
                                chatDb.chatDao().updateAdditionalByChatCodeSync(chatCode, merged)
                            }
                        }
                    }
                    if (reactionUpdates.isNotEmpty()) Log.d(TAG, "syncChat: ${reactionUpdates.size} REACTION/MOD events processed")
                    for ((channelCode, chatObj) in lastChatPerChannel) {
                        chatDb.channelDao().updateLastChatSync(
                            channelCode = channelCode,
                            date = chatObj.optLong("sendDate", 0L),
                            contents = chatObj.optString("contents", ""),
                            lastSendUserId = chatObj.optString("sendUserId", "")
                        )
                    }
                    if (lastEventId > lastOffset) {
                        chatDb.syncMetaDao().insertSync(SyncMetaEntity(CHAT_SYNC_META_KEY, lastEventId))
                    }
                } }

                Log.d(TAG, "syncChat page done: chats=${chats.size}, lastEventId=$lastEventId")

                // ── 무한 루프 방지 가드 ──
                pageCount++
                val emptyPage = eventsArray.isEmpty()
                val offsetStalled = lastEventId <= lastOffset
                if (!hasMore || emptyPage || offsetStalled || pageCount >= MAX_SYNC_PAGES) {
                    if (hasMore && pageCount >= MAX_SYNC_PAGES) {
                        Log.w(TAG, "syncChat: hit MAX_SYNC_PAGES=$MAX_SYNC_PAGES, stopping to avoid runaway")
                    }
                    if (hasMore && offsetStalled && !emptyPage) {
                        Log.w(TAG, "syncChat: offset stalled at $lastOffset, stopping (server inconsistency)")
                    }
                    break
                }
                } while (true)

                FileLogger.log(TAG, "syncChat DONE pages=$pageCount")
                true
            } catch (e: Exception) {
                Log.e(TAG, "syncChat error: ${e.message}", e)
                FileLogger.log(TAG, "syncChat ERROR ${e.message}")
                false
            }
        }
        } finally { chatSyncMutex.unlock() }
    }
}
