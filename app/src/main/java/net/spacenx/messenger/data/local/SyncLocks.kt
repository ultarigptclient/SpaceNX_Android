package net.spacenx.messenger.data.local

import kotlinx.coroutines.sync.Mutex

/**
 * 사용자별 DB 동시 쓰기 직렬화. SQLCipher Room WAL lock 경합을 줄이고
 * sync ↔ push event 간 race 를 방지한다.
 *
 * 같은 DB 를 가리키는 코루틴은 직렬화되지만, 다른 DB 끼리는 lock 이 분리되어 병렬 동작.
 *
 * 사용 예:
 *   suspend fun syncChannel(...) = SyncLocks.chatDbMutex.withLock { ... }
 *
 * 주의: lock 안에서 다른 lock 을 잡지 말 것 (deadlock 위험).
 *      Mutex 는 reentrant 가 아니므로 같은 lock 을 중첩으로 잡으면 영구 대기.
 */
object SyncLocks {
    val chatDbMutex = Mutex()
    val orgDbMutex = Mutex()
    val messageDbMutex = Mutex()
    val notiDbMutex = Mutex()
    val projectDbMutex = Mutex()
    val commonDbMutex = Mutex()
}
