package net.spacenx.messenger.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.common.Constants
import net.spacenx.messenger.data.local.DatabaseProvider
import net.spacenx.messenger.data.local.entity.CommonEntity
import net.spacenx.messenger.data.local.entity.SyncMetaEntity
import net.spacenx.messenger.data.remote.api.ApiClient
import net.spacenx.messenger.data.remote.api.AuthApi
import net.spacenx.messenger.data.remote.api.dto.RefreshTokenRequestDTO
import net.spacenx.messenger.data.remote.api.dto.SyncConfigRequestDTO
import net.spacenx.messenger.data.remote.socket.ConnectionConfig
import org.json.JSONObject

/**
 * 로그인 플로우 오케스트레이터
 *
 * 플로우:
 * 1. REST login (id/pw → accessToken + refreshToken)
 * 2. syncConfig (accessToken → config 저장 + 새 accessToken)
 * 3. Binary socket connect (config의 bridge host/port) → HI (accessToken)
 *
 * 재연결:
 * - savedToken → syncConfig → socket + HI
 * - syncConfig 401/403 → refreshToken → syncConfig → socket + HI
 * - refresh 실패 → 토큰 삭제 → 로그인 화면
 */
sealed class LoginState {
    object Idle : LoginState()
    object Connecting : LoginState()
    object Connected : LoginState()
    /** REST 인증 성공 (Flutter 패턴: JS resolve 즉시, socket 연결 전) */
    data class Authenticated(val userName: String, val userJson: String) : LoginState()
    /** Socket HI 완료 (push 수신 가능) */
    data class LoggedIn(val userName: String, val userJson: String, val config: Map<String, String>) : LoginState()
    data class Failed(val message: String) : LoginState()
    object Disconnected : LoginState()
    /** syncConfig 완료 후 FRONTEND_SKIN/VERSION 변경 감지 → WebView 재로드 필요 */
    data class SkinChanged(val spaUrl: String) : LoginState()
}

