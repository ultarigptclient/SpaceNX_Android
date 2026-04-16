package net.spacenx.messenger.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import net.spacenx.messenger.data.local.MessageDatabase
import net.spacenx.messenger.data.local.entity.MessageEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MessageDao Integration Test
 *
 * SQLCipher 없이 in-memory Room DB 사용.
 * N+1 배치 메서드(getByMessageCodes, updateStateForCodes, deleteByMessageCodes) 포함 검증.
 */
@RunWith(AndroidJUnit4::class)
class MessageDaoTest {

    private lateinit var db: MessageDatabase
    private lateinit var dao: MessageDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MessageDatabase::class.java
        )
            .addMigrations(MessageDatabase.MIGRATION_1_2)
            .allowMainThreadQueries() // 테스트 편의상 허용
            .build()
        dao = db.messageDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── 기본 CRUD ──

    @Test
    fun insert_and_getByMessageCode() = runTest {
        val entity = messageEntity("MSG001")
        dao.insert(entity)

        val result = dao.getByMessageCode("MSG001")
        assertNotNull(result)
        assertEquals("MSG001", result?.messageCode)
        assertEquals("테스트 메시지", result?.contents)
    }

    @Test
    fun insert_duplicate_replaces() = runTest {
        dao.insert(messageEntity("MSG001", contents = "처음"))
        dao.insert(messageEntity("MSG001", contents = "덮어쓰기"))

        val result = dao.getByMessageCode("MSG001")
        assertEquals("덮어쓰기", result?.contents)
    }

    @Test
    fun getByMessageCode_returns_null_when_not_exist() = runTest {
        val result = dao.getByMessageCode("NOTEXIST")
        assertNull(result)
    }

    @Test
    fun deleteByMessageCode() = runTest {
        dao.insert(messageEntity("MSG001"))
        dao.deleteByMessageCode("MSG001")

        assertNull(dao.getByMessageCode("MSG001"))
    }

    @Test
    fun updateState() = runTest {
        dao.insert(messageEntity("MSG001", state = 0))
        dao.updateState("MSG001", 1)

        assertEquals(1, dao.getByMessageCode("MSG001")?.state)
    }

    // ── 배치 메서드 (N+1 방지) ──

    @Test
    fun getByMessageCodes_returns_matching_entities() = runTest {
        dao.insertAll(listOf(
            messageEntity("MSG001"),
            messageEntity("MSG002"),
            messageEntity("MSG003")
        ))

        val result = dao.getByMessageCodes(listOf("MSG001", "MSG003"))
        assertEquals(2, result.size)
        assertTrue(result.map { it.messageCode }.containsAll(listOf("MSG001", "MSG003")))
    }

    @Test
    fun getByMessageCodes_empty_list_returns_empty() = runTest {
        dao.insert(messageEntity("MSG001"))
        val result = dao.getByMessageCodes(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun updateStateForCodes_updates_all_matching() = runTest {
        dao.insertAll(listOf(
            messageEntity("MSG001", state = 0),
            messageEntity("MSG002", state = 0),
            messageEntity("MSG003", state = 0)
        ))

        dao.updateStateForCodes(listOf("MSG001", "MSG002"), 1)

        assertEquals(1, dao.getByMessageCode("MSG001")?.state)
        assertEquals(1, dao.getByMessageCode("MSG002")?.state)
        assertEquals(0, dao.getByMessageCode("MSG003")?.state) // 변경 안됨
    }

    @Test
    fun deleteByMessageCodes_deletes_only_matching() = runTest {
        dao.insertAll(listOf(
            messageEntity("MSG001"),
            messageEntity("MSG002"),
            messageEntity("MSG003")
        ))

        dao.deleteByMessageCodes(listOf("MSG001", "MSG002"))

        assertNull(dao.getByMessageCode("MSG001"))
        assertNull(dao.getByMessageCode("MSG002"))
        assertNotNull(dao.getByMessageCode("MSG003")) // 남아있어야 함
    }

    // ── 조회 쿼리 ──

    @Test
    fun getAll_returns_all_inserted() = runTest {
        dao.insertAll(listOf(
            messageEntity("MSG001"),
            messageEntity("MSG002")
        ))

        val result = dao.getAll()
        assertEquals(2, result.size)
    }

    @Test
    fun countReceived_excludes_sender() = runTest {
        dao.insertAll(listOf(
            messageEntity("MSG001", sendUserId = "user1"),  // 내가 보낸 것
            messageEntity("MSG002", sendUserId = "user2"),  // 받은 것
            messageEntity("MSG003", sendUserId = "user3")   // 받은 것
        ))

        val count = dao.countReceived("user1")
        assertEquals(2, count)
    }

    @Test
    fun countReceivedUnread_counts_unread_only() = runTest {
        dao.insertAll(listOf(
            messageEntity("MSG001", sendUserId = "user2", state = 0), // 미읽음
            messageEntity("MSG002", sendUserId = "user2", state = 1), // 읽음
            messageEntity("MSG003", sendUserId = "user2", state = 0)  // 미읽음
        ))

        val count = dao.countReceivedUnread("user1")
        assertEquals(2, count)
    }

    @Test
    fun deleteAll_removes_everything() = runTest {
        dao.insertAll(listOf(messageEntity("MSG001"), messageEntity("MSG002")))
        dao.deleteAll()

        assertTrue(dao.getAll().isEmpty())
    }

    // ── 헬퍼 ──

    private fun messageEntity(
        messageCode: String,
        sendUserId: String = "user1",
        contents: String = "테스트 메시지",
        state: Int = 0,
        sendDate: Long = System.currentTimeMillis()
    ) = MessageEntity(
        messageCode = messageCode,
        sendUserId = sendUserId,
        contents = contents,
        state = state,
        sendDate = sendDate
    )
}
