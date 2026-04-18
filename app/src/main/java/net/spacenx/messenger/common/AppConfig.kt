package net.spacenx.messenger.common

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import net.spacenx.messenger.R
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap


/**
 * 서버 정보 + 인증 정보 관리 (BaseDefine 대체)
 *
 * syncConfig 이후 config DB 값을 인메모리 캐시에 보관하여
 * REST base URL, bridge host/port 등을 동기적으로 제공한다.
 */
class AppConfig(private val context: Context) {

    companion object {
        private const val PREF_SECURE = "SecureInfo"
        private const val PREF_DEVICE = "DeviceInfo"
        private const val KEY_USER_ID = "userId"
        private const val KEY_PASSWORD = "password"
        private const val KEY_TOKEN = "token"
        private const val KEY_REFRESH_TOKEN = "refreshToken"
        private const val KEY_GMS_TOKEN = "gmsToken"
        private const val KEY_UUID_PREFIX = "uuid_"

        // config DB keys
        const val CONFIG_REST_BASE_URL = "ultari.rest.base-url"
        const val CONFIG_BRIDGE_HOST = "ultari.server.bridge.host"
        const val CONFIG_BRIDGE_PORT = "ultari.server.bridge.port"
        const val CONFIG_QUIC_PORT = "ultari.server.bridge.quic-port"  // common.db config 테이블 실제 key

        // endpoint config keys
        const val EP_AUTH_LOGIN = "ultari.rest.endpoints.auth.Login"
        const val EP_AUTH_LOGOUT = "ultari.rest.endpoints.auth.Logout"
        const val EP_AUTH_SYNC_CONFIG = "ultari.rest.endpoints.auth.SyncConfig"
        const val EP_AUTH_REFRESH = "ultari.rest.endpoints.auth.RefreshToken"

        const val EP_ORG_MY_PART = "ultari.rest.endpoints.org.MyPartRequest"
        const val EP_ORG_SUB_ORG = "ultari.rest.endpoints.org.SubOrg"
        const val EP_ORG_SYNC_ORG = "ultari.rest.endpoints.org.SyncOrg"
        const val EP_ORG_SEARCH_USER = "ultari.rest.endpoints.org.SearchUser"
        const val EP_ORG_GET_USER_INFO = "ultari.rest.endpoints.org.GetUserInfo"
        const val EP_ORG_GET_DEPT_INFO = "ultari.rest.endpoints.org.GetDeptInfo"
        const val EP_ORG_GET_ROOT_DEPTS = "ultari.rest.endpoints.org.GetRootDepts"

        const val EP_COMM_SYNC_CHANNEL = "ultari.rest.endpoints.comm.SyncChannel"
        const val EP_COMM_SYNC_CHAT = "ultari.rest.endpoints.comm.SyncChat"
        const val EP_COMM_MAKE_CHANNEL = "ultari.rest.endpoints.comm.MakeChannel"
        const val EP_COMM_SEND_CHAT = "ultari.rest.endpoints.comm.SendChat"
        const val EP_COMM_GET_CHANNEL_INFO = "ultari.rest.endpoints.comm.GetChannelInfo"
        const val EP_COMM_ADD_CHANNEL_MEMBER = "ultari.rest.endpoints.comm.AddChannelMember"
        const val EP_COMM_REMOVE_CHANNEL_MEMBER = "ultari.rest.endpoints.comm.RemoveChannelMember"
        const val EP_COMM_DESTROY_CHANNEL = "ultari.rest.endpoints.comm.DestroyChannel"
        const val EP_COMM_READ_CHAT = "ultari.rest.endpoints.comm.ReadChat"
        const val EP_COMM_DELETE_CHAT = "ultari.rest.endpoints.comm.DeleteChat"
        const val EP_COMM_MOD_CHAT = "ultari.rest.endpoints.comm.ModChat"
        const val EP_COMM_REACTION_CHAT = "ultari.rest.endpoints.comm.ReactionChat"
        const val EP_COMM_SYNC_MESSAGE = "ultari.rest.endpoints.comm.SyncMessage"
        const val EP_COMM_SEND_MESSAGE = "ultari.rest.endpoints.comm.SendMessage"
        const val EP_COMM_READ_MESSAGE = "ultari.rest.endpoints.comm.ReadMessage"
        const val EP_COMM_DELETE_MESSAGE = "ultari.rest.endpoints.comm.DeleteMessage"
        const val EP_COMM_RETRIEVE_MESSAGE = "ultari.rest.endpoints.comm.RetrieveMessage"
        const val EP_COMM_NOTIFICATION = "ultari.rest.endpoints.comm.Notification"
        const val EP_COMM_READ_NOTI = "ultari.rest.endpoints.comm.ReadNoti"
        const val EP_COMM_DELETE_NOTI = "ultari.rest.endpoints.comm.DeleteNoti"
        const val EP_COMM_SYNC_NOTI = "ultari.rest.endpoints.comm.SyncNoti"

        const val EP_BUDDY_SYNC_BUDDY = "ultari.rest.endpoints.buddy.SyncBuddy"
        const val EP_BUDDY_ADD = "ultari.rest.endpoints.buddy.BuddyAdd"
        const val EP_BUDDY_DEL = "ultari.rest.endpoints.buddy.BuddyDel"
        const val EP_BUDDY_MOD = "ultari.rest.endpoints.buddy.BuddyMod"
        const val EP_BUDDY_MOVE = "ultari.rest.endpoints.buddy.BuddyMove"
        const val EP_BUDDY_ADD_LINK = "ultari.rest.endpoints.buddy.AddLink"
        const val EP_BUDDY_DEL_LINK = "ultari.rest.endpoints.buddy.DelLink"

        const val EP_STATUS_SUMMARY = "ultari.rest.endpoints.status.StatusSummary"
        const val EP_STATUS_GET_ICONS = "ultari.rest.endpoints.status.GetIcons"
        const val EP_STATUS_SET_PRESENCE = "ultari.rest.endpoints.status.SetPresence"

        const val EP_NICK_GET_ALL = "ultari.rest.endpoints.nick.GetAllNick"
        const val EP_NICK_GET = "ultari.rest.endpoints.nick.GetNick"
        const val EP_NICK_SET = "ultari.rest.endpoints.nick.SetNick"

        const val EP_MEDIA_UPLOAD_IMAGE = "ultari.rest.endpoints.media.UploadImage"

        const val EP_PUBSUB_PUBLISH = "ultari.rest.endpoints.pubsub.Publish"
        const val EP_PUBSUB_SUBSCRIBE = "ultari.rest.endpoints.pubsub.Subscribe"
        const val EP_PUBSUB_UNSUBSCRIBE = "ultari.rest.endpoints.pubsub.Unsubscribe"
    }

