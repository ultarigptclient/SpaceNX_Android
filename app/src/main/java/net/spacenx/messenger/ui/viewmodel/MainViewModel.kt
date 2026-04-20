package net.spacenx.messenger.ui.viewmodel

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * MainActivity 상태 및 순수 로직을 분리한 ViewModel.
 * WebView·Activity API가 필요한 코드는 MainActivity에 유지.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    // ── 로그인/세션 상태 ──
    var isLoggedIn = false
    var isAutoLogin = false
    /** 선제적 autoLogin 결과 보관 (WebView 로드 전에 Authenticated 도달한 경우) */
    var pendingAuthJson: String? = null
    /** hardReload 시 즉시 resolve용 — 마지막 Authenticated userJson 캐시 */
    var lastAuthJson: String? = null
    var isForegroundResume = false
    var isLogoutRequested = false
    private var loginCompletedAt = 0L

    // ── 앱/UI 상태 ──
    var isAppInForeground = false
    var backPressedTime = 0L
    var overlayPermissionPopup = false
    /** 파일 피커 진행 중 플래그 — 스킨 리로드 억제용 */
    @Volatile var isPickingFile = false

    fun setLoginCompleted() {
        loginCompletedAt = System.currentTimeMillis()
        Log.d(TAG, "loginCompleted at $loginCompletedAt")
    }

    // ── 권한 ──

    fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.CHANGE_NETWORK_STATE,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return permissions.toTypedArray()
    }

    fun needPermissionCheck(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "need POST_NOTIFICATIONS")
                return true
            }
        }
        for (permission in getRequiredPermissions()) {
            if (ActivityCompat.checkSelfPermission(
                    context, permission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "needPermission : $permission")
                return true
            }
        }
        return false
    }

    // ── 콘텐츠 전처리 ──

    fun preprocessContent(content: String): String {
        return when {
            content.startsWith("ATTACH:/") || content.startsWith("FILE://") -> "첨부 파일"
            content.contains("<img") -> content.replace(Regex("<[^>]*>"), "").trim().ifEmpty { "[이미지]" }
            else -> content.replace(Regex("<[^>]*>"), "").trim()
        }
    }
}
