package net.spacenx.messenger.service.socket

import android.content.Context
import net.spacenx.messenger.data.repository.LoginState
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
import net.spacenx.messenger.data.remote.socket.quic.WebTransportQuicSocket
import org.json.JSONObject
import net.spacenx.messenger.util.FileLogger

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
        /**
         * 연속 재시도 상한. 도달 시 Disconnected/Failed 상태로 전환하고 재시도 중단.
         * 5+10+20+40+60*4 ≈ 6분 시도 후 포기. 사용자가 명시적 reconnect 시 다시 시작.
         */
        private const val MAX_RECONNECT_ATTEMPTS = 8
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
                config, l,
                noopBodyProvider = { buildNoopBody() }
            )
            Transport.QUIC -> {
                // minSdk 31 이상에서 WebTransport (Chromium/quiche) 단일 사용.
                // 기존 kwik 0.10.x fallback 경로는 2026-04-18 제거됨.
                Log.d(TAG, "QUIC: WebTransport via WebView (Chromium/quiche)")
                WebTransportQuicSocket(context, config, l, noopBodyProvider = { buildNoopBody() })
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

    /**
     * Deferred 의 reset/complete 는 모두 tokenLock 내부에서 실행해 race 방지.
     * - HI watchdog · NOOP 응답 · refresh 응답이 동시 처리될 때 stale Deferred 가
     *   다른 객체로 교체된 직후에 complete 되어 waiter 가 영구 대기하는 문제를 차단.
     */
    private fun completeJwtTokenDeferred(token: String?) {
        synchronized(tokenLock) {
            if (!jwtTokenDeferred.isCompleted) jwtTokenDeferred.complete(token)
        }
    }
    private fun resetJwtTokenDeferred() {
        synchronized(tokenLock) {
            jwtTokenDeferred = CompletableDeferred()
        }
    }
    private fun completeHiDeferred() {
        synchronized(tokenLock) {
            if (!hiCompletedDeferred.isCompleted) hiCompletedDeferred.complete(Unit)
        }
    }
    private fun completeHiDeferredExceptionally(e: Throwable) {
        synchronized(tokenLock) {
            if (!hiCompletedDeferred.isCompleted) hiCompletedDeferred.completeExceptionally(e)
        }
    }
    private fun resetHiDeferred() {
        synchronized(tokenLock) {
            hiCompletedDeferred = CompletableDeferred()
        }
    }

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

    /** 소켓으로 수신된 raw JSON 알림 프레임 콜백 (FCM 포맷과 동일한 구조) */
    var onRawSocketJson: ((JSONObject) -> Unit)? = null

    // 재접속 상태
    private val isReconnecting = AtomicBoolean(false)
    @Volatile private var reconnectDelaySec = 5
    @Volatile private var reconnectAttempts = 0
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
        resetJwtTokenDeferred()
        resetHiDeferred()
        isReconnecting.set(false)  // 진행 중인 재접속 플래그 초기화 (새 로그인으로 인한 상태 리셋)
        reconnectAttempts = 0
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
        reconnectAttempts = 0
        binarySocketClient = createSocketClient(config)
        CoroutineScope(Dispatchers.IO).launch {
            binarySocketClient!!.connect()
        }
    }

    suspend fun connectSuspend(config: ConnectionConfig) {
        lastConnectionConfig = config
        reconnectDelaySec = 5
        reconnectAttempts = 0
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
            put("accessToken", jwtToken ?: "")
            put("deviceType", "android")
            put("pushToken", appConfig.gmsToken)
            put("deviceId", Constants.getUUIDByMyId(context))
        }
        val hiBody = hiJson.toString().toByteArray(Charsets.UTF_8)
        Log.i(TAG, "[NeoHS 3/4] HI 전송 → userId=${pendingUserId}, hasToken=${jwtToken != null}, pushToken=${appConfig.gmsToken?.take(10)}...")
        FileLogger.log(TAG, "[NeoHS 3/4] HI 전송 → userId=${pendingUserId} hasToken=${jwtToken != null}")
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
            Log.i(TAG, "[NeoHS] HI response raw: $bodyStr")
            val json = JSONObject(bodyStr)
            val errorCode = json.optInt("errorCode", -1)

            if (errorCode == 0) {
                val newToken: String? = json.optString("accessToken", "")
                    .ifEmpty { json.optString("token", "") }
                    .ifEmpty { null }
                val newRefreshToken: String? = json.optString("refreshToken", "").ifEmpty { null }
                if (!newToken.isNullOrEmpty()) {
                    updateTokens(newToken, newRefreshToken)
                }
                completeJwtTokenDeferred(jwtToken)
                completeHiDeferred()
                Log.i(TAG, "[NeoHS 4/4] HI 성공 ✓ tokenRefreshed=${newToken != null}")
                FileLogger.log(TAG, "[NeoHS 4/4] HI 성공 ✓ tokenRefreshed=${newToken != null}")

                // LoggedIn 상태 발행
                val userJson = restLoginUserJson ?: JSONObject().apply {
                    put("errorCode", 0)
                    put("userId", pendingUserId ?: "")
                    put("userInfo", JSONObject())
                }.toString()
                val userName = pendingUserId ?: ""
                _loginState.tryEmit(LoginState.LoggedIn(userName, userJson, emptyMap()))

                // TIME_REQUEST 전송
                sendTimeRequest()

                // HI 완료 리스너 호출 (PubSubRepository 자동 재구독)
                hiCompletedListeners.forEach { it() }
            } else {
                val msg = json.optString("errorMessage", "HI 실패")
                Log.w(TAG, "[NeoHS 4/4] HI 실패 ✗ errorCode=$errorCode, msg=$msg")
                FileLogger.log(TAG, "[NeoHS 4/4] HI 실패 ✗ errorCode=$errorCode msg=$msg")

                // 토큰 만료로 HI 실패 시 → JWT만 클리어 (refreshToken은 REST 재인증용으로 보존)
                if (msg.contains("Invalid") || msg.contains("expired") || msg.contains("token", ignoreCase = true)) {
                    Log.w(TAG, "HI token rejected, clearing JWT (keeping refreshToken for REST re-auth)")
                    appConfig.clearJwt()
                    jwtToken = null
                    resetJwtTokenDeferred()
                    resetHiDeferred()
                    loginSessionScope?.cancel()
                    loginSessionScope = null
                    binarySocketClient?.disconnectSilently()
                    binarySocketClient = null
                    // 토큰 클리어 완료 → Idle 전환 (로그인 화면으로)
                    _loginState.tryEmit(LoginState.Idle)
                } else {
                    // 토큰 무관 HI 실패 (서버 오류 등) → deferred 즉시 실패 처리 + UI 알림
                    // hiCompletedDeferred.await() 대기 중인 코루틴이 무한 대기하지 않도록 exception으로 완료
                    completeHiDeferredExceptionally(Exception("HI failed: $msg"))
                    _loginState.tryEmit(LoginState.Failed("서버 오류: $msg"))
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
        if (!isReconnecting.compareAndSet(false, true)) return  // 이미 재접속 중이면 중복 진입 차단
        if (lastConnectionConfig == null) { isReconnecting.set(false); return }
        val scope = loginSessionScope ?: run { isReconnecting.set(false); return }  // 로그아웃/세션 종료 시 재접속 시도 안 함
        // Circuit breaker: 연속 N회 실패하면 재시도 중단. 사용자가 명시적 reconnect 호출 시 재개.
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "scheduleReconnect: max attempts ($MAX_RECONNECT_ATTEMPTS) reached, giving up")
            FileLogger.log(TAG, "Reconnect GIVE_UP after $reconnectAttempts attempts")
            isReconnecting.set(false)
            _loginState.tryEmit(LoginState.Failed("서버에 연결할 수 없습니다 ($MAX_RECONNECT_ATTEMPTS 회 시도 실패)"))
            return
        }
        reconnectAttempts++
        val delaySec = reconnectDelaySec
        // Jitter — 서버 재시작 시 thundering herd 방지 (0~2000ms 랜덤 추가)
        val jitterMs = (0..2000).random()
        val totalDelayMs = delaySec * 1000L + jitterMs

        Log.d(TAG, "scheduleReconnect in ${delaySec}s +${jitterMs}ms jitter...")
        scope.launch {
            delay(totalDelayMs)
            if (!isReconnecting.get()) return@launch

            try {
                Log.d(TAG, "Reconnecting...")
                binarySocketClient?.disconnectSilently()
                binarySocketClient = null
                delay(500) // 소켓 리소스 해제 대기
                binarySocketClient = createSocketClient(lastConnectionConfig!!, reconnectEventListener)
                binarySocketClient!!.connect()
            } catch (e: Exception) {
                Log.e(TAG, "Reconnect failed: ${e.message}")
                FileLogger.log(TAG, "Reconnect FAILED ${e.javaClass.simpleName}: ${e.message} → retry in ${(reconnectDelaySec * 2).coerceAtMost(60)}s")
                isReconnecting.set(false)
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
        val scope = loginSessionScope ?: run { onAuthFailed?.invoke(); return }
        scope.launch {
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
                    isReconnecting.set(false)
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
                            completeJwtTokenDeferred(jwtToken)
                            completeHiDeferred()

                            isReconnecting.set(false)
                            reconnectDelaySec = 5
                            reconnectAttempts = 0  // HI 성공 → 재시도 카운터 리셋
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
            isReconnecting.set(false)
            _loginState.tryEmit(LoginState.Disconnected)
            // 재접속 스케줄 (exponential backoff)
            reconnectDelaySec = (reconnectDelaySec * 2).coerceAtMost(60)
            scheduleReconnect()
        }

        override fun onError(error: Throwable) {
            Log.e(TAG, "Reconnect socket error: ${error.message}")
            isReconnecting.set(false)
            reconnectDelaySec = (reconnectDelaySec * 2).coerceAtMost(60)
            scheduleReconnect()
        }

        override fun onRawJsonFrame(json: String) {
            routeRawJsonFrame(json)
        }
    }

    fun cancelReconnect() {
        isReconnecting.set(false)
    }

    // ── neoSend: 바이너리 소켓으로 프레임 전송 + 응답 대기 ──

    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()

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
                FileLogger.log(TAG, "Binary frame recv: cmd=${command?.protocol ?: "0x${frame.commandCode.toString(16)}"} invokeId=${frame.invokeId}")
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
            FileLogger.log(TAG, "Binary socket DISCONNECTED")
            binarySocketClient = null
            loginSessionScope?.cancel()
            loginSessionScope = null
            _loginState.tryEmit(LoginState.Disconnected)
        }

        override fun onError(error: Throwable) {
            Log.e(TAG, "Binary socket error: ${error.message}")
            FileLogger.log(TAG, "Binary socket ERROR ${error.javaClass.simpleName}: ${error.message}")
            _loginState.tryEmit(LoginState.Failed("소켓 오류: ${error.message}"))
        }

        override fun onRawJsonFrame(json: String) {
            routeRawJsonFrame(json)
        }
    }

    /**
     * raw JSON 프레임 라우팅:
     * `command` 필드가 알려진 ProtocolCommand이면 frameHandlers로 직접 전달 (기존 push 처리 로직 재사용).
     * 알 수 없는 command이면 onRawSocketJson으로 fallback.
     */
    private fun routeRawJsonFrame(json: String) {
        try {
            val obj = JSONObject(json)
            val commandName = obj.optString("command", "")
            val cmd = if (commandName.isNotEmpty()) ProtocolCommand.fromName(commandName) else null
            if (cmd != null) {
                val frame = BinaryFrameCodec.BinaryFrame(cmd.code, 0, json.toByteArray(Charsets.UTF_8))
                frameHandlers[cmd.code]?.invoke(frame) ?: Log.d(TAG, "No handler for raw JSON command: $commandName")
            } else {
                onRawSocketJson?.invoke(obj)
            }
        } catch (_: Exception) {}
    }
}
