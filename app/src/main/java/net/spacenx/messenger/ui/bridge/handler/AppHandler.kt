package net.spacenx.messenger.ui.bridge.handler

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.spacenx.messenger.data.remote.api.ApiClient
import net.spacenx.messenger.data.remote.socket.codec.ProtocolCommand
import net.spacenx.messenger.data.repository.ChannelRepository
import net.spacenx.messenger.data.repository.PubSubRepository
import net.spacenx.messenger.ui.bridge.BridgeContext
import net.spacenx.messenger.ui.bridge.BridgeDispatcher
import org.json.JSONArray
import org.json.JSONObject

class AppHandler(
    private val ctx: BridgeContext,
    private val channelRepo: ChannelRepository,
    private val pubSubRepo: PubSubRepository
) {
    companion object {
        private const val TAG = "AppHandler"
    }

    fun handleGetClientState() {
        val needLogin = ctx.activity.needLogin()

        if (!needLogin && ctx.activity.isAutoLogin) {
            Log.d(TAG, "getClientState: autoLogin in progress, responding needLogin=false")
            val result = JSONObject().apply {
                put("needPrivacy", !ctx.activity.privacyPolicy())
                put("needPermission", ctx.activity.needPermission())
                put("needLogin", false)
            }
            ctx.resolveToJs("getClientState", result)
            return
        }

        if (needLogin) {
            // 토큰 없음 → localStorage 인증 캐시 제거 (이전 세션 캐시로 메인화면이 뜨는 것 방지)
            Log.d(TAG, "getClientState: needLogin=true, clearing localStorage auth cache")
            ctx.evalJs("try{localStorage.removeItem('nx_auth');localStorage.removeItem('isLoggedIn');localStorage.removeItem('currentUser');}catch(e){}")
        }

        val result = JSONObject().apply {
            put("needPrivacy", !ctx.activity.privacyPolicy())
            put("needPermission", ctx.activity.needPermission())
            put("needLogin", needLogin)
        }
        ctx.resolveToJs("getClientState", result)

        if (!needLogin) {
            val userId = ctx.appConfig.getSavedUserId() ?: ""
            if (userId.isNotEmpty()) {
                Log.d(TAG, "getClientState: needLogin=false, triggering auto reconnect for $userId")
                ctx.activity.triggerAutoReconnect(userId)
            }
        }
    }

    suspend fun handleSaveCredential(params: Map<String, Any?>) {
        try {
            val userId = ctx.paramStr(params, "userId")
            val password = ctx.paramStr(params, "password")
            val savePassword = ctx.paramBool(params, "savePassword")
            val autoLogin = ctx.paramBool(params, "autoLogin")
            ctx.appConfig.saveCredential(userId, password, savePassword, autoLogin)
            ctx.resolveToJs("saveCredential", JSONObject())
        } catch (e: Exception) {
            ctx.rejectToJs("saveCredential", e.message)
        }
    }

    fun handleGetAppInfo() {
        try {
            val pm = ctx.activity.packageManager
            val pi = pm.getPackageInfo(ctx.activity.packageName, 0)
            val result = JSONObject().apply {
                put("errorCode", 0)
                put("appVersion", pi.versionName ?: "1.0")
                put("platform", "Android")
                put("osVersion", android.os.Build.VERSION.RELEASE)
                put("deviceModel", android.os.Build.MODEL)
                put("packageName", ctx.activity.packageName)
                put("features", JSONArray())
            }
            ctx.resolveToJs("getAppInfo", result)
        } catch (e: Exception) {
            ctx.rejectToJs("getAppInfo", e.message)
        }
    }

    suspend fun handleGetUserConfig(params: Map<String, Any?>) {
        try {
            // cbId 사용: rapid-fire 시 각 호출이 자신의 콜백 슬롯으로 응답
            val cbId = ctx.paramStr(params, "cbId").ifEmpty { "getUserConfig" }
            val userId = ctx.appConfig.getSavedUserId() ?: ""

            // 로컬 캐시 즉시 반환 (UI 블로킹 방지)
            val cached = withContext(Dispatchers.IO) {
                try {
                    val settings = ctx.dbProvider.getSettingDatabase().settingDao().getAll()
                    val configs = JSONObject()
                    for (s in settings) configs.put(s.key, s.value)
                    JSONObject().put("errorCode", 0).put("configs", configs)
                } catch (_: Exception) {
                    JSONObject().put("errorCode", 0).put("configs", JSONObject())
                }
            }
            val cachedSize = cached.optJSONObject("configs")?.length() ?: 0
            Log.d(TAG, "getUserConfig: returning local cache ($cachedSize keys)")
            ctx.resolveToJs(cbId, cached)

            // 백그라운드에서 서버 동기화 → 로컬 캐시 갱신 후 push event (로그인 전이면 skip)
            if (!ctx.dbProvider.isInitialized()) return
            ctx.scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val token = ctx.loginViewModel.sessionManager.jwtToken
                        val url = ctx.appConfig.getEndpointByPath("/config/get")
                        ApiClient.postJson(url, JSONObject().put("userId", userId), token)
                    }
                    val configs = result.optJSONObject("configs")
                    if (configs != null && configs.length() > 0) {
                        withContext(Dispatchers.IO) {
                            val dao = ctx.dbProvider.getSettingDatabase().settingDao()
                            val keys = configs.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                val value = configs.optString(key, "")
                                dao.insert(net.spacenx.messenger.data.local.entity.SettingEntity(key, value))
                            }
                        }
                        // 서버에서 새 설정을 받았으면 push event로 React에 알림
                        val event = JSONObject().put("event", "configUpdated").put("configs", configs).toString()
                        ctx.evalJs("window.postMessage('${ctx.esc(event)}')")
                        Log.d(TAG, "getUserConfig: server sync complete, pushed configUpdated (${configs.length()} keys)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "getUserConfig background sync failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            ctx.rejectToJs("getUserConfig", e.message)
        }
    }

    suspend fun handleSetUserConfig(params: Map<String, Any?>) {
        try {
            val userId = ctx.appConfig.getSavedUserId() ?: ""
            val key = ctx.paramStr(params, "key")
            val value = ctx.paramStr(params, "value")
            val result = withContext(Dispatchers.IO) {
                val token = ctx.loginViewModel.sessionManager.jwtToken
                val url = ctx.appConfig.getEndpointByPath("/config/set")
                // 로컬 캐시도 갱신
                ctx.dbProvider.getSettingDatabase().settingDao()
                    .insert(net.spacenx.messenger.data.local.entity.SettingEntity(key, value))
                ApiClient.postJson(url, JSONObject().apply {
                    put("userId", userId)
                    put("key", key)
                    put("value", value)
                }, token)
            }
            ctx.resolveToJs("setUserConfig", result)
            // FRONTEND_VERSION/SKIN 변경 시 SPA URL 업데이트 후 WebView 재로딩 (Flutter 동일 패턴)
            if ((key == "FRONTEND_VERSION" || key == "FRONTEND_SKIN") && value.isNotEmpty()) {
                ctx.appConfig.updateConfigCache(mapOf(key to value))
                ctx.activity.reloadSpa()
                Log.d(TAG, "setUserConfig: $key=$value → reloading SPA")
            }
        } catch (e: Exception) {
            ctx.rejectToJs("setUserConfig", e.message)
        }
    }

    fun handleBroadcastTheme(params: Map<String, Any?>) {
        val themeId = ctx.paramStr(params, "themeId").ifEmpty { "nx-light" }
        ctx.appConfig.saveThemeId(themeId)
        ctx.resolveToJs("broadcastTheme", JSONObject())
    }

    fun handleGetSyncStatus() {
        Log.d(TAG, "getSyncStatus: completed=${ctx.completedSyncs}")
        ctx.resolveToJs("getSyncStatus", JSONObject()
            .put("completed", JSONArray().apply { ctx.completedSyncs.forEach { put(it) } }))
        // WebView 새로고침 후 React가 postMessage 이벤트를 다시 받아야 데이터 로딩이 트리거됨.
        // completedSyncs를 postMessage로 재전송 (notifyReactOnce: completedSyncs에 중복 추가 안 함)
        if (ctx.completedSyncs.isNotEmpty()) {
            val snapshot = ctx.completedSyncs.toList()
            Log.d(TAG, "getSyncStatus: re-emitting ${snapshot.size} completed events to React")
            snapshot.forEach { event -> ctx.notifyReactOnce(event) }
        }
    }

    // ── QUIC 설정 ──

    fun handleGetQuicSetting() {
        val result = JSONObject().apply {
            put("errorCode", 0)
            put("enabled", ctx.appConfig.isQuicEnabled())
            put("port", ctx.appConfig.getQuicPort())
        }
        ctx.resolveToJs("getQuicSetting", result)
    }

    fun handleSetQuicSetting(params: Map<String, Any?>) {
        val enabled = params["enabled"] as? Boolean ?: false
        ctx.appConfig.setQuicEnabled(enabled)
        Log.d(TAG, "setQuicSetting: enabled=$enabled")
        ctx.resolveToJs("setQuicSetting", JSONObject().put("errorCode", 0))
    }

    fun handleOpenWindow(params: Map<String, Any?>) {
        val type = ctx.paramStr(params, "type")
        val channelCode = ctx.paramStr(params, "channelCode")
        val displayName = ctx.paramStr(params, "displayName")
        val members = ctx.paramStr(params, "members")
        Log.d(TAG, "openWindow type=$type channelCode=$channelCode displayName=$displayName members=$members")

        when (type) {
            "channel" -> {
                val qp = StringBuilder()
                if (channelCode.isNotEmpty()) qp.append("channelCode=$channelCode")
                if (displayName.isNotEmpty()) {
                    if (qp.isNotEmpty()) qp.append("&")
                    qp.append("name=${android.net.Uri.encode(displayName)}")
                }
                if (members.isNotEmpty()) {
                    if (qp.isNotEmpty()) qp.append("&")
                    qp.append("members=$members")
                }
                val url = "${ctx.activity.getBaseUrl()}chat.html?$qp"
                Log.d(TAG, "openWindow: opening subWebView $url")
                ctx.activity.openSubWebView(url)
            }
            "message" -> {
                val queryParams = mutableListOf<String>()
                for ((k, v) in params) {
                    if (k != "type" && v != null) queryParams.add("$k=${android.net.Uri.encode(v.toString())}")
                }
                val url = "${ctx.activity.getBaseUrl()}message.html?${queryParams.joinToString("&")}"
                Log.d(TAG, "openWindow message: opening subWebView $url")
                ctx.activity.openSubWebView(url)
            }
            else -> Log.w(TAG, "openWindow: unsupported type=$type")
        }
    }

    suspend fun handleOpenNoteSendWindow(params: Map<String, Any?>) {
        val userIds = params["userIds"]
        val idList: List<String> = when (userIds) {
            is List<*> -> userIds.filterIsInstance<String>()
            is String -> {
                // JSON 배열 문자열 또는 단순 id 처리
                try {
                    val arr = org.json.JSONArray(userIds)
                    (0 until arr.length()).map { arr.getString(it) }
                } catch (_: Exception) {
                    if (userIds.isNotBlank()) listOf(userIds) else emptyList()
                }
            }
            else -> emptyList()
        }

        // 1) 프론트엔드가 userNames를 이미 넘겨준 경우 우선 사용 (Windows 동일 패턴)
        val existingNames = params["userNames"]
        val frontendNames: List<String> = when (existingNames) {
            is List<*> -> existingNames.filterIsInstance<String>()
            is String -> try {
                val arr = org.json.JSONArray(existingNames)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (_: Exception) {
                if (existingNames.isNotBlank()) listOf(existingNames) else emptyList()
            }
            else -> emptyList()
        }

        // 2) 프론트엔드 이름이 유효하면 그대로 사용, 아니면 로컬 DB에서 조회
        val names = if (frontendNames.size == idList.size && frontendNames.any { it.isNotBlank() }) {
            Log.d(TAG, "openNoteSendWindow: using frontend-provided names=$frontendNames")
            frontendNames
        } else {
            // subWebView 열기 전에 이름 미리 조회 (bridge 왕복 없이 즉시 표시)
            val resolved = idList.map { ctx.userNameCache.resolve(it) }
            Log.d(TAG, "openNoteSendWindow: resolved names from DB=$resolved (frontend names=$frontendNames)")
            resolved
        }

        // receivers 배열 구성 (Windows C++ 동일 패턴 — React가 receivers 있으면 즉시 표시)
        val receiversArr = org.json.JSONArray()
        for (i in idList.indices) {
            receiversArr.put(org.json.JSONObject().apply {
                put("userId", idList[i])
                put("userName", if (i < names.size) names[i] else idList[i])
            })
        }
        // getPopupContext에서 반환할 데이터 설정
        val popupCtx = org.json.JSONObject().apply {
            put("type", "messageCompose")
            put("page", "message")
            put("mode", "new")
            put("userIds", org.json.JSONArray(idList))
            put("receivers", receiversArr)
            put("loginUserId", ctx.appConfig.getSavedUserId() ?: "")
        }
        (ctx as? net.spacenx.messenger.ui.bridge.BridgeDispatcher)?.pendingPopupContext = popupCtx
        ctx.activity.pendingSubWebViewContext = popupCtx.toString()

        val userIdsJson = org.json.JSONArray(idList).toString()
        val encodedIds = android.net.Uri.encode(userIdsJson)
        val url = "${ctx.activity.getBaseUrl()}message.html?userIds=$encodedIds"
        Log.d(TAG, "openNoteSendWindow: opening subWebView $url (receivers=$receiversArr)")
        ctx.activity.openSubWebView(url)
    }

    suspend fun handleDbQuery(params: Map<String, Any?>) {
        try {
            val query = ctx.paramStr(params, "query")
            val result = JSONObject()
            when (query) {
                "allSyncMeta" -> {
                    result.put("channelEventOffset", ctx.dbProvider.getChatDatabase().syncMetaDao()?.getValue("channelEventOffset") ?: 0)
                    result.put("chatEventOffset", ctx.dbProvider.getChatDatabase().syncMetaDao()?.getValue("chatEventOffset") ?: 0)
                    result.put("messageEventOffset", ctx.dbProvider.getMessageDatabase().syncMetaDao().getValue("messageEventOffset") ?: 0)
                    result.put("notiEventOffset", ctx.dbProvider.getNotiDatabase().syncMetaDao().getValue("notiEventOffset") ?: 0)
                }
                "channelCount" -> result.put("count", ctx.dbProvider.getChatDatabase().channelDao()?.getAll()?.size ?: 0)
                "userCount" -> result.put("count", ctx.dbProvider.getOrgDatabase().userDao().getAll().size)
                else -> result.put("error", "Unknown query: $query")
            }
            ctx.resolveToJs("dbQuery", result.put("errorCode", 0))
        } catch (e: Exception) {
            ctx.rejectToJs("dbQuery", e.message)
        }
    }

    suspend fun handleChangeStatus(params: Map<String, Any?>) {
        val statusCode = ctx.paramStr(params, "statusCode").toIntOrNull() ?: 0
        Log.d("Presence", "[1] changeStatus: userId=${ctx.appConfig.getSavedUserId()}, statusCode=$statusCode, restPriority=${BridgeDispatcher.PRESENCE_REST_PRIORITY}")

        suspend fun tryRest(): Boolean {
            return try {
                // 서버는 {userId, presence} 필드를 요구. JS가 보내는 statusCode 키를 그대로 넘기면 NPE 발생.
                val body = JSONObject().apply {
                    put("userId", ctx.appConfig.getSavedUserId() ?: "")
                    put("presence", statusCode)
                }
                val token = ctx.appConfig.getSavedToken()
                val result = withContext(Dispatchers.IO) {
                    ApiClient.postJson(ctx.appConfig.getEndpointByPath("/status/setpresence"), body, token)
                }
                if (result.optInt("errorCode", 0) != 0) {
                    Log.w("Presence", "[1] changeStatus REST rejected: errorCode=${result.optInt("errorCode", 0)}")
                    return false
                }
                ctx.resolveToJs("changeStatus", result)
                Log.d("Presence", "[1] changeStatus via REST: statusCode=$statusCode")
                true
            } catch (e: Exception) {
                Log.w("Presence", "[1] changeStatus REST failed: ${e.message}")
                false
            }
        }

        fun trySocket(): Boolean {
            val sm = ctx.loginViewModel.sessionManager
            return if (sm.hiCompletedDeferred.isCompleted && sm.loginSessionScope != null) {
                val body = JSONObject().put("presence", statusCode).toString().toByteArray(Charsets.UTF_8)
                sm.sendFrame(ProtocolCommand.SET_PRESENCE.code, body)
                ctx.resolveToJs("changeStatus", JSONObject().put("errorCode", 0))
                Log.d("Presence", "[1] changeStatus via socket: presence=$statusCode")
                true
            } else {
                Log.w("Presence", "[1] changeStatus socket not ready")
                false
            }
        }

        val success = if (BridgeDispatcher.PRESENCE_REST_PRIORITY) tryRest() || trySocket()
                      else trySocket() || tryRest()

        if (success) {
            ctx.appConfig.saveMyStatusCode(statusCode)
            val userId = ctx.appConfig.getSavedUserId() ?: ""
            if (userId.isNotEmpty()) {
                // neoPush('Icon') 경로 사용 → _mergePresenceGlobal → mobileIcon 보존
                val iconMsg = """{"event":"neoPush","command":"Icon","data":{"userId":"$userId","icon":$statusCode}}"""
                Log.d("Presence", "[2] self→React Icon: userId=$userId icon=$statusCode")
                ctx.evalJs("window.postMessage('${ctx.esc(iconMsg)}')")
                val nick = ctx.appConfig.getMyNick()
                if (nick.isNotEmpty()) {
                    val nickMsg = """{"event":"neoPush","command":"Nick","data":{"userId":"$userId","nick":"${ctx.esc(nick)}"}}"""
                    ctx.evalJs("window.postMessage('${ctx.esc(nickMsg)}')")
                }
            }
        } else {
            ctx.rejectToJs("changeStatus", "both REST and socket failed")
        }
    }

    suspend fun handleSetNick(params: Map<String, Any?>) {
        val nick = ctx.paramStr(params, "nick")
        try {
            val userId = ctx.appConfig.getSavedUserId() ?: ""
            // 서버가 브라우저와 동일하게 {userId, nick}만 받도록 명시적으로 구성
            // (paramsToJson은 _callbackId 등 JS 내부 필드까지 포함해 서버가 업데이트를 조용히 무시함)
            val body = JSONObject().apply {
                put("userId", userId)
                put("nick", nick)
            }
            val token = ctx.appConfig.getSavedToken()
            Log.d(TAG, "setNick request: $body")
            val result = withContext(Dispatchers.IO) {
                ApiClient.postJson(ctx.appConfig.getEndpointByPath("/nick/setnick"), body, token)
            }
            Log.d(TAG, "setNick response: $result")
            val errorCode = result.optInt("errorCode", -1)
            if (errorCode != 0) {
                Log.w(TAG, "setNick REST rejected: errorCode=$errorCode")
                ctx.rejectToJs("setNick", "errorCode=$errorCode")
                return
            }
            ctx.resolveToJs("setNick", result)
            if (nick.isNotEmpty()) ctx.appConfig.saveMyNick(nick)
            if (nick.isNotEmpty() && userId.isNotEmpty()) {
                val nickMsg = """{"event":"neoPush","command":"Nick","data":{"userId":"$userId","nick":"${ctx.esc(nick)}"}}"""
                Log.d(TAG, "setNick self→React: $nickMsg")
                ctx.evalJs("window.postMessage('${ctx.esc(nickMsg)}')")
            }
        } catch (e: Exception) {
            ctx.rejectToJs("setNick", e.message)
        }
    }

    suspend fun handleUploadProfilePhoto(params: Map<String, Any?>) {
        Log.d(TAG, "uploadProfilePhoto params: $params")
        try {
            val fileObj = params["file"]
            val nativeTempId = when (fileObj) {
                is JSONObject -> fileObj.optString("nativeTempId", fileObj.optString("_nativeTempId", ""))
                is Map<*, *> -> fileObj["nativeTempId"]?.toString() ?: fileObj["_nativeTempId"]?.toString() ?: ""
                is String -> try { JSONObject(fileObj).let { it.optString("nativeTempId", it.optString("_nativeTempId", "")) } } catch (_: Exception) { "" }
                else -> ""
            }
            val fileUrl = when (fileObj) {
                is JSONObject -> fileObj.optString("url", "")
                is Map<*, *> -> fileObj["url"]?.toString() ?: ""
                is String -> try { JSONObject(fileObj).optString("url", "") } catch (_: Exception) { "" }
                else -> ""
            }
            val userId = ctx.appConfig.getSavedUserId() ?: ""
            if (userId.isEmpty()) {
                ctx.rejectToJs("uploadProfilePhoto", "Missing userId")
                return
            }
            val token = ctx.appConfig.getSavedToken()
            val result = withContext(Dispatchers.IO) {
                val photoUploadUrl = ctx.appConfig.getEndpointByPath("/media/photo/upload")
                if (nativeTempId.isNotEmpty()) {
                    val tempFile = java.io.File(java.io.File(ctx.activity.cacheDir, "pickedFiles"), nativeTempId)
                    if (!tempFile.exists()) throw Exception("Temp file not found: $nativeTempId")
                    Log.d(TAG, "uploadProfilePhoto: uploading from tempFile ${tempFile.length()}B")
                    val imageBytes = tempFile.readBytes()
                    try { tempFile.delete() } catch (_: Exception) {}
                    ApiClient.uploadProfilePhoto(photoUploadUrl, imageBytes, userId, token)
                } else if (fileUrl.isNotEmpty()) {
                    val origin = ctx.appConfig.getRestBaseUrl().replace(Regex("/api$"), "")
                    val fullUrl = if (fileUrl.startsWith("http")) fileUrl else "$origin$fileUrl"
                    Log.d(TAG, "uploadProfilePhoto: downloading from $fullUrl")
                    val imageBytes = ApiClient.downloadBytes(fullUrl, token)
                    Log.d(TAG, "uploadProfilePhoto: downloaded ${imageBytes.size} bytes, uploading")
                    ApiClient.uploadProfilePhoto(photoUploadUrl, imageBytes, userId, token)
                } else {
                    throw Exception("Missing nativeTempId or fileUrl")
                }
            }
            Log.d(TAG, "uploadProfilePhoto response: $result")
            try {
                val photoUrl = result.optString("photoUrl", "")
                val photoVersion = result.optLong("photoVersion", 0L)
                if (photoUrl.isNotEmpty() && photoVersion > 0) {
                    val cacheBustedUrl = "$photoUrl?v=$photoVersion"
                    result.put("photoUrl", cacheBustedUrl)
                    ctx.appConfig.saveMyPhotoVersion(photoVersion)
                }
            } catch (_: Exception) {}
            ctx.resolveToJs("uploadProfilePhoto", result)
            try {
                val photoUrl = result.optString("photoUrl", "")
                val photoVersion = result.optLong("photoVersion", 0L)
                if (photoUrl.isNotEmpty() && userId.isNotEmpty()) {
                    val photoJson = """{"users":[{"userId":"$userId","command":"Photo","photoUrl":"${ctx.esc(photoUrl)}","photoVersion":$photoVersion}]}"""
                    Log.d(TAG, "uploadProfilePhoto self→React: $photoJson")
                    ctx.evalJs("window._onPresenceUpdate && window._onPresenceUpdate('${ctx.esc(photoJson)}')")
                }
            } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "uploadProfilePhoto error: ${e.message}", e)
            ctx.rejectToJs("uploadProfilePhoto", e.message)
        }
    }

    suspend fun handleSyncConfig(params: Map<String, Any?>) {
        try {
            val userId = ctx.appConfig.getSavedUserId() ?: ctx.paramStr(params, "userId")
            val lastSyncTime = ctx.paramLong(params, "lastSyncTime") ?: 0L
            val body = JSONObject().put("userId", userId).put("lastSyncTime", lastSyncTime)
            val token = ctx.appConfig.getSavedToken()
            val result = withContext(Dispatchers.IO) {
                ApiClient.postJson(ctx.appConfig.getEndpointByPath("/auth/syncconfig"), body, token)
            }
            // configs 캐시 업데이트
            val configs = result.optJSONObject("configs")
            if (configs != null && configs.length() > 0) {
                val configMap = mutableMapOf<String, String>()
                val keys = configs.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    configMap[key] = configs.optString(key, "")
                }
                ctx.appConfig.updateConfigCache(configMap)
                // FRONTEND_VERSION / FRONTEND_SKIN 변경 시 SPA 재로딩
                val fv = configMap["FRONTEND_VERSION"]
                val fs = configMap["FRONTEND_SKIN"]
                if (!fv.isNullOrEmpty() || !fs.isNullOrEmpty()) {
                    ctx.activity.reloadSpa()
                    Log.d(TAG, "syncConfig: SPA reload triggered (FRONTEND_VERSION=$fv FRONTEND_SKIN=$fs)")
                }
            }
            // 서버가 새 JWT 발급한 경우 갱신
            val newToken = result.optString("accessToken", "")
            if (newToken.isNotEmpty()) {
                ctx.loginViewModel.sessionManager.jwtToken = newToken
                Log.d(TAG, "syncConfig: JWT token updated")
            }
            ctx.resolveToJs("syncConfig", result)
        } catch (e: Exception) {
            ctx.rejectToJs("syncConfig", e.message)
        }
    }

    suspend fun handleSendMessageToRoomMembers(params: Map<String, Any?>) {
        val channelCode = ctx.paramStr(params, "channelCode")
        val myUserId = ctx.appConfig.getSavedUserId() ?: ""
        try {
            val members = withContext(Dispatchers.IO) {
                if (channelCode.isEmpty()) emptyList()
                else ctx.dbProvider.getChatDatabase().channelMemberDao()
                    .getActiveMembersByChannel(channelCode)
                    .map { it.userId }
                    .filter { it != myUserId }
            }
            if (members.isEmpty()) {
                ctx.rejectToJs("sendMessageToRoomMembers", "no members in channel $channelCode")
                return
            }
            Log.d(TAG, "sendMessageToRoomMembers: channelCode=$channelCode, ${members.size} recipients")
            handleOpenNoteSendWindow(mapOf("userIds" to members))
        } catch (e: Exception) {
            ctx.rejectToJs("sendMessageToRoomMembers", e.message)
        }
    }

    suspend fun handleLegacyOpenChannelRoom(params: Map<String, Any?>) {
        try {
            val raw = ctx.paramStr(params, "channelCode")
            val json = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
            val channelCode = json.optString("channelCode", raw)
            val targetUserId = json.optString("userId", "")

            if (targetUserId.isNotEmpty()) {
                val sendUserId = ctx.appConfig.getSavedUserId() ?: ""
                val result = withContext(Dispatchers.IO) {
                    channelRepo.makeChannelWithUserName(channelCode, sendUserId, targetUserId)
                }
                ctx.resolveToJsRaw("openChannelRoom", result)
            } else if (channelCode.isNotEmpty()) {
                val result = withContext(Dispatchers.IO) { channelRepo.openChannelRoom(channelCode) }
                ctx.resolveToJsRaw("openChannelRoom", result)
            }
        } catch (e: Exception) {
            ctx.rejectToJs("openChannelRoom", e.message)
        }
    }
}
