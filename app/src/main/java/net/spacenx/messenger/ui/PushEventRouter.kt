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

                        // ICON_EVENT는 presence 전용 경로로 전달 — forwardPushToReact 스킵
                        if (cmd != ProtocolCommand.ICON_EVENT) {
                            bridgeDispatcher.forwardPushToReact(cmd.protocol, data)
                        } else {
                            val escaped = bridgeDispatcher.esc(data.toString())
                            Log.d("Presence", "[6] ICON_EVENT→React: $data")
                            activity.runOnUiThread {
                                webView.evaluateJavascript(
                                    "window._onPresenceUpdate && window._onPresenceUpdate('$escaped')", null
                                )
                            }
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

                        // Org 이벤트 → delta sync + orgReady
                        if (cmd == ProtocolCommand.ORG_USER_EVENT ||
                            cmd == ProtocolCommand.ORG_DEPT_EVENT ||
                            cmd == ProtocolCommand.ORG_USER_REMOVED_EVENT ||
                            cmd == ProtocolCommand.ORG_DEPT_REMOVED_EVENT) {
                            if (myUserId.isNotEmpty()) {
                                try {
                                    loginViewModel.orgRepo.syncOrg(myUserId)
                                } catch (e: Exception) {
                                    Log.w(TAG, "orgRepo.syncOrg on ${cmd.protocol} failed: ${e.message}")
                                }
                            }
                            bridgeDispatcher.notifyReact("orgReady")
                        }

                        // Project/Issue/Thread/Cal/Todo 이벤트 → projectReady
                        if (cmd == ProtocolCommand.MOD_ISSUE_EVENT ||
                            cmd == ProtocolCommand.MOD_PROJECT_EVENT ||
                            cmd == ProtocolCommand.MOD_THREAD_EVENT ||
                            cmd == ProtocolCommand.MOD_CAL_EVENT ||
                            cmd == ProtocolCommand.MOD_TODO_EVENT ||
                            cmd == ProtocolCommand.CREATE_CHAT_THREAD_EVENT ||
                            cmd == ProtocolCommand.DELETE_CHAT_THREAD_EVENT ||
                            cmd == ProtocolCommand.ADD_COMMENT_EVENT ||
                            cmd == ProtocolCommand.DELETE_COMMENT_EVENT) {
                            bridgeDispatcher.notifyReact("projectReady")
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
                                    val json = JSONObject()
                                        .put("event", "threadReady")
                                        .put("channelCode", threadData["channelCode"])
                                        .put("chatCode", threadData["chatCode"])
                                        .put("commentCount", threadData["commentCount"])
                                        .toString()
                                    bridgeDispatcher.evalJs(
                                        "window.postMessage('${bridgeDispatcher.esc(json)}')"
                                    )
                                } else {
                                    bridgeDispatcher.notifyReact("threadReady")
                                }
                            }
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
                                if (sid != myUserId) {
                                    showPushNotification(
                                        Constants.TYPE_TALK,
                                        data.optString("channelCode", ""),
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
        if (channelCode == bridgeDispatcher.activeChannelCode) {
            Log.d(TAG, "showPushNotification: suppressed (active channel)")
            return
        }
        if (appConfig.isChannelMuted(channelCode)) {
            Log.d(TAG, "showPushNotification: muted channel $channelCode")
            return
        }
        if (isAppInForeground()) {
            activity.runOnUiThread {
                inAppBanner.show(senderName.ifEmpty { "새 메시지" }, preprocessContent(contents), channelCode) {
                    navigateToChannel(channelCode)
                }
            }
            Log.d(TAG, "showPushNotification: in-app banner shown")
        } else {
            notificationGroupManager.showNotify(type, channelCode, senderName, preprocessContent(contents), null)
            Log.d(TAG, "showPushNotification: system notification shown")
        }
    }

    fun showPushNotificationGeneric(type: String, key: String, senderName: String, contents: String, senderId: String = "") {
        if (isAppInForeground()) {
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
        } else {
            notificationGroupManager.showNotify(type, key, senderName, preprocessContent(contents), null, null, senderId)
        }
    }
}
