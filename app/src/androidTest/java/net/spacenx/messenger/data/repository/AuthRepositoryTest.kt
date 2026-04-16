package net.spacenx.messenger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.test.runTest
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.data.local.CommonDatabase
import net.spacenx.messenger.data.local.DatabaseProvider
import net.spacenx.messenger.data.local.dao.CommonDao
import net.spacenx.messenger.data.local.entity.CommonEntity
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AuthRepository 단위 테스트.
 * MockK 로 DatabaseProvider / AppConfig / SocketSessionManager 를 대체.
 */
@RunWith(AndroidJUnit4::class)
class AuthRepositoryTest {

    private val databaseProvider: DatabaseProvider = mockk()
    private val appConfig: AppConfig = mockk()
    // relaxed: AuthRepository 생성자가 sessionManager.loginState 를 초기화하므로
    // stub 없는 호출에 대해 기본값 반환 필요
    private val sessionManager: SocketSessionManager = mockk(relaxed = true)

    private lateinit var repo: AuthRepository

    @Before
    fun setUp() {
        repo = AuthRepository(appConfig, sessionManager, databaseProvider)
    }

    @Test
    fun loadInitialConfigCache_forwards_db_values_to_appConfig() = runTest {
        val mockDb = mockk<CommonDatabase>()
        val mockDao = mockk<CommonDao>()
        coEvery { databaseProvider.getCommonDatabase() } returns mockDb
        every { mockDb.commonDao() } returns mockDao
        coEvery { mockDao.getAll() } returns listOf(
            CommonEntity("FRONTEND_SKIN", "nx"),
            CommonEntity("FRONTEND_VERSION", "1.0.28"),
            CommonEntity("ultari.rest.base-url", "https://neo.ultari.co.kr:18019")
        )
        every { appConfig.updateConfigCache(any()) } just Runs

        repo.loadInitialConfigCache()

        coVerify {
            appConfig.updateConfigCache(
                mapOf(
                    "FRONTEND_SKIN" to "nx",
                    "FRONTEND_VERSION" to "1.0.28",
                    "ultari.rest.base-url" to "https://neo.ultari.co.kr:18019"
                )
            )
        }
    }

    @Test
    fun loadInitialConfigCache_empty_db_sends_empty_map() = runTest {
        val mockDb = mockk<CommonDatabase>()
        val mockDao = mockk<CommonDao>()
        coEvery { databaseProvider.getCommonDatabase() } returns mockDb
        every { mockDb.commonDao() } returns mockDao
        coEvery { mockDao.getAll() } returns emptyList()
        every { appConfig.updateConfigCache(any()) } just Runs

        repo.loadInitialConfigCache()

        coVerify { appConfig.updateConfigCache(emptyMap()) }
    }

    @Test
    fun loadInitialConfigCache_swallows_database_exception() = runTest {
        coEvery { databaseProvider.getCommonDatabase() } throws RuntimeException("DB not ready")

        // 예외가 외부로 전파되지 않아야 함
        repo.loadInitialConfigCache()
    }

    @Test
    fun loadInitialConfigCache_swallows_dao_exception() = runTest {
        val mockDb = mockk<CommonDatabase>()
        coEvery { databaseProvider.getCommonDatabase() } returns mockDb
        every { mockDb.commonDao() } returns mockk {
            coEvery { getAll() } throws RuntimeException("DAO error")
        }

        repo.loadInitialConfigCache()
    }
}
