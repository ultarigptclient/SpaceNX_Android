package net.spacenx.messenger.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.spacenx.messenger.data.repository.AuthRepository
import net.spacenx.messenger.data.repository.BuddyRepository
import net.spacenx.messenger.data.repository.ChannelRepository
import net.spacenx.messenger.data.repository.LoginState
import net.spacenx.messenger.data.repository.MessageRepository
import net.spacenx.messenger.data.repository.NotiRepository
import net.spacenx.messenger.data.repository.OrgRepository
import net.spacenx.messenger.data.repository.ProjectRepository
import net.spacenx.messenger.data.repository.PubSubRepository
import net.spacenx.messenger.data.repository.StatusRepository
import net.spacenx.messenger.data.cache.UserNameCache
import net.spacenx.messenger.service.push.PushEventHandler
import net.spacenx.messenger.service.socket.SocketSessionManager
import net.spacenx.messenger.service.SessionService
import net.spacenx.messenger.service.SyncService
import org.json.JSONObject

/**
 * 로그인/세션/동기화 ViewModel.
 * 비즈니스 로직은 SessionService(세션) + SyncService(동기화)로 위임.
 * BridgeDispatcher 하위 호환을 위해 repo 프로퍼티를 그대로 노출.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    application: Application,
    private val sessionService: SessionService,
    val syncService: SyncService,
    // BridgeDispatcher가 직접 접근하는 repo들 — 싱글톤이므로 동일 인스턴스
    val authRepo: AuthRepository,
    val buddyRepo: BuddyRepository,
    val channelRepo: ChannelRepository,
    val orgRepo: OrgRepository,
    val pubSubRepo: PubSubRepository,
    val statusRepo: StatusRepository,
    val messageRepo: MessageRepository,
    val notiRepo: NotiRepository,
    val userNameCache: UserNameCache,
    val pushEventHandler: PushEventHandler,
    val projectRepo: ProjectRepository
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "LoginViewModel"
    }

    // ── BridgeDispatcher 하위 호환 프로퍼티 ──
    /** sessionManager — handlers에서 jwtToken 접근에 사용 */
    val sessionManager: SocketSessionManager get() = sessionService.sessionManager

    // ── 상태 노출 ──
    val loginState: StateFlow<LoginState> = sessionService.loginState
    val subscribeResponse = pubSubRepo.subscribeResponse

    // SyncService deferred 위임 (BridgeDispatcher가 await)
    val syncBuddyDeferred get() = syncService.syncBuddyDeferred
    val syncChannelDeferred get() = syncService.syncChannelDeferred
    val syncChatDeferred get() = syncService.syncChatDeferred

    /** OrgHandler에서 중복 syncBuddy 방지용 — 마지막 초기 sync 완료 시각 */
    val lastBuddySyncMs: Long get() = syncService.lastBuddySyncMs

    private var loginJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            authRepo.loadInitialConfigCache()
        }
    }

    // ── 인증 ──

    fun login(username: String, password: String) {
        Log.d(TAG, "login() called: $username")
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            authRepo.login(username, password)
        }
    }

    fun reconnect(userId: String) {
        Log.d(TAG, "reconnect() called: $userId")
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            authRepo.reconnect(userId)
        }
    }

    fun logout() {
        Log.d(TAG, "logout() called")
        syncService.cancelAllSync()
        authRepo.logout()
    }

    fun disconnect() {
        authRepo.disconnect()
    }

    fun disconnectSilently() {
        loginJob?.cancel()
        loginJob = null
        pubSubRepo.prepareForReconnect()
        authRepo.disconnectSilently()
    }

    /** onDestroy 시 소켓 끊기 */
    fun disconnectSocket() {
        authRepo.disconnect()
    }

    fun isConnected(): Boolean = authRepo.isConnected() || (loginJob?.isActive == true)

    fun unsubscribeAll() {
        pubSubRepo.unsubscribeAll()
    }

    // ── 동기화 (SyncService 위임) ──

    fun syncOrgAndBuddy(userId: String) {
        Log.d(TAG, "syncOrgAndBuddy() called: userId=$userId")
        syncService.syncOrgAndBuddy(userId)
    }

    fun startBackgroundSync(
        notifyCallback: (String) -> Unit,
        pushCallback: ((String, JSONObject) -> Unit)? = null,
        syncStatusCallback: ((String, String, Int?, Int?) -> Unit)? = null,
        projectRepo: ProjectRepository? = null
    ) {
        syncService.startBackgroundSync(notifyCallback, pushCallback, syncStatusCallback, projectRepo)
    }

    fun syncBuddy(userId: String) {
        Log.d(TAG, "syncBuddy() called: userId=$userId")
        syncService.syncBuddy(userId)
    }

    /** 포그라운드 복귀 시 소켓 alive 판정이어도 경량 delta 재동기화 (push 유실 보완) */
    fun resyncDeltaOnly(userId: String, notifyCallback: (String) -> Unit, projectRepo: ProjectRepository? = null) {
        syncService.resyncDeltaOnly(userId, notifyCallback, projectRepo)
    }

    /** onDestroy 시 백그라운드 sync 취소 */
    fun cancelAllSync() {
        loginJob?.cancel()
        loginJob = null
        syncService.cancelAllSync()
    }

    override fun onCleared() {
        super.onCleared()
        authRepo.disconnect()
    }
}
