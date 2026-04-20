package net.spacenx.messenger.ui

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.spacenx.messenger.BuildConfig
import net.spacenx.messenger.R
import net.spacenx.messenger.data.remote.api.ApiClient
import net.spacenx.messenger.data.remote.api.dto.UpdateInfo
import java.io.File
import java.net.URL
import java.security.MessageDigest

class AppUpdateChecker(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "AppUpdateChecker"

        /**
         * 업데이트 APK 다운로드를 허용할 호스트 목록.
         * 정확 매칭만 허용. 새 호스트 추가 시 코드 변경 + 앱 업데이트 필요.
         */
        //최종 apk 릴리즈시 확인 필수!
        private val ALLOWED_UPDATE_HOSTS = setOf(
            "www.ultari.co.kr",
            "neo.ultari.co.kr"
        )
    }

    private var pendingDownloadUrl: String? = null
    private var downloadJob: Job? = null
    private var lastCheckTime = 0L
    private val checkIntervalMs = 10 * 1000L
    private var isDialogShowing = false

    private val installPermissionLauncher = activity.activityResultRegistry.register(
        "install_permission_check",
        activity,
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        val url = pendingDownloadUrl ?: return@register
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            activity.packageManager.canRequestPackageInstalls()
        ) {
            startDownload(url)
        }
    }

    fun check(url: String?) {
        if (url == null) return
        if (isDialogShowing) return
        val now = System.currentTimeMillis()
        if (now - lastCheckTime < checkIntervalMs) return
        lastCheckTime = now
        scope.launch {
            try {
                val response = ApiClient.updateApi.checkUpdate(url)
                if (!response.isSuccessful) {
                    Log.e(TAG, "Update check failed: HTTP ${response.code()}")
                    return@launch
                }
                val body = response.body()
                if (body.isNullOrBlank()) {
                    Log.e(TAG, "Update check: empty response")
                    return@launch
                }
                val updateInfo = parseUpdateResponse(body) ?: run {
                    Log.e(TAG, "Update check: failed to parse response")
                    return@launch
                }
                Log.d(TAG, "Update check server version=${updateInfo.version}, url=${updateInfo.downloadUrl}")
                val currentVersion = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: ""
                if (updateInfo.version != currentVersion) {
                    Log.d(TAG, "Update available: $currentVersion → ${updateInfo.version}")
                    activity.runOnUiThread { showForceUpdateDialog(updateInfo.version, updateInfo.downloadUrl) }
                } else {
                    Log.d(TAG, "App is up to date: $currentVersion")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check error", e)
            }
        }
    }

    private fun showForceUpdateDialog(serverVersion: String, downloadUrl: String) {
        isDialogShowing = true
        AlertDialog.Builder(activity)
            .setTitle("업데이트 필요")
            .setMessage("새 버전($serverVersion)이 있습니다.\n앱을 업데이트해야 계속 사용할 수 있습니다.")
            .setCancelable(false)
            .setPositiveButton("업데이트") { _, _ ->
                isDialogShowing = false
                checkPermissionAndDownload(downloadUrl)
            }
            .setNegativeButton("종료") { _, _ ->
                isDialogShowing = false
                activity.finish()
            }
            .create()
            .show()
    }

    private fun checkPermissionAndDownload(downloadUrl: String) {
        if (!isUpdateUrlAllowed(downloadUrl)) {
            Log.e(TAG, "Refusing update from disallowed URL: $downloadUrl")
            AlertDialog.Builder(activity)
                .setTitle("업데이트 차단")
                .setMessage("업데이트 다운로드 주소가 허용 목록에 없어 차단되었습니다.\n관리자에게 문의해주세요.")
                .setPositiveButton("확인") { _, _ -> activity.finish() }
                .setCancelable(false)
                .show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            pendingDownloadUrl = downloadUrl
            AlertDialog.Builder(activity)
                .setTitle("설치 권한 필요")
                .setMessage("알 수 없는 출처의 앱 설치 권한이 필요합니다.\n설정에서 허용해 주세요.")
                .setCancelable(false)
                .setPositiveButton("설정으로 이동") { dialog, _ ->
                    dialog.dismiss()
                    installPermissionLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:${activity.packageName}")
                        }
                    )
                }
                .setNegativeButton("취소") { dialog, _ -> dialog.dismiss() }
                .create()
                .show()
        } else {
            startDownload(downloadUrl)
        }
    }

    private fun startDownload(downloadUrl: String) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_apk_download, null)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBarDownload)
        val tvPercent = view.findViewById<TextView>(R.id.tvPercent)
        val tvLabel = view.findViewById<TextView>(R.id.tvProgressLabel)
        val tvFileSize = view.findViewById<TextView>(R.id.tvFileSize)
        val btnCancel = view.findViewById<ImageButton>(R.id.btnCancelDownload)

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()

        btnCancel.setOnClickListener {
            downloadJob?.cancel()
            dialog.dismiss()
        }

        downloadJob = scope.launch(Dispatchers.IO) {
            try {
                val apkFile = File(activity.filesDir, "downloads/update.apk").also {
                    it.parentFile?.mkdirs()
                }
                val connection = URL(downloadUrl).openConnection()
                connection.connect()
                val fileSize = connection.contentLength
                if (fileSize > 0) {
                    val mb = "%.1f MB".format(fileSize / 1024f / 1024f)
                    withContext(Dispatchers.Main) { tvFileSize.text = mb }
                }

                connection.getInputStream().use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (fileSize > 0) {
                                val progress = (downloaded * 100 / fileSize).toInt()
                                val downloadedMb = "%.1f / %.1f MB".format(
                                    downloaded / 1024f / 1024f,
                                    fileSize / 1024f / 1024f
                                )
                                withContext(Dispatchers.Main) {
                                    progressBar.progress = progress
                                    tvPercent.text = "$progress%"
                                    tvLabel.text = downloadedMb
                                }
                            }
                        }
                    }
                }
                if (!verifyApkSignature(apkFile)) {
                    Log.e(TAG, "APK signature verification failed — refusing to install")
                    apkFile.delete()
                    withContext(Dispatchers.Main) {
                        dialog.dismiss()
                        AlertDialog.Builder(activity)
                            .setTitle("업데이트 검증 실패")
                            .setMessage("다운로드한 업데이트 파일의 서명이 일치하지 않습니다.\n변조 가능성이 있어 설치를 중단합니다.")
                            .setPositiveButton("확인") { _, _ -> activity.finish() }
                            .setCancelable(false)
                            .show()
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    installApk(apkFile)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Download cancelled by user")
                    return@launch
                }
                Log.e(TAG, "Download failed", e)
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    AlertDialog.Builder(activity)
                        .setTitle("다운로드 실패")
                        .setMessage("APK 다운로드 중 오류가 발생했습니다.\n${e.message}")
                        .setPositiveButton("확인", null)
                        .show()
                }
            }
        }
    }

    private fun installApk(apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setDataAndType(uri, "application/vnd.android.package-archive")
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
        }
    }

    /**
     * 서버가 내려준 다운로드 URL 이 HTTPS 이고 호스트 allowlist 안에 들어있는지 확인.
     * 부분 매칭(*.ultari.co.kr 같은 suffix) 은 의도적으로 허용하지 않음 — 명시 등록만.
     */
    private fun isUpdateUrlAllowed(downloadUrl: String): Boolean {
        return try {
            val url = URL(downloadUrl)
            val schemeOk = url.protocol.equals("https", ignoreCase = true)
            val hostOk = url.host in ALLOWED_UPDATE_HOSTS
            if (!schemeOk) Log.w(TAG, "Update URL scheme not HTTPS: ${url.protocol}")
            if (!hostOk) Log.w(TAG, "Update URL host not in allowlist: ${url.host}")
            schemeOk && hostOk
        } catch (e: Exception) {
            Log.e(TAG, "Invalid update URL: $downloadUrl", e)
            false
        }
    }

    /**
     * 다운로드한 APK 의 서명 인증서 SHA-256 이 BuildConfig.APP_SIGNING_SHA256 과 일치하는지 검증.
     * 일치하지 않으면 변조된 APK 일 가능성이 높으므로 설치를 중단해야 함.
     *
     * 디버그 빌드는 키스토어가 다르므로 건너뜀 (개발 편의).
     */
    private fun verifyApkSignature(apkFile: File): Boolean {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Skipping APK signature check in DEBUG build")
            return true
        }
        return try {
            val pm = activity.packageManager
            val expected = BuildConfig.APP_SIGNING_SHA256.uppercase()
            if (expected.isBlank()) {
                Log.e(TAG, "APP_SIGNING_SHA256 is empty — refusing to install")
                return false
            }
            val signatures: Array<android.content.pm.Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.GET_SIGNING_CERTIFICATES
                ) ?: return false
                val signingInfo = info.signingInfo ?: return false
                if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
                else signingInfo.signingCertificateHistory
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.GET_SIGNATURES
                ) ?: return false
                @Suppress("DEPRECATION")
                info.signatures ?: return false
            }
            val md = MessageDigest.getInstance("SHA-256")
            val matched = signatures.any { sig ->
                val actual = md.digest(sig.toByteArray()).joinToString("") { "%02X".format(it) }
                Log.d(TAG, "APK signature SHA256 candidate: $actual")
                actual == expected
            }
            if (!matched) Log.e(TAG, "No signature matched expected SHA256: $expected")
            matched
        } catch (e: Exception) {
            Log.e(TAG, "verifyApkSignature failed", e)
            false
        }
    }

    private fun parseUpdateResponse(body: String): UpdateInfo? {
        var version: String? = null
        var downloadUrl: String? = null
        for (line in body.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("VERSION:") -> version = trimmed.removePrefix("VERSION:")
                trimmed.startsWith("URL:") -> downloadUrl = trimmed.removePrefix("URL:")
            }
        }
        return if (version != null && downloadUrl != null) UpdateInfo(version, downloadUrl) else null
    }
}
