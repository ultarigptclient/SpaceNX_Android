package net.spacenx.messenger.ui.bridge

import kotlinx.coroutines.CoroutineScope
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.data.local.DatabaseProvider
import net.spacenx.messenger.data.repository.UserNameCache
import net.spacenx.messenger.ui.MainActivity
import net.spacenx.messenger.ui.viewmodel.LoginViewModel
import org.json.JSONObject

/**
 * Handler들이 공통으로 사용하는 BridgeDispatcher 기능을 노출하는 인터페이스.
 */
interface BridgeContext {
    val activity: MainActivity
    val appConfig: AppConfig
    val dbProvider: DatabaseProvider
    val scope: CoroutineScope
    val userNameCache: UserNameCache
    val loginViewModel: LoginViewModel
    var activeChannelCode: String?
    val completedSyncs: MutableSet<String>

    // JS 통신
    fun resolveToJs(action: String, data: JSONObject)
    fun resolveToJs(action: String, rawJsonStr: String)
    fun resolveToJsRaw(action: String, rawJsonStr: String)
    fun rejectToJs(action: String, errorMessage: String?)
    fun evalJs(js: String)
    fun evalJsMain(js: String)
    fun notifyReact(event: String)
    fun notifyReactOnce(event: String)
    fun esc(s: String): String

    // 공통 헬퍼
    fun guardDbNotReady(action: String): Boolean
    suspend fun handleRestForward(action: String, path: String, params: Map<String, Any?>)
    suspend fun updateCrudOffset(commandName: String, response: JSONObject)
    suspend fun saveChannelLocally(channelCode: String, members: List<String>, type: String = "")
    fun subscribeUsersFromJson(jsonStr: String, arrayKey: String)

    // 파라미터 유틸리티
    fun paramStr(params: Map<String, Any?>, key: String): String
    fun paramInt(params: Map<String, Any?>, key: String, default: Int = 0): Int
    fun paramLong(params: Map<String, Any?>, key: String): Long?
    fun paramBool(params: Map<String, Any?>, key: String): Boolean
    fun paramList(params: Map<String, Any?>, key: String): List<String>
    fun paramsToJson(params: Map<String, Any?>): JSONObject
    fun paramsToJson(v: Any?): JSONObject
}
