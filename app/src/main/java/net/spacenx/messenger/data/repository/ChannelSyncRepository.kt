package net.spacenx.messenger.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import net.spacenx.messenger.common.AppConfig
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
import org.json.JSONObject

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
                val lastOffset = chatDb.syncMetaDao().getValueSync(SYNC_META_KEY) ?: 0L

                Log.d(TAG, "syncChannel: userId=$userId, lastOffset=$lastOffset")

                val channelApi = ApiClient.createChannelApiFromBaseUrl(appConfig.getRestBaseUrl(), token)
                val endpoint = appConfig.getEndpoint(EP_COMM_SYNC_CHANNEL, "api/comm/syncchannel")
                val request = SyncChannelRequestDTO(userId = userId, channelEventOffset = lastOffset, reset = false)
                val response = channelApi.syncChannel(endpoint, request)

                if (!response.isSuccessful) {
                    Log.e(TAG, "syncChannel HTTP error: ${response.code()}")
                    return@withContext false
                }

                var errorCode = -1
                var lastEventId = 0L
                val eventsArray = mutableListOf<JSONObject>()
                val respBody = response.body() ?: return@withContext false
                respBody.parseStream { reader ->
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "errorCode" -> errorCode = JsonStreamUtil.nextIntOrZero(reader)
                            "lastEventId" -> lastEventId = JsonStreamUtil.nextLongOrZero(reader)
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
                Log.d(TAG, "syncChannel page: events=${eventsArray.size}, lastEventId=$lastEventId, errorCode=$errorCode")

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

                chatDb.runInTransaction {
                    if (lastOffset == 0L && channels.isNotEmpty()) {
                        val existingLastChat = mutableMapOf<String, Triple<Long, String, String>>()
                        val oldChannels = chatDb.channelDao().getAllSync()
                        for (old in oldChannels) {
                            if (old.lastChatDate > 0L) {
                                existingLastChat[old.channelCode] = Triple(old.lastChatDate, old.lastChatContents, old.masterUserId)
                            }
                        }
                        chatDb.channelDao().deleteAllSync()
                        chatDb.channelMemberDao().deleteAllSync()
                        for (i in channels.indices) {
                            val saved = existingLastChat[channels[i].channelCode]
                            if (saved != null) {
                                channels[i] = channels[i].copy(lastChatDate = saved.first, lastChatContents = saved.second, masterUserId = saved.third)
                            }
                        }
                    }
                    if (channels.isNotEmpty()) { chatDb.channelDao().insertAllSync(channels); Log.d(TAG, "syncChannel: ${channels.size} channels upserted") }
                    if (members.isNotEmpty()) { chatDb.channelMemberDao().insertAllSync(members); Log.d(TAG, "syncChannel: ${members.size} members upserted") }
                    if (offsets.isNotEmpty()) { chatDb.channelOffsetDao().insertAllSync(offsets); Log.d(TAG, "syncChannel: ${offsets.size} offsets saved") }
                    for ((rmChannelCode, rmUserId) in removedMemberPairs) {
                        chatDb.channelMemberDao().deleteMemberSync(rmChannelCode, rmUserId)
                        Log.d(TAG, "syncChannel: member hard-deleted channelCode=$rmChannelCode userId=$rmUserId")
                    }
                    for (code in destroyedChannelCodes) {
                        chatDb.channelDao().deleteByChannelCodeSync(code)
                        chatDb.channelMemberDao().deleteByChannelCodeSync(code)
                        Log.d(TAG, "syncChannel: destroyed channel=$code")
                    }
                    for (code in removedChannelCodes) {
                        chatDb.channelMemberDao().unregistMemberSync(code, myUserId, System.currentTimeMillis())
                        Log.d(TAG, "syncChannel: removed channel=$code (unregist)")
                    }
                    if (systemChats.isNotEmpty()) { chatDb.chatDao().insertAllSync(systemChats); Log.d(TAG, "syncChannel: ${systemChats.size} system chats upserted") }
                    if (lastEventId > 0) { chatDb.syncMetaDao().insertSync(SyncMetaEntity(SYNC_META_KEY, lastEventId)) }
                }

                Log.d(TAG, "syncChannel complete: lastEventId=$lastEventId")
                true
            } catch (e: Exception) {
                Log.e(TAG, "syncChannel error: ${e.message}", e)
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
                val lastOffset = chatDb.syncMetaDao().getValueSync(CHAT_SYNC_META_KEY) ?: 0L

                Log.d(TAG, "syncChat: userId=$userId, lastOffset=$lastOffset")

                val channelApi = ApiClient.createChannelApiFromBaseUrl(appConfig.getRestBaseUrl(), token)
                val endpoint = appConfig.getEndpoint(EP_COMM_SYNC_CHAT, "api/comm/syncchat")
                val request = SyncChatRequestDTO(userId = userId, chatEventOffset = lastOffset)
                val response = channelApi.syncChat(endpoint, request)

                if (!response.isSuccessful) {
                    Log.e(TAG, "syncChat HTTP error: ${response.code()}")
                    return@withContext false
                }

                var errorCode = -1
                var lastEventId = lastOffset
                val eventsArray = mutableListOf<JSONObject>()
                val respBody = response.body() ?: return@withContext false
                respBody.parseStream { reader ->
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "errorCode" -> errorCode = JsonStreamUtil.nextIntOrZero(reader)
                            "lastEventId" -> lastEventId = JsonStreamUtil.nextLongOrZero(reader)
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
                Log.d(TAG, "syncChat page: events=${eventsArray.size}, lastEventId=$lastEventId, errorCode=$errorCode")

                if (errorCode != 0) return@withContext false

                val chats = mutableListOf<ChatEntity>()
                val chatEvents = mutableListOf<ChatEventEntity>()
                val deletedChats = mutableListOf<Pair<String, String>>()
                val reactionUpdates = mutableListOf<Pair<String, JSONObject>>()
                val lastChatPerChannel = mutableMapOf<String, JSONObject>()

                if (eventsArray.isNotEmpty()) {
                    for (event in eventsArray) {
                        val eventType = event.optString("eventType", "")
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
                                chats.add(ChatEntity(
                                    channelCode = channelCode,
                                    chatCode = chatCode,
                                    sendUserId = c.optString("sendUserId", ""),
                                    contents = c.optString("contents", ""),
                                    sendDate = sendDate,
                                    chatType = c.optInt("chatType", 0),
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
                                if (channelCode.isNotEmpty() && chatCode.isNotEmpty()) {
                                    deletedChats.add(channelCode to chatCode)
                                }
                            }
                            "REACTION", "MOD" -> {
                                if (chatCode.isNotEmpty()) reactionUpdates.add(chatCode to c)
                            }
                        }
                    }
                }

                chatDb.runInTransaction {
                    if (chatEvents.isNotEmpty()) { chatDb.chatEventDao().insertAllSync(chatEvents); Log.d(TAG, "syncChat: ${chatEvents.size} chatEvents saved") }
                    if (chats.isNotEmpty()) { chatDb.chatDao().insertAllSync(chats); Log.d(TAG, "syncChat: ${chats.size} chats saved") }
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
                            masterUserId = chatObj.optString("sendUserId", "")
                        )
                    }
                    if (lastEventId > lastOffset) {
                        chatDb.syncMetaDao().insertSync(SyncMetaEntity(CHAT_SYNC_META_KEY, lastEventId))
                    }
                }

                Log.d(TAG, "syncChat complete: ${chats.size} chats, lastEventId=$lastEventId")
                true
            } catch (e: Exception) {
                Log.e(TAG, "syncChat error: ${e.message}", e)
                false
            }
        }
        } finally { chatSyncMutex.unlock() }
    }
}
