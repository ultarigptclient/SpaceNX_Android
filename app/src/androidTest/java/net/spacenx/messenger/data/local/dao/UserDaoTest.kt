package net.spacenx.messenger.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import net.spacenx.messenger.data.local.OrgDatabase
import net.spacenx.messenger.data.local.entity.UserEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserDaoTest {

    private lateinit var db: OrgDatabase
    private lateinit var dao: UserDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OrgDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
        dao = db.userDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── 기본 CRUD ──

    @Test
    fun insert_and_getByUserId() = runTest {
        dao.insert(userEntity("user1", deptId = "dept1"))
        val result = dao.getByUserId("user1")
        assertNotNull(result)
        assertEquals("dept1", result?.deptId)
    }

    @Test
    fun insert_duplicate_replaces() = runTest {
        dao.insert(userEntity("user1", deptId = "dept1"))
        dao.insert(userEntity("user1", deptId = "dept2"))
        assertEquals("dept2", dao.getByUserId("user1")?.deptId)
    }

    @Test
    fun getByUserId_returns_null_when_not_exist() = runTest {
        assertNull(dao.getByUserId("NOTEXIST"))
    }

    @Test
    fun getAll_returns_all() = runTest {
        dao.insertAll(listOf(userEntity("user1"), userEntity("user2"), userEntity("user3")))
        assertEquals(3, dao.getAll().size)
    }

    @Test
    fun deleteAll() = runTest {
        dao.insertAll(listOf(userEntity("user1"), userEntity("user2")))
        dao.deleteAll()
        assertTrue(dao.getAll().isEmpty())
    }

    // ── 배치 조회 (N+1 방지 — ChannelRepository 에서 사용) ──

    @Test
    fun getByUserIds_returns_matching_users() = runTest {
        dao.insertAll(listOf(
            userEntity("user1"),
            userEntity("user2"),
            userEntity("user3")
        ))
        val result = dao.getByUserIds(listOf("user1", "user3"))
        assertEquals(2, result.size)
        assertTrue(result.map { it.userId }.containsAll(listOf("user1", "user3")))
    }

    @Test
    fun getByUserIds_empty_list_returns_empty() = runTest {
        dao.insert(userEntity("user1"))
        assertTrue(dao.getByUserIds(emptyList()).isEmpty())
    }

    @Test
    fun getByUserIds_ignores_nonexistent_ids() = runTest {
        dao.insert(userEntity("user1"))
        val result = dao.getByUserIds(listOf("user1", "NOTEXIST"))
        assertEquals(1, result.size)
        assertEquals("user1", result[0].userId)
    }

    // ── 부서별 조회 ──

    @Test
    fun getByDeptId_returns_only_matching_dept() = runTest {
        dao.insertAll(listOf(
            userEntity("user1", deptId = "dept1"),
            userEntity("user2", deptId = "dept1"),
            userEntity("user3", deptId = "dept2")
        ))
        val result = dao.getByDeptId("dept1")
        assertEquals(2, result.size)
        assertTrue(result.none { it.userId == "user3" })
    }

    @Test
    fun getByDeptId_returns_empty_when_no_match() = runTest {
        dao.insert(userEntity("user1", deptId = "dept1"))
        assertTrue(dao.getByDeptId("dept99").isEmpty())
    }

    // ── 검색 ──

    @Test
    fun searchByUserInfo_finds_matching_keyword() = runTest {
        dao.insertAll(listOf(
            userEntity("user1", userInfo = """{"userName":"홍길동","deptName":"개발팀"}"""),
            userEntity("user2", userInfo = """{"userName":"김철수","deptName":"기획팀"}"""),
            userEntity("user3", userInfo = """{"userName":"홍길순","deptName":"디자인팀"}""")
        ))
        val result = dao.searchByUserInfo("홍")
        assertEquals(2, result.size)
        assertTrue(result.none { it.userId == "user2" })
    }

    @Test
    fun searchByUserInfo_returns_empty_when_no_match() = runTest {
        dao.insert(userEntity("user1", userInfo = """{"userName":"홍길동"}"""))
        assertTrue(dao.searchByUserInfo("김").isEmpty())
    }

    // ── 삭제 ──

    @Test
    fun deleteByIds_removes_only_specified() = runTest {
        dao.insertAll(listOf(
            userEntity("user1"),
            userEntity("user2"),
            userEntity("user3")
        ))
        dao.deleteByIds(listOf("user1", "user2"))
        assertNull(dao.getByUserId("user1"))
        assertNull(dao.getByUserId("user2"))
        assertNotNull(dao.getByUserId("user3"))
    }

    // ── 헬퍼 ──

    private fun userEntity(
        userId: String,
        deptId: String = "dept1",
        userInfo: String = """{"userName":"$userId"}"""
    ) = UserEntity(
        userId = userId,
        deptId = deptId,
        userInfo = userInfo
    )
}