    // ── 서버 정보 (strings.xml — 최초 로그인 시 fallback) ──

    val messengerHost: String
        get() = context.getString(R.string.messengerserver_private_ip)

    val messengerPort: Int
        get() = context.getString(R.string.messengerserver_private_port).toInt()

    /** REST API 포트 (공인 포트) — 최초 로그인 시 fallback */
    val restPort: Int
        get() = context.getString(R.string.messengerserver_public_port).toInt()

    // ── config DB 인메모리 캐시 ──

    private val configCache = ConcurrentHashMap<String, String>()

    init {
        // FRONTEND_SKIN/VERSION을 SharedPreferences에서 동기 로드
        // (DB loadConfigCache는 비동기라 startProcess의 getSpaUrl()과 경쟁 → 항상 nx 기본값으로 시작하는 문제 방지)
        val prefs = context.getSharedPreferences(PREF_DEVICE, Context.MODE_PRIVATE)
        prefs.getString("FRONTEND_SKIN", null)?.let { configCache["FRONTEND_SKIN"] = it }
        prefs.getString("FRONTEND_VERSION", null)?.let { configCache["FRONTEND_VERSION"] = it }
    }

    /** syncConfig 성공 후 호출하여 인메모리 캐시 갱신 */
    fun updateConfigCache(configs: Map<String, String>) {
        configCache.putAll(configs)
        // FRONTEND_SKIN/VERSION이 포함된 경우 다음 기동을 위해 SharedPreferences에 즉시 저장
        val skin = configs["FRONTEND_SKIN"]
        val version = configs["FRONTEND_VERSION"]
        if (skin != null || version != null) {
            val edit = context.getSharedPreferences(PREF_DEVICE, Context.MODE_PRIVATE).edit()
            if (skin != null) edit.putString("FRONTEND_SKIN", skin)
            if (version != null) edit.putString("FRONTEND_VERSION", version)
            edit.apply()
        }
    }

    fun getConfigValue(key: String): String? = configCache[key]

    /** FRONTEND_SKIN 기반 SPA base URL 계산 (nx → nst 등) */
    fun getSpaBaseUrl(): String {
        val skin = configCache["FRONTEND_SKIN"]
        val defaultBase = "https://neo.ultari.co.kr:18019/static/nx"
        return if (!skin.isNullOrEmpty()) {
            defaultBase.replace("/static/nx", "/static/$skin")
        } else defaultBase
    }

    /** FRONTEND_VERSION + FRONTEND_SKIN 적용된 최종 SPA URL */
    fun getSpaUrl(): String {
        val base = getSpaBaseUrl()
        val version = configCache["FRONTEND_VERSION"]
        return if (!version.isNullOrEmpty()) {
            "$base/$version/index.html"
        } else {
            "$base/index.html"
        }
    }

