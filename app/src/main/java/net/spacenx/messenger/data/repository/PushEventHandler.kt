package net.spacenx.messenger.data.repository

import android.util.Log
import net.spacenx.messenger.common.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.spacenx.messenger.data.local.DatabaseProvider
import net.spacenx.messenger.data.local.entity.ChannelEntity
import net.spacenx.messenger.data.local.entity.ChannelMemberEntity
import net.spacenx.messenger.data.local.entity.ChannelOffsetEntity
import net.spacenx.messenger.data.local.entity.ChatEntity
import net.spacenx.messenger.data.local.entity.CalEventEntity
import net.spacenx.messenger.data.local.entity.ChatThreadEntity
import net.spacenx.messenger.data.local.entity.DeptEntity
import net.spacenx.messenger.data.local.entity.UserEntity
import net.spacenx.messenger.data.remote.socket.codec.ProtocolCommand
import org.json.JSONArray
import org.json.JSONObject

/**
 * 바이너리 소켓 Push 이벤트 → 로컬 DB 반영
 *
 * SocketSessionManager 에서 push frame을 수신하면
 * 이 핸들러를 호출하여 commandCode에 따라 적절한 DB 작업을 수행한다.
 */
class PushEventHandler(
    private val dbProvider: DatabaseProvider,
    private val channelRepository: ChannelRepository,
    private val messageRepository: MessageRepository,
    private val notiRepository: NotiRepository,
    private val appConfig: AppConfig
) {
    companion object {
        private const val TAG = "PushEventHandler"
    }

    /**
     * Push 이벤트를 commandCode에 따라 로컬 DB에 반영한다.
     *
     * @param commandCode ProtocolCommand code (예: 0x0280)
     * @param data push 이벤트 JSON payload
     */
    suspend fun applyToLocalDb(commandCode: Int, data: JSONObject) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "applyToLocalDb: commandCode=0x${Integer.toHexString(commandCode)}, data=$data")

                when (commandCode) {
                    ProtocolCommand.SEND_CHAT_EVENT.code -> handleSendChatEvent(data)
                    ProtocolCommand.READ_CHAT_EVENT.code -> handleReadChatEvent(data)
                    ProtocolCommand.DELETE_CHAT_EVENT.code -> handleDeleteChatEvent(data)
                    ProtocolCommand.MOD_CHAT_EVENT.code -> handleModChatEvent(data)
                    ProtocolCommand.REACTION_CHAT_EVENT.code -> handleReactionChatEvent(data)
                    ProtocolCommand.VOTE_EVENT.code,
                    ProtocolCommand.CLOSE_VOTE_EVENT.code,
                    ProtocolCommand.PIN_EVENT.code,
                    ProtocolCommand.UNPIN_EVENT.code -> handleChatMetaEvent(commandCode, data)
                    ProtocolCommand.MOD_ISSUE_EVENT.code,
                    ProtocolCommand.MOD_PROJECT_EVENT.code,
                    ProtocolCommand.MOD_THREAD_EVENT.code,
                    ProtocolCommand.MOD_CAL_EVENT.code,
                    ProtocolCommand.MOD_TODO_EVENT.code,
                    ProtocolCommand.CREATE_CHAT_THREAD_EVENT.code -> handleProjectEvent(commandCode, data)
                    ProtocolCommand.MAKE_CHANNEL_EVENT.code -> handleMakeChannelEvent(data)
                    ProtocolCommand.ADD_CHANNEL_MEMBER_EVENT.code -> handleAddChannelMemberEvent(data)
                    ProtocolCommand.DESTROY_CHANNEL_EVENT.code -> handleDestroyChannelEvent(data)
                    ProtocolCommand.REMOVE_CHANNEL_MEMBER_EVENT.code -> handleRemoveChannelMemberEvent(data)
                    ProtocolCommand.SET_CHANNEL_EVENT.code -> handleSetChannelEvent(data)
                    ProtocolCommand.MOD_CHANNEL_EVENT.code,
                    ProtocolCommand.REMOVE_CHANNEL_EVENT.code -> handleSetChannelEvent(data)
                    ProtocolCommand.KICK_USER_EVENT.code -> handleRemoveChannelMemberEvent(data)
                    ProtocolCommand.DELETE_CHAT_THREAD_EVENT.code,
                    ProtocolCommand.ADD_COMMENT_EVENT.code,
                    ProtocolCommand.DELETE_COMMENT_EVENT.code -> handleProjectEvent(commandCode, data)
                    ProtocolCommand.NICK_EVENT.code,
                    ProtocolCommand.MOBILE_ICN_EVENT.code -> handleChatMetaEvent(commandCode, data)
                    ProtocolCommand.SEND_MESSAGE_EVENT.code -> handleSendMessageEvent(data)
                    ProtocolCommand.READ_MESSAGE_EVENT.code -> handleReadMessageEvent(data)
                    ProtocolCommand.DELETE_MESSAGE_EVENT.code -> handleDeleteMessageEvent(data)
                    ProtocolCommand.RETRIEVE_MESSAGE_EVENT.code -> handleRetrieveMessageEvent(data)
                    ProtocolCommand.NOTIFY_EVENT.code,
                    ProtocolCommand.NOTIFICATION_EVENT.code -> handleNotifyEvent(data)
                    ProtocolCommand.ORG_USER_EVENT.code,
                    ProtocolCommand.ORG_DEPT_EVENT.code,
                    ProtocolCommand.ORG_USER_REMOVED_EVENT.code,
                    ProtocolCommand.ORG_DEPT_REMOVED_EVENT.code -> handleOrgEvent(commandCode, data)
                    ProtocolCommand.TYPING_CHAT_EVENT.code -> { /* typing indicator — ephemeral, no DB action */ }
                    else -> Log.w(TAG, "Unknown push commandCode: 0x${Integer.toHexString(commandCode)}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "applyToLocalDb error: commandCode=0x${Integer.toHexString(commandCode)}, ${e.message}", e)
            }
        }
    }

    // ── 0x0280 SendChatEvent: 채팅 수신 → upsert chat + channel lastChat 갱신 ──

    private suspend fun handleSendChatEvent(data: JSONObject) {
        val chatDb = dbProvider.getChatDatabase()
        val channelCode = data.optString("channelCode", "")
        val chatCode = data.optString("chatCode", "")
        val contents = data.optString("contents", "")
        val sendDate = data.optLong("sendDate", 0L)
        val additional = data.optJSONObject("additional")?.toString()

        // chatType 매핑:
        //  - 문자열: "AI"/"ai" → -99, "SYSTEM"/"system" → 99
        //  - 숫자 6 → 99 (서버가 SYSTEM을 6으로 내려주는 경우 방어)
        //  - 그 외 숫자는 그대로 유지
        val rawChatType = data.opt("chatType")
        val isAiChat = rawChatType == "AI" || rawChatType == "ai"
        val isSystemChat = rawChatType == "SYSTEM" || rawChatType == "system" ||
            (rawChatType is Number && rawChatType.toInt() == 6)
        val chatType = when {
            isAiChat -> -99
            isSystemChat -> 99
            rawChatType is Number -> rawChatType.toInt()
            else -> data.optInt("chatType", 0)
        }
        val sendUserId = if (isAiChat) "AI" else data.optString("sendUserId", "")

        // 채널이 없는 상태에서 SYSTEM 메시지(초대/퇴장 등)만 먼저 도착한 경우,
        // 방 생성은 MakeChannelEvent/AddChannelMemberEvent 가 담당하므로 여기서는 skip.
        // (그 이벤트들이 도착하면 채널·멤버가 정상 생성되고, 해당 시점의 syncChat 로 SYSTEM 메시지가 함께 들어옴)
        val existingChannel = chatDb.channelDao().getByChannelCode(channelCode)
        if (existingChannel == null && isSystemChat) {
            Log.w(TAG, "handleSendChatEvent: channel $channelCode missing and chatType=SYSTEM — skip (let MakeChannel/AddMember event create channel)")
            return
        }

        val chatEntity = ChatEntity(
            channelCode = channelCode,
            chatCode = chatCode,
            sendUserId = sendUserId,
            contents = contents,
            sendDate = sendDate,
            chatType = chatType,
            additional = additional
        )

        chatDb.chatDao().insert(chatEntity)

        // 로컬에 채널이 없으면 syncChannel로 채널 정보 가져오기 (일반 메시지 수신 fallback)
        val channel = existingChannel
        if (channel == null) {
            Log.w(TAG, "handleSendChatEvent: channel $channelCode not found locally, triggering syncChannel")
            val userId = appConfig.getSavedUserId() ?: ""
            try {
                channelRepository.syncChannel(userId)
            } catch (e: Exception) {
                Log.e(TAG, "handleSendChatEvent: syncChannel failed: ${e.message}")
            }
            // incremental sync 후에도 없으면 full resync (offset=0)
            var synced = chatDb.channelDao().getByChannelCode(channelCode)
            if (synced == null && userId.isNotEmpty()) {
                Log.w(TAG, "handleSendChatEvent: channel $channelCode still missing after sync, forcing full resync")
                try {
                    channelRepository.syncChannelFull(userId)
                } catch (e: Exception) {
                    Log.e(TAG, "handleSendChatEvent: full resync failed: ${e.message}")
                }
                synced = chatDb.channelDao().getByChannelCode(channelCode)
            }
            if (synced != null && sendDate >= synced.lastChatDate) {
                chatDb.channelDao().insert(
                    synced.copy(lastChatDate = sendDate, lastChatContents = contents, lastSendUserId = sendUserId)
                )
            }
        } else {
            chatDb.channelDao().updateLastChatDate(channelCode, sendDate)
            if (sendDate >= channel.lastChatDate) {
                chatDb.channelDao().insert(
                    channel.copy(lastChatDate = sendDate, lastChatContents = contents, lastSendUserId = sendUserId)
                )
            }
        }

        // SendChatEvent: 발신자는 읽은 것과 동일 — offset 저장 (AI 메시지 제외)
        if (!isAiChat && sendUserId.isNotEmpty() && sendDate > 0) {
            chatDb.channelOffsetDao().upsert(
                ChannelOffsetEntity(channelCode = channelCode, userId = sendUserId, offsetDate = sendDate)
            )
        }

        Log.d(TAG, "handleSendChatEvent: channelCode=$channelCode, chatCode=$chatCode")
    }

    // ── 0x0281 ReadChatEvent: 읽음 offset 갱신 ──

    private suspend fun handleReadChatEvent(data: JSONObject) {
        val chatDb = dbProvider.getChatDatabase()
        val channelCode = data.optString("channelCode", "")
        val userId = data.optString("readUserId", data.optString("userId", ""))
        val offsetDate = data.optLong("readDate", data.optLong("offsetDate", 0L))

        if (channelCode.isEmpty() || userId.isEmpty() || offsetDate <= 0L) {
            Log.w(TAG, "handleReadChatEvent: invalid data, skipping. cc=$channelCode uid=$userId offset=$offsetDate")
            return
        }

        val offsetEntity = ChannelOffsetEntity(
            channelCode = channelCode,
            userId = userId,
            offsetDate = offsetDate
        )
        chatDb.channelOffsetDao().insert(offsetEntity)

        Log.d(TAG, "handleReadChatEvent: channelCode=$channelCode, userId=$userId, offsetDate=$offsetDate")
    }

    // ── 0x0282 DeleteChatEvent: 채팅 상태 변경 (삭제 표시) ──

    private suspend fun handleDeleteChatEvent(data: JSONObject) {
        val chatDb = dbProvider.getChatDatabase()
        val channelCode = data.optString("channelCode", "")
        val chatCode = data.optString("chatCode", "")

        val existing = chatDb.chatDao().getByChatCode(chatCode)
        if (existing != null) {
            chatDb.chatDao().insert(existing.copy(state = 1))
            Log.d(TAG, "handleDeleteChatEvent: chatCode=$chatCode marked as deleted")
        } else {
            Log.w(TAG, "handleDeleteChatEvent: chatCode=$chatCode not found in DB")
        }
    }

    // ── 0x0283 ModChatEvent: 채팅 내용 수정 ──

    private suspend fun handleModChatEvent(data: JSONObject) {
        val chatDb = dbProvider.getChatDatabase()
        val chatCode = data.optString("chatCode", "")
        val contents = data.optString("contents", "")

        val existing = chatDb.chatDao().getByChatCode(chatCode)
        if (existing != null) {
            chatDb.chatDao().insert(existing.copy(contents = contents))
            Log.d(TAG, "handleModChatEvent: chatCode=$chatCode contents updated")
        } else {
            Log.w(TAG, "handleModChatEvent: chatCode=$chatCode not found in DB")
        }
    }

    // ── 0x0284 ReactionChatEvent: 리액션 변경 알림 ──
    // REST /togglereaction 경유 push는 최신 reactions 맵({emoji: [userIds]})을 payload에 실어줌
    // → 해당 chat의 additional.reactions에 직접 merge 하여 즉시 반영.
    // 바이너리 경로 push는 reactionNumber만 있고 reactions 누락 → 이 경우 syncChat으로 fallback.

    private suspend fun handleReactionChatEvent(data: JSONObject) {
        Log.d(TAG, "handleReactionChatEvent: channelCode=${data.optString("channelCode")}, chatCode=${data.optString("chatCode")}, sendUserId=${data.optString("sendUserId")}, reactionNumber=${data.optInt("reactionNumber")}, hasReactions=${data.has("reactions")}")

        val chatCode = data.optString("chatCode", "")
        val reactions = data.optJSONObject("reactions")

        if (chatCode.isNotEmpty() && reactions != null) {
            val chatDb = dbProvider.getChatDatabase()
            val existing = chatDb.chatDao().getByChatCode(chatCode)
            if (existing != null) {
                val merged = try {
                    val add = if (!existing.additional.isNullOrEmpty()) JSONObject(existing.additional) else JSONObject()
                    add.put("reactions", reactions)
                    add.toString()
                } catch (_: Exception) { existing.additional ?: "" }
                chatDb.chatDao().insert(existing.copy(additional = merged))
                Log.d(TAG, "handleReactionChatEvent: chatCode=$chatCode additional.reactions updated from push")
                return
            }
        }

        // reactions 필드가 없거나 chat이 로컬에 없는 경우만 fallback sync
        val userId = appConfig.getSavedUserId() ?: return
        channelRepository.syncChat(userId)
    }

    // ── 0x0290 MakeChannelEvent: 채널 생성 → upsert channel + members ──

    private suspend fun handleMakeChannelEvent(data: JSONObject) {
        val chatDb = dbProvider.getChatDatabase()
        val channelCode = data.optString("channelCode", "")
        var channelName = if (data.isNull("channelName")) "" else data.optString("channelName", "")
        val channelType = data.optString("channelType", "")
        val additionalObj = data.optJSONObject("additional")
        val registDate = data.optLong("registDate", System.currentTimeMillis())

        // 멤버 파싱 — push는 "users" 문자열 배열 또는 "channelMemberList" 객체 배열
        val memberUserIds = mutableListOf<String>()
        val usersArray = data.optJSONArray("users")
        val memberListArray = data.optJSONArray("channelMemberList") ?: data.optJSONArray("channelMembers")

        if (usersArray != null) {
            for (i in 0 until usersArray.length()) {
                // optString — 문자열이 아니거나 null 이면 "" 반환 (JSONException 방지)
                val uid = usersArray.optString(i, "")
                if (uid.isNotEmpty()) memberUserIds.add(uid)
            }
        } else if (memberListArray != null) {
            for (i in 0 until memberListArray.length()) {
                val m = memberListArray.optJSONObject(i) ?: continue
                memberUserIds.add(m.optString("userId", ""))
            }
        }

        // channelName이 비어있으면 상대방 이름으로 채우기 (DM)
        if (channelName.isEmpty() && memberUserIds.isNotEmpty()) {
            try {
                val orgDb = dbProvider.getOrgDatabase()
                val myUserId = dbProvider.getCurrentUserId() ?: ""
                val otherIds = memberUserIds.filter { it != myUserId && it.isNotEmpty() }
                val names = mutableListOf<String>()
                for (uid in otherIds) {
                    val user = orgDb.userDao().getByUserId(uid)
                    if (user != null && user.userInfo.isNotEmpty()) {
                        val info = JSONObject(user.userInfo)
                        val name = info.optString("userName", uid)
                        names.add(name)
                    } else {
                        names.add(uid)
                    }
                }
                if (names.isNotEmpty()) channelName = names.joinToString(", ")
            } catch (e: Exception) {
                Log.w(TAG, "handleMakeChannelEvent: failed to resolve channelName: ${e.message}")
            }
        }

        val channelEntity = ChannelEntity(
            channelCode = channelCode,
            channelName = channelName,
            channelType = channelType,
            additional = additionalObj?.toString() ?: ""
        )
        chatDb.channelDao().insert(channelEntity)

        // 멤버 저장
        if (memberUserIds.isNotEmpty()) {
            val members = memberUserIds.map { uid ->
                ChannelMemberEntity(channelCode = channelCode, userId = uid, registDate = registDate)
            }
            chatDb.channelMemberDao().insertAll(members)
        }

        Log.d(TAG, "handleMakeChannelEvent: channelCode=$channelCode, channelName=$channelName, members=${memberUserIds.size}")
    }

    // ── 0x0291 AddChannelMemberEvent: 멤버 추가 ──

    private suspend fun handleAddChannelMemberEvent(data: JSONObject) {
        val chatDb = dbProvider.getChatDatabase()
        val channelCode = data.optString("channelCode", "")

        val topRegistDate = data.optLong("registDate", 0L)
        val memberList = data.optJSONArray("users") ?: data.optJSONArray("channelMemberList")
        val addedUserIds = mutableListOf<String>()
        var eventDate = if (topRegistDate > 0L) topRegistDate else System.currentTimeMillis()
        if (memberList != null) {
            val members = mutableListOf<ChannelMemberEntity>()
            for (i in 0 until memberList.length()) {
                val m = memberList.optJSONObject(i) ?: continue
                val userId = m.optString("userId", "")
                if (userId.isEmpty()) continue
                val registDate = m.optLong("registDate", 0L)
                    .takeIf { it > 0L }
                    ?: m.optLong("actDate", 0L).takeIf { it > 0L }
                    ?: topRegistDate
                members.add(ChannelMemberEntity(channelCode = channelCode, userId = userId, registDate = registDate))
                addedUserIds.add(userId)
            }
            if (members.isNotEmpty()) {
                chatDb.channelMemberDao().insertAll(members)
            }
        }

        // 2026-04-18: 서버가 SendChatEvent(chatType="SYSTEM")로 입장 메시지를 별도 내려주므로 자체 생성 비활성화 (중복 방지).
        //             서버 경로 이상 시 복구할 수 있게 코드는 주석으로 보존.
        /*
        // 입장 시스템 메시지 삽입
        if (channelCode.isNotEmpty() && addedUserIds.isNotEmpty()) {
            try {
                val orgDb = dbProvider.getOrgDatabase()
                val names = addedUserIds.map { uid ->
                    val user = orgDb.userDao().getByUserId(uid)
                    if (user != null && user.userInfo.isNotEmpty()) {
                        try { JSONObject(user.userInfo).optString("userName", uid) } catch (_: Exception) { uid }
                    } else uid
                }
                val chatCode = "sys_add_${channelCode}_${eventDate}"
                val existing = chatDb.chatDao().getByChatCode(chatCode)
                if (existing == null) {
                    chatDb.chatDao().insert(ChatEntity(
                        channelCode = channelCode,
                        chatCode = chatCode,
                        sendUserId = "",
                        contents = "${names.joinToString(", ")}님이 대화에 참여했습니다.",
                        sendDate = eventDate,
                        chatType = 99
                    ))
                }
            } catch (_: Exception) { }
        }
        */

        Log.d(TAG, "handleAddChannelMemberEvent: channelCode=$channelCode, added=${addedUserIds}")
    }

    // ── 0x0292 DestroyChannelEvent: 채널 삭제 ──

    private suspend fun handleDestroyChannelEvent(data: JSONObject) {
        val chatDb = dbProvider.getChatDatabase()
        val channelCode = data.optString("channelCode", "")

        chatDb.channelMemberDao().deleteByChannel(channelCode)
        chatDb.chatDao().deleteByChannel(channelCode)
        chatDb.channelOffsetDao().deleteByChannel(channelCode)
        chatDb.channelDao().deleteByChannelCode(channelCode)

        Log.d(TAG, "handleDestroyChannelEvent: channelCode=$channelCode deleted")
    }

    // ── 0x0293 RemoveChannelMemberEvent: 멤버 탈퇴 (unregistDate 갱신) ──

    private suspend fun handleRemoveChannelMemberEvent(data: JSONObject) {
        val chatDb = dbProvider.getChatDatabase()
        val channelCode = data.optString("channelCode", "")
        val unregistDate = data.optLong("removeDate", System.currentTimeMillis())
        val myUserId = appConfig.getSavedUserId() ?: ""

        val removedUserIds = mutableListOf<String>()
        val users = data.optJSONArray("users")
        if (users != null && users.length() > 0) {
            for (i in 0 until users.length()) {
                val userId = users.optJSONObject(i)?.optString("userId", "") ?: ""
                if (userId.isEmpty()) continue
                chatDb.channelMemberDao().deleteMember(channelCode, userId)
                removedUserIds.add(userId)
                Log.d(TAG, "handleRemoveChannelMemberEvent: channelCode=$channelCode, userId=$userId deleted")
            }
        } else {
            // fallback: 최상위 userId 필드
            val userId = data.optString("userId", "")
            if (userId.isNotEmpty()) {
                chatDb.channelMemberDao().deleteMember(channelCode, userId)
                removedUserIds.add(userId)
                Log.d(TAG, "handleRemoveChannelMemberEvent: channelCode=$channelCode, userId=$userId deleted")
            }
        }

        // 본인이 나간 경우: 채널·채팅·오프셋까지 모두 로컬에서 제거 (Bridge removeChannelMember self-leave와 동일)
        if (channelCode.isNotEmpty() && myUserId.isNotEmpty() && removedUserIds.contains(myUserId)) {
            chatDb.channelMemberDao().deleteByChannel(channelCode)
            chatDb.chatDao().deleteByChannel(channelCode)
            chatDb.channelOffsetDao().deleteByChannel(channelCode)
            chatDb.channelDao().deleteByChannelCode(channelCode)
            Log.d(TAG, "handleRemoveChannelMemberEvent: self-leave → channelCode=$channelCode purged locally")
            return
        }

        // 2026-04-18: 서버가 SendChatEvent(chatType="SYSTEM")로 퇴장 메시지를 별도 내려주므로 자체 생성 비활성화 (중복 방지).
        //             서버 경로 이상 시 복구할 수 있게 코드는 주석으로 보존.
        /*
        // 퇴장 시스템 메시지 삽입 (타인 퇴장에만)
        if (channelCode.isNotEmpty() && removedUserIds.isNotEmpty()) {
            try {
                val orgDb = dbProvider.getOrgDatabase()
                for (userId in removedUserIds) {
                    val user = orgDb.userDao().getByUserId(userId)
                    val name = if (user != null && user.userInfo.isNotEmpty()) {
                        try { JSONObject(user.userInfo).optString("userName", userId) } catch (_: Exception) { userId }
                    } else userId
                    val chatCode = "sys_rm_${channelCode}_${userId}_${unregistDate}"
                    val existing = chatDb.chatDao().getByChatCode(chatCode)
                    if (existing == null) {
                        chatDb.chatDao().insert(ChatEntity(
                            channelCode = channelCode,
                            chatCode = chatCode,
                            sendUserId = "",
                            contents = "${name}님이 퇴장했습니다.",
                            sendDate = unregistDate,
                            chatType = 99
                        ))
                    }
                }
            } catch (_: Exception) { }
        }
        */
    }

    // ── 0x0296 SetChannelEvent: 채널 정보 갱신 ──

    private suspend fun handleSetChannelEvent(data: JSONObject) {
        val chatDb = dbProvider.getChatDatabase()
        val channelCode = data.optString("channelCode", "")

        val existing = chatDb.channelDao().getByChannelCode(channelCode)
        val channelEntity = if (existing != null) {
            existing.copy(
                channelName = data.optString("channelName", existing.channelName),
                channelType = data.optString("channelType", existing.channelType),
                additional = data.optJSONObject("additional")?.toString() ?: existing.additional
            )
        } else {
            ChannelEntity(
                channelCode = channelCode,
                channelName = data.optString("channelName", ""),
                channelType = data.optString("channelType", ""),
                additional = data.optJSONObject("additional")?.toString() ?: "{}"
            )
        }
        chatDb.channelDao().insert(channelEntity)

        Log.d(TAG, "handleSetChannelEvent: channelCode=$channelCode")
    }

    // ── 0x0B80 SendMessageEvent: 쪽지 수신 → MessageRepository 위임 ──

    private suspend fun handleSendMessageEvent(data: JSONObject) {
        messageRepository.handlePushEvent(data)
        Log.d(TAG, "handleSendMessageEvent: delegated to MessageRepository")
    }

    // ── 0x0B81 ReadMessageEvent: 쪽지 읽음 → 로컬 DB state 갱신 ──

    private suspend fun handleReadMessageEvent(data: JSONObject) {
        val messageCode = data.optString("messageCode", "")
        val readUserId = data.optString("readUserId", "")
        val readDate = data.optLong("readDate", 0L)
        if (messageCode.isEmpty()) return
        try {
            val dao = dbProvider.getMessageDatabase().messageDao()

            // 1) state 비트 플립 (수신자 관점 읽음)
            dao.markRead(messageCode)

            // 2) receivers JSON 내 해당 사용자 readDate 갱신
            //    (발신자 관점에서 "누가 읽었나" UI 렌더링에 필요)
            //    구조(우선): [{list:[{key:userId, readDate}, ...]}, ...]
            //    구조(fallback): [{userId, read, readDate}, ...]
            //    React는 readDate > 0 체크로 "읽음" 판단 (MessagePage/index.jsx:234)
            if (readUserId.isNotEmpty()) {
                val entity = dao.getByMessageCode(messageCode)
                if (entity != null) {
                    val arr = try { JSONArray(entity.receivers) } catch (_: Exception) { JSONArray() }
                    var changed = false
                    for (i in 0 until arr.length()) {
                        val elem = arr.optJSONObject(i) ?: continue
                        val nestedList = elem.optJSONArray("list")
                        if (nestedList != null) {
                            for (j in 0 until nestedList.length()) {
                                val r = nestedList.optJSONObject(j) ?: continue
                                if (r.optString("key") == readUserId) {
                                    val existing = r.optLong("readDate", 0L)
                                    val effective = if (readDate > 0) readDate else System.currentTimeMillis()
                                    if (effective > existing) {
                                        r.put("readDate", effective)
                                        r.put("read", true)
                                        changed = true
                                    }
                                }
                            }
                        } else {
                            val uid = elem.optString("userId").ifEmpty { elem.optString("key") }
                            if (uid == readUserId) {
                                val existing = elem.optLong("readDate", 0L)
                                val effective = if (readDate > 0) readDate else System.currentTimeMillis()
                                if (effective > existing) {
                                    elem.put("readDate", effective)
                                    elem.put("read", true)
                                    changed = true
                                }
                            }
                        }
                    }
                    if (changed) dao.updateReceivers(messageCode, arr.toString())
                }
            }
            Log.d(TAG, "handleReadMessageEvent: messageCode=$messageCode readUserId=$readUserId marked read")
        } catch (e: Exception) {
            Log.e(TAG, "handleReadMessageEvent error: ${e.message}", e)
        }
    }

    // ── 0x0B82 DeleteMessageEvent: 쪽지 삭제 → 로컬 DB 삭제 ──

    private suspend fun handleDeleteMessageEvent(data: JSONObject) {
        val messageCode = data.optString("messageCode", "")
        if (messageCode.isEmpty()) return
        try {
            val msgDb = dbProvider.getMessageDatabase()
            msgDb.messageDao().deleteByMessageCode(messageCode)
            Log.d(TAG, "handleDeleteMessageEvent: messageCode=$messageCode deleted")
        } catch (e: Exception) {
            Log.e(TAG, "handleDeleteMessageEvent error: ${e.message}", e)
        }
    }

    // ── 0x0B83 RetrieveMessageEvent: 쪽지 회수 → retrieved 상태 갱신 ──

    private suspend fun handleRetrieveMessageEvent(data: JSONObject) {
        val messageCode = data.optString("messageCode", "")
        if (messageCode.isEmpty()) return
        try {
            val msgDb = dbProvider.getMessageDatabase()
            val existing = msgDb.messageDao().getByMessageCode(messageCode)
            if (existing != null) {
                msgDb.messageDao().update(existing.copy(retrieved = true))
                Log.d(TAG, "handleRetrieveMessageEvent: messageCode=$messageCode retrieved")
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleRetrieveMessageEvent error: ${e.message}", e)
        }
    }

    // ── 0x0780 NotifyEvent: 알림 수신 → NotiRepository 위임 ──

    private suspend fun handleNotifyEvent(data: JSONObject) {
        Log.d(TAG, "handleNotifyEvent: raw data=$data")
        notiRepository.handlePushEvent(data)
        Log.d(TAG, "handleNotifyEvent: delegated to NotiRepository")
    }

    // ── 0x0286~0x0289 VoteEvent / CloseVoteEvent / PinEvent / UnpinEvent ──
    // DB 저장 없이 React(WebView)로 forward만 수행 (Flutter와 동일 패턴)

    private fun handleChatMetaEvent(commandCode: Int, data: JSONObject) {
        Log.d(TAG, "handleChatMetaEvent: cmd=0x${Integer.toHexString(commandCode)} channelCode=${data.optString("channelCode")} chatCode=${data.optString("chatCode")}")
        // DB 직접 갱신 없음 — React(WebView)가 push forward를 받아 UI 갱신
    }

    // ── 0x028A~0x028F Project / Issue / Thread / Calendar / Todo Events ──
    // DB 저장 없이 React(WebView)로 forward만 수행 — React가 delta-sync 호출

    private suspend fun handleProjectEvent(commandCode: Int, data: JSONObject) {
        Log.d(TAG, "handleProjectEvent: cmd=0x${Integer.toHexString(commandCode)} data=$data")

        // ADD_COMMENT / DELETE_COMMENT: 로컬 chat_thread.commentCount 갱신
        // (서버는 0x28C ModThreadEvent 또는 0x299/0x29A 로 보낼 수 있음 — eventType 기준으로 판단)
        val eventType = data.optString("eventType", "")

        if (commandCode == ProtocolCommand.MOD_CAL_EVENT.code) {
            val calCode = data.optString("calCode", "")
            val userId = data.optString("userId", appConfig.getSavedUserId() ?: "")
            val calObj = data.optJSONObject("event") ?: JSONObject()
            if (calCode.isNotEmpty() && eventType != "DELETE" && eventType != "DEL_CAL") {
                try {
                    val entity = pushCalObjToEntity(calObj, calCode, userId, data)
                    dbProvider.getProjectDatabase().calEventDao().insertAll(listOf(entity))
                    Log.d(TAG, "handleProjectEvent: MOD_CAL_EVENT $eventType → calCode=$calCode saved to DB")
                } catch (e: Exception) {
                    Log.e(TAG, "handleProjectEvent MOD_CAL_EVENT save error: ${e.message}", e)
                }
            } else if (calCode.isNotEmpty() && (eventType == "DELETE" || eventType == "DEL_CAL")) {
                try {
                    dbProvider.getProjectDatabase().calEventDao().delete(calCode)
                    Log.d(TAG, "handleProjectEvent: MOD_CAL_EVENT DELETE → calCode=$calCode removed from DB")
                } catch (e: Exception) {
                    Log.e(TAG, "handleProjectEvent MOD_CAL_EVENT delete error: ${e.message}", e)
                }
            }
            return
        }

        if (eventType == "DELETE_ISSUE") {
            val issueCode = data.optString("issueCode", "")
            if (issueCode.isNotEmpty()) {
                try {
                    dbProvider.getProjectDatabase().issueDao().delete(issueCode)
                    Log.d(TAG, "handleProjectEvent: DELETE_ISSUE → issueCode=$issueCode 삭제")
                } catch (e: Exception) {
                    Log.e(TAG, "handleProjectEvent DELETE_ISSUE error: ${e.message}", e)
                }
            }
            return
        }

        if (eventType == "CREATE_CHAT_THREAD") {
            val threadCode = data.optString("threadCode", "")
            val chatCode = data.optString("chatCode", "")
            val channelCode = data.optString("channelCode", "")
            if (threadCode.isNotEmpty() && chatCode.isNotEmpty()) {
                try {
                    val dao = dbProvider.getProjectDatabase().chatThreadDao()
                    dao.insertChatThreads(listOf(ChatThreadEntity(
                        chatCode = chatCode,
                        threadCode = threadCode,
                        channelCode = channelCode,
                        commentCount = data.optInt("commentCount", 0),
                        createdDate = data.optLong("modDate", 0L),
                        chatContents = data.optString("chatContents", "")
                    )))
                    Log.d(TAG, "handleProjectEvent: CREATE_CHAT_THREAD → threadCode=$threadCode chatCode=$chatCode saved")
                } catch (e: Exception) {
                    Log.e(TAG, "handleProjectEvent CREATE_CHAT_THREAD save error: ${e.message}", e)
                }
            }
            return
        }

        if (eventType == "ADD_COMMENT" || eventType == "DELETE_COMMENT") {
            val threadCode = data.optString("threadCode", "")
            if (threadCode.isEmpty() || !data.has("commentCount")) return
            val commentCount = data.optInt("commentCount", 0)
            try {
                val dao = dbProvider.getProjectDatabase().chatThreadDao()
                val ct = dao.getByThreadCode(threadCode)
                if (ct != null) {
                    dao.insertChatThreads(listOf(ct.copy(commentCount = commentCount)))
                    Log.d(TAG, "handleProjectEvent: $eventType → threadCode=$threadCode commentCount=$commentCount")
                } else {
                    Log.d(TAG, "handleProjectEvent: $eventType threadCode=$threadCode not in local DB, skip")
                }
            } catch (e: Exception) {
                Log.e(TAG, "handleProjectEvent commentCount update error: ${e.message}", e)
            }
        }
    }

    // ── 0x0380-0x0383 OrgUserEvent / OrgDeptEvent / OrgUserRemovedEvent / OrgDeptRemovedEvent ──
    // 조직도 실시간 변경 push — DB 직접 갱신 후 orgReady 발행

    private suspend fun handleOrgEvent(commandCode: Int, data: JSONObject) {
        val cmd = ProtocolCommand.fromCode(commandCode)
        Log.d(TAG, "handleOrgEvent: cmd=0x${Integer.toHexString(commandCode)} userId=${data.optString("userId")} deptId=${data.optString("deptId")}")

        try {
            val orgDb = dbProvider.getOrgDatabase()

            when (cmd) {
                ProtocolCommand.ORG_USER_EVENT -> {
                    val userId = data.optString("userId", "")
                    if (userId.isEmpty()) return
                    val userInfoRaw = when {
                        data.optJSONObject("userInfo") != null -> data.optJSONObject("userInfo")!!.toString()
                        data.optString("userInfo", "").isNotEmpty() -> data.optString("userInfo", "")
                        else -> "{}"
                    }
                    orgDb.userDao().insertAllSync(listOf(UserEntity(
                        userId = userId,
                        deptId = data.optString("deptId", ""),
                        loginId = data.optString("loginId", ""),
                        userInfo = userInfoRaw,
                        userOrder = data.optString("userOrder", "")
                    )))
                    Log.d(TAG, "handleOrgEvent: user $userId upserted")
                }

                ProtocolCommand.ORG_DEPT_EVENT -> {
                    val deptId = data.optString("deptId", "")
                    if (deptId.isEmpty()) return
                    orgDb.deptDao().insertAllSync(listOf(DeptEntity(
                        deptId = deptId,
                        parentDept = data.optString("parentDept", ""),
                        deptName = data.optString("deptName", ""),
                        deptOrder = data.optString("deptOrder", ""),
                        deptStatus = data.optString("deptStatus", "")
                    )))
                    Log.d(TAG, "handleOrgEvent: dept $deptId upserted")
                }

                ProtocolCommand.ORG_USER_REMOVED_EVENT -> {
                    val userId = data.optString("userId", "")
                    if (userId.isEmpty()) return
                    orgDb.userDao().deleteByIdsSync(listOf(userId))
                    Log.d(TAG, "handleOrgEvent: user $userId removed")
                }

                ProtocolCommand.ORG_DEPT_REMOVED_EVENT -> {
                    val deptId = data.optString("deptId", "")
                    if (deptId.isEmpty()) return
                    orgDb.deptDao().deleteByIdsSync(listOf(deptId))
                    Log.d(TAG, "handleOrgEvent: dept $deptId removed")
                }

                else -> Log.w(TAG, "handleOrgEvent: unhandled cmd=0x${Integer.toHexString(commandCode)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleOrgEvent DB error: ${e.message}", e)
        }
    }

    private fun pushCalObjToEntity(calObj: JSONObject, calCode: String, fallbackUserId: String, root: JSONObject): CalEventEntity {
        var startDate = calObj.optLong("startDate", 0L)
        var endDate = calObj.optLong("endDate", 0L)
        if (startDate == 0L) {
            val eventDate = calObj.optString("eventDate", "")
            startDate = parseDateTimeMs(eventDate, calObj.optString("startTime", ""))
            if (endDate == 0L && startDate > 0L) {
                endDate = parseDateTimeMs(eventDate, calObj.optString("endTime", ""))
                if (endDate == 0L || endDate <= startDate) endDate = startDate + 3_600_000L
            }
        }
        val userId = calObj.optString("userId", "").ifEmpty { root.optString("userId", fallbackUserId) }
        return CalEventEntity(
            calCode = calCode,
            userId = userId.ifEmpty { fallbackUserId },
            title = calObj.optString("title", ""),
            description = calObj.optString("description", ""),
            calType = calObj.optString("calType", calObj.optString("category", "PERSONAL")),
            startDate = startDate,
            endDate = endDate,
            allDay = calObj.optInt("allDay", 0),
            color = calObj.optString("color", ""),
            location = calObj.optString("location", ""),
            modDate = root.optLong("modDate", calObj.optLong("modDate", 0L)),
            createdDate = calObj.optLong("createdDate", 0L)
        )
    }

    private fun parseDateTimeMs(dateStr: String, timeStr: String): Long {
        if (dateStr.isEmpty()) return 0L
        return try {
            val fullTime = if (timeStr.isEmpty()) "00:00" else timeStr
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            sdf.parse("$dateStr $fullTime")?.time ?: 0L
        } catch (_: Exception) { 0L }
    }
}
