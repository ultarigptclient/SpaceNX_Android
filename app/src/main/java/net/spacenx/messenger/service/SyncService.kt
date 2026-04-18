package net.spacenx.messenger.service

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.data.local.DatabaseProvider
import net.spacenx.messenger.data.repository.BuddyRepository
import net.spacenx.messenger.data.repository.ChannelRepository
import net.spacenx.messenger.data.repository.MessageRepository
import net.spacenx.messenger.data.repository.NotiRepository
import net.spacenx.messenger.data.repository.OrgRepository
import net.spacenx.messenger.data.repository.ProjectRepository
import net.spacenx.messenger.data.repository.PubSubRepository
import net.spacenx.messenger.data.repository.UserNameCache
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 초기/백그라운드 동기화 오케스트레이션.
 * LoginViewModel에서 sync 관련 로직을 분리, 앱 생명주기 동안 상태 유지.
 * Activity가 재생성되어도 sync가 중단되지 않으며, cancelAllSync()로 명시적 취소.
 */
@Singleton
class SyncService @Inject constructor(
    private val orgRepo: OrgRepository,
    private val buddyRepo: BuddyRepository,
    private val channelRepo: ChannelRepository,
    private val messageRepo: MessageRepository,
    private val notiRepo: NotiRepository,
    private val pubSubRepo: PubSubRepository,
    private val appConfig: AppConfig,
    private val databaseProvider: DatabaseProvider,
    val userNameCache: UserNameCache
) {

    companion object {
        private const val TAG = "SyncService"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backgroundSyncJobs = mutableListOf<Job>()
    private var orgBuddySyncJob: Job? = null

    // ── Sync 완료 시그널 (BridgeDispatcher가 await) ──
    var syncBuddyDeferred = CompletableDeferred<Unit>()
        private set
    var syncChannelDeferred = CompletableDeferred<Unit>()
        private set
    var syncChatDeferred = CompletableDeferred<Unit>()
        private set

    /** 마지막 buddy sync 완료 시각 (ms). OrgHandler에서 중복 sync 방지용 */
    @Volatile var lastBuddySyncMs: Long = 0L
        private set

    fun syncOrgAndBuddy(userId: String, useCache: Boolean = false) {
        Log.d(TAG, "syncOrgAndBuddy: userId=$userId, useCache=$useCache")
        // 이전 sync Job 취소 (계정 전환 / 재연결 시 이전 사용자 작업 중단)
        orgBuddySyncJob?.cancel()
        // 이전 deferred 완료 → 재생성 (race condition 방지)
        syncBuddyDeferred.complete(Unit)
        syncChannelDeferred.complete(Unit)
        syncChatDeferred.complete(Unit)
        syncBuddyDeferred = CompletableDeferred()
        syncChannelDeferred = CompletableDeferred()
        syncChatDeferred = CompletableDeferred()

        val logPrefix = if (useCache) "reconnect " else ""
        orgBuddySyncJob = scope.launch {
            try {
                coroutineScope {
                    // syncOrg, syncMyPart, syncChannel 동시 시작
                    // syncBuddy는 org DB 의존 → syncOrg 완료 후
                    val orgDeferred = async {
                        (withTimeoutOrNull(15_000L) { orgRepo.syncOrg(userId) } ?: false)
                            .also { Log.d(TAG, "${logPrefix}syncOrg=$it") }
                    }
                    val myPartDeferred = async {
                        if (appConfig.getMyDeptId() == null) {
                            (withTimeoutOrNull(15_000L) { buddyRepo.syncMyPart(userId) } ?: false)
                                .also { Log.d(TAG, "${logPrefix}syncMyPart=$it") }
                        } else true
                    }
                    val channelDeferred = async {
                        (withTimeoutOrNull(15_000L) { channelRepo.syncChannel(userId) } ?: false)
                            .also { Log.d(TAG, "${logPrefix}syncChannel=$it") }
                    }

                    // syncOrg 완료 → syncBuddy
                    orgDeferred.await()
                    val buddySuccess = withTimeoutOrNull(15_000L) { buddyRepo.syncBuddy(userId) } ?: false
                    Log.d(TAG, "${logPrefix}syncBuddy=$buddySuccess")

                    // myPart 완료 대기 (buildBuddyListJson에서 myDeptId 사용)
                    myPartDeferred.await()
                    lastBuddySyncMs = System.currentTimeMillis()
                    Log.d(TAG, "buddy sync complete — lastBuddySyncMs set, signaling startBackgroundSync")
                    syncBuddyDeferred.complete(Unit)
                    val rawBuddyJson = buildBuddyListJson()
                    autoSubscribeUsers(rawBuddyJson)

                    // syncChannel 완료 → syncChat
                    channelDeferred.await()
                    syncChannelDeferred.complete(Unit)
                    val chatSuccess = withTimeoutOrNull(15_000L) { channelRepo.syncChat(userId) } ?: false
                    Log.d(TAG, "${logPrefix}syncChat=$chatSuccess")
                    syncChatDeferred.complete(Unit)
                }
            } catch (e: Exception) {
                Log.e(TAG, "${logPrefix}syncOrgAndBuddy failed: ${e.message}", e)
            } finally {
                syncBuddyDeferred.complete(Unit)
                syncChannelDeferred.complete(Unit)
                syncChatDeferred.complete(Unit)
            }
        }
    }

    fun startBackgroundSync(
        notifyCallback: (String) -> Unit,
        pushCallback: ((String, JSONObject) -> Unit)? = null,
        syncStatusCallback: ((String, String, Int?, Int?) -> Unit)? = null,
        projectRepo: ProjectRepository? = null
    ) {
        backgroundSyncJobs.forEach { it.cancel() }
        backgroundSyncJobs.clear()

        // org → buddy
        backgroundSyncJobs += scope.launch {
            try {
                syncStatusCallback?.invoke("org", "syncing", null, null)
                syncBuddyDeferred.await()
                userNameCache.clear()
                syncStatusCallback?.invoke("org", "done", null, null)
                notifyCallback("orgReady")
            } catch (_: Exception) {
                syncStatusCallback?.invoke("org", "error", null, null)
                notifyCallback("orgReady")
            }
            try {
                syncStatusCallback?.invoke("buddy", "syncing", null, null)
                syncStatusCallback?.invoke("buddy", "done", null, null)
                notifyCallback("buddyReady")
            } catch (_: Exception) {
                syncStatusCallback?.invoke("buddy", "error", null, null)
                notifyCallback("buddyReady")
            }
        }
        // channel → chat
        backgroundSyncJobs += scope.launch {
            try {
                syncStatusCallback?.invoke("channel", "syncing", null, null)
                syncChannelDeferred.await()
                val chCount = try { channelRepo.getChannelCount() } catch (_: Exception) { 0 }
                syncStatusCallback?.invoke("channel", "done", chCount, null)
                notifyCallback("channelReady")
            } catch (_: Exception) {
                syncStatusCallback?.invoke("channel", "error", null, null)
                notifyCallback("channelReady")
            }
            try {
                syncStatusCallback?.invoke("chat", "syncing", null, null)
                syncChatDeferred.await()
                syncStatusCallback?.invoke("chat", "done", null, null)
                notifyCallback("chatReady")
                notifyCallback("channelReady")
            } catch (_: Exception) {
                syncStatusCallback?.invoke("chat", "error", null, null)
                notifyCallback("chatReady")
            }
        }
        // message
        backgroundSyncJobs += scope.launch {
            try {
                syncStatusCallback?.invoke("message", "syncing", null, null)
                val result = messageRepo.syncMessage()
                val msgCounts = try { messageRepo.getCounts() } catch (_: Exception) { emptyMap() }
                val inboxCount = msgCounts["inbox"] ?: 0
                syncStatusCallback?.invoke("message", "done", inboxCount, null)
                notifyCallback("messageReady")
                @Suppress("UNCHECKED_CAST")
                val newMessages = result["newMessages"] as? List<JSONObject> ?: emptyList()
                for (msg in newMessages) {
                    val pushData = JSONObject(msg.toString())
                    pushData.put("command", "SendMessageEvent")
                    val receiversRaw = pushData.opt("receivers")
                    if (receiversRaw is String) {
                        try { pushData.put("receivers", JSONArray(receiversRaw)) } catch (_: Exception) {}
                    }
                    pushCallback?.invoke("SendMessageEvent", pushData)
                }
            } catch (e: Exception) {
                Log.e(TAG, "syncMessage error: ${e.message}")
                syncStatusCallback?.invoke("message", "error", null, null)
                notifyCallback("messageReady")
            }
        }
        // noti
        backgroundSyncJobs += scope.launch {
            try {
                syncStatusCallback?.invoke("noti", "syncing", null, null)
                notiRepo.syncNoti()
                val notiCounts = try { notiRepo.getCounts() } catch (_: Exception) { emptyMap() }
                val totalCount = notiCounts["total"] ?: 0
                syncStatusCallback?.invoke("noti", "done", totalCount, null)
                notifyCallback("notiReady")
            } catch (e: Exception) {
                Log.e(TAG, "syncNoti error: ${e.message}")
                syncStatusCallback?.invoke("noti", "error", null, null)
                notifyCallback("notiReady")
            }
        }
        // project / issue / thread
        if (projectRepo != null) {
            backgroundSyncJobs += scope.launch {
                try {
                    syncStatusCallback?.invoke("project", "syncing", null, null)
                    projectRepo.syncProject()
                    syncStatusCallback?.invoke("project", "done", null, null)
                    notifyCallback("projectReady")
                } catch (e: Exception) {
                    Log.e(TAG, "syncProject error: ${e.message}")
                    syncStatusCallback?.invoke("project", "error", null, null)
                    notifyCallback("projectReady")
                }
                try {
                    syncStatusCallback?.invoke("issue", "syncing", null, null)
                    projectRepo.syncIssue()
                    syncStatusCallback?.invoke("issue", "done", null, null)
                    notifyCallback("issueReady")
                } catch (e: Exception) {
                    Log.e(TAG, "syncIssue error: ${e.message}")
                    syncStatusCallback?.invoke("issue", "error", null, null)
                    notifyCallback("issueReady")
                }
                try {
                    syncStatusCallback?.invoke("thread", "syncing", null, null)
                    projectRepo.syncThread()
                    syncStatusCallback?.invoke("thread", "done", null, null)
                    notifyCallback("threadReady")
                } catch (e: Exception) {
                    Log.e(TAG, "syncThread error: ${e.message}")
                    syncStatusCallback?.invoke("thread", "error", null, null)
                    notifyCallback("threadReady")
                }
                try {
                    syncStatusCallback?.invoke("calendar", "syncing", null, null)
                    projectRepo.syncCalendar()
                    syncStatusCallback?.invoke("calendar", "done", null, null)
                    notifyCallback("calReady")
                } catch (e: Exception) {
                    Log.e(TAG, "syncCalendar error: ${e.message}")
                    syncStatusCallback?.invoke("calendar", "error", null, null)
                    notifyCallback("calReady")
                }
                try {
                    syncStatusCallback?.invoke("todo", "syncing", null, null)
                    projectRepo.syncTodo()
                    syncStatusCallback?.invoke("todo", "done", null, null)
                    notifyCallback("todoReady")
                } catch (e: Exception) {
                    Log.e(TAG, "syncTodo error: ${e.message}")
                    syncStatusCallback?.invoke("todo", "error", null, null)
                    notifyCallback("todoReady")
                }
            }
        }
    }

    fun syncBuddy(userId: String) {
        scope.launch {
            val success = buddyRepo.syncBuddy(userId)
            Log.d(TAG, "syncBuddy result: $success")
        }
    }

    fun cancelAllSync() {
        orgBuddySyncJob?.cancel()
        orgBuddySyncJob = null
        backgroundSyncJobs.forEach { it.cancel() }
        backgroundSyncJobs.clear()
        lastBuddySyncMs = 0L
    }

    // ── 내부 헬퍼 ──

    private suspend fun buildBuddyListJson(): String {
        val rawBuddyJson = buddyRepo.getBuddyListWithUserInfo()
        try {
            val buddyJson = JSONObject(rawBuddyJson)
            val myDeptJson = JSONObject(orgRepo.getMyDeptAsJson())
            val deptId = myDeptJson.optString("deptId", "")
            if (deptId.isNotEmpty()) {
                val buddies = buddyJson.optJSONArray("buddies") ?: JSONArray()
                val deptBuddyId = "_dept_$deptId"
                buddies.put(JSONObject().apply {
                    put("buddyId", deptBuddyId)
                    put("buddyParent", "0")
                    put("buddyName", myDeptJson.optString("deptName", "").ifEmpty { "내부서" })
                    put("buddyType", "6")
                    put("buddyOrder", "000000")
                })
                val users = myDeptJson.optJSONArray("users") ?: JSONArray()
                for (i in 0 until users.length()) {
                    val user = users.getJSONObject(i)
                    buddies.put(JSONObject().apply {
                        put("buddyId", user.optString("userId", ""))
                        put("buddyParent", deptBuddyId)
                        put("buddyName", user.optString("userName", ""))
                        put("buddyType", "0")
                        put("buddyOrder", user.optString("userOrder", "999999"))
                        put("userId", user.optString("userId", ""))
                        put("deptName", user.optString("deptName", ""))
                        put("companyCode", user.optString("companyCode", ""))
                        put("posName", user.optString("posName", ""))
                        put("loginId", user.optString("loginId", ""))
                        put("phone", user.optString("phone", ""))
                        put("mobile", user.optString("mobile", ""))
                        put("userName", user.optString("userName", ""))
                        put("email", user.optString("email", ""))
                    })
                }
                buddyJson.put("buddies", buddies)
            }
            return buddyJson.toString()
        } catch (e: Exception) {
            Log.e(TAG, "buildBuddyListJson failed: ${e.message}")
        }
        return rawBuddyJson
    }

    private suspend fun autoSubscribeUsers(rawBuddyJson: String) {
        try {
            val userIds = mutableSetOf<String>()
            val buddyJson = JSONObject(rawBuddyJson)
            val buddies = buddyJson.optJSONArray("buddies")
            if (buddies != null) {
                for (i in 0 until buddies.length()) {
                    val buddy = buddies.getJSONObject(i)
                    val type = buddy.optString("buddyType", "")
                    if (type == "0" || type == "U") {
                        val buddyId = buddy.optString("buddyId", "")
                        if (buddyId.isNotEmpty()) userIds.add(buddyId)
                    }
                }
            }
            Log.d("Presence", "[3] autoSubscribe: buddyCount=${userIds.size}")
            if (userIds.isNotEmpty()) {
                pubSubRepo.sendSubscribe(pubSubRepo.defaultTopic, "USER", userIds.toList())
            }
        } catch (e: Exception) {
            Log.e(TAG, "autoSubscribeUsers failed: ${e.message}", e)
        }
    }
}
