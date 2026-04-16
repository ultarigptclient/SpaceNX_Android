package net.spacenx.messenger.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import net.spacenx.messenger.data.local.ChatDatabase
import net.spacenx.messenger.data.local.entity.ChatEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatDaoTest {

    private lateinit var db: ChatDatabase
    private lateinit var dao: ChatDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ChatDatabase::class.java
        )
            .addMigrations(ChatDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        dao = db.chatDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── 기본 CRUD ──

    @Test
    fun insert_and_getByChatCode() = runTest {
        dao.insert(chatEntity("CH01", "CHAT001"))
        val result = dao.getByChatCode("CHAT001")
        assertNotNull(result)
        assertEquals("CHAT001", result?.chatCode)
    }

    @Test
    fun insert_duplicate_replaces() = runTest {
        dao.insert(chatEntity("CH01", "CHAT001", contents = "처음"))
        dao.insert(chatEntity("CH01", "CHAT001", contents = "덮어쓰기"))
        assertEquals("덮어쓰기", dao.getByChatCode("CHAT001")?.contents)
    }

    @Test
    fun getByChatCode_returns_null_when_not_exist() = runTest {
        assertNull(dao.getByChatCode("NOTEXIST"))
    }

    @Test
    fun deleteByChatCode() = runTest {
        dao.insert(chatEntity("CH01", "CHAT001"))
        dao.deleteByChatCode("CHAT001")
        assertNull(dao.getByChatCode("CHAT001"))
    }

    @Test
    fun deleteByChannel_removes_only_that_channel() = runTest {
        dao.insertAll(listOf(
            chatEntity("CH01", "CHAT001"),
            chatEntity("CH01", "CHAT002"),
            chatEntity("CH02", "CHAT003")
        ))
        dao.deleteByChannel("CH01")
        assertTrue(dao.getByChannel("CH01").isEmpty())
        assertEquals(1, dao.getByChannel("CH02").size)
    }

    // ── 채널별 조회 ──

    @Test
    fun getByChannel_returns_all_for_channel() = runTest {
        dao.insertAll(listOf(
            chatEntity("CH01", "CHAT001"),
            chatEntity("CH01", "CHAT002"),
            chatEntity("CH02", "CHAT003")
        ))
        assertEquals(2, dao.getByChannel("CH01").size)
    }

    @Test
    fun getRecent_respects_limit() = runTest {
        dao.insertAll((1..10).map { chatEntity("CH01", "CHAT$it", sendDate = it.toLong()) })
        val result = dao.getRecent("CH01", 3)
        assertEquals(3, result.size)
    }

    @Test
    fun getRecent_returns_most_recent_first() = runTest {
        dao.insertAll(listOf(
            chatEntity("CH01", "CHAT001", sendDate = 100L),
            chatEntity("CH01", "CHAT002", sendDate = 300L),
            chatEntity("CH01", "CHAT003", sendDate = 200L)
        ))
        val result = dao.getRecent("CH01", 3)
        assertEquals("CHAT002", result[0].chatCode)
    }

    @Test
    fun getBeforeDate_returns_older_messages() = runTest {
        dao.insertAll(listOf(
            chatEntity("CH01", "CHAT001", sendDate = 100L),
            chatEntity("CH01", "CHAT002", sendDate = 200L),
            chatEntity("CH01", "CHAT003", sendDate = 300L)
        ))
        val result = dao.getBeforeDate("CH01", beforeDate = 300L, limit = 10)
        assertEquals(2, result.size)
        assertTrue(result.none { it.chatCode == "CHAT003" })
    }

    // ── 안읽음 카운트 (핵심 비즈니스 로직) ──

    @Test
    fun countUnread_counts_only_unread_after_offset() = runTest {
        dao.insertAll(listOf(
            chatEntity("CH01", "CHAT001", sendDate = 100L, sendUserId = "user2", state = 0),  // 미읽음
            chatEntity("CH01", "CHAT002", sendDate = 200L, sendUserId = "user2", state = 0),  // 미읽음
            chatEntity("CH01", "CHAT003", sendDate = 50L,  sendUserId = "user2", state = 0),  // offset 이전 → 제외
            chatEntity("CH01", "CHAT004", sendDate = 150L, sendUserId = "user1", state = 0),  // 내가 보낸 것 → 제외
        ))
        val count = dao.countUnread("CH01", offsetDate = 80L, myUserId = "user1")
        assertEquals(2, count)
    }

    @Test
    fun countUnread_returns_zero_when_all_read() = runTest {
        dao.insertAll(listOf(
            chatEntity("CH01", "CHAT001", sendUserId = "user2", state = 1),
            chatEntity("CH01", "CHAT002", sendUserId = "user2", state = 1)
        ))
        assertEquals(0, dao.countUnread("CH01", offsetDate = 0L, myUserId = "user1"))
    }

    @Test
    fun deleteAll_removes_everything() = runTest {
        dao.insertAll(listOf(
            chatEntity("CH01", "CHAT001"),
            chatEntity("CH02", "CHAT002")
        ))
        dao.deleteAll()
        assertTrue(dao.getByChannel("CH01").isEmpty())
        assertTrue(dao.getByChannel("CH02").isEmpty())
    }

    // ── 헬퍼 ──

    private fun chatEntity(
        channelCode: String,
        chatCode: String,
        contents: String = "테스트 채팅",
        sendDate: Long = System.currentTimeMillis(),
        sendUserId: String = "user1",
        state: Int = 0
    ) = ChatEntity(
        channelCode = channelCode,
        chatCode = chatCode,
        contents = contents,
        sendDate = sendDate,
        sendUserId = sendUserId,
        state = state
    )
}