    /**
     * config DB에서 endpoint 경로를 조회한다.
     * DB에 값이 없으면 fallback 경로를 사용한다. (최초 로그인 등 syncConfig 전)
     * 선행 슬래시는 제거하여 Retrofit baseUrl과 올바르게 결합되도록 한다.
     */
    fun getEndpoint(configKey: String, fallback: String): String {
        val raw = configCache[configKey] ?: fallback
        return raw.removePrefix("/")
    }

    /**
     * REST path (e.g. "/comm/sendchat") → 절대 URL 변환.
     * getRestBaseUrl() + path 결합.
     */
    fun getEndpointByPath(path: String): String {
        val base = getRestBaseUrl()
        val trimmedPath = path.removePrefix("/")
        return if (base.endsWith("/")) "${base}api/$trimmedPath" else "$base/api/$trimmedPath"
    }

    /**
     * REST API base URL (syncConfig에서 받은 ultari.rest.base-url의 첫 번째 URL)
     * 없으면 strings.xml의 host:port를 fallback으로 사용
     */
    fun getRestBaseUrl(): String {
        val raw = configCache[CONFIG_REST_BASE_URL]
        if (!raw.isNullOrEmpty()) {
            val urls = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val url = urls.first()
            return url
        }
        return "https://$messengerHost:$restPort"
    }

    /** 바이너리 프로토콜 서버 호스트 (syncConfig → fallback strings.xml) */
    fun getBridgeHost(): String {
        return configCache[CONFIG_BRIDGE_HOST] ?: messengerHost
    }

    /** 바이너리 프로토콜 서버 포트 (syncConfig → fallback strings.xml) */
    fun getBridgePort(): Int {
        return configCache[CONFIG_BRIDGE_PORT]?.toIntOrNull() ?: messengerPort
    }

    // ── 전송 프로토콜 (TCP/QUIC) — 기본 TCP, QUIC 활성화 시 Gateway2:18029 사용 ──
    // 향후 옵션 A(Cronet+HTTP/3) 전환 시 QuicSocketClient 구현만 교체하면 됨 (호출부 변경 불필요)
    private val PREF_USE_QUIC = "transport_use_quic"
    private val PREF_QUIC_PORT = "transport_quic_port"
    private val DEFAULT_QUIC_PORT = 18029

    /** QUIC 사용 여부 — 기본값 false(TCP). 설정/개발자 옵션에서 토글 */
    fun isQuicEnabled(): Boolean =
        context.getSharedPreferences(PREF_DEVICE, Context.MODE_PRIVATE)
            .getBoolean(PREF_USE_QUIC, false)

    fun setQuicEnabled(enabled: Boolean) {
        context.getSharedPreferences(PREF_DEVICE, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_USE_QUIC, enabled).commit()  // commit() 동기 — apply()는 비동기라 즉시 reconnect 시 못 읽힘
    }

    /**
     * QUIC 포트 — common.db config 테이블의 [CONFIG_QUIC_PORT] 값 우선.
     * DB가 없거나 값이 없으면 SharedPreferences → 기본값 18029.
     */
    fun getQuicPort(): Int {
        return configCache[CONFIG_QUIC_PORT]?.toIntOrNull()
            ?: context.getSharedPreferences(PREF_DEVICE, Context.MODE_PRIVATE)
                .getInt(PREF_QUIC_PORT, DEFAULT_QUIC_PORT)
    }

    // ── EncryptedSharedPreferences (credentials + JWT 토큰 통합) ──

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val securePrefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            PREF_SECURE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ── 인증 정보 (EncryptedSharedPreferences) ──

