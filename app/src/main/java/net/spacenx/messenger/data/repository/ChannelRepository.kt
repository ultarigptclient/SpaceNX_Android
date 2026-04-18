package net.spacenx.messenger.data.repository

import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.data.local.ChatDatabase
import net.spacenx.messenger.data.local.DatabaseProvider
import net.spacenx.messenger.service.socket.SocketSessionManager
import org.json.JSONObject

/**
 * 채널 Repository 파사드.
 *
 * 기존 호출자(Handler, SyncService, PushEventHandler 등)의 변경 없이
 * 도메인별로 분리된 Repository에 위임한다.
 *
 * - 채널·채팅 동기화 → ChannelSyncRepository
 * - 채널 조회·검색   → ChannelQueryRepository
 * - 채널·채팅 생성   → ChannelActionRepository
 * - JSON 유틸리티   → ChannelUtils
 */
class ChannelRepository(
    databaseProvider: DatabaseProvider,
    appConfig: AppConfig,
    sessionManager: SocketSessionManager
) {
    companion object {
        suspend fun enrichChannelAdditional(channelAdditional: String, chatDb: ChatDatabase): JSONObject =
            ChannelUtils.enrichChannelAdditional(channelAdditional, chatDb)

        fun sanitizeAdditional(additional: String): String =
            ChannelUtils.sanitizeAdditional(additional)
    }

    private val sync = ChannelSyncRepository(databaseProvider, appConfig, sessionManager)
    private val query = ChannelQueryRepository(databaseProvider, appConfig, sessionManager)
    private val action = ChannelActionRepository(databaseProvider, appConfig, sessionManager, query)

    // ── 동기화 ──
    suspend fun syncChannel(userId: String): Boolean = sync.syncChannel(userId)
    suspend fun syncChannelFull(userId: String): Boolean = sync.syncChannelFull(userId)
    suspend fun syncChat(userId: String): Boolean = sync.syncChat(userId)

    // ── 조회 ──
    suspend fun getChannelCount(): Int = query.getChannelCount()
    suspend fun getChannelListAsJson(): String = query.getChannelListAsJson()
    suspend fun openChannelRoom(channelCode: String): String = query.openChannelRoom(channelCode)
    suspend fun searchChannelRoom(keyword: String, type: String): String = query.searchChannelRoom(keyword, type)

    // ── 생성·전송 ──
    suspend fun makeChannelWithUserName(channelCode: String, sendUserId: String, targetUserId: String): String =
        action.makeChannelWithUserName(channelCode, sendUserId, targetUserId)

    suspend fun makeChannel(channelCode: String, channelName: String, sendUserId: String, users: List<String>): String =
        action.makeChannel(channelCode, channelName, sendUserId, users)

    suspend fun sendChat(channelCode: String, chatCode: String, sendUserId: String, contents: String, additional: String?): String =
        action.sendChat(channelCode, chatCode, sendUserId, contents, additional)
}
