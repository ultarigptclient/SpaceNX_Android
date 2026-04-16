package net.spacenx.messenger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.data.local.ChatDatabase
import net.spacenx.messenger.data.local.DatabaseProvider
import net.spacenx.messenger.data.local.dao.SyncMetaDao
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChannelSyncRepositoryTest {

    private val databaseProvider: DatabaseProvider = mockk()
    private val appConfig: AppConfig = mockk()
    private val sessionManager: SocketSessionManager = mockk()

    private lateinit var repo: ChannelSyncRepository

    @Before
    fun setUp() {
        repo = ChannelSyncRepository(databaseProvider, appConfig, sessionManager)
    }

    @Test
    fun syncChannel_returns_false_on_db_exception() = runTest {
        every { sessionManager.jwtToken } returns "test-token"
        coEvery { databaseProvider.getChatDatabase() } throws RuntimeException("DB not ready")

        assertFalse(repo.syncChannel("user1"))
    }

    @Test(expected = RuntimeException::class)
    fun syncChannelFull_propagates_db_exception() = runTest {
        // syncChannelFull 은 syncChannel 과 달리 try-catch 가 없어 예외를 그대로 전파한다.
        every { sessionManager.jwtToken } returns "test-token"
        val mockDb = mockk<ChatDatabase>()
        val mockSyncMetaDao = mockk<SyncMetaDao>()
        coEvery { databaseProvider.getChatDatabase() } returns mockDb
        every { mockDb.syncMetaDao() } returns mockSyncMetaDao
        coEvery { mockSyncMetaDao.deleteByKey(any()) } throws RuntimeException("DB error")

        repo.syncChannelFull("user1") // should throw
    }

    @Test
    fun syncChat_returns_false_on_db_exception() = runTest {
        every { sessionManager.jwtToken } returns "test-token"
        coEvery { databaseProvider.getChatDatabase() } throws RuntimeException("DB not ready")

        assertFalse(repo.syncChat("user1"))
    }

    @Test
    fun syncChannel_reentrant_call_returns_true_while_mutex_held() = runTest {
        every { sessionManager.jwtToken } returns "test-token"

        val mockDb = mockk<ChatDatabase>(relaxed = true)
        val mockSyncMetaDao = mockk<SyncMetaDao>(relaxed = true)
        every { mockDb.syncMetaDao() } returns mockSyncMetaDao
        coEvery { mockSyncMetaDao.getValueSync(any()) } returns 0L

        // 첫 호출이 mutex 를 잡은 채 지연하도록 DB 접근을 지연시킴
        coEvery { databaseProvider.getChatDatabase() } coAnswers {
            delay(2000L)
            mockDb
        }

        val firstJob = async { repo.syncChannel("user1") }
        delay(100L) // 첫 호출이 mutex 를 잡을 시간

        val secondResult = repo.syncChannel("user1")

        assertTrue(secondResult)
        firstJob.cancel()
    }
}
