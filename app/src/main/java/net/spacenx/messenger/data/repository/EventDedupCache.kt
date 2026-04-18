package net.spacenx.messenger.data.repository

import java.util.Collections

/**
 * Push 이벤트 중복 처리 방지용 LRU 셋.
 *
 * 동일 이벤트가 FCM + 소켓 양쪽으로 전달되거나, 소켓 재연결 직후 syncChat 결과와 push 가
 * 겹쳐도 한 번만 DB 에 반영되도록 한다.
 *
 * key 는 가능하면 server eventId, 없으면 chatCode/messageCode 등 unique identifier 사용.
 * 중복 검사는 `seen()` 한 번 호출로 끝남 (true 면 처리, false 면 skip).
 *
 * 1024 entries 면 메시지 1024개치 dedup window. 메모리 ~16KB. 충분히 가벼움.
 */
class EventDedupCache(private val capacity: Int = 1024) {

    // LinkedHashMap accessOrder=true → LRU. synchronized wrapper 로 thread-safe.
    private val seen: MutableSet<String> = Collections.synchronizedSet(
        Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>(capacity, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                    return size > capacity
                }
            }
        )
    )

    /**
     * 이 키를 처음 본 것이면 true (처리 진행), 이미 본 것이면 false (skip).
     * 빈 문자열 key 는 항상 true 반환 (dedup 불가, fall through).
     */
    fun seen(key: String): Boolean {
        if (key.isBlank()) return true
        synchronized(seen) {
            return seen.add(key)
        }
    }

    fun clear() {
        synchronized(seen) { seen.clear() }
    }
}
