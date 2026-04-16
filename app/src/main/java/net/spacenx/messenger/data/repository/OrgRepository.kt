package net.spacenx.messenger.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.common.AppConfig.Companion.EP_ORG_MY_PART
import net.spacenx.messenger.common.AppConfig.Companion.EP_ORG_SEARCH_USER
import net.spacenx.messenger.common.AppConfig.Companion.EP_ORG_SUB_ORG
import net.spacenx.messenger.common.AppConfig.Companion.EP_ORG_SYNC_ORG
import net.spacenx.messenger.data.local.DatabaseProvider
import net.spacenx.messenger.data.local.entity.DeptEntity
import net.spacenx.messenger.data.local.entity.SyncMetaEntity
import net.spacenx.messenger.data.local.entity.UserEntity
import net.spacenx.messenger.data.remote.api.ApiClient
import net.spacenx.messenger.data.remote.api.dto.MyPartRequestDTO
import net.spacenx.messenger.data.remote.api.dto.SearchUserRequestDTO
import net.spacenx.messenger.data.remote.api.dto.SubOrgRequestDTO
import net.spacenx.messenger.data.remote.api.dto.SyncOrgRequestDTO
import org.json.JSONArray
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject

/**
 * 조직도 동기화 + REST API (MyPart/SubOrg/SearchUser)
 */
