package net.spacenx.messenger.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.spacenx.messenger.BuildConfig
import net.spacenx.messenger.util.FileLogger
import kotlinx.coroutines.withTimeoutOrNull
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.common.AppConfig.Companion.EP_BUDDY_SYNC_BUDDY
import net.spacenx.messenger.common.AppConfig.Companion.EP_ORG_MY_PART
import net.spacenx.messenger.data.local.DatabaseProvider
import net.spacenx.messenger.data.local.entity.BuddyEntity
import net.spacenx.messenger.data.local.entity.SyncMetaEntity
import net.spacenx.messenger.data.remote.api.ApiClient
import net.spacenx.messenger.data.remote.api.dto.MyPartRequestDTO
import net.spacenx.messenger.data.remote.api.dto.SyncBuddyRequestDTO
import net.spacenx.messenger.service.socket.SocketSessionManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.spacenx.messenger.data.local.SyncLocks
import org.json.JSONArray
import org.json.JSONObject

/**
 * 내목록 동기화 + 조직도 UserEntity 매칭
 * - syncBuddy: 서버에서 buddy 목록 동기화 (동료/기타 폴더)
 * - syncMyPart: 로그인 사용자의 myDeptId 저장 (부서 탭은 org 테이블에서 직접 조회)
 */

