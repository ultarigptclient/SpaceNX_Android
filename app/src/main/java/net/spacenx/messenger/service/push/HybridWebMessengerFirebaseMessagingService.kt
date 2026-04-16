package net.spacenx.messenger.service.push

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.common.Constants
import net.spacenx.messenger.data.local.DatabaseProvider
import org.json.JSONObject
import javax.inject.Inject

@AndroidEntryPoint
class HybridWebMessengerFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "HybridWebMessengerFirebaseMessagingService"

        @JvmStatic
        var alarmListener: AlarmServiceListener? = null

        @JvmStatic
        var onGmsTokenListener: OnGmsTokenListener? = null

        @JvmStatic
        fun getToken(context: Context, listener: OnGmsTokenListener) {
            val appConfig = AppConfig(context)
            val cached = appConfig.gmsToken

            if (cached.isNotEmpty()) {
                Log.d(TAG, "getToken cached: $cached")
                listener.onGmsToken(cached, false)
                return
            }

            FirebaseApp.initializeApp(context.applicationContext)
            Log.d(TAG, "getToken FirebaseApp initialized")

            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.w(TAG, "getToken failed", task.exception)
                    return@addOnCompleteListener
                }
                val token = task.result
                appConfig.gmsToken = token
                Log.d(TAG, "getToken success: $token")
                listener.onGmsToken(token, false)
            }
        }
    }

    @Inject lateinit var appConfig: AppConfig
    @Inject lateinit var dbProvider: DatabaseProvider
    @Inject lateinit var notificationGroupManager: GroupNotificationManager

    private var notificationSettings: NotificationSettingsChecker = DefaultNotificationSettingsChecker()

    // ── Token ──
    override fun onNewToken(refreshedToken: String) {
        Log.d(TAG, "onNewToken: $refreshedToken")
        appConfig.gmsToken = refreshedToken
        applicationContext.getSharedPreferences("GmsToken", Context.MODE_PRIVATE)
            .edit()
            .putString("token", refreshedToken)
            .apply()
        onGmsTokenListener?.onGmsToken(refreshedToken, true)
        super.onNewToken(refreshedToken)
    }

    // ── Message Received ──
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d(TAG, "onMessageReceived")
        super.onMessageReceived(remoteMessage)

        try {
            val data = remoteMessage.data

            if (data.containsKey("comm") || (data.containsKey("key") && data.containsKey("name"))) {
                handleFlatPushData(data)
                return
            }

            val allMsg = data["msg"]
            if (allMsg != null) {
                try {
                    val json = JSONObject(allMsg)
                    if (handleStructuredMessage(json)) return
                } catch (_: Exception) {}
            }

            for ((_, value) in data) {
                processDataEntry(value)
            }
        } catch (e: Exception) {
            Log.e(TAG, "onMessageReceived error", e)
        }
    }

    private fun handleFlatPushData(data: Map<String, String>) {
        val comm = data["comm"] ?: ""
        val name = data["name"] ?: ""
        val msg = data["msg"] ?: ""
        val key = data["key"] ?: ""
        val systemName = data["systemName"] ?: ""
        val senderId = data["id"] ?: data["senderId"] ?: data["userId"] ?: ""

        Log.d(TAG, "handleFlatPushData: comm=$comm, key=$key, name=$name, senderId=$senderId")

        if (msg.startsWith("ptt://")) return

        when (comm) {
            "chat" -> sendNotification(Constants.TYPE_TALK, key, name, msg, null, senderId)
            "message" -> sendNotification(Constants.TYPE_MESSAGE, key, name, msg, null, senderId)
            "noti" -> {
                val title = if (systemName.isNotEmpty()) "[$systemName] $msg" else msg
                sendNotification(Constants.TYPE_SYSTEM_NOTIFY, key, name, title, null)
            }
            else -> {
                when (key) {
                    "MSG" -> sendNotification(Constants.TYPE_MESSAGE, key, name, msg, null, senderId)
                    "NOTI" -> sendNotification(Constants.TYPE_SYSTEM_NOTIFY, key, name, msg, null)
                    "CUSTOM" -> sendNotification(Constants.TYPE_CUSTOM, key, name, msg, null)
                    else -> if (msg.isNotEmpty()) sendNotification(Constants.TYPE_TALK, key, name, msg, null)
                }
            }
        }
    }

    private fun handleStructuredMessage(json: JSONObject): Boolean {
        if (!json.has("senderName") || !json.has("type") || !json.has("key")
            || !json.has("content") || !json.has("info")
        ) return false

        val senderName = json.getString("senderName")
        val type = json.getString("type")
        val key = json.getString("key")
        val content = json.getString("content")
        val info = json.getString("info")

        if (type != "MCU") return false

        val parts = info.split("|")
        Log.d(TAG, "MCU push - key=$key, content=$content, info=$info")

        if (parts.size < 5) return true

        sendNotification(Constants.TYPE_MCU, key, senderName, content, info)
        return true
    }

    private fun processDataEntry(value: String) {
        try {
            Log.d(TAG, "processDataEntry raw: $value")
            //processDataEntry raw: {"id":"jse","name":"전수은","comm":"대화3","systemName":null,"key":"mo19sh5rUFZFajikBcF","date":"1776338366677","PCICON":"Y","badgeCount":1024,"messageType":"CHAT"}
            //processDataEntry raw: {"id":null,"name":"","comm":"<p style=\"text-align: left;\">4444444444</p>","systemName":null,"key":"MESSAGE","date":null,"PCICON":"Y","badgeCount":1025,"messageType":"MESSAGE"}

            val myId = Constants.getMyId(applicationContext)
            if (myId.isNullOrEmpty()) return

            val json = JSONObject(value)
            val key = json.optString("key", "")
            if (key.isEmpty()) return

            val messageType = json.optString("messageType", "").uppercase()
            // CHAT 타입은 "comm" 필드에 메시지 내용이 담겨 있음
            val rawContent = if (messageType == "CHAT") {
                json.optString("comm",
                    json.optString("msg",
                    json.optString("message", "")))
            } else {
                json.optString("msg",
                    json.optString("message",
                    json.optString("comm", "")))
            }
            val content = if (rawContent.isNotEmpty()) rawContent
            else when (messageType) {
                "CHAT"           -> "새 채팅메시지가 도착했습니다."
                "MSG", "MESSAGE" -> "새 쪽지가 도착했습니다."
                "NOTI"           -> "새 알림이 도착했습니다."
                else             -> "새 메시지가 도착했습니다."
            }
            val senderId = json.optString("id", "").takeIf { it != "null" } ?: ""
            val userName = json.optString("name", "")
            val systemName = json.optString("systemName", "")
            val url = json.optString("url", "")
            val badgeCount = json.optInt("badgeCount", 0)

            if (content.startsWith("ptt://")) return

            Log.d(TAG, "processDataEntry: key=$key, name=$userName, sender=$senderId, badge=$badgeCount")
            showNotify(key, userName, content, badgeCount, systemName, url, senderId)
        } catch (e: Exception) {
            Log.e(TAG, "processDataEntry error", e)
        }
    }

    private fun showNotify(
        key: String,
        userName: String,
        rawContent: String,
        badgeCount: Int,
        systemName: String,
        url: String,
        senderId: String = ""
    ) {
        var content = rawContent
        when {
            content.startsWith("ATTACH:/") || content.startsWith("FILE://") ->
                content = "첨부 파일 수신"

            content.startsWith("{SIZEINFO") -> {
                val nlIdx = content.indexOf('\n')
                content = if (nlIdx >= 0) content.substring(nlIdx + 1) else content
            }

            content.startsWith("<REPLY") -> {
                val before = content.substring(0, content.indexOf("<REPLY"))
                val after = content.substring(content.indexOf(">") + 1)
                content = before + after
            }

            content.startsWith("#\$RESET_PASSWORD\$#") ||
                content.startsWith("#\$LOGOUT_SERVICE\$#") ||
                content.startsWith("[COLOECT_UNREAD]") ||
                content.startsWith("#\$DEVICELOCK_CLEAR\$#") ||
                content.startsWith("[DESTROY]") ||
                content.startsWith("[ROOM_NAME]") ||
                content.startsWith("[IMPORTANT_CHAT]") -> return
        }

        when (key) {
            "MSG", "MESSAGE" -> sendNotification(Constants.TYPE_MESSAGE, key, userName, content, null, senderId)
            "OTHER" -> { /* silent badge only */ }
            "NOTI" -> sendNotification(Constants.TYPE_SYSTEM_NOTIFY, key, userName, content, null)
            "CUSTOM" -> sendNotification(Constants.TYPE_CUSTOM, key, userName, content, null)
            "mail" -> sendNotification(Constants.TYPE_MAIL, key, userName, content, url)
            else -> {
                // 채팅방 key: 뮤트 채널 체크
                if (appConfig.isChannelMuted(key)) {
                    Log.d(TAG, "showNotify: muted channel $key, skipping notification")
                    return
                }
                sendNotification(Constants.TYPE_TALK, key, userName, content, null, senderId)
            }
        }
    }

    private fun sendNotification(
        type: String,
        key: String,
        userName: String,
        message: String,
        arg: String?,
        senderId: String = ""
    ) {
        Log.d(TAG, "sendNotification type=$type, key=$key, user=$userName")

        if (message.startsWith("[AUTO_DELETE_CHAT]")) return

        // 포그라운드 + 현재 열려 있는 채널이면 FCM 시스템 알림 억제
        // (소켓 경로의 MainActivity.showPushNotification이 이미 활성 채널 여부에 따라 처리)
        if (type == Constants.TYPE_TALK
            && net.spacenx.messenger.common.AppState.isForeground
            && net.spacenx.messenger.common.AppState.activeChannelCode == key
        ) {
            Log.d(TAG, "sendNotification: suppressed (active channel $key)")
            return
        }

        // 포그라운드 상태에서 MSG FCM 억제: 소켓이 SendMessageEvent/ReadMessageEvent를
        // 실시간으로 처리하므로 FCM은 중복. 특히 서버가 쪽지 발신자에게도 FCM을 잘못
        // 전송하는 경우 자기 자신에게 알림이 뜨는 문제를 방지.
        if (type == Constants.TYPE_MESSAGE
            && net.spacenx.messenger.common.AppState.isForeground
        ) {
            Log.d(TAG, "sendNotification: suppressed MSG (foreground)")
            return
        }

        when (type) {
            Constants.TYPE_TALK          -> if (!notificationSettings.useNotification("TALK")) return
            Constants.TYPE_MESSAGE       -> if (!notificationSettings.useNotification("MESSAGE")) return
            Constants.TYPE_MCU           -> if (!notificationSettings.useNotification("MCU")) return
            Constants.TYPE_SYSTEM_NOTIFY -> if (!notificationSettings.useNotification("NOTIFY")) return
            Constants.TYPE_CUSTOM        -> if (!notificationSettings.useNotification("SYSTEMALARM_BOARD")) return
            Constants.TYPE_MAIL          -> if (!notificationSettings.useNotification("MAIL")) return
        }

        if (!isScreenOn()) {
            var wakeLock: PowerManager.WakeLock? = null
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HybridWebMessenger:FCMWakeLock")
                wakeLock.acquire(2000)
            } catch (e: Exception) {
                Log.e(TAG, "WakeLock error", e)
            } finally {
                try { if (wakeLock?.isHeld == true) wakeLock.release() } catch (_: Exception) {}
            }
        }

        // 채팅방 이름 조회 (MessagingStyle conversationTitle용)
        val conversationTitle: String? = if (type == Constants.TYPE_TALK) {
            try {
                runBlocking(Dispatchers.IO) {
                    dbProvider.getChatDatabase().channelDao().getByChannelCode(key)?.channelName
                }
            } catch (_: Exception) { null }
        } else null

        notificationGroupManager.showNotify(type, key, userName, message, arg, conversationTitle, senderId)
    }

    private fun isScreenOn(): Boolean {
        return try {
            (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        } catch (_: Exception) { false }
    }

    fun interface AlarmServiceListener {
        fun onAlarmReceived()
    }
}
