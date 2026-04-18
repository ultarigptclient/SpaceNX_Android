package net.spacenx.messenger.ui.bridge

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridge handler 의 in-flight read 요청 dedup.
 *
 * 동일 key 로 동시에 들어온 read 호출은 단 한 번만 실제 작업을 수행하고
 * 결과를 모든 caller 에게 공유한다. React 가 화면 진입 시 같은 액션을
 * 짧은 간격으로 2~3회 호출하는 패턴 (focusChannel/getChatList/getMyPart 등) 의
 * 중복 네트워크 호출 + 중복 DB 조회를 차단.
 *
 * write 액션에는 사용 금지 (멱등하지 않음).
 *
 * 사용 예:
 *   private val getOrgListDedup = InFlightDedup<String, String>()
 *   suspend fun handleGetOrgList(userId: String): String =
 *       getOrgListDedup.get(userId) { orgRepo.getOrgListAsJson(userId) }
 */
class InFlightDedup<K : Any, V> {

    private val inFlight = ConcurrentHashMap<K, CompletableDeferred<V>>()

    /**
     * key 에 대해 진행 중인 작업이 있으면 그 결과를 await,
     * 없으면 [compute] 를 실행해 결과를 공유한다.
     */
    suspend fun get(key: K, compute: suspend () -> V): V {
        val existing = inFlight[key]
        if (existing != null && existing.isActive) {
            return existing.await()
        }
        val deferred = CompletableDeferred<V>()
        val prev = inFlight.putIfAbsent(key, deferred)
        if (prev != null && prev.isActive) {
            return prev.await()
        }
        try {
            val result = compute()
            deferred.complete(result)
            return result
        } catch (e: Throwable) {
            deferred.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(key, deferred)
        }
    }
}
