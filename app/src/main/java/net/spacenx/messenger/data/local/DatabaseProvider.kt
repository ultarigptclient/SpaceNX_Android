package net.spacenx.messenger.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import net.spacenx.messenger.data.remote.socket.codec.AmCodec
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * DB 관리 (6 databases)
 *
 * 공통 DB (/app/db/):
 *   common.db   — config, syncMeta
 *
 * 사용자별 DB (/app/db/{userId}/):
 *   org.db      — depts, users, buddies, syncMeta
 *   chat.db     — channels, channelMembers, channelOffsets, chats, chatEvents, syncMeta
 *   message.db  — messages, messageEvents, syncMeta
 *   noti.db     — notis, notiEvents, syncMeta
 *   setting.db  — setting
 */
class DatabaseProvider(private val context: Context) {

    companion object {
        private const val TAG = "DatabaseProvider"
        private const val DB_BASE_DIR = "db"
        private const val KEYSTORE_ALIAS = "hybrid_db_key"
        private const val PREFS_NAME = "db_key_prefs"
        private const val PREFS_KEY_ENCRYPTED = "encrypted_passphrase"
        private const val PREFS_KEY_IV = "passphrase_iv"
        /** 레거시 키 — Keystore 마이그레이션 원본 (최초 1회만 사용 후 불필요) */
        private const val LEGACY_ENCRYPTED_DB_KEY = "0TAajREW1fYPHX1psMaHDw=="

        init {
            System.loadLibrary("sqlcipher")
        }
    }

    private val openHelperFactory: SupportOpenHelperFactory by lazy {
        val passphrase = getOrCreatePassphrase()
        SupportOpenHelperFactory(passphrase.toByteArray())
    }

