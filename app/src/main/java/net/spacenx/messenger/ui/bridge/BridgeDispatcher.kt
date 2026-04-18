package net.spacenx.messenger.ui.bridge

import android.util.Log
import android.webkit.WebView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.common.JsEscapeUtil
import net.spacenx.messenger.data.local.DatabaseProvider
import net.spacenx.messenger.data.local.entity.ChannelEntity
import net.spacenx.messenger.data.local.entity.ChannelMemberEntity
import net.spacenx.messenger.data.local.entity.SyncMetaEntity
import net.spacenx.messenger.data.remote.api.ApiClient
import net.spacenx.messenger.data.repository.*
import net.spacenx.messenger.data.repository.ProjectRepository
import net.spacenx.messenger.data.cache.UserNameCache
import net.spacenx.messenger.ui.MainActivity
import net.spacenx.messenger.ui.bridge.handler.*
import net.spacenx.messenger.ui.call.CallService
import net.spacenx.messenger.ui.viewmodel.LoginViewModel
import org.json.JSONArray
import org.json.JSONObject

/**
 * JS Bridge 액션 라우팅 + BridgeContext 구현.
 * 비즈니스 로직은 handler/ 패키지의 도메인별 Handler에 위임.
 */
class BridgeDispatcher(
    override val activity: MainActivity,
    private val webView: WebView,
    private val authRepo: AuthRepository,
    private val buddyRepo: BuddyRepository,
    private val channelRepo: ChannelRepository,
    private val orgRepo: OrgRepository,
    private val pubSubRepo: PubSubRepository,
    private val statusRepo: StatusRepository,
    private val messageRepo: MessageRepository,
    private val notiRepo: NotiRepository,
    override val loginViewModel: LoginViewModel,
    override val dbProvider: DatabaseProvider,
    override val appConfig: AppConfig,
    override val userNameCache: UserNameCache,
    val projectRepo: ProjectRepository
) : BridgeContext {

    companion object {
        private const val TAG = "BridgeDispatcher"

        // ── 상태 변경 우선순위 플래그 ──
        // true  = REST 먼저 → 실패 시 바이너리 소켓 fallback
        // false = 바이너리 소켓 먼저 → 실패(소켓 미연결) 시 REST fallback
        // 2026-04-09
        const val PRESENCE_REST_PRIORITY = true

        // ── 핸들러별 라우팅 셋 ──
        // 새 액션을 핸들러에 추가할 때 dispatch() when 절을 건드리지 않고 이 셋만 수정.
        // (#9 BridgeDispatcher actionMap prep — full Map 변환 대신 액션 inventory 만 외부화.)

        private val ORG_HANDLER_ACTIONS = setOf(
            "getOrgList", "getOrgSubList", "searchUsers", "openUserDetail",
            "syncBuddy", "addUserToMyList", "getMyPart",
            "addBuddy", "removeBuddy",
            "addBuddyGroup", "deleteBuddyGroup", "renameBuddyGroup", "createSubGroup",
        )
        private val CHANNEL_HANDLER_ACTIONS = setOf(
            "syncChannel", "getChannelList", "getChannelSummaries",
            "createChatRoom", "createGroupChatRoom", "openChannel",
            "addChannelMember", "removeChannelMember",
            "addChannelFavorite", "removeChannelFavorite", "createConference",
            "removeChannel", "findChannelByMembers", "getChannel",
            "deleteRoom", "openChatRoom", "joinConference",
        )
        private val CHAT_HANDLER_ACTIONS = setOf(
            "getChatList", "sendChat", "readChat", "deleteChat", "modChat", "toggleReaction",
            "addLocalSystemChat",
            "toggleVote", "closeVote", "pinMessage", "unpinMessage",
            "getUnreadCount",
        )
        private val MESSAGE_HANDLER_ACTIONS = setOf(
            "sendMessage", "readMessage", "deleteMessage", "syncMessage",
            "fullSync", "loadMoreMessages", "getMessageDetail", "getMessageCounts",
            "retrieveMessage",
        )
        private val FILE_HANDLER_ACTIONS = setOf(
            "uploadFile", "pickFile", "downloadFile", "openFile", "relocateFiles",
            "fileUploadPause", "fileUploadResume", "fileUploadCancel",
            "fileDownloadPause", "fileDownloadResume", "fileDownloadCancel",
            "previewFile", "shareFile",
        )
        private val NOTI_HANDLER_ACTIONS = setOf(
            "syncNoti", "loadMoreNotis", "getNotiCounts", "readNoti",
        )
        private val NEOSEND_HANDLER_ACTIONS = setOf("apiPost", "neoSend", "httpRequest")

        /** neoSend REST fallback 매핑 */
        val NEOSEND_REST_MAP = mapOf(
            "SendChat" to "/comm/sendchat",
            "ReadChat" to "/comm/readchat",
            "DeleteChat" to "/comm/deletechat",
            "ModChat" to "/comm/modchat",
            "ReactionChat" to "/comm/reactionchat",
            "MakeChannel" to "/comm/makechannel",
            "AddChannelMember" to "/comm/addchannelmember",
            "RemoveChannelMember" to "/comm/removechannelmember",
            "DestroyChannel" to "/comm/destroychannel",
            "SendMessage" to "/comm/sendmessage",
            "ReadMessage" to "/comm/readmessage",
            "DeleteMessage" to "/comm/deletemessage",
            "GetMessageList" to "/comm/syncmessage",
            "SetNick" to "/nick/setnick",
            "GetAllNick" to "/nick/getallnick",
            "SyncNoti" to "/noti/syncnoti",
            "BadgeCount" to "/noti/badgecount",
            "Subscribe" to "/status/subscribe",
        )
    }

    override val scope: CoroutineScope get() = activity.lifecycleScope

    // NHM-61: sync 완료 이벤트 보관 (React 마운트 전 완료 대비) — 멀티스레드 안전
    override val completedSyncs: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /** NHM-70: 현재 열려 있는 채널 코드 (인앱 알림 억제용). 프로세스 전역 AppState에도 미러링. */
    @Volatile
    override var activeChannelCode: String? = null
        set(value) {
            field = value
            net.spacenx.messenger.common.AppState.activeChannelCode = value
        }

    /** 서브 WebView 팝업 컨텍스트 (getPopupContext에서 반환 후 소비) */
    @Volatile
    var pendingPopupContext: JSONObject? = null

    // ── 도메인 핸들러 ──
    private val channelActionHandler = ChannelActionHandler(this, channelRepo)
    private val chatHandler = ChatHandler(this, channelRepo)
    private val messageHandler = MessageHandler(this, messageRepo)
    private val orgHandler = OrgHandler(this, orgRepo, buddyRepo, pubSubRepo)
    private val notiHandler = NotiHandler(this, notiRepo)
    private val fileHandler = FileHandler(this)
    private val appHandler = AppHandler(this, channelRepo, pubSubRepo)
    private val neoSendHandler = NeoSendHandler(this, messageRepo, projectRepo)
    val callService = CallService(
        appContext = activity.applicationContext,
        getEndpoint = { path -> appConfig.getEndpointByPath(path) },
        getToken = { loginViewModel.sessionManager.jwtToken }
    )

    init {
        // Presence push → WebView
        scope.launch {
            pubSubRepo.publishEvent.collect { json ->
                // Self PUBLISH: fill in missing nick/icon so React doesn't clear them
                val enriched = try {
                    val obj = org.json.JSONObject(json)
                    val myId = appConfig.getSavedUserId()
                    if (obj.optString("userId") == myId) {
                        val cmd = obj.optString("command")
                        if (cmd == "Icon") {
                            val nick = appConfig.getMyNick()
                            if (nick.isNotEmpty()) obj.put("nick", nick)
                            val newIcon = obj.optInt("icon", -1)
                            if (newIcon >= 0) appConfig.saveMyStatusCode(newIcon)
                        } else if (cmd == "Nick") {
                            val icon = appConfig.getMyStatusCode()
                            obj.put("icon", icon)
                            val newNick = obj.optString("nick")
                            if (newNick.isNotEmpty()) appConfig.saveMyNick(newNick)
                        }
                    }
                    obj.toString()
                } catch (_: Exception) { json }
                Log.d("Presence", "[5] PUBLISH→React: $enriched")
                val wrapped = """{"users":[$enriched]}"""
                evalJs("window._onPresenceUpdate && window._onPresenceUpdate('${esc(wrapped)}')")
            }
        }
    }

    fun dispatch(action: String, params: Map<String, Any?> = emptyMap()) {
        activity.runOnUiThread {
            when (action) {
                // ── 앱/윈도우 ──
                "finishApp", "closeApp", "closeWindow" -> activity.finish()
                "updateStatusBar" -> activity.onUpdateStatusBarFromWeb(paramStr(params, "hex"))

                // ── 인증 ──
                "getClientState" -> appHandler.handleGetClientState()
                "requestPermission" -> activity.onRequestPermissionFromWeb()
                "agreePrivacyPolicy" -> activity.onAgreePrivacyPolicyFromWeb()
                "login" -> activity.onLoginFromWeb(
                    paramStr(params, "userId").ifEmpty { paramStr(params, "username") },
                    paramStr(params, "password")
                )
                "autoLogin" -> activity.onAutoLoginFromWeb()
                "waitAutoLogin" -> {
                    val pending = activity.pendingAuthJson
                    if (pending != null) {
                        activity.pendingAuthJson = null
                        Log.d(TAG, "waitAutoLogin: pendingAuthJson found, resolving immediately")
                        evalJs("(function(){var fn=window._waitAutoLoginResolve||window._autoLoginResolve;if(fn)fn('$pending');})();")
                    } else {
                        Log.d(TAG, "waitAutoLogin: no pending auth, waiting for Authenticated state")
                    }
                }
                "logout" -> {
                    completedSyncs.clear()
                    userNameCache.clear()
                    activity.requestLogout()
                }
                // 프론트엔드 setCredential() 은 'SetCredential' (대문자 S) 로 전송. legacy saveCredential alias 는 제거됨.
                "SetCredential" -> scope.launch { appHandler.handleSaveCredential(params) }

                // ── 조직도 + 내목록 + 버디 관리 ──
                in ORG_HANDLER_ACTIONS -> scope.launch { orgHandler.handle(action, params) }

                // ── 채널 관리 (joinConference 포함) ──
                in CHANNEL_HANDLER_ACTIONS -> scope.launch { channelActionHandler.handle(action, params) }

                // ── 채팅 메시지 ──
                in CHAT_HANDLER_ACTIONS -> scope.launch { chatHandler.handle(action, params) }
                // destroyChannel·forwardChat 는 nx 에서 apiPost 로 직접 호출 — dispatcher 경유 불필요 (제거됨).
                "typingChat" -> scope.launch { handleRestForward("typingChat", "/comm/typingchat", params) }

                // ── 쪽지 ──
                in MESSAGE_HANDLER_ACTIONS -> scope.launch { messageHandler.handle(action, params) }

                "searchMessageListByUser" -> scope.launch {
                    handleRestForward("searchMessageListByUser", "/comm/syncmessage", params)
                }

                // ── 파일 (chat / filebox 공통 — context 파라미터로 분기) ──
                in FILE_HANDLER_ACTIONS -> scope.launch { fileHandler.handle(action, params) }

                // ── 상태/프로필 ──
                "changeStatus" -> scope.launch { appHandler.handleChangeStatus(params) }
                "changeStatusMessage" -> scope.launch { handleRestForward("changeStatusMessage", "/status/setpresence", params) }
                "setNick" -> scope.launch { appHandler.handleSetNick(params) }
                "updateProfile" -> scope.launch { handleRestForward("updateProfile", "/org/updateprofile", params) }
                "uploadProfilePhoto" -> scope.launch { appHandler.handleUploadProfilePhoto(params) }

                // ── 알림 ── (deleteNoti 는 nx 미호출로 제거)
                in NOTI_HANDLER_ACTIONS -> scope.launch { notiHandler.handle(action, params) }

                // ── 앱 설정 동기화 ──
                "syncConfig" -> scope.launch { appHandler.handleSyncConfig(params) }

                // ── 채팅방 멤버 전체에게 쪽지 발송 ──
                "sendMessageToRoomMembers" -> scope.launch { appHandler.handleSendMessageToRoomMembers(params) }

                // ── 유저 구독 (Presence REST) ──
                "subscribeUsers" -> scope.launch { handleRestForward("subscribeUsers", "/status/subscribe", params) }

                // ── 앱 정보 / 사용자 설정 ──
                "getAppInfo" -> appHandler.handleGetAppInfo()
                "getUserConfig" -> scope.launch { appHandler.handleGetUserConfig(params) }
                "setUserConfig" -> scope.launch { appHandler.handleSetUserConfig(params) }

                // ── QUIC 전송 설정 ──
                "getQuicSetting" -> appHandler.handleGetQuicSetting()
                "setQuicSetting" -> appHandler.handleSetQuicSetting(params)

                // ── 회의 (joinConference 는 CHANNEL_HANDLER_ACTIONS 에서 처리) ──
                "listConference" -> scope.launch { handleRestForward("listConference", "/comm/listconference", params) }
                "inviteConference" -> scope.launch { handleRestForward("inviteConference", "/comm/inviteconference", params) }

                // ── NHM-70: 현재 열린 채널 추적 (인앱 알림 억제) ── (setActiveChannel·setViewingChannel alias 제거)
                "focusChannel" -> {
                    val cc = paramStr(params, "channelCode").ifEmpty { null }
                    activeChannelCode = cc
                    Log.d(TAG, "focusChannel: $activeChannelCode")
                    resolveToJs(action, org.json.JSONObject().put("errorCode", 0))
                }

                // ── 테마 ── (broadcastSkin 은 nx 미호출로 제거)
                "broadcastTheme" -> appHandler.handleBroadcastTheme(params)

                // ── 앱 제어 ──
                "exitApp" -> {
                    resolveToJs("exitApp", JSONObject().put("errorCode", 0))
                    activity.finishAffinity()
                }
                "hardReload" -> {
                    activity.onHardReload()
                    webView.post { webView.reload() }
                    resolveToJs("hardReload", JSONObject().put("errorCode", 0))
                }

                // ── sync 상태 ──
                "getSyncStatus" -> appHandler.handleGetSyncStatus()

                // ── 프로젝트/스레드 sync ──
                "syncThread" -> scope.launch {
                    try {
                        val limit = paramInt(params, "limit", 50)
                        val offset = paramInt(params, "offset", 0)
                        // Flutter와 동일: {errorCode:0, events:[{eventType, threadCode, chatCode, ...}], hasMore}
                        // delta sync는 SyncService에서 완료 후 threadReady 발행 — 여기서 중복 호출 불필요
                        val result = withContext(Dispatchers.IO) {
                            projectRepo.getAllThreadsAsEvents(limit, offset)
                        }
                        resolveToJs("syncThread", result)
                    } catch (e: Exception) {
                        rejectToJs("syncThread", e.message)
                    }
                }

                // ── 범용 REST ──
                in NEOSEND_HANDLER_ACTIONS -> scope.launch { neoSendHandler.handle(action, params) }

                // ── 통화 (LiveKit 네이티브 연동) ──
                "createCall" -> {
                    activity.ensureCallPermissions {
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    callService.createCall(
                                        paramStr(params, "channelCode"),
                                        paramStr(params, "userId"),
                                        paramStr(params, "userName"),
                                        paramStr(params, "callType").ifEmpty { "audio" }
                                    )
                                }
                                if (callService.isInCall) {
                                    activity.showCallOverlay(callService, callService.currentCallType ?: "audio")
                                }
                                resolveToJs("createCall", result)
                            } catch (e: Exception) { rejectToJs("createCall", e.message) }
                        }
                    }
                }
                "joinCall" -> {
                    activity.ensureCallPermissions {
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    callService.joinCall(
                                        paramStr(params, "channelCode"),
                                        paramStr(params, "userId"),
                                        paramStr(params, "userName")
                                    )
                                }
                                if (callService.isInCall) {
                                    activity.showCallOverlay(callService, callService.currentCallType ?: "audio")
                                }
                                resolveToJs("joinCall", result)
                            } catch (e: Exception) { rejectToJs("joinCall", e.message) }
                        }
                    }
                }
                "endCall" -> scope.launch {
                    try {
                        val channelCode = paramStr(params, "channelCode")
                        activity.hideCallOverlay()
                        val result = withContext(Dispatchers.IO) { callService.endCall(channelCode) }
                        resolveToJs("endCall", result)
                    } catch (e: Exception) { rejectToJs("endCall", e.message) }
                }
                "toggleMic" -> scope.launch {
                    callService.toggleMicrophone(paramBool(params, "enabled"))
                    resolveToJs("toggleMic", JSONObject().put("errorCode", 0))
                }
                "toggleCamera" -> scope.launch {
                    callService.toggleCamera(paramBool(params, "enabled"))
                    resolveToJs("toggleCamera", JSONObject().put("errorCode", 0))
                }
                "toggleScreenShare" -> scope.launch {
                    callService.toggleScreenShare(paramBool(params, "enabled"))
                    resolveToJs("toggleScreenShare", JSONObject().put("errorCode", 0))
                }

                // ── 채널 검색 / 스레드 ──
                "searchChatListByUser" -> scope.launch {
                    handleRestForward("searchChatListByUser", "/comm/searchchatlistbyuser", params)
                }
                "getThreadsByChannel" -> scope.launch {
                    try {
                        val channelCode = paramStr(params, "channelCode")
                        // 로컬 DB 조회
                        val localResult = withContext(Dispatchers.IO) { projectRepo.getThreadsByChannel(channelCode) }
                        val threads = localResult.optJSONArray("threads")
                        if (threads != null && threads.length() > 0) {
                            resolveToJs("getThreadsByChannel", localResult)
                        } else {
                            // 로컬 비어있으면 REST fallback + 로컬 캐시
                            handleRestForward("getThreadsByChannel", "/comm/getthreadsbychannel", params)
                        }
                    } catch (e: Exception) {
                        handleRestForward("getThreadsByChannel", "/comm/getthreadsbychannel", params)
                    }
                }
                "getThreadsByIssue" -> scope.launch {
                    try {
                        val issueCode = paramStr(params, "issueCode")
                        val result = withContext(Dispatchers.IO) { projectRepo.getThreadsByIssue(issueCode) }
                        resolveToJs("getThreadsByIssue", result)
                    } catch (e: Exception) {
                        rejectToJs("getThreadsByIssue", e.message)
                    }
                }

                // ── 스레드 댓글 조회 (ThreadPage 상세) ──
                "getThreadComments" -> scope.launch {
                    try {
                        val threadCode = paramStr(params, "threadCode")
                        // 로컬 DB 조회 (commentCount와 비교하여 부족하면 REST fallback)
                        val localResult = withContext(Dispatchers.IO) { projectRepo.handleLocally("/comm/getthreadcomments", org.json.JSONObject().put("threadCode", threadCode)) }
                        if (localResult != null) {
                            resolveToJs("getThreadComments", localResult)
                        } else {
                            // REST fallback
                            val token = appConfig.getSavedToken()
                            val result = withContext(Dispatchers.IO) {
                                ApiClient.postJson(appConfig.getEndpointByPath("/comm/getthreadcomments"), org.json.JSONObject().put("threadCode", threadCode), token)
                            }
                            resolveToJs("getThreadComments", result)
                            // REST 응답 캐시
                            withContext(Dispatchers.IO) { projectRepo.cacheAfterCud("/comm/getthreadcomments", org.json.JSONObject().put("threadCode", threadCode), result) }
                        }
                    } catch (e: Exception) {
                        rejectToJs("getThreadComments", e.message)
                    }
                }

                // ── 전체 이슈 조회 ──
                "getAllIssues" -> scope.launch {
                    val cbId = paramStr(params, "_callbackId").ifEmpty { "getAllIssues" }
                    if (!dbProvider.isInitialized()) {
                        resolveToJs(cbId, JSONObject().put("errorCode", 0).put("issues", JSONArray()))
                        return@launch
                    }
                    try {
                        val result = withContext(Dispatchers.IO) {
                            val db = dbProvider.getProjectDatabase()
                            val issues = db.issueDao().getAll()
                            val arr = JSONArray()
                            for (issue in issues) {
                                arr.put(JSONObject().apply {
                                    put("issueCode", issue.issueCode)
                                    put("projectCode", issue.projectCode)
                                    put("channelCode", issue.channelCode)
                                    put("title", issue.title)
                                    put("issueStatus", issue.issueStatus)
                                    put("priority", issue.priority)
                                    put("assigneeUserId", issue.assigneeUserId)
                                    put("reporterUserId", issue.reporterUserId)
                                    if (issue.dueDate > 0) put("dueDate", issue.dueDate)
                                    if (issue.createdDate > 0) put("createdDate", issue.createdDate)
                                    if (issue.modDate > 0) put("modDate", issue.modDate)
                                    put("threadCode", issue.threadCode)
                                    put("commentCount", issue.commentCount)
                                })
                            }
                            JSONObject().put("errorCode", 0).put("issues", arr)
                        }
                        resolveToJs(cbId, result)
                    } catch (e: Exception) {
                        rejectToJs(cbId, e.message)
                    }
                }

                // 제거됨: dbQuery·openChannelRoom·searchChannelRoom·getUserInfo·getStatusMobile — nx 미호출.

                // ── 팝업 ── (openNoteSendWindow alias 제거)
                "openWindow" -> appHandler.handleOpenWindow(params)
                "openMessageSendWindow" -> scope.launch { appHandler.handleOpenNoteSendWindow(params) }
                "getPopupContext" -> {
                    val ctx = pendingPopupContext ?: JSONObject()
                    pendingPopupContext = null
                    resolveToJs("getPopupContext", ctx)
                }

                // ── 채팅방 알림 ON/OFF ──
                "setRoomNotification" -> scope.launch {
                    val channelCode = paramStr(params, "channelCode")
                    val on = params["on"]?.toString()?.lowercase() != "false"
                    if (channelCode.isNotEmpty()) {
                        if (on) appConfig.unmuteChannel(channelCode)
                        else appConfig.muteChannel(channelCode)
                        Log.d(TAG, "setRoomNotification: channelCode=$channelCode on=$on")
                        resolveToJs(action, JSONObject().put("errorCode", 0))
                        notifyReact("channelReady")
                    } else {
                        resolveToJs(action, JSONObject().put("errorCode", 0))
                    }
                }

                // ── 채팅 내보내기 (stub) ──
                "exportChat" -> scope.launch {
                    Log.d(TAG, "$action: not yet implemented on Android, resolving OK")
                    resolveToJs(action, JSONObject().put("errorCode", 0))
                }

                // ── 미지원 기능 (모바일 stub) ──
                "makeCall", "transferCall" -> scope.launch {
                    Log.d(TAG, "$action: not supported on Android, resolving OK")
                    resolveToJs(action, JSONObject().put("errorCode", 0))
                }
                "requestRemoteControl" -> scope.launch {
                    Log.d(TAG, "requestRemoteControl: not supported on Android, resolving OK")
                    resolveToJs("requestRemoteControl", JSONObject().put("errorCode", 0))
                }
                "openSmsSendPage" -> scope.launch {
                    Log.d(TAG, "openSmsSendPage: not supported on Android, resolving OK")
                    resolveToJs("openSmsSendPage", JSONObject().put("errorCode", 0))
                }
                "setUserDisplayMode", "setUserSortMode" -> scope.launch {
                    Log.d(TAG, "$action: UI-only preference, resolving OK")
                    resolveToJs(action, JSONObject().put("errorCode", 0))
                }

                // ── 데스크탑(Win/Mac) 전용 액션 — 모바일에서는 무시 ──
                "windowDrag", "windowMinimize", "windowMaximize", "windowRestore", "windowResize",
                "mousedown", "mousemove" -> {
                    // no-op (모바일에는 윈도우 관리 개념 없음)
                }

                else -> {
                    Log.w(TAG, "unhandled action=$action")
                    rejectToJs(action, "Unhandled action: $action")
                }
            }
        }
    }

    // ══════════════════════════════════════
    // JS 통신 유틸리티 (BridgeContext 구현)
    // ══════════════════════════════════════

    override fun resolveToJs(action: String, data: JSONObject) {
        evalJs("window._${action}Resolve && window._${action}Resolve('${esc(data.toString())}')")
    }

    override fun resolveToJs(action: String, rawJsonStr: String) {
        evalJs("window._${action}Resolve && window._${action}Resolve('${esc(rawJsonStr)}')")
    }

    override fun resolveToJsRaw(action: String, rawJsonStr: String) {
        evalJs("window._${action}Resolve && window._${action}Resolve($rawJsonStr)")
    }

    override fun rejectToJs(action: String, errorMessage: String?) {
        val err = JSONObject().put("errorCode", -1).put("errorMessage", errorMessage ?: "Unknown error")
        evalJs("window._${action}Reject && window._${action}Reject('${esc(err.toString())}')")
    }

    /** React에 이벤트 알림 (NHM-61: 완료 이벤트는 보관) — 항상 메인 WebView */
    override fun notifyReact(event: String) {
        completedSyncs.add(event)
        val json = JSONObject().put("event", event).toString()
        evalJsMain("window.postMessage('${esc(json)}')")
    }

    /**
     * 재연결 시 이전 세션에서 pending 상태로 남은 sync 콜백을 즉시 reject.
     * Disconnect 감지 후 새 sync 콜백이 등록되기 전에 호출해야 한다.
     */
    fun flushStaleSyncCallbacks() {
        val errJson = esc("""{"errorCode":-1,"errorMessage":"reconnecting"}""")
        listOf("syncNoti", "syncMessage", "syncChannel", "syncBuddy", "syncThread").forEach { action ->
            evalJsMain(
                "(function(){var r=window._${action}Reject;" +
                "window._${action}Resolve=null;window._${action}Reject=null;" +
                "if(typeof r==='function')r('$errJson');})()"
            )
        }
    }

    /** React에 일회성 이벤트 전송 (completedSyncs에 추가 안함) — 항상 메인 WebView */
    override fun notifyReactOnce(event: String) {
        val json = JSONObject().put("event", event).toString()
        evalJsMain("window.postMessage('${esc(json)}')")
    }

    /** Push 이벤트 → 메인 WebView (+ 서브 WebView가 열려있으면 거기도) */
    fun forwardPushToReact(command: String, data: JSONObject) {
        // additional가 null이면 React에서 null.fileName 크래시 발생 방지
        if (data.has("additional") && data.isNull("additional")) {
            data.put("additional", JSONObject())
        } else if (data.has("additional") && !data.isNull("additional")) {
            val addObj = data.optJSONObject("additional")
            if (addObj != null) {
                data.put("additional", JSONObject(ChannelRepository.sanitizeAdditional(addObj.toString())))
            }
        }
        val pushMsg = JSONObject().put("event", "neoPush").put("command", command).put("data", data)
        val js = "window.postMessage('${esc(pushMsg.toString())}')"
        evalJsMain(js)
        if (activity.isSubWebViewOpen()) {
            val sub = activity.getSubWebView()
            sub.post { sub.evaluateJavascript(js, null) }
        }
    }

    /** resolve/reject 전달용 — 서브 WebView가 열려 있으면 서브로, 아니면 메인으로. */
    override fun evalJs(js: String) {
        val target = if (activity.isSubWebViewOpen()) activity.getSubWebView() else webView
        target.post { target.evaluateJavascript(js, null) }
    }

    override fun evalJsMain(js: String) {
        webView.post { webView.evaluateJavascript(js, null) }
    }

    override fun esc(s: String) = JsEscapeUtil.escapeForJs(s)

    // ══════════════════════════════════════
    // 공통 헬퍼 (BridgeContext 구현)
    // ══════════════════════════════════════

    override fun guardDbNotReady(action: String): Boolean {
        if (!dbProvider.isInitialized()) {
            Log.w(TAG, "$action: DB not initialized, returning empty")
            resolveToJs(action, JSONObject().put("errorCode", 0))
            return true
        }
        return false
    }

    override suspend fun handleRestForward(action: String, path: String, params: Map<String, Any?>) {
        try {
            val body = paramsToJson(params)
            val token = appConfig.getSavedToken()
            val result = withContext(Dispatchers.IO) {
                ApiClient.postJson(appConfig.getEndpointByPath(path), body, token)
            }
            resolveToJs(action, result)
        } catch (e: Exception) {
            rejectToJs(action, e.message)
        }
    }

    override suspend fun updateCrudOffset(commandName: String, response: JSONObject) {
        val eventId = response.optLong("eventId", 0L)
        if (eventId <= 0L) return
        try {
            when (commandName.lowercase()) {
                "sendmessage", "readmessage", "deletemessage", "retrievemessage" ->
                    dbProvider.getMessageDatabase().syncMetaDao()
                        .insert(SyncMetaEntity("messageEventOffset", eventId))
                "sendchat", "deletechat", "modchat", "reactionchat" ->
                    dbProvider.getChatDatabase().syncMetaDao()
                        .insert(SyncMetaEntity("chat_last_sync_time", eventId))
                "makechannel", "destroychannel", "removechannelmember", "removechannel",
                "modchannel", "setchannel", "joinconference",
                "togglevote", "closevote", "pinmessage", "unpinmessage" ->
                    dbProvider.getChatDatabase().syncMetaDao()
                        .insert(SyncMetaEntity("channel_last_sync_time", eventId))
                "readnoti", "deletenoti" ->
                    dbProvider.getNotiDatabase().syncMetaDao()
                        .insert(SyncMetaEntity("notiEventOffset", eventId))
            }
            Log.d(TAG, "updateCrudOffset: $commandName → eventId=$eventId")
        } catch (e: Exception) {
            Log.w(TAG, "updateCrudOffset error: ${e.message}")
        }
    }

    override suspend fun saveChannelLocally(channelCode: String, members: List<String>, type: String, channelName: String) {
        val now = System.currentTimeMillis()
        val state = if (members.size > 2) 1 else 0  // PRIVATE_CHAT=0, GROUP_CHAT=1
        dbProvider.getChatDatabase().channelDao()?.insert(
            ChannelEntity(channelCode = channelCode, channelType = type, channelName = channelName, state = state, lastChatDate = now)
        )
        // 방금 내가 만든 방: 멤버 registDate 를 0 으로 저장해 pre-join 필터가 걸리지 않도록 함.
        // device/server 시각 차로 sendDate < registDate 가 되는 race 를 회피. 실제 값은 syncChannel 에서 서버값으로 채워짐.
        for (uid in members) {
            dbProvider.getChatDatabase().channelMemberDao()?.insert(
                ChannelMemberEntity(channelCode = channelCode, userId = uid, registDate = 0L)
            )
        }
    }

    override fun subscribeUsersFromJson(jsonStr: String, arrayKey: String) {
        try {
            val json = JSONObject(jsonStr)
            val arr = json.optJSONArray(arrayKey) ?: return
            val ids = (0 until arr.length()).map { arr.getJSONObject(it).optString("userId", "") }.filter { it.isNotEmpty() }
            if (ids.isNotEmpty()) pubSubRepo.sendSubscribe(pubSubRepo.defaultTopic, "USER", ids)
        } catch (e: Exception) {
            Log.w(TAG, "subscribeUsersFromJson error: ${e.message}")
        }
    }

    // ══════════════════════════════════════
    // 파라미터 유틸리티 (BridgeContext 구현)
    // ══════════════════════════════════════

    override fun paramStr(params: Map<String, Any?>, key: String): String =
        params[key]?.toString() ?: ""

    override fun paramInt(params: Map<String, Any?>, key: String, default: Int): Int =
        (params[key] as? Number)?.toInt() ?: paramStr(params, key).toIntOrNull() ?: default

    override fun paramLong(params: Map<String, Any?>, key: String): Long? =
        (params[key] as? Number)?.toLong() ?: paramStr(params, key).toLongOrNull()

    override fun paramBool(params: Map<String, Any?>, key: String): Boolean {
        val v = params[key] ?: return false
        return v == true || v == 1 || v.toString() == "1" || v.toString() == "true"
    }

    override fun paramList(params: Map<String, Any?>, key: String): List<String> {
        val v = params[key] ?: return emptyList()
        if (v is JSONArray) return (0 until v.length()).map { v.getString(it) }
        if (v is List<*>) return v.filterIsInstance<String>()
        val str = v.toString()
        return if (str.startsWith("[")) {
            try { val arr = JSONArray(str); (0 until arr.length()).map { arr.getString(it) } }
            catch (_: Exception) { str.split(",").map { it.trim() }.filter { it.isNotEmpty() } }
        } else str.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    override fun paramsToJson(params: Map<String, Any?>): JSONObject {
        val json = JSONObject()
        for ((k, v) in params) {
            when (v) {
                is JSONObject -> json.put(k, v)
                is JSONArray -> json.put(k, v)
                null -> json.put(k, JSONObject.NULL)
                else -> json.put(k, v)
            }
        }
        return json
    }

    override fun paramsToJson(v: Any?): JSONObject {
        if (v is JSONObject) return v
        if (v is Map<*, *>) {
            val json = JSONObject()
            for ((k, val2) in v) json.put(k.toString(), val2)
            return json
        }
        return try { JSONObject(v.toString()) } catch (_: Exception) { JSONObject() }
    }
}
