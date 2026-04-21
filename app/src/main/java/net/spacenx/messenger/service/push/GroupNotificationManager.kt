package net.spacenx.messenger.service.push

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Looper
import android.text.Html
import android.util.Log
import android.util.LruCache
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import net.spacenx.messenger.R
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.common.Constants
import net.spacenx.messenger.data.remote.api.ApiClient
import net.spacenx.messenger.ui.MainActivity
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 그룹 알림 표시 인터페이스.
 */
interface GroupNotificationManager {
    /**
     * @param type              알림 타입 (TALK, MESSAGE, MCU, NOTI, CUSTOM, mail)
     * @param key               메시지 키 (채팅방 코드 또는 카테고리)
     * @param userName          발신자 이름
     * @param message           메시지 내용
     * @param arg               추가 인자 (MCU info, mail URL 등)
     * @param conversationTitle 채팅방 이름 (MessagingStyle 헤더; null이면 userName 표시)
     * @param senderId          발신자 userId (프로필 사진 로딩용)
     */
    fun showNotify(
        type: String,
        key: String,
        userName: String,
        message: String,
        arg: String?,
        conversationTitle: String? = null,
        senderId: String? = null
    ): Boolean

    fun cancel(key: String?)
    fun clearAll()
}

/**
 * NotificationCompat 기반 그룹 알림 구현.
 *
 * - 채팅(TALK): MessagingStyle — 채팅방별 메시지 누적, 발신자 프로필 사진, 읽음 액션 버튼
 *   알림 표시: nm.notify(tag=channelCode, id=TALK_ID) — 앱 재시작 후에도 tag로 cancel 가능
 * - 쪽지/알림 등: BigText 단건 알림 (id 기반)
 */
