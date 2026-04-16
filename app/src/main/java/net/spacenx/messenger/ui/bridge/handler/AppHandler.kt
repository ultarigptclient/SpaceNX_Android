package net.spacenx.messenger.ui.bridge.handler

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.spacenx.messenger.data.remote.api.ApiClient
import net.spacenx.messenger.data.repository.ChannelRepository
import net.spacenx.messenger.data.repository.PubSubRepository
import net.spacenx.messenger.ui.bridge.BridgeContext
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
            Log.d(TAG, "getClientState: autoLogin in progress, rejecting (Flutter pattern)")
            ctx.rejectToJs("getClientState", "autoLogin in progress")
            return
        }

        if (needLogin) {
            // 토큰 없음 → localStorage 인증 캐시 제거 (이전 세션 캐시로 메인화면이 뜨는 것 방지)
            Log.d(TAG, "getClientState: needLogin=true, clearing localStorage auth cache")
            ctx.evalJs("localStorage.removeItem('nx_auth'); localStorage.removeItem('isLoggedIn'); localStorage.removeItem('currentUser')")
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
            ctx.resolveToJs("getUserConfig", cached)

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
