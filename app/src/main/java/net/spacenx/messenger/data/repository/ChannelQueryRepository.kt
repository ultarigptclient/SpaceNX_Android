package net.spacenx.messenger.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.data.local.DatabaseProvider
import net.spacenx.messenger.data.local.entity.ChannelMemberEntity
import net.spacenx.messenger.service.socket.SocketSessionManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 채널 조회 (로컬 DB → JSON 변환, 웹 전달용)
 * getChannelCount, getChannelListAsJson, openChannelRoom, searchChannelRoom
 */
class ChannelQueryRepository(
    private val databaseProvider: DatabaseProvider,
    private val appConfig: AppConfig,
    private val sessionManager: SocketSessionManager
) {
    companion object {
        private const val TAG = "ChannelQueryRepository"
    }

    suspend fun getChannelCount(): Int {
        return withContext(Dispatchers.IO) {
            databaseProvider.getChatDatabase().channelDao().getAll().size
        }
    }

    suspend fun getChannelListAsJson(): String {
        return withContext(Dispatchers.IO) {
            val chatDb = databaseProvider.getChatDatabase()
            val orgDb = databaseProvider.getOrgDatabase()
            val myUserId = databaseProvider.getCurrentUserId() ?: ""
            val allChannels = chatDb.channelDao().getAll()
            val allCodes = allChannels.map { it.channelCode }

            // N+1 제거: 멤버/offset 을 채널 단위 루프 대신 한 번의 IN 쿼리로 일괄 조회 후 in-memory group by.
            // 채널 50개 + 멤버 평균 5명 기준 100+ 쿼리 → 2 쿼리.
            val channelMembersMap: Map<String, List<ChannelMemberEntity>> = if (allCodes.isNotEmpty())
                chatDb.channelMemberDao().getActiveMembersForChannels(allCodes).groupBy { it.channelCode }
            else emptyMap()
            val offsetsByChannel: Map<String, Map<String, Long>> = if (allCodes.isNotEmpty())
                chatDb.channelOffsetDao().getForChannels(allCodes)
                    .groupBy { it.channelCode }
                    .mapValues { entry -> entry.value.associate { it.userId to it.offsetDate } }
            else emptyMap()

            val dmKeyToBestCode = mutableMapOf<String, Pair<String, Long>>()
            for (channel in allChannels) {
                val members = channelMembersMap[channel.channelCode] ?: emptyList()
                if (channel.channelType == "DM" && members.size == 2) {
                    val key = members.map { it.userId }.sorted().joinToString("|")
                    val prev = dmKeyToBestCode[key]
                    if (prev == null || channel.lastChatDate >= prev.second) {
                        dmKeyToBestCode[key] = channel.channelCode to channel.lastChatDate
                    }
                }
            }

            val allMemberIds = channelMembersMap.values.flatMap { ms -> ms.map { it.userId } }.distinct()
            val userMap = if (allMemberIds.isNotEmpty())
                orgDb.userDao().getByUserIds(allMemberIds).associateBy { it.userId }
            else emptyMap()

            val channelsArray = JSONArray()
            for (channel in allChannels) {
                val members = channelMembersMap[channel.channelCode] ?: emptyList()

                if (channel.channelType == "DM" && members.size == 2) {
                    val key = members.map { it.userId }.sorted().joinToString("|")
                    val bestCode = dmKeyToBestCode[key]?.first
                    if (bestCode != null && bestCode != channel.channelCode) continue
                }

                val offsetMap = offsetsByChannel[channel.channelCode] ?: emptyMap()
                val unreadCount = if (myUserId.isNotEmpty()) {
                    val myOffsetDate = offsetMap[myUserId] ?: 0L
                    chatDb.chatDao().countUnread(channel.channelCode, myOffsetDate, myUserId)
                } else 0

                val obj = JSONObject().apply {
                    put("channelCode", channel.channelCode)
                    put("channelName", channel.channelName)
                    put("channelType", channel.channelType)
                    put("lastChatDate", channel.lastChatDate)
                    put("lastChatContents", channel.lastChatContents)
                    put("lastChat", channel.lastChatContents)
                    put("unreadCount", unreadCount)
                    put("additional", ChannelUtils.enrichChannelAdditional(channel.additional, chatDb))
                }

                val memberArray = JSONArray()
                for (member in members) {
                    val memberObj = JSONObject().apply {
                        put("userId", member.userId)
                        put("offsetDate", offsetMap[member.userId] ?: 0L)
                    }
                    val user = userMap[member.userId]
                    if (user != null) {
                        val userInfo = JSONObject(user.userInfo)
                        memberObj.put("userName", userInfo.optString("userName", ""))
                        memberObj.put("userInfo", userInfo)
                    }
                    memberArray.put(memberObj)
                }
                obj.put("memberCount", members.size)
                obj.put("channelMemberList", memberArray)
                channelsArray.put(obj)
            }

            JSONObject().apply {
                put("errorCode", 0)
                put("channels", channelsArray)
            }.toString()
        }
    }

    suspend fun openChannelRoom(channelCode: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val chatDb = databaseProvider.getChatDatabase()
                val orgDb = databaseProvider.getOrgDatabase()

                val channel = chatDb.channelDao().getByChannelCode(channelCode)
                if (channel == null) {
                    Log.e(TAG, "openChannelRoom: channel not found: $channelCode")
                    return@withContext JSONObject().apply {
                        put("errorCode", -1)
                        put("errorMessage", "Channel not found")
                    }.toString()
                }

                val result = JSONObject().apply {
                    put("errorCode", 0)
                    put("channelCode", channel.channelCode)
                    put("channelName", channel.channelName)
                    put("channelType", channel.channelType)
                    put("additional", ChannelUtils.enrichChannelAdditional(channel.additional, chatDb))
                }

                val members = chatDb.channelMemberDao().getActiveMembersByChannel(channelCode)
                val memberUserMap = if (members.isNotEmpty())
                    orgDb.userDao().getByUserIds(members.map { it.userId }).associateBy { it.userId }
                else emptyMap()
                val memberArray = JSONArray()
                for (member in members) {
                    val memberObj = JSONObject().apply { put("userId", member.userId) }
                    val user = memberUserMap[member.userId]
                    if (user != null) {
                        val userInfo = JSONObject(user.userInfo)
                        memberObj.put("userName", userInfo.optString("userName", ""))
                        memberObj.put("userInfo", userInfo)
                    }
                    memberArray.put(memberObj)
                }
                result.put("memberCount", members.size)
                result.put("channelMemberList", memberArray)

                val recentChats = chatDb.chatDao().getRecent(channelCode, 50)
                val chatsArray = JSONArray()
                for (chat in recentChats.reversed()) {
                    chatsArray.put(JSONObject().apply {
                        put("channelCode", chat.channelCode)
                        put("chatCode", chat.chatCode)
                        put("sendUserId", chat.sendUserId ?: "")
                        put("contents", chat.contents)
                        put("sendDate", chat.sendDate)
                        put("chatType", chat.chatType)
                        if (chat.additional != null) put("additional", JSONObject(ChannelUtils.sanitizeAdditional(chat.additional)))
                    })
                }
                result.put("chats", chatsArray)
                result.put("hasMore", recentChats.size >= 50)

                Log.d(TAG, "openChannelRoom: $channelCode, members=${members.size}, chats=${recentChats.size}")
                result.toString()
            } catch (e: Exception) {
                Log.e(TAG, "openChannelRoom error: ${e.message}", e)
                JSONObject().apply {
                    put("errorCode", -1)
                    put("errorMessage", e.message ?: "Unknown error")
                }.toString()
            }
        }
    }

    suspend fun searchChannelRoom(keyword: String, type: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val chatDb = databaseProvider.getChatDatabase()
                val orgDb = databaseProvider.getOrgDatabase()

                Log.d(TAG, "searchChannelRoom: keyword=$keyword, type=$type")

                val result = JSONObject().apply { put("errorCode", 0) }
                val resultArray = JSONArray()

                when (type) {
                    "channel" -> {
                        val allChannels = chatDb.channelDao().getAll()
                        for (ch in allChannels) {
                            if (ch.channelName.contains(keyword, ignoreCase = true)) {
                                resultArray.put(JSONObject().apply {
                                    put("channelCode", ch.channelCode)
                                    put("channelName", ch.channelName)
                                    put("channelType", ch.channelType)
                                    put("lastChatDate", ch.lastChatDate)
                                    put("lastChatContents", ch.lastChatContents)
                                })
                            }
                        }
                    }
                    "chat" -> {
                        val allChannels = chatDb.channelDao().getAll()
                        for (ch in allChannels) {
                            val chats = chatDb.chatDao().getByChannel(ch.channelCode)
                            for (chat in chats) {
                                if (chat.contents.contains(keyword, ignoreCase = true)) {
                                    resultArray.put(JSONObject().apply {
                                        put("channelCode", chat.channelCode)
                                        put("channelName", ch.channelName)
                                        put("chatCode", chat.chatCode)
                                        put("sendUserId", chat.sendUserId ?: "")
                                        put("contents", chat.contents)
                                        put("sendDate", chat.sendDate)
                                    })
                                }
                            }
                        }
                    }
                    "member" -> {
                        val allChannels = chatDb.channelDao().getAll()
                        val chMembers = allChannels.map { ch ->
                            ch to chatDb.channelMemberDao().getActiveMembersByChannel(ch.channelCode)
                        }
                        val memberIds = chMembers.flatMap { (_, ms) -> ms.map { it.userId } }.distinct()
                        val memberUserMap = if (memberIds.isNotEmpty())
                            orgDb.userDao().getByUserIds(memberIds).associateBy { it.userId }
                        else emptyMap()
                        for ((ch, members) in chMembers) {
                            val matched = members.any { m ->
                                val user = memberUserMap[m.userId]
                                user != null && JSONObject(user.userInfo)
                                    .optString("userName", "").contains(keyword, ignoreCase = true)
                            }
                            if (matched) {
                                resultArray.put(JSONObject().apply {
                                    put("channelCode", ch.channelCode)
                                    put("channelName", ch.channelName)
                                    put("channelType", ch.channelType)
                                    put("lastChatDate", ch.lastChatDate)
                                    put("lastChatContents", ch.lastChatContents)
                                })
                            }
                        }
                    }
                    else -> {
                        val allChannels = chatDb.channelDao().getAll()
                        for (ch in allChannels) {
                            if (ch.channelName.contains(keyword, ignoreCase = true) ||
                                ch.lastChatContents.contains(keyword, ignoreCase = true)) {
                                resultArray.put(JSONObject().apply {
                                    put("channelCode", ch.channelCode)
                                    put("channelName", ch.channelName)
                                    put("channelType", ch.channelType)
                                    put("lastChatDate", ch.lastChatDate)
                                    put("lastChatContents", ch.lastChatContents)
                                })
                            }
                        }
                    }
                }

                result.put("data", resultArray)
                Log.d(TAG, "searchChannelRoom: found ${resultArray.length()} results")
                result.toString()
            } catch (e: Exception) {
                Log.e(TAG, "searchChannelRoom error: ${e.message}", e)
                JSONObject().apply {
                    put("errorCode", -1)
                    put("errorMessage", e.message ?: "Unknown error")
                }.toString()
            }
        }
    }
}