class NotificationGroupManager(
    private val context: Context,
    private val appConfig: AppConfig
) : GroupNotificationManager {

    companion object {
        private val NO_PHOTO_SENTINEL: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8)
        private const val TAG = "GroupNotification"
        private const val CHANNEL_ID = "hybrid_web_messenger_channel"
        private const val CHANNEL_NAME = "메신저 알림"
        private const val CHANNEL_DESCRIPTION = "메신저 알림 채널"
        private const val GROUP_KEY = "net.spacenx.messenger"
        private const val SUMMARY_ID = 200

        /** TALK 알림은 tag=channelCode + 고정 id로 관리 → 앱 재시작 후에도 취소 가능 */
        private const val TALK_ID = 1

        private const val MAX_MESSAGES_PER_KEY = 7
        private const val PHOTO_SIZE_PX = 128
    }

    private data class PendingMessage(
        val sender: String,
        val senderId: String,
        val text: String,
        val timestamp: Long
    )

    /** 채팅방별 누적 메시지 (MessagingStyle용). thread-safe list 사용. */
    private val messageHistory = ConcurrentHashMap<String, MutableList<PendingMessage>>()

    /**
     * 비-TALK 알림용 key→notificationId 매핑.
     * TALK는 tag 기반이므로 여기 저장 안 함.
     */
    private val simpleKeyMap = ConcurrentHashMap<String, Int>()

    /** 현재 활성 TALK 채널 추적 (summary 관리용). */
    private val activeTalkKeys = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val nextId = AtomicInteger(SUMMARY_ID + 1)

    /** 프로필 사진 메모리 캐시 (userId → circular Bitmap, NO_PHOTO_SENTINEL = 404 확인됨) */
    private val photoCache: LruCache<String, Bitmap> = LruCache(40)

    fun invalidatePhotoCache(userId: String) { photoCache.remove(userId) }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = CHANNEL_DESCRIPTION
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), audioAttributes)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission")
    override fun showNotify(
        type: String,
        key: String,
        userName: String,
        message: String,
        arg: String?,
        conversationTitle: String?,
        senderId: String?
    ): Boolean {
        Log.d(TAG, "showNotify type=$type key=$key user=$userName sender=$senderId")
        val nm = NotificationManagerCompat.from(context)

        when (type) {
            Constants.TYPE_TALK -> {
                val notification = buildChatNotification(key, userName, senderId ?: "", message, conversationTitle)
                    .build()
                nm.notify(key, TALK_ID, notification)
                activeTalkKeys.add(key)
                // TALK는 채팅방별 독립 알림 — GROUP_SUMMARY 없음
                // GROUP_SUMMARY(id=200)가 남아 있으면 Samsung One UI가 요약 카드로 덮어써
                // setLargeIcon/shortcut 프로필 사진이 접힌 상태에서 안 보이므로 명시적 취소
                nm.cancel(SUMMARY_ID)
            }
            else -> {
                val notifyKey = arg ?: key
                val notifyId = simpleKeyMap.getOrPut(notifyKey) { nextId.getAndIncrement() }
                val notification = buildSimpleNotification(type, notifyKey, userName, message, arg, senderId ?: "")
                    .build()
                nm.notify(notifyId, notification)
                showGroupSummary(nm)
            }
        }
        return true
    }

    /** TALK 타입: MessagingStyle — 채팅방별 메시지 누적 */
    private fun buildChatNotification(
        key: String,
        userName: String,
        senderId: String,
        message: String,
        conversationTitle: String?
    ): NotificationCompat.Builder {
        val messages = messageHistory.getOrPut(key) {
            Collections.synchronizedList(mutableListOf())
        }
        synchronized(messages) {
            messages.add(PendingMessage(userName, senderId, message, System.currentTimeMillis()))
            if (messages.size > MAX_MESSAGES_PER_KEY) messages.removeAt(0)
        }

        val mePerson = Person.Builder().setName("나").build()
        val style = NotificationCompat.MessagingStyle(mePerson)
        if (!conversationTitle.isNullOrEmpty()) {
            style.conversationTitle = conversationTitle
        }

        // 발신자 프로필 사진 (백그라운드 스레드에서만 로딩)
        val senderBitmap: Bitmap? = if (senderId.isNotEmpty() && !isMainThread()) {
            loadBitmap(senderId)
        } else null
        Log.d(TAG, "buildChatNotification: senderBitmap=${if (senderBitmap != null) "non-null(${senderBitmap.width}x${senderBitmap.height})" else "null"}, isMainThread=${isMainThread()}")

        synchronized(messages) {
            messages.forEach { msg ->
                val personBuilder = Person.Builder().setName(msg.sender)
                if (msg.senderId.isNotEmpty() && !isMainThread()) {
                    val bmp = if (msg.senderId == senderId) senderBitmap else loadBitmap(msg.senderId)
                    if (bmp != null) personBuilder.setIcon(IconCompat.createWithBitmap(bmp))
                }
                style.addMessage(msg.text, msg.timestamp, personBuilder.build())
            }
        }

        val pendingIntent = createMainPendingIntent(Constants.TYPE_TALK, key, null, key.hashCode())
        val readPendingIntent = createReadPendingIntent(key)

        // Conversation shortcut 등록 → Samsung One UI 접힌 상태에서 프로필 사진 표시
        val shortcutId = "chat_$key"
        // label 우선순위: conversationTitle → userName → key (빈 문자열 방지)
        val shortcutLabel = conversationTitle?.takeIf { it.isNotEmpty() }
            ?: userName.takeIf { it.isNotEmpty() }
            ?: key
        pushConversationShortcut(shortcutId, shortcutLabel, senderId, senderBitmap, pendingIntent)

        val replyPendingIntent = createReplyPendingIntent(key)
        val remoteInput = RemoteInput.Builder(NotificationActionReceiver.REPLY_RESULT_KEY)
            .setLabel("답장")
            .build()
        val replyAction = NotificationCompat.Action.Builder(0, "답장", replyPendingIntent)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .build()

        val msgCount = synchronized(messages) { messages.size }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(senderBitmap)
            .setContentIntent(pendingIntent)
            .setStyle(style)
            .setShortcutId(shortcutId)           // Samsung One UI: 대화 아이콘 = shortcut 아이콘
            // TALK는 그룹 없이 독립 알림 — GROUP_KEY 미설정 시 setLargeIcon이 접힌 상태에서도 표시됨
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setNumber(msgCount)
            .addAction(NotificationCompat.Action.Builder(0, "읽음", readPendingIntent).build())
            .addAction(replyAction)
    }

    /** 쪽지/알림/MCU 등: 단순 BigText */
    private fun buildSimpleNotification(
        type: String,
        key: String,
        userName: String,
        message: String,
        arg: String?,
        senderId: String = ""
    ): NotificationCompat.Builder {
        val notifyId = simpleKeyMap[key] ?: nextId.get()
        val pendingIntent = createMainPendingIntent(type, key, arg, notifyId)
        val title = when (type) {
            Constants.TYPE_MESSAGE       -> userName.ifEmpty { "쪽지" }
            Constants.TYPE_SYSTEM_NOTIFY -> "알림"
            Constants.TYPE_MCU           -> message
            else -> userName.ifEmpty { "알림" }
        }
        val htmlDecoded = if (type == Constants.TYPE_MCU) userName
                          else Html.fromHtml(message, Html.FROM_HTML_MODE_COMPACT).toString().trim()
        val body = if (type == Constants.TYPE_MESSAGE) "쪽지\n$htmlDecoded" else htmlDecoded

        // 쪽지: 발신자 프로필 사진 로딩 (백그라운드 스레드에서만)
        val effectiveSenderId = senderId.takeIf { it.isNotEmpty() && it != "null" } ?: ""
        val senderBitmap: Bitmap? = if (type == Constants.TYPE_MESSAGE && effectiveSenderId.isNotEmpty() && !isMainThread()) {
            loadBitmap(effectiveSenderId)
        } else null

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .apply { if (senderBitmap != null) setLargeIcon(senderBitmap) }
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setGroup(GROUP_KEY)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
    }

    @SuppressLint("MissingPermission")
    private fun showGroupSummary(nm: NotificationManagerCompat) {
        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("메신저")
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()
        nm.notify(SUMMARY_ID, summary)
    }

    /** 앱 실행 PendingIntent (푸시 클릭 → MainActivity) */
    private fun createMainPendingIntent(
        type: String,
        key: String,
        arg: String?,
        requestCode: Int
    ): PendingIntent {
        if (type == Constants.TYPE_MAIL && !arg.isNullOrEmpty()) {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(arg))
            return PendingIntent.getActivity(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("NotificationType", type)
            putExtra("Key", key)
            if (arg != null) putExtra("Arg", arg)
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 읽음 버튼 PendingIntent → NotificationActionReceiver */
    private fun createReadPendingIntent(key: String): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_READ
            putExtra(NotificationActionReceiver.EXTRA_NOTIFY_KEY, key)
        }
        return PendingIntent.getBroadcast(
            context,
            key.hashCode() xor 0x10000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 답장 버튼 PendingIntent → NotificationActionReceiver (RemoteInput 전달용 FLAG_MUTABLE 필수) */
    private fun createReplyPendingIntent(key: String): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_REPLY
            putExtra(NotificationActionReceiver.EXTRA_NOTIFY_KEY, key)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            key.hashCode() xor 0x20000,
            intent,
            flags
        )
    }

    /**
     * Dynamic shortcut 등록/갱신.
     * Samsung One UI는 setShortcutId()로 연결된 shortcut의 아이콘을
     * 접힌 알림 카드 아이콘으로 표시함.
     */
    private fun pushConversationShortcut(
        shortcutId: String,
        label: String,
        senderId: String,
        bitmap: Bitmap?,
        pendingIntent: PendingIntent
    ) {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
            }
            val icon = when {
                bitmap != null -> IconCompat.createWithBitmap(bitmap)
                else -> IconCompat.createWithResource(context, R.mipmap.ic_launcher)
            }
            val person = Person.Builder()
                .setName(label)
                .setIcon(icon)
                .setImportant(true)
                .build()
            val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
                .setLongLived(true)
                .setShortLabel(label)
                .setIcon(icon)
                .setPerson(person)
                .setIntent(intent)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        } catch (e: Exception) {
            Log.w(TAG, "pushConversationShortcut failed: ${e.message}")
        }
    }

    /**
     * 프로필 사진 Bitmap 동기 로딩.
     * 캐시 히트 시 즉시 반환, 미스 시 HTTP 다운로드.
     * 반드시 백그라운드 스레드에서 호출할 것.
     */
    private fun loadBitmap(userId: String): Bitmap? {
        val cached = photoCache.get(userId)
        if (cached != null) return if (cached === NO_PHOTO_SENTINEL) null else cached

        return try {
            val baseUrl = appConfig.getRestBaseUrl().trimEnd('/')
            val url = "$baseUrl/photo/$userId"
            val request = okhttp3.Request.Builder().url(url).build()
            val response = ApiClient.okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                photoCache.put(userId, NO_PHOTO_SENTINEL)
                return null
            }
            val bytes = response.body?.bytes() ?: run {
                photoCache.put(userId, NO_PHOTO_SENTINEL)
                return null
            }
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: run {
                photoCache.put(userId, NO_PHOTO_SENTINEL)
                return null
            }
            val resized = Bitmap.createScaledBitmap(raw, PHOTO_SIZE_PX, PHOTO_SIZE_PX, true)
            val circular = toCircleBitmap(resized)
            photoCache.put(userId, circular)
            Log.d(TAG, "loadBitmap: loaded photo for $userId")
            circular
        } catch (e: Exception) {
            Log.w(TAG, "loadBitmap: failed for $userId", e)
            null
        }
    }

    private fun toCircleBitmap(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return output
    }

    private fun isMainThread() = Looper.myLooper() == Looper.getMainLooper()

    override fun cancel(key: String?) {
        val nm = NotificationManagerCompat.from(context)
        if (key == null) {
            nm.cancelAll()
            simpleKeyMap.clear()
            messageHistory.clear()
            activeTalkKeys.clear()
        } else {
            nm.cancel(key, TALK_ID)
            activeTalkKeys.remove(key)
            messageHistory.remove(key)
            val notifyId = simpleKeyMap.remove(key)
            if (notifyId != null) nm.cancel(notifyId)
            if (activeTalkKeys.isEmpty() && simpleKeyMap.isEmpty()) {
                nm.cancel(SUMMARY_ID)
            }
        }
    }

    override fun clearAll() = cancel(null)
}
