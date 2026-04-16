package net.spacenx.messenger.data.repository

import android.util.Log
import androidx.collection.LruCache
import net.spacenx.messenger.data.local.DatabaseProvider
import org.json.JSONObject

/**
 * NHM-63: userId → userName 인메모리 캐시
 * orgReady / 로그아웃 시 clear
 *
 * LRU 제한(2000) — 5000+ 조직에서도 메모리 폭증 방지.
 * `androidx.collection.LruCache` 는 내부적으로 synchronized 보장.
 */
class UserNameCache(private val dbProvider: DatabaseProvider) {

    companion object {
        private const val TAG = "UserNameCache"
        private const val MAX_ENTRIES = 2000
    }

    private val cache = LruCache<String, String>(MAX_ENTRIES)

    fun get(userId: String): String? = cache.get(userId)

    /**
     * userId → "이름 직위" 형태로 반환.
     * 캐시에 없으면 orgDb에서 조회 후 캐시.
     */
    suspend fun resolve(userId: String): String {
        if (userId.isEmpty()) return ""
        cache.get(userId)?.let { return it }

        val orgDb = try { dbProvider.getOrgDatabase() } catch (_: Exception) { return userId }
        try {
            // 1) users 테이블 조회
            val user = orgDb.userDao().getByUserId(userId)
            if (user != null && user.userInfo.isNotEmpty()) {
                val info = JSONObject(user.userInfo)
                val userName = info.optString("userName", userId)
                val posName = info.optString("posName", "")
                val name = if (posName.isNotEmpty()) "$userName $posName" else userName
                cache.put(userId, name)
                return name
            }
            // 2) buddies 테이블 조회 (내목록 사용자, buddyId = "userId" 또는 "userId@^^@...")
            val buddy = orgDb.buddyDao().getByUserId(userId)
            if (buddy != null && buddy.buddyName.isNotEmpty()) {
                cache.put(userId, buddy.buddyName)
                return buddy.buddyName
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolve($userId) error: ${e.message}")
        }
        cache.put(userId, userId)
        return userId
    }

    fun clear() {
        cache.evictAll()
        Log.d(TAG, "cache cleared")
    }
}