class OrgRepository(
    private val databaseProvider: DatabaseProvider,
    private val appConfig: AppConfig,
    private val sessionManager: SocketSessionManager
) {
    companion object {
        private const val TAG = "OrgRepository"
    }

    private val syncMutex = Mutex()

    /** @return true if user data was changed (added/updated/removed) */
    var lastSyncHadUserChanges: Boolean = false
        private set

    // getMyPart 결과 캐시 (10초 TTL) — JS에서 중복 호출 시 API 재요청 방지
    @Volatile private var myPartCacheJson: String? = null
    @Volatile private var myPartCacheUserId: String? = null
    @Volatile private var myPartCacheTime: Long = 0L
    private val MY_PART_CACHE_TTL = 10_000L
    // 동시 요청 합치기 — 콜드 스타트 시 캐시 없을 때 중복 API 호출 방지
    @Volatile private var myPartInFlight: kotlinx.coroutines.Deferred<String>? = null

    // ── DB sync ──
    suspend fun syncOrg(userId: String): Boolean {
        if (!syncMutex.tryLock()) {
            Log.d(TAG, "syncOrg already in progress, skipping")
            return true
        }
        try {
        return withContext(Dispatchers.IO) {
            try {
                val token = awaitToken()
                val orgDb = databaseProvider.getOrgDatabase()
                val lastSyncTime = orgDb.syncMetaDao().getValueSync("orgLastSyncTime") ?: 0L
                val orgEventOffset = orgDb.syncMetaDao().getValueSync("orgEventOffset") ?: 0L

                Log.d(TAG, "syncOrg: userId=$userId, lastSyncTime=$lastSyncTime, orgEventOffset=$orgEventOffset")

                val baseUrl = appConfig.getRestBaseUrl()
                val orgApi = ApiClient.createOrgApiFromBaseUrl(baseUrl, token)
                val endpoint = appConfig.getEndpoint(EP_ORG_SYNC_ORG, "api/org/syncorg")
                val request = SyncOrgRequestDTO(
                    userId = userId,
                    lastSyncTime = lastSyncTime,
                    orgEventOffset = orgEventOffset
                )
                val response = orgApi.syncOrg(endpoint, request)

                if (!response.isSuccessful) {
                    Log.e(TAG, "syncOrg HTTP error: ${response.code()}")
                    return@withContext false
                }

                // ── 스트리밍 파싱 (OOM 방지) ──
                // migration 직후 전체 org delta 응답은 수십~수백 MB 가능성이 있어
                // response.string() + JSONObject(rawJson) 방식은 heap 한계(256MB)를 쉽게 초과한다.
                // android.util.JsonReader 로 byteStream 을 직접 파싱하여 중간 버퍼 생성을 제거.
                var errorCode = -1
                var serverTime = 0L
                var newOrgOffset = 0L
                val depts = mutableListOf<DeptEntity>()
                val users = mutableListOf<UserEntity>()
                val removedUIds = mutableListOf<String>()
                val removedDIds = mutableListOf<String>()

                val respBody = response.body() ?: run {
                    Log.e(TAG, "syncOrg: null response body")
                    return@withContext false
                }
                respBody.byteStream().use { byteStream ->
                    android.util.JsonReader(java.io.InputStreamReader(byteStream, Charsets.UTF_8)).use { reader ->
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "errorCode" -> errorCode = reader.nextInt()
                                "lastSyncTime" -> serverTime = reader.nextLong()
                                "orgEventOffset" -> newOrgOffset = reader.nextLong()
                                "depts" -> {
                                    reader.beginArray()
                                    while (reader.hasNext()) {
                                        var deptId = ""; var parentDept = ""
                                        var deptName = ""; var deptOrder = ""; var deptStatus = ""
                                        reader.beginObject()
                                        while (reader.hasNext()) {
                                            when (reader.nextName()) {
                                                "deptId" -> deptId = nextStringOrEmpty(reader)
                                                "parentDept" -> parentDept = nextStringOrEmpty(reader)
                                                "deptName" -> deptName = nextStringOrEmpty(reader)
                                                "deptOrder" -> deptOrder = nextStringOrEmpty(reader)
                                                "deptStatus" -> deptStatus = nextStringOrEmpty(reader)
                                                else -> reader.skipValue()
                                            }
                                        }
                                        reader.endObject()
                                        depts.add(DeptEntity(deptId, parentDept, deptName, deptOrder, deptStatus))
                                    }
                                    reader.endArray()
                                }
                                "users" -> {
                                    reader.beginArray()
                                    while (reader.hasNext()) {
                                        var userId = ""; var deptId = ""; var loginId = ""
                                        var userInfo = "{}"; var userOrder = ""
                                        reader.beginObject()
                                        while (reader.hasNext()) {
                                            when (reader.nextName()) {
                                                "userId" -> userId = nextStringOrEmpty(reader)
                                                "deptId" -> deptId = nextStringOrEmpty(reader)
                                                "loginId" -> loginId = nextStringOrEmpty(reader)
                                                "userOrder" -> userOrder = nextStringOrEmpty(reader)
                                                "userInfo" -> userInfo = readObjectAsJsonString(reader)
                                                else -> reader.skipValue()
                                            }
                                        }
                                        reader.endObject()
                                        users.add(UserEntity(userId, deptId, loginId, userInfo, userOrder))
                                    }
                                    reader.endArray()
                                }
                                "removedUserIds" -> {
                                    reader.beginArray()
                                    while (reader.hasNext()) removedUIds.add(nextStringOrEmpty(reader))
                                    reader.endArray()
                                }
                                "removedDeptIds" -> {
                                    reader.beginArray()
                                    while (reader.hasNext()) removedDIds.add(nextStringOrEmpty(reader))
                                    reader.endArray()
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                }

                if (errorCode != 0) {
                    Log.e(TAG, "syncOrg error: errorCode=$errorCode")
                    return@withContext false
                }

                // 단일 트랜잭션으로 DB 일괄 처리
                val txStart = System.currentTimeMillis()
                orgDb.runInTransaction {
                    val deptDao = orgDb.deptDao()
                    val userDao = orgDb.userDao()

                    if (lastSyncTime == 0L) {
                        deptDao.deleteAllSync()
                        userDao.deleteAllSync()
                    }

                    if (depts.isNotEmpty()) {
                        deptDao.insertAllSync(depts)
                        Log.d(TAG, "syncOrg: ${depts.size} depts saved")
                    }

                    if (users.isNotEmpty()) {
                        userDao.insertAllSync(users)
                        Log.d(TAG, "syncOrg: ${users.size} users saved")
                    }

                    if (removedUIds.isNotEmpty()) {
                        userDao.deleteByIdsSync(removedUIds)
                        Log.d(TAG, "syncOrg: ${removedUIds.size} users removed")
                    }

                    if (removedDIds.isNotEmpty()) {
                        deptDao.deleteByIdsSync(removedDIds)
                        Log.d(TAG, "syncOrg: ${removedDIds.size} depts removed")
                    }

                    // serverTime + orgEventOffset 저장
                    orgDb.syncMetaDao().insertSync(SyncMetaEntity("orgLastSyncTime", serverTime))
                    if (newOrgOffset > 0) {
                        orgDb.syncMetaDao().insertSync(SyncMetaEntity("orgEventOffset", newOrgOffset))
                    }
                }
                lastSyncHadUserChanges = users.isNotEmpty() || removedUIds.isNotEmpty()

                val txTime = System.currentTimeMillis() - txStart
                Log.d(TAG, "syncOrg transaction complete: ${txTime}ms (depts=${depts.size}, users=${users.size}, removedUsers=${removedUIds.size})")
                Log.d(TAG, "syncOrg complete: serverTime=$serverTime, userChanges=$lastSyncHadUserChanges")

                return@withContext true
            } catch (e: Exception) {
                Log.e(TAG, "syncOrg error: ${e.message}", e)
                return@withContext false
            }
        }
        } finally {
            syncMutex.unlock()
        }
    }

    suspend fun getRootDepts(): List<DeptEntity> {
        return databaseProvider.getOrgDatabase().deptDao().getRootDepts()
    }

    suspend fun getSubOrg(deptId: String): Pair<List<DeptEntity>, List<UserEntity>> {
        val orgDb = databaseProvider.getOrgDatabase()
        val childDepts = orgDb.deptDao().getByParent(deptId)
        val users = orgDb.userDao().getByDeptId(deptId)
        return Pair(childDepts, users)
    }

    suspend fun getAllDepts(): List<DeptEntity> {
        return databaseProvider.getOrgDatabase().deptDao().getAll()
    }

    suspend fun getAllUsers(): List<UserEntity> {
        return databaseProvider.getOrgDatabase().userDao().getAll()
    }

    // ── JSON formatters for WebView ──

    suspend fun getRootDeptsAsJson(): String {
        Log.d(TAG, "getRootDeptsAsJson() called, databaseProvider=$databaseProvider")
        val allDepts = databaseProvider.getOrgDatabase().deptDao().getAll()
        Log.d(TAG, "getRootDeptsAsJson: total depts in DB = ${allDepts.size}")
        if (allDepts.isNotEmpty()) {
            val samples = allDepts.take(5)
            samples.forEach { d ->
                Log.d(TAG, "  dept sample: deptId=${d.deptId}, parentDept=${d.parentDept}, name=${d.deptName}")
            }
            val distinctParents = allDepts.map { it.parentDept }.distinct().take(10)
            Log.d(TAG, "  distinct parentDept values (top 10): $distinctParents")
        }
        val rootDepts = getRootDepts()
        Log.d(TAG, "getRootDeptsAsJson: rootDepts = ${rootDepts.size}")
        val deptsArray = JSONArray()
        rootDepts.forEach { dept ->
            deptsArray.put(JSONObject().apply {
                put("deptId", dept.deptId)
                put("parentDept", dept.parentDept)
                put("deptName", dept.deptName)
                put("deptOrder", dept.deptOrder)
                put("deptStatus", dept.deptStatus)
            })
        }
        return JSONObject().apply {
            put("errorCode", 0)
            put("depts", deptsArray)
        }.toString()
    }

    suspend fun getSubOrgAsJson(deptId: String): String {
        Log.d(TAG, "getSubOrgAsJson() deptId=$deptId")
        val (childDepts, users) = getSubOrg(deptId)
        Log.d(TAG, "getSubOrgAsJson: childDepts=${childDepts.size}, users=${users.size}")
        if (users.isNotEmpty()) {
            val sample = users.take(2)
            sample.forEach { u -> Log.d(TAG, "  user data: userId=${u.userId}, deptId=${u.deptId}, userInfo=${u.userInfo}, userOrder=${u.userOrder}") }
        }
        if (users.isEmpty()) {
            val allUsers = databaseProvider.getOrgDatabase().userDao().getAll()
            Log.d(TAG, "getSubOrgAsJson: total users in DB = ${allUsers.size}")
            val sample = allUsers.take(3)
            sample.forEach { u -> Log.d(TAG, "  user sample: userId=${u.userId}, deptId=${u.deptId}") }
        }
        val deptsArray = JSONArray()
        val userDao = databaseProvider.getOrgDatabase().userDao()
        childDepts.forEach { dept ->
            val userCount = userDao.getByDeptId(dept.deptId).size
            deptsArray.put(JSONObject().apply {
                put("deptId", dept.deptId)
                put("parentDept", dept.parentDept)
                put("deptName", dept.deptName)
                put("deptOrder", dept.deptOrder)
                put("deptStatus", dept.deptStatus)
                put("userCount", userCount)
            })
        }
        val usersArray = JSONArray()
        users.forEach { user ->
            val info = JSONObject(user.userInfo)
            val merged = JSONObject().apply {
                put("userId", user.userId)
                put("deptId", user.deptId)
                put("userOrder", user.userOrder)
                // userInfo 필드를 최상위로 펼침 (JS가 o.userName, o.positionName 등 직접 접근)
                val keys = info.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, info.get(key))
                }
            }
            usersArray.put(merged)
        }
        return JSONObject().apply {
            put("errorCode", 0)
            put("depts", deptsArray)
            put("users", usersArray)
        }.toString()
    }

    /**
     * "부서" 탭용: 로그인 사용자의 deptId로 org 테이블에서 부서명 + 부서원 조회
     */
    suspend fun getMyDeptAsJson(): String {
        return withContext(Dispatchers.IO) {
            val myDeptId = appConfig.getMyDeptId()
            Log.d(TAG, "getMyDeptAsJson: myDeptId=$myDeptId")

            if (myDeptId.isNullOrEmpty()) {
                return@withContext JSONObject().apply {
                    put("errorCode", 0)
                    put("deptId", "")
                    put("deptName", "")
                    put("depts", JSONArray())
                    put("users", JSONArray())
                }.toString()
            }

            val orgDb = databaseProvider.getOrgDatabase()

            // 부서명 조회
            val dept = orgDb.deptDao().getByDeptId(myDeptId)
            val deptName = dept?.deptName ?: ""
            Log.d(TAG, "getMyDeptAsJson: deptName=$deptName")

            // 하위 부서 조회
            val childDepts = orgDb.deptDao().getByParent(myDeptId)
            val deptsArray = JSONArray()
            childDepts.forEach { child ->
                deptsArray.put(JSONObject().apply {
                    put("deptId", child.deptId)
                    put("parentDept", child.parentDept)
                    put("deptName", child.deptName)
                    put("deptOrder", child.deptOrder)
                    put("deptStatus", child.deptStatus)
                })
            }

            // 부서원 조회
            val users = orgDb.userDao().getByDeptId(myDeptId)
            Log.d(TAG, "getMyDeptAsJson: users=${users.size}")
            val usersArray = JSONArray()
            users.forEach { user ->
                val info = JSONObject(user.userInfo)
                val merged = JSONObject().apply {
                    put("userId", user.userId)
                    put("deptId", user.deptId)
                    put("userOrder", user.userOrder)
                    val keys = info.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        put(key, info.get(key))
                    }
                }
                usersArray.put(merged)
            }

            JSONObject().apply {
                put("errorCode", 0)
                put("deptId", myDeptId)
                put("deptName", deptName)
                put("depts", deptsArray)
                put("users", usersArray)
            }.toString()
        }
    }

    // ── REST API ──

    suspend fun getMyPart(userId: String): String {
        // 1) TTL 캐시 체크
        val now = System.currentTimeMillis()
        val cached = myPartCacheJson
        if (cached != null && myPartCacheUserId == userId && now - myPartCacheTime < MY_PART_CACHE_TTL) {
            Log.d(TAG, "getMyPart() cache hit for userId=$userId")
            return cached
        }
        // 2) 이미 진행 중인 요청이 있으면 그 결과를 공유
        myPartInFlight?.let { deferred ->
            if (deferred.isActive) {
                Log.d(TAG, "getMyPart() joining in-flight request for userId=$userId")
                return deferred.await()
            }
        }
        // 3) 새 요청 시작 — CompletableDeferred로 동시 호출이 합류
        Log.d(TAG, "getMyPart() userId=$userId")
        val deferred = kotlinx.coroutines.CompletableDeferred<String>()
        myPartInFlight = deferred
        try {
            val token = awaitToken()
            val orgApi = ApiClient.createOrgApiFromBaseUrl(appConfig.getRestBaseUrl(), token)
            val endpoint = appConfig.getEndpoint(EP_ORG_MY_PART, "api/org/mypartrequest")
            val request = MyPartRequestDTO(userId = userId)
            val response = withContext(Dispatchers.IO) {
                orgApi.getMyPart(endpoint, request)
            }
            if (response.isSuccessful) {
                val rawJson = response.body()?.string() ?: "{}"
                Log.d(TAG, "MyPart response: $rawJson")
                myPartCacheJson = rawJson
                myPartCacheUserId = userId
                myPartCacheTime = System.currentTimeMillis()
                deferred.complete(rawJson)
                return rawJson
            } else {
                throw Exception("MyPart failed: ${response.code()}")
            }
        } catch (e: Exception) {
            deferred.completeExceptionally(e)
            throw e
        } finally {
            if (myPartInFlight === deferred) myPartInFlight = null
        }
    }

    suspend fun getMyPartRequest(userId: String): String {
        Log.d(TAG, "getMyPartRequest() userId=$userId")
        val token = awaitToken()
        val orgApi = ApiClient.createOrgApiFromBaseUrl(appConfig.getRestBaseUrl(), token)
        val endpoint = appConfig.getEndpoint(EP_ORG_MY_PART, "api/org/mypartrequest")
        val request = MyPartRequestDTO(userId = userId)
        val response = withContext(Dispatchers.IO) {
            orgApi.getMyPartRequest(endpoint, request)
        }
        if (response.isSuccessful) {
            val rawJson = response.body()?.string() ?: "{}"
            Log.d(TAG, "MyPartRequest response: $rawJson")
            return rawJson
        } else {
            throw Exception("MyPartRequest failed: ${response.code()}")
        }
    }

    suspend fun getSubOrgFromServer(userId: String, deptId: String): String {
        Log.d(TAG, "getSubOrgFromServer() userId=$userId, deptId=$deptId")
        val token = awaitToken()
        val orgApi = ApiClient.createOrgApiFromBaseUrl(appConfig.getRestBaseUrl(), token)
        val endpoint = appConfig.getEndpoint(EP_ORG_SUB_ORG, "api/org/suborg")
        val request = SubOrgRequestDTO(userId = userId, deptId = deptId)
        val response = withContext(Dispatchers.IO) {
            orgApi.getSubOrg(endpoint, request)
        }
        if (response.isSuccessful) {
            val rawJson = response.body()?.string() ?: "{}"
            Log.d(TAG, "SubOrg response: $rawJson")
            return rawJson
        } else {
            throw Exception("SubOrg failed: ${response.code()}")
        }
    }

    suspend fun searchUser(type: String, keyword: String): String {
        Log.d(TAG, "searchUser() REST call: type=$type, keyword=$keyword")
        val token = awaitToken()
        val orgApi = ApiClient.createOrgApiFromBaseUrl(appConfig.getRestBaseUrl(), token)
        val endpoint = appConfig.getEndpoint(EP_ORG_SEARCH_USER, "api/org/searchuser")
        val request = SearchUserRequestDTO(type = type, keyword = keyword)
        val response = withContext(Dispatchers.IO) {
            orgApi.searchUser(endpoint, request)
        }
        if (response.isSuccessful) {
            val rawJson = response.body()?.string() ?: "{}"
            Log.d(TAG, "SearchUser REST response: $rawJson")
            return rawJson
        } else {
            throw Exception("SearchUser REST failed: ${response.code()}")
        }
    }

    private suspend fun awaitToken(): String? {
        if (sessionManager.jwtToken != null) return sessionManager.jwtToken
        Log.d(TAG, "Waiting for JWT token...")
        return withTimeoutOrNull(10_000L) { sessionManager.jwtTokenDeferred.await() }
    }

    // ── JsonReader helpers (syncOrg 스트리밍 파싱용) ──

    private fun nextStringOrEmpty(reader: android.util.JsonReader): String {
        return when (reader.peek()) {
            android.util.JsonToken.NULL -> { reader.nextNull(); "" }
            android.util.JsonToken.STRING -> reader.nextString()
            android.util.JsonToken.NUMBER -> reader.nextString()  // 숫자도 문자열로
            android.util.JsonToken.BOOLEAN -> reader.nextBoolean().toString()
            else -> { reader.skipValue(); "" }
        }
    }

    /** nested userInfo 객체를 다시 JSON 문자열로 직렬화 (DB 저장 포맷 호환) */
    private fun readObjectAsJsonString(reader: android.util.JsonReader): String {
        if (reader.peek() == android.util.JsonToken.NULL) { reader.nextNull(); return "{}" }
        val obj = JSONObject()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            val v = readValueAsAny(reader)
            if (v == null) obj.put(name, JSONObject.NULL) else obj.put(name, v)
        }
        reader.endObject()
        return obj.toString()
    }

    private fun readValueAsAny(reader: android.util.JsonReader): Any? {
        return when (reader.peek()) {
            android.util.JsonToken.BEGIN_OBJECT -> {
                val inner = JSONObject()
                reader.beginObject()
                while (reader.hasNext()) {
                    val n = reader.nextName()
                    val v = readValueAsAny(reader)
                    if (v == null) inner.put(n, JSONObject.NULL) else inner.put(n, v)
                }
                reader.endObject()
                inner
            }
            android.util.JsonToken.BEGIN_ARRAY -> {
                val arr = JSONArray()
                reader.beginArray()
                while (reader.hasNext()) arr.put(readValueAsAny(reader))
                reader.endArray()
                arr
            }
            android.util.JsonToken.STRING -> reader.nextString()
            android.util.JsonToken.NUMBER -> {
                val s = reader.nextString()
                s.toLongOrNull() ?: s.toDoubleOrNull() ?: s
            }
            android.util.JsonToken.BOOLEAN -> reader.nextBoolean()
            android.util.JsonToken.NULL -> { reader.nextNull(); null }
            else -> { reader.skipValue(); null }
        }
    }
}