    /**
     * Android Keystore로 보호된 DB passphrase 조회/생성.
     * 최초 실행: AmCodec으로 레거시 키 복호화 → Keystore AES-GCM으로 암호화 → SharedPrefs에 저장
     * 이후: SharedPrefs에서 암호화된 passphrase 읽기 → Keystore로 복호화
     */
    private fun getOrCreatePassphrase(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encryptedB64 = prefs.getString(PREFS_KEY_ENCRYPTED, null)
        val ivB64 = prefs.getString(PREFS_KEY_IV, null)

        if (encryptedB64 != null && ivB64 != null) {
            // Keystore에서 복호화
            return try {
                val key = getKeystoreKey()
                val iv = Base64.decode(ivB64, Base64.NO_WRAP)
                val encrypted = Base64.decode(encryptedB64, Base64.NO_WRAP)
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                String(cipher.doFinal(encrypted), Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w(TAG, "Keystore decrypt failed, regenerating: ${e.message}")
                generateAndStorePassphrase(prefs)
            }
        }

        // 최초 실행: 레거시 키에서 마이그레이션
        return generateAndStorePassphrase(prefs)
    }

    private fun generateAndStorePassphrase(prefs: android.content.SharedPreferences): String {
        val passphrase = AmCodec.decryptSeed(LEGACY_ENCRYPTED_DB_KEY) ?: error("Failed to decrypt DB key")
        // Keystore AES-GCM으로 암호화하여 저장
        val key = getOrCreateKeystoreKey()
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(passphrase.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        prefs.edit()
            .putString(PREFS_KEY_ENCRYPTED, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(PREFS_KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .apply()
        Log.d(TAG, "DB passphrase stored in Android Keystore")
        return passphrase
    }

    private fun getKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        return keyStore.getKey(KEYSTORE_ALIAS, null) as SecretKey
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            return keyStore.getKey(KEYSTORE_ALIAS, null) as SecretKey
        }
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        keyGen.init(
            KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGen.generateKey()
    }

    /**
     * Application.onCreate에서 호출 — passphrase를 미리 계산하여
     * 첫 DB 접근 시 SQLCipher 초기화 지연을 제거
     */
    fun warmUpPassphrase() {
        openHelperFactory  // lazy 프로퍼티 접근 → getOrCreatePassphrase() 실행
    }

    private var currentUserId: String? = null

    // 공통 DB
    private var commonDb: CommonDatabase? = null

    // 사용자별 DB
    private var orgDb: OrgDatabase? = null
    private var chatDb: ChatDatabase? = null
    private var messageDb: MessageDatabase? = null
    private var notiDb: NotiDatabase? = null
    private var settingDb: SettingDatabase? = null
    private var projectDb: ProjectDatabase? = null

    private fun getBaseDir(): File {
        return File(context.filesDir, DB_BASE_DIR).also {
            if (!it.exists()) it.mkdirs()
        }
    }

    private fun getUserDir(userId: String): File {
        return File(context.filesDir, "$DB_BASE_DIR${File.separator}$userId").also {
            if (!it.exists()) it.mkdirs()
        }
    }

    @Synchronized
    fun initForUser(userId: String) {
        if (currentUserId == userId && projectDb?.isOpen == true) return
        if (currentUserId != null && currentUserId != userId) {
            closeUserDatabases()
        }
        currentUserId = userId
        Log.d(TAG, "Initialized for user: $userId, dbPath: ${getUserDir(userId).absolutePath}")
    }

    // ── 공통 DB ──

    @Synchronized
    fun getCommonDatabase(): CommonDatabase {
        commonDb?.let { if (it.isOpen) return it }
        val dbFile = File(getBaseDir(), "common.db")
        val db = Room.databaseBuilder(context, CommonDatabase::class.java, dbFile.absolutePath)
            .openHelperFactory(openHelperFactory)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            // common.db: REST syncConfig 로 재빌드 가능 (엔드포인트/테마 등 서버 config). destructive OK.
            .fallbackToDestructiveMigration()
            .build()
        commonDb = db
        return db
    }

    // ── 사용자별 DB ──
    // 데이터 중요 DB(org/chat/message/noti/project)는 destructive fallback 제거.
    // 스키마 bump 시 명시 migration 추가 없이 배포하면 Room 이 IllegalStateException 으로 크래시 → 개발자가 즉시 인지.
    // 이전 silent 데이터 전체 삭제 사고 방지. version 을 올리면 반드시 MIGRATION_N_M 작성 필수.

    @Synchronized
    fun getOrgDatabase(): OrgDatabase {
        val userId = requireUserId()
        orgDb?.let { if (it.isOpen) return it }
        val dbFile = File(getUserDir(userId), "org.db")
        val db = Room.databaseBuilder(context, OrgDatabase::class.java, dbFile.absolutePath)
            .openHelperFactory(openHelperFactory)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()
        orgDb = db
        return db
    }

    @Synchronized
    fun getChatDatabase(): ChatDatabase {
        val userId = requireUserId()
        chatDb?.let { if (it.isOpen) return it }
        val dbFile = File(getUserDir(userId), "chat.db")
        val db = Room.databaseBuilder(context, ChatDatabase::class.java, dbFile.absolutePath)
            .openHelperFactory(openHelperFactory)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addMigrations(ChatDatabase.MIGRATION_1_2, ChatDatabase.MIGRATION_2_3)
            .build()
        chatDb = db
        return db
    }

    @Synchronized
    fun getMessageDatabase(): MessageDatabase {
        val userId = requireUserId()
        messageDb?.let { if (it.isOpen) return it }
        val dbFile = File(getUserDir(userId), "message.db")
        val db = Room.databaseBuilder(context, MessageDatabase::class.java, dbFile.absolutePath)
            .openHelperFactory(openHelperFactory)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addMigrations(MessageDatabase.MIGRATION_1_2, MessageDatabase.MIGRATION_2_3)
            .build()
        messageDb = db
        return db
    }

    @Synchronized
    fun getNotiDatabase(): NotiDatabase {
        val userId = requireUserId()
        notiDb?.let { if (it.isOpen) return it }
        val dbFile = File(getUserDir(userId), "noti.db")
        val db = Room.databaseBuilder(context, NotiDatabase::class.java, dbFile.absolutePath)
            .openHelperFactory(openHelperFactory)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addMigrations(NotiDatabase.MIGRATION_1_2)
            .build()
        notiDb = db
        return db
    }

    @Synchronized
    fun getProjectDatabase(): ProjectDatabase {
        val userId = requireUserId()
        projectDb?.let { if (it.isOpen) return it }
        val dbFile = File(getUserDir(userId), "project.db")
        val db = Room.databaseBuilder(context, ProjectDatabase::class.java, dbFile.absolutePath)
            .openHelperFactory(openHelperFactory)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addMigrations(ProjectDatabase.MIGRATION_1_2)
            .build()
        projectDb = db
        return db
    }

    @Synchronized
    fun getSettingDatabase(): SettingDatabase {
        val userId = requireUserId()
        settingDb?.let { if (it.isOpen) return it }
        val dbFile = File(getUserDir(userId), "setting.db")
        val db = Room.databaseBuilder(context, SettingDatabase::class.java, dbFile.absolutePath)
            .openHelperFactory(openHelperFactory)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            // setting.db: 로컬 유저 설정만 저장. 재빌드 가능 — destructive OK.
            .fallbackToDestructiveMigration()
            .build()
        settingDb = db
        return db
    }

    @Synchronized
    fun closeUserDatabases() {
        safeClose(orgDb, "org.db"); orgDb = null
        safeClose(chatDb, "chat.db"); chatDb = null
        safeClose(messageDb, "message.db"); messageDb = null
        safeClose(notiDb, "noti.db"); notiDb = null
        safeClose(settingDb, "setting.db"); settingDb = null
        safeClose(projectDb, "project.db"); projectDb = null
        currentUserId = null
        Log.d(TAG, "User databases closed")
    }

    @Synchronized
    fun closeAll() {
        closeUserDatabases()
        safeClose(commonDb, "common.db"); commonDb = null
        Log.d(TAG, "All databases closed")
    }

    private fun safeClose(db: androidx.room.RoomDatabase?, name: String) {
        try {
            db?.let { if (it.isOpen) it.close() }
        } catch (e: Exception) {
            Log.w(TAG, "Error closing $name: ${e.message}")
        }
    }

    fun deleteUserData(userId: String) {
        val userDir = File(context.filesDir, "$DB_BASE_DIR${File.separator}$userId")
        if (userDir.exists()) {
            userDir.deleteRecursively()
            Log.d(TAG, "Deleted user data for: $userId")
        }
    }

    /** 현재 초기화된 userId (push 이벤트에서 상대방 판별에 사용) */
    fun getCurrentUserId(): String? = currentUserId

    /** initForUser() 호출 여부 */
    fun isInitialized(): Boolean = currentUserId != null

    private fun requireUserId(): String {
        return currentUserId
            ?: throw IllegalStateException("DatabaseProvider not initialized. Call initForUser() first.")
    }
}
