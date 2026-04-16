package net.spacenx.messenger.data.repository

import android.util.Log
import kotlinx.coroutines.withTimeoutOrNull
import net.spacenx.messenger.data.local.ChatDatabase
import org.json.JSONObject

/**
 * ChannelRepository 계열 공통 유틸리티
 * - sanitizeAdditional / enrichChannelAdditional: BridgeDispatcher·ChatHandler에서도 사용
 * - awaitToken: SyncRepository·ActionRepository 공통 의존
 */
object ChannelUtils {

    /**
     * channel.additional의 pinnedMessages 항목에 chat additional 데이터를 병합.
     * React가 핀고정 파일 메시지 렌더링 시 additional.file.fileName에 접근하므로 필수.
     */
    suspend fun enrichChannelAdditional(channelAdditional: String, chatDb: ChatDatabase): JSONObject {
        val addJson = if (channelAdditional.isNotBlank()) {
            try { JSONObject(channelAdditional) } catch (_: Exception) { return JSONObject() }
        } else return JSONObject()

        val pinnedMessages = addJson.optJSONArray("pinnedMessages")
        if (pinnedMessages != null) {
            for (i in 0 until pinnedMessages.length()) {
                val pm = pinnedMessages.getJSONObject(i)
                if (pm.has("additional")) {
                    val existing = pm.optJSONObject("additional")
                    if (existing != null) {
                        pm.put("additional", JSONObject(sanitizeAdditional(existing.toString())))
                    } else {
                        pm.put("additional", JSONObject().put("file", JSONObject()))
                    }
                    continue
                }
                val chatCode = pm.optString("chatCode", "")
                if (chatCode.isEmpty()) continue
                val chat = chatDb.chatDao().getByChatCode(chatCode)
                if (!chat?.additional.isNullOrEmpty()) {
                    try {
                        pm.put("additional", JSONObject(sanitizeAdditional(chat!!.additional!!)))
                    } catch (_: Exception) {
                        pm.put("additional", JSONObject().put("file", JSONObject()))
                    }
                } else {
                    pm.put("additional", JSONObject().put("file", JSONObject()))
                }
            }
        }
        return addJson
    }

    /** additional JSON 정규화: 구형 포맷 → "file" 래퍼 추가, file:null → {} */
    fun sanitizeAdditional(additional: String): String {
        if (additional.isEmpty()) return additional
        return try {
            val json = JSONObject(additional)
            if (json.has("file")) {
                if (json.isNull("file")) {
                    json.put("file", JSONObject())
                } else {
                    val fileObj = json.optJSONObject("file")
                    if (fileObj != null) {
                        for (field in listOf("fileName", "fileType", "fileUrl", "url", "thumbnail")) {
                            if (fileObj.has(field) && fileObj.isNull(field)) fileObj.put(field, "")
                        }
                        if (fileObj.has("fileSize") && fileObj.isNull("fileSize")) fileObj.put("fileSize", 0)
                    }
                }
            } else if (json.optString("type") == "file") {
                json.put("file", JSONObject().apply {
                    put("fileName", json.optString("fileName", ""))
                    put("fileSize", json.optLong("fileSize", 0))
                    put("url", json.optString("url", json.optString("fileUrl", "")))
                    put("fileId", json.optString("fileId", ""))
                    val ft = json.optString("fileType", ""); if (ft.isNotEmpty()) put("fileType", ft)
                    val th = json.optString("thumbnail", ""); if (th.isNotEmpty()) put("thumbnail", th)
                })
            }
            json.toString()
        } catch (_: Exception) {
            additional
        }
    }

    /** JWT 토큰 대기 (최대 10초) */
    suspend fun awaitToken(sessionManager: SocketSessionManager): String? {
        if (sessionManager.jwtToken != null) return sessionManager.jwtToken
        Log.d("ChannelUtils", "Waiting for JWT token...")
        return withTimeoutOrNull(10_000L) { sessionManager.jwtTokenDeferred.await() }
    }
}
