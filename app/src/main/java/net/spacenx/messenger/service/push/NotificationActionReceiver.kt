package net.spacenx.messenger.service.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.data.remote.api.ApiClient
import org.json.JSONObject
import javax.inject.Inject

/**
 * 알림 액션 버튼 처리 BroadcastReceiver.
 * - ACTION_READ: readChat API 호출 + 알림 취소
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotificationActionReceiver"
        const val ACTION_READ = "net.spacenx.messenger.ACTION_READ"
        const val ACTION_REPLY = "net.spacenx.messenger.ACTION_REPLY"
        const val EXTRA_NOTIFY_KEY = "notifyKey"
        const val REPLY_RESULT_KEY = "reply_text"
    }

    @Inject lateinit var notificationGroupManager: GroupNotificationManager
    @Inject lateinit var appConfig: AppConfig

    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_NOTIFY_KEY)
        Log.d(TAG, "onReceive: action=${intent.action}, key=$key")

        when (intent.action) {
            ACTION_READ -> {
                if (key != null) {
                    // 1. 알림 취소
                    notificationGroupManager.cancel(key)
                    Log.d(TAG, "READ: cancelled notification for key=$key")

                    // 2. readChat API 호출
                    val userId = appConfig.getSavedUserId() ?: return
                    runBlocking(Dispatchers.IO) {
                        try {
                            val body = JSONObject().apply {
                                put("channelCode", key)
                                put("readUserId", userId)
                            }
                            val result = ApiClient.postJson(
                                appConfig.getEndpointByPath("/comm/readchat"), body
                            )
                            Log.d(TAG, "READ: readChat result=${result.optInt("errorCode", -1)}")
                        } catch (e: Exception) {
                            Log.e(TAG, "READ: readChat failed", e)
                        }
                    }
                }
            }

            ACTION_REPLY -> {
                if (key != null) {
                    val replyText = RemoteInput.getResultsFromIntent(intent)
                        ?.getCharSequence(REPLY_RESULT_KEY)
                        ?.toString()
                        ?.trim()
                    if (replyText.isNullOrEmpty()) return

                    val userId = appConfig.getSavedUserId() ?: return
                    Log.d(TAG, "REPLY: key=$key, text=${replyText.take(50)}")

                    runBlocking(Dispatchers.IO) {
                        try {
                            val sendBody = JSONObject().apply {
                                put("channelCode", key)
                                put("sendUserId", userId)
                                put("contents", replyText)
                                put("chatType", "TEXT")
                            }
                            val result = ApiClient.postJson(
                                appConfig.getEndpointByPath("/comm/sendchat"), sendBody
                            )
                            Log.d(TAG, "REPLY: sendChat result=${result.optInt("errorCode", -1)}")
                        } catch (e: Exception) {
                            Log.e(TAG, "REPLY: sendChat failed", e)
                        }
                        try {
                            val readBody = JSONObject().apply {
                                put("channelCode", key)
                                put("readUserId", userId)
                            }
                            val result = ApiClient.postJson(
                                appConfig.getEndpointByPath("/comm/readchat"), readBody
                            )
                            Log.d(TAG, "REPLY: readChat result=${result.optInt("errorCode", -1)}")
                        } catch (e: Exception) {
                            Log.e(TAG, "REPLY: readChat failed", e)
                        }
                    }
                    // 답장 전송 후 알림 취소
                    notificationGroupManager.cancel(key)
                }
            }
        }
    }
}
