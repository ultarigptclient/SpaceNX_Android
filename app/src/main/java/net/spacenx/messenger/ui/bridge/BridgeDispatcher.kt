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

    // NHM-61: sync 완료 이벤트 보관 (React 마운트 전 완료 대비)
    override val completedSyncs = mutableSetOf<String>()

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
                Log.d("Presence", "[5] PUBLISH→React: $json")
                val wrapped = """{"users":[$json]}"""
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

                // ── 조직도 + 내목록 ──
                "getOrgList", "getOrgSubList", "searchUsers", "openUserDetail",
                "syncBuddy", "addUserToMyList", "getMyPart"
                    -> scope.launch { orgHandler.handle(action, params) }

                // ── 채널 관리 ──
                "syncChannel", "getChannelList", "getChannelSummaries",
                "createChatRoom", "createGroupChatRoom", "openChannel",
                "addChannelMember", "removeChannelMember",
                "addChannelFavorite", "removeChannelFavorite", "createConference",
                "removeChannel", "findChannelByMembers", "getChannel"
                    -> scope.launch { channelActionHandler.handle(action, params) }

                // ── 채팅 메시지 ──
                "getChatList", "sendChat", "readChat", "deleteChat", "modChat", "toggleReaction",
                "addLocalSystemChat",
                "toggleVote", "closeVote", "pinMessage", "unpinMessage",
                "getUnreadCount"
                    -> scope.launch { chatHandler.handle(action, params) }
                // destroyChannel·forwardChat 는 nx 에서 apiPost 로 직접 호출 — dispatcher 경유 불필요 (제거됨).
                "typingChat" -> scope.launch { handleRestForward("typingChat", "/comm/typingchat", params) }

                // ── 쪽지 ──
                "sendMessage", "readMessage", "deleteMessage", "syncMessage",
                "fullSync", "loadMoreMessages", "getMessageDetail", "getMessageCounts",
                "retrieveMessage"
                    -> scope.launch { messageHandler.handle(action, params) }

                "searchMessageListByUser" -> scope.launch {
                    handleRestForward("searchMessageListByUser", "/comm/syncmessage", params)
                }

                // ── 파일 (chat / filebox 공통 — context 파라미터로 분기) ──
                "uploadFile", "pickFile", "downloadFile", "openFile", "relocateFiles",
                "fileUploadPause", "fileUploadResume", "fileUploadCancel",
                "fileDownloadPause", "fileDownloadResume", "fileDownloadCancel",
                "previewFile", "shareFile"
                    -> scope.launch { fileHandler.handle(action, params) }

                // ── 상태/프로필 ──
                "changeStatus" -> scope.launch {
                    val statusCode = paramStr(params, "statusCode").toIntOrNull() ?: 0
                    Log.d("Presence", "[1] changeStatus: userId=${appConfig.getSavedUserId()}, statusCode=$statusCode, restPriority=$PRESENCE_REST_PRIORITY")

                    suspend fun tryRest(): Boolean {
                        return try {
                            val body = paramsToJson(params)
                            val token = appConfig.getSavedToken()
                            val result = withContext(Dispatchers.IO) {
                                ApiClient.postJson(appConfig.getEndpointByPath("/status/setpresence"), body, token)
                            }
                            // 서버가 errorCode 없이 성공 응답하는 경우를 위해 default=0 (성공 가정)
                            if (result.optInt("errorCode", 0) != 0) {
                                Log.w("Presence", "[1] changeStatus REST rejected: errorCode=${result.optInt("errorCode", 0)}")
                                return false
                            }
                            resolveToJs("changeStatus", result)
                            Log.d("Presence", "[1] changeStatus via REST: statusCode=$statusCode")
                            true
                        } catch (e: Exception) {
                            Log.w("Presence", "[1] changeStatus REST failed: ${e.message}")
                            false
                        }
                    }

                    fun trySocket(): Boolean {
                        val sm = loginViewModel.sessionManager
                        return if (sm.hiCompletedDeferred.isCompleted && sm.loginSessionScope != null) {
                            val body = JSONObject().put("presence", statusCode).toString().toByteArray(Charsets.UTF_8)
                            sm.sendFrame(net.spacenx.messenger.data.remote.socket.codec.ProtocolCommand.SET_PRESENCE.code, body)
                            resolveToJs("changeStatus", JSONObject().put("errorCode", 0))
                            Log.d("Presence", "[1] changeStatus via socket: presence=$statusCode")
                            true
                        } else {
                            Log.w("Presence", "[1] changeStatus socket not ready")
                            false
                        }
                    }

                    val success = if (PRESENCE_REST_PRIORITY) {
                        tryRest() || trySocket()
                    } else {
                        trySocket() || tryRest()
                    }
                    if (success) {
                        // 본인 상태 저장 (앱 재시작 후에도 유지) — 성공 시에만 저장
                        appConfig.saveMyStatusCode(statusCode)
                        // 본인 상태 즉시 반영 — 서버는 본인 자신에게 ICON_EVENT를 보내지 않으므로 직접 호출
                        val userId = appConfig.getSavedUserId() ?: ""
                        if (userId.isNotEmpty()) {
                            val presenceJson = """{"users":[{"userId":"$userId","icon":$statusCode}]}"""
                            Log.d("Presence", "[2] self→React: $presenceJson")
                            evalJs("window._onPresenceUpdate && window._onPresenceUpdate('${esc(presenceJson)}')")
                        }
                    } else {
                        rejectToJs("changeStatus", "both REST and socket failed")
                    }
                }
                "changeStatusMessage" -> scope.launch { handleRestForward("changeStatusMessage", "/status/setpresence", params) }
                "setNick" -> scope.launch {
                    val nick = paramStr(params, "nick")
                    try {
                        val body = paramsToJson(params)
                        val token = appConfig.getSavedToken()
                        val result = withContext(Dispatchers.IO) {
                            ApiClient.postJson(appConfig.getEndpointByPath("/nick/setnick"), body, token)
                        }
                        resolveToJs("setNick", result)
                        // 서버는 본인에게 NICK PUBLISH를 보내지 않으므로 직접 반영
                        if (nick.isNotEmpty()) appConfig.saveMyNick(nick)
                        val userId = appConfig.getSavedUserId() ?: ""
                        if (nick.isNotEmpty() && userId.isNotEmpty()) {
                            val nickJson = """{"users":[{"userId":"$userId","command":"Nick","nick":"${esc(nick)}"}]}"""
                            Log.d(TAG, "setNick self→React: $nickJson")
                            evalJs("window._onPresenceUpdate && window._onPresenceUpdate('${esc(nickJson)}')")
                        }
                    } catch (e: Exception) {
                        rejectToJs("setNick", e.message)
                    }
                }
                "updateProfile" -> scope.launch { handleRestForward("updateProfile", "/org/updateprofile", params) }
                "uploadProfilePhoto" -> activity.lifecycleScope.launch {
                    Log.d(TAG, "uploadProfilePhoto params: $params")
                    try {
                        val fileObj = params["file"]
                        val fileUrl = when (fileObj) {
                            is JSONObject -> fileObj.optString("url", "")
                            is Map<*, *> -> fileObj["url"]?.toString() ?: ""
                            is String -> try { JSONObject(fileObj).optString("url", "") } catch (_: Exception) { "" }
                            else -> ""
                        }
                        val userId = appConfig.getSavedUserId() ?: ""
                        if (fileUrl.isEmpty() || userId.isEmpty()) {
                            rejectToJs("uploadProfilePhoto", "Missing fileUrl or userId")
                            return@launch
                        }
                        // pickFile로 업로드된 파일을 서버에서 다운로드 → /api/media/photo/upload로 multipart 재전송
                        val token = appConfig.getSavedToken()
                        val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val origin = appConfig.getRestBaseUrl().replace(Regex("/api$"), "")
                            val fullUrl = if (fileUrl.startsWith("http")) fileUrl else "$origin$fileUrl"
                            Log.d(TAG, "uploadProfilePhoto: downloading from $fullUrl")
                            val imageBytes = ApiClient.downloadBytes(fullUrl, token)
                            Log.d(TAG, "uploadProfilePhoto: downloaded ${imageBytes.size} bytes, uploading to photo/upload")
                            val photoUploadUrl = appConfig.getEndpointByPath("/media/photo/upload")
                            ApiClient.uploadProfilePhoto(photoUploadUrl, imageBytes, userId, token)
                        }
                        Log.d(TAG, "uploadProfilePhoto response: $result")
                        // 서버는 본인에게 PHOTO PUBLISH를 보내지 않으므로 직접 반영
                        try {
                            val photoUrl = result.optString("photoUrl", "")
                            val photoVersion = result.optLong("photoVersion", 0L)
                            if (photoUrl.isNotEmpty() && photoVersion > 0) {
                                // photoVersion을 쿼리 파라미터로 붙여 브라우저 이미지 캐시 무효화
                                val cacheBustedUrl = "$photoUrl?v=$photoVersion"
                                // resolveToJs에도 버전 URL 포함 (React promise 핸들러에서 캐시 없이 로드)
                                result.put("photoUrl", cacheBustedUrl)
                                // 앱 재시작 후 복원을 위해 로컬 저장
                                appConfig.saveMyPhotoVersion(photoVersion)
                            }
                        } catch (_: Exception) {}
                        resolveToJs("uploadProfilePhoto", result)
                        // _onPresenceUpdate로도 동일한 URL 전달
                        try {
                            val photoUrl = result.optString("photoUrl", "")
                            val photoVersion = result.optLong("photoVersion", 0L)
                            if (photoUrl.isNotEmpty() && userId.isNotEmpty()) {
                                val photoJson = """{"users":[{"userId":"$userId","command":"Photo","photoUrl":"${esc(photoUrl)}","photoVersion":$photoVersion}]}"""
                                Log.d(TAG, "uploadProfilePhoto self→React: $photoJson")
                                evalJs("window._onPresenceUpdate && window._onPresenceUpdate('${esc(photoJson)}')")
                            }
                        } catch (_: Exception) {}
                    } catch (e: Exception) {
                        Log.e(TAG, "uploadProfilePhoto error: ${e.message}", e)
                        rejectToJs("uploadProfilePhoto", e.message)
                    }
                }

                // ── 알림 ── (deleteNoti 는 nx 미호출로 제거)
                "syncNoti", "loadMoreNotis", "getNotiCounts", "readNoti"
                    -> scope.launch { notiHandler.handle(action, params) }

                // ── 유저 구독 (Presence REST) ──
                "subscribeUsers" -> scope.launch { handleRestForward("subscribeUsers", "/status/subscribe", params) }

                // ── 앱 정보 / 사용자 설정 ──
                "getAppInfo" -> appHandler.handleGetAppInfo()
                "getUserConfig" -> scope.launch { appHandler.handleGetUserConfig(params) }
                "setUserConfig" -> scope.launch { appHandler.handleSetUserConfig(params) }

                // ── 회의 ──
                "joinConference" -> scope.launch { channelActionHandler.handle(action, params) }
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
                        withContext(Dispatchers.IO) { projectRepo.syncThread() }
                        val limit = paramInt(params, "limit", 50)
                        val offset = paramInt(params, "offset", 0)
                        // Flutter와 동일: {errorCode:0, events:[{eventType, threadCode, chatCode, ...}], hasMore}
                        val result = withContext(Dispatchers.IO) {
                            projectRepo.getAllThreadsAsEvents(limit, offset)
                        }
                        resolveToJs("syncThread", result)
                    } catch (e: Exception) {
                        rejectToJs("syncThread", e.message)
                    }
                }

                // ── 범용 REST ──
                "apiPost", "neoSend", "httpRequest"
                    -> scope.launch { neoSendHandler.handle(action, params) }

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
                "windowDrag", "windowMinimize", "windowMaximize", "windowRestore",
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

    override suspend fun saveChannelLocally(channelCode: String, members: List<String>, type: String) {
        val now = System.currentTimeMillis()
        dbProvider.getChatDatabase().channelDao()?.insert(
            ChannelEntity(channelCode = channelCode, channelType = type, lastChatDate = now)
        )
        for (uid in members) {
            dbProvider.getChatDatabase().channelMemberDao()?.insert(
                ChannelMemberEntity(channelCode = channelCode, userId = uid, registDate = now)
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
