package net.spacenx.messenger.ui

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.spacenx.messenger.data.remote.api.ApiClient
import net.spacenx.messenger.data.remote.api.dto.UpdateInfo

class AppUpdateChecker(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "AppUpdateChecker"
    }

    fun check(url: String?) {
        if (url == null) return
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
        android.app.AlertDialog.Builder(activity)
            .setTitle("업데이트 필요")
            .setMessage("새 버전($serverVersion)이 있습니다.\n앱을 업데이트해야 계속 사용할 수 있습니다.")
            .setCancelable(false)
            .setPositiveButton("업데이트") { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open update URL: ${e.message}")
                }
                activity.finish()
            }
            .setNegativeButton("종료") { _, _ -> activity.finish() }
            .create()
            .show()
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
