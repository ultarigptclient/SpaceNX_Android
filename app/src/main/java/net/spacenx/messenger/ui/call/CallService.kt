package net.spacenx.messenger.ui.call

import android.content.Context
import android.util.Log
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.spacenx.messenger.data.remote.api.ApiClient
import org.json.JSONObject

/**
 * LiveKit 통화 서비스 (Flutter CallService 동일 패턴)
 *
 * REST로 createCall/joinCall → 서버가 wsUrl + token 반환 → LiveKit Room 연���
 */
class CallService(
    private val appContext: Context,
    private val getEndpoint: (String) -> String,
    private val getToken: () -> String?
) {
    companion object {
        private const val TAG = "CallService"
    }

    private var room: Room? = null
    private var serviceScope: CoroutineScope? = null

    var currentChannelCode: String? = null
        private set
    var currentCallType: String? = null
        private set

    val isInCall: Boolean
        get() = room?.state == Room.State.CONNECTED

    val participants: List<Participant>
        get() {
            val r = room ?: return emptyList()
            val list = mutableListOf<Participant>()
            r.localParticipant?.let { list.add(it) }
            list.addAll(r.remoteParticipants.values)
            return list
        }

    /** 통화 이벤트 콜백 (UI 갱신용) */
    var onCallEvent: ((event: String, data: Map<String, Any?>) -> Unit)? = null

    suspend fun createCall(
        channelCode: String, userId: String, userName: String, callType: String
    ): JSONObject {
        val body = JSONObject().apply {
            put("channelCode", channelCode)
            put("userId", userId)
            put("userName", userName)
            put("callType", callType)
        }
        val result = ApiClient.postJson(getEndpoint("/comm/createcall"), body)
        if (result.optInt("errorCode", -1) == 0) {
            val wsUrl = result.optString("wsUrl", "")
            val token = result.optString("token", "")
            if (wsUrl.isNotEmpty() && token.isNotEmpty()) {
                connectToRoom(wsUrl, token, callType, enableCamera = callType == "video")
                currentChannelCode = channelCode
                currentCallType = callType
            }
        }
        return result
    }

    suspend fun joinCall(
        channelCode: String, userId: String, userName: String
    ): JSONObject {
        val body = JSONObject().apply {
            put("channelCode", channelCode)
            put("userId", userId)
            put("userName", userName)
        }
        val result = ApiClient.postJson(getEndpoint("/comm/joincall"), body)
        if (result.optInt("errorCode", -1) == 0) {
            val wsUrl = result.optString("wsUrl", "")
            val token = result.optString("token", "")
            if (wsUrl.isNotEmpty() && token.isNotEmpty()) {
                connectToRoom(wsUrl, token, currentCallType, enableCamera = false)
                currentChannelCode = channelCode
            }
        }
        return result
    }

    suspend fun endCall(channelCode: String): JSONObject {
        disconnectRoom()
        val body = JSONObject().apply { put("channelCode", channelCode) }
        val result = ApiClient.postJson(getEndpoint("/comm/endcall"), body)
        currentChannelCode = null
        currentCallType = null
        return result
    }

    private suspend fun connectToRoom(
        wsUrl: String, token: String, callType: String?, enableCamera: Boolean
    ) {
        disconnectRoom()

        val newRoom = LiveKit.create(
            appContext = appContext,
            options = RoomOptions(adaptiveStream = true, dynacast = true)
        )
        room = newRoom

        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        serviceScope?.launch {
            newRoom.events.collect { event ->
                when (event) {
                    is RoomEvent.Disconnected -> {
                        onCallEvent?.invoke("disconnected", emptyMap())
                    }
                    is RoomEvent.ParticipantConnected -> {
                        onCallEvent?.invoke("participantChanged", emptyMap())
                    }
                    is RoomEvent.ParticipantDisconnected -> {
                        onCallEvent?.invoke("participantChanged", emptyMap())
                        if (newRoom.remoteParticipants.isEmpty()) {
                            onCallEvent?.invoke("allLeft", emptyMap())
                        }
                    }
                    is RoomEvent.TrackSubscribed,
                    is RoomEvent.TrackUnsubscribed,
                    is RoomEvent.TrackMuted,
                    is RoomEvent.TrackUnmuted -> {
                        onCallEvent?.invoke("trackChanged", emptyMap())
                    }
                    else -> {}
                }
            }
        }

        newRoom.connect(wsUrl, token)
        newRoom.localParticipant?.setMicrophoneEnabled(true)
        if (enableCamera && callType == "video") {
            newRoom.localParticipant?.setCameraEnabled(true)
        }

        Log.d(TAG, "Connected to LiveKit room: $wsUrl")
    }

    suspend fun toggleMicrophone(enabled: Boolean) {
        room?.localParticipant?.setMicrophoneEnabled(enabled)
    }

    suspend fun toggleCamera(enabled: Boolean) {
        room?.localParticipant?.setCameraEnabled(enabled)
    }

    suspend fun toggleScreenShare(enabled: Boolean) {
        room?.localParticipant?.setScreenShareEnabled(enabled)
    }

    private fun disconnectRoom() {
        try {
            serviceScope?.cancel()
            serviceScope = null
            room?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "disconnect error: ${e.message}")
        }
        room = null
    }

    fun dispose() {
        serviceScope?.cancel()
        serviceScope = null
        try { room?.disconnect() } catch (_: Exception) {}
        room = null
        currentChannelCode = null
        currentCallType = null
    }
}
