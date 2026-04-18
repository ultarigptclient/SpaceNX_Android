package net.spacenx.messenger.ui

import android.app.AlertDialog
import android.content.Intent
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
import net.spacenx.messenger.R
import net.spacenx.messenger.data.remote.api.ApiClient
import net.spacenx.messenger.data.remote.api.dto.UpdateInfo
import java.io.File
import java.net.URL

class AppUpdateChecker(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "AppUpdateChecker"
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
