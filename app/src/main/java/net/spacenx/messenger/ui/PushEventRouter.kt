package net.spacenx.messenger.ui

import android.util.Log
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.common.Constants
import net.spacenx.messenger.common.JsEscapeUtil
import net.spacenx.messenger.data.local.DatabaseProvider
import net.spacenx.messenger.data.remote.socket.codec.ProtocolCommand
import net.spacenx.messenger.service.push.GroupNotificationManager
import net.spacenx.messenger.ui.bridge.BridgeDispatcher
import net.spacenx.messenger.ui.viewmodel.LoginViewModel
import org.json.JSONObject

/**
 * 소켓 push 프레임 라우팅 + 인앱/시스템 알림 표시 담당.
 * 원래 MainActivity.registerPushHandlers() / showPushNotification() 에 있던 코드를 분리.
 */
class PushEventRouter(
    private val activity: AppCompatActivity,
    private val loginViewModel: LoginViewModel,
    private val bridgeDispatcher: BridgeDispatcher,
    private val appConfig: AppConfig,
    private val databaseProvider: DatabaseProvider,
    private val notificationGroupManager: GroupNotificationManager,
    private val inAppBanner: InAppBannerView,
    private val webView: WebView,
    private val scope: CoroutineScope,
    private val isAppInForeground: () -> Boolean,
    private val preprocessContent: (String) -> String
) {
    companion object {
        private const val TAG = "PushEventRouter"
    }

    /** SocketSessionManager에 프레임 핸들러 + 재연결/인증 실패 콜백을 등록한다. */
    fun register() {
        val sm = loginViewModel.sessionManager
        for (cmd in ProtocolCommand.PUSH_EVENT_CODES) {
            sm.registerFrameHandler(cmd.code) { frame ->
                scope.launch {
                    try {
                        val data = JSONObject(frame.bodyAsString())

                        // DualConnectionKick: 중복 로그인 → 토큰 삭제 후 강제 로그아웃
                        if (cmd == ProtocolCommand.DUAL_CONNECTION_KICK) {
                            Log.w(TAG, "DualConnectionKickEvent: another device connected, forcing logout")
                            appConfig.clearTokens()
                            appConfig.clearCredentials()
                            activity.runOnUiThread { bridgeDispatcher.notifyReact("forceLogout") }
                            return@launch
                        }

                        // QueueOverflow: 서버 push queue(500 frames) clear됨 → 도메인별 delta 재싱크.
                        // 서버 event 테이블은 보존(retention ≥14일)되므로 stored offset부터 sync하면 드랍 이벤트 복구됨.
                        // 도메인별 독립 실행: 한 sync 실패/지연이 다른 도메인을 막지 않게.
                        if (cmd == ProtocolCommand.QUEUE_OVERFLOW) {
                            Log.w(TAG, "QueueOverflow: triggering full delta resync")
                            val uid = appConfig.getSavedUserId() ?: ""
                            scope.launch {
                                if (uid.isNotEmpty()) {
                                    try { loginViewModel.channelRepo.syncChannel(uid) }
                                    catch (e: Exception) { Log.w(TAG, "QueueOverflow syncChannel failed: ${e.message}") }
                                    try { loginViewModel.channelRepo.syncChat(uid) }
                                    catch (e: Exception) { Log.w(TAG, "QueueOverflow syncChat failed: ${e.message}") }
                                }
                                bridgeDispatcher.notifyReact("channelReady")
                                bridgeDispatcher.notifyReactOnce("chatReady")
                            }
                            scope.launch {
                                try { loginViewModel.messageRepo.syncMessage() }
                                catch (e: Exception) { Log.w(TAG, "QueueOverflow syncMessage failed: ${e.message}") }
                                bridgeDispatcher.notifyReact("messageReady")
                            }
                            scope.launch {
                                try { loginViewModel.notiRepo.syncNoti() }
                                catch (e: Exception) { Log.w(TAG, "QueueOverflow syncNoti failed: ${e.message}") }
                                bridgeDispatcher.notifyReact("notiReady")
                            }
                            return@launch
                        }

                        // 로컬 DB 적용
                        loginViewModel.pushEventHandler.applyToLocalDb(cmd.code, data)

                        // sendUserName 보강
                        val sendUserId = data.optString("sendUserId", "")
                        if (sendUserId.isNotEmpty() && !data.has("sendUserName")) {
                            data.put("sendUserName", loginViewModel.userNameCache.resolve(sendUserId))
                        }

                        // MakeChannelEvent: channelName null → DB에서 보강
                        if (cmd == ProtocolCommand.MAKE_CHANNEL_EVENT && data.isNull("channelName")) {
                            val cc = data.optString("channelCode", "")
                            if (cc.isNotEmpty()) {
                                try {
                                    val ch = databaseProvider.getChatDatabase()
                                        .channelDao().getByChannelCode(cc)
                                    if (ch != null && ch.channelName.isNotEmpty()) {
                                        data.put("channelName", ch.channelName)
                                    }
                                } catch (_: Exception) {}
                            }
                        }

                        val myUserId = appConfig.getSavedUserId() ?: ""

                        // ModThreadEvent ADD_COMMENT/DELETE_COMMENT: 서버가 chatCode=null로 보냄 → DB에서 보강
                        if (cmd == ProtocolCommand.MOD_THREAD_EVENT) {
                            val et = data.optString("eventType", "")
                            if ((et == "ADD_COMMENT" || et == "DELETE_COMMENT") && data.isNull("chatCode")) {
                                val tc = data.optString("threadCode", "")
                                if (tc.isNotEmpty()) {
                                    val td = loginViewModel.projectRepo.getChatThreadByCode(tc)
                                    val cc = td?.get("chatCode") as? String
                                    if (!cc.isNullOrEmpty()) {
                                        data.put("chatCode", cc)
                                    }
                                }
                            }
                        }

                        // ICON_EVENT / NICK_EVENT 포함 모든 push는 neoPush 경로로 전달
                        // React onPush('Icon'/'Nick') 핸들러가 _mergePresenceGlobal 처리
                        Log.d("Presence", "[6] ${cmd.protocol}→React: $data")
                        bridgeDispatcher.forwardPushToReact(cmd.protocol, data)

                        // ReadNotiEvent / DeleteNotiEvent → notiReady
                        if (cmd == ProtocolCommand.READ_NOTI_EVENT ||
                            cmd == ProtocolCommand.DELETE_NOTI_EVENT) {
                            bridgeDispatcher.notifyReact("notiReady")
                        }

                        // MuteChannelEvent → channelReady (React 뮤트 토글 상태 갱신)
                        if (cmd == ProtocolCommand.MUTE_CHANNEL_EVENT) {
                            bridgeDispatcher.notifyReact("channelReady")
                        }

                        // Message 이벤트 → messageReady
                        if (cmd == ProtocolCommand.SEND_MESSAGE_EVENT ||
                            cmd == ProtocolCommand.READ_MESSAGE_EVENT ||
                            cmd == ProtocolCommand.DELETE_MESSAGE_EVENT ||
                            cmd == ProtocolCommand.RETRIEVE_MESSAGE_EVENT) {
                            bridgeDispatcher.notifyReact("messageReady")
                        }

                        // SendChatEvent (채널 밖) → chatReady + channelReady
                        if (cmd == ProtocolCommand.SEND_CHAT_EVENT && bridgeDispatcher.activeChannelCode == null) {
                            bridgeDispatcher.notifyReactOnce("chatReady")
                            bridgeDispatcher.notifyReactOnce("channelReady")
                        }

                        // 멤버 입장/퇴장 → channelReady
                        if (cmd == ProtocolCommand.ADD_CHANNEL_MEMBER_EVENT ||
                            cmd == ProtocolCommand.REMOVE_CHANNEL_MEMBER_EVENT) {
                            bridgeDispatcher.notifyReact("channelReady")
                        }

                        // Org 이벤트 → delta sync + orgReady + buddyReady
                        if (cmd == ProtocolCommand.ORG_USER_EVENT ||
                            cmd == ProtocolCommand.ORG_DEPT_EVENT ||
                            cmd == ProtocolCommand.ORG_USER_REMOVED_EVENT ||
                            cmd == ProtocolCommand.ORG_DEPT_REMOVED_EVENT) {
                            Log.d(TAG, "orgEvent: ${cmd.protocol} received, triggering syncOrg")
                            if (myUserId.isNotEmpty()) {
                                try {
                                    loginViewModel.orgRepo.syncOrg(myUserId)
                                    Log.d(TAG, "orgEvent: syncOrg complete → notifying orgReady+buddyReady")
                                } catch (e: Exception) {
                                    Log.w(TAG, "orgRepo.syncOrg on ${cmd.protocol} failed: ${e.message}")
                                }
                            }
                            bridgeDispatcher.notifyReact("orgReady")
                            bridgeDispatcher.notifyReact("buddyReady")
                        }

                        // Project/Issue/Thread 이벤트 → projectReady
                        if (cmd == ProtocolCommand.MOD_ISSUE_EVENT ||
                            cmd == ProtocolCommand.MOD_PROJECT_EVENT ||
                            cmd == ProtocolCommand.MOD_THREAD_EVENT ||
                            cmd == ProtocolCommand.CREATE_CHAT_THREAD_EVENT ||
                            cmd == ProtocolCommand.DELETE_CHAT_THREAD_EVENT ||
                            cmd == ProtocolCommand.ADD_COMMENT_EVENT ||
                            cmd == ProtocolCommand.DELETE_COMMENT_EVENT) {
                            bridgeDispatcher.notifyReact("projectReady")
                        }

                        // ModCalEvent → delta sync + calReady
                        if (cmd == ProtocolCommand.MOD_CAL_EVENT) {
                            scope.launch {
                                try {
                                    bridgeDispatcher.projectRepo.syncCalendar()
                                } catch (e: Exception) {
                                    Log.w(TAG, "syncCalendar on ModCalEvent failed: ${e.message}")
                                }
                                bridgeDispatcher.notifyReactOnce("calReady")
                            }
                        }

                        // ModTodoEvent → delta sync + todoReady
                        if (cmd == ProtocolCommand.MOD_TODO_EVENT) {
                            scope.launch {
                                try {
                                    bridgeDispatcher.projectRepo.syncTodo()
                                } catch (e: Exception) {
                                    Log.w(TAG, "syncTodo on ModTodoEvent failed: ${e.message}")
                                }
                                bridgeDispatcher.notifyReactOnce("todoReady")
                            }
                        }

                        // ADD_COMMENT / DELETE_COMMENT → threadReady (말풍선 답글 배지)
                        val eventType = data.optString("eventType", "")
                        if ((cmd == ProtocolCommand.MOD_THREAD_EVENT ||
                             cmd == ProtocolCommand.ADD_COMMENT_EVENT ||
                             cmd == ProtocolCommand.DELETE_COMMENT_EVENT) &&
                            (eventType == "ADD_COMMENT" || eventType == "DELETE_COMMENT")) {
                            val threadCode = data.optString("threadCode", "")
                            if (threadCode.isNotEmpty()) {
                                val threadData = loginViewModel.projectRepo.getChatThreadByCode(threadCode)
                                if (threadData != null) {
                                    // commentCount는 이벤트 값 우선 (DB 반영 전일 수 있음)
                                    val count = if (!data.isNull("commentCount")) data.optInt("commentCount")
                                                else threadData["commentCount"] as? Int ?: 0
                                    val json = JSONObject()
                                        .put("event", "threadReady")
                                        .put("channelCode", threadData["channelCode"])
                                        .put("chatCode", threadData["chatCode"])
                                        .put("commentCount", count)
                                        .toString()
                                    // evalJsMain: 서브 WebView(스레드 패널) 열려있어도 채팅 버블이 있는 메인 WebView로 전송
                                    bridgeDispatcher.evalJsMain(
                                        "window.postMessage('${bridgeDispatcher.esc(json)}')"
                                    )
                                } else {
                                    // DB에 스레드 없음 — channelCode + commentCount만으로 threadReady 전달
                                    val channelCode = data.optString("channelCode", "")
                                    if (channelCode.isNotEmpty()) {
                                        val json = JSONObject()
                                            .put("event", "threadReady")
                                            .put("channelCode", channelCode)
                                            .put("threadCode", threadCode)
                                            .put("commentCount", data.optInt("commentCount", 0))
                                            .toString()
                                        bridgeDispatcher.evalJsMain(
                                            "window.postMessage('${bridgeDispatcher.esc(json)}')"
                                        )
                                    } else {
                                        bridgeDispatcher.notifyReact("threadReady")
                                    }
                                }
                            }
                            // chatReady도 함께 발행: React가 채널 chat list를 refresh하여 thread 뱃지 JOIN 재평가
                            bridgeDispatcher.notifyReactOnce("chatReady")
                        }

                        // setConfig push → FRONTEND_VERSION/SKIN URL 변경
                        if (cmd == ProtocolCommand.SET_CONFIG) {
                            val key = data.optString("key", "")
                            val value = data.optString("value", "")
                            if ((key == "FRONTEND_VERSION" || key == "FRONTEND_SKIN") && value.isNotEmpty()) {
                                Log.d(TAG, "setConfig push: $key=$value")
                                appConfig.updateConfigCache(mapOf(key to value))
                                activity.runOnUiThread { webView.loadUrl(appConfig.getSpaUrl()) }
                            }
                        }

                        // 인앱 배너 / 시스템 Notification
                        when (cmd) {
                            ProtocolCommand.SEND_CHAT_EVENT -> {
                                val sid = data.optString("sendUserId", "")
                                // 채널이 로컬에 없고 SYSTEM 메시지(입장/퇴장/초대)면 배너 skip —
                                // 방 생성은 MakeChannel/AddMember 이벤트가 담당. 해당 이벤트가 먼저
                                // 도착하지 않은 순간에 고아 SYSTEM 메시지로 배너가 뜨는 것을 방지.
                                val rawChatType = data.opt("chatType")
                                val isSystemChat = rawChatType == "SYSTEM" || rawChatType == "system" ||
                                    (rawChatType is Number && (rawChatType.toInt() == 32 || rawChatType.toInt() == 6))
                                val chCode = data.optString("channelCode", "")
                                val channelMissing = if (isSystemChat && chCode.isNotEmpty()) {
                                    try {
                                        databaseProvider.getChatDatabase().channelDao().getByChannelCode(chCode) == null
                                    } catch (_: Exception) { false }
                                } else false
                                if (channelMissing) {
                                    Log.d(TAG, "SendChatEvent banner skipped: missing channel $chCode for SYSTEM msg")
                                } else if (sid != myUserId) {
                                    showPushNotification(
                                        Constants.TYPE_TALK,
                                        chCode,
                                        data.optString("sendUserName", sid),
                                        data.optString("contents", ""),
                                        data.optInt("chatType", 0)
                                    )
                                }
                            }
                            ProtocolCommand.SEND_MESSAGE_EVENT -> {
                                val sid = data.optString("sendUserId", "")
                                if (sid != myUserId) {
                                    showPushNotificationGeneric(
                                        Constants.TYPE_MESSAGE, "MSG",
                                        data.optString("sendUserName", sid),
                                        data.optString("title", data.optString("contents", "")),
                                        sid
                                    )
                                }
                            }
                            ProtocolCommand.NOTIFY_EVENT -> {
                                showPushNotificationGeneric(
                                    Constants.TYPE_SYSTEM_NOTIFY, "NOTI", "알림",
                                    data.optString("contents", data.optString("title", "알림"))
                                )
                            }
                            else -> {}
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Push handle error: ${e.message}")
                    }
                }
            }
        }

        // 소켓으로 수신된 raw JSON 알림 (FCM 포맷과 동일, 바이너리 헤더 없이 전달)
        sm.onRawSocketJson = { json ->
            scope.launch {
                try {
                    val messageType = json.optString("messageType", "").uppercase()
                    val key = json.optString("key", "").takeIf { it.isNotEmpty() && it != "null" } ?: ""
                    val senderId = json.optString("id", "").takeIf { it != "null" } ?: ""
                    val name = json.optString("name", "").takeIf { it != "null" } ?: ""
                    val comm = json.optString("comm", "")
                    Log.d(TAG, "Raw socket JSON: type=$messageType, key=$key, sender=$senderId")

                    // Icon/Nick raw JSON → neoPush('Icon'/'Nick') 경로로 전달
                    val command = json.optString("command", "")
                    if (command == "Icon" || command == "Nick") {
                        Log.d("Presence", "Raw JSON $command→React: $json")
                        bridgeDispatcher.forwardPushToReact(command, json)
                        return@launch
                    }

                    when (messageType) {
                        "CHAT" -> {
                            if (key.isNotEmpty() && key != bridgeDispatcher.activeChannelCode) {
                                bridgeDispatcher.notifyReactOnce("chatReady")
                                bridgeDispatcher.notifyReactOnce("channelReady")
                                val resolvedName = name.ifEmpty {
                                    if (senderId.isNotEmpty()) loginViewModel.userNameCache.resolve(senderId) else ""
                                }
                                showPushNotification(Constants.TYPE_TALK, key, resolvedName, preprocessContent(comm))
                            }
                        }
                        "MESSAGE", "MSG" -> {
                            bridgeDispatcher.notifyReact("messageReady")
                            val resolvedName = name.ifEmpty {
                                if (senderId.isNotEmpty()) loginViewModel.userNameCache.resolve(senderId) else "쪽지 수신"
                            }
                            showPushNotificationGeneric(Constants.TYPE_MESSAGE, "MSG", resolvedName, preprocessContent(comm), senderId)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Raw socket JSON handle error: ${e.message}")
                }
            }
        }

        // 재접속 콜백
        val sm2 = loginViewModel.sessionManager
        sm2.onReconnected = {
            Log.d(TAG, "Socket reconnected — notifying React")
            activity.runOnUiThread {
                webView.evaluateJavascript(
                    "window.Transport && window.Transport.onNativeStatus('connected')", null
                )
            }
            bridgeDispatcher.evalJs(
                "window.postMessage('${JsEscapeUtil.escapeForJs(JSONObject().put("event", "neoConnected").toString())}')"
            )
        }
        sm2.onAuthFailed = {
            Log.w(TAG, "Reconnect auth failed — forcing logout")
            appConfig.clearTokens()
            appConfig.clearCredentials()
            activity.runOnUiThread { bridgeDispatcher.notifyReact("forceLogout") }
        }
    }

    /** React SPA에 채널 열기 요청 */
    fun navigateToChannel(channelCode: String) {
        val escaped = JsEscapeUtil.escapeForJs(channelCode)
        bridgeDispatcher.evalJs("window._pendingChatOpen={channelCode:'$escaped',fromNav:'noti'}")
        bridgeDispatcher.evalJs(
            "window.dispatchEvent(new CustomEvent('neoNavigate',{detail:{nav:'chat',channelCode:'$escaped'}}))"
        )
        bridgeDispatcher.evalJs(
            "window.postMessage('${JsEscapeUtil.escapeForJs(JSONObject().put("event", "neoOpenChat").put("channelCode", channelCode).toString())}')"
        )
    }

    fun showPushNotification(
        type: String,
        channelCode: String,
        senderName: String,
        contents: String,
        chatType: Int = 0
    ) {
        if (!isAppInForeground()) return  // 백그라운드 시스템 알림은 FCM이 단독 처리
        if (channelCode == bridgeDispatcher.activeChannelCode) {
            Log.d(TAG, "showPushNotification: suppressed (active channel)")
            return
        }
        if (appConfig.isChannelMuted(channelCode)) {
            Log.d(TAG, "showPushNotification: muted channel $channelCode")
            return
        }
        activity.runOnUiThread {
            inAppBanner.show(senderName.ifEmpty { "새 메시지" }, preprocessContent(contents), channelCode) {
                navigateToChannel(channelCode)
            }
        }
        Log.d(TAG, "showPushNotification: in-app banner shown")
    }

    fun showPushNotificationGeneric(type: String, key: String, senderName: String, contents: String, senderId: String = "") {
        if (!isAppInForeground()) return  // 백그라운드 시스템 알림은 FCM이 단독 처리
        activity.runOnUiThread {
            val title = when (type) {
                Constants.TYPE_MESSAGE -> "쪽지: $senderName"
                Constants.TYPE_SYSTEM_NOTIFY -> "알림"
                else -> senderName.ifEmpty { "알림" }
            }
            inAppBanner.show(title, preprocessContent(contents), key) {
                when (type) {
                    Constants.TYPE_MESSAGE ->
                        bridgeDispatcher.evalJs("window.dispatchEvent(new CustomEvent('neoNavigate',{detail:{nav:'message'}}))")
                    Constants.TYPE_SYSTEM_NOTIFY ->
                        bridgeDispatcher.evalJs("window.dispatchEvent(new CustomEvent('neoNavigate',{detail:{nav:'noti'}}))")
                }
            }
        }
    }
}