class AuthRepository(
    private val appConfig: AppConfig,
    val sessionManager: SocketSessionManager,
    private val databaseProvider: DatabaseProvider
) {
    companion object {
        private const val TAG = "AuthRepository"
        private const val SYNC_META_CONFIG_KEY = "configLastSyncTime"
    }

    val loginState: Flow<LoginState> = sessionManager.loginState

    /** 앱 시작 시 common.db에서 config를 읽어 AppConfig 캐시 초기화 */
    suspend fun loadInitialConfigCache() {
        try {
            val all = databaseProvider.getCommonDatabase().commonDao().getAll()
            appConfig.updateConfigCache(all.associate { it.key to it.value })
        } catch (_: Exception) { /* DB 아직 없을 수 있음 */ }
    }

    /**
     * ID/PW 로그인 (최초 로그인 / 로그인 화면에서 입력)
     *
     * Flutter 패턴:
     * ① REST login → 토큰 저장 → Authenticated (JS resolve 즉시)
     * ② syncConfig + socket + HI → LoggedIn (push 수신 시작)
     */
    suspend fun login(username: String, password: String) {
        Log.d(TAG, "login() called for user: $username")

        sessionManager.pendingUserId = username
        sessionManager.pendingPassword = password
        sessionManager.resetForNewLogin()
        sessionManager.emitLoginStateSuspend(LoginState.Connecting)

        try {
            withContext(Dispatchers.IO) {
                ensureActive()

                // ① REST 로그인 → 토큰 발급
                val restLoginSuccess = restLogin(username, password)
                if (!restLoginSuccess) return@withContext

                // ② Authenticated 즉시 발행 → JS에 로그인 결과 전달 (Flutter 패턴)
                val userJson = sessionManager.restLoginUserJson ?: "{}"
                sessionManager.emitLoginState(
                    LoginState.Authenticated(username, userJson)
                )
                Log.d(TAG, "login: Authenticated emitted, proceeding to syncConfig + socket")

                // ③ syncConfig (백그라운드 — JS는 이미 메인 화면 전환)
                val syncConfigOk = syncConfig(username)
                if (!syncConfigOk) {
                    Log.w(TAG, "login: syncConfig failed, but already authenticated")
                    // Authenticated 이후이므로 Failed 대신 경고만 (React는 이미 메인 화면)
                }

                // ④ socket connect → HI → LoggedIn (push 수신 시작)
                connectSocket(username)
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "login() cancelled, cleaning up sockets")
            sessionManager.resetForNewLogin()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.javaClass.simpleName}: ${e.message}", e)
            sessionManager.emitLoginStateSuspend(LoginState.Failed("연결 실패: ${e.message}"))
        }
    }

    /**
     * refreshToken 기반 재연결 (앱 재시작 / 포그라운드 복귀 / 네트워크 복구)
     *
     * NHM-50 명세:
     * refreshToken → POST /api/auth/refresh → 새 토큰 갱신
     * → syncConfig → socket + HI
     * refresh 실패 → 토큰 삭제 → 로그인 화면
     */
    /**
     * refreshToken 기반 재연결 (앱 재시작 / 포그라운드 복귀 / 네트워크 복구)
     *
     * Flutter autoLogin 패턴:
     * ① refreshToken → POST /auth/refresh → 토큰 갱신
     * ② Authenticated 발행 → JS에 autoLogin resolve (즉시)
     * ③ syncConfig → socket + HI → LoggedIn (push 수신 시작)
     */
    suspend fun reconnect(userId: String) {
        Log.d(TAG, "reconnect() called for user: $userId")

        sessionManager.pendingUserId = userId
        sessionManager.resetForNewLogin()
        sessionManager.emitLoginStateSuspend(LoginState.Connecting)

        try {
            withContext(Dispatchers.IO) {
                ensureActive()
                Constants.myId = userId

                // ① refreshToken으로 새 accessToken 발급 (네트워크 오류 시 5초 간격, 최대 3회 재시도)
                var refreshResult: RefreshResult
                var retryCount = 0
                while (true) {
                    ensureActive()
                    refreshResult = restRefreshToken()
                    when (refreshResult) {
                        RefreshResult.SUCCESS -> break
                        RefreshResult.TOKEN_INVALID -> {
                            Log.d(TAG, "reconnect: token invalid → clear tokens → login screen")
                            appConfig.clearTokens()
                            appConfig.clearFrontendCache()
                            sessionManager.jwtToken = null
                            sessionManager.refreshToken = null
                            sessionManager.emitLoginState(LoginState.Failed("TOKEN_INVALID"))
                            return@withContext
                        }
                        RefreshResult.NETWORK_ERROR -> {
                            if (++retryCount >= 3) {
                                Log.e(TAG, "reconnect: network error after $retryCount retries")
                                sessionManager.emitLoginStateSuspend(LoginState.Failed("네트워크 연결 실패"))
                                return@withContext
                            }
                            Log.d(TAG, "reconnect: network error, retrying in 5s... ($retryCount/3)")
                            kotlinx.coroutines.delay(5000L)
                        }
                    }
                }

                sessionManager.jwtTokenDeferred.complete(sessionManager.jwtToken)

                // ② Authenticated 즉시 발행 → JS에 autoLogin resolve (Flutter 패턴)
                val userJson = buildAutoLoginUserJson(userId)
                sessionManager.emitLoginState(
                    LoginState.Authenticated(userId, userJson)
                )
                Log.d(TAG, "reconnect: Authenticated emitted, proceeding to syncConfig + socket")

                // ③ syncConfig (백그라운드 — JS는 이미 메인 화면 전환)
                val urlBeforeSync = appConfig.getSpaUrl()
                val syncConfigOk = syncConfig(userId)
                if (!syncConfigOk) {
                    Log.w(TAG, "reconnect: syncConfig failed, but already authenticated")
                } else {
                    val urlAfterSync = appConfig.getSpaUrl()
                    if (urlAfterSync != urlBeforeSync) {
                        Log.d(TAG, "reconnect: FRONTEND_SKIN changed → SkinChanged: $urlBeforeSync → $urlAfterSync")
                        sessionManager.emitLoginState(LoginState.SkinChanged(urlAfterSync))
                    }
                }

                // ④ socket connect → HI → LoggedIn (push 수신 시작)
                connectSocket(userId)
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "reconnect() cancelled")
            sessionManager.resetForNewLogin()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Reconnect failed: ${e.javaClass.simpleName}: ${e.message}", e)
            sessionManager.emitLoginStateSuspend(LoginState.Failed("재연결 실패: ${e.message}"))
        }
    }

    /** autoLogin 시 userJson 생성 (REST login 응답이 없으므로 저장된 정보로 구성) */
    private fun buildAutoLoginUserJson(userId: String): String {
        return JSONObject().apply {
            put("errorCode", 0)
            put("userId", userId)
            put("userInfo", JSONObject().apply {
                put("userId", userId)
            })
        }.toString()
    }

    /**
     * config에서 bridge host/port 읽어 소켓 연결.
     * QUIC 활성화 시 QUIC 우선 시도 → 실패 시 TCP 자동 폴백.
     */
    private suspend fun connectSocket(userId: String) {
        val useQuic = appConfig.isQuicEnabled()
        val bridgeHost = appConfig.getBridgeHost()
        val uuid = appConfig.getUuidForUser(userId)
        val gmsToken = appConfig.getUUID() ?: ""

        if (useQuic) {
            val quicPort = appConfig.getQuicPort()
            val quicPortSource = appConfig.getConfigValue(AppConfig.CONFIG_QUIC_PORT)
                ?.let { "DB($it)" } ?: "default($quicPort)"
            Log.d(TAG, "Socket connecting: $bridgeHost:$quicPort transport=QUIC quicPortSrc=$quicPortSource")

            try {
                sessionManager.connectSuspend(
                    ConnectionConfig(
                        host = bridgeHost, port = quicPort, userId = userId,
                        password = "", gmsToken = gmsToken, uuid = uuid,
                        transport = net.spacenx.messenger.data.remote.socket.Transport.QUIC
                    )
                )
                return  // QUIC 성공
            } catch (e: Exception) {
                Log.w(TAG, "QUIC connect failed (${e.message}), falling back to TCP")
            }
        }

        // TCP (기본 또는 QUIC 폴백)
        val tcpPort = appConfig.getBridgePort()
        Log.d(TAG, "Socket connecting: $bridgeHost:$tcpPort transport=TCP")
        sessionManager.connectSuspend(
            ConnectionConfig(
                host = bridgeHost, port = tcpPort, userId = userId,
                password = "", gmsToken = gmsToken, uuid = uuid,
                transport = net.spacenx.messenger.data.remote.socket.Transport.TCP
            )
        )
    }

    // ── REST Login ──

    private suspend fun restLogin(username: String, password: String): Boolean {
        Log.d(TAG, "restLogin() called for user: $username")
        try {
            val baseUrl = appConfig.getRestBaseUrl()
            val authApi = ApiClient.createAuthApiFromBaseUrl(baseUrl)
            val request = mapOf("loginId" to username, "password" to password)
            val response = authApi.login("api/auth/login", request)

            if (!response.isSuccessful) {
                Log.e(TAG, "REST login HTTP error: ${response.code()}")
                sessionManager.emitLoginState(LoginState.Failed("로그인 실패: HTTP ${response.code()}"))
                return false
            }

            val rawJson = response.body()?.string()?.takeIf { it.isNotEmpty() } ?: "{}"
            Log.d(TAG, "REST login response: $rawJson")
            val json = JSONObject(rawJson)
            val errorCode = json.optInt("errorCode", -1)

            if (errorCode != 0) {
                val msg = json.optString("errorMessage", "로그인 실패")
                Log.e(TAG, "REST login failed: errorCode=$errorCode, msg=$msg")
                sessionManager.emitLoginState(LoginState.Failed(msg))
                return false
            }

            // 토큰 저장
            val token = json.optString("accessToken", json.optString("token", ""))
            val refresh = json.optString("refreshToken", "")
            if (token.isNotEmpty() && refresh.isNotEmpty()) {
                sessionManager.jwtToken = token
                sessionManager.refreshToken = refresh
                appConfig.saveTokens(token, refresh)
                sessionManager.jwtTokenDeferred.complete(token)
                Log.d(TAG, "REST login success - tokens saved")
            }

            // 자격증명 저장
            appConfig.saveCredentials(username, password)

            val userId = json.optString("userId", username)
            Constants.myId = userId

            // 로그인 응답의 deptId로 myDeptId 즉시 갱신
            val deptId = json.optString("deptId", "")
            if (deptId.isNotEmpty()) {
                appConfig.saveMyDeptId(deptId)
            }

            // REST 로그인 응답의 사용자 정보 보존
            val userInfo = JSONObject().apply {
                put("userId", userId)
                put("userName", json.optString("userName", ""))
                put("deptId", json.optString("deptId", ""))
            }
            sessionManager.restLoginUserJson = JSONObject().apply {
                put("errorCode", 0)
                put("userId", userId)
                put("userInfo", userInfo)
            }.toString()
            Log.d(TAG, "REST login userInfo saved: ${sessionManager.restLoginUserJson}")

            return true
        } catch (e: Exception) {
            Log.e(TAG, "REST login error: ${e.message}", e)
            sessionManager.emitLoginState(LoginState.Failed("로그인 실패: ${e.message}"))
            return false
        }
    }

    // ── SyncConfig ──

    /**
     * POST /api/auth/syncconfig
     * - lastSyncTime offset 전송
     * - config 배열 → common.db 저장
     * - lastSyncTime → syncMeta 저장
     * - 새 accessToken 갱신
     */
    private suspend fun syncConfig(userId: String): Boolean {
        Log.d(TAG, "syncConfig() called for user: $userId")
        try {
            val commonDb = databaseProvider.getCommonDatabase()
            val lastSyncTime = commonDb.syncMetaDao().getValue(SYNC_META_CONFIG_KEY) ?: 0L

            val baseUrl = appConfig.getRestBaseUrl()
            val authApi = ApiClient.createAuthApiFromBaseUrl(baseUrl)
            val token = sessionManager.jwtToken ?: ""
            val request = SyncConfigRequestDTO(userId = userId, lastSyncTime = lastSyncTime)
            val response = authApi.syncConfig("api/auth/syncconfig", "Bearer $token", request)

            if (response.code() == 401 || response.code() == 403) {
                Log.e(TAG, "syncConfig auth error: ${response.code()}")
                return false
            }

            if (!response.isSuccessful) {
                Log.e(TAG, "syncConfig HTTP error: ${response.code()}")
                return false
            }

            val rawJson = response.body()?.string()?.takeIf { it.isNotEmpty() } ?: "{}"
            Log.d(TAG, "syncConfig response: $rawJson")
            val json = JSONObject(rawJson)
            val errorCode = json.optInt("errorCode", -1)

            if (errorCode != 0) {
                Log.e(TAG, "syncConfig error: errorCode=$errorCode")
                return false
            }

            // 새 accessToken 갱신
            val newToken = json.optString("accessToken", "")
            if (newToken.isNotEmpty()) {
                sessionManager.jwtToken = newToken
                val rt = sessionManager.refreshToken ?: ""
                appConfig.saveTokens(newToken, rt)
                if (!sessionManager.jwtTokenDeferred.isCompleted) {
                    sessionManager.jwtTokenDeferred.complete(newToken)
                }
                Log.d(TAG, "syncConfig - accessToken updated")
            }

            val serverTime = json.optLong("lastSyncTime", 0L)
            val configsArray = json.optJSONArray("configs")

            // config 배열 → common.db 저장
            val configEntities = mutableListOf<CommonEntity>()
            val configMap = mutableMapOf<String, String>()
            if (configsArray != null) {
                for (i in 0 until configsArray.length()) {
                    val c = configsArray.getJSONObject(i)
                    val key = c.optString("key", "")
                    val value = c.optString("value", "")
                    val category = c.optString("category", "")
                    if (key.isNotEmpty()) {
                        configEntities.add(CommonEntity(key = key, value = value))
                        configMap[key] = value
                    }
                }
            }

            commonDb.runInTransaction {
                if (configEntities.isNotEmpty()) {
                    commonDb.commonDao().insertAllSync(configEntities)
                    Log.d(TAG, "syncConfig: ${configEntities.size} configs saved to common.db")
                }
                commonDb.syncMetaDao().insertSync(SyncMetaEntity(SYNC_META_CONFIG_KEY, serverTime))
            }

            // 인메모리 캐시 갱신
            appConfig.updateConfigCache(configMap)
            Log.d(TAG, "syncConfig complete: serverTime=$serverTime, configs=${configEntities.size}")

            return true
        } catch (e: Exception) {
            Log.e(TAG, "syncConfig error: ${e.message}", e)
            return false
        }
    }

    // ── RefreshToken (REST) ──

    /** refreshToken 결과: 성공, 토큰 무효(로그인 필요), 네트워크 오류(재시도 가능) */
    enum class RefreshResult { SUCCESS, TOKEN_INVALID, NETWORK_ERROR }

    private suspend fun restRefreshToken(): RefreshResult {
        val rt = sessionManager.refreshToken ?: appConfig.getSavedRefreshToken()
        if (rt.isNullOrEmpty()) {
            Log.w(TAG, "restRefreshToken: no refreshToken available")
            return RefreshResult.TOKEN_INVALID
        }
        Log.d(TAG, "restRefreshToken() called")
        try {
            val baseUrl = appConfig.getRestBaseUrl()
            val authApi = ApiClient.createAuthApiFromBaseUrl(baseUrl)
            val endpoint = appConfig.getEndpoint(AppConfig.EP_AUTH_REFRESH, "api/auth/refresh")
            val request = RefreshTokenRequestDTO(refreshToken = rt)
            val response = authApi.refreshToken(endpoint, request)

            if (!response.isSuccessful) {
                val code = response.code()
                Log.e(TAG, "refreshToken HTTP error: $code")
                // 401/403 = 토큰 무효, 그 외 서버 오류는 재시도 가능
                return if (code == 401 || code == 403) RefreshResult.TOKEN_INVALID else RefreshResult.NETWORK_ERROR
            }

            val rawJson = response.body()?.string()?.takeIf { it.isNotEmpty() } ?: "{}"
            Log.d(TAG, "refreshToken response: $rawJson")
            val json = JSONObject(rawJson)
            val errorCode = json.optInt("errorCode", -1)

            if (errorCode != 0) {
                Log.e(TAG, "refreshToken failed: errorCode=$errorCode")
                return RefreshResult.TOKEN_INVALID
            }

            val newToken = json.optString("accessToken", "")
            val newRefresh = json.optString("refreshToken", rt)
            if (newToken.isNotEmpty()) {
                sessionManager.jwtToken = newToken
                sessionManager.refreshToken = newRefresh
                appConfig.saveTokens(newToken, newRefresh)
                Log.d(TAG, "refreshToken success - tokens updated")
                return RefreshResult.SUCCESS
            }
            return RefreshResult.TOKEN_INVALID
        } catch (e: java.net.ConnectException) {
            Log.e(TAG, "refreshToken network error (ConnectException): ${e.message}")
            return RefreshResult.NETWORK_ERROR
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "refreshToken network error (Timeout): ${e.message}")
            return RefreshResult.NETWORK_ERROR
        } catch (e: java.io.IOException) {
            Log.e(TAG, "refreshToken network error (IOException): ${e.message}")
            return RefreshResult.NETWORK_ERROR
        } catch (e: Exception) {
            // 예상치 못한 예외(SSL, JSON 파싱 등)는 네트워크 오류로 처리 → 재시도 허용
            Log.e(TAG, "refreshToken unexpected error: ${e.message}", e)
            return RefreshResult.NETWORK_ERROR
        }
    }

    // ── Logout ──

    fun logout() {
        val userId = sessionManager.pendingUserId ?: appConfig.getSavedUserId() ?: ""
        Log.d(TAG, "logout() called for user: $userId")
        CoroutineScope(Dispatchers.IO).launch {
            // REST logout (서버에서 refreshToken 폐기)
            val restSuccess = restLogout(userId)
            if (!restSuccess) {
                Log.w(TAG, "REST logout failed, clearing local tokens anyway")
            }

            // REST 성공/실패 무관하게 로컬 토큰 + 자격증명 삭제
            appConfig.clearTokens()
            appConfig.clearCredentials()
            appConfig.clearMyDeptId()
            appConfig.clearFrontendCache()
            Constants.myId = ""
            sessionManager.jwtToken = null
            sessionManager.refreshToken = null
            Log.d(TAG, "Logout: local tokens and credentials cleared")

            sessionManager.emitLoginStateSuspend(LoginState.Idle)
            sessionManager.disconnect()
        }
    }

    private suspend fun restLogout(userId: String): Boolean {
        return try {
            val baseUrl = appConfig.getRestBaseUrl()
            val token = sessionManager.jwtToken ?: appConfig.getSavedToken() ?: ""
            val endpoint = appConfig.getEndpoint(AppConfig.EP_AUTH_LOGOUT, "api/auth/logout")
            val request = mapOf("userId" to userId)
            // Authorization 헤더 포함하여 서버에서 세션 식별
            val retrofit = ApiClient.createRetrofitFromBaseUrl(baseUrl, token)
            val authedApi = retrofit.create(AuthApi::class.java)
            val response = authedApi.logout(endpoint, request)
            val rawJson = response.body()?.string()?.takeIf { it.isNotEmpty() } ?: "{}"
            Log.d(TAG, "REST logout response: $rawJson")
            val json = JSONObject(rawJson)
            json.optInt("errorCode", -1) == 0
        } catch (e: Exception) {
            Log.e(TAG, "REST logout error: ${e.message}", e)
            false
        }
    }

    fun disconnect() { sessionManager.disconnect() }

    fun disconnectSilently() { sessionManager.disconnectSilently() }

    fun isConnected() = sessionManager.isConnected()
}
