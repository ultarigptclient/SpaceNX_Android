package net.spacenx.messenger

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.data.local.DatabaseProvider
import net.spacenx.messenger.service.push.HybridWebMessengerFirebaseMessagingService
import net.spacenx.messenger.util.FileLogger
import javax.inject.Inject

@HiltAndroidApp
class HybridWebMessengerApp : Application() {

    companion object {
        private const val TAG = "HybridWebMessengerApp"
    }

    @Inject lateinit var databaseProvider: DatabaseProvider
    @Inject lateinit var appConfig: AppConfig

    override fun onCreate() {
        super.onCreate()

        // 파일 로거 초기화 (저장된 설정 복원)
        FileLogger.init(this)

        // 크래시 시 로그 파일 flush — 프로세스 강제 종료 직전 큐 잔여분 보존
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                FileLogger.log("CRASH", "${throwable.javaClass.name}: ${throwable.message}")
                // stacktrace 상위 10줄만 기록 (파일 용량 제한)
                throwable.stackTrace.take(10).forEach { frame ->
                    FileLogger.log("CRASH", "  at $frame")
                }
                FileLogger.flush()
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // SQLCipher passphrase 사전 계산 (첫 DB 접근 지연 방지)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                databaseProvider.warmUpPassphrase()
                Log.d(TAG, "SQLCipher passphrase warmed up")
            } catch (e: Exception) {
                Log.w(TAG, "SQLCipher warmup failed: ${e.message}")
            }
        }

        // FCM 토큰 사전 조회
        HybridWebMessengerFirebaseMessagingService.getToken(this) { token, isRefresh ->
            Log.d(TAG, "FCM token ready (refresh=$isRefresh): $token")
        }

        // pickFile 임시 파일 청소 — 24시간 이상된 잔존물 제거.
        // 업로드 중 앱 강제종료 시 cacheDir/pickedFiles 에 남은 tmp-* 파일이 누적되면
        // 저장 공간이 천천히 차오르며 후속 업로드도 실패 가능.
        CoroutineScope(Dispatchers.IO).launch {
            try { purgeStalePickedFiles() } catch (e: Exception) { Log.w(TAG, "purgeStalePickedFiles: ${e.message}") }
        }

        Log.d(TAG, "Application created")
    }

    private fun purgeStalePickedFiles() {
        val tempDir = java.io.File(cacheDir, "pickedFiles")
        if (!tempDir.isDirectory) return
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        var deleted = 0
        var totalBytesFreed = 0L
        tempDir.listFiles()?.forEach { f ->
            try {
                if (f.lastModified() < cutoff) {
                    val sz = f.length()
                    if (f.delete()) {
                        deleted++
                        totalBytesFreed += sz
                    }
                }
            } catch (_: Exception) {}
        }
        if (deleted > 0) {
            Log.d(TAG, "purgeStalePickedFiles: removed $deleted files (${totalBytesFreed / 1024}KB)")
        }
    }
}
