package net.spacenx.messenger.ui

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.common.Constants
import net.spacenx.messenger.common.JsEscapeUtil
import net.spacenx.messenger.data.local.DatabaseProvider
import net.spacenx.messenger.data.repository.LoginState
import net.spacenx.messenger.ui.bridge.BridgeDispatcher
import net.spacenx.messenger.ui.viewmodel.LoginViewModel
import net.spacenx.messenger.ui.viewmodel.MainViewModel

/**
 * LoginState 흐름 처리 담당.
 * 원래 MainActivity.handleLoginState() 에 있던 코드를 분리.
 */
class LoginStateCoordinator(
    private val activity: AppCompatActivity,
    private val webView: WebView,
    private val bridgeDispatcher: BridgeDispatcher,
    private val loginViewModel: LoginViewModel,
    private val appConfig: AppConfig,
    private val databaseProvider: DatabaseProvider,
    private val mainViewModel: MainViewModel,
    private val scope: CoroutineScope,
    /** LoggedIn 상태 진입 시 호출 — PushEventRouter.register() */
    private val onLoggedIn: () -> Unit,
    /** cold-start 딥링크 intent 존재 여부 확인 */
    private val hasPendingDeepLink: () -> Boolean,
    /** cold-start 딥링크 소비 */
    private val onConsumeDeepLink: () -> Unit,
    /** 다음 페이지 로드 시 localStorage 클리어 플래그 설정 */
    private val setClearAuthOnNextLoad: (Boolean) -> Unit
) {
    companion object {
        private const val TAG = "LoginStateCoordinator"
    }

    fun handle(state: LoginState) {
        Log.d(TAG, "handle: $state")
        when (state) {
            is LoginState.Authenticated -> handleAuthenticated(state)
            is LoginState.LoggedIn     -> handleLoggedIn()
            is LoginState.Failed       -> handleFailed(state)
            is LoginState.Disconnected -> handleDisconnected()
            is LoginState.Idle         -> handleIdle()
            is LoginState.SkinChanged  -> handleSkinChanged(state)
            else -> { /* Connecting, Connected — 처리 불필요 */ }
        }
    }

    // ── Authenticated: REST 인증 성공 ──

    private fun handleAuthenticated(state: LoginState.Authenticated) {
        mainViewModel.isLoggedIn = true
        mainViewModel.setLoginCompleted()

        // 개인정보 동의 플래그 설정
        activity.getSharedPreferences("talkConfig", Context.MODE_PRIVATE)
            .edit().putBoolean("ISPERSONAL", true).apply()

        val userId = Constants.myId.ifEmpty { appConfig.getSavedUserId() ?: "" }
        databaseProvider.initForUser(userId)
        loginViewModel.syncOrgAndBuddy(userId, useCache = mainViewModel.isForegroundResume)

        // FRONTEND_SKIN/VERSION 변경 감지 (sync 완료 후 URL 전환)
        val pendingSpaUrl = appConfig.getSpaUrl()
        val currentUrl = webView.url ?: ""
        val needSkinReload = !mainViewModel.isForegroundResume
            && pendingSpaUrl != currentUrl
            && !currentUrl.startsWith(appConfig.getSpaBaseUrl())

        loginViewModel.startBackgroundSync(
            notifyCallback = { event ->
                bridgeDispatcher.notifyReact(event)
                val required = setOf("orgReady", "buddyReady", "channelReady", "messageReady", "notiReady")
                if (needSkinReload && bridgeDispatcher.completedSyncs.containsAll(required)) {
                    Log.d(TAG, "FRONTEND_SKIN/VERSION changed after sync: $currentUrl → $pendingSpaUrl")
                    activity.runOnUiThread { webView.loadUrl(pendingSpaUrl) }
                }
            },
            pushCallback = { cmd, data -> bridgeDispatcher.forwardPushToReact(cmd, data) },
            projectRepo = bridgeDispatcher.projectRepo
        )

        // JS resolve — Flutter 패턴: socket 연결 전에 즉시 응답
        when {
            mainViewModel.isForegroundResume -> {
                Log.d(TAG, "Foreground resume, skipping resolve")
                mainViewModel.isForegroundResume = false
            }
            mainViewModel.isAutoLogin -> {
                val authJson = JsEscapeUtil.escapeForJs(state.userJson)
                mainViewModel.pendingAuthJson = authJson
                Log.d(TAG, "Authenticated: pendingAuthJson saved, will resolve on waitAutoLogin")
                webView.evaluateJavascript(
                    "(function(){var fn=window._waitAutoLoginResolve||window._autoLoginResolve;" +
                    "if(fn){fn('$authJson');window.__authResolved=true;}})();",
                    null
                )
            }
            else -> {
                Log.d(TAG, "→ _loginResolve (Authenticated)")
                webView.evaluateJavascript("window._loginResolve(${state.userJson})", null)
            }
        }

        webView.evaluateJavascript(
            "localStorage.setItem('isLoggedIn','true');" +
            "localStorage.setItem('currentUser','${state.userJson.replace("'", "\\'")}');",
            null
        )
    }

    // ── LoggedIn: Socket HI 완료 ──

    private fun handleLoggedIn() {
        Log.d(TAG, "Socket HI complete, registering push handlers")
        mainViewModel.isAutoLogin = false
        onLoggedIn()

        webView.evaluateJavascript(
            "window.Transport && window.Transport.onNativeStatus('connected')", null
        )
        bridgeDispatcher.evalJs(
            "window.postMessage('${JsEscapeUtil.escapeForJs(org.json.JSONObject().put("event", "neoConnected").toString())}')"
        )

        // cold-start 딥링크 처리 (React 마운트 대기)
        if (hasPendingDeepLink()) {
            scope.launch {
                delay(800L)
                onConsumeDeepLink()
            }
        }

        // 저장된 내 상태코드를 React에 즉시 반영
        val myUserId = appConfig.getSavedUserId()
        val myStatusCode = appConfig.getMyStatusCode()
        if (myUserId != null && myStatusCode > 0) {
            val presenceJson = """{"users":[{"userId":"$myUserId","icon":$myStatusCode}]}"""
            Log.d("Presence", "[restore] push saved status on login: $presenceJson")
            bridgeDispatcher.evalJs(
                "window._onPresenceUpdate && window._onPresenceUpdate('${JsEscapeUtil.escapeForJs(presenceJson)}')"
            )
        }
    }

    // ── Failed ──

    private fun handleFailed(state: LoginState.Failed) {
        mainViewModel.isForegroundResume = false
        if (state.message == "TOKEN_EXPIRED") return  // 내부 재시도용, 웹에 전달 안 함

        if (state.message == "TOKEN_INVALID") {
            Log.d(TAG, "TOKEN_INVALID → cancelling sync, reloading WebView")
            loginViewModel.cancelAllSync()
            mainViewModel.isAutoLogin = false
            mainViewModel.isLoggedIn = false
            setClearAuthOnNextLoad(true)
            webView.loadUrl(appConfig.getSpaUrl())
            return
        }

        val escapedMsg = state.message.replace("'", "\\'")
        when {
            state.message == "LOGOUT_FAILED" -> {
                Log.d(TAG, "→ _logoutReject('$escapedMsg')")
                webView.evaluateJavascript("window._logoutReject('$escapedMsg')", null)
            }
            mainViewModel.isAutoLogin -> {
                Log.d(TAG, "→ _autoLoginReject('$escapedMsg')")
                webView.evaluateJavascript(
                    "(function(){var fn=window._waitAutoLoginReject||window._autoLoginReject;if(fn)fn('{}');})();",
                    null
                )
            }
            else -> {
                Log.d(TAG, "→ _loginReject('$escapedMsg')")
                webView.evaluateJavascript("window._loginReject('$escapedMsg')", null)
            }
        }
    }

    // ── Disconnected ──

    private fun handleDisconnected() {
        webView.evaluateJavascript(
            "window.Transport && window.Transport.onNativeStatus('disconnected')", null
        )
        if (appConfig.getSavedUserId().isNullOrEmpty()) {
            mainViewModel.isLoggedIn = false
        }
        // 백그라운드 전환으로 인한 Disconnected → isLoggedIn 유지
    }

    // ── Idle ──

    private fun handleIdle() {
        mainViewModel.isForegroundResume = false
        when {
            mainViewModel.isLogoutRequested -> {
                databaseProvider.closeUserDatabases()
                Log.d(TAG, "Logout complete, → _logoutResolve()")
                mainViewModel.isLogoutRequested = false
                mainViewModel.isLoggedIn = false
                bridgeDispatcher.completedSyncs.clear()
                loginViewModel.userNameCache.clear()
                webView.evaluateJavascript("window._logoutResolve()", null)
            }
            mainViewModel.isAutoLogin -> {
                Log.d(TAG, "Token expired during autoLogin, → reject('TOKEN_EXPIRED')")
                mainViewModel.isLoggedIn = false
                webView.evaluateJavascript(
                    "(function(){var fn=window._waitAutoLoginReject||window._autoLoginReject;" +
                    "if(fn)fn('TOKEN_EXPIRED');})();",
                    null
                )
            }
            mainViewModel.isLoggedIn -> {
                Log.d(TAG, "Token expired, → _loginReject('TOKEN_EXPIRED')")
                mainViewModel.isLoggedIn = false
                webView.evaluateJavascript("window._loginReject('TOKEN_EXPIRED')", null)
            }
        }
    }

    // ── SkinChanged ──

    private fun handleSkinChanged(state: LoginState.SkinChanged) {
        if (mainViewModel.isPickingFile) {
            Log.d(TAG, "SkinChanged: skipped (file picker active)")
            return
        }
        Log.d(TAG, "SkinChanged → reload: ${state.spaUrl}")
        webView.loadUrl(state.spaUrl)
    }
}
