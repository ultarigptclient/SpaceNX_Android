package net.spacenx.messenger.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.File

object WebFileManager {

    private const val TAG = "WebFileManager"
    private const val WEB_DIR = "dist"
    private const val PREF_NAME = "web_file_manager"
    private const val KEY_COPIED_VERSION = "copied_version"

    fun getWebBaseDir(context: Context): File {
        return File(context.filesDir, WEB_DIR)
    }

    private fun isDebugBuild(context: Context): Boolean {
        return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    fun copyAssetsToInternalIfNeeded(context: Context) {
        val debug = isDebugBuild(context)
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val currentVersion = getAppVersionCode(context)
        val copiedVersion = prefs.getLong(KEY_COPIED_VERSION, -1)

        if (!debug && copiedVersion == currentVersion) {
            Log.d(TAG, "Web assets already copied for version $currentVersion")
            return
        }

        if (debug) {
            Log.d(TAG, "Debug build: always re-copying web assets")
        }

        val destDir = getWebBaseDir(context)
        if (destDir.exists()) {
            destDir.deleteRecursively()
        }

        copyAssetDir(context, WEB_DIR, destDir)

        prefs.edit().putLong(KEY_COPIED_VERSION, currentVersion).apply()
        Log.d(TAG, "Web assets copied to ${destDir.absolutePath}")
    }

    private fun copyAssetDir(context: Context, assetPath: String, destDir: File) {
        val assetManager = context.assets
        val entries = assetManager.list(assetPath) ?: return

        destDir.mkdirs()

        for (entry in entries) {
            val assetEntryPath = "$assetPath/$entry"
            val destFile = File(destDir, entry)

            val subEntries = assetManager.list(assetEntryPath)
            if (!subEntries.isNullOrEmpty()) {
                copyAssetDir(context, assetEntryPath, destFile)
            } else {
                assetManager.open(assetEntryPath).use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun getAppVersionCode(context: Context): Long {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return packageInfo.longVersionCode
    }
}
