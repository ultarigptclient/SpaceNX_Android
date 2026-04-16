package net.spacenx.messenger.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import net.spacenx.messenger.data.local.ChatDatabase
import net.spacenx.messenger.data.local.entity.ChannelEntity
import net.spacenx.messenger.data.local.entity.ChannelMemberEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChannelDaoTest {

    private lateinit var db: ChatDatabase
    private lateinit var channelDao: ChannelDao
    private lateinit var memberDao: ChannelMemberDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ChatDatabase::class.java
        )
            .addMigrations(ChatDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        channelDao = db.channelDao()
        memberDao = db.channelMemberDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── ChannelDao ──

    @Test
    fun insert_and_getByChannelCode() = runTest {
        channelDao.insert(channelEntity("CH01", "테스트채널"))
        val result = channelDao.getByChannelCode("CH01")
        assertNotNull(result)
        assertEquals("테스트채널", result?.channelName)
    }

    @Test
    fun insert_duplicate_replaces() = runTest {
        channelDao.insert(channelEntity("CH01", "처음"))
        channelDao.insert(channelEntity("CH01", "변경됨"))
        assertEquals("변경됨", channelDao.getByChannelCode("CH01")?.channelName)
    }

    @Test
    fun getByChannelCode_returns_null_when_not_exist() = runTest {
        assertNull(channelDao.getByChannelCode("NOTEXIST"))
    }

    @Test
    fun getAll_ordered_by_lastChatDate_desc() = runTest {
        channelDao.insertAll(listOf(
            channelEntity("CH01", lastChatDate = 100L),
            channelEntity("CH02", lastChatDate = 300L),
            channelEntity("CH03", lastChatDate = 200L)
        ))
        val result = channelDao.getAll()
        assertEquals("CH02", result[0].channelCode)
        assertEquals("CH03", result[1].channelCode)
        assertEquals("CH01", result[2].channelCode)
    }

    @Test
    fun updateLastChatDate() = runTest {
        channelDao.insert(channelEntity("CH01", lastChatDate = 0L))
        channelDao.updateLastChatDate("CH01", 999L)
        assertEquals(999L, channelDao.getByChannelCode("CH01")?.lastChatDate)
    }

    @Test
    fun updateAdditional() = runTest {
        channelDao.insert(channelEntity("CH01"))
        channelDao.updateAdditional("CH01", """{"key":"value"}""")
        assertEquals("""{"key":"value"}""", channelDao.getByChannelCode("CH01")?.additional)
    }

    @Test
    fun deleteByChannelCode() = runTest {
        channelDao.insert(channelEntity("CH01"))
        channelDao.deleteByChannelCode("CH01")
        assertNull(channelDao.getByChannelCode("CH01"))
    }

    @Test
    fun deleteAll() = runTest {
        channelDao.insertAll(listOf(channelEntity("CH01"), channelEntity("CH02")))
        channelDao.deleteAll()
        assertTrue(channelDao.getAll().isEmpty())
    }

    // ── ChannelMemberDao ──

    @Test
    fun insert_member_and_getMembersByChannel() = runTest {
        memberDao.insertAll(listOf(
            memberEntity("CH01", "user1"),
            memberEntity("CH01", "user2"),
            memberEntity("CH02", "user3")
        ))
        assertEquals(2, memberDao.getMembersByChannel("CH01").size)
    }

    @Test
    fun getActiveMembersByChannel_excludes_unregistered() = runTest {
        memberDao.insertAll(listOf(
            memberEntity("CH01", "user1", unregistDate = null),   // 활성
            memberEntity("CH01", "user2", unregistDate = 1000L),  // 탈퇴
            memberEntity("CH01", "user3", unregistDate = 0L),     // 0도 활성 처리
        ))
        val active = memberDao.getActiveMembersByChannel("CH01")
        assertEquals(2, active.size)
        assertTrue(active.none { it.userId == "user2" })
    }

    @Test
    fun getMember_returns_specific_member() = runTest {
        memberDao.insert(memberEntity("CH01", "user1"))
        val result = memberDao.getMember("CH01", "user1")
        assertNotNull(result)
        assertEquals("user1", result?.userId)
    }

    @Test
    fun getMember_returns_null_when_not_exist() = runTest {
        assertNull(memberDao.getMember("CH01", "NOTEXIST"))
    }

    @Test
    fun deleteMember() = runTest {
        memberDao.insert(memberEntity("CH01", "user1"))
        memberDao.deleteMember("CH01", "user1")
        assertNull(memberDao.getMember("CH01", "user1"))
    }

    @Test
    fun deleteByChannel_removes_only_that_channel() = runTest {
        memberDao.insertAll(listOf(
            memberEntity("CH01", "user1"),
            memberEntity("CH01", "user2"),
            memberEntity("CH02", "user3")
        ))
        memberDao.deleteByChannel("CH01")
        assertTrue(memberDao.getMembersByChannel("CH01").isEmpty())
        assertEquals(1, memberDao.getMembersByChannel("CH02").size)
    }

    @Test
    fun findDMChannel_finds_exact_pair() = runTest {
        memberDao.insertAll(listOf(
            memberEntity("CH01", "userA"),
            memberEntity("CH01", "userB"),
            memberEntity("CH02", "userA"),
            memberEntity("CH02", "userC")
        ))
        val result = memberDao.findDMChannel("userA", "userB")
        assertEquals("CH01", result)
    }

    @Test
    fun findDMChannel_returns_null_when_no_dm() = runTest {
        memberDao.insertAll(listOf(
            memberEntity("CH01", "userA"),
            memberEntity("CH01", "userB"),
            memberEntity("CH01", "userC") // 3명 → DM 아님
        ))
        assertNull(memberDao.findDMChannel("userA", "userB"))
    }

    @Test
    fun unregistMemberSync_sets_unregistDate() = runTest {
        memberDao.insert(memberEntity("CH01", "user1", unregistDate = null))
        memberDao.unregistMemberSync("CH01", "user1", 5000L)
        val result = memberDao.getMember("CH01", "user1")
        assertEquals(5000L, result?.unregistDate)
    }

    // ── 헬퍼 ──

    private fun channelEntity(
        channelCode: String,
        channelName: String = "채널$channelCode",
        lastChatDate: Long = 0L
    ) = ChannelEntity(
        channelCode = channelCode,
        channelName = channelName,
        lastChatDate = lastChatDate
    )

    private fun memberEntity(
        channelCode: String,
        userId: String,
        unregistDate: Long? = null
    ) = ChannelMemberEntity(
        channelCode = channelCode,
        userId = userId,
        unregistDate = unregistDate
    )
}
