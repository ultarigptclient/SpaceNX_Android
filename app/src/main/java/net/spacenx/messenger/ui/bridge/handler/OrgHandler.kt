package net.spacenx.messenger.ui.bridge.handler

import android.app.AlertDialog
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.FrameLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.spacenx.messenger.data.remote.api.ApiClient
import net.spacenx.messenger.data.repository.BuddyRepository
import net.spacenx.messenger.data.repository.OrgRepository
import net.spacenx.messenger.data.repository.PubSubRepository
import net.spacenx.messenger.ui.bridge.BridgeContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

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
            "addBuddyGroup" -> handleAddBuddyGroup()
            "deleteBuddyGroup" -> handleDeleteBuddyGroup(params)
            "renameBuddyGroup" -> handleRenameBuddyGroup(params)
            "createSubGroup" -> handleCreateSubGroup(params)
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
            val d = ctx.esc(json)
            ctx.evalJs(
                "(function(){var d='$d';" +
                "if(window.__goslResolve&&window.__goslResolve(d))return;" +
                "window._getOrgSubListResolve&&window._getOrgSubListResolve(d);})()"
            )
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
            // 30초 이내에 네이티브 초기 sync가 완료됐으면 재동기화 skip — 로그인 직후 buddyReady
            // 이벤트를 받은 React가 다시 syncBuddy를 호출해 API가 중복 실행되는 문제 방지
            val elapsed = System.currentTimeMillis() - ctx.loginViewModel.lastBuddySyncMs
            val recentlySynced = userId.isNotEmpty() && elapsed < 30_000L
            if (userId.isNotEmpty() && !recentlySynced) {
                Log.d(TAG, "syncBuddy: API call (elapsed=${elapsed}ms)")
                withContext(Dispatchers.IO) { buddyRepo.syncBuddy(userId) }
            } else {
                Log.d(TAG, "syncBuddy: skip API — recently synced ${elapsed}ms ago, returning local cache")
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
            val d = ctx.esc(rawJson)
            // shim(__gmpResolve)으로 동시 대기 중인 resolve 함수 전부 호출, 없으면 단일 슬롯 fallback
            ctx.evalJs(
                "(function(){var d='$d';" +
                "if(window.__gmpResolve&&window.__gmpResolve(d))return;" +
                "window._getMyPartResolve&&window._getMyPartResolve(d);})()"
            )
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
                ctx.notifyReact("buddyReady")
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
            val buddyName = withContext(Dispatchers.IO) {
                val user = ctx.dbProvider.getOrgDatabase().userDao().getByUserId(targetUserId)
                if (user != null) JSONObject(user.userInfo).optString("userName", "") else ""
            }
            val result = withContext(Dispatchers.IO) {
                val token = ctx.loginViewModel.sessionManager.jwtToken
                ApiClient.postJson(
                    ctx.appConfig.getEndpointByPath("/buddy/buddyadd"),
                    JSONObject()
                        .put("userId", myUserId)
                        .put("buddyId", targetUserId)
                        .put("buddyParent", "0")
                        .put("buddyName", buddyName)
                        .put("buddyType", "0"),
                    token
                )
            }
            if (result.optInt("errorCode", -1) == 0) {
                withContext(Dispatchers.IO) { buddyRepo.syncBuddy(myUserId) }
                ctx.notifyReact("buddyReady")
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
                ctx.notifyReact("buddyReady")
            }
            ctx.resolveToJs("removeBuddy", result)
        } catch (e: Exception) {
            ctx.rejectToJs("removeBuddy", e.message)
        }
    }

    private suspend fun handleAddBuddyGroup() {
        try {
            val name = promptForText("최상위 그룹 추가", "", "그룹 이름")?.trim().orEmpty()
            if (name.isEmpty()) {
                ctx.resolveToJs("addBuddyGroup", JSONObject().put("errorCode", 0).put("cancelled", true))
                return
            }
            val myUserId = ctx.appConfig.getSavedUserId() ?: ""
            val result = createBuddyGroup(myUserId, newGroupId(), "0", name)
            if (result.optInt("errorCode", -1) == 0) {
                withContext(Dispatchers.IO) { buddyRepo.syncBuddy(myUserId) }
                ctx.notifyReact("buddyReady")
            }
            ctx.resolveToJs("addBuddyGroup", result)
        } catch (e: Exception) {
            ctx.rejectToJs("addBuddyGroup", e.message)
        }
    }

    private suspend fun handleCreateSubGroup(params: Map<String, Any?>) {
        try {
            val parentId = paramGroupId(params)
            if (parentId.isEmpty()) {
                ctx.rejectToJs("createSubGroup", "groupId required")
                return
            }
            val name = promptForText("하위 그룹 생성", "", "그룹 이름")?.trim().orEmpty()
            if (name.isEmpty()) {
                ctx.resolveToJs("createSubGroup", JSONObject().put("errorCode", 0).put("cancelled", true))
                return
            }
            val myUserId = ctx.appConfig.getSavedUserId() ?: ""
            val result = createBuddyGroup(myUserId, newGroupId(), parentId, name)
            if (result.optInt("errorCode", -1) == 0) {
                withContext(Dispatchers.IO) { buddyRepo.syncBuddy(myUserId) }
                ctx.notifyReact("buddyReady")
            }
            ctx.resolveToJs("createSubGroup", result)
        } catch (e: Exception) {
            ctx.rejectToJs("createSubGroup", e.message)
        }
    }

    private suspend fun handleRenameBuddyGroup(params: Map<String, Any?>) {
        try {
            val buddyId = paramGroupId(params)
            if (buddyId.isEmpty()) {
                ctx.rejectToJs("renameBuddyGroup", "groupId required")
                return
            }
            val existing = withContext(Dispatchers.IO) {
                ctx.dbProvider.getOrgDatabase().buddyDao().getByBuddyId(buddyId)
            }
            if (existing == null) {
                ctx.rejectToJs("renameBuddyGroup", "group not found")
                return
            }
            val newName = promptForText("그룹 이름 변경", existing.buddyName, "그룹 이름")?.trim().orEmpty()
            if (newName.isEmpty() || newName == existing.buddyName) {
                ctx.resolveToJs("renameBuddyGroup", JSONObject().put("errorCode", 0).put("cancelled", true))
                return
            }
            val myUserId = ctx.appConfig.getSavedUserId() ?: ""
            val result = withContext(Dispatchers.IO) {
                val token = ctx.loginViewModel.sessionManager.jwtToken
                ApiClient.postJson(
                    ctx.appConfig.getEndpointByPath("/buddy/buddymod"),
                    JSONObject()
                        .put("userId", myUserId)
                        .put("buddyId", buddyId)
                        .put("buddyParent", existing.buddyParent)
                        .put("buddyName", newName),
                    token
                )
            }
            if (result.optInt("errorCode", -1) == 0) {
                withContext(Dispatchers.IO) { buddyRepo.syncBuddy(myUserId) }
                ctx.notifyReact("buddyReady")
            }
            ctx.resolveToJs("renameBuddyGroup", result)
        } catch (e: Exception) {
            ctx.rejectToJs("renameBuddyGroup", e.message)
        }
    }

    private suspend fun handleDeleteBuddyGroup(params: Map<String, Any?>) {
        try {
            val buddyId = paramGroupId(params)
            if (buddyId.isEmpty()) {
                ctx.rejectToJs("deleteBuddyGroup", "groupId required")
                return
            }
            val existing = withContext(Dispatchers.IO) {
                ctx.dbProvider.getOrgDatabase().buddyDao().getByBuddyId(buddyId)
            }
            if (existing == null) {
                ctx.rejectToJs("deleteBuddyGroup", "group not found")
                return
            }
            val confirmed = promptForConfirm(
                "그룹 삭제",
                "'${existing.buddyName}' 그룹을 삭제하시겠습니까?"
            )
            if (!confirmed) {
                ctx.resolveToJs("deleteBuddyGroup", JSONObject().put("errorCode", 0).put("cancelled", true))
                return
            }
            val myUserId = ctx.appConfig.getSavedUserId() ?: ""
            val result = withContext(Dispatchers.IO) {
                val token = ctx.loginViewModel.sessionManager.jwtToken
                ApiClient.postJson(
                    ctx.appConfig.getEndpointByPath("/buddy/buddydel"),
                    JSONObject()
                        .put("userId", myUserId)
                        .put("buddyId", buddyId)
                        .put("buddyParent", existing.buddyParent),
                    token
                )
            }
            if (result.optInt("errorCode", -1) == 0) {
                withContext(Dispatchers.IO) { buddyRepo.syncBuddy(myUserId) }
                ctx.notifyReact("buddyReady")
            }
            ctx.resolveToJs("deleteBuddyGroup", result)
        } catch (e: Exception) {
            ctx.rejectToJs("deleteBuddyGroup", e.message)
        }
    }

    private fun newGroupId(): String =
        "g_${System.currentTimeMillis().toString(36)}${(1000..9999).random()}"

    private fun paramGroupId(params: Map<String, Any?>): String {
        val keys = listOf("buddyId", "groupId", "id")
        for (k in keys) {
            val v = ctx.paramStr(params, k)
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    private suspend fun createBuddyGroup(
        userId: String,
        buddyId: String,
        buddyParent: String,
        buddyName: String
    ): JSONObject = withContext(Dispatchers.IO) {
        val token = ctx.loginViewModel.sessionManager.jwtToken
        ApiClient.postJson(
            ctx.appConfig.getEndpointByPath("/buddy/buddyadd"),
            JSONObject()
                .put("userId", userId)
                .put("buddyId", buddyId)
                .put("buddyParent", buddyParent)
                .put("buddyName", buddyName)
                .put("buddyType", "6")
                .put("buddyOrder", "999999"),
            token
        )
    }

    private suspend fun promptForText(title: String, prefill: String, hint: String): String? =
        suspendCancellableCoroutine { cont ->
            ctx.activity.runOnUiThread {
                val editText = EditText(ctx.activity).apply {
                    this.hint = hint
                    setText(prefill)
                    setSelection(prefill.length)
                    inputType = InputType.TYPE_CLASS_TEXT
                }
                val dp = ctx.activity.resources.displayMetrics.density
                val container = FrameLayout(ctx.activity).apply {
                    val pad = (24 * dp).toInt()
                    setPadding(pad, (8 * dp).toInt(), pad, 0)
                    addView(editText)
                }
                val resumed = java.util.concurrent.atomic.AtomicBoolean(false)
                val resumeOnce: (String?) -> Unit = { value ->
                    if (resumed.compareAndSet(false, true) && cont.isActive) cont.resume(value)
                }
                AlertDialog.Builder(ctx.activity)
                    .setTitle(title)
                    .setView(container)
                    .setPositiveButton("확인") { _, _ -> resumeOnce(editText.text.toString()) }
                    .setNegativeButton("취소") { _, _ -> resumeOnce(null) }
                    .setOnCancelListener { resumeOnce(null) }
                    .show()
            }
        }

    private suspend fun promptForConfirm(title: String, message: String): Boolean =
        suspendCancellableCoroutine { cont ->
            ctx.activity.runOnUiThread {
                val resumed = java.util.concurrent.atomic.AtomicBoolean(false)
                val resumeOnce: (Boolean) -> Unit = { value ->
                    if (resumed.compareAndSet(false, true) && cont.isActive) cont.resume(value)
                }
                AlertDialog.Builder(ctx.activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("확인") { _, _ -> resumeOnce(true) }
                    .setNegativeButton("취소") { _, _ -> resumeOnce(false) }
                    .setOnCancelListener { resumeOnce(false) }
                    .show()
            }
        }
}