    fun saveCredentials(userId: String, password: String) {
        securePrefs.edit()
            .putString(KEY_USER_ID, userId)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun getSavedUserId(): String? = securePrefs.getString(KEY_USER_ID, null)

    fun getSavedPassword(): String? = securePrefs.getString(KEY_PASSWORD, null)

    fun clearCredentials() {
        securePrefs.edit()
            .remove(KEY_USER_ID)
            .remove(KEY_PASSWORD)
            .apply()
    }

    // ── JWT 토큰 ──

    fun saveTokens(token: String, refreshToken: String) {
        securePrefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .apply()
    }

    fun getSavedToken(): String? = securePrefs.getString(KEY_TOKEN, null)

    fun getSavedRefreshToken(): String? = securePrefs.getString(KEY_REFRESH_TOKEN, null)

    fun clearTokens() {
        securePrefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .apply()
    }

    /** JWT만 클리어 (refreshToken은 REST 재인증용으로 보존) */
    fun clearJwt() {
        securePrefs.edit()
            .remove(KEY_TOKEN)
            .apply()
    }

    fun clearAll() {
        securePrefs.edit().clear().apply()
    }

    /** 로그아웃/토큰 만료 시 호출 — SKIN·VERSION 모두 초기화 (다음 로그인 시 syncConfig로 재취득) */
    fun clearFrontendCache() {
        configCache.remove("FRONTEND_VERSION")
        configCache.remove("FRONTEND_SKIN")
        devicePrefs.edit()
            .remove("FRONTEND_VERSION")
            .remove("FRONTEND_SKIN")
            .apply()
    }

    // ── 본인 Presence 상태 ──

    fun saveMyStatusCode(statusCode: Int) {
        devicePrefs.edit().putInt("myStatusCode", statusCode).commit()  // commit() 동기 — apply() 비동기면 앱 강제종료 시 이전 값이 살아남음
    }

    fun getMyStatusCode(): Int = devicePrefs.getInt("myStatusCode", 0)

    // ── 본인 Nick ──

    fun saveMyNick(nick: String) {
        devicePrefs.edit().putString("myNick", nick).apply()
    }

    fun getMyNick(): String = devicePrefs.getString("myNick", "") ?: ""

    // ── 본인 Photo ──

    fun saveMyPhotoVersion(photoVersion: Long) {
        devicePrefs.edit().putLong("myPhotoVersion", photoVersion).apply()
    }

    fun getMyPhotoVersion(): Long = devicePrefs.getLong("myPhotoVersion", 0L)

    // ── 내부서 (myDeptId) ──

    fun saveMyDeptId(deptId: String) {
        devicePrefs.edit().putString("myDeptId", deptId).apply()
    }

    fun getMyDeptId(): String? = devicePrefs.getString("myDeptId", null)

    fun clearMyDeptId() {
        devicePrefs.edit().remove("myDeptId").apply()
    }

    // ── Firebase 토큰 ──

    private val devicePrefs
        get() = context.getSharedPreferences(PREF_DEVICE, Context.MODE_PRIVATE)

    var gmsToken: String
        get() = devicePrefs.getString(KEY_GMS_TOKEN, "") ?: ""
        set(value) {
            devicePrefs.edit().putString(KEY_GMS_TOKEN, value).apply()
        }

    // ── UUID (사용자별) ──

    fun getUuidForUser(userId: String): String {
        val key = KEY_UUID_PREFIX + userId
        val existing = devicePrefs.getString(key, null)
        if (existing != null) return existing

        val uuid = UUID.randomUUID().toString()
        devicePrefs.edit().putString(key, uuid).apply()
        return uuid
    }

    fun getUUID(): String? {
        val sharedPref = context.getSharedPreferences("UUID", Context.MODE_PRIVATE)
        var uuid: String? = sharedPref.getString("uuid", "")!!.trim { it <= ' ' }

        if (uuid!!.isEmpty()) {
            uuid = UUID.randomUUID().toString()

            val editor = sharedPref.edit()
            editor.putString("uuid", uuid)
            editor.apply()
        }

        return uuid
    }

    // ── 테마 ──

    fun saveThemeId(themeId: String) {
        devicePrefs.edit().putString("themeId", themeId).apply()
    }

    fun getThemeId(): String? = devicePrefs.getString("themeId", null)

    // ── 스킨 ──

    fun saveSkinId(skinId: String) {
        devicePrefs.edit().putString("skinId", skinId).apply()
    }

    fun getSkinId(): String? = devicePrefs.getString("skinId", null)

    // ── 자격 증명 저장 (WebView bridge 용) ──

    fun saveCredential(userId: String, password: String, savePassword: Boolean, autoLogin: Boolean) {
        if (savePassword) {
            saveCredentials(userId, password)
        } else {
            saveCredentials(userId, "")
        }
        devicePrefs.edit()
            .putBoolean("savePassword", savePassword)
            .putBoolean("autoLogin", autoLogin)
            .apply()
    }

    // ── 채널별 알림 뮤트 ──

    fun muteChannel(channelCode: String) {
        val muted = getMutedChannels().toMutableSet()
        muted.add(channelCode)
        devicePrefs.edit().putStringSet("mutedChannels", muted).apply()
    }

    fun unmuteChannel(channelCode: String) {
        val muted = getMutedChannels().toMutableSet()
        muted.remove(channelCode)
        devicePrefs.edit().putStringSet("mutedChannels", muted).apply()
    }

    fun isChannelMuted(channelCode: String): Boolean =
        getMutedChannels().contains(channelCode)

    private fun getMutedChannels(): Set<String> =
        devicePrefs.getStringSet("mutedChannels", emptySet()) ?: emptySet()
}