class BuddyRepository(
    private val databaseProvider: DatabaseProvider,
    private val appConfig: AppConfig,
    private val sessionManager: SocketSessionManager
) {
    companion object {
        private const val TAG = "BuddyRepository"
        private const val SYNC_META_KEY = "buddyLastSyncTime"
        private const val SYNC_META_OFFSET_KEY = "buddyEventOffset"
        private const val CONCURRENT_SEPARATOR = "@^^@"
    }

    private val syncMutex = Mutex()

    // ── syncBuddy: REST → DB 저장 ──

    suspend fun syncBuddy(userId: String): Boolean {
        if (!syncMutex.tryLock()) { Log.d(TAG, "syncBuddy already in progress, skipping"); return true }
        try {
        return withContext(Dispatchers.IO) {
            try {
                val token = awaitToken()
                val orgDb = databaseProvider.getOrgDatabase()
                val lastSyncTime = orgDb.syncMetaDao().getValueSync(SYNC_META_KEY) ?: 0L
                val buddyEventOffset = orgDb.syncMetaDao().getValueSync(SYNC_META_OFFSET_KEY) ?: 0L

                Log.d(TAG, "syncBuddy: userId=$userId, lastSyncTime=$lastSyncTime, buddyEventOffset=$buddyEventOffset")
                FileLogger.log(TAG, "syncBuddy REQ userId=$userId lastSyncTime=$lastSyncTime offset=$buddyEventOffset")

                val buddyApi = ApiClient.createBuddyApiFromBaseUrl(appConfig.getRestBaseUrl(), token)
                val endpoint = appConfig.getEndpoint(EP_BUDDY_SYNC_BUDDY, "api/buddy/syncbuddy")
                val request = SyncBuddyRequestDTO(userId = userId, lastSyncTime = lastSyncTime, buddyEventOffset = buddyEventOffset)
                val response = buddyApi.syncBuddy(endpoint, request)

                if (!response.isSuccessful) {
                    Log.e(TAG, "syncBuddy HTTP error: ${response.code()}")
                    return@withContext false
                }

                val rawJson = response.body()?.string() ?: "{}"
                val json = JSONObject(rawJson)
                val errorCode = json.optInt("errorCode", -1)

                if (errorCode != 0) {
                    Log.e(TAG, "syncBuddy error: errorCode=$errorCode")
                    return@withContext false
                }

                val serverTime = json.optLong("lastSyncTime", 0L)
                val buddiesArray = json.optJSONArray("buddies")

                // JSON → Entity 변환 (buddyType="0"이면 users 테이블에서 userName 조회)
                val userDao = orgDb.userDao()
                val buddies = mutableListOf<BuddyEntity>()
                if (buddiesArray != null) {
                    for (i in 0 until buddiesArray.length()) {
                        val b = buddiesArray.getJSONObject(i)
                        var name = b.optString("buddyName", "")
                        val type = b.optString("buddyType", "")
                        if (type == "0" && name.isEmpty()) {
                            val uid = extractUserId(b.optString("buddyId", ""))
                            val user = userDao.getByUserId(uid)
                            if (user != null) {
                                val userInfo = JSONObject(user.userInfo)
                                name = userInfo.optString("userName", "")
                            }
                        }
                        buddies.add(
                            BuddyEntity(
                                buddyId = b.optString("buddyId", ""),
                                buddyParent = b.optString("buddyParent", ""),
                                buddyName = name,
                                buddyType = type,
                                buddyOrder = b.optString("buddyOrder", "")
                            )
                        )
                    }
                }

                // NHM-68: 삭제된 버디 처리
                val removedBuddies = json.optJSONArray("removedBuddies")
                val removedKeys = mutableListOf<Pair<String, String>>() // buddyId, buddyParent
                if (removedBuddies != null) {
                    for (i in 0 until removedBuddies.length()) {
                        val r = removedBuddies.getJSONObject(i)
                        removedKeys.add(
                            r.optString("buddyId", "") to r.optString("buddyParent", "")
                        )
                    }
                }

                // NHM-68: buddyEventOffset 저장
                val newOffset = json.optLong("buddyEventOffset", 0L)

                // 단일 트랜잭션으로 DB 저장
                SyncLocks.orgDbMutex.withLock { orgDb.runInTransaction {
                    if (lastSyncTime == 0L && buddies.isNotEmpty()) {
                        orgDb.buddyDao().deleteAllSync()
                    }
                    if (buddies.isNotEmpty()) {
                        orgDb.buddyDao().insertAllSync(buddies)
                        Log.d(TAG, "syncBuddy: ${buddies.size} buddies saved")
                    }
                    // NHM-68: 삭제된 버디 제거
                    for ((bId, bParent) in removedKeys) {
                        if (bId.isNotEmpty()) {
                            orgDb.buddyDao().deleteByIdAndParentSync(bId, bParent)
                        }
                    }
                    if (removedKeys.isNotEmpty()) {
                        Log.d(TAG, "syncBuddy: ${removedKeys.size} buddies removed")
                    }

                    orgDb.syncMetaDao().insertSync(SyncMetaEntity(SYNC_META_KEY, serverTime))
                    if (newOffset > 0) {
                        orgDb.syncMetaDao().insertSync(SyncMetaEntity(SYNC_META_OFFSET_KEY, newOffset))
                    }
                } }

                Log.d(TAG, "syncBuddy complete: serverTime=$serverTime, offset=$newOffset, removed=${removedKeys.size}")
                FileLogger.log(TAG, "syncBuddy DONE buddies=${buddies.size} removed=${removedKeys.size} newOffset=$newOffset")
                return@withContext true
            } catch (e: Exception) {
                Log.e(TAG, "syncBuddy error: ${e.message}", e)
                FileLogger.log(TAG, "syncBuddy ERROR ${e.message}")
                return@withContext false
            }
        }
        } finally { syncMutex.unlock() }
    }

    // ── 내부서: myDeptId 저장 (부서 탭은 org 테이블에서 직접 조회) ──

    suspend fun syncMyPart(userId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val token = awaitToken()
                val orgApi = ApiClient.createOrgApiFromBaseUrl(appConfig.getRestBaseUrl(), token)
                val endpoint = appConfig.getEndpoint(EP_ORG_MY_PART, "api/org/mypartrequest")
                val response = orgApi.getMyPartRequest(endpoint, MyPartRequestDTO(userId = userId))

                if (!response.isSuccessful) {
                    Log.e(TAG, "syncMyPart HTTP error: ${response.code()}")
                    return@withContext false
                }

                val rawJson = response.body()?.string() ?: "{}"
                val json = JSONObject(rawJson)
                Log.d(TAG, "syncMyPart response: $rawJson")
                FileLogger.log(TAG, "syncMyPart RES errorCode=${json.optInt("errorCode", -1)}")

                if (json.optInt("errorCode", -1) != 0) {
                    Log.e(TAG, "syncMyPart error: errorCode=${json.optInt("errorCode")}")
                    return@withContext false
                }

                val pathDepts = json.optJSONArray("pathDepts")
                if (pathDepts == null || pathDepts.length() == 0) {
                    Log.d(TAG, "syncMyPart: no pathDepts")
                    return@withContext true
                }

                // 마지막 dept가 사용자의 소속 부서
                val myDept = pathDepts.getJSONObject(pathDepts.length() - 1)
                val myDeptId = myDept.optString("deptId", "")
                Log.d(TAG, "syncMyPart: myDeptId=$myDeptId, deptName=${myDept.optString("deptName")}")

                appConfig.saveMyDeptId(myDeptId)

                Log.d(TAG, "syncMyPart complete: myDeptId=$myDeptId saved")
                FileLogger.log(TAG, "syncMyPart DONE myDeptId=$myDeptId")
                return@withContext true
            } catch (e: Exception) {
                Log.e(TAG, "syncMyPart error: ${e.message}", e)
                return@withContext false
            }
        }
    }

    // ── 내목록 + UserInfo 매칭 → JSON (웹 전달용) ──

    suspend fun getBuddyListWithUserInfo(): String {
        return withContext(Dispatchers.IO) {
            val orgDb = databaseProvider.getOrgDatabase()
            val allBuddies = orgDb.buddyDao().getAll()
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "getBuddyList: ===== buddies dump (${allBuddies.size}) =====")
                allBuddies.forEach { b ->
                    Log.d(TAG, "getBuddyList: id=${b.buddyId}, parent=${b.buddyParent}, name=${b.buddyName}, type=${b.buddyType}, order=${b.buddyOrder}")
                }
                Log.d(TAG, "getBuddyList: ===== end dump =====")
            }

            // buddyType="0" 사용자들의 userId 추출 → UserEntity 배치 조회 (N+1 방지)
            val userBuddyIds = allBuddies
                .filter { it.buddyType == "0" }
                .map { extractUserId(it.buddyId) }
                .distinct()

            val userMap: Map<String, String> = if (userBuddyIds.isNotEmpty()) {
                orgDb.userDao().getByUserIds(userBuddyIds)
                    .associate { it.userId to it.userInfo }
            } else emptyMap()

            // JSON 조립
            val buddiesArray = JSONArray()
            for (buddy in allBuddies) {
                val obj = JSONObject().apply {
                    put("buddyId", buddy.buddyId)
                    put("buddyParent", buddy.buddyParent)
                    put("buddyName", buddy.buddyName)
                    put("buddyType", buddy.buddyType)
                    put("buddyOrder", buddy.buddyOrder)
                }

                // 사용자 타입이면 userInfo 병합
                if (buddy.buddyType == "0") {
                    val userId = extractUserId(buddy.buddyId)
                    val userInfoStr = userMap[userId]
                    if (userInfoStr != null) {
                        val userInfo = JSONObject(userInfoStr)
                        val keys = userInfo.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            obj.put(key, userInfo.get(key))
                        }
                        // buddyName이 비어있으면 userName으로 채움
                        if (buddy.buddyName.isEmpty()) {
                            val userName = userInfo.optString("userName", "")
                            if (userName.isNotEmpty()) {
                                obj.put("buddyName", userName)
                            }
                        }
                    }
                    obj.put("userId", userId)
                    // 내목록 detail에 직급 표시 (React는 deptName을 detail로 사용)
                    val posName = obj.optString("posName", "")
                    if (posName.isNotEmpty()) {
                        obj.put("deptName", posName)
                    }
                }

                buddiesArray.put(obj)
            }

            // 내 부서 폴더는 LoginViewModel.emitBuddyList()에서 추가됨
            // BridgeDispatcher.handleSyncBuddy()에서 호출 시에도 추가
            JSONObject().apply {
                put("errorCode", 0)
                put("buddies", buddiesArray)
            }.toString()
        }
    }

    /**
     * 내 부서 폴더(buddyType="6") + 부서원 추가
     * Flutter BuddyService._addMyDeptFolder() 이식
     */
    private suspend fun addMyDeptFolder(orgDb: net.spacenx.messenger.data.local.OrgDatabase, buddiesArray: JSONArray) {
        try {
            val userId = appConfig.getSavedUserId() ?: return
            if (userId.isEmpty()) return

            val me = orgDb.userDao().getByUserId(userId) ?: return
            val myDeptId = me.deptId
            if (myDeptId.isEmpty()) return

            // 부서명 가져오기
            var deptName = myDeptId
            if (me.userInfo.isNotEmpty()) {
                try {
                    val info = JSONObject(me.userInfo)
                    val dn = info.optString("deptName", "")
                    if (dn.isNotEmpty()) deptName = dn
                } catch (_: Exception) {}
            }
            // userInfo에 없으면 dept 테이블에서 조회
            if (deptName == myDeptId) {
                val dept = orgDb.deptDao().getByDeptId(myDeptId)
                if (dept != null && dept.deptName.isNotEmpty()) deptName = dept.deptName
            }

            // 부서 폴더 추가 (buddyType "6" = 그룹, 최상단)
            val folderId = "_myDept_$myDeptId"
            buddiesArray.put(JSONObject().apply {
                put("buddyId", folderId)
                put("buddyParent", "0")
                put("buddyName", deptName)
                put("buddyType", "6")
                put("buddyOrder", "000000")
            })

            // 부서원 추가
            val deptUsers = orgDb.userDao().getByDeptId(myDeptId)
            var order = 1
            for (user in deptUsers) {
                var userName = user.userId
                if (user.userInfo.isNotEmpty()) {
                    try {
                        val info = JSONObject(user.userInfo)
                        val un = info.optString("userName", "")
                        if (un.isNotEmpty()) userName = un
                    } catch (_: Exception) {}
                }
                buddiesArray.put(JSONObject().apply {
                    put("buddyId", user.userId)
                    put("buddyParent", folderId)
                    put("buddyName", userName)
                    put("buddyType", "U")
                    put("buddyOrder", (order++).toString().padStart(6, '0'))
                    put("userId", user.userId)
                    // userInfo 병합
                    if (user.userInfo.isNotEmpty()) {
                        try {
                            val info = JSONObject(user.userInfo)
                            val keys = info.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                put(key, info.get(key))
                            }
                        } catch (_: Exception) {}
                    }
                })
            }
            Log.d(TAG, "addMyDeptFolder: folder=$deptName, members=${deptUsers.size}")
        } catch (e: Exception) {
            Log.w(TAG, "addMyDeptFolder error: ${e.message}")
        }
    }


    /**
     * buddyId에서 userId 추출
     * "ultari000001@^^@ultari0000" → "ultari000001"
     * "ultari000001" → "ultari000001"
     */
    private fun extractUserId(buddyId: String): String {
        val idx = buddyId.indexOf(CONCURRENT_SEPARATOR)
        return if (idx > 0) buddyId.substring(0, idx) else buddyId
    }

    private suspend fun awaitToken(): String? {
        if (sessionManager.jwtToken != null) return sessionManager.jwtToken
        Log.d(TAG, "Waiting for JWT token...")
        return withTimeoutOrNull(10_000L) { sessionManager.jwtTokenDeferred.await() }
    }
}
