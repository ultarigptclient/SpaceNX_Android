package net.spacenx.messenger.data.remote.api

import android.util.Base64
import kotlinx.serialization.json.Json
import net.spacenx.messenger.BuildConfig
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object ApiClient {

    private val json = Json { ignoreUnknownKeys = true }

    /** 개발용 stub — 모든 인증서 허용. 릴리스 빌드에선 사용 금지. */
    private val insecureTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /** 플랫폼 기본 trust store 사용 (릴리스용) */
    private val platformTrustManager: X509TrustManager by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    /**
     * DEBUG: 모든 인증서 허용 (로컬 self-signed 개발 서버 접속용)
     * RELEASE: 플랫폼 기본 trust store — 실제 서버 인증서 검증됨
     */
    private val activeTrustManager: X509TrustManager =
        if (BuildConfig.DEBUG) insecureTrustManager else platformTrustManager

    val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(activeTrustManager), SecureRandom())
        }
    }

    private val connectionPool = ConnectionPool(5, 30, TimeUnit.SECONDS)

    /** 네트워크 에러 시 자동 재시도 (최대 2회, 1s/2s backoff) */
    private val retryInterceptor = Interceptor { chain ->
        var lastException: java.io.IOException? = null
        for (attempt in 0..2) {
            try {
                val response = chain.proceed(chain.request())
                if (response.isSuccessful || attempt >= 2) return@Interceptor response
                response.close()
            } catch (e: java.io.IOException) {
                lastException = e
                if (attempt >= 2) throw e
            }
            Thread.sleep(1000L * (attempt + 1))
        }
        throw lastException ?: java.io.IOException("Max retries exceeded")
    }

    internal val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(connectionPool)
            .addInterceptor(retryInterceptor)
            .apply {
                // DEBUG: stub trust + no-op hostname 허용 (self-signed 로컬 서버)
                // RELEASE: OkHttp 기본값 — 플랫폼 trust store + 실제 hostname 검증
                if (BuildConfig.DEBUG) {
                    sslSocketFactory(sslContext.socketFactory, activeTrustManager)
                    hostnameVerifier { _, _ -> true }
                }
            }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    val wsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .apply {
                if (BuildConfig.DEBUG) {
                    sslSocketFactory(sslContext.socketFactory, activeTrustManager)
                    hostnameVerifier { _, _ -> true }
                }
            }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("https://localhost/")
            .client(okHttpClient)
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()
    }

    val updateApi: UpdateApi by lazy {
        retrofit.create(UpdateApi::class.java)
    }

    // ── 토큰 없는 Auth API (login, refresh) ──

    fun createAuthApiFromBaseUrl(baseUrl: String): AuthApi {
        return createRetrofitFromBaseUrl(baseUrl).create(AuthApi::class.java)
    }

    // ── 토큰 포함 API (syncConfig 헤더 직접 전달이므로 토큰 불필요) ──

    fun createOrgApi(host: String, port: Int, token: String? = null): OrgApi {
        return createHttpRetrofit(host, port, token).create(OrgApi::class.java)
    }

    fun createOrgApiFromBaseUrl(baseUrl: String, token: String? = null): OrgApi {
        return createRetrofitFromBaseUrl(baseUrl, token).create(OrgApi::class.java)
    }

    fun createBuddyApi(host: String, port: Int, token: String? = null): BuddyApi {
        return createHttpRetrofit(host, port, token).create(BuddyApi::class.java)
    }

    fun createBuddyApiFromBaseUrl(baseUrl: String, token: String? = null): BuddyApi {
        return createRetrofitFromBaseUrl(baseUrl, token).create(BuddyApi::class.java)
    }

    fun createChannelApi(host: String, port: Int, token: String? = null): ChannelApi {
        return createHttpRetrofit(host, port, token).create(ChannelApi::class.java)
    }

    fun createChannelApiFromBaseUrl(baseUrl: String, token: String? = null): ChannelApi {
        return createRetrofitFromBaseUrl(baseUrl, token).create(ChannelApi::class.java)
    }

    fun createStatusApiFromBaseUrl(baseUrl: String, token: String? = null): StatusApi {
        return createRetrofitFromBaseUrl(baseUrl, token).create(StatusApi::class.java)
    }

    fun createCommApiFromBaseUrl(baseUrl: String, token: String? = null): CommApi {
        return createRetrofitFromBaseUrl(baseUrl, token).create(CommApi::class.java)
    }

    // ── Retrofit 캐시 (baseUrl+token 조합별 재사용) ──

    private val retrofitCache = java.util.concurrent.ConcurrentHashMap<String, Retrofit>()

    /** 토큰 갱신 시 stale 캐시 제거 */
    fun clearRetrofitCache() {
        retrofitCache.clear()
    }

    // ── host:port 기반 Retrofit ──

    fun createHttpRetrofit(host: String, port: Int, token: String? = null): Retrofit {
        val key = "https://$host:$port/|${token ?: ""}"
        return retrofitCache.getOrPut(key) {
            val client = if (token != null) {
                okHttpClient.newBuilder()
                    .addInterceptor(bearerInterceptor(token))
                    .build()
            } else {
                okHttpClient
            }
            Retrofit.Builder()
                .baseUrl("https://$host:$port/")
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
        }
    }

    // ── baseUrl 기반 Retrofit (syncConfig 이후 사용) ──

    fun createRetrofitFromBaseUrl(baseUrl: String, token: String? = null): Retrofit {
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val key = "$url|${token ?: ""}"
        return retrofitCache.getOrPut(key) {
            val client = if (token != null) {
                okHttpClient.newBuilder()
                    .addInterceptor(bearerInterceptor(token))
                    .build()
            } else {
                okHttpClient
            }
            Retrofit.Builder()
                .baseUrl(url)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
        }
    }

    // ── 토큰 갱신 포함 Retrofit (401/403 → refreshToken → 재시도) ──

    fun createRetrofitWithTokenRefresh(
        baseUrl: String,
        tokenProvider: () -> String?,
        refreshTokenProvider: () -> String?,
        onTokenRefreshed: (accessToken: String, refreshToken: String) -> Unit,
        onRefreshFailed: () -> Unit,
        refreshEndpoint: String = "api/auth/refresh"
    ): Retrofit {
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val client = okHttpClient.newBuilder()
            .addInterceptor(bearerInterceptor { tokenProvider() ?: "" })
            .addInterceptor(tokenRefreshInterceptor(
                baseUrl = url,
                refreshTokenProvider = refreshTokenProvider,
                onTokenRefreshed = onTokenRefreshed,
                onRefreshFailed = onRefreshFailed,
                refreshEndpoint = refreshEndpoint
            ))
            .build()
        return Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    private fun bearerInterceptor(token: String): Interceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer $token")
            .build()
        chain.proceed(request)
    }

    private fun bearerInterceptor(tokenProvider: () -> String): Interceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer ${tokenProvider()}")
            .build()
        chain.proceed(request)
    }

    /**
     * 401/403 수신 시 refreshToken으로 토큰 갱신 후 원래 요청 재시도
     */
    private fun tokenRefreshInterceptor(
        baseUrl: String,
        refreshTokenProvider: () -> String?,
        onTokenRefreshed: (accessToken: String, refreshToken: String) -> Unit,
        onRefreshFailed: () -> Unit,
        refreshEndpoint: String = "api/auth/refresh"
    ): Interceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())

        if (response.code != 401 && response.code != 403) {
            return@Interceptor response
        }

        // 401/403 → refreshToken으로 갱신 시도
        val refreshToken = refreshTokenProvider() ?: run {
            onRefreshFailed()
            return@Interceptor response
        }

        val refreshBody = """{"refreshToken":"$refreshToken"}"""
            .toRequestBody("application/json".toMediaType())

        val ep = if (refreshEndpoint.startsWith("/")) refreshEndpoint.substring(1) else refreshEndpoint
        val refreshRequest = okhttp3.Request.Builder()
            .url("${baseUrl}$ep")
            .post(refreshBody)
            .build()

        val refreshResponse: Response
        try {
            refreshResponse = okHttpClient.newCall(refreshRequest).execute()
        } catch (e: Exception) {
            onRefreshFailed()
            return@Interceptor response
        }

        if (!refreshResponse.isSuccessful) {
            refreshResponse.close()
            onRefreshFailed()
            return@Interceptor response
        }

        val refreshJson = try {
            val body = refreshResponse.body?.string() ?: "{}"
            org.json.JSONObject(body)
        } catch (e: Exception) {
            onRefreshFailed()
            return@Interceptor response
        }

        if (refreshJson.optInt("errorCode", -1) != 0) {
            onRefreshFailed()
            return@Interceptor response
        }

        val newAccessToken = refreshJson.optString("accessToken", "")
        val newRefreshToken = refreshJson.optString("refreshToken", refreshToken)

        if (newAccessToken.isEmpty()) {
            onRefreshFailed()
            return@Interceptor response
        }

        onTokenRefreshed(newAccessToken, newRefreshToken)

        // 원래 요청 새 토큰으로 재시도
        response.close()
        val retryRequest = chain.request().newBuilder()
            .removeHeader("Authorization")
            .addHeader("Authorization", "Bearer $newAccessToken")
            .build()
        chain.proceed(retryRequest)
    }

    /**
     * POST JSON to an absolute URL and return parsed JSONObject.
     * Used by BridgeDispatcher for generic REST calls.
     */
    fun postJson(url: String, body: JSONObject): JSONObject {
        val requestBody = body.toString()
            .toRequestBody("application/json".toMediaType())
        val request = okhttp3.Request.Builder()
            .url(url)
            .post(requestBody)
            .build()
        val response = okHttpClient.newCall(request).execute()
        val raw = response.body?.string() ?: "{}"
        return try { JSONObject(raw) } catch (_: Exception) { JSONObject().put("raw", raw) }
    }

    /**
     * Generic HTTP request (GET, POST, PUT, DELETE, etc.).
     */
    fun httpRequest(method: String, url: String, body: JSONObject? = null): JSONObject {
        val requestBody = if (body != null && method.uppercase() != "GET") {
            body.toString().toRequestBody("application/json".toMediaType())
        } else null
        val request = okhttp3.Request.Builder()
            .url(url)
            .method(method.uppercase(), requestBody)
            .build()
        val response = okHttpClient.newCall(request).execute()
        val raw = response.body?.string() ?: "{}"
        return try { JSONObject(raw) } catch (_: Exception) { JSONObject().put("raw", raw) }
    }

    /**
     * Upload a file (base64-encoded) via multipart POST.
     */
    fun uploadFile(fileName: String, mimeType: String, base64Data: String, uploadUrl: String? = null, token: String? = null): JSONObject {
        val bytes = Base64.decode(base64Data, Base64.DEFAULT)
        val fileBody = bytes.toRequestBody(mimeType.toMediaType())
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, fileBody)
            .build()
        val url = uploadUrl ?: "https://neo.ultari.co.kr:18019/api/media/file/upload"
        val reqBuilder = okhttp3.Request.Builder()
            .url(url)
            .post(multipartBody)
        if (!token.isNullOrEmpty()) reqBuilder.addHeader("Authorization", "Bearer $token")
        val response = okHttpClient.newCall(reqBuilder.build()).execute()
        val raw = response.body?.string() ?: "{}"
        return try { JSONObject(raw) } catch (_: Exception) { JSONObject().put("raw", raw) }
    }

    /**
     * Upload a file via streaming multipart POST (메모리에 전체 로드하지 않음).
     * @param inputStream 파일 InputStream (호출 측에서 close 불필요 — 내부에서 처리)
     * @param fileName 파일 이름
     * @param mimeType MIME 타입
     * @param contentLength 파일 크기 (진행률 계산용, 0이면 unknown)
     * @param uploadUrl 업로드 URL
     * @param token Bearer 토큰
     * @param onProgress 진행률 콜백 (sent, total)
     */
    /** 파일 업로드 전용 OkHttpClient (타임아웃 확장) */
    private val uploadClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun uploadFileStream(
        inputStream: java.io.InputStream,
        fileName: String,
        mimeType: String,
        contentLength: Long,
        uploadUrl: String,
        token: String? = null,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null
    ): JSONObject {
        val mediaType = mimeType.toMediaType()
        val fileBody = object : okhttp3.RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength() = if (contentLength > 0) contentLength else -1L
            override fun writeTo(sink: okio.BufferedSink) {
                val buffer = ByteArray(8192)
                var totalSent = 0L
                inputStream.use { stream ->
                    var read: Int
                    while (stream.read(buffer).also { read = it } != -1) {
                        sink.write(buffer, 0, read)
                        totalSent += read
                        onProgress?.invoke(totalSent, contentLength)
                    }
                }
            }
        }
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, fileBody)
            .build()
        val reqBuilder = okhttp3.Request.Builder()
            .url(uploadUrl)
            .post(multipartBody)
        if (!token.isNullOrEmpty()) reqBuilder.addHeader("Authorization", "Bearer $token")
        val response = uploadClient.newCall(reqBuilder.build()).execute()
        val raw = response.body?.string() ?: "{}"
        return try { JSONObject(raw) } catch (_: Exception) { JSONObject().put("raw", raw) }
    }

    /**
     * POST JSON with optional Bearer token.
     */
    fun postJson(url: String, body: JSONObject, token: String?): JSONObject {
        val requestBody = body.toString()
            .toRequestBody("application/json".toMediaType())
        val reqBuilder = okhttp3.Request.Builder()
            .url(url)
            .post(requestBody)
        if (!token.isNullOrEmpty()) reqBuilder.addHeader("Authorization", "Bearer $token")
        val response = okHttpClient.newCall(reqBuilder.build()).execute()
        val raw = response.body?.string() ?: "{}"
        return try { JSONObject(raw) } catch (_: Exception) { JSONObject().put("raw", raw) }
    }

    /**
     * Download file to local path with optional Bearer token.
     */
    fun downloadFile(url: String, savePath: String, token: String?) {
        val reqBuilder = okhttp3.Request.Builder().url(url)
        if (!token.isNullOrEmpty()) reqBuilder.addHeader("Authorization", "Bearer $token")
        val response = okHttpClient.newCall(reqBuilder.build()).execute()
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
        response.body?.byteStream()?.use { input ->
            java.io.FileOutputStream(savePath).use { output ->
                input.copyTo(output)
            }
        }
    }

    /** 바이너리 다운로드 최대 크기 (OOM 방지). 기본 20MB — 프로필 사진 등 작은 asset 전용. */
    private const val DOWNLOAD_BYTES_MAX_DEFAULT = 20L * 1024 * 1024

    /**
     * URL 을 ByteArray 로 다운로드. [maxBytes] 초과 시 예외로 중단하여 OOM 크래시 방지.
     * 큰 파일에는 절대 쓰지 말고 [downloadFile] (파일 스트리밍) 사용할 것.
     */
    fun downloadBytes(url: String, token: String?, maxBytes: Long = DOWNLOAD_BYTES_MAX_DEFAULT): ByteArray {
        val reqBuilder = okhttp3.Request.Builder().url(url)
        if (!token.isNullOrEmpty()) reqBuilder.addHeader("Authorization", "Bearer $token")
        return okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body ?: throw Exception("Empty body")
            val declared = body.contentLength()
            if (declared > maxBytes) {
                throw Exception("downloadBytes refused: declared size $declared > maxBytes $maxBytes")
            }
            // contentLength=-1(unknown)일 때도 안전하도록 스트리밍 + 누적 cap
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            body.byteStream().use { stream ->
                var total = 0L
                var read: Int
                while (stream.read(buf).also { read = it } != -1) {
                    total += read
                    if (total > maxBytes) {
                        throw Exception("downloadBytes refused: stream exceeds maxBytes $maxBytes")
                    }
                    out.write(buf, 0, read)
                }
            }
            out.toByteArray()
        }
    }

    fun uploadProfilePhoto(uploadUrl: String, imageBytes: ByteArray, userId: String, token: String?): JSONObject {
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "$userId.jpg",
                imageBytes.toRequestBody("image/jpeg".toMediaType()))
            .addFormDataPart("userId", userId)
            .build()
        val reqBuilder = okhttp3.Request.Builder().url(uploadUrl).post(multipartBody)
        if (!token.isNullOrEmpty()) reqBuilder.addHeader("Authorization", "Bearer $token")
        val response = okHttpClient.newCall(reqBuilder.build()).execute()
        val raw = response.body?.string() ?: "{}"
        return try { JSONObject(raw) } catch (_: Exception) { JSONObject().put("raw", raw) }
    }

    fun evictConnections() {
        connectionPool.evictAll()
    }
}
