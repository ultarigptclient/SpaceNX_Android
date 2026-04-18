package net.spacenx.messenger.ui.call

import android.content.Context
import org.json.JSONObject

//2026-04-18 livekit 당장 미사용으로 의존성 제거함. 추후 주석 풀어야함
/*
 * 실제 구현은 git history 또는 아래 원본 복원 후 사용:
 *   implementation("io.livekit:livekit-android:2.5.0")
 */
class CallService(
    private val appContext: Context,
    private val getEndpoint: (String) -> String,
    private val getToken: () -> String?
) {
    companion object {
        private const val TAG = "CallService"
    }

    var currentChannelCode: String? = null
        private set
    var currentCallType: String? = null
        private set

    val isInCall: Boolean get() = false
    val participants: List<Any> get() = emptyList()
    var onCallEvent: ((event: String, data: Map<String, Any?>) -> Unit)? = null

    suspend fun createCall(
        channelCode: String, userId: String, userName: String, callType: String
    ): JSONObject = JSONObject().put("errorCode", -1).put("message", "통화 기능 미지원")

    suspend fun joinCall(
        channelCode: String, userId: String, userName: String
    ): JSONObject = JSONObject().put("errorCode", -1).put("message", "통화 기능 미지원")

    suspend fun endCall(channelCode: String): JSONObject {
        currentChannelCode = null
        currentCallType = null
        return JSONObject().put("errorCode", 0)
    }

    suspend fun toggleMicrophone(enabled: Boolean) {}
    suspend fun toggleCamera(enabled: Boolean) {}
    suspend fun toggleScreenShare(enabled: Boolean) {}

    fun dispose() {
        currentChannelCode = null
        currentCallType = null
    }
}
