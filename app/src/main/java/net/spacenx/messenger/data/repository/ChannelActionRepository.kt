package net.spacenx.messenger.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.common.AppConfig.Companion.EP_COMM_MAKE_CHANNEL
import net.spacenx.messenger.common.AppConfig.Companion.EP_COMM_SEND_CHAT
import net.spacenx.messenger.data.local.DatabaseProvider
import net.spacenx.messenger.data.local.entity.ChannelEntity
import net.spacenx.messenger.data.local.entity.ChannelMemberEntity
import net.spacenx.messenger.data.local.entity.ChatEntity
import net.spacenx.messenger.data.local.entity.SyncMetaEntity
import net.spacenx.messenger.data.remote.api.ApiClient
import net.spacenx.messenger.data.remote.api.dto.MakeChannelRequestDTO
import net.spacenx.messenger.data.remote.api.dto.SendChatRequestDTO
import org.json.JSONObject

/**
 * 채널·채팅 생성·전송 (REST 호출 + 로컬 DB 반영)
 * makeChannel, makeChannelWithUserName, sendChat
 */
class ChannelActionRepository(
    private val databaseProvider: DatabaseProvider,
    private val appConfig: AppConfig,
    private val sessionManager: SocketSessionManager,
    private val queryRepository: ChannelQueryRepository
) {
    companion object {
        private const val TAG = "ChannelActionRepository"
    }

    suspend fun makeChannelWithUserName(channelCode: String, sendUserId: String, targetUserId: String): String {
        return withContext(Dispatchers.IO) {
            val orgDb = databaseProvider.getOrgDatabase()
            val targetUser = orgDb.userDao().getByUserId(targetUserId)
            val channelName = if (targetUser != null) {
                JSONObject(targetUser.userInfo).optString("userName", targetUserId)
            } else {
                targetUserId
            }
            makeChannel(channelCode, channelName, sendUserId, listOf(sendUserId, targetUserId))
        }
    }

    suspend fun makeChannel(channelCode: String, channelName: String, sendUserId: String, users: List<String>): String {
        return withContext(Dispatchers.IO) {
            try {
                val token = ChannelUtils.awaitToken(sessionManager)
                val channelApi = ApiClient.createChannelApiFromBaseUrl(appConfig.getRestBaseUrl(), token)
                val endpoint = appConfig.getEndpoint(EP_COMM_MAKE_CHANNEL, "api/comm/makechannel")
                val request = MakeChannelRequestDTO(
                    channelCode = channelCode,
                    channelName = channelName,
                    sendUserId = sendUserId,
                    users = users
                )
                Log.d(TAG, "makeChannel: channelCode=$channelCode, channelName=$channelName, users=$users")
                val response = channelApi.makeChannel(endpoint, request)

                if (!response.isSuccessful) {
                    Log.e(TAG, "makeChannel HTTP error: ${response.code()}")
                    return@withContext JSONObject().apply {
                        put("errorCode", -1)
                        put("errorMessage", "HTTP ${response.code()}")
                    }.toString()
                }

                val rawJson = response.body()?.string() ?: "{}"
                Log.d(TAG, "makeChannel response: $rawJson")

                val resJson = JSONObject(rawJson)
                if (resJson.optInt("errorCode", -1) == 0) {
                    val chatDb = databaseProvider.getChatDatabase()
                    chatDb.runInTransaction {
                        val state = if (users.size > 2) 1 else 0
                        chatDb.channelDao().insertAllSync(listOf(
                            ChannelEntity(channelCode = channelCode, channelName = channelName, channelType = "NORMAL", state = state)
                        ))
                        chatDb.channelMemberDao().insertAllSync(users.map { userId ->
                            ChannelMemberEntity(channelCode = channelCode, userId = userId)
                        })
                        val eventId = resJson.optLong("eventId", 0L)
                        if (eventId > 0L) {
                            chatDb.syncMetaDao().insertSync(SyncMetaEntity(ChannelSyncRepository.SYNC_META_KEY, eventId))
                        }
                    }
                    Log.d(TAG, "makeChannel: saved to DB, members=${users.size}")
                    return@withContext queryRepository.openChannelRoom(channelCode)
                }

                rawJson
            } catch (e: Exception) {
                Log.e(TAG, "makeChannel error: ${e.message}", e)
                JSONObject().apply {
                    put("errorCode", -1)
                    put("errorMessage", e.message ?: "Unknown error")
                }.toString()
            }
        }
    }

    suspend fun sendChat(channelCode: String, chatCode: String, sendUserId: String, contents: String, additional: String?): String {
        return withContext(Dispatchers.IO) {
            try {
                val token = ChannelUtils.awaitToken(sessionManager)
                val channelApi = ApiClient.createChannelApiFromBaseUrl(appConfig.getRestBaseUrl(), token)
                val endpoint = appConfig.getEndpoint(EP_COMM_SEND_CHAT, "api/comm/sendchat")
                val additionalJson = if (additional != null) {
                    kotlinx.serialization.json.Json.parseToJsonElement(additional) as? kotlinx.serialization.json.JsonObject
                } else null
                val request = SendChatRequestDTO(
                    channelCode = channelCode,
                    chatCode = chatCode,
                    sendUserId = sendUserId,
                    contents = contents,
                    additional = additionalJson
                )
                Log.d(TAG, "sendChat: channelCode=$channelCode, chatCode=$chatCode")
                val response = channelApi.sendChat(endpoint, request)

                if (!response.isSuccessful) {
                    Log.e(TAG, "sendChat HTTP error: ${response.code()}")
                    return@withContext JSONObject().apply {
                        put("errorCode", -1)
                        put("errorMessage", "HTTP ${response.code()}")
                    }.toString()
                }

                val rawJson = response.body()?.string() ?: "{}"
                Log.d(TAG, "sendChat response: $rawJson")

                val resJson = JSONObject(rawJson)
                if (resJson.optInt("errorCode", -1) == 0) {
                    val chatDb = databaseProvider.getChatDatabase()
                    val sendDate = resJson.optLong("dateTime", System.currentTimeMillis())
                    chatDb.chatDao().insertAllSync(listOf(
                        ChatEntity(
                            channelCode = channelCode,
                            chatCode = resJson.optString("chatCode", chatCode),
                            sendUserId = sendUserId,
                            contents = contents,
                            sendDate = sendDate,
                            chatType = 0,
                            additional = additional
                        )
                    ))
                    chatDb.channelDao().updateLastChatSync(channelCode, sendDate, contents, lastSendUserId = sendUserId)
                    val eventId = resJson.optLong("eventId", 0L)
                    if (eventId > 0L) {
                        chatDb.syncMetaDao().insertSync(SyncMetaEntity(ChannelSyncRepository.CHAT_SYNC_META_KEY, eventId))
                    }
                    Log.d(TAG, "sendChat: saved to DB")
                }

                rawJson
            } catch (e: Exception) {
                Log.e(TAG, "sendChat error: ${e.message}", e)
                JSONObject().apply {
                    put("errorCode", -1)
                    put("errorMessage", e.message ?: "Unknown error")
                }.toString()
            }
        }
    }
}
