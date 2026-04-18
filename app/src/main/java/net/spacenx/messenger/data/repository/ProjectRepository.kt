package net.spacenx.messenger.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.spacenx.messenger.util.FileLogger
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.common.JsonStreamUtil
import net.spacenx.messenger.common.parseStream
import kotlinx.coroutines.withTimeoutOrNull
import net.spacenx.messenger.data.local.ChatDatabase
import net.spacenx.messenger.data.local.DatabaseProvider
import net.spacenx.messenger.data.local.entity.*
import java.util.Calendar as JavaCalendar
import net.spacenx.messenger.data.remote.api.ApiClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 프로젝트/이슈/스레드 delta sync + 로컬 캐시 조회 + CUD 즉시 반영
 */
class ProjectRepository(
    private val dbProvider: DatabaseProvider,
    private val appConfig: AppConfig,
    private val sessionManager: SocketSessionManager? = null
) {
    companion object {
        private const val TAG = "ProjectRepository"
        private const val SYNC_PAGE_SIZE = 200
    }

    // ══════════════════════════════════════
    // syncProject — 프로젝트 delta sync
    // ══════════════════════════════════════

    suspend fun syncProject(): Boolean = withContext(Dispatchers.IO) {
        try {
            val db = dbProvider.getProjectDatabase()
            val userId = appConfig.getSavedUserId() ?: return@withContext false
            val token = awaitToken() ?: return@withContext false
            var totalFetched = 0

            while (true) {
                val lastOffset = db.syncMetaDao().getValue("projectEventOffset") ?: 0L
                Log.d(TAG, "syncProject: offset=$lastOffset")
                if (lastOffset == 0L) FileLogger.log(TAG, "syncProject REQ offset=$lastOffset")

                val body = JSONObject().apply {
                    put("userId", userId)
                    put("projectEventOffset", lastOffset)
                    put("reset", lastOffset == 0L)
                    put("limit", SYNC_PAGE_SIZE)
                }
                val result = ApiClient.postJson(appConfig.getEndpointByPath("/comm/syncproject"), body, token)
                if (result.optInt("errorCode", -1) != 0) break

                val events = result.optJSONArray("events") ?: JSONArray()
                if (events.length() > 0) {
                    totalFetched += events.length()
                    processProjectEvents(db, events)
                    val lastEventId = result.optLong("lastEventId", 0L)
                    if (lastEventId > 0) db.syncMetaDao().insert(ProjectSyncMetaEntity("projectEventOffset", lastEventId))
                }

                val hasMore = result.optBoolean("hasMore", false)
                if (!hasMore || events.length() == 0) break
            }

            Log.d(TAG, "syncProject complete: $totalFetched events")
            FileLogger.log(TAG, "syncProject DONE totalEvents=$totalFetched")
            true
        } catch (e: Exception) {
            Log.e(TAG, "syncProject error: ${e.message}", e)
            FileLogger.log(TAG, "syncProject ERROR ${e.message}")
            false
        }
    }

    private suspend fun processProjectEvents(db: net.spacenx.messenger.data.local.ProjectDatabase, events: JSONArray) {
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            val eventType = event.optString("eventType", "")
            val project = event.optJSONObject("project") ?: JSONObject()
            val projectCode = project.optString("projectCode", event.optString("projectCode", ""))
            val members = event.optJSONArray("members")
            val channels = event.optJSONArray("channels")

            when (eventType) {
                "CREATE_PROJECT", "MOD_PROJECT", "ADD_CHANNEL", "REMOVE_CHANNEL" -> {
                    if (projectCode.isNotEmpty() && project.length() > 0) {
                        db.projectDao().insertProjects(listOf(ProjectEntity(
                            projectCode = projectCode,
                            projectName = project.optString("projectName", ""),
                            icon = project.optString("icon", ""),
                            color = project.optString("color", ""),
                            projectStatus = project.optString("projectStatus", "ACTIVE"),
                            ownerUserId = project.optString("ownerUserId", ""),
                            description = project.optString("description", ""),
                            deadline = project.optLong("deadline", 0L),
                            modDate = project.optLong("modDate", 0L),
                            createdDate = project.optLong("createdDate", 0L)
                        )))
                    }
                    if (members != null) {
                        val memberList = (0 until members.length()).map { j ->
                            val m = members.getJSONObject(j)
                            ProjectMemberEntity(
                                projectCode = m.optString("projectCode", projectCode),
                                userId = m.optString("userId", ""),
                                createdDate = m.optLong("createdDate", 0L)
                            )
                        }
                        if (memberList.isNotEmpty()) db.projectDao().insertMembers(memberList)
                    }
                    if (channels != null) {
                        val channelList = (0 until channels.length()).map { j ->
                            val c = channels.getJSONObject(j)
                            ProjectChannelEntity(
                                projectCode = c.optString("projectCode", projectCode),
                                channelCode = c.optString("channelCode", ""),
                                createdDate = c.optLong("createdDate", 0L)
                            )
                        }
                        if (channelList.isNotEmpty()) db.projectDao().insertChannels(channelList)
                    }
                }
                "DELETE_PROJECT" -> {
                    if (projectCode.isNotEmpty()) {
                        db.projectDao().deleteProject(projectCode)
                        db.projectDao().deleteAllMembers(projectCode)
                        db.projectDao().deleteAllChannels(projectCode)
                        db.issueDao().deleteByProject(projectCode)
                    }
                }
                "ADD_MEMBER" -> {
                    if (members != null) {
                        val memberList = (0 until members.length()).map { j ->
                            val m = members.getJSONObject(j)
                            ProjectMemberEntity(
                                projectCode = m.optString("projectCode", projectCode),
                                userId = m.optString("userId", ""),
                                createdDate = m.optLong("createdDate", 0L)
                            )
                        }
                        if (memberList.isNotEmpty()) db.projectDao().insertMembers(memberList)
                    }
                }
                "REMOVE_MEMBER" -> {
                    val removedUserId = event.optString("userId", "")
                    if (projectCode.isNotEmpty() && removedUserId.isNotEmpty()) {
                        db.projectDao().deleteMember(projectCode, removedUserId)
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════
    // syncIssue — 이슈 delta sync
    // ══════════════════════════════════════

    suspend fun syncIssue(): Boolean = withContext(Dispatchers.IO) {
        try {
            val db = dbProvider.getProjectDatabase()
            val userId = appConfig.getSavedUserId() ?: return@withContext false
            val token = awaitToken() ?: return@withContext false
            var totalFetched = 0

            while (true) {
                val lastOffset = db.syncMetaDao().getValue("issueEventOffset") ?: 0L
                Log.d(TAG, "syncIssue: offset=$lastOffset")
                val body = JSONObject().apply {
                    put("userId", userId)
                    put("issueEventOffset", lastOffset)
                    put("reset", lastOffset == 0L)
                    put("limit", SYNC_PAGE_SIZE)
                }
                val result = ApiClient.postJson(appConfig.getEndpointByPath("/comm/syncissue"), body, token)
                if (result.optInt("errorCode", -1) != 0) break

                val events = result.optJSONArray("events") ?: JSONArray()
                if (events.length() > 0) {
                    totalFetched += events.length()
                    processIssueEvents(db, events)
                    val lastEventId = result.optLong("lastEventId", 0L)
                    if (lastEventId > 0) db.syncMetaDao().insert(ProjectSyncMetaEntity("issueEventOffset", lastEventId))
                }

                if (!result.optBoolean("hasMore", false) || events.length() == 0) break
            }

            Log.d(TAG, "syncIssue complete: $totalFetched events")
            FileLogger.log(TAG, "syncIssue DONE totalEvents=$totalFetched")
            true
        } catch (e: Exception) {
            Log.e(TAG, "syncIssue error: ${e.message}", e)
            FileLogger.log(TAG, "syncIssue ERROR ${e.message}")
            false
        }
    }

    private suspend fun processIssueEvents(db: net.spacenx.messenger.data.local.ProjectDatabase, events: JSONArray) {
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            val eventType = event.optString("eventType", "")
            val issue = event.optJSONObject("issue") ?: JSONObject()
            val issueCode = issue.optString("issueCode", event.optString("issueCode", ""))

            when (eventType) {
                "CREATE_ISSUE", "MOD_ISSUE" -> {
                    if (issueCode.isNotEmpty() && issue.length() > 0) {
                        db.issueDao().insertAll(listOf(IssueEntity(
                            issueCode = issueCode,
                            projectCode = issue.optString("projectCode", ""),
                            channelCode = issue.optString("channelCode", ""),
                            title = issue.optString("title", ""),
                            description = issue.optString("description", ""),
                            issueType = issue.optString("issueType", "TASK"),
                            issueStatus = issue.optString("issueStatus", "TODO"),
                            priority = issue.optString("priority", "NORMAL"),
                            assigneeUserId = issue.optString("assigneeUserId", ""),
                            reporterUserId = issue.optString("reporterUserId", ""),
                            labels = issue.optString("labels", ""),
                            dueDate = issue.optLong("dueDate", 0L),
                            completedDate = issue.optLong("completedDate", 0L),
                            modDate = issue.optLong("modDate", 0L),
                            createdDate = issue.optLong("createdDate", 0L),
                            threadCode = issue.optString("threadCode", ""),
                            commentCount = issue.optInt("commentCount", 0)
                        )))
                    }
                }
                "DELETE_ISSUE" -> {
                    if (issueCode.isNotEmpty()) db.issueDao().delete(issueCode)
                }
            }
        }
    }

    // ══════════════════════════════════════
    // syncCalendar — 달력 delta sync
    // ══════════════════════════════════════

    suspend fun syncCalendar(): Boolean = withContext(Dispatchers.IO) {
        try {
            val db = dbProvider.getProjectDatabase()
            val userId = appConfig.getSavedUserId() ?: return@withContext false
            val token = awaitToken() ?: return@withContext false
            var totalFetched = 0

            // offset > 0이지만 테이블이 비어있으면 DB 마이그레이션 직후 데이터 유실 상황 → 전체 재동기화
            val existingOffset = db.syncMetaDao().getValue("calEventOffset") ?: 0L
            if (existingOffset > 0L && db.calEventDao().getByUser(userId).isEmpty()) {
                db.syncMetaDao().insert(ProjectSyncMetaEntity("calEventOffset", 0L))
                Log.d(TAG, "syncCalendar: cal_events empty despite offset=$existingOffset, resetting for full re-sync")
            }

            while (true) {
                val lastOffset = db.syncMetaDao().getValue("calEventOffset") ?: 0L
                Log.d(TAG, "syncCalendar: offset=$lastOffset")

                val body = JSONObject().apply {
                    put("userId", userId)
                    put("calEventOffset", lastOffset)
                    put("reset", lastOffset == 0L)
                    put("limit", SYNC_PAGE_SIZE)
                }
                val result = ApiClient.postJson(appConfig.getEndpointByPath("/comm/synccalendar"), body, token)
                if (result.optInt("errorCode", -1) != 0) break

                val events = result.optJSONArray("events") ?: JSONArray()
                if (events.length() > 0) {
                    totalFetched += events.length()
                    processCalEvents(db, userId, events)
                    val lastEventId = result.optLong("lastEventId", 0L)
                    if (lastEventId > 0) db.syncMetaDao().insert(ProjectSyncMetaEntity("calEventOffset", lastEventId))
                }

                if (!result.optBoolean("hasMore", false) || events.length() == 0) break
            }

            Log.d(TAG, "syncCalendar complete: $totalFetched events")
            FileLogger.log(TAG, "syncCalendar DONE totalEvents=$totalFetched")
            true
        } catch (e: Exception) {
            Log.e(TAG, "syncCalendar error: ${e.message}", e)
            FileLogger.log(TAG, "syncCalendar ERROR ${e.message}")
            false
        }
    }

    private suspend fun processCalEvents(db: net.spacenx.messenger.data.local.ProjectDatabase, userId: String, events: JSONArray) {
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            val eventType = event.optString("eventType", "")
            // 서버 응답 키: "event" (초기 sync) 또는 calCode만 있는 delta
            val calObj = event.optJSONObject("event") ?: JSONObject()
            val calCode = calObj.optString("calCode", event.optString("calCode", ""))

            when (eventType) {
                "CREATE_CAL", "MOD_CAL" -> {
                    if (calCode.isNotEmpty()) {
                        db.calEventDao().insertAll(listOf(calObjToEntity(calObj, calCode, userId)))
                    }
                }
                "DEL_CAL" -> {
                    if (calCode.isNotEmpty()) db.calEventDao().delete(calCode)
                }
            }
        }
    }

    // ══════════════════════════════════════
    // syncTodo — Todo delta sync (projectCode=null 이슈)
    // ══════════════════════════════════════

    suspend fun syncTodo(): Boolean = withContext(Dispatchers.IO) {
        try {
            val db = dbProvider.getProjectDatabase()
            val userId = appConfig.getSavedUserId() ?: return@withContext false
            val token = awaitToken() ?: return@withContext false
            var totalFetched = 0

            while (true) {
                val lastOffset = db.syncMetaDao().getValue("todoEventOffset") ?: 0L
                Log.d(TAG, "syncTodo: offset=$lastOffset")

                val body = JSONObject().apply {
                    put("userId", userId)
                    put("todoEventOffset", lastOffset)
                    put("reset", lastOffset == 0L)
                    put("limit", SYNC_PAGE_SIZE)
                }
                val result = ApiClient.postJson(appConfig.getEndpointByPath("/comm/synctodo"), body, token)
                if (result.optInt("errorCode", -1) != 0) break

                val events = result.optJSONArray("events") ?: JSONArray()
                if (events.length() > 0) {
                    totalFetched += events.length()
                    processTodoEvents(db, events)
                    val lastEventId = result.optLong("lastEventId", 0L)
                    if (lastEventId > 0) db.syncMetaDao().insert(ProjectSyncMetaEntity("todoEventOffset", lastEventId))
                }

                if (!result.optBoolean("hasMore", false) || events.length() == 0) break
            }

            Log.d(TAG, "syncTodo complete: $totalFetched events")
            FileLogger.log(TAG, "syncTodo DONE totalEvents=$totalFetched")
            true
        } catch (e: Exception) {
            Log.e(TAG, "syncTodo error: ${e.message}", e)
            FileLogger.log(TAG, "syncTodo ERROR ${e.message}")
            false
        }
    }

    private suspend fun processTodoEvents(db: net.spacenx.messenger.data.local.ProjectDatabase, events: JSONArray) {
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            val eventType = event.optString("eventType", "")
            // 서버 응답 키: "todo" (synctodo 전용)
            val todo = event.optJSONObject("todo") ?: JSONObject()
            val issueCode = todo.optString("issueCode", event.optString("issueCode", ""))

            when (eventType) {
                "CREATE_TODO", "MOD_TODO" -> {
                    if (issueCode.isNotEmpty() && todo.length() > 0) {
                        db.issueDao().insertAll(listOf(IssueEntity(
                            issueCode = issueCode,
                            projectCode = "",   // Todo: projectCode 항상 빈 문자열
                            channelCode = todo.optString("channelCode", ""),
                            title = todo.optString("title", ""),
                            description = todo.optString("description", ""),
                            issueType = todo.optString("issueType", "TASK"),
                            issueStatus = todo.optString("issueStatus", "TODO"),
                            priority = todo.optString("priority", "NORMAL"),
                            assigneeUserId = todo.optString("assigneeUserId", ""),
                            reporterUserId = todo.optString("reporterUserId", ""),
                            labels = todo.optString("labels", ""),
                            dueDate = todo.optLong("dueDate", 0L),
                            completedDate = todo.optLong("completedDate", 0L),
                            modDate = todo.optLong("modDate", 0L),
                            createdDate = todo.optLong("createdDate", 0L),
                            threadCode = todo.optString("threadCode", ""),
                            commentCount = todo.optInt("commentCount", 0)
                        )))
                    }
                }
                "DEL_TODO" -> {
                    if (issueCode.isNotEmpty()) db.issueDao().delete(issueCode)
                }
            }
        }
    }

    // ══════════════════════════════════════
    // syncThread — 스레드 delta sync
    // ══════════════════════════════════════

    suspend fun syncThread(): Boolean = withContext(Dispatchers.IO) {
        try {
            val db = dbProvider.getProjectDatabase()
            val userId = appConfig.getSavedUserId() ?: return@withContext false
            val token = awaitToken() ?: return@withContext false

            // chatContents 마이그레이션: 최초 1회 threadEventOffset 리셋 → 서버에서 전체 재fetch
            if (db.syncMetaDao().getValue("threadContentsMigrated") == null) {
                db.syncMetaDao().insert(ProjectSyncMetaEntity("threadEventOffset", 0L))
                db.syncMetaDao().insert(ProjectSyncMetaEntity("threadContentsMigrated", 1L))
            }

            var totalFetched = 0
            var pageCount = 0

            while (true) {
                val lastOffset = db.syncMetaDao().getValue("threadEventOffset") ?: 0L
                Log.d(TAG, "syncThread: offset=$lastOffset")
                val body = JSONObject().apply {
                    put("userId", userId)
                    put("threadEventOffset", lastOffset)
                    put("reset", lastOffset == 0L)
                    put("limit", SYNC_PAGE_SIZE)
                }

                // ── 스트리밍 파싱 (OOM 방지) ──
                // 스레드/댓글이 누적된 계정의 첫 sync 응답은 대용량 가능. string+JSONObject 버퍼 제거.
                val reqBody = body.toString().toRequestBody("application/json".toMediaType())
                val req = okhttp3.Request.Builder()
                    .url(appConfig.getEndpointByPath("/comm/syncthread"))
                    .post(reqBody)
                if (!token.isNullOrEmpty()) req.addHeader("Authorization", "Bearer $token")
                val response = ApiClient.okHttpClient.newCall(req.build()).execute()

                var errorCode = -1
                var lastEventId = 0L
                var hasMore = false
                val events = JSONArray()
                response.use { resp ->
                    if (!resp.isSuccessful) { errorCode = -1; return@use }
                    val respBody = resp.body ?: return@use
                    respBody.parseStream { reader ->
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "errorCode" -> errorCode = JsonStreamUtil.nextIntOrZero(reader)
                                "lastEventId" -> lastEventId = JsonStreamUtil.nextLongOrZero(reader)
                                "hasMore" -> hasMore = JsonStreamUtil.nextBooleanOrFalse(reader)
                                "events" -> {
                                    reader.beginArray()
                                    while (reader.hasNext()) events.put(JsonStreamUtil.readObject(reader))
                                    reader.endArray()
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                }

                if (errorCode != 0) break

                if (events.length() > 0) {
                    totalFetched += events.length()
                    processThreadEvents(db, events)
                    if (lastEventId > 0) db.syncMetaDao().insert(ProjectSyncMetaEntity("threadEventOffset", lastEventId))
                }

                pageCount++
                if (!hasMore || events.length() == 0 || pageCount >= 200) break
            }

            Log.d(TAG, "syncThread complete: $totalFetched events")
            FileLogger.log(TAG, "syncThread DONE totalEvents=$totalFetched pages=$pageCount")
            true
        } catch (e: Exception) {
            Log.e(TAG, "syncThread error: ${e.message}", e)
            FileLogger.log(TAG, "syncThread ERROR ${e.message}")
            false
        }
    }

    private suspend fun processThreadEvents(db: net.spacenx.messenger.data.local.ProjectDatabase, events: JSONArray) {
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            val eventType = event.optString("eventType", "")
            val threadCode = event.optString("threadCode", "")

            when (eventType) {
                "CREATE_CHAT_THREAD" -> {
                    val chatCode = event.optString("chatCode", "")
                    val channelCode = event.optString("channelCode", "")
                    if (chatCode.isNotEmpty() && threadCode.isNotEmpty()) {
                        db.chatThreadDao().insertChatThreads(listOf(ChatThreadEntity(
                            chatCode = chatCode,
                            threadCode = threadCode,
                            channelCode = channelCode,
                            commentCount = event.optInt("commentCount", 0),
                            createdDate = event.optLong("createdDate", 0L),
                            chatContents = event.optString("chatContents", "")
                        )))
                    }
                }
                "CREATE_ISSUE_THREAD" -> {
                    val issueCode = event.optString("issueCode", "")
                    if (issueCode.isNotEmpty() && threadCode.isNotEmpty()) {
                        db.chatThreadDao().insertIssueThreads(listOf(IssueThreadEntity(
                            issueCode = issueCode,
                            threadCode = threadCode,
                            createdDate = event.optLong("createdDate", 0L)
                        )))
                    }
                }
                "DELETE_CHAT_THREAD" -> {
                    val chatCode = event.optString("chatCode", "")
                    if (chatCode.isNotEmpty()) db.chatThreadDao().deleteByChatCode(chatCode)
                }
                "DELETE_ISSUE_THREAD" -> {
                    if (threadCode.isNotEmpty()) db.threadCommentDao().deleteByThreadCode(threadCode)
                }
                "ADD_COMMENT", "DELETE_COMMENT" -> {
                    val commentCount = if (event.has("commentCount")) event.optInt("commentCount", 0) else null
                    if (commentCount != null && threadCode.isNotEmpty()) {
                        val ct = db.chatThreadDao().getByThreadCode(threadCode)
                        if (ct != null) {
                            val newContents = event.optString("chatContents", "")
                            val merged = ct.copy(
                                commentCount = commentCount,
                                chatContents = if (newContents.isNotEmpty()) newContents else ct.chatContents
                            )
                            db.chatThreadDao().insertChatThreads(listOf(merged))
                        }
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════
    // 로컬 조회 (apiPost 대체용)
    // ══════════════════════════════════════

    /** apiPost 조회 API → SQLite 우선. null이면 REST fallback */
    suspend fun handleLocally(path: String, body: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        if (!dbProvider.isInitialized()) return@withContext null
        try {
            val db = dbProvider.getProjectDatabase()
            val userId = body.optString("userId", appConfig.getSavedUserId() ?: "")

            when (path) {
                "/comm/getprojects" -> {
                    val projects = if (userId.isNotEmpty()) db.projectDao().getProjectsByUser(userId) else db.projectDao().getAllProjects()
                    if (projects.isEmpty()) return@withContext null
                    val arr = JSONArray()
                    for (p in projects) {
                        val members = db.projectDao().getMembers(p.projectCode)
                        val channels = db.projectDao().getChannels(p.projectCode)
                        val issueCount = db.issueDao().getByProject(p.projectCode).size
                        arr.put(projectToJson(p, members, channels, issueCount))
                    }
                    JSONObject().put("errorCode", 0).put("projects", arr)
                }
                "/comm/getproject" -> {
                    val projectCode = body.optString("projectCode", "")
                    val p = db.projectDao().getProject(projectCode) ?: return@withContext null
                    val members = db.projectDao().getMembers(projectCode)
                    val channels = db.projectDao().getChannels(projectCode)
                    val issueCount = db.issueDao().getByProject(projectCode).size
                    JSONObject().put("errorCode", 0).put("project", projectToJson(p, members, channels, issueCount))
                }
                "/comm/getissues" -> {
                    val projectCode = body.optString("projectCode", "")
                    val issues = db.issueDao().getByProject(projectCode)
                    if (issues.isEmpty()) return@withContext null
                    JSONObject().put("errorCode", 0).put("issues", issuesToJsonArray(issues))
                }
                "/comm/gettodos" -> {
                    val todos = db.issueDao().getAllTodos()
                    if (todos.isEmpty()) return@withContext null
                    JSONObject().put("errorCode", 0).put("todos", issuesToJsonArray(todos))
                }
                "/comm/getcalevents" -> {
                    // 서버 계약: { userId, eventDate: "YYYY-MM-DD" } → 해당 날짜 일정만 반환
                    val eventDate = body.optString("eventDate", "")
                    val all = db.calEventDao().getByUser(userId)
                    val events = if (eventDate.isNotEmpty()) {
                        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                        all.filter { it.startDate > 0 && fmt.format(java.util.Date(it.startDate)) == eventDate }
                    } else all
                    if (events.isEmpty()) return@withContext null
                    JSONObject().put("errorCode", 0).put("events", calEventsToJsonArray(events))
                }
                "/comm/getcaleventsbymouth" -> {
                    // 서버 계약: { userId, year, month(1-12) } — 로컬도 동일 파라미터로 맞춤
                    val year = body.optInt("year", 0)
                    val month = body.optInt("month", 0)
                    val events = if (year > 0 && month in 1..12) {
                        val cal = java.util.Calendar.getInstance()
                        cal.clear(); cal.set(year, month - 1, 1, 0, 0, 0)
                        val fromMs = cal.timeInMillis
                        cal.add(java.util.Calendar.MONTH, 1)
                        val toMs = cal.timeInMillis
                        db.calEventDao().getByUserAndMonth(userId, fromMs, toMs)
                    } else {
                        db.calEventDao().getByUser(userId)
                    }
                    if (events.isEmpty()) return@withContext null
                    JSONObject().put("errorCode", 0).put("events", calEventsToJsonArray(events))
                }
                "/comm/getmyissues" -> {
                    val issues = db.issueDao().getByAssignee(userId)
                    if (issues.isEmpty()) return@withContext null
                    JSONObject().put("errorCode", 0).put("issues", issuesToJsonArray(issues))
                }
                "/comm/getissue" -> {
                    val issue = db.issueDao().getByCode(body.optString("issueCode", "")) ?: return@withContext null
                    JSONObject().put("errorCode", 0).put("issue", issueToJson(issue))
                }
                "/comm/getchatthreads" -> {
                    val threads = db.chatThreadDao().getByChannel(body.optString("channelCode", ""))
                    if (threads.isEmpty()) return@withContext null
                    val chatDb = dbProvider.getChatDatabase()
                    JSONObject().put("errorCode", 0).put("threads", threadsToJsonArray(threads, chatDb))
                }
                "/comm/getchatthread" -> {
                    val ct = db.chatThreadDao().getByChatCode(body.optString("chatCode", "")) ?: return@withContext null
                    JSONObject().put("errorCode", 0)
                        .put("threadCode", ct.threadCode).put("chatCode", ct.chatCode)
                        .put("channelCode", ct.channelCode).put("commentCount", ct.commentCount)
                }
                "/comm/getthread" -> {
                    val ct = db.chatThreadDao().getByThreadCode(body.optString("threadCode", "")) ?: return@withContext null
                    JSONObject().put("errorCode", 0).put("thread", JSONObject().apply {
                        put("threadCode", ct.threadCode); put("chatCode", ct.chatCode)
                        put("channelCode", ct.channelCode); put("commentCount", ct.commentCount)
                    })
                }
                "/comm/getthreadcomments" -> {
                    val threadCode = body.optString("threadCode", "")
                    val comments = db.threadCommentDao().getByThreadCode(threadCode)
                    if (comments.isEmpty()) return@withContext null
                    // commentCount와 로컬 댓글 수 비교 — 불일치하면 REST fallback
                    val thread = db.chatThreadDao().getByThreadCode(threadCode)
                    if (thread != null && thread.commentCount > comments.size) return@withContext null
                    JSONObject().put("errorCode", 0).put("comments", commentsToJsonArray(comments)).put("commentCount", comments.size)
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleLocally error: $path → ${e.message}")
            null
        }
    }

    // ══════════════════════════════════════
    // CUD 후 로컬 즉시 반영
    // ══════════════════════════════════════

    suspend fun cacheAfterCud(path: String, body: JSONObject, result: JSONObject) = withContext(Dispatchers.IO) {
        if (!dbProvider.isInitialized()) return@withContext
        try {
            if (result.optInt("errorCode", -1) != 0) return@withContext
            val db = dbProvider.getProjectDatabase()

            when (path) {
                "/comm/getcalevents", "/comm/getcaleventsbymouth" -> {
                    val events = result.optJSONArray("events") ?: return@withContext
                    Log.d(TAG, "cacheAfterCud: caching ${events.length()} cal events from REST fallback ($path)")
                    val savedUserId = body.optString("userId", appConfig.getSavedUserId() ?: "")
                    val list = (0 until events.length()).map { i ->
                        val e = events.getJSONObject(i)
                        calObjToEntity(e, e.optString("calCode", ""), savedUserId)
                    }.filter { it.calCode.isNotEmpty() }
                    if (list.isNotEmpty()) db.calEventDao().insertAll(list)
                }
                "/comm/gettodos" -> {
                    val todos = result.optJSONArray("todos") ?: return@withContext
                    Log.d(TAG, "cacheAfterCud: caching ${todos.length()} todos from REST fallback")
                    val list = (0 until todos.length()).map { i ->
                        val t = todos.getJSONObject(i)
                        IssueEntity(
                            issueCode = t.optString("issueCode", ""),
                            projectCode = "",
                            channelCode = t.optString("channelCode", ""),
                            title = t.optString("title", ""),
                            description = t.optString("description", ""),
                            issueType = t.optString("issueType", "TASK"),
                            issueStatus = t.optString("issueStatus", "TODO"),
                            priority = t.optString("priority", "NORMAL"),
                            assigneeUserId = t.optString("assigneeUserId", ""),
                            reporterUserId = t.optString("reporterUserId", ""),
                            labels = t.optString("labels", ""),
                            dueDate = t.optLong("dueDate", 0L),
                            completedDate = t.optLong("completedDate", 0L),
                            modDate = t.optLong("modDate", 0L),
                            createdDate = t.optLong("createdDate", 0L),
                            threadCode = t.optString("threadCode", ""),
                            commentCount = t.optInt("commentCount", 0)
                        )
                    }.filter { it.issueCode.isNotEmpty() }
                    if (list.isNotEmpty()) db.issueDao().insertAll(list)
                }
                "/comm/addthreadcomment" -> {
                    val commentId = result.optInt("commentId", 0)
                    val threadCode = body.optString("threadCode", "")
                    val userId = body.optString("userId", "")
                    val contents = body.optString("contents", "")
                    val dateTime = result.optLong("dateTime", System.currentTimeMillis())
                    if (commentId > 0 && threadCode.isNotEmpty()) {
                        db.threadCommentDao().insertAll(listOf(ThreadCommentEntity(
                            commentId = commentId, threadCode = threadCode,
                            userId = userId, contents = contents, createdDate = dateTime
                        )))
                        updateThreadCommentCount(db, threadCode, 1)
                    }
                }
                "/comm/deletethreadcomment" -> {
                    val commentId = body.optInt("commentId", 0)
                    if (commentId > 0) {
                        val c = db.threadCommentDao().getById(commentId)
                        if (c != null) {
                            db.threadCommentDao().delete(commentId)
                            updateThreadCommentCount(db, c.threadCode, -1)
                        }
                    }
                }
                "/comm/getthreadcomments" -> {
                    // REST fallback 응답을 캐시
                    val comments = result.optJSONArray("comments") ?: return@withContext
                    val list = (0 until comments.length()).map { i ->
                        val c = comments.getJSONObject(i)
                        ThreadCommentEntity(
                            commentId = c.optInt("commentId", 0),
                            threadCode = c.optString("threadCode", ""),
                            userId = c.optString("userId", ""),
                            contents = c.optString("contents", ""),
                            createdDate = c.optLong("createdDate", 0L)
                        )
                    }
                    if (list.isNotEmpty()) db.threadCommentDao().insertAll(list)
                }

                "/comm/createissue", "/comm/updateissue" -> {
                    // 서버 응답: issue 객체가 있으면 사용, 없으면 result/body에서 직접 읽기
                    val issue = result.optJSONObject("issue")
                    val src = issue ?: result  // issue 객체 없으면 result 자체에서 읽기
                    val issueCode = src.optString("issueCode", body.optString("issueCode", ""))
                    if (issueCode.isNotEmpty()) {
                        Log.d(TAG, "cacheAfterCud: saving issue $issueCode from ${if (issue != null) "issue obj" else "result/body"}")
                        db.issueDao().insertAll(listOf(IssueEntity(
                            issueCode = issueCode,
                            projectCode = src.optString("projectCode", body.optString("projectCode", "")),
                            channelCode = src.optString("channelCode", body.optString("channelCode", "")),
                            title = src.optString("title", body.optString("title", "")),
                            description = src.optString("description", body.optString("description", "")),
                            issueType = src.optString("issueType", body.optString("issueType", "TASK")),
                            issueStatus = src.optString("issueStatus", body.optString("issueStatus", "TODO")),
                            priority = src.optString("priority", body.optString("priority", "NORMAL")),
                            assigneeUserId = src.optString("assigneeUserId", body.optString("assigneeUserId", "")),
                            reporterUserId = src.optString("reporterUserId", body.optString("reporterUserId", "")),
                            labels = src.optString("labels", body.optString("labels", "")),
                            dueDate = src.optLong("dueDate", body.optLong("dueDate", 0L)),
                            completedDate = src.optLong("completedDate", 0L),
                            modDate = src.optLong("modDate", System.currentTimeMillis()),
                            createdDate = src.optLong("createdDate", System.currentTimeMillis()),
                            threadCode = src.optString("threadCode", ""),
                            commentCount = src.optInt("commentCount", 0)
                        )))
                    } else {
                        Log.w(TAG, "cacheAfterCud: no issueCode found in result or body")
                    }
                }

                "/comm/deleteissue" -> {
                    val issueCode = body.optString("issueCode", result.optString("issueCode", ""))
                    if (issueCode.isNotEmpty()) {
                        db.issueDao().delete(issueCode)
                    }
                }

                "/comm/createcalevent", "/comm/updatecalevent" -> {
                    val src = result.optJSONObject("calEvent") ?: result
                    val calCode = src.optString("calCode", body.optString("calCode", ""))
                    if (calCode.isNotEmpty()) {
                        val savedUserId = appConfig.getSavedUserId() ?: ""
                        val merged = JSONObject().apply {
                            // body 먼저 채우고 src로 덮어쓰기 (서버 응답 우선)
                            body.keys().forEach { k -> put(k, body.get(k)) }
                            src.keys().forEach { k -> put(k, src.get(k)) }
                            if (!has("userId") || optString("userId").isEmpty()) put("userId", savedUserId)
                        }
                        db.calEventDao().insertAll(listOf(calObjToEntity(merged, calCode, savedUserId)))
                    }
                }
                "/comm/deletecalevent" -> {
                    val calCode = body.optString("calCode", result.optString("calCode", ""))
                    if (calCode.isNotEmpty()) db.calEventDao().delete(calCode)
                }

                "/comm/createtodo", "/comm/updatetodo" -> {
                    val src = result.optJSONObject("issue") ?: result
                    val issueCode = src.optString("issueCode", body.optString("issueCode", ""))
                    if (issueCode.isNotEmpty()) {
                        db.issueDao().insertAll(listOf(IssueEntity(
                            issueCode = issueCode,
                            projectCode = "",   // Todo는 항상 projectCode 없음
                            channelCode = src.optString("channelCode", body.optString("channelCode", "")),
                            title = src.optString("title", body.optString("title", "")),
                            description = src.optString("description", body.optString("description", "")),
                            issueType = src.optString("issueType", body.optString("issueType", "TASK")),
                            issueStatus = src.optString("issueStatus", body.optString("issueStatus", "TODO")),
                            priority = src.optString("priority", body.optString("priority", "NORMAL")),
                            assigneeUserId = src.optString("assigneeUserId", body.optString("assigneeUserId", "")),
                            reporterUserId = src.optString("reporterUserId", body.optString("reporterUserId", "")),
                            labels = src.optString("labels", body.optString("labels", "")),
                            dueDate = src.optLong("dueDate", body.optLong("dueDate", 0L)),
                            completedDate = src.optLong("completedDate", 0L),
                            modDate = src.optLong("modDate", System.currentTimeMillis()),
                            createdDate = src.optLong("createdDate", System.currentTimeMillis()),
                            threadCode = src.optString("threadCode", ""),
                            commentCount = src.optInt("commentCount", 0)
                        )))
                    }
                }
                "/comm/deletetodo" -> {
                    val issueCode = body.optString("issueCode", result.optString("issueCode", ""))
                    if (issueCode.isNotEmpty()) db.issueDao().delete(issueCode)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "cacheAfterCud error: $path → ${e.message}")
        }
    }

    private suspend fun updateThreadCommentCount(db: net.spacenx.messenger.data.local.ProjectDatabase, threadCode: String, delta: Int) {
        val ct = db.chatThreadDao().getByThreadCode(threadCode)
        if (ct != null) {
            db.chatThreadDao().insertChatThreads(listOf(ct.copy(
                commentCount = (ct.commentCount + delta).coerceAtLeast(0)
            )))
        }
    }

    /** CUD 후 관련 sync 트리거 필요 여부 반환: "project", "issue", "thread", 또는 null */
    fun getSyncTypeForPath(path: String): String? {
        return when {
            path in listOf("/comm/createproject", "/comm/updateproject", "/comm/deleteproject",
                "/comm/addprojectmember", "/comm/removeprojectmember",
                "/comm/addprojectchannel", "/comm/removeprojectchannel") -> "project"
            path in listOf("/comm/createissue", "/comm/updateissue", "/comm/deleteissue") -> "issue"
            path in listOf("/comm/createchatthread", "/comm/createissuethread", "/comm/deletechatthread",
                "/comm/addthreadcomment", "/comm/deletethreadcomment") -> "thread"
            path in listOf("/comm/createcalevent", "/comm/updatecalevent", "/comm/deletecalevent") -> "calendar"
            path in listOf("/comm/createtodo", "/comm/updatetodo", "/comm/deletetodo") -> "todo"
            else -> null
        }
    }

    // ══════════════════════════════════════
    // getThreadsByChannel (bridge action)
    // ══════════════════════════════════════

    /** threadCode로 ChatThread 조회 → threadReady 이벤트에 channelCode/chatCode/commentCount 포함용 */
    suspend fun getChatThreadByCode(threadCode: String): Map<String, Any>? = withContext(Dispatchers.IO) {
        try {
            val ct = dbProvider.getProjectDatabase().chatThreadDao().getByThreadCode(threadCode)
            if (ct != null) mapOf(
                "chatCode" to ct.chatCode,
                "channelCode" to ct.channelCode,
                "commentCount" to ct.commentCount
            ) else null
        } catch (_: Exception) { null }
    }

    /** syncThread bridge action: Flutter와 동일한 events 형식으로 반환 */
    suspend fun getAllThreadsAsEvents(limit: Int, offset: Int): JSONObject = withContext(Dispatchers.IO) {
        try {
            val db = dbProvider.getProjectDatabase()
            val chatThreads = db.chatThreadDao().getAllChatThreads(limit, offset)
            val issueThreads = db.chatThreadDao().getAllIssueThreads(limit, offset)
            val chatDb = dbProvider.getChatDatabase()
            val events = JSONArray()
            chatThreads.forEach { t ->
                val contents = t.chatContents.ifEmpty {
                    chatDb.chatDao().getByChatCode(t.chatCode)?.contents ?: ""
                }
                events.put(JSONObject().apply {
                    put("eventType", "CREATE_CHAT_THREAD")
                    put("threadCode", t.threadCode)
                    put("chatCode", t.chatCode)
                    put("channelCode", t.channelCode)
                    put("commentCount", t.commentCount)
                    if (t.createdDate > 0) put("createdDate", t.createdDate)
                    if (contents.isNotEmpty()) put("chatContents", contents)
                })
            }
            issueThreads.forEach { t ->
                events.put(JSONObject().apply {
                    put("eventType", "CREATE_ISSUE_THREAD")
                    put("threadCode", t.threadCode)
                    put("issueCode", t.issueCode)
                    if (t.createdDate > 0) put("createdDate", t.createdDate)
                })
            }
            val total = chatThreads.size + issueThreads.size
            JSONObject().put("errorCode", 0).put("events", events).put("hasMore", total >= limit)
        } catch (e: Exception) {
            Log.e(TAG, "getAllThreadsAsEvents error: ${e.message}")
            JSONObject().put("errorCode", -1).put("errorMessage", e.message)
        }
    }

    suspend fun getThreadsByChannel(channelCode: String): JSONObject = withContext(Dispatchers.IO) {
        try {
            val db = dbProvider.getProjectDatabase()
            val chatDb = dbProvider.getChatDatabase()
            val threads = db.chatThreadDao().getByChannel(channelCode)
            JSONObject().put("errorCode", 0).put("threads", threadsToJsonArray(threads, chatDb))
        } catch (e: Exception) {
            Log.e(TAG, "getThreadsByChannel error: ${e.message}")
            JSONObject().put("errorCode", -1).put("errorMessage", e.message)
        }
    }

    suspend fun getThreadsByIssue(issueCode: String): JSONObject = withContext(Dispatchers.IO) {
        try {
            val db = dbProvider.getProjectDatabase()
            val thread = db.chatThreadDao().getIssueThreadByIssue(issueCode)
            val threads = if (thread != null) {
                JSONArray().put(JSONObject().apply {
                    put("threadCode", thread.threadCode)
                    put("issueCode", thread.issueCode)
                    put("createdDate", thread.createdDate)
                })
            } else {
                JSONArray()
            }
            JSONObject().put("errorCode", 0).put("threads", threads)
        } catch (e: Exception) {
            Log.e(TAG, "getThreadsByIssue error: ${e.message}")
            JSONObject().put("errorCode", -1).put("errorMessage", e.message)
        }
    }

    // ── JSON 변환 helpers ──

    private fun projectToJson(p: ProjectEntity, members: List<ProjectMemberEntity>, channels: List<ProjectChannelEntity>, issueCount: Int): JSONObject {
        return JSONObject().apply {
            put("projectCode", p.projectCode)
            put("projectName", p.projectName)
            put("icon", p.icon)
            put("color", p.color)
            put("projectStatus", p.projectStatus)
            put("ownerUserId", p.ownerUserId)
            put("description", p.description)
            if (p.deadline > 0) put("deadline", p.deadline)
            if (p.createdDate > 0) put("createdDate", p.createdDate)
            if (p.modDate > 0) put("modDate", p.modDate)
            put("members", JSONArray().apply {
                members.forEach { m -> put(JSONObject().put("projectCode", m.projectCode).put("userId", m.userId).apply {
                    if (m.createdDate > 0) put("createdDate", m.createdDate)
                }) }
            })
            put("channels", JSONArray().apply {
                channels.forEach { c -> put(JSONObject().put("projectCode", c.projectCode).put("channelCode", c.channelCode).apply {
                    if (c.createdDate > 0) put("createdDate", c.createdDate)
                }) }
            })
            put("issueCount", issueCount)
        }
    }

    private fun issueToJson(i: IssueEntity): JSONObject {
        return JSONObject().apply {
            put("issueCode", i.issueCode)
            put("projectCode", i.projectCode)
            put("channelCode", i.channelCode)
            put("title", i.title)
            put("description", i.description)
            put("issueType", i.issueType)
            put("issueStatus", i.issueStatus)
            put("priority", i.priority)
            put("assigneeUserId", i.assigneeUserId)
            put("reporterUserId", i.reporterUserId)
            put("labels", i.labels)
            if (i.dueDate > 0) put("dueDate", i.dueDate)
            if (i.completedDate > 0) put("completedDate", i.completedDate)
            if (i.createdDate > 0) put("createdDate", i.createdDate)
            if (i.modDate > 0) put("modDate", i.modDate)
            put("threadCode", i.threadCode)
            put("commentCount", i.commentCount)
        }
    }

    private fun issuesToJsonArray(issues: List<IssueEntity>): JSONArray {
        return JSONArray().apply { issues.forEach { put(issueToJson(it)) } }
    }

    private suspend fun threadsToJsonArray(threads: List<ChatThreadEntity>, chatDb: ChatDatabase): JSONArray {
        return JSONArray().apply {
            threads.forEach { t ->
                val contents = t.chatContents.ifEmpty {
                    chatDb.chatDao().getByChatCode(t.chatCode)?.contents ?: ""
                }
                put(JSONObject().apply {
                    put("threadCode", t.threadCode)
                    put("chatCode", t.chatCode)
                    put("channelCode", t.channelCode)
                    put("commentCount", t.commentCount)
                    if (t.createdDate > 0) put("createdDate", t.createdDate)
                    if (contents.isNotEmpty()) put("chatContents", contents)
                })
            }
        }
    }

    private fun parseDateTimeMs(dateStr: String, timeStr: String): Long {
        if (dateStr.isEmpty()) return 0L
        return try {
            val fullTime = if (timeStr.isEmpty()) "00:00" else timeStr
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            sdf.parse("$dateStr $fullTime")?.time ?: 0L
        } catch (_: Exception) { 0L }
    }

    private fun calObjToEntity(obj: JSONObject, calCode: String, fallbackUserId: String): CalEventEntity {
        var startDate = obj.optLong("startDate", 0L)
        var endDate = obj.optLong("endDate", 0L)
        // 서버가 eventDate/startTime/endTime 문자열로 보내는 경우 변환
        if (startDate == 0L) {
            val eventDate = obj.optString("eventDate", "")
            startDate = parseDateTimeMs(eventDate, obj.optString("startTime", ""))
            if (endDate == 0L && startDate > 0L) {
                endDate = parseDateTimeMs(eventDate, obj.optString("endTime", ""))
                if (endDate == 0L || endDate <= startDate) endDate = startDate + 3_600_000L
            }
        }
        return CalEventEntity(
            calCode = calCode,
            userId = obj.optString("userId", fallbackUserId).ifEmpty { fallbackUserId },
            title = obj.optString("title", ""),
            description = obj.optString("description", ""),
            calType = obj.optString("calType", "PERSONAL"),
            startDate = startDate,
            endDate = endDate,
            allDay = obj.optInt("allDay", 0),
            color = obj.optString("color", ""),
            location = obj.optString("location", ""),
            modDate = obj.optLong("modDate", 0L),
            createdDate = obj.optLong("createdDate", 0L)
        )
    }

    private fun calEventToJson(e: CalEventEntity): JSONObject {
        // 서버 /comm/synccalendar·/comm/getcalevents·/comm/getcaleventsbymouth 응답과 동일 스키마로 직렬화.
        // CalPage 는 ev.eventDate 문자열로 필터하므로 startDate(ms)만 내려주면 렌더링이 안 됨.
        val dateFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val startMs = e.startDate
        val endMs = if (e.endDate > 0) e.endDate else e.startDate
        return JSONObject().apply {
            put("calCode", e.calCode)
            put("userId", e.userId)
            put("title", e.title)
            put("eventDate", if (startMs > 0) dateFmt.format(java.util.Date(startMs)) else "")
            put("startTime", if (startMs > 0) timeFmt.format(java.util.Date(startMs)) else "")
            put("endTime", if (endMs > 0) timeFmt.format(java.util.Date(endMs)) else "")
            put("category", e.calType)
            put("color", e.color)
            put("location", e.location)
            if (e.createdDate > 0) put("createdDate", e.createdDate)
            if (e.modDate > 0) put("modDate", e.modDate)
        }
    }

    private fun calEventsToJsonArray(events: List<CalEventEntity>): JSONArray {
        return JSONArray().apply { events.forEach { put(calEventToJson(it)) } }
    }

    private fun commentsToJsonArray(comments: List<ThreadCommentEntity>): JSONArray {
        return JSONArray().apply {
            comments.forEach { c ->
                put(JSONObject().apply {
                    put("commentId", c.commentId)
                    put("threadCode", c.threadCode)
                    put("userId", c.userId)
                    put("contents", c.contents)
                    if (c.createdDate > 0) put("createdDate", c.createdDate)
                })
            }
        }
    }

    private suspend fun awaitToken(): String? {
        val sm = sessionManager ?: return null
        if (sm.jwtToken != null) return sm.jwtToken
        Log.d(TAG, "Waiting for JWT token...")
        return withTimeoutOrNull(10_000L) { sm.jwtTokenDeferred.await() }
    }
}
