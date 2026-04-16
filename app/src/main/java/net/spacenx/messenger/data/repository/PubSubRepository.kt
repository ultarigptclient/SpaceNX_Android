package net.spacenx.messenger.data.repository

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import net.spacenx.messenger.data.remote.socket.codec.ProtocolCommand
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/**
 * Subscribe/Unsubscribe/Presence 관리
 */
class PubSubRepository(private val sessionManager: SocketSessionManager) {

    companion object {
        private const val TAG = "PubSubRepository"
    }

    private val MAX_SUBSCRIBED_USERS = 500
    private val subscribedUserIds: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private var pendingResubscribeIds = listOf<String>()

    private val _subscribeResponse = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val subscribeResponse: Flow<String> = _subscribeResponse

    private val _publishEvent = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val publishEvent: Flow<String> = _publishEvent

    val defaultTopic = listOf("INFO", "ICON_PC", "ICON_MOBILE", "ICON_UC", "NICK", "PHOTO")

    init {
        // Subscribe 응답 핸들러 등록
        sessionManager.registerFrameHandler(ProtocolCommand.SUBSCRIBE.code) { frame ->
            val bodyStr = frame.bodyAsString()
            Log.d("Presence", "[3] subscribe response: $bodyStr")
            _subscribeResponse.tryEmit(bodyStr)
        }

        // Publish 수신 핸들러 - WebView로 전달
        sessionManager.registerFrameHandler(ProtocolCommand.PUBLISH.code) { frame ->
            val bodyStr = frame.bodyAsString()
            Log.d("Presence", "[5] PUBLISH received: $bodyStr")
            _publishEvent.tryEmit(bodyStr)
        }

        // HI 완료 시 자동 재구독
        sessionManager.onHICompleted { autoResubscribe() }
    }

    fun prepareForReconnect() {
        if (subscribedUserIds.isNotEmpty()) {
            pendingResubscribeIds = subscribedUserIds.toList()
        }
        subscribedUserIds.clear()
    }

    fun sendSubscribe(topic: List<String>, targetType: String, targetList: List<String>) {
        val newIds = targetList.filter { it !in subscribedUserIds }
        if (newIds.isEmpty()) {
            Log.d(TAG, "Subscribe skipped - all IDs already subscribed: $targetList")
            return
        }
        // 최대 구독 수 초과 시 오래된 항목 제거 (LinkedHashSet 삽입순)
        synchronized(subscribedUserIds) {
            while (subscribedUserIds.size + newIds.size > MAX_SUBSCRIBED_USERS) {
                val it = subscribedUserIds.iterator()
                if (it.hasNext()) { it.next(); it.remove() } else break
            }
            subscribedUserIds.addAll(newIds)
        }
        sessionManager.loginSessionScope?.launch {
            try {
                if (!sessionManager.hiCompletedDeferred.isCompleted) {
                    Log.d(TAG, "Subscribe waiting for HI completion...")
                    sessionManager.hiCompletedDeferred.await()
                }
            } catch (e: Exception) {
                Log.w(TAG, "sendSubscribe: HI failed, aborting subscribe — ${e.message}")
                return@launch
            }
            val json = JSONObject().apply {
                put("topic", JSONArray(topic))
                put("targetType", targetType)
                put("targetList", JSONArray(newIds))
            }
            val body = json.toString().toByteArray(Charsets.UTF_8)
            Log.d("Presence", "[3] subscribe request: ${newIds.size} users, topic=$topic")
            sessionManager.sendFrame(ProtocolCommand.SUBSCRIBE.code, body)
        } ?: Log.w(TAG, "sendSubscribe: no active session scope")
    }

    fun sendUnsubscribe(topic: List<String>, targetType: String, targetList: List<String>) {
        subscribedUserIds.removeAll(targetList.toSet())
        val json = JSONObject().apply {
            put("topic", JSONArray(topic))
            put("targetType", targetType)
            put("targetList", JSONArray(targetList))
        }
        val body = json.toString().toByteArray(Charsets.UTF_8)
        Log.d(TAG, "Sending Binary Unsubscribe frame: $json")
        sessionManager.sendFrame(ProtocolCommand.UNSUBSCRIBE.code, body)
    }

    fun unsubscribeAll() {
        if (subscribedUserIds.isEmpty()) return
        Log.d(TAG, "unsubscribeAll: ${subscribedUserIds.size} users")
        val json = JSONObject().apply {
            put("topic", JSONArray(defaultTopic))
            put("targetType", "USER")
            put("targetList", JSONArray(subscribedUserIds.toList()))
        }
        val body = json.toString().toByteArray(Charsets.UTF_8)
        sessionManager.sendFrame(ProtocolCommand.UNSUBSCRIBE.code, body)
    }

    fun resubscribeAll() {
        if (subscribedUserIds.isEmpty()) return
        Log.d(TAG, "resubscribeAll: ${subscribedUserIds.size} users")
        val ids = subscribedUserIds.toList()
        sessionManager.loginSessionScope?.launch {
            try {
                if (!sessionManager.hiCompletedDeferred.isCompleted) {
                    Log.d(TAG, "resubscribeAll waiting for HI completion...")
                    sessionManager.hiCompletedDeferred.await()
                }
            } catch (e: Exception) {
                Log.w(TAG, "resubscribeAll: HI failed, aborting resubscribe — ${e.message}")
                return@launch
            }
            val json = JSONObject().apply {
                put("topic", JSONArray(defaultTopic))
                put("targetType", "USER")
                put("targetList", JSONArray(ids))
            }
            val body = json.toString().toByteArray(Charsets.UTF_8)
            Log.d(TAG, "resubscribeAll sending Subscribe frame: ${ids.size} users")
            sessionManager.sendFrame(ProtocolCommand.SUBSCRIBE.code, body)
        } ?: Log.w(TAG, "resubscribeAll: no active session scope")
    }

    fun clearSubscriptions() {
        subscribedUserIds.clear()
        pendingResubscribeIds = emptyList()
    }
    private fun autoResubscribe() {
        // pendingResubscribeIds에 이전 세션 구독 목록 병합
        if (pendingResubscribeIds.isNotEmpty()) {
            subscribedUserIds.addAll(pendingResubscribeIds)
            pendingResubscribeIds = emptyList()
        }
        // HI 완료 = 새 서버 세션 → 서버에는 구독 정보 없음.
        // subscribedUserIds 전체를 무조건 재전송해야 PUBLISH가 정상 작동.
        val ids = subscribedUserIds.toList()
        if (ids.isEmpty()) return
        Log.d(TAG, "Auto-resubscribe ${ids.size} users after HI (new server session)")
        val json = JSONObject().apply {
            put("topic", JSONArray(defaultTopic))
            put("targetType", "USER")
            put("targetList", JSONArray(ids))
        }
        val body = json.toString().toByteArray(Charsets.UTF_8)
        sessionManager.sendFrame(ProtocolCommand.SUBSCRIBE.code, body)
    }
}
