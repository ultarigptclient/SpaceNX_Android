package net.spacenx.messenger.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import net.spacenx.messenger.data.local.NotiDatabase
import net.spacenx.messenger.data.local.entity.NotiEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotiDaoTest {

    private lateinit var db: NotiDatabase
    private lateinit var dao: NotiDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NotiDatabase::class.java
        )
            .addMigrations(NotiDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        dao = db.notiDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── 기본 CRUD ──

    @Test
    fun insert_and_getByNotiCode() = runTest {
        dao.insert(notiEntity("NOTI001"))
        val result = dao.getByNotiCode("NOTI001")
        assertNotNull(result)
        assertEquals("NOTI001", result?.notiCode)
    }

    @Test
    fun insert_duplicate_replaces() = runTest {
        dao.insert(notiEntity("NOTI001", title = "처음"))
        dao.insert(notiEntity("NOTI001", title = "변경됨"))
        assertEquals("변경됨", dao.getByNotiCode("NOTI001")?.notiTitle)
    }

    @Test
    fun getByNotiCode_returns_null_when_not_exist() = runTest {
        assertNull(dao.getByNotiCode("NOTEXIST"))
    }

    @Test
    fun deleteByNotiCode() = runTest {
        dao.insert(notiEntity("NOTI001"))
        dao.deleteByNotiCode("NOTI001")
        assertNull(dao.getByNotiCode("NOTI001"))
    }

    // ── 읽음 처리 ──

    @Test
    fun markAsRead_sets_read_flag() = runTest {
        dao.insert(notiEntity("NOTI001", read = 0))
        dao.markAsRead("NOTI001")
        assertEquals(1, dao.getByNotiCode("NOTI001")?.read)
    }

    @Test
    fun getUnread_returns_only_unread() = runTest {
        dao.insertAll(listOf(
            notiEntity("NOTI001", read = 0),
            notiEntity("NOTI002", read = 1),
            notiEntity("NOTI003", read = 0)
        ))
        val unread = dao.getUnread()
        assertEquals(2, unread.size)
        assertTrue(unread.none { it.notiCode == "NOTI002" })
    }

    // ── 카운트 ──

    @Test
    fun countAll_returns_total() = runTest {
        dao.insertAll(listOf(
            notiEntity("NOTI001"),
            notiEntity("NOTI002"),
            notiEntity("NOTI003")
        ))
        assertEquals(3, dao.countAll())
    }

    @Test
    fun countUnread_returns_only_unread_count() = runTest {
        dao.insertAll(listOf(
            notiEntity("NOTI001", read = 0),
            notiEntity("NOTI002", read = 1),
            notiEntity("NOTI003", read = 0)
        ))
        assertEquals(2, dao.countUnread())
    }

    @Test
    fun countUnread_returns_zero_when_all_read() = runTest {
        dao.insertAll(listOf(
            notiEntity("NOTI001", read = 1),
            notiEntity("NOTI002", read = 1)
        ))
        assertEquals(0, dao.countUnread())
    }

    // ── 전체 조회 ──

    @Test
    fun getAll_returns_ordered_by_sendDate_desc() = runTest {
        dao.insertAll(listOf(
            notiEntity("NOTI001", sendDate = 100L),
            notiEntity("NOTI002", sendDate = 300L),
            notiEntity("NOTI003", sendDate = 200L)
        ))
        val result = dao.getAll()
        assertEquals("NOTI002", result[0].notiCode)
        assertEquals("NOTI003", result[1].notiCode)
        assertEquals("NOTI001", result[2].notiCode)
    }

    @Test
    fun getAll_with_pagination() = runTest {
        dao.insertAll((1..5).map { notiEntity("NOTI$it", sendDate = it.toLong()) })
        val page = dao.getAll(limit = 2, offset = 0)
        assertEquals(2, page.size)
    }

    @Test
    fun deleteAll_removes_everything() = runTest {
        dao.insertAll(listOf(notiEntity("NOTI001"), notiEntity("NOTI002")))
        dao.deleteAll()
        assertEquals(0, dao.countAll())
    }

    // ── 헬퍼 ──

    private fun notiEntity(
        notiCode: String,
        title: String = "테스트 알림",
        read: Int = 0,
        sendDate: Long = System.currentTimeMillis()
    ) = NotiEntity(
        notiCode = notiCode,
        notiTitle = title,
        read = read,
        sendDate = sendDate
    )
}
