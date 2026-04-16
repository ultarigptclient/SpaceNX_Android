package net.spacenx.messenger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.data.local.ChatDatabase
import net.spacenx.messenger.data.local.DatabaseProvider
import net.spacenx.messenger.data.local.dao.ChannelDao
import net.spacenx.messenger.data.local.entity.ChannelEntity
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChannelQueryRepositoryTest {

    private val databaseProvider: DatabaseProvider = mockk()
    private val appConfig: AppConfig = mockk()
    private val sessionManager: SocketSessionManager = mockk()

    private lateinit var repo: ChannelQueryRepository

    @Before
    fun setUp() {
        repo = ChannelQueryRepository(databaseProvider, appConfig, sessionManager)
    }

    @Test
    fun getChannelCount_returns_zero_when_no_channels() = runTest {
        val mockDb = mockk<ChatDatabase>()
        val mockDao = mockk<ChannelDao>()
        coEvery { databaseProvider.getChatDatabase() } returns mockDb
        every { mockDb.channelDao() } returns mockDao
        coEvery { mockDao.getAll() } returns emptyList()

        assertEquals(0, repo.getChannelCount())
    }

    @Test
    fun getChannelCount_returns_channel_list_size() = runTest {
        val mockDb = mockk<ChatDatabase>()
        val mockDao = mockk<ChannelDao>()
        coEvery { databaseProvider.getChatDatabase() } returns mockDb
        every { mockDb.channelDao() } returns mockDao
        coEvery { mockDao.getAll() } returns listOf(
            channelEntity("CH001"),
            channelEntity("CH002"),
            channelEntity("CH003")
        )

        assertEquals(3, repo.getChannelCount())
    }

    @Test
    fun openChannelRoom_returns_errorCode_minus1_when_channel_missing() = runTest {
        val mockDb = mockk<ChatDatabase>()
        coEvery { databaseProvider.getChatDatabase() } returns mockDb
        every { mockDb.channelDao() } returns mockk {
            coEvery { getByChannelCode("NOTEXIST") } returns null
        }

        val result = repo.openChannelRoom("NOTEXIST")

        assertEquals(-1, org.json.JSONObject(result).getInt("errorCode"))
    }

    @Test
    fun openChannelRoom_returns_errorCode_minus1_on_exception() = runTest {
        coEvery { databaseProvider.getChatDatabase() } throws RuntimeException("DB error")

        val result = repo.openChannelRoom("CH001")

        assertEquals(-1, org.json.JSONObject(result).getInt("errorCode"))
    }

    @Test
    fun searchChannelRoom_returns_errorCode_minus1_on_exception() = runTest {
        coEvery { databaseProvider.getChatDatabase() } throws RuntimeException("DB error")

        val result = repo.searchChannelRoom("keyword", "channel")

        assertEquals(-1, org.json.JSONObject(result).getInt("errorCode"))
    }

    private fun channelEntity(channelCode: String) = ChannelEntity(
        channelCode = channelCode,
        channelName = "ch-$channelCode"
    )
}
