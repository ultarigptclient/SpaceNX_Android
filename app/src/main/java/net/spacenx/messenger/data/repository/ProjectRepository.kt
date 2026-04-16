package net.spacenx.messenger.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.common.JsonStreamUtil
import net.spacenx.messenger.common.parseStream
import kotlinx.coroutines.withTimeoutOrNull
import net.spacenx.messenger.data.local.DatabaseProvider
import net.spacenx.messenger.data.local.entity.*
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
            true
        } catch (e: Exception) {
            Log.e(TAG, "syncProject error: ${e.message}", e)
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
            true
        } catch (e: Exception) {
            Log.e(TAG, "syncIssue error: ${e.message}", e)
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
    // syncThread — 스레드 delta sync
    // ══════════════════════════════════════

    suspend fun syncThread(): Boolean = withContext(Dispatchers.IO) {
        try {
            val db = dbProvider.getProjectDatabase()
            val userId = appConfig.getSavedUserId() ?: return@withContext false
            val token = awaitToken() ?: return@withContext false
            var totalFetched = 0
            var pageCount = 0

            while (true) {
                val lastOffset = db.syncMetaDao().getValue("threadEventOffset") ?: 0L
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
            true
        } catch (e: Exception) {
            Log.e(TAG, "syncThread error: ${e.message}", e)
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
                            createdDate = event.optLong("createdDate", 0L)
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
                            db.chatThreadDao().insertChatThreads(listOf(ct.copy(commentCount = commentCount)))
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
                    JSONObject().put("errorCode", 0).put("threads", threadsToJsonArray(threads))
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
            val events = JSONArray()
            chatThreads.forEach { t ->
                events.put(JSONObject().apply {
                    put("eventType", "CREATE_CHAT_THREAD")
                    put("threadCode", t.threadCode)
                    put("chatCode", t.chatCode)
                    put("channelCode", t.channelCode)
                    put("commentCount", t.commentCount)
                    if (t.createdDate > 0) put("createdDate", t.createdDate)
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
            val threads = db.chatThreadDao().getByChannel(channelCode)
            JSONObject().put("errorCode", 0).put("threads", threadsToJsonArray(threads))
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

    private fun threadsToJsonArray(threads: List<ChatThreadEntity>): JSONArray {
        return JSONArray().apply {
            threads.forEach { t ->
                put(JSONObject().apply {
                    put("threadCode", t.threadCode)
                    put("chatCode", t.chatCode)
                    put("channelCode", t.channelCode)
                    put("commentCount", t.commentCount)
                    if (t.createdDate > 0) put("createdDate", t.createdDate)
                })
            }
        }
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
