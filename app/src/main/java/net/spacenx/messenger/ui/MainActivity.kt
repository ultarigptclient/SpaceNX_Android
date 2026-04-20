package net.spacenx.messenger.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.widget.Toast
import android.provider.Settings
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import androidx.core.app.ActivityCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.google.android.datatransport.BuildConfig
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import net.spacenx.messenger.HybridWebMessengerApp
import net.spacenx.messenger.R
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.common.Constants
import net.spacenx.messenger.common.JsEscapeUtil
import net.spacenx.messenger.data.remote.socket.codec.ProtocolCommand
import net.spacenx.messenger.service.push.HybridWebMessengerFirebaseMessagingService
import net.spacenx.messenger.ui.bridge.BridgeDispatcher
import net.spacenx.messenger.ui.bridge.WebAppBridge
import net.spacenx.messenger.ui.viewmodel.LoginViewModel
import net.spacenx.messenger.ui.viewmodel.MainViewModel
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import android.view.View
import android.widget.ImageButton
import net.spacenx.messenger.util.FileLogger


@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        //        private const val BASE_URL = "https://10.0.0.112:443/"
        //        private const val BASE_URL = "https://appassets.androidplatform.net/web/"
        private const val BASE_URL = "https://neo.ultari.co.kr:18019/static/nx/"
        private const val REQUEST_MULTIPLE_PERMISSIONS = 100
        private const val REQUEST_NOTIFICATION_PERMISSION = 101

        /**
         * NHM-openUserDetail 동시성 수정: window._openUserDetailResolve 단일 슬롯 문제 해결.
         * React가 openUserDetail을 20개 동시 호출하면 슬롯이 마지막 것으로 덮어써져
         * 나머지 19개가 10초 타임아웃. 이 shim은 userId별 resolver 큐를 유지한다.
         */
        /**
         * openUserDetail 중복 호출 방어 shim.
         *
         * JS 브릿지는 window._openUserDetail_<cbId>Resolve = resolve 로 콜백을 등록한다.
         * 동일 userId 를 두 컴포넌트가 동시에 요청하면 두 번째 등록이 첫 번째를 덮어써서
         * 첫 번째 Promise 가 10 초 타임아웃된다.
         *
         * 해결책:
         *  - JSON.stringify 를 래핑해 postMessage 직전 시점에
         *    window._<cbId>Resolve 를 캡처 → cbId 별 배열(pm)에 보관 → window 키를 null 로 초기화
         *  - 두 번째 할당도 같은 방식으로 캡처 → pm[cbId] = [resolveA, resolveB]
         *  - Android 가 resolveToJs 대신 window.__oudResolve(cbId, data) 를 호출하면
         *    모든 수집된 resolve 함수를 한 번에 호출
         *
         * 이전 버전의 버그:
         *  - window._openUserDetailResolve (존재하지 않는 키) 를 참조 → 항상 undefined
         *  - pm 키로 userId 를 사용했으나 native 는 cbId 로 호출 → 매핑 불일치
         */
        private const val BRIDGE_SHIM_JS = "(function(){" +
            "if(window.__bridgeShimInstalled)return;" +
            "window.__bridgeShimInstalled=true;" +
            "var pm={};" +
            // JSON.stringify 래핑: postMessage 직전 resolve fn 캡처 (동시 호출 슬롯 덮어쓰기 방지)
            "var os=JSON.stringify;" +
            "JSON.stringify=function(o){" +
            "if(o&&typeof o==='object'){" +
            // openUserDetail: _callbackId 기반 큐
            "if(o.action==='openUserDetail'&&o._callbackId){" +
            "var key='_'+o._callbackId+'Resolve';" +
            "var fn=window[key];" +
            "if(typeof fn==='function'){" +
            "(pm[o._callbackId]=pm[o._callbackId]||[]).push(fn);" +
            "window[key]=null;}" +
            // getMyPart: 단일 action 큐 (callbackId 없이 동시 다중 호출 처리)
            "}else if(o.action==='getMyPart'){" +
            "var fn2=window._getMyPartResolve;" +
            "if(typeof fn2==='function'){" +
            "(pm['getMyPart']=pm['getMyPart']||[]).push(fn2);" +
            "window._getMyPartResolve=null;}" +
            // getOrgSubList: 동시 다중 호출 처리 (내 부서 멤버 목록 race condition 방지)
            "}else if(o.action==='getOrgSubList'){" +
            "var fn3=window._getOrgSubListResolve;" +
            "if(typeof fn3==='function'){" +
            "(pm['getOrgSubList']=pm['getOrgSubList']||[]).push(fn3);" +
            "window._getOrgSubListResolve=null;}}" +
            "}" +
            "return os.apply(JSON,arguments);};" +
            // Android OrgHandler 가 호출: pm[cbId] 에 쌓인 resolve 전부 실행
            "window.__oudResolve=function(cbId,d){" +
            "if(pm[cbId]&&pm[cbId].length){" +
            "var fns=pm[cbId].splice(0);delete pm[cbId];" +
            "for(var i=0;i<fns.length;i++){try{fns[i](d);}catch(e){}}" +
            "return true;}return false;};" +
            // getMyPart resolve 디스패처
            "window.__gmpResolve=function(d){" +
            "if(pm['getMyPart']&&pm['getMyPart'].length){" +
            "var fns=pm['getMyPart'].splice(0);delete pm['getMyPart'];" +
            "for(var i=0;i<fns.length;i++){try{fns[i](d);}catch(e){}}" +
            "return true;}return false;};" +
            // getOrgSubList resolve 디스패처
            "window.__goslResolve=function(d){" +
            "if(pm['getOrgSubList']&&pm['getOrgSubList'].length){" +
            "var fns=pm['getOrgSubList'].splice(0);delete pm['getOrgSubList'];" +
            "for(var i=0;i<fns.length;i++){try{fns[i](d);}catch(e){}}" +
            "return true;}return false;};" +
            "})();"

    }

    private val loginViewModel: LoginViewModel by viewModels()
    private val mainViewModel: MainViewModel by viewModels()
    @Inject lateinit var appConfig: AppConfig
    @Inject lateinit var databaseProvider: net.spacenx.messenger.data.local.DatabaseProvider
    @Inject lateinit var notificationGroupManager: net.spacenx.messenger.service.push.GroupNotificationManager

    private lateinit var webView: WebView
    private lateinit var subWebView: WebView
    private lateinit var inAppBanner: InAppBannerView
    private lateinit var btnDevLog: ImageButton
    var pendingSubWebViewContext: String? = null
    private lateinit var bridgeDispatcher: BridgeDispatcher
    private lateinit var statusBarManager: StatusBarManager
    private lateinit var updateChecker: AppUpdateChecker
    private lateinit var pushEventRouter: PushEventRouter
    private lateinit var loginStateCoordinator: LoginStateCoordinator
    private var reconnectJob: Job? = null

    // ── 상태 (MainViewModel에 위임) ──
    private var overlayPermissionPopup
        get() = mainViewModel.overlayPermissionPopup
        set(v) { mainViewModel.overlayPermissionPopup = v }
    private var isLoggedIn
        get() = mainViewModel.isLoggedIn
        set(v) { mainViewModel.isLoggedIn = v }
    private var backPressedTime
        get() = mainViewModel.backPressedTime
        set(v) { mainViewModel.backPressedTime = v }
    private var isForegroundResume
        get() = mainViewModel.isForegroundResume
        set(v) { mainViewModel.isForegroundResume = v }
    private var isLogoutRequested
        get() = mainViewModel.isLogoutRequested
        set(v) { mainViewModel.isLogoutRequested = v }
    var isAutoLogin
        get() = mainViewModel.isAutoLogin
        set(v) { mainViewModel.isAutoLogin = v }
    private var isAppInForeground
        get() = mainViewModel.isAppInForeground
        set(v) { mainViewModel.isAppInForeground = v }
    var pendingAuthJson
        get() = mainViewModel.pendingAuthJson
        set(v) { mainViewModel.pendingAuthJson = v }

    /** 다음 페이지 로드 시 localStorage auth 캐시를 제거할 플래그 (addDocumentStartJavaScript용) */
    @Volatile private var clearAuthOnNextLoad = false

    // ── 파일 선택 (Bridge pickFile 액션) ──
    /** 파일 피커 진행 중 플래그 — 스킨 리로드 억제용 (업로드+resolve 완료 시 해제) */
    var isPickingFile
        get() = mainViewModel.isPickingFile
        set(v) { mainViewModel.isPickingFile = v }
    var pendingPickFileCallback: ((List<android.net.Uri>) -> Unit)? = null
    val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = mutableListOf<android.net.Uri>()
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            if (data?.clipData != null) {
                for (i in 0 until data.clipData!!.itemCount) {
                    uris.add(data.clipData!!.getItemAt(i).uri)
                }
            } else if (data?.data != null) {
                uris.add(data.data!!)
            }
        }
        pendingPickFileCallback?.invoke(uris)
        pendingPickFileCallback = null
    }

    // ── 파일 선택 (WebView <input type="file">) ──
    private var fileUploadCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d("FileChooser", "fileChooserLauncher result: resultCode=${result.resultCode}")
        val uris = if (result.resultCode == RESULT_OK) {
            val data = result.data
            if (data?.clipData != null) {
                Array(data.clipData!!.itemCount) { i -> data.clipData!!.getItemAt(i).uri }.also {
                    Log.d("FileChooser", "Selected ${it.size} files (clipData): ${it.joinToString()}")
                }
            } else {
                data?.data?.let { arrayOf(it) }.also {
                    Log.d("FileChooser", "Selected single file: ${it?.firstOrNull()}")
                }
            }
        } else {
            Log.d("FileChooser", "File selection cancelled")
            null
        }
        fileUploadCallback?.onReceiveValue(uris)
        fileUploadCallback = null
    }

    fun getCurrentUserId(): String = appConfig.getSavedUserId() ?: ""
    fun getBaseUrl(): String = BASE_URL

    /** FRONTEND_VERSION/SKIN 갱신 후 SPA URL 재로딩 (bridge 핸들러에서 호출) */
    fun reloadSpa() {
        runOnUiThread { webView.loadUrl(appConfig.getSpaUrl()) }
    }

    // ── 앱 라이프사이클 ──

    /**
     * 백그라운드 grace period — 이 시간 이내에 포그라운드 복귀하면 소켓 유지 + 재연결/재sync skip.
     * 짧은 창 전환, 사진/영상 뷰어 진입 등 흔한 상황에서 불필요한 HI/refresh/sync 트래픽 차단.
     */
    private val BACKGROUND_DISCONNECT_GRACE_MS = 30_000L
    private var disconnectJob: kotlinx.coroutines.Job? = null

    private val appLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            Log.d(TAG, "App FOREGROUND (ProcessLifecycle onStart)")
            isAppInForeground = true
            net.spacenx.messenger.common.AppState.isForeground = true
            // grace period 내 복귀 → 예정된 disconnect 취소
            disconnectJob?.cancel()
            disconnectJob = null
            if (isLoggedIn) {
                isForegroundResume = true
                if (loginViewModel.isConnected()) {
                    Log.d(TAG, "Foreground resume: socket still alive, skipping reconnect/sync")
                    return
                }
                reconnectJob?.cancel()
                reconnectJob = lifecycleScope.launch {
                    delay(500L)
                    if (!loginViewModel.isConnected()) {
                        Log.d(TAG, "Reconnecting socket on foreground (debounced)")
                        reconnectSocket()
                    }
                }
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            Log.d(TAG, "App BACKGROUND (ProcessLifecycle onStop)")
            isAppInForeground = false
            net.spacenx.messenger.common.AppState.isForeground = false
            reconnectJob?.cancel()
            reconnectJob = null
            if (isLoggedIn) {
                // 즉시 끊지 않고 grace period 예약 — 짧은 백그라운드는 소켓 유지
                disconnectJob?.cancel()
                disconnectJob = lifecycleScope.launch {
                    delay(BACKGROUND_DISCONNECT_GRACE_MS)
                    if (!isAppInForeground && isLoggedIn) {
                        Log.d(TAG, "Background > ${BACKGROUND_DISCONNECT_GRACE_MS}ms, sending unsubscribeAll + disconnect")
                        try { loginViewModel.unsubscribeAll() } catch (_: Exception) {}
                        try { loginViewModel.disconnectSilently() } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    // ── 네트워크 복구 감지 ──

    private lateinit var connectivityManager: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                Log.d(TAG, "NetworkCallback onAvailable: isLoggedIn=$isLoggedIn, isAppInForeground=$isAppInForeground, isConnected=${loginViewModel.isConnected()}")
                if (isLoggedIn && isAppInForeground && !loginViewModel.isConnected()) {
                    reconnectJob?.cancel()
                    reconnectJob = lifecycleScope.launch {
                        delay(1000L)
                        if (!loginViewModel.isConnected()) {
                            Log.d(TAG, "Network recovered, reconnecting socket")
                            isForegroundResume = true
                            reconnectSocket()
                        }
                    }
                }
            }
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        Log.d(TAG, "Overlay permission launcher result")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Edge-to-edge (targetSdk 35): 시스템 바 영역만큼 루트 뷰에 패딩 적용
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // ── QUIC 테스트 활성화 (2026-04-15) ──
        // UDP 18029 inbound 오픈 확인 후 재활성화. 포트는 common.db config.ultari.server.bridge.quic-port 참조.
        // 비활성화 시 false로 변경. 정식 토글은 개발자 옵션 메뉴에 이관 예정.
        appConfig.setQuicEnabled(false)

        if (Constants.getUseSecureCapture()) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        btnDevLog = findViewById(R.id.btnDevLog)
        btnDevLog.setOnClickListener {
            startActivity(Intent(this@MainActivity, ConfigLogFileActivity::class.java))
        }

        setupMainWebView()

        // BridgeDispatcher + JavaScript Bridge 등록
        bridgeDispatcher = BridgeDispatcher(
            this, webView,
            loginViewModel.authRepo,
            loginViewModel.buddyRepo,
            loginViewModel.channelRepo,
            loginViewModel.orgRepo,
            loginViewModel.pubSubRepo,
            loginViewModel.statusRepo,
            loginViewModel.messageRepo,
            loginViewModel.notiRepo,
            loginViewModel,
            databaseProvider,
            appConfig,
            loginViewModel.userNameCache,
            loginViewModel.projectRepo
        )
        webView.addJavascriptInterface(WebAppBridge(bridgeDispatcher), WebAppBridge.NAME)

        statusBarManager = StatusBarManager(this)
        updateChecker = AppUpdateChecker(this, lifecycleScope)
        pushEventRouter = PushEventRouter(
            activity = this,
            loginViewModel = loginViewModel,
            bridgeDispatcher = bridgeDispatcher,
            appConfig = appConfig,
            databaseProvider = databaseProvider,
            notificationGroupManager = notificationGroupManager,
            inAppBanner = inAppBanner,
            webView = webView,
            scope = lifecycleScope,
            isAppInForeground = { isAppInForeground },
            preprocessContent = { preprocessContent(it) }
        )
        loginStateCoordinator = LoginStateCoordinator(
            activity = this,
            webView = webView,
            bridgeDispatcher = bridgeDispatcher,
            loginViewModel = loginViewModel,
            appConfig = appConfig,
            databaseProvider = databaseProvider,
            mainViewModel = mainViewModel,
            scope = lifecycleScope,
            onLoggedIn = {
                pushEventRouter.register()
                runOnUiThread { btnDevLog.visibility = View.VISIBLE }
            },
            hasPendingDeepLink = { pendingDeepLinkIntent != null },
            onConsumeDeepLink = { consumePendingDeepLink() },
            setClearAuthOnNextLoad = { v -> clearAuthOnNextLoad = v }
        )

        setupAuthBridge()

        setupBackPressed()

        // 로그인 상태 관찰
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                loginViewModel.loginState.collect { loginStateCoordinator.handle(it) }
            }
        }

        observeSubscribeResponse()


        // orgList/buddyList는 startBackgroundSync의 orgReady/buddyReady 이벤트 →
        // React가 getOrgList/syncBuddy bridge 호출하는 단일 경로로 전달.
        // SharedFlow push(_getOrgListResolve, _syncBuddyResolve)는 이벤트 경로와 중복되어 제거.

        // 라이프사이클 옵저버 등록
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)

        // 네트워크 복구 감지 등록
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        // 첫 페이지 로드
        startProcess()

        // cold start: 앱이 꺼진 상태에서 알림 클릭 시 onCreate로 진입
        // NotificationType extra가 있을 때만 저장, 로그인 완료 후 처리
        intent?.let { if (it.hasExtra("NotificationType")) pendingDeepLinkIntent = it }
    }

    /** cold start 또는 로그인 완료 후 처리할 딥링크 intent */
    private var pendingDeepLinkIntent: Intent? = null

    /** 로그인 완료 후 호출 — pending 딥링크 소비 */
    fun consumePendingDeepLink() {
        val pending = pendingDeepLinkIntent ?: return
        pendingDeepLinkIntent = null
        if (pending.hasExtra("NotificationType")) {
            handleNotificationIntent(pending)
        }
    }

    fun startProcess() {
        Log.d(TAG, "startProcess")
        updateChecker.check(getUpdateUrl())
        startService()

        // Flutter 패턴: refreshToken이 있으면 WebView 로드와 동시에 선제적 autoLogin 시작
        val userId = appConfig.getSavedUserId()
        val refreshToken = appConfig.getSavedRefreshToken()
        Log.d(TAG, "startProcess: userId=${userId?.take(6)}, refreshToken=${if (refreshToken.isNullOrEmpty()) "EMPTY" else "present(${refreshToken.length})"}")
        if (!userId.isNullOrEmpty() && !refreshToken.isNullOrEmpty()) {
            Log.d(TAG, "startProcess: preemptive autoLogin for $userId")
            isAutoLogin = true
            loginViewModel.reconnect(userId)
        } else {
            // 유효한 토큰 없음 → 다음 페이지 로드 시 localStorage auth 캐시 제거 예약
            Log.d(TAG, "startProcess: no valid token, scheduling auth clear on next load")
            clearAuthOnNextLoad = true
        }

        val spaUrl = appConfig.getSpaUrl()
        webView.loadUrl(spaUrl)
        Log.d(TAG, "startProcess: loaded $spaUrl")
    }

    /** 상태 체크만 (getClientStatus용, 부작용 없음) */
    fun needPermission(): Boolean = mainViewModel.needPermissionCheck(applicationContext)

    fun permissionRequest(): Boolean {
        if (needPermissionCheck(applicationContext)) {
            Log.d(TAG, "Launch permission view2")
            return false
        } else {
            Log.d(TAG, "Launch permission view3")
            // checkBatteryOptimization() // 주석 처리
            return true
        }
    }

    fun checkBatteryOptimization() {
        Log.d(TAG, "checkBatteryOptimization start")
        if (!Constants.getMyId(applicationContext)!!.isEmpty()) {
            try {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                val packageName = getPackageName()
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    Log.d(TAG, "BATTERY_OPTIMIZATIONS request battery optimization")
                    @SuppressLint("BatteryLife")
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.setData(Uri.parse("package:" + getPackageName()))
                    startActivity(intent)
                }
                else Log.d(TAG, "BATTERY_OPTIMIZATIONS already ignoring battery optimization")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun startService() {
        HybridWebMessengerFirebaseMessagingService.getToken(this) { token, isRefresh ->
            Log.d(TAG, "startService FCM token: $token (refresh=$isRefresh)")
        }
    }

    fun getUpdateUrl(): String {
        return getString(R.string.UPDATE_URL)
    }

    fun privacyPolicy(): Boolean {
        if (Constants.getMyId(applicationContext)!!.isEmpty()) {
            val sharedPref = getSharedPreferences("talkConfig", MODE_PRIVATE)
            if (sharedPref.getBoolean("ISPERSONAL", false)) return true
            else {
                Log.d(TAG, "privacyPolicy, start privacy")
                return false
            }
        }
        return true
    }

    fun needLogin(): Boolean {
        // refreshToken이 있으면 자동 로그인 가능 (NHM-50)
        val refreshToken = appConfig.getSavedRefreshToken()
        return refreshToken.isNullOrEmpty()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        statusBarManager.refreshThemeColorAndStatusBar(webView)
    }

    override fun onStop() {
        super.onStop()
        // ProcessLifecycleOwner는 ~700ms 디바운스가 있어 FCM 억제 타이밍 레이스 발생 가능.
        // Activity onStop에서 즉시 false로 세팅해 백키/홈키 직후 FCM이 와도 억제되도록 함.
        net.spacenx.messenger.common.AppState.isForeground = false
        isAppInForeground = false
        // unsubscribeAll은 appLifecycleObserver.onStop에서 처리 (소켓 끊기 전 전송)
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android WebView는 requestFocus() 없이 document.hasFocus()가 false를 반환함.
        // useChatRoom.js가 focusChannel을 호출하려면 hasFocus()=true + window.focus 이벤트가 필요.
        if (hasFocus) {
            webView.requestFocus()
            webView.post { webView.evaluateJavascript("window.dispatchEvent(new Event('focus'))", null) }
        } else {
            webView.post { webView.evaluateJavascript("window.dispatchEvent(new Event('blur'))", null) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (overlayPermissionPopup) {
            overlayPermissionPopup = false
            requestPermission()
        }
        updateChecker.check(getUpdateUrl())
    }

    override fun onDestroy() {
        super.onDestroy()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(appLifecycleObserver)
        connectivityManager.unregisterNetworkCallback(networkCallback)

        // 소켓 + 코루틴 먼저 정리 (DB 사용 중인 백그라운드 작업 취소)
        loginViewModel.cancelAllSync()
        loginViewModel.disconnectSocket()
        bridgeDispatcher.callService.dispose()

        // 코루틴 취소가 전파될 시간 확보 후 DB 닫기
        // (cancel은 cooperative — IO 블로킹 작업이 즉시 중단되지 않을 수 있음)
        databaseProvider.closeAll()

        // WebView 리소스 해제 (메모리 누수 방지)
        webView.removeJavascriptInterface(WebAppBridge.NAME)
        webView.stopLoading()
        webView.destroy()

        subWebView.removeJavascriptInterface(WebAppBridge.NAME)
        subWebView.stopLoading()
        subWebView.destroy()
    }

    // ── 권한 요청 ──

    private fun getRequiredPermissions(): Array<String> = mainViewModel.getRequiredPermissions()

    fun onRequestPermissionFromWeb() {
        Log.d(TAG, "onRequestPermissionFromWeb")
        requestPermission()
    }

    fun needPermissionCheck(context: Context): Boolean = mainViewModel.needPermissionCheck(context)

    private fun requestPermission() {
        if (!Settings.canDrawOverlays(this)) {
            MaterialAlertDialogBuilder(this)
                .setMessage("알림 표시를 위해 '다른 앱 위에 표시' 권한이 필요합니다.")
                .setPositiveButton("확인") { _, _ ->
                    overlayPermissionPopup = true
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    overlayPermissionLauncher.launch(intent)
                }
                .setCancelable(false)
                .show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
                return
            }
        }

        Log.d(TAG, "request multiple permissions")
        ActivityCompat.requestPermissions(
            this,
            getRequiredPermissions(),
            REQUEST_MULTIPLE_PERMISSIONS
        )
    }

    fun putBooleanSharePref(key: String, value: Boolean) {
        try {
            Log.d(TAG, "putBooleanSharePref key:$key, value:$value")
            val pref: SharedPreferences = getSharedPreferences("talkConfig", MODE_PRIVATE)
            val editor = pref.edit()
            editor.putBoolean(key, value)
            editor.commit()
        } catch (e: java.lang.Exception) {
            Log.e(TAG, "putBooleanSharePref err:$e")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQUEST_MULTIPLE_PERMISSIONS -> {
                for (i in permissions.indices) {
                    val granted = grantResults[i] == PackageManager.PERMISSION_GRANTED
                    Log.d(TAG, "Permission(${permissions[i]}) Result: $granted")
                }

                val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (allGranted) {
                    Log.d(TAG, "All permissions granted")
                    webView.evaluateJavascript("window._completePermission()", null)
                } else {
                    Log.d(TAG, "Some permissions denied")
                    val permanentlyDenied = permissions.filterIndexed { i, _ ->
                        grantResults[i] != PackageManager.PERMISSION_GRANTED
                    }.any { !ActivityCompat.shouldShowRequestPermissionRationale(this, it) }

                    if (permanentlyDenied) {
                        showPermissionSettingsDialog()
                    } else {
                        finish()
                    }
                }
            }

            REQUEST_NOTIFICATION_PERMISSION -> {
                val granted = grantResults.isNotEmpty()
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED
                Log.d(TAG, "POST_NOTIFICATIONS result: $granted")
                if (!granted) {
                    Log.d(TAG, "POST_NOTIFICATIONS denied")
                    if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                        showPermissionSettingsDialog()
                    } else {
                        finish()
                    }
                    return
                }
                Log.d(TAG, "request multiple permissions")
                ActivityCompat.requestPermissions(
                    this,
                    getRequiredPermissions(),
                    REQUEST_MULTIPLE_PERMISSIONS
                )
            }
        }
    }

    private fun showPermissionSettingsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("권한 필요")
            .setMessage("앱 사용을 위해 필요한 권한이 허용되지 않았습니다.\n설정에서 권한을 허용해주세요.")
            .setPositiveButton("설정으로 이동") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
            .setNegativeButton("종료") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    fun onUpdateStatusBarFromWeb(hex: String) = statusBarManager.onUpdateStatusBarFromWeb(hex)

    // ── 소켓 재연결 ──

    /**
     * @param silent true=포그라운드 복귀(JS 상태 유지), false=앱 재시작(JS 초기화 필요)
     */
    private fun reconnectSocket() {
        val userId = appConfig.getSavedUserId()
        val refreshToken = appConfig.getSavedRefreshToken()
        if (!userId.isNullOrEmpty() && !refreshToken.isNullOrEmpty()) {
            Log.d(TAG, "reconnectSocket: userId=$userId")
            loginViewModel.reconnect(userId)
        } else {
            Log.d(TAG, "reconnectSocket: no refreshToken, skip")
        }
    }

    // ── 개인정보 동의 ──

    fun onAgreePrivacyPolicyFromWeb() {
        Log.d(TAG, "onAgreePrivacyPolicyFromWeb")
        try {
            putBooleanSharePref("ISPERSONAL", true)
            webView.evaluateJavascript("window._agreePrivacyPolicyResolve()", null)
        } catch (e: Exception) {
            Log.e(TAG, "onAgreePrivacyPolicyFromWeb error", e)
            FileLogger.log(TAG, "onAgreePrivacyPolicyFromWeb ERROR ${e.message}")
            val escapedMsg = e.message?.replace("'", "\\'") ?: "Unknown error"
            webView.evaluateJavascript("window._agreePrivacyPolicyReject('$escapedMsg')", null)
        }
    }

    // ── 로그아웃 ──

    fun requestLogout() {
        Log.d(TAG, "requestLogout called")
        isLogoutRequested = true
        btnDevLog.visibility = View.GONE
        loginViewModel.logout()
    }

    // ── 로그인 ──

    fun onLoginFromWeb(username: String, password: String) {
        Log.d(TAG, "onLoginFromWeb - username=$username")
        isAutoLogin = false
        loginViewModel.login(username, password)
    }

    fun onAutoLoginFromWeb() {
        Log.d(TAG, "onAutoLoginFromWeb")
        isAutoLogin = true
        if (loginViewModel.sessionManager.jwtToken != null) {
            Log.d(TAG, "onAutoLoginFromWeb: token already present, skipping reconnect")
            return
        }
        reconnectSocket()
    }

    /** hardReload 시 foregroundResume 플래그 초기화 — autoLogin deferred가 정상 resolve되도록 */
    fun onHardReload() {
        Log.d(TAG, "onHardReload: resetting isForegroundResume")
        isForegroundResume = false
    }

    /** getClientState에서 needLogin=false일 때 호출 — refreshToken으로 자동 로그인 */
    fun triggerAutoReconnect(userId: String) {
        isAutoLogin = true
        if (loginViewModel.sessionManager.jwtToken != null) {
            Log.d(TAG, "triggerAutoReconnect: token already present, skipping reconnect")
            return
        }
        Log.d(TAG, "triggerAutoReconnect: userId=$userId")
        reconnectSocket()
    }

    // ══════════════════════════════════════
    // NHM-70: onNewIntent — 알림 클릭 → 해당 화면 이동
    // ══════════════════════════════════════

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent) {
        val type = intent.getStringExtra("NotificationType") ?: return
        val key = intent.getStringExtra("Key") ?: return
        val arg = intent.getStringExtra("Arg")
        Log.d(TAG, "handleNotificationIntent: type=$type, key=$key, arg=$arg")

        // 로그인 완료 후에 처리
        if (!isLoggedIn) {
            Log.d(TAG, "handleNotificationIntent: not logged in, deferring")
            return
        }

        when (type) {
            Constants.TYPE_TALK -> navigateToChannel(key)
            Constants.TYPE_MESSAGE -> {
                val js = "window.dispatchEvent(new CustomEvent('neoNavigate',{detail:{nav:'message'}}))"
                bridgeDispatcher.evalJs(js)
            }
            Constants.TYPE_SYSTEM_NOTIFY -> {
                val js = "window.dispatchEvent(new CustomEvent('neoNavigate',{detail:{nav:'noti'}}))"
                bridgeDispatcher.evalJs(js)
            }
        }

        // extras 소비
        intent.removeExtra("NotificationType")
        intent.removeExtra("Key")
        intent.removeExtra("Arg")
    }

    /** React SPA에 채널 열기 요청 */
    fun navigateToChannel(channelCode: String) {
        // _pendingChatOpen 설정 + neoNavigate CustomEvent dispatch
        val pendingJs = "window._pendingChatOpen={channelCode:'${JsEscapeUtil.escapeForJs(channelCode)}',fromNav:'noti'}"
        bridgeDispatcher.evalJs(pendingJs)
        val navJs = "window.dispatchEvent(new CustomEvent('neoNavigate',{detail:{nav:'chat',channelCode:'${JsEscapeUtil.escapeForJs(channelCode)}'}}))"
        bridgeDispatcher.evalJs(navJs)
        val js = "window.postMessage('${JsEscapeUtil.escapeForJs(JSONObject().put("event", "neoOpenChat").put("channelCode", channelCode).toString())}')"
        bridgeDispatcher.evalJs(js)
    }

    // ══════════════════════════════════════
    // NHM-70: foreground 인앱 알림 배너
    // ══════════════════════════════════════

    fun showPushNotification(
        type: String, channelCode: String, senderName: String, contents: String, chatType: Int = 0
    ) = pushEventRouter.showPushNotification(type, channelCode, senderName, contents, chatType)

    fun showPushNotificationGeneric(type: String, key: String, senderName: String, contents: String) =
        pushEventRouter.showPushNotificationGeneric(type, key, senderName, contents)

    private fun preprocessContent(content: String): String = mainViewModel.preprocessContent(content)

    // ══════════════════════════════════════
    // 서브 WebView (채팅/쪽지 팝업 대체)
    // ══════════════════════════════════════

    @SuppressLint("SetJavaScriptEnabled")
    private fun initSubWebView() {
        subWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
        }
        subWebView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                Log.d("SubWebConsole", msg.message())
                return true
            }
        }
        subWebView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(
                view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?
            ) { handler?.proceed() }

            /**
             * 임시 방어책: 동일 userId에 대해 openUserDetail 이 중복 호출될 때
             * JS 브릿지가 window._openUserDetail_<userId>Resolve 를 덮어써서
             * 먼저 등록된 Promise 가 10초 타임아웃되는 문제를 방지.
             *
             * URL 의 userIds 파라미터로 전달된 userId 목록에 대해
             * Object.defineProperty setter/getter 를 주입해 모든 resolve 함수를
             * 수집하고, native 가 resolve 할 때 수집된 함수 전부를 호출한다.
             *
             * 프론트엔드에서 cbId 중복 제거가 구현되면 이 코드는 제거해도 된다.
             */
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url == null) return
                try {
                    val uri = android.net.Uri.parse(url)
                    val userIdsParam = uri.getQueryParameter("userIds") ?: return
                    val arr = org.json.JSONArray(userIdsParam)
                    if (arr.length() == 0) return
                    val keys = (0 until arr.length())
                        .map { arr.getString(it) }
                        .filter { it.isNotEmpty() }
                        .joinToString(",") { id -> "\"_openUserDetail_${id}Resolve\"" }
                    val patchJs = """
                        (function() {
                            [$keys].forEach(function(key) {
                                var collected = [];
                                Object.defineProperty(window, key, {
                                    configurable: true,
                                    enumerable: true,
                                    set: function(fn) {
                                        if (typeof fn === 'function') collected.push(fn);
                                        else collected = [];
                                    },
                                    get: function() {
                                        if (collected.length === 0) return undefined;
                                        return function(data) {
                                            var toCall = collected.splice(0);
                                            try {
                                                Object.defineProperty(window, key, {
                                                    configurable: true, writable: true, value: undefined
                                                });
                                            } catch(e) {}
                                            toCall.forEach(function(f) { try { f(data); } catch(e) {} });
                                        };
                                    }
                                });
                            });
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(patchJs, null)
                    Log.d(TAG, "SubWebView: injected multi-resolve patch for keys=[$keys]")
                } catch (e: Exception) {
                    Log.w(TAG, "SubWebView: multi-resolve patch injection failed: ${e.message}")
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "SubWebView loaded: $url")
                // history.back() / window.close() → bridge closeWindow 으로 가로채기
                val closeInterceptJs = """
                    (function(){
                        if(window.__nativeCloseInstalled)return;
                        window.__nativeCloseInstalled=true;
                        var _doClose=function(){
                            try{window.AndroidBridge.postMessage(JSON.stringify({action:'closeWindow'}));}catch(e){}
                        };
                        window.close=_doClose;
                        var _origBack=window.history.back.bind(window.history);
                        window.history.back=function(){
                            if(!window.history.state&&window.history.length<=1){_doClose();return;}
                            _origBack();
                        };
                    })();
                """.trimIndent()
                view?.evaluateJavascript(closeInterceptJs, null)
                // 수신자 정보 주입 (pendingSubWebViewContext 가 세팅된 경우)
                val ctx = pendingSubWebViewContext
                if (ctx != null) {
                    pendingSubWebViewContext = null
                    val escaped = JsEscapeUtil.escapeForJs(ctx)
                    view?.evaluateJavascript(
                        "try{var d=JSON.parse('$escaped');" +
                        "window._neoInitData=d;" +
                        "if(typeof window.initPage==='function')window.initPage(d);" +
                        "}catch(e){console.error('[SubWebView] initPage inject error:',e);}", null
                    )
                    Log.d(TAG, "SubWebView: injected _nativeContext")
                }
            }
        }
        // 서브 WebView에서도 closeWindow/closeApp → 서브 닫기
        subWebView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun postMessage(json: String) {
                try {
                    val obj = JSONObject(json)
                    val action = obj.optString("action", "")
                    Log.d(TAG, "SubWebView bridge: action=$action")
                    when (action) {
                        "closeWindow", "closeApp", "finishApp",
                        "close", "back", "goBack", "cancel", "closeSubWindow" -> runOnUiThread { closeSubWebView() }
                        else -> {
                            // 다른 액션은 메인 BridgeDispatcher로 위임
                            runOnUiThread { bridgeDispatcher.dispatch(action, WebAppBridge.parseParams(obj)) }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "SubWebView bridge error: ${e.message}")
                    FileLogger.log(TAG, "SubWebView bridge ERROR ${e.message}")
                }
            }
        }, WebAppBridge.NAME)
    }

    fun openSubWebView(url: String) {
        Log.d(TAG, "openSubWebView: $url")
        runOnUiThread {
            subWebView.visibility = android.view.View.VISIBLE
            subWebView.loadUrl(url)
        }
    }

    fun closeSubWebView() {
        Log.d(TAG, "closeSubWebView")
        runOnUiThread {
            subWebView.visibility = android.view.View.GONE
            subWebView.evaluateJavascript("document.body.innerHTML=''", null)
            // 채널 목록 갱신 (채팅방에서 나온 후)
            bridgeDispatcher.notifyReactOnce("chatReady")
        }
    }

    fun isSubWebViewOpen(): Boolean = subWebView.visibility == android.view.View.VISIBLE
    fun getSubWebView(): WebView = subWebView

    // ── 통화 오버레이 ──

    private var callOverlayFragment: net.spacenx.messenger.ui.call.CallOverlayFragment? = null

    /** 통화에 필요한 CAMERA/RECORD_AUDIO 퍼미션이 granted인지 확인 */
    fun hasCallPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private var pendingCallAction: (() -> Unit)? = null
    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (allGranted) {
            pendingCallAction?.invoke()
        } else {
            Log.w(TAG, "Call permissions denied: $grants")
            bridgeDispatcher.scope.launch {
                bridgeDispatcher.rejectToJs("createCall", "통화 권한이 필요합니다")
            }
        }
        pendingCallAction = null
    }

    /** 통화 퍼미션 확인 후 action 실행. 미허용 시 런타임 요청 */
    fun ensureCallPermissions(action: () -> Unit) {
        if (hasCallPermissions()) {
            action()
        } else {
            pendingCallAction = action
            callPermissionLauncher.launch(arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ))
        }
    }

    fun showCallOverlay(callService: net.spacenx.messenger.ui.call.CallService, callType: String) {
        runOnUiThread {
            if (callOverlayFragment != null) return@runOnUiThread
            // callOverlay 컨테이너 추가 (WebView 위)
            val container = android.widget.FrameLayout(this).apply {
                id = android.view.View.generateViewId()
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            val rootFrame = findViewById<android.widget.FrameLayout>(android.R.id.content)
                .getChildAt(0) as android.widget.FrameLayout
            rootFrame.addView(container)

            val fragment = net.spacenx.messenger.ui.call.CallOverlayFragment.newInstance(callType)
            fragment.callService = callService
            fragment.onEndCall = {
                lifecycleScope.launch {
                    callService.endCall(callService.currentChannelCode ?: "")
                    hideCallOverlay()
                }
            }
            callOverlayFragment = fragment
            supportFragmentManager.beginTransaction()
                .add(container.id, fragment, "call_overlay")
                .commitAllowingStateLoss()
            Log.d(TAG, "showCallOverlay: $callType")
        }
    }

    fun hideCallOverlay() {
        runOnUiThread {
            val fragment = callOverlayFragment ?: return@runOnUiThread
            try {
                supportFragmentManager.beginTransaction()
                    .remove(fragment)
                    .commitAllowingStateLoss()
                // 컨테이너 제거
                val rootFrame = findViewById<android.widget.FrameLayout>(android.R.id.content)
                    .getChildAt(0) as android.widget.FrameLayout
                val lastChild = rootFrame.getChildAt(rootFrame.childCount - 1)
                if (lastChild !is WebView && lastChild !is InAppBannerView) {
                    rootFrame.removeView(lastChild)
                }
            } catch (e: Exception) {
                Log.w(TAG, "hideCallOverlay error: ${e.message}")
            }
            callOverlayFragment = null
            Log.d(TAG, "hideCallOverlay")
        }
    }

    // ── onCreate 분해 헬퍼: 메인 WebView 초기화 (settings + WebViewClient + WebChromeClient) ──
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupMainWebView() {
        // WebView + 서브 WebView + 인앱 배너 초기화
        webView = findViewById(R.id.webView)
        subWebView = findViewById(R.id.subWebView)
        inAppBanner = findViewById(R.id.inAppBanner)
        initSubWebView()
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                Log.w(TAG, "SSL error: ${error?.primaryError}, url: ${error?.url}")
                handler?.proceed()
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d(TAG, "Page started: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "Page loaded: $url")
                statusBarManager.updateStatusBarColor(view)
                statusBarManager.injectColorSchemeListener(view)
                // openUserDetail 동시 호출 resolver 큐 shim 주입
                view?.evaluateJavascript(BRIDGE_SHIM_JS, null)

                // 선제적 autoLogin: WebView 로드 전에 Authenticated 완료된 경우
                // React가 waitAutoLogin 콜백을 등록할 때까지 짧은 지연 후 resolve
                val pending = pendingAuthJson
                if (pending != null && isAutoLogin) {
                    Log.d(TAG, "Page loaded: pendingAuthJson found, will resolve after React init")
                    view?.postDelayed({
                        // Activity destroy 후 dead WebView 에 evaluateJavascript 호출 방지
                        if (isDestroyed || isFinishing) return@postDelayed
                        val stillPending = pendingAuthJson
                        if (stillPending != null) {
                            pendingAuthJson = null
                            Log.d(TAG, "→ _autoLoginResolve (deferred after page load)")
                            webView.evaluateJavascript(
                                "(function(){var fn=window._waitAutoLoginResolve||window._autoLoginResolve;if(fn)fn('$stillPending');})();",
                                null
                            )
                        }
                    }, 100) // React가 getClientState → waitAutoLogin 콜백 등록하는 시간
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    Log.d("WebConsole", "[${it.messageLevel()}] ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                }
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                Log.d("FileChooser", "onShowFileChooser: acceptTypes=${fileChooserParams?.acceptTypes?.joinToString()}, mode=${fileChooserParams?.mode}")
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                val intent = fileChooserParams?.createIntent() ?: android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                Log.d("FileChooser", "Launching file chooser intent: ${intent.type}")
                fileChooserLauncher.launch(intent)
                return true
            }
        }
    }

    // ── onCreate 분해 헬퍼: AuthBridge + DocumentStart script ──
    private fun setupAuthBridge() {
        // addDocumentStartJavaScript: 모든 페이지 스크립트보다 먼저 실행 보장
        // clearAuthOnNextLoad 플래그가 true이면 localStorage isLoggedIn 제거 (로그인 화면 강제 표시)
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun shouldClearAuth(): Boolean {
                val result = clearAuthOnNextLoad
                if (result) clearAuthOnNextLoad = false
                return result
            }
        }, "_AuthBridge")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                "(function(){try{var has=typeof window._AuthBridge!=='undefined';var should=has&&window._AuthBridge.shouldClearAuth();console.log('[_Auth] bridge='+has+' shouldClear='+should+' nx_auth='+localStorage.getItem('nx_auth'));if(should){localStorage.removeItem('nx_auth');localStorage.removeItem('isLoggedIn');localStorage.removeItem('currentUser');console.log('[_Auth] cleared');}}catch(e){console.log('[_Auth] error: '+e);}})();",
                setOf("*")
            )
        }
    }

    // ── onCreate 분해 헬퍼: 뒤로가기 처리 ──
    private fun setupBackPressed() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 서브 WebView가 열려있으면 닫기
                if (subWebView.visibility == android.view.View.VISIBLE) {
                    closeSubWebView()
                    backPressedTime = 0L
                    return
                }

                // React에 backPressed 전달
                // webView.goBack()/history.back() 사용 안함: SPA에서 URL 히스토리 되돌리면 테마/상태가 원복되는 문제
                // React가 window._onBackPressed()를 정의: true=SPA 내부에서 처리됨, false=루트 화면
                // 미정의 시 기존 동작 (항상 종료 토스트)
                webView.evaluateJavascript(
                    "(function() { if (typeof window._onBackPressed === 'function') return window._onBackPressed(); return false; })()"
                ) { result ->
                    runOnUiThread {
                        if (result == "true") {
                            backPressedTime = 0L
                        } else {
                            bridgeDispatcher.notifyReactOnce("backPressed")
                            val now = System.currentTimeMillis()
                            if (now - backPressedTime < 1500L) {
                                finish()
                            } else {
                                backPressedTime = now
                                Toast.makeText(this@MainActivity, "한번 더 누르면 종료됩니다", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        })
    }

    // ── onCreate 분해 헬퍼: Subscribe(Presence) 응답 관찰 ──
    private fun observeSubscribeResponse() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                loginViewModel.subscribeResponse.collect { jsonStr ->
                    // icon(PC) / mobileIcon 병합 → 최종 icon (React BuddyPage 렌더용)
                    // 본인 userId이면 로컬 저장 상태로 덮어씀 (서버가 0으로 내려줘도 유지)
                    val myUserId = appConfig.getSavedUserId() ?: ""
                    val myStatusCode = appConfig.getMyStatusCode()
                    val myNick = appConfig.getMyNick()
                    val transformed = try {
                        val json = org.json.JSONObject(jsonStr)
                        val users = json.optJSONArray("users")
                        if (users != null) {
                            for (i in 0 until users.length()) {
                                val u = users.getJSONObject(i)
                                val mobileIcon = u.optInt("mobileIcon", 0)
                                val pcIcon = u.optInt("icon", 0)
                                val isMe = u.optString("userId") == myUserId
                                // mobileIcon = 모바일 기기 연결 여부 플래그 (1=접속중, 0=미접속) — 상태 코드가 아님
                                // icon      = 사용자가 명시적으로 설정한 상태 (자리비움=2, 다른용무중=3 등)
                                // 본인: 서버 icon(명시적 상태) 우선 → 서버가 0이면 로컬 저장값으로 복원
                                // 타인: mobileIcon(모바일 접속중) > 0 이면 mobileIcon, 아니면 pcIcon
                                val icon = if (isMe) {
                                    when {
                                        pcIcon > 0 -> pcIcon
                                        myStatusCode > 0 -> myStatusCode
                                        else -> 0
                                    }
                                } else {
                                    if (mobileIcon > 0) mobileIcon else pcIcon
                                }
                                u.put("icon", icon)
                                if (isMe) {
                                    val serverNick = u.optString("nick")
                                    if (myNick.isNotEmpty()) {
                                        // 로컬 저장값 우선: REST setNick은 서버 presence 캐시를 즉시 갱신하지 않으므로
                                        // subscribe 응답의 서버 nick이 구버전일 수 있음. 실시간 변경은 PUBLISH Nick으로 수신.
                                        u.put("nick", myNick)
                                    } else if (serverNick.isNotEmpty()) {
                                        // 로컬이 비어있을 때만 서버값 채택 (초기 로그인 등)
                                        appConfig.saveMyNick(serverNick)
                                    }
                                }
                            }
                        }
                        json.toString()
                    } catch (_: Exception) { jsonStr }
                    Log.d("Presence", "[4] subscribe→React: $transformed")
                    webView.post {
                        webView.evaluateJavascript(
                            "window._onPresenceUpdate($transformed)",
                            null
                        )
                    }
                    // _autoLoginResolve 이후 타이밍 보장: subscribe 응답 후 저장된 내 사진 버전 주입
                    val myPhotoVersion = appConfig.getMyPhotoVersion()
                    if (myPhotoVersion > 0 && myUserId.isNotEmpty()) {
                        val photoUrl = "/photo/$myUserId?v=$myPhotoVersion"
                        val photoJson = """{"users":[{"userId":"$myUserId","command":"Photo","photoUrl":"$photoUrl","photoVersion":$myPhotoVersion}]}"""
                        Log.d("Presence", "[restore] photo after subscribe: $photoJson")
                        webView.post {
                            webView.evaluateJavascript(
                                "window._onPresenceUpdate && window._onPresenceUpdate('${net.spacenx.messenger.common.JsEscapeUtil.escapeForJs(photoJson)}')",
                                null
                            )
                        }
                    }
                }
            }
        }
    }
}
