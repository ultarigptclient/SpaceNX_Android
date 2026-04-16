package net.spacenx.messenger.ui.bridge.handler

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.spacenx.messenger.data.remote.api.ApiClient
import net.spacenx.messenger.data.repository.BuddyRepository
import net.spacenx.messenger.data.repository.OrgRepository
import net.spacenx.messenger.data.repository.PubSubRepository
import net.spacenx.messenger.ui.bridge.BridgeContext
import org.json.JSONArray
import org.json.JSONObject

class OrgHandler(
    private val ctx: BridgeContext,
    private val orgRepo: OrgRepository,
    private val buddyRepo: BuddyRepository,
    private val pubSubRepo: PubSubRepository
) {
    companion object {
        private const val TAG = "OrgHandler"
    }

    suspend fun handle(action: String, params: Map<String, Any?>) {
        when (action) {
            "getOrgList" -> handleGetOrgList()
            "getOrgSubList" -> handleGetOrgSubList(params)
            "searchUsers" -> handleSearchUsers(params)
            "openUserDetail" -> handleOpenUserDetail(params)
            "syncBuddy" -> handleSyncBuddy()
            "addUserToMyList" -> handleAddUserToMyList(params)
            "getMyPart" -> handleGetMyPart(params)
            "addBuddy" -> handleAddBuddy(params)
            "removeBuddy" -> handleRemoveBuddy(params)
        }
    }

    private suspend fun handleGetOrgList() {
        if (ctx.guardDbNotReady("getOrgList")) return
        try {
            val json = withContext(Dispatchers.IO) { orgRepo.getRootDeptsAsJson() }
            ctx.resolveToJs("getOrgList", json)
        } catch (e: Exception) {
            ctx.rejectToJs("getOrgList", e.message)
        }
    }

    private suspend fun handleGetOrgSubList(params: Map<String, Any?>) {
        try {
            val deptId = ctx.paramStr(params, "deptId")
            val json = withContext(Dispatchers.IO) { orgRepo.getSubOrgAsJson(deptId) }
            ctx.resolveToJs("getOrgSubList", json)
            ctx.subscribeUsersFromJson(json, "users")
        } catch (e: Exception) {
            ctx.rejectToJs("getOrgSubList", e.message)
        }
    }

    private suspend fun handleSearchUsers(params: Map<String, Any?>) {
        try {
            val keyword = ctx.paramStr(params, "keyword")
            val rawJson = withContext(Dispatchers.IO) {
                orgRepo.searchUser(ctx.paramStr(params, "type").ifEmpty { "USER" }, keyword)
            }
            val jsonObj = JSONObject(rawJson)
            val users = jsonObj.optJSONArray("users")
            if (users != null) {
                for (i in 0 until users.length()) {
                    val user = users.getJSONObject(i)
                    val userInfo = user.optJSONObject("userInfo")
                    if (userInfo != null) {
                        val keys = userInfo.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            if (!user.has(key)) user.put(key, userInfo.get(key))
                        }
                    }
                }
            }
            ctx.resolveToJs("searchUsers", jsonObj.toString())
        } catch (e: Exception) {
            ctx.rejectToJs("searchUsers", e.message)
        }
    }

    /**
     * openUserDetail — JS가 nativeSend 호출 시 callbackId='openUserDetail_<userId>'로
     * 등록하므로, _callbackId 파라미터를 읽어 resolveToJs(cbId, data)로 응답한다.
     */
    /**
     * openUserDetail resolve 시 BRIDGE_SHIM_JS 의 __oudResolve 큐를 우선 사용.
     * shim 이 pm[cbId] 에 수집해 둔 모든 resolve fn 을 한 번에 호출한다.
     * shim 미설치(페이지 로드 전 등) 시에는 기존 window._cbIdResolve 직접 호출로 폴백.
     */
    private fun resolveUserDetail(cbId: String, data: JSONObject) {
        val d = ctx.esc(data.toString())
        val cb = ctx.esc(cbId)
        ctx.evalJs(
            "(function(){var d='$d';" +
            "if(window.__oudResolve&&window.__oudResolve('$cb',d))return;" +
            "window._${cbId}Resolve&&window._${cbId}Resolve(d);})()"
        )
    }

    private suspend fun handleOpenUserDetail(params: Map<String, Any?>) {
        val userId = ctx.paramStr(params, "userId")
        val cbId = ctx.paramStr(params, "_callbackId").ifEmpty { "openUserDetail" }
        Log.d(TAG, "openUserDetail: userId=$userId, cbId=$cbId")
        try {
            val orgDb = ctx.dbProvider.getOrgDatabase()
            val user = withContext(Dispatchers.IO) { orgDb.userDao().getByUserId(userId) }
            if (user != null) {
                val info = try { JSONObject(user.userInfo) } catch (_: Exception) { JSONObject() }
                info.put("userId", userId)
                info.put("deptId", user.deptId)
                info.put("errorCode", 0)
                resolveUserDetail(cbId, info)
                return
            }
            val buddy = withContext(Dispatchers.IO) { orgDb.buddyDao().getByUserId(userId) }
            if (buddy != null && buddy.buddyName.isNotEmpty()) {
                Log.d(TAG, "openUserDetail: userId=$userId found in buddies, name=${buddy.buddyName}")
                resolveUserDetail(cbId, JSONObject()
                    .put("userId", userId)
                    .put("userName", buddy.buddyName)
                    .put("errorCode", 0))
                return
            }
            Log.w(TAG, "openUserDetail: userId=$userId not in local DB, resolving with userId as fallback")
            resolveUserDetail(cbId, JSONObject()
                .put("userId", userId)
                .put("userName", userId)
                .put("errorCode", 0))
        } catch (e: Exception) {
            Log.w(TAG, "openUserDetail: exception for userId=$userId: ${e.message}")
            resolveUserDetail(cbId, JSONObject()
                .put("userId", userId)
                .put("userName", userId)
                .put("errorCode", 0))
        }
    }

    private suspend fun handleSyncBuddy() {
        if (ctx.guardDbNotReady("syncBuddy")) return
        try {
            val userId = ctx.appConfig.getSavedUserId() ?: ""
            if (userId.isNotEmpty()) {
                withContext(Dispatchers.IO) { buddyRepo.syncBuddy(userId) }
            }
            val rawJson = withContext(Dispatchers.IO) { buddyRepo.getBuddyListWithUserInfo() }
            // dept 폴더는 React가 getOrgSubList로 수신 — enrichment 없이 raw 반환
            ctx.resolveToJs("syncBuddy", rawJson)
        } catch (e: Exception) {
            ctx.rejectToJs("syncBuddy", e.message)
        }
    }

    private suspend fun enrichBuddyWithMyDept(rawJson: String): String {
        try {
            val buddyJson = JSONObject(rawJson)
            val myDeptJson = JSONObject(orgRepo.getMyDeptAsJson())
            val deptId = myDeptJson.optString("deptId", "")
            if (deptId.isEmpty()) return rawJson
            val deptName = myDeptJson.optString("deptName", "").ifEmpty { "내부서" }

            val buddies = buddyJson.optJSONArray("buddies") ?: JSONArray()
            val deptBuddyId = "_dept_$deptId"

            // 이미 내 부서 폴더가 있으면 중복 추가하지 않음
            var alreadyHasDept = false
            for (i in 0 until buddies.length()) {
                if (buddies.getJSONObject(i).optString("buddyId") == deptBuddyId) {
                    alreadyHasDept = true
                    break
                }
            }
            if (alreadyHasDept) return buddyJson.toString()

            buddies.put(JSONObject().apply {
                put("buddyId", deptBuddyId)
                put("buddyParent", "0")
                put("buddyName", deptName)
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
                    val posName = user.optString("posName", "")
                    put("deptName", posName.ifEmpty { user.optString("deptName", "") })
                    put("companyCode", user.optString("companyCode", ""))
                    put("posName", posName)
                    put("loginId", user.optString("loginId", ""))
                    put("phone", user.optString("phone", ""))
                    put("mobile", user.optString("mobile", ""))
                    put("userName", user.optString("userName", ""))
                    put("email", user.optString("email", ""))
                })
            }

            buddyJson.put("buddies", buddies)
            return buddyJson.toString()
        } catch (e: Exception) {
            Log.w(TAG, "enrichBuddyWithMyDept error: ${e.message}")
            return rawJson
        }
    }

    private suspend fun handleGetMyPart(params: Map<String, Any?>) {
        try {
            val userId = ctx.paramStr(params, "userId").ifEmpty { ctx.appConfig.getSavedUserId() ?: "" }
            val rawJson = withContext(Dispatchers.IO) { orgRepo.getMyPart(userId) }
            ctx.resolveToJs("getMyPart", rawJson)
            ctx.subscribeUsersFromJson(rawJson, "users")
        } catch (e: Exception) {
            ctx.rejectToJs("getMyPart", e.message)
        }
    }

    private suspend fun handleAddUserToMyList(params: Map<String, Any?>) {
        try {
            val result = withContext(Dispatchers.IO) {
                val body = ctx.paramsToJson(params)
                ApiClient.postJson(ctx.appConfig.getEndpointByPath("/buddy/addlink"), body)
            }
            val errorCode = result.optInt("errorCode", -1)
            if (errorCode == 0) {
                withContext(Dispatchers.IO) { buddyRepo.syncBuddy(ctx.appConfig.getSavedUserId() ?: "") }
            }
            ctx.resolveToJs("addUserToMyList", result)
        } catch (e: Exception) {
            ctx.rejectToJs("addUserToMyList", e.message)
        }
    }

    private suspend fun handleAddBuddy(params: Map<String, Any?>) {
        try {
            val myUserId = ctx.appConfig.getSavedUserId() ?: ""
            val targetUserId = ctx.paramStr(params, "userId")
            val result = withContext(Dispatchers.IO) {
                val token = ctx.loginViewModel.sessionManager.jwtToken
                ApiClient.postJson(
                    ctx.appConfig.getEndpointByPath("/buddy/addlink"),
                    JSONObject().put("userIds", JSONArray().put(targetUserId)).put("groupId", "0"),
                    token
                )
            }
            if (result.optInt("errorCode", -1) == 0) {
                withContext(Dispatchers.IO) { buddyRepo.syncBuddy(myUserId) }
            }
            ctx.resolveToJs("addBuddy", result)
        } catch (e: Exception) {
            ctx.rejectToJs("addBuddy", e.message)
        }
    }

    private suspend fun handleRemoveBuddy(params: Map<String, Any?>) {
        try {
            val myUserId = ctx.appConfig.getSavedUserId() ?: ""
            val targetUserId = ctx.paramStr(params, "userId")
            val result = withContext(Dispatchers.IO) {
                val token = ctx.loginViewModel.sessionManager.jwtToken
                ApiClient.postJson(
                    ctx.appConfig.getEndpointByPath("/buddy/buddydel"),
                    JSONObject().put("userId", myUserId)
                        .put("buddyId", targetUserId)
                        .put("buddyParent", "0"),
                    token
                )
            }
            if (result.optInt("errorCode", -1) == 0) {
                withContext(Dispatchers.IO) { buddyRepo.syncBuddy(myUserId) }
            }
            ctx.resolveToJs("removeBuddy", result)
        } catch (e: Exception) {
            ctx.rejectToJs("removeBuddy", e.message)
        }
    }
}
