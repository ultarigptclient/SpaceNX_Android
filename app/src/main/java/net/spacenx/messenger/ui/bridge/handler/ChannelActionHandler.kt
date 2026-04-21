package net.spacenx.messenger.ui.bridge.handler

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.spacenx.messenger.data.local.entity.ChannelEntity
import net.spacenx.messenger.data.local.entity.ChannelMemberEntity
import net.spacenx.messenger.data.local.entity.SyncMetaEntity
import net.spacenx.messenger.data.remote.api.ApiClient
import net.spacenx.messenger.data.repository.ChannelRepository
import net.spacenx.messenger.ui.bridge.BridgeContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 채널/채팅방 생성·멤버 관리·조회 브릿지 핸들러.
 * ChatHandler에서 분리: 메시지 송수신과 채널 구조 관리 책임 분리.
 */
class ChannelActionHandler(
    private val ctx: BridgeContext,
    private val channelRepo: ChannelRepository
) {
    companion object {
        private const val TAG = "ChannelActionHandler"
    }

    suspend fun handle(action: String, params: Map<String, Any?>) {
        when (action) {
            "createChatRoom" -> handleCreateChatRoom(params)
            "createGroupChatRoom" -> handleCreateGroupChatRoom(params)
            "openChannel" -> handleOpenChannel(params)
            "syncChannel", "getChannelList", "getChannelSummaries" -> handleGetChannelListAs(action)
            "addChannelMember" -> handleAddChannelMember(params)
            "removeChannelMember" -> handleRemoveChannelMember(params)
            "addChannelFavorite" -> handleAddChannelFavorite(params)
            "removeChannelFavorite" -> handleRemoveChannelFavorite(params)
            "createConference" -> handleCreateConference(params)
            "joinConference" -> handleJoinConference(params)
            "removeChannel" -> handleRemoveChannel(params)
            "findChannelByMembers" -> handleFindChannelByMembers(params)
            "getChannel" -> handleGetChannel(params)
            "deleteRoom" -> handleDeleteRoom(params)
            "openChatRoom" -> handleOpenChatRoom(params)
        }
    }

    private suspend fun handleCreateChatRoom(params: Map<String, Any?>) {
        try {
            val userId = ctx.paramStr(params, "userId")
            val myId = ctx.appConfig.getSavedUserId() ?: ""
            val result = withContext(Dispatchers.IO) {
                val body = JSONObject().put("users", JSONArray().put(myId).put(userId)).put("sendUserId", myId)
                ApiClient.postJson(ctx.appConfig.getEndpointByPath("/comm/makechannel"), body)
            }
            val channelCode = result.optString("channelCode", "")
            if (channelCode.isNotEmpty()) {
                withContext(Dispatchers.IO) { ctx.saveChannelLocally(channelCode, listOf(myId, userId)) }
            }
            ctx.resolveToJs("createChatRoom", result)
        } catch (e: Exception) {
            ctx.rejectToJs("createChatRoom", e.message)
        }
    }

    private suspend fun handleCreateGroupChatRoom(params: Map<String, Any?>) {
        try {
            val userIds = ctx.paramList(params, "userIds")
            val channelName = listOf("channelName", "channelTitle", "title", "name", "roomName")
                .map { ctx.paramStr(params, it) }
                .firstOrNull { it.isNotEmpty() } ?: ""
            Log.d(TAG, "createGroupChatRoom: paramKeys=${params.keys}, resolvedChannelName='$channelName'")
            val myId = ctx.appConfig.getSavedUserId() ?: ""
            val result = withContext(Dispatchers.IO) {
                val usersArr = JSONArray().apply { userIds.forEach { put(it) } }
                val body = JSONObject()
                    .put("users", usersArr)
                    .put("sendUserId", myId)
                    .put("channelType", "GROUP")
                if (channelName.isNotEmpty()) body.put("channelName", channelName)
                ApiClient.postJson(ctx.appConfig.getEndpointByPath("/comm/makechannel"), body)
            }
            val channelCode = result.optString("channelCode", "")
            Log.d(TAG, "createGroupChatRoom: server response channelCode=$channelCode, serverChannelName='${result.optString("channelName", "")}'")
            if (channelCode.isNotEmpty()) {
                val resolvedName = result.optString("channelName", "").ifEmpty { channelName }
                Log.d(TAG, "createGroupChatRoom: saving locally channelCode=$channelCode, channelName='$resolvedName'")
                withContext(Dispatchers.IO) { ctx.saveChannelLocally(channelCode, userIds, "GROUP", resolvedName) }
            }
            ctx.resolveToJs("createGroupChatRoom", result)
        } catch (e: Exception) {
            ctx.rejectToJs("createGroupChatRoom", e.message)
        }
    }

    private suspend fun handleOpenChannel(params: Map<String, Any?>) {
        try {
            val members = ctx.paramList(params, "members")
            if (members.size < 2) {
                ctx.rejectToJs("openChannel", "members required")
                return
            }
            val sorted = members.sorted()
            val existingCode = withContext(Dispatchers.IO) {
                ctx.dbProvider.getChatDatabase().channelMemberDao().findDMChannel(sorted[0], sorted[1])
            }
            if (existingCode != null) {
                val ch: ChannelEntity? = withContext(Dispatchers.IO) {
                    ctx.dbProvider.getChatDatabase().channelDao().getByChannelCode(existingCode)
                }
                ctx.resolveToJs("openChannel", JSONObject()
                    .put("channelCode", existingCode)
                    .put("channelName", ch?.channelName ?: ""))
            } else {
                ctx.resolveToJs("openChannel", JSONObject()
                    .put("channelCode", "")
                    .put("channelName", "")
                    .put("members", JSONArray().apply { members.forEach { put(it) } })
                    .put("pending", true))
            }
        } catch (e: Exception) {
            ctx.rejectToJs("openChannel", e.message)
        }
    }

    suspend fun handleGetChannelListAs(action: String) {
        if (ctx.guardDbNotReady(action)) return
        ctx.activeChannelCode = null
        try {
            val channels = withContext(Dispatchers.IO) { getEnrichedChannelList() }
            Log.d(TAG, "$action: enriched ${channels.length()} channels")
            ctx.resolveToJs(action, JSONObject().put("errorCode", 0).put("channels", channels))
        } catch (e: Exception) {
            Log.e(TAG, "$action error: ${e.message}", e)
            ctx.rejectToJs(action, e.message)
        }
    }

    private suspend fun getEnrichedChannelList(): JSONArray {
        val jsonStr = channelRepo.getChannelListAsJson()
        val root = JSONObject(jsonStr)
        val channels = root.optJSONArray("channels") ?: return JSONArray()
        for (i in 0 until channels.length()) {
            val ch = channels.getJSONObject(i)
            val channelCode = ch.optString("channelCode", "")
            ch.put("muteFlag", ctx.appConfig.isChannelMuted(channelCode))
            val members = ch.optJSONArray("channelMemberList") ?: continue
            for (j in 0 until members.length()) {
                val m = members.getJSONObject(j)
                val userId = m.optString("userId", "")
                m.put("userName", ctx.userNameCache.resolve(userId))
            }
        }
        return channels
    }

    private suspend fun handleAddChannelMember(params: Map<String, Any?>) {
        try {
            val body = ctx.paramsToJson(params)
            val result = withContext(Dispatchers.IO) {
                ApiClient.postJson(ctx.appConfig.getEndpointByPath("/comm/addchannelmember"), body)
            }
            val channelCode = ctx.paramStr(params, "channelCode")
            val users = params["users"]
            if (channelCode.isNotEmpty() && users != null) {
                val userList = when (users) {
                    is List<*> -> users.filterIsInstance<Map<*, *>>()
                    is JSONArray -> (0 until users.length()).map { i ->
                        val obj = users.optJSONObject(i)
                        if (obj != null) mapOf("userId" to obj.optString("userId", "")) else null
                    }.filterNotNull()
                    else -> emptyList()
                }
                if (userList.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    withContext(Dispatchers.IO) {
                        val memberDao = ctx.dbProvider.getChatDatabase().channelMemberDao()
                        for (u in userList) {
                            val userId = u["userId"]?.toString() ?: continue
                            if (userId.isNotEmpty()) {
                                memberDao?.insert(ChannelMemberEntity(
                                    channelCode = channelCode, userId = userId, registDate = now
                                ))
                            }
                        }
                    }
                }
            }
            ctx.resolveToJs("addChannelMember", result)
        } catch (e: Exception) {
            ctx.rejectToJs("addChannelMember", e.message)
        }
    }

    private suspend fun handleRemoveChannelMember(params: Map<String, Any?>) {
        try {
            val channelCode = ctx.paramStr(params, "channelCode")
            val userId = ctx.paramStr(params, "userId").ifEmpty { ctx.appConfig.getSavedUserId() ?: "" }
            Log.d(TAG, "removeChannelMember: channelCode=$channelCode, userId=$userId")
            val body = JSONObject()
                .put("channelCode", channelCode)
                .put("userId", userId)
                .put("sendUserId", userId)
            val result = withContext(Dispatchers.IO) {
                ApiClient.postJson(ctx.appConfig.getEndpointByPath("/comm/removechannelmember"), body)
            }
            Log.d(TAG, "removeChannelMember: server result=$result")
            val isSuccess = result.optInt("errorCode", -1) == 0
                && result.optInt("status", 200) < 400
            if (!isSuccess) {
                Log.w(TAG, "removeChannelMember: server failed, skip local delete. errorCode=${result.optInt("errorCode", -1)}, status=${result.optInt("status", 200)}")
                ctx.resolveToJs("removeChannelMember", result)
                return
            }
            val myUserId = ctx.appConfig.getSavedUserId() ?: ""
            if (channelCode.isNotEmpty() && userId == myUserId) {
                withContext(Dispatchers.IO) {
                    val chatDb = ctx.dbProvider.getChatDatabase()
                    val beforeCount = chatDb.channelDao().getAll().size
                    chatDb.channelMemberDao().deleteByChannel(channelCode)
                    chatDb.chatDao().deleteByChannel(channelCode)
                    chatDb.channelDao().deleteByChannelCode(channelCode)
                    val afterCount = chatDb.channelDao().getAll().size
                    Log.d(TAG, "removeChannelMember: self-leave, local delete done channelCode=$channelCode, channels $beforeCount → $afterCount")
                }
            } else if (channelCode.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    val chatDb = ctx.dbProvider.getChatDatabase()
                    chatDb.channelMemberDao().deleteMember(channelCode, userId)
                    Log.d(TAG, "removeChannelMember: kicked userId=$userId from channelCode=$channelCode")
                }
            } else {
                Log.w(TAG, "removeChannelMember: channelCode is EMPTY, skip local delete")
            }
            withContext(Dispatchers.IO) { ctx.updateCrudOffset("removechannelmember", result) }
            ctx.resolveToJs("removeChannelMember", result)
        } catch (e: Exception) {
            Log.e(TAG, "removeChannelMember error: ${e.message}", e)
            ctx.rejectToJs("removeChannelMember", e.message)
        }
    }

    private suspend fun handleAddChannelFavorite(params: Map<String, Any?>) {
        try {
            val userId = ctx.appConfig.getSavedUserId() ?: ""
            val result = withContext(Dispatchers.IO) {
                val token = ctx.loginViewModel.sessionManager.jwtToken
                val url = ctx.appConfig.getEndpointByPath("/buddy/buddyadd")
                val res = ApiClient.postJson(url, JSONObject().apply {
                    put("userId", userId)
                    put("buddyId", ctx.paramStr(params, "channelCode"))
                    put("buddyParent", "0")
                    put("buddyName", ctx.paramStr(params, "channelName").ifEmpty { ctx.paramStr(params, "channelCode") })
                    put("buddyType", "C")
                }, token)
                if (res.optInt("errorCode", -1) == 0) {
                    val orgDb = ctx.dbProvider.getOrgDatabase()
                    orgDb.syncMetaDao().insertSync(SyncMetaEntity("buddyLastSyncTime", 0L))
                    orgDb.syncMetaDao().insertSync(SyncMetaEntity("buddyEventOffset", 0L))
                    Log.d(TAG, "addChannelFavorite: buddy sync meta reset for full resync")
                }
                res
            }
            ctx.resolveToJs("addChannelFavorite", result)
        } catch (e: Exception) {
            ctx.rejectToJs("addChannelFavorite", e.message)
        }
    }

    private suspend fun handleRemoveChannelFavorite(params: Map<String, Any?>) {
        try {
            val userId = ctx.appConfig.getSavedUserId() ?: ""
            val result = withContext(Dispatchers.IO) {
                val token = ctx.loginViewModel.sessionManager.jwtToken
                val url = ctx.appConfig.getEndpointByPath("/buddy/buddydel")
                val res = ApiClient.postJson(url, JSONObject().apply {
                    put("userId", userId)
                    put("buddyId", ctx.paramStr(params, "channelCode"))
                    put("buddyParent", "0")
                }, token)
                if (res.optInt("errorCode", -1) == 0) {
                    val orgDb = ctx.dbProvider.getOrgDatabase()
                    orgDb.syncMetaDao().insertSync(SyncMetaEntity("buddyLastSyncTime", 0L))
                    orgDb.syncMetaDao().insertSync(SyncMetaEntity("buddyEventOffset", 0L))
                    Log.d(TAG, "removeChannelFavorite: buddy sync meta reset for full resync")
                }
                res
            }
            ctx.resolveToJs("removeChannelFavorite", result)
        } catch (e: Exception) {
            ctx.rejectToJs("removeChannelFavorite", e.message)
        }
    }

    private suspend fun handleCreateConference(params: Map<String, Any?>) {
        try {
            val userId = ctx.appConfig.getSavedUserId() ?: ""
            val channelName = ctx.paramStr(params, "channelName")
            val password = ctx.paramStr(params, "password")
            val expireDays = ctx.paramInt(params, "expireDays", 7)
            val expireDate = System.currentTimeMillis() + expireDays * 24 * 60 * 60 * 1000L

            val result = withContext(Dispatchers.IO) {
                val token = ctx.loginViewModel.sessionManager.jwtToken
                val url = ctx.appConfig.getEndpointByPath("/comm/makechannel")
                ApiClient.postJson(url, JSONObject().apply {
                    put("channelName", channelName)
                    put("sendUserId", userId)
                    put("users", JSONArray().put(userId))
                    put("channelType", "CONFERENCE")
                    put("additional", JSONObject().apply {
                        put("password", password)
                        put("expireDate", expireDate)
                        put("hostUserId", userId)
                    })
                }, token)
            }
            val createdCode = result.optString("channelCode", "")
            if (result.optInt("errorCode", -1) == 0 && createdCode.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    val chatDb = ctx.dbProvider.getChatDatabase()
                    chatDb.channelDao().insert(ChannelEntity(
                        channelCode = createdCode,
                        channelType = "CONFERENCE",
                        channelName = channelName,
                        lastChatDate = now
                    ))
                    chatDb.channelMemberDao().insert(ChannelMemberEntity(
                        channelCode = createdCode,
                        userId = userId,
                        registDate = now
                    ))
                }
            }
            ctx.resolveToJs("createConference", result)
        } catch (e: Exception) {
            ctx.rejectToJs("createConference", e.message)
        }
    }

    private suspend fun handleJoinConference(params: Map<String, Any?>) {
        try {
            val channelCode = ctx.paramStr(params, "channelCode")
            val password = ctx.paramStr(params, "password")
            val userId = ctx.appConfig.getSavedUserId() ?: ""
            val body = JSONObject().apply {
                put("channelCode", channelCode)
                put("password", password)
                put("userId", userId)
            }
            val result = withContext(Dispatchers.IO) {
                ApiClient.postJson(ctx.appConfig.getEndpointByPath("/comm/joinconference"), body)
            }
            if (result.optInt("errorCode", -1) == 0 && channelCode.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    val chatDb = ctx.dbProvider.getChatDatabase()
                    chatDb.channelDao().insert(ChannelEntity(
                        channelCode = channelCode,
                        channelType = "CONFERENCE",
                        channelName = result.optString("channelName", ""),
                        lastChatDate = now
                    ))
                    chatDb.channelMemberDao().insert(ChannelMemberEntity(
                        channelCode = channelCode,
                        userId = userId,
                        registDate = now
                    ))
                }
            }
            withContext(Dispatchers.IO) { ctx.updateCrudOffset("joinconference", result) }
            ctx.resolveToJs("joinConference", result)
        } catch (e: Exception) {
            ctx.rejectToJs("joinConference", e.message)
        }
    }

    private suspend fun handleRemoveChannel(params: Map<String, Any?>) {
        try {
            val channelCodeList = ctx.paramList(params, "channelCodeList")
            val userId = ctx.paramStr(params, "userId").ifEmpty { ctx.appConfig.getSavedUserId() ?: "" }
            val body = JSONObject().put("channelCodeList", JSONArray(channelCodeList)).put("userId", userId)
            val result = withContext(Dispatchers.IO) {
                ApiClient.postJson(ctx.appConfig.getEndpointByPath("/comm/removechannel"), body)
            }
            if (result.optInt("errorCode", -1) == 0 && channelCodeList.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    val chatDb = ctx.dbProvider.getChatDatabase()
                    for (code in channelCodeList) {
                        chatDb.channelMemberDao()?.deleteByChannel(code)
                        chatDb.chatDao().deleteByChannel(code)
                        chatDb.channelDao().deleteByChannelCode(code)
                        Log.d(TAG, "removeChannel: local delete done for channelCode=$code")
                    }
                }
                withContext(Dispatchers.IO) { ctx.updateCrudOffset("removechannel", result) }
            }
            ctx.resolveToJs("removeChannel", result)
        } catch (e: Exception) {
            ctx.rejectToJs("removeChannel", e.message)
        }
    }

    private suspend fun handleFindChannelByMembers(params: Map<String, Any?>) {
        try {
            val memberIds = ctx.paramList(params, "memberIds")
            if (memberIds.isEmpty()) {
                ctx.resolveToJs("findChannelByMembers", JSONObject().put("channelCode", ""))
                return
            }
            val sorted = memberIds.sorted()
            val channelCode = withContext(Dispatchers.IO) {
                val chatDb = ctx.dbProvider.getChatDatabase()
                val allChannels = chatDb.channelDao().getAll()
                for (ch in allChannels) {
                    val activeMembers = chatDb.channelMemberDao()
                        .getActiveMembersByChannel(ch.channelCode)
                        .map { it.userId }
                        .sorted()
                    if (activeMembers == sorted) return@withContext ch.channelCode
                }
                null
            }
            ctx.resolveToJs("findChannelByMembers", JSONObject().put("channelCode", channelCode ?: ""))
        } catch (e: Exception) {
            ctx.rejectToJs("findChannelByMembers", e.message)
        }
    }

    private suspend fun handleDeleteRoom(params: Map<String, Any?>) {
        try {
            val channelCode = ctx.paramStr(params, "channelCode")
            val myId = ctx.appConfig.getSavedUserId() ?: ""
            val result = withContext(Dispatchers.IO) {
                ApiClient.postJson(
                    ctx.appConfig.getEndpointByPath("/comm/destroychannel"),
                    JSONObject().put("channelCode", channelCode).put("userId", myId)
                )
            }
            if (result.optInt("errorCode", -1) == 0 && channelCode.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    val chatDb = ctx.dbProvider.getChatDatabase()
                    chatDb.channelMemberDao()?.deleteByChannel(channelCode)
                    chatDb.chatDao().deleteByChannel(channelCode)
                    chatDb.channelDao().deleteByChannelCode(channelCode)
                    Log.d(TAG, "deleteRoom: local data purged for channelCode=$channelCode")
                }
            }
            ctx.resolveToJs("deleteRoom", result)
        } catch (e: Exception) {
            ctx.rejectToJs("deleteRoom", e.message)
        }
    }

    private suspend fun handleOpenChatRoom(params: Map<String, Any?>) {
        try {
            val userId = ctx.paramStr(params, "userId")
            val myId = ctx.appConfig.getSavedUserId() ?: ""
            val result = withContext(Dispatchers.IO) {
                val body = JSONObject().put("users", JSONArray().put(myId).put(userId)).put("sendUserId", myId)
                ApiClient.postJson(ctx.appConfig.getEndpointByPath("/comm/makechannel"), body)
            }
            val channelCode = result.optString("channelCode", "")
            if (channelCode.isNotEmpty()) {
                withContext(Dispatchers.IO) { ctx.saveChannelLocally(channelCode, listOf(myId, userId)) }
            }
            ctx.resolveToJs("openChatRoom", result)
        } catch (e: Exception) {
            ctx.rejectToJs("openChatRoom", e.message)
        }
    }

    private suspend fun handleGetChannel(params: Map<String, Any?>) {
        if (ctx.guardDbNotReady("getChannel")) return
        try {
            val channelCode = ctx.paramStr(params, "channelCode")
            val result = withContext(Dispatchers.IO) {
                val chatDb = ctx.dbProvider.getChatDatabase()
                val ch = chatDb.channelDao().getByChannelCode(channelCode)
                if (ch != null) {
                    val members = chatDb.channelMemberDao().getActiveMembersByChannel(channelCode)
                    val offsetMap = chatDb.channelOffsetDao().getByChannel(channelCode)
                        .associate { it.userId to it.offsetDate }
                    val memberArr = JSONArray()
                    for (m in members) {
                        memberArr.put(JSONObject().apply {
                            put("userId", m.userId)
                            put("userName", ctx.userNameCache.resolve(m.userId))
                            put("registDate", m.registDate)
                            put("offsetDate", offsetMap[m.userId] ?: 0L)
                        })
                    }
                    JSONObject().apply {
                        put("errorCode", 0)
                        put("channelCode", ch.channelCode)
                        put("channelName", ch.channelName)
                        put("channelType", ch.channelType)
                        put("additional", ch.additional)
                        put("channelMemberList", memberArr)
                        put("memberCount", members.size)
                        put("muteFlag", ctx.appConfig.isChannelMuted(channelCode))
                    }
                } else {
                    val body = JSONObject().put("channelCode", channelCode)
                    ApiClient.postJson(ctx.appConfig.getEndpointByPath("/comm/getchannelinfo"), body)
                }
            }
            ctx.resolveToJs("getChannel", result)
        } catch (e: Exception) {
            ctx.rejectToJs("getChannel", e.message)
        }
    }
}
