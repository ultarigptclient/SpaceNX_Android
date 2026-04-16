package net.spacenx.messenger.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import net.spacenx.messenger.data.repository.AuthRepository
import net.spacenx.messenger.data.repository.LoginState
import net.spacenx.messenger.data.repository.SocketSessionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 소켓 세션 및 인증 상태 관리 (앱 생명주기 동안 유지).
 * LoginViewModel이 직접 들고 있던 authRepo + sessionManager 역할 분리.
 */
@Singleton
class SessionService @Inject constructor(
    val authRepo: AuthRepository,
    val sessionManager: SocketSessionManager
) {

    companion object {
        private const val TAG = "SessionService"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 로그인 상태 — Eagerly started so new collectors get current value immediately */
    val loginState: StateFlow<LoginState> = authRepo.loginState
        .stateIn(scope, SharingStarted.Eagerly, LoginState.Idle)

    init {
        // 상태 변화 로깅 (디버그용)
        scope.run {
            // loginState는 위에서 구독 시작됨
            Log.d(TAG, "SessionService initialized")
        }
    }
}
