package net.spacenx.messenger.ui.bridge.handler

import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.spacenx.messenger.data.remote.api.ApiClient
import net.spacenx.messenger.ui.bridge.BridgeContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class FileHandler(
    private val ctx: BridgeContext
) {
    companion object {
        private const val TAG = "FileHandler"

        // ── 청크 업로드 상수 (FilePage/ChunkUploader와 동일) ──
        private const val CHUNK_MIN = 5L * 1024 * 1024       // 5 MB
        private const val CHUNK_MAX = 40L * 1024 * 1024      // 40 MB (게이트웨이 50MB 이내)
        private const val CHUNK_THRESHOLD = 30L * 1024 * 1024 // 30 MB 이상이면 청크 모드
        private const val CHUNK_TARGET = 10                   // 목표 청크 수
        private const val PARALLEL_CHUNKS = 3                 // 동시 업로드 청크 수

        private fun calcChunkSize(fileSize: Long): Int {
            val ideal = ((fileSize + CHUNK_TARGET - 1) / CHUNK_TARGET)
            return ideal.coerceIn(CHUNK_MIN, CHUNK_MAX).toInt()
        }

        // ── Filebox 백그라운드 세션 관리 (모바일 전용) ──
        // 청크 업로드는 여러 Call을 가질 수 있고 서버 sessionId 도 추적해야 하므로 UploadSession 으로 래핑.
        private val activeUploads = ConcurrentHashMap<String, UploadSession>()
        private val activeDownloads = ConcurrentHashMap<String, Call>()
    }

    /** 업로드 세션 — 단일(Call 1개) + 청크(Call N개 + serverSessionId) 공통 */
    private class UploadSession {
        val calls: MutableList<Call> = java.util.Collections.synchronizedList(mutableListOf())
        @Volatile var serverSessionId: String? = null
        @Volatile var chunked: Boolean = false
    }

    suspend fun handle(action: String, params: Map<String, Any?>) {
        when (action) {
            "uploadFile" -> handleUploadFile(params)
            "pickFile" -> handlePickFile(params)
            "downloadFile" -> handleDownloadFile(params)
            "openFile" -> handleOpenFile(params)
            "relocateFiles" -> handleRelocateFiles(params)
            "fileUploadCancel" -> handleCancelUpload(params)
            "fileDownloadCancel" -> handleCancelDownload(params)
            // Pause/Resume은 모바일에서 단순 ack (백그라운드 세션 일시정지 미지원, 취소만 동작)
            "fileUploadPause", "fileUploadResume",
            "fileDownloadPause", "fileDownloadResume" -> {
                ctx.resolveToJs(action, JSONObject().put("errorCode", 0))
            }
            "previewFile" -> handlePreviewFile(params)
            "shareFile" -> handleShareFile(params)
        }
    }

    /** filebox 업로드 세션 취소 — 진행 중 Call 전부 취소 + 청크 모드면 서버 DELETE */
    private fun handleCancelUpload(params: Map<String, Any?>) {
        val sessionId = ctx.paramStr(params, "sessionId")
        if (sessionId.isEmpty()) {
            ctx.resolveToJs("fileUploadCancel", JSONObject().put("errorCode", 0))
            return
        }
        val session = activeUploads.remove(sessionId)
        if (session != null) {
            synchronized(session.calls) {
                session.calls.forEach { c -> try { if (!c.isCanceled()) c.cancel() } catch (_: Exception) {} }
            }
            if (session.chunked) {
                val srvSid = session.serverSessionId
                if (!srvSid.isNullOrEmpty()) {
                    // 서버 측 세션 정리 (best-effort, 비동기)
                    ctx.scope.launch {
                        try {
                            val token = ctx.loginViewModel.sessionManager.jwtToken
                            val url = ctx.appConfig.getEndpointByPath("/file/upload/cancel/$srvSid")
                            withContext(Dispatchers.IO) { httpDelete(url, token) }
                        } catch (e: Exception) {
                            Log.w(TAG, "cancel DELETE failed: ${e.message}")
                        }
                    }
                }
            }
            emitFileEvent("fileUploadFailed", JSONObject().put("sessionId", sessionId).put("reason", "cancelled"))
        }
        ctx.resolveToJs("fileUploadCancel", JSONObject().put("errorCode", 0).put("sessionId", sessionId))
    }

    /** filebox 다운로드 세션 취소 */
    private fun handleCancelDownload(params: Map<String, Any?>) {
        val sessionId = ctx.paramStr(params, "sessionId")
        if (sessionId.isEmpty()) {
            ctx.resolveToJs("fileDownloadCancel", JSONObject().put("errorCode", 0))
            return
        }
        val call = activeDownloads.remove(sessionId)
        if (call != null && !call.isCanceled()) {
            try { call.cancel() } catch (_: Exception) {}
            emitFileEvent("fileDownloadFailed", JSONObject().put("sessionId", sessionId).put("reason", "cancelled"))
        }
        ctx.resolveToJs("fileDownloadCancel", JSONObject().put("errorCode", 0).put("sessionId", sessionId))
    }

    /** 간단한 DELETE 요청 (best-effort) */
    private fun httpDelete(url: String, token: String?) {
        val reqBuilder = okhttp3.Request.Builder().url(url).delete()
        if (!token.isNullOrEmpty()) reqBuilder.addHeader("Authorization", "Bearer $token")
        ApiClient.okHttpClient.newCall(reqBuilder.build()).execute().close()
    }

    /** WebView로 filebox 세션 이벤트 전송 (window.postMessage) */
    private fun emitFileEvent(event: String, data: JSONObject) {
        val payload = JSONObject(data.toString()).put("event", event)
        ctx.evalJsMain("window.postMessage('${ctx.esc(payload.toString())}')")
    }

    private fun newSessionId(prefix: String): String =
        "$prefix-${System.currentTimeMillis()}-${(Math.random() * 1_000_000).toInt()}"

    private suspend fun handleUploadFile(params: Map<String, Any?>) {
        // bridge.js의 `nativeSend('uploadFile', ..., { callbackId: 'uploadFile_N' })`에서 붙인 _callbackId 필수 echo
        val cbId = ctx.paramStr(params, "_callbackId").ifEmpty { "uploadFile" }
        val context = ctx.paramStr(params, "context")
        val nativeTempId = ctx.paramStr(params, "nativeTempId")
        Log.d(TAG, "uploadFile: cbId=$cbId, context='$context', nativeTempId='$nativeTempId', keys=${params.keys}")
        // filebox 컨텍스트면 백그라운드 세션 흐름 (sessionId + Started/Progress/Completed/Failed 이벤트)
        if (context == "filebox") {
            handleFileboxUpload(params, cbId)
            return
        }
        // chat 컨텍스트: pickFile이 발급한 nativeTempId가 있으면 임시 파일을 stream upload
        if (nativeTempId.isNotEmpty()) {
            handleChatUploadByTempId(nativeTempId, cbId)
            return
        }
        // 기본: base64 멀티파트 (legacy fallback)
        try {
            val uploadUrl = ctx.appConfig.getEndpointByPath("/media/file/upload")
            val token = ctx.loginViewModel.sessionManager.jwtToken
            val result = withContext(Dispatchers.IO) {
                ApiClient.uploadFile(
                    ctx.paramStr(params, "fileName"),
                    ctx.paramStr(params, "mimeType").ifEmpty { "application/octet-stream" },
                    ctx.paramStr(params, "base64"),
                    uploadUrl,
                    token
                )
            }
            Log.d(TAG, "uploadFile result: $result")
            ctx.resolveToJs(cbId, result)
        } catch (e: Exception) {
            Log.e(TAG, "uploadFile error: ${e.message}", e)
            ctx.rejectToJs(cbId, e.message)
        }
    }

    /**
     * Filebox 업로드: pickFile에서 받은 nativeTempId로 식별된 임시 파일을
     * /media/filebox/upload(parentFolderCode)로 전송. sessionId 발급 + 진행률/완료 이벤트.
     */
    private suspend fun handleFileboxUpload(params: Map<String, Any?>, cbId: String) {
        val nativeTempId = ctx.paramStr(params, "nativeTempId")
        val parentFolderCode = ctx.paramStr(params, "parentFolderCode")
        val sessionId = newSessionId("up")
        Log.d(TAG, "fileboxUpload start: sessionId=$sessionId, parentFolderCode='$parentFolderCode', nativeTempId='$nativeTempId'")
        try {
            val tempDir = java.io.File(ctx.activity.cacheDir, "pickedFiles")
            val tempFile = java.io.File(tempDir, nativeTempId)
            if (nativeTempId.isEmpty() || !tempFile.exists()) {
                Log.w(TAG, "fileboxUpload: temp not found — dir=${tempDir.absolutePath} exists=${tempDir.exists()}, file=$nativeTempId exists=${tempFile.exists()}")
                ctx.rejectToJs(cbId, "Temp file not found: $nativeTempId")
                return
            }
            val fileName = tempFile.name.substringAfter('_', tempFile.name)
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(java.io.File(fileName).extension) ?: "application/octet-stream"
            val token = ctx.loginViewModel.sessionManager.jwtToken
            val userId = ctx.appConfig.getSavedUserId() ?: ""
            val fileSize = tempFile.length()
            val chunked = fileSize >= CHUNK_THRESHOLD
            Log.d(TAG, "fileboxUpload: file=$fileName, size=$fileSize, mime=$mime, userId=$userId, chunked=$chunked, tokenLen=${token?.length ?: 0}")

            // 세션 미리 등록 (cancel 대응)
            val session = UploadSession().also { it.chunked = chunked }
            activeUploads[sessionId] = session

            emitFileEvent("fileUploadStarted", JSONObject()
                .put("sessionId", sessionId)
                .put("fileName", fileName)
                .put("size", fileSize)
                .put("chunked", chunked))

            // 즉시 ack — 실제 결과는 push 이벤트로
            ctx.resolveToJs(cbId, JSONObject()
                .put("errorCode", 0)
                .put("sessionId", sessionId)
                .put("fileName", fileName))

            withContext(Dispatchers.IO) {
                try {
                    val result = if (chunked) {
                        uploadFileboxChunked(
                            tempFile, fileName, mime, token, sessionId, session,
                            userId, parentFolderCode
                        )
                    } else {
                        val uploadUrl = ctx.appConfig.getEndpointByPath("/file/upload")
                        uploadFileboxStreaming(
                            tempFile, fileName, mime, uploadUrl, token, sessionId, session,
                            userId, parentFolderCode
                        )
                    }
                    Log.d(TAG, "fileboxUpload completed: sessionId=$sessionId, chunked=$chunked, result=$result")
                    emitFileEvent("fileUploadCompleted", JSONObject()
                        .put("sessionId", sessionId)
                        .put("fileName", fileName)
                        .put("result", result))
                } catch (e: Exception) {
                    val cancelled = e is java.io.IOException && e.message?.contains("Canceled", true) == true
                    Log.w(TAG, "fileboxUpload failed: sessionId=$sessionId, cancelled=$cancelled, err=${e.message}", e)
                    if (!cancelled) {
                        emitFileEvent("fileUploadFailed", JSONObject()
                            .put("sessionId", sessionId)
                            .put("fileName", fileName)
                            .put("reason", e.message ?: "upload failed"))
                    }
                } finally {
                    activeUploads.remove(sessionId)
                    try { tempFile.delete() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fileboxUpload outer error: ${e.message}", e)
            ctx.rejectToJs(cbId, e.message)
        }
    }

    /** OkHttp Call로 filebox 단일 업로드 (< 30MB). session에 Call 등록하여 cancel 가능 */
    private fun uploadFileboxStreaming(
        file: java.io.File, fileName: String, mime: String,
        uploadUrl: String, token: String?, sessionId: String, session: UploadSession,
        userId: String, parentFolderCode: String
    ): JSONObject {
        val mediaType = mime.toMediaTypeOrNull()
        val total = file.length()
        val fileBody = object : okhttp3.RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength() = if (total > 0) total else -1L
            override fun writeTo(sink: okio.BufferedSink) {
                val buffer = ByteArray(16 * 1024)
                var sent = 0L
                var lastEmit = 0
                file.inputStream().use { stream ->
                    var read: Int
                    while (stream.read(buffer).also { read = it } != -1) {
                        sink.write(buffer, 0, read)
                        sent += read
                        val pct = if (total > 0) (sent * 100 / total).toInt() else 0
                        if (pct != lastEmit) {
                            lastEmit = pct
                            // FilePage 가 구독하는 필드명 (percent/bytesSent/totalBytes). 레거시 키도 함께 유지.
                            emitFileEvent("fileUploadProgress", JSONObject()
                                .put("sessionId", sessionId)
                                .put("fileName", fileName)
                                .put("percent", pct)
                                .put("bytesSent", sent)
                                .put("totalBytes", total)
                                .put("progress", pct)
                                .put("sent", sent)
                                .put("total", total))
                        }
                    }
                }
            }
        }
        val multipartBuilder = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("file", fileName, fileBody)
        if (userId.isNotEmpty()) {
            multipartBuilder.addFormDataPart("userId", userId)
        }
        if (parentFolderCode.isNotEmpty()) {
            multipartBuilder.addFormDataPart("parentFolderCode", parentFolderCode)
        }
        val multipart = multipartBuilder.build()
        val reqBuilder = okhttp3.Request.Builder().url(uploadUrl).post(multipart)
        if (!token.isNullOrEmpty()) reqBuilder.addHeader("Authorization", "Bearer $token")
        val call = ApiClient.okHttpClient.newCall(reqBuilder.build())
        session.calls.add(call)
        Log.d(TAG, "uploadFileboxStreaming: POST $uploadUrl (session=$sessionId)")
        val response = call.execute()
        val raw = response.body?.string() ?: "{}"
        Log.d(TAG, "uploadFileboxStreaming: status=${response.code}, bodyLen=${raw.length}, preview=${raw.take(200)}")
        if (!response.isSuccessful) {
            throw java.io.IOException("HTTP ${response.code}: ${raw.take(200)}")
        }
        return try { JSONObject(raw) } catch (_: Exception) { JSONObject().put("raw", raw) }
    }

    /**
     * 파일 선택 후 임시 파일에 복사 → nativeTempId 발급.
     * 실제 업로드는 후속 uploadFile(nativeTempId) 또는 fileboxUpload(nativeTempId, parentFolderCode)에서 수행.
     * Flutter/iOS 클라이언트와 동일한 두-단계 흐름.
     */
    private suspend fun handlePickFile(params: Map<String, Any?>) {
        try {
            val multiple = ctx.paramBool(params, "multiple")
            Log.d(TAG, "pickFile: multiple=$multiple")
            ctx.activity.isPickingFile = true
            val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                if (multiple) putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            ctx.activity.pendingPickFileCallback = { uris ->
                ctx.scope.launch {
                    try {
                        val files = mutableListOf<Map<String, Any?>>()
                        val tempDir = java.io.File(ctx.activity.cacheDir, "pickedFiles").apply { mkdirs() }
                        Log.d(TAG, "pickFile: ${uris.size} files selected")
                        for (uri in uris) {
                            try {
                                val cursor = ctx.activity.contentResolver.query(uri, null, null, null, null)
                                var fileName = "file"
                                var fileSize = 0L
                                cursor?.use {
                                    if (it.moveToFirst()) {
                                        val nameIdx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                        val sizeIdx = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                                        if (nameIdx >= 0) fileName = it.getString(nameIdx) ?: "file"
                                        if (sizeIdx >= 0) fileSize = it.getLong(sizeIdx)
                                    }
                                }
                                val mime = ctx.activity.contentResolver.getType(uri) ?: "application/octet-stream"
                                val nativeTempId = newSessionId("tmp") + "_" + fileName.replace(Regex("[/\\\\]"), "_")
                                val tempFile = java.io.File(tempDir, nativeTempId)
                                withContext(Dispatchers.IO) {
                                    ctx.activity.contentResolver.openInputStream(uri)?.use { input ->
                                        java.io.FileOutputStream(tempFile).use { out -> input.copyTo(out) }
                                    } ?: throw Exception("Cannot open input stream for $uri")
                                }
                                Log.d(TAG, "pickFile: cached $fileName (${tempFile.length()}B, $mime) → $nativeTempId")
                                // Flutter/iOS 호환: filebox(FilePage)는 `_nativeTempId`(언더스코어) 참조, bridge.js chat uploadFile은 `nativeTempId`. 두 키 모두 제공.
                                files.add(mapOf(
                                    "fileName" to fileName,
                                    "fileSize" to (if (fileSize > 0) fileSize else tempFile.length()),
                                    "mimeType" to mime,
                                    "nativeTempId" to nativeTempId,
                                    "_nativeTempId" to nativeTempId
                                ))
                            } catch (e: Exception) {
                                Log.w(TAG, "pickFile cache failed: ${e.message}")
                            }
                        }
                        ctx.resolveToJs("pickFile", JSONObject().put("errorCode", 0).put("files", JSONArray().apply {
                            files.forEach { put(JSONObject(it)) }
                        }))
                    } catch (e: Exception) {
                        ctx.rejectToJs("pickFile", e.message)
                    } finally {
                        ctx.activity.isPickingFile = false
                    }
                }
            }
            ctx.activity.pickFileLauncher.launch(intent)
        } catch (e: Exception) {
            ctx.rejectToJs("pickFile", e.message)
        }
    }

    /**
     * chat 첨부용 업로드 — pickFile이 cacheDir에 저장한 임시 파일을 /media/file/upload로 stream upload.
     * 진행률은 window.postMessage('uploadProgress') 이벤트로 emit.
     */
    private suspend fun handleChatUploadByTempId(nativeTempId: String, cbId: String) {
        try {
            val tempDir = java.io.File(ctx.activity.cacheDir, "pickedFiles")
            val tempFile = java.io.File(tempDir, nativeTempId)
            if (!tempFile.exists()) {
                ctx.rejectToJs(cbId, "Temp file not found: $nativeTempId")
                return
            }
            val fileName = tempFile.name.substringAfter('_', tempFile.name)
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(java.io.File(fileName).extension) ?: "application/octet-stream"
            val uploadUrl = ctx.appConfig.getEndpointByPath("/media/file/upload")
            val token = ctx.loginViewModel.sessionManager.jwtToken
            val result = withContext(Dispatchers.IO) {
                ApiClient.uploadFileStream(tempFile, fileName, mime, uploadUrl, token) { sent, total ->
                    val pct = if (total > 0) (sent * 100 / total).toInt() else 0
                    val progressJson = JSONObject()
                        .put("event", "uploadProgress")
                        .put("progress", pct)
                        .put("fileName", fileName)
                    ctx.evalJsMain("window.postMessage('${ctx.esc(progressJson.toString())}')")
                }
            }
            val uploadedUrl = result.optString("url", result.optString("fileUrl", ""))
            val fileId = result.optString("fileId", "")
            Log.d(TAG, "chatUpload result: file=$fileName, size=${tempFile.length()}, url='$uploadedUrl', fileId='$fileId', keys=${result.keys().asSequence().toList()}, raw=${result.toString().take(400)}")
            ctx.resolveToJs(cbId, JSONObject()
                .put("errorCode", 0)
                .put("fileName", fileName)
                .put("fileSize", tempFile.length())
                .put("url", uploadedUrl)
                .put("fileId", fileId))
            try { tempFile.delete() } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "uploadFile(tempId) error: ${e.message}", e)
            ctx.rejectToJs(cbId, e.message)
        }
    }

    private suspend fun handleDownloadFile(params: Map<String, Any?>) {
        // bridge.js가 `callbackId: downloadFile_N` 으로 보내므로 _callbackId echo
        val cbId = ctx.paramStr(params, "_callbackId").ifEmpty { "downloadFile" }
        // filebox 다운로드는 백그라운드 세션 흐름 (sessionId + Started/Progress/Completed/Failed)
        if (ctx.paramStr(params, "context") == "filebox") {
            handleFileboxDownload(params, cbId)
            return
        }
        try {
            val url = ctx.paramStr(params, "url")
            val rawName = ctx.paramStr(params, "fileName").ifEmpty { "download" }
            val fileName = rawName.replace(Regex("[/\\\\]"), "_").replace("..", "")
            if (url.isEmpty()) {
                ctx.rejectToJs(cbId, "No URL")
                return
            }
            // Flutter 패턴: baseUrl에서 /api 제거 후 origin + url (getEndpointByPath는 /api/ 추가하므로 사용 안함)
            val fullUrl = if (url.startsWith("http")) url else {
                val origin = ctx.appConfig.getRestBaseUrl().replace(Regex("/api$"), "")
                "$origin$url"
            }
            val urlHash = url.hashCode().toUInt().toString(16)
            val dlDir = java.io.File(ctx.activity.filesDir, "downloads/$urlHash")
            if (!dlDir.exists()) dlDir.mkdirs()
            val saveFile = java.io.File(dlDir, fileName).canonicalFile
            if (!saveFile.path.startsWith(dlDir.canonicalPath)) {
                ctx.rejectToJs(cbId, "Invalid filename")
                return
            }
            val savePath = saveFile.absolutePath
            if (saveFile.exists() && saveFile.length() > 0) {
                Log.d(TAG, "downloadFile cache hit: $fileName")
                ctx.resolveToJs(cbId, JSONObject().apply {
                    put("errorCode", 0)
                    put("path", savePath)
                    put("fileName", fileName)
                })
                return
            }
            withContext(Dispatchers.IO) {
                val token = ctx.loginViewModel.sessionManager.jwtToken
                ApiClient.downloadFile(fullUrl, savePath, token)
            }
            showDownloadCompleteNotification(fileName, savePath)
            ctx.resolveToJs(cbId, JSONObject().apply {
                put("errorCode", 0)
                put("path", savePath)
                put("fileName", fileName)
            })
        } catch (e: Exception) {
            ctx.rejectToJs(cbId, e.message)
        }
    }

    private fun showDownloadCompleteNotification(fileName: String, filePath: String) {
        try {
            val channelId = "download_complete"
            val nm = ctx.activity.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(channelId, "다운로드", android.app.NotificationManager.IMPORTANCE_LOW)
                nm.createNotificationChannel(channel)
            }
            val file = java.io.File(filePath)
            val authority = "${ctx.activity.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(ctx.activity, authority, file)
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension) ?: "application/octet-stream"
            val openIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                ctx.activity, filePath.hashCode(), openIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val notification = androidx.core.app.NotificationCompat.Builder(ctx.activity, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("다운로드 완료")
                .setContentText(fileName)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            nm.notify(filePath.hashCode(), notification)
        } catch (e: Exception) {
            Log.w(TAG, "showDownloadCompleteNotification error: ${e.message}")
        }
    }

    private fun handleOpenFile(params: Map<String, Any?>) {
        try {
            val path = ctx.paramStr(params, "path")
            if (path.isEmpty()) {
                ctx.rejectToJs("openFile", "No path")
                return
            }
            val file = java.io.File(path)
            val authority = "${ctx.activity.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(ctx.activity, authority, file)
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension) ?: "application/octet-stream"
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.activity.startActivity(intent)
            ctx.resolveToJs("openFile", JSONObject().put("errorCode", 0))
        } catch (e: Exception) {
            ctx.rejectToJs("openFile", e.message)
        }
    }

    /**
     * 다운로드된 파일을 context별 하위 디렉토리로 재배치 (Flutter 동일 패턴)
     * params: fileIds (List<String>), context (String)
     */
    private suspend fun handleRelocateFiles(params: Map<String, Any?>) {
        try {
            val fileIds = ctx.paramList(params, "fileIds")
            val contextName = ctx.paramStr(params, "context")
            if (fileIds.isEmpty() || contextName.isEmpty()) {
                ctx.resolveToJs("relocateFiles", JSONObject().put("errorCode", 0))
                return
            }
            // path traversal 방지: contextName에서 경로 구분자/상위 참조 제거
            val safeContext = contextName.replace(Regex("[/\\\\]"), "_").replace("..", "")
            if (safeContext.isEmpty()) {
                ctx.rejectToJs("relocateFiles", "Invalid context name")
                return
            }
            withContext(Dispatchers.IO) {
                val baseDir = java.io.File(ctx.activity.filesDir, "downloads")
                val destDir = java.io.File(baseDir, safeContext).also {
                    // canonical path 검증: baseDir 하위인지 확인
                    if (!it.canonicalPath.startsWith(baseDir.canonicalPath)) {
                        throw SecurityException("Invalid destination path")
                    }
                }
                if (!destDir.exists()) destDir.mkdirs()
                for (fileId in fileIds) {
                    val safeFileId = fileId.replace(Regex("[/\\\\]"), "_").replace("..", "")
                    val src = java.io.File(baseDir, safeFileId)
                    if (src.exists() && src.canonicalPath.startsWith(baseDir.canonicalPath)) {
                        val dest = java.io.File(destDir, safeFileId)
                        src.renameTo(dest)
                    }
                }
            }
            ctx.resolveToJs("relocateFiles", JSONObject().put("errorCode", 0))
        } catch (e: Exception) {
            ctx.rejectToJs("relocateFiles", e.message)
        }
    }

    /**
     * Filebox 다운로드: sessionId 발급 + 진행률 push 이벤트.
     * 기본 downloadFile과 달리 백그라운드 세션처럼 동작 (Pause 미지원, Cancel만 동작).
     */
    private suspend fun handleFileboxDownload(params: Map<String, Any?>, cbId: String) {
        val sessionId = newSessionId("dl")
        val rawName = ctx.paramStr(params, "fileName").ifEmpty { "download" }
        val fileName = rawName.replace(Regex("[/\\\\]"), "_").replace("..", "")
        val fileId = ctx.paramStr(params, "fileId")
        try {
            // bridge.js 의 filebox 다운로드는 url 을 포스트메시지에 담지 않고 fileId 만 보냄.
            // FilePage 와 동일한 실제 서버 경로: /api/file/download/{fileCode}?userId={userId}
            val rawUrl = ctx.paramStr(params, "url")
            val fullUrl = when {
                rawUrl.startsWith("http") -> rawUrl
                rawUrl.isNotEmpty() -> {
                    val origin = ctx.appConfig.getRestBaseUrl().replace(Regex("/api$"), "")
                    "$origin$rawUrl"
                }
                fileId.isNotEmpty() -> {
                    val me = ctx.appConfig.getSavedUserId() ?: ""
                    val base = ctx.appConfig.getEndpointByPath("/file/download/$fileId")
                    if (me.isNotEmpty()) {
                        val sep = if (base.contains("?")) "&" else "?"
                        "$base${sep}userId=${java.net.URLEncoder.encode(me, "UTF-8")}"
                    } else base
                }
                else -> {
                    ctx.rejectToJs(cbId, "No URL or fileId")
                    return
                }
            }
            Log.d(TAG, "fileboxDownload: url=$fullUrl, fileName=$fileName, fileId=$fileId")
            val dlDir = java.io.File(ctx.activity.filesDir, "downloads/filebox").apply { mkdirs() }
            val saveFile = java.io.File(dlDir, fileName).canonicalFile
            if (!saveFile.path.startsWith(dlDir.canonicalPath)) {
                ctx.rejectToJs(cbId, "Invalid filename")
                return
            }
            emitFileEvent("fileDownloadStarted", JSONObject()
                .put("sessionId", sessionId)
                .put("fileName", fileName))

            // 즉시 ack
            ctx.resolveToJs(cbId, JSONObject()
                .put("errorCode", 0)
                .put("sessionId", sessionId)
                .put("fileName", fileName))

            withContext(Dispatchers.IO) {
                try {
                    downloadFileboxStreaming(fullUrl, saveFile.absolutePath, sessionId, fileName)
                    emitFileEvent("fileDownloadCompleted", JSONObject()
                        .put("sessionId", sessionId)
                        .put("fileName", fileName)
                        .put("path", saveFile.absolutePath))
                } catch (e: Exception) {
                    if (e is java.io.IOException && e.message?.contains("Canceled", true) == true) {
                        // 이미 cancel emit됨
                    } else {
                        emitFileEvent("fileDownloadFailed", JSONObject()
                            .put("sessionId", sessionId)
                            .put("fileName", fileName)
                            .put("reason", e.message ?: "download failed"))
                    }
                } finally {
                    activeDownloads.remove(sessionId)
                }
            }
        } catch (e: Exception) {
            ctx.rejectToJs(cbId, e.message)
        }
    }

    private fun downloadFileboxStreaming(url: String, savePath: String, sessionId: String, fileName: String) {
        val token = ctx.loginViewModel.sessionManager.jwtToken
        val reqBuilder = okhttp3.Request.Builder().url(url)
        if (!token.isNullOrEmpty()) reqBuilder.addHeader("Authorization", "Bearer $token")
        val call = ApiClient.okHttpClient.newCall(reqBuilder.build())
        activeDownloads[sessionId] = call
        Log.d(TAG, "downloadFileboxStreaming: GET $url (session=$sessionId)")
        val response = call.execute()
        if (!response.isSuccessful) {
            Log.w(TAG, "downloadFileboxStreaming: HTTP ${response.code}")
            throw java.io.IOException("HTTP ${response.code}")
        }
        val total = response.body?.contentLength() ?: -1L
        Log.d(TAG, "downloadFileboxStreaming: status=200, total=$total")
        var sent = 0L
        var lastEmit = 0
        response.body?.byteStream()?.use { input ->
            java.io.FileOutputStream(savePath).use { out ->
                val buf = ByteArray(16 * 1024)
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                    sent += read
                    if (total > 0) {
                        val pct = (sent * 100 / total).toInt()
                        if (pct != lastEmit) {
                            lastEmit = pct
                            // FilePage 구독 필드명 (percent/bytesReceived/totalBytes). 레거시 키도 유지.
                            emitFileEvent("fileDownloadProgress", JSONObject()
                                .put("sessionId", sessionId)
                                .put("fileName", fileName)
                                .put("percent", pct)
                                .put("bytesReceived", sent)
                                .put("totalBytes", total)
                                .put("progress", pct)
                                .put("received", sent)
                                .put("total", total))
                        }
                    }
                }
            }
        }
        Log.d(TAG, "downloadFileboxStreaming: done, sent=$sent bytes")
    }

    /** previewFile: ACTION_VIEW로 시스템 미리보기 */
    private fun handlePreviewFile(params: Map<String, Any?>) {
        try {
            val path = ctx.paramStr(params, "path")
            if (path.isEmpty()) { ctx.rejectToJs("previewFile", "No path"); return }
            val file = java.io.File(path)
            if (!file.exists()) { ctx.rejectToJs("previewFile", "File not found"); return }
            val authority = "${ctx.activity.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(ctx.activity, authority, file)
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension) ?: "*/*"
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.activity.startActivity(intent)
            ctx.resolveToJs("previewFile", JSONObject().put("errorCode", 0))
        } catch (e: Exception) {
            ctx.rejectToJs("previewFile", e.message)
        }
    }

    /** shareFile: ACTION_SEND 시스템 공유 시트 */
    private fun handleShareFile(params: Map<String, Any?>) {
        try {
            val path = ctx.paramStr(params, "path")
            if (path.isEmpty()) { ctx.rejectToJs("shareFile", "No path"); return }
            val file = java.io.File(path)
            if (!file.exists()) { ctx.rejectToJs("shareFile", "File not found"); return }
            val authority = "${ctx.activity.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(ctx.activity, authority, file)
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension) ?: "*/*"
            val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = mime
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = android.content.Intent.createChooser(sendIntent, file.name).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.activity.startActivity(chooser)
            ctx.resolveToJs("shareFile", JSONObject().put("errorCode", 0))
        } catch (e: Exception) {
            ctx.rejectToJs("shareFile", e.message)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Chunk Upload (30MB+) — PC FilePage ChunkUploader 프로토콜과 동일
    //  1) POST /api/file/upload/init        → {sessionId, fileCode}
    //  2) POST /api/file/upload/chunk/{sid}?chunkIndex=i (multipart)
    //  3) POST /api/file/upload/complete/{sid} → filebox item
    //  4) DELETE /api/file/upload/cancel/{sid}  (handleCancelUpload에서 호출)
    // ══════════════════════════════════════════════════════════════════

    private suspend fun uploadFileboxChunked(
        file: java.io.File, fileName: String, mime: String, token: String?,
        clientSessionId: String, session: UploadSession,
        userId: String, parentFolderCode: String
    ): JSONObject = coroutineScope {
        val fileSize = file.length()
        val chunkSize = calcChunkSize(fileSize)
        val totalChunks = ((fileSize + chunkSize - 1) / chunkSize).toInt()
        Log.d(TAG, "chunkUpload start: size=$fileSize, chunkSize=$chunkSize, totalChunks=$totalChunks")

        // 1) init
        val initUrl = ctx.appConfig.getEndpointByPath("/file/upload/init")
        val initBody = JSONObject().apply {
            put("userId", userId)
            put("fileName", fileName)
            put("totalSize", fileSize)
            put("mimeType", mime)
            put("parentFolderCode", if (parentFolderCode.isNotEmpty()) parentFolderCode else JSONObject.NULL)
            put("totalChunks", totalChunks)
            put("chunkSize", chunkSize)
        }
        val initResp = withContext(Dispatchers.IO) { ApiClient.postJson(initUrl, initBody, token) }
        val serverSid = initResp.optString("sessionId", "")
        val fileCode = initResp.optString("fileCode", "")
        if (serverSid.isEmpty()) throw java.io.IOException("chunkUpload init: missing sessionId (resp=$initResp)")
        session.serverSessionId = serverSid
        Log.d(TAG, "chunkUpload init ok: serverSid=$serverSid, fileCode=$fileCode")

        // 2) 병렬 청크 업로드 — 진행률은 누적 byte 기준
        val nextIdx = java.util.concurrent.atomic.AtomicInteger(0)
        val completedBytes = java.util.concurrent.atomic.AtomicLong(0)
        val completedChunks = java.util.concurrent.atomic.AtomicInteger(0)
        val lastEmitPct = java.util.concurrent.atomic.AtomicInteger(-1)

        val workers = (0 until minOf(PARALLEL_CHUNKS, totalChunks)).map {
            async(Dispatchers.IO) {
                while (true) {
                    val i = nextIdx.getAndIncrement()
                    if (i >= totalChunks) break
                    val start = i.toLong() * chunkSize
                    val end = minOf(start + chunkSize, fileSize)
                    val len = (end - start).toInt()

                    val chunkBytes = ByteArray(len)
                    java.io.RandomAccessFile(file, "r").use { raf ->
                        raf.seek(start)
                        raf.readFully(chunkBytes)
                    }
                    val url = ctx.appConfig.getEndpointByPath("/file/upload/chunk/$serverSid?chunkIndex=$i")
                    val chunkBody = chunkBytes.toRequestBody(mime.toMediaTypeOrNull())
                    val multipart = okhttp3.MultipartBody.Builder()
                        .setType(okhttp3.MultipartBody.FORM)
                        .addFormDataPart("chunkIndex", i.toString())
                        .addFormDataPart("chunk", fileName, chunkBody)
                        .build()
                    val reqBuilder = okhttp3.Request.Builder().url(url).post(multipart)
                    if (!token.isNullOrEmpty()) reqBuilder.addHeader("Authorization", "Bearer $token")
                    val call = ApiClient.okHttpClient.newCall(reqBuilder.build())
                    session.calls.add(call)
                    call.execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw java.io.IOException("chunk $i HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                        }
                    }
                    val totalSent = completedBytes.addAndGet(len.toLong())
                    val doneChunks = completedChunks.incrementAndGet()
                    val pct = (totalSent * 100 / fileSize).toInt()
                    val prev = lastEmitPct.get()
                    if (pct != prev && lastEmitPct.compareAndSet(prev, pct)) {
                        emitFileEvent("fileUploadProgress", JSONObject()
                            .put("sessionId", clientSessionId)
                            .put("fileName", fileName)
                            .put("percent", pct)
                            .put("bytesSent", totalSent)
                            .put("totalBytes", fileSize)
                            .put("progress", pct)
                            .put("sent", totalSent)
                            .put("total", fileSize)
                            .put("uploadedChunks", doneChunks)
                            .put("totalChunks", totalChunks))
                    }
                }
            }
        }
        workers.awaitAll()

        // 3) complete → filebox 레코드
        val completeUrl = ctx.appConfig.getEndpointByPath("/file/upload/complete/$serverSid")
        val completeReq = okhttp3.Request.Builder()
            .url(completeUrl)
            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
        if (!token.isNullOrEmpty()) completeReq.addHeader("Authorization", "Bearer $token")
        val completeCall = ApiClient.okHttpClient.newCall(completeReq.build())
        session.calls.add(completeCall)
        val completeResp = completeCall.execute()
        val raw = completeResp.body?.string() ?: "{}"
        Log.d(TAG, "chunkUpload complete: status=${completeResp.code}, bodyLen=${raw.length}")
        if (!completeResp.isSuccessful) {
            throw java.io.IOException("complete HTTP ${completeResp.code}: ${raw.take(200)}")
        }
        try { JSONObject(raw) } catch (_: Exception) { JSONObject().put("raw", raw) }
    }
}
