package net.spacenx.messenger.data.remote.socket.codec

/**
 * 서버 ProtocolCommand 미러링 (Binary Protocol 5.0)
 * 서버의 ProtocolCommand.java enum 값과 동일
 */
enum class ProtocolCommand(val code: Int, val protocol: String) {
    // ── Auth ──
    LOGIN(0x0101, "Login"),
    REFRESH_TOKEN(0x0102, "RefreshToken"),
    SYNC_CONFIG(0x0103, "SyncConfig"),
    LOGOUT_CMD(0x0104, "Logout"),
    SET_CONFIG(0x0105, "SetConfig"),

    // ── Chat ──
    SEND_CHAT(0x0201, "SendChat"),
    READ_CHAT(0x0202, "ReadChat"),
    DELETE_CHAT(0x0203, "DeleteChat"),
    MOD_CHAT(0x0204, "ModChat"),
    REACTION_CHAT(0x0205, "ReactionChat"),
    SYNC_CHAT(0x0206, "SyncChat"),
    MAKE_CHANNEL(0x0210, "MakeChannel"),
    ADD_CHANNEL_MEMBER(0x0211, "AddChannelMember"),
    DESTROY_CHANNEL(0x0212, "DestroyChannel"),
    REMOVE_CHANNEL_MEMBER(0x0213, "RemoveChannelMember"),
    SYNC_CHANNEL(0x0220, "SyncChannel"),
    GET_CHANNEL_INFO(0x0221, "GetChannelInfo"),

    // ── Chat Events (Push, invokeId=0) ──
    SEND_CHAT_EVENT(0x0280, "SendChatEvent"),
    READ_CHAT_EVENT(0x0281, "ReadChatEvent"),
    DELETE_CHAT_EVENT(0x0282, "DeleteChatEvent"),
    MOD_CHAT_EVENT(0x0283, "ModChatEvent"),
    REACTION_CHAT_EVENT(0x0284, "ReactionChatEvent"),
    TYPING_CHAT_EVENT(0x0285, "TypingChatEvent"),
    VOTE_EVENT(0x0286, "VoteEvent"),
    CLOSE_VOTE_EVENT(0x0287, "CloseVoteEvent"),
    PIN_EVENT(0x0288, "PinEvent"),
    UNPIN_EVENT(0x0289, "UnpinEvent"),
    // ── Project / Issue / Thread / Calendar / Todo Events (Push, invokeId=0) ──
    MOD_ISSUE_EVENT(0x028A, "ModIssueEvent"),
    MOD_PROJECT_EVENT(0x028B, "ModProjectEvent"),
    MOD_THREAD_EVENT(0x028C, "ModThreadEvent"),
    MOD_CAL_EVENT(0x028D, "ModCalEvent"),
    MOD_TODO_EVENT(0x028E, "ModTodoEvent"),
    CREATE_CHAT_THREAD_EVENT(0x028F, "CreateChatThreadEvent"),
    MAKE_CHANNEL_EVENT(0x0290, "MakeChannelEvent"),
    ADD_CHANNEL_MEMBER_EVENT(0x0291, "AddChannelMemberEvent"),
    DESTROY_CHANNEL_EVENT(0x0292, "DestroyChannelEvent"),
    REMOVE_CHANNEL_MEMBER_EVENT(0x0293, "RemoveChannelMemberEvent"),
    MOD_CHANNEL_EVENT(0x0294, "ModChannelEvent"),
    REMOVE_CHANNEL_EVENT(0x0295, "RemoveChannelEvent"),
    SET_CHANNEL_EVENT(0x0296, "SetChannelEvent"),
    KICK_USER_EVENT(0x0297, "KickUserEvent"),
    DELETE_CHAT_THREAD_EVENT(0x0298, "DeleteChatThreadEvent"),
    ADD_COMMENT_EVENT(0x0299, "AddCommentEvent"),
    DELETE_COMMENT_EVENT(0x029A, "DeleteCommentEvent"),
    NICK_EVENT(0x029B, "Nick"),
    MOBILE_ICN_EVENT(0x029C, "MobileICN"),
    MUTE_CHANNEL_EVENT(0x029D, "MuteChannelEvent"),

    // ── Org ──
    SYNC_ORG(0x030A, "SyncOrg"),
    SEARCH_USER(0x0309, "SearchUser"),

    // ── Org Events (Push, invokeId=0) ──
    ORG_USER_EVENT(0x0380, "OrgUserEvent"),
    ORG_DEPT_EVENT(0x0381, "OrgDeptEvent"),
    ORG_USER_REMOVED_EVENT(0x0382, "OrgUserRemovedEvent"),
    ORG_DEPT_REMOVED_EVENT(0x0383, "OrgDeptRemovedEvent"),

    // ── Buddy ──
    BUDDY_ADD_LINK(0x0409, "BuddyAddLink"),

