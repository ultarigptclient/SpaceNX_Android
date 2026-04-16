package net.spacenx.messenger.data.repository

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.common.Constants
import net.spacenx.messenger.data.remote.api.ApiClient
import net.spacenx.messenger.data.remote.socket.BinarySocketClient
import net.spacenx.messenger.data.remote.socket.BinarySocketEventListener
import net.spacenx.messenger.data.remote.socket.ConnectionConfig
import net.spacenx.messenger.data.remote.socket.NeoSocketBase
import net.spacenx.messenger.data.remote.socket.Transport
import net.spacenx.messenger.data.remote.socket.codec.BinaryFrameCodec
import net.spacenx.messenger.data.remote.socket.codec.ProtocolCommand
import net.spacenx.messenger.data.remote.socket.quic.QuicSocketClient
import net.spacenx.messenger.data.remote.socket.quic.WebTransportQuicSocket
import org.json.JSONObject

/**
 * 소켓 연결/해제, HI, Logout, RefreshToken, TIME_REQUEST, NOOP 토큰 교환, 프레임 라우팅 담당
 */
class SocketSessionManager(
    private val context: Context,
    private val appConfig: AppConfig
) {
    companion object {
        private const val TAG = "SocketSessionManager"
        /** HI 응답 대기 한도 — 서버가 FIN 없이 drop 해도 waiters 무한 대기 방지 */
        private const val HI_TIMEOUT_MS = 30_000L
    }

    // 소켓 — 전송(TCP/QUIC) 무관. ConnectionConfig.transport 로 구체 구현 선택
    private var binarySocketClient: NeoSocketBase? = null

    /**
     * 전송 프로토콜 팩토리.
     * - TCP  : [BinarySocketClient] (기본)
     * - QUIC : [WebTransportQuicSocket] (API 31+, Chromium/quiche → RETRY 처리 완벽)
     *          API 30 이하는 [QuicSocketClient] (kwik) 폴백 — RETRY 실패 가능
     */
    private fun createSocketClient(
        config: ConnectionConfig,
        listenerOverride: BinarySocketEventListener? = null
    ): NeoSocketBase {
        val l = listenerOverride ?: binarySocketEventListener
        return when (config.transport) {
            Transport.TCP -> BinarySocketClient(
                context, config, l,
                noopBodyProvider = { buildNoopBody() }
            )
            Transport.QUIC -> if (WebTransportQuicSocket.isSupported) {
                Log.d(TAG, "QUIC: WebTransport via WebView (Chromium/quiche)")
                WebTransportQuicSocket(context, config, l, noopBodyProvider = { buildNoopBody() })
            } else {
                Log.w(TAG, "QUIC: kwik fallback (API < 31, RETRY may fail)")
                QuicSocketClient(context, config, l, noopBodyProvider = { buildNoopBody() })
            }
        }
    }

    // JWT 토큰 — 동시 갱신(예: 401 두 개 병렬 firing + NOOP 응답 + HI 응답) 시 interleave 방지용 lock.
    private val tokenLock = Any()

    @Volatile
    var jwtToken: String? = null
        internal set
    @Volatile
    var refreshToken: String? = null
        internal set

    /**
     * 토큰 atomic 업데이트 + EncryptedSharedPreferences 저장.
     * 병렬 refresh (예: HI 응답 + NOOP 응답이 동시 처리) 에서 일관성 보장.
     *
     * @param access 새 accessToken (필수, 빈 문자열이면 무시)
     * @param refresh 새 refreshToken (null/빈 문자열이면 기존 값 유지)
     */
    private fun updateTokens(access: String, refresh: String? = null) {
        if (access.isEmpty()) return
        synchronized(tokenLock) {
            jwtToken = access
            if (!refresh.isNullOrEmpty()) refreshToken = refresh
            appConfig.saveTokens(access, refreshToken ?: "")
        }
    }
    var jwtTokenDeferred = CompletableDeferred<String?>()
        private set
    var hiCompletedDeferred = CompletableDeferred<Unit>()
        private set

    // 세션 스코프 (소켓 끊김 시 취소 → PubSubRepository의 대기 코루틴 정리)
    var loginSessionScope: CoroutineScope? = null
        private set

    // 상태
    private val _loginState = MutableSharedFlow<LoginState>(replay = 1, extraBufferCapacity = 10)
    val loginState: Flow<LoginState> = _loginState


    // 프레임 핸들러 (PubSubRepository 등이 등록)
    private val frameHandlers = mutableMapOf<Int, (BinaryFrameCodec.BinaryFrame) -> Unit>()

    // HI 완료 리스너 (PubSubRepository 자동 재구독)
    private val hiCompletedListeners = mutableListOf<() -> Unit>()

    // NHM-68: 재접속 콜백
    var onReconnected: (() -> Unit)? = null
    var onAuthFailed: (() -> Unit)? = null

    // 재접속 상태
    @Volatile
    private var isReconnecting = false
    private var reconnectDelaySec = 5
    private var lastConnectionConfig: ConnectionConfig? = null

    // 로그인 시 사용한 자격증명
    var pendingUserId: String? = null
        internal set
    var pendingPassword: String? = null
        internal set
    // REST 로그인 응답에서 받은 사용자 정보 (HI 응답 시 LoggedIn에 포함)
    var restLoginUserJson: String? = null
        internal set

    fun registerFrameHandler(commandCode: Int, handler: (BinaryFrameCodec.BinaryFrame) -> Unit) {
        frameHandlers[commandCode] = handler
    }

    fun onHICompleted(listener: () -> Unit) {
        hiCompletedListeners.add(listener)
    }

    fun resetForNewLogin() {
        jwtToken = null
        refreshToken = null
        restLoginUserJson = null
        jwtTokenDeferred = CompletableDeferred()
        hiCompletedDeferred = CompletableDeferred()
        loginSessionScope?.cancel()
        loginSessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // stale Retrofit 캐시 정리
        ApiClient.clearRetrofitCache()

        // 이전 소켓 정리
        binarySocketClient?.disconnectSilently()
        binarySocketClient = null
    }

    fun emitLoginState(state: LoginState) {
        _loginState.tryEmit(state)
    }

    suspend fun emitLoginStateSuspend(state: LoginState) {
        _loginState.emit(state)
    }

    fun connect(config: ConnectionConfig) {
        lastConnectionConfig = config
        reconnectDelaySec = 5
        binarySocketClient = createSocketClient(config)
        CoroutineScope(Dispatchers.IO).launch {
            binarySocketClient!!.connect()
        }
    }

    suspend fun connectSuspend(config: ConnectionConfig) {
        lastConnectionConfig = config
        reconnectDelaySec = 5
        binarySocketClient = createSocketClient(config)
        binarySocketClient!!.connect()
    }

    fun disconnect() {
        loginSessionScope?.cancel()
        loginSessionScope = null
        binarySocketClient?.disconnect()
        binarySocketClient = null
    }

    fun disconnectSilently() {
        Log.d(TAG, "disconnectSilently() called")
        loginSessionScope?.cancel()
        loginSessionScope = null
        binarySocketClient?.disconnectSilently()
        binarySocketClient = null
        _loginState.tryEmit(LoginState.Disconnected)
    }

    fun isConnected(): Boolean = binarySocketClient != null

    fun sendFrame(commandCode: Int, body: ByteArray) {
        binarySocketClient?.sendFrame(commandCode, body)
    }

    // ── NOOP body (accessToken 포함) ──

    private fun buildNoopBody(): ByteArray {
        val json = JSONObject().apply {
            put("accessToken", jwtToken ?: "")
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    // ── Binary HI ──

    private fun sendHI() {
        val hiJson = JSONObject().apply {
            put("userId", pendingUserId)
            put("token", jwtToken ?: "")
            put("deviceType", "android")
            put("pushToken", appConfig.gmsToken)
            put("deviceId", Constants.getUUIDByMyId(context))
        }
        val hiBody = hiJson.toString().toByteArray(Charsets.UTF_8)
        Log.d(TAG, "Sending Binary HI frame: $hiJson")
        binarySocketClient?.sendFrame(ProtocolCommand.HI.code, hiBody)

        // HI watchdog — 서버가 TCP FIN 없이 drop 하는 경우 hiCompletedDeferred 가 영원히 pending 상태로 남는 걸 방지.
        // PubSubRepository 등 HI 대기 중인 코루틴은 TimeoutCancellationException 받고 abort.
        val watchDeferred = hiCompletedDeferred
        loginSessionScope?.launch {
            delay(HI_TIMEOUT_MS)
            if (!watchDeferred.isCompleted) {
                Log.w(TAG, "HI response timeout after ${HI_TIMEOUT_MS}ms — completing exceptionally")
                watchDeferred.completeExceptionally(
                    java.io.IOException("HI response timeout (${HI_TIMEOUT_MS}ms)")
                )
            }
        }
    }

    private fun handleHIResponse(frame: BinaryFrameCodec.BinaryFrame) {
        try {
            val bodyStr = frame.bodyAsString()
            Log.d(TAG, "Binary HI response raw: $bodyStr")
            val json = JSONObject(bodyStr)
            val errorCode = json.optInt("errorCode", -1)

            if (errorCode == 0) {
                val userInfoObj = json.optJSONObject("userInfo")
                val nick = json.optString("nick", "")

                // HI 응답에서 갱신된 토큰 저장
                // optString 으로 필드 누락·JSON null·빈 문자열 모두 "" 처리 (JSONException 방지)
                val newToken: String? = json.optString("accessToken", "")
                    .ifEmpty { json.optString("token", "") }
                    .ifEmpty { null }
                val newRefreshToken: String? = json.optString("refreshToken", "").ifEmpty { null }
                if (!newToken.isNullOrEmpty()) {
                    updateTokens(newToken, newRefreshToken)
                    Log.d(TAG, "HI response - tokens updated in EncryptedSharedPreferences")
                }
                if (!jwtTokenDeferred.isCompleted) jwtTokenDeferred.complete(jwtToken)
                if (!hiCompletedDeferred.isCompleted) hiCompletedDeferred.complete(Unit)
                Log.d(TAG, "HI success - userInfo=$userInfoObj, nick=$nick, jwt=${jwtToken != null}")

                // LoggedIn 상태 발행
                val userJson = restLoginUserJson ?: JSONObject().apply {
                    put("errorCode", 0)
                    put("userId", userInfoObj?.optString("userId", pendingUserId ?: "") ?: pendingUserId ?: "")
                    put("userInfo", userInfoObj ?: JSONObject())
                }.toString()
                val userName = pendingUserId ?: userInfoObj?.optString("userName", "") ?: ""
                _loginState.tryEmit(LoginState.LoggedIn(userName, userJson, emptyMap()))

                // TIME_REQUEST 전송
                sendTimeRequest()

                // HI 완료 리스너 호출 (PubSubRepository 자동 재구독)
                hiCompletedListeners.forEach { it() }
            } else {
                val msg = json.optString("errorMessage", "HI 실패")
                Log.w(TAG, "HI failed: $msg")

                // 토큰 만료로 HI 실패 시 → JWT만 클리어 (refreshToken은 REST 재인증용으로 보존)
                if (msg.contains("Invalid") || msg.contains("expired") || msg.contains("token", ignoreCase = true)) {
                    Log.w(TAG, "HI token rejected, clearing JWT (keeping refreshToken for REST re-auth)")
                    appConfig.clearJwt()
                    jwtToken = null
                    jwtTokenDeferred = CompletableDeferred()
                    hiCompletedDeferred = CompletableDeferred()
                    loginSessionScope?.cancel()
                    loginSessionScope = null
                    binarySocketClient?.disconnectSilently()
                    binarySocketClient = null
                    // 토큰 클리어 완료 → Idle 전환 (로그인 화면으로)
                    _loginState.tryEmit(LoginState.Idle)
                } else {
                    // 토큰 무관 HI 실패 (서버 오류 등) → deferred 즉시 실패 처리
                    // hiCompletedDeferred.await() 대기 중인 코루틴이 무한 대기하지 않도록 exception으로 완료
                    if (!hiCompletedDeferred.isCompleted) {
                        hiCompletedDeferred.completeExceptionally(Exception("HI failed: $msg"))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse HI response: ${e.message}")
        }
    }

    // ── Binary TIME_REQUEST ──

    private fun sendTimeRequest() {
        Log.d(TAG, "Sending Binary TIME_REQUEST frame")
        binarySocketClient?.sendFrame(ProtocolCommand.TIME_REQUEST.code, ByteArray(0))
    }

    private fun handleTimeResponse(frame: BinaryFrameCodec.BinaryFrame) {
        try {
            val json = JSONObject(frame.bodyAsString())
            val errorCode = json.optInt("errorCode", -1)
            val nowTime = json.optString("nowTime", "")
            Log.d(TAG, "Binary TIME_REQUEST response: errorCode=$errorCode, nowTime=$nowTime")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse TIME_REQUEST response: ${e.message}")
        }
    }

    // ── Binary RefreshToken ──

    fun sendRefreshToken() {
        val rt = refreshToken ?: appConfig.getSavedRefreshToken()
        if (rt.isNullOrEmpty()) {
            Log.w(TAG, "sendRefreshToken() - no refreshToken available")
            return
        }
        val json = JSONObject().apply {
            put("refreshToken", rt)
        }
        val body = json.toString().toByteArray(Charsets.UTF_8)
        Log.d(TAG, "Sending Binary RefreshToken frame: $json")
        binarySocketClient?.sendFrame(ProtocolCommand.REFRESH_TOKEN.code, body)
    }

    private fun handleRefreshTokenResponse(frame: BinaryFrameCodec.BinaryFrame) {
        try {
            val bodyStr = frame.bodyAsString()
            Log.d(TAG, "RefreshToken response: $bodyStr")
            val json = JSONObject(bodyStr)
            val errorCode = json.optInt("errorCode", -1)
            if (errorCode == 0) {
                val newToken = json.optString("token", "")
                val newRefresh = json.optString("refreshToken", "")
                if (newToken.isNotEmpty()) {
                    updateTokens(newToken, newRefresh)
                    Log.d(TAG, "RefreshToken success - tokens updated")
                }
            } else {
                val msg = json.optString("errorMessage", "토큰 갱신 실패")
                Log.w(TAG, "RefreshToken failed: $msg")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse RefreshToken response: ${e.message}")
        }
    }

    // ── NOOP 응답 처리 (서버에서 새 accessToken 전달) ──

    private fun handleNoopResponse(frame: BinaryFrameCodec.BinaryFrame) {
        try {
            val bodyStr = frame.bodyAsString()
            if (bodyStr.isEmpty()) {
                Log.d(TAG, "NOOP response received (empty body)")
                return
            }
            val json = JSONObject(bodyStr)
            val newToken = json.optString("accessToken", "")
            if (newToken.isNotEmpty() && newToken != jwtToken) {
                updateTokens(newToken)  // refresh 유지
                Log.d(TAG, "NOOP response - accessToken updated")
            } else {
                Log.d(TAG, "NOOP response received (no token change)")
            }
        } catch (e: Exception) {
            Log.d(TAG, "NOOP response received (non-JSON body)")
        }
    }

    // ── NHM-68: 재접속 인증 복구 ──

    /**
     * 소켓 끊김 → 재접속 → HI
     * HI 실패 → REST refreshToken → 재접속 → HI
     * HI 성공 → onReconnected (delta sync)
     */
    fun scheduleReconnect() {
        if (isReconnecting || lastConnectionConfig == null) return
        isReconnecting = true
        val delaySec = reconnectDelaySec
        // Jitter — 서버 재시작 시 thundering herd 방지 (0~2000ms 랜덤 추가)
        val jitterMs = (0..2000).random()
        val totalDelayMs = delaySec * 1000L + jitterMs

        Log.d(TAG, "scheduleReconnect in ${delaySec}s +${jitterMs}ms jitter...")
        CoroutineScope(Dispatchers.IO).launch {
            delay(totalDelayMs)
            if (!isReconnecting) return@launch

            try {
                Log.d(TAG, "Reconnecting...")
                binarySocketClient?.disconnectSilently()
                binarySocketClient = null
                delay(500) // 소켓 리소스 해제 대기
                binarySocketClient = createSocketClient(lastConnectionConfig!!, reconnectEventListener)
                binarySocketClient!!.connect()
            } catch (e: Exception) {
                Log.e(TAG, "Reconnect failed: ${e.message}")
                isReconnecting = false
                reconnectDelaySec = (reconnectDelaySec * 2).coerceAtMost(60)
                scheduleReconnect()
            }
        }
    }

    /**
     * 재접속 HI 인증 실패 → REST refreshToken 갱신 → 재접속
     */
    private fun handleReconnectAuthFailed() {
        Log.d(TAG, "Reconnect auth failed — trying REST token refresh")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val rt = refreshToken ?: appConfig.getSavedRefreshToken()
                if (rt.isNullOrEmpty()) {
                    Log.w(TAG, "No refreshToken — forcing logout")
                    onAuthFailed?.invoke()
                    return@launch
                }
                val result = ApiClient.postJson(
                    appConfig.getEndpointByPath("/auth/refresh"),
                    JSONObject().put("refreshToken", rt)
                )
                val errorCode = result.optInt("errorCode", -1)
                if (errorCode != 0) {
                    Log.w(TAG, "REST token refresh failed: $result — forcing logout")
                    onAuthFailed?.invoke()
                    return@launch
                }
                val newAccess = result.optString("accessToken", "")
                val newRefresh = result.optString("refreshToken", rt)
                if (newAccess.isNotEmpty()) {
                    updateTokens(newAccess, newRefresh)
                    Log.d(TAG, "REST token refresh OK — reconnecting bridge")
                    isReconnecting = false
                    reconnectDelaySec = 5
                    scheduleReconnect()
                } else {
                    onAuthFailed?.invoke()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reconnect auth recovery failed: ${e.message}")
                onAuthFailed?.invoke()
            }
        }
    }

    /** 재접속용 이벤트 리스너 — HI 성공 시 onReconnected, 실패 시 handleReconnectAuthFailed */
    private val reconnectEventListener = object : BinarySocketEventListener {
        override fun onConnected() {
            Log.d(TAG, "Reconnect socket connected — sending HI")
            _loginState.tryEmit(LoginState.Connected)
            sendHI()
        }

        override fun onFrameReceived(frame: BinaryFrameCodec.BinaryFrame) {
            val command = ProtocolCommand.fromCode(frame.commandCode)

            when (command) {
                ProtocolCommand.HI -> {
                    try {
                        val json = JSONObject(frame.bodyAsString())
                        val errorCode = json.optInt("errorCode", -1)
                        if (errorCode == 0) {
                            // 토큰 갱신
                            val newToken = json.optString("accessToken", json.optString("token", ""))
                            val newRefresh = json.optString("refreshToken", "")
                            if (newToken.isNotEmpty()) {
                                updateTokens(newToken, newRefresh)
                            }
                            if (!jwtTokenDeferred.isCompleted) jwtTokenDeferred.complete(jwtToken)
                            if (!hiCompletedDeferred.isCompleted) hiCompletedDeferred.complete(Unit)

                            isReconnecting = false
                            reconnectDelaySec = 5
                            Log.d(TAG, "Reconnect HI OK → triggering delta sync")
                            hiCompletedListeners.forEach { it() }
                            onReconnected?.invoke()
                        } else {
                            Log.w(TAG, "Reconnect HI failed: ${json.optString("errorMessage")}")
                            handleReconnectAuthFailed()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Reconnect HI parse error: ${e.message}")
                        handleReconnectAuthFailed()
                    }
                    return
                }
                ProtocolCommand.NOOP -> { handleNoopResponse(frame); return }
                ProtocolCommand.REFRESH_TOKEN -> { handleRefreshTokenResponse(frame); return }
                ProtocolCommand.TIME_REQUEST -> { handleTimeResponse(frame); return }
                else -> {}
            }

            // 외부 핸들러 (push 이벤트 등)
            frameHandlers[frame.commandCode]?.invoke(frame)
        }

        override fun onDisconnected() {
            Log.d(TAG, "Reconnect socket disconnected")
            binarySocketClient = null
            isReconnecting = false
            _loginState.tryEmit(LoginState.Disconnected)
            // 재접속 스케줄 (exponential backoff)
            reconnectDelaySec = (reconnectDelaySec * 2).coerceAtMost(60)
            scheduleReconnect()
        }

        override fun onError(error: Throwable) {
            Log.e(TAG, "Reconnect socket error: ${error.message}")
            isReconnecting = false
            reconnectDelaySec = (reconnectDelaySec * 2).coerceAtMost(60)
            scheduleReconnect()
        }
    }

    fun cancelReconnect() {
        isReconnecting = false
    }

    // ── neoSend: 바이너리 소켓으로 프레임 전송 + 응답 대기 ──

    private var nextInvokeId = 1
    private val pendingRequests = mutableMapOf<Int, CompletableDeferred<JSONObject>>()

    /**
     * NHM-68: WS 기반 neoSend — command name으로 프레임 전송, invokeId로 응답 매칭
     * @return 서버 응답 JSONObject
     */
    suspend fun sendCommand(commandName: String, body: JSONObject): JSONObject {
        val cmd = ProtocolCommand.fromName(commandName)
            ?: throw IllegalArgumentException("Unknown command: $commandName")
        return sendCommand(cmd.code, body)
    }

    suspend fun sendCommand(commandCode: Int, body: JSONObject): JSONObject {
        val client = binarySocketClient ?: throw IllegalStateException("Not connected")
        val deferred = CompletableDeferred<JSONObject>()
        val bodyBytes = body.toString().toByteArray(Charsets.UTF_8)

        // sendFrame returns the invokeId used
        val invokeId = client.sendFrame(commandCode, bodyBytes)
        pendingRequests[invokeId] = deferred

        // 15초 타임아웃
        return kotlinx.coroutines.withTimeoutOrNull(15_000L) { deferred.await() }
            ?: run {
                pendingRequests.remove(invokeId)
                throw Exception("Timeout: ${ProtocolCommand.fromCode(commandCode)?.protocol ?: "0x${commandCode.toString(16)}"}")
            }
    }

    /** 응답 프레임 매칭 (invokeId > 0) */
    internal fun matchPendingResponse(invokeId: Int, body: JSONObject): Boolean {
        val deferred = pendingRequests.remove(invokeId) ?: return false
        if (!deferred.isCompleted) deferred.complete(body)
        return true
    }

    // ── BinarySocketEventListener ──

    private val binarySocketEventListener = object : BinarySocketEventListener {

        override fun onConnected() {
            Log.d(TAG, "Binary socket connected")
            _loginState.tryEmit(LoginState.Connected)

            if (jwtToken != null) {
                Log.d(TAG, "REST login token exists, sending HI directly")
                sendHI()
                return
            }

            // jwtToken이 없으면 HI 전송 불가 — AuthRepository에서 처리
            Log.w(TAG, "onConnected but no jwtToken — waiting for token")
        }

        override fun onFrameReceived(frame: BinaryFrameCodec.BinaryFrame) {
            val command = ProtocolCommand.fromCode(frame.commandCode)
            if (command != ProtocolCommand.NOOP) {
                Log.d(TAG, "Binary frame received: cmd=${command?.protocol ?: "0x${frame.commandCode.toString(16)}"}, invokeId=${frame.invokeId}")
            }

            // 내부 핸들러
            when (command) {
                ProtocolCommand.HI -> { handleHIResponse(frame); return }
                ProtocolCommand.TIME_REQUEST -> { handleTimeResponse(frame); return }
                ProtocolCommand.REFRESH_TOKEN -> { handleRefreshTokenResponse(frame); return }
                ProtocolCommand.NOOP -> { handleNoopResponse(frame); return }
                else -> { /* fall through */ }
            }

            // invokeId > 0이면 request-response 매칭 시도
            if (frame.invokeId > 0) {
                try {
                    val json = JSONObject(frame.bodyAsString())
                    if (matchPendingResponse(frame.invokeId, json)) return
                } catch (_: Exception) {}
            }

            // 외부 등록 핸들러 (push 이벤트 등)
            frameHandlers[frame.commandCode]?.invoke(frame)
                ?: Log.d(TAG, "Unhandled binary command: 0x${frame.commandCode.toString(16)}")
        }

        override fun onDisconnected() {
            Log.d(TAG, "Binary socket disconnected")
            binarySocketClient = null
            loginSessionScope?.cancel()
            loginSessionScope = null
            _loginState.tryEmit(LoginState.Disconnected)
        }

        override fun onError(error: Throwable) {
            Log.e(TAG, "Binary socket error: ${error.message}")
            _loginState.tryEmit(LoginState.Failed("소켓 오류: ${error.message}"))
        }
    }
}