    // ── Nick ──
    SET_NICK(0x0501, "SetNick"),
    GET_ALL_NICK(0x0502, "GetAllNick"),

    // ── Status (Presence) ──
    SET_PRESENCE(0x0601, "SetPresence"),
    ICON_EVENT(0x0680, "IconEvent"),

    // ── Noti ──
    GET_NOTI_LIST(0x0702, "SyncNoti"),
    ReadNoti(0x0703, "ReadNoti"),
    DELETE_NOTI(0x0704, "DeleteNoti"),
    NOTIFY_EVENT(0x0780, "NotifyEvent"),
    READ_NOTI_EVENT(0x0781, "ReadNotiEvent"),
    DELETE_NOTI_EVENT(0x0782, "DeleteNotiEvent"),
    NOTIFICATION_EVENT(0x0783, "NotificationEvent"),

    // ── Noti Badge ──
    BADGE_COUNT(0x0805, "BadgeCount"),

    // ── Subscribe ──
    PUBLISH(0x0901, "Publish"),
    SUBSCRIBE(0x0902, "Subscribe"),
    UNSUBSCRIBE(0x0903, "Unsubscribe"),

    // ── Bridge ──
    HI(0x0A01, "HI"),
    @Deprecated("Use LOGOUT_CMD") LOGOUT(0x0A02, "Logout"),
    TIME_REQUEST(0x0A07, "timeRequest"),
    NOOP(0x0A09, "noop"),
    QUEUE_OVERFLOW(0x0A0F, "QueueOverflow"),
    DUAL_CONNECTION_KICK(0x0A10, "DualConnectionKickEvent"),

    // ── Message (Note) ──
    SEND_MESSAGE(0x0B01, "SendMessage"),
    READ_MESSAGE(0x0B02, "ReadMessage"),
    DELETE_MESSAGE(0x0B03, "DeleteMessage"),
    GET_MESSAGE_LIST(0x0B04, "GetMessageList"),
    RETRIEVE_MESSAGE(0x0B05, "RetrieveMessage"),
    SEND_MESSAGE_EVENT(0x0B80, "SendMessageEvent"),
    READ_MESSAGE_EVENT(0x0B81, "ReadMessageEvent"),
    DELETE_MESSAGE_EVENT(0x0B82, "DeleteMessageEvent"),
    RETRIEVE_MESSAGE_EVENT(0x0B83, "RetrieveMessageEvent"),
    ;

    companion object {
        private val CODE_MAP = entries.associateBy { it.code }
        fun fromCode(code: Int): ProtocolCommand? = CODE_MAP[code]

        /** protocol name → command code (neoSend용) */
        private val NAME_MAP = entries.associateBy { it.protocol }
        fun fromName(name: String): ProtocolCommand? = NAME_MAP[name]

        /** Push event command codes (invokeId=0) */
        val PUSH_EVENT_CODES = setOf(
            SEND_CHAT_EVENT, READ_CHAT_EVENT, DELETE_CHAT_EVENT, MOD_CHAT_EVENT, REACTION_CHAT_EVENT, TYPING_CHAT_EVENT,
            VOTE_EVENT, CLOSE_VOTE_EVENT, PIN_EVENT, UNPIN_EVENT,
            MOD_ISSUE_EVENT, MOD_PROJECT_EVENT, MOD_THREAD_EVENT, MOD_CAL_EVENT, MOD_TODO_EVENT, CREATE_CHAT_THREAD_EVENT,
            MAKE_CHANNEL_EVENT, ADD_CHANNEL_MEMBER_EVENT, DESTROY_CHANNEL_EVENT,
            REMOVE_CHANNEL_MEMBER_EVENT, MOD_CHANNEL_EVENT, REMOVE_CHANNEL_EVENT, SET_CHANNEL_EVENT,
            KICK_USER_EVENT, DELETE_CHAT_THREAD_EVENT, ADD_COMMENT_EVENT, DELETE_COMMENT_EVENT,
            NICK_EVENT, MOBILE_ICN_EVENT, MUTE_CHANNEL_EVENT,
            SEND_MESSAGE_EVENT, READ_MESSAGE_EVENT, DELETE_MESSAGE_EVENT, RETRIEVE_MESSAGE_EVENT,
            ICON_EVENT, NOTIFY_EVENT, READ_NOTI_EVENT, DELETE_NOTI_EVENT, NOTIFICATION_EVENT,
            SET_CONFIG,
            ORG_USER_EVENT, ORG_DEPT_EVENT, ORG_USER_REMOVED_EVENT, ORG_DEPT_REMOVED_EVENT,
            QUEUE_OVERFLOW, DUAL_CONNECTION_KICK
        )

        fun isPushEvent(code: Int): Boolean =
            PUSH_EVENT_CODES.any { it.code == code }
    }
}
