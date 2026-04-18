package net.spacenx.messenger.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.data.local.DatabaseProvider
import net.spacenx.messenger.data.repository.AuthRepository
import net.spacenx.messenger.data.repository.BuddyRepository
import net.spacenx.messenger.data.repository.ChannelRepository
import net.spacenx.messenger.data.repository.MessageRepository
import net.spacenx.messenger.data.repository.NotiRepository
import net.spacenx.messenger.data.repository.OrgRepository
import net.spacenx.messenger.data.repository.ProjectRepository
import net.spacenx.messenger.data.repository.PubSubRepository
import net.spacenx.messenger.data.repository.StatusRepository
import net.spacenx.messenger.data.cache.UserNameCache
import net.spacenx.messenger.service.socket.SocketSessionManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideAuthRepository(
        appConfig: AppConfig,
        sessionManager: SocketSessionManager,
        databaseProvider: DatabaseProvider
    ): AuthRepository = AuthRepository(appConfig, sessionManager, databaseProvider)

    @Provides
    @Singleton
    fun provideBuddyRepository(
        databaseProvider: DatabaseProvider,
        appConfig: AppConfig,
        sessionManager: SocketSessionManager
    ): BuddyRepository = BuddyRepository(databaseProvider, appConfig, sessionManager)

    @Provides
    @Singleton
    fun provideOrgRepository(
        databaseProvider: DatabaseProvider,
        appConfig: AppConfig,
        sessionManager: SocketSessionManager
    ): OrgRepository = OrgRepository(databaseProvider, appConfig, sessionManager)

    @Provides
    @Singleton
    fun provideChannelRepository(
        databaseProvider: DatabaseProvider,
        appConfig: AppConfig,
        sessionManager: SocketSessionManager
    ): ChannelRepository = ChannelRepository(databaseProvider, appConfig, sessionManager)

    @Provides
    @Singleton
    fun provideStatusRepository(
        appConfig: AppConfig,
        sessionManager: SocketSessionManager
    ): StatusRepository = StatusRepository(appConfig, sessionManager)

    @Provides
    @Singleton
    fun provideMessageRepository(
        databaseProvider: DatabaseProvider,
        appConfig: AppConfig,
        sessionManager: SocketSessionManager
    ): MessageRepository = MessageRepository(databaseProvider, appConfig, sessionManager)

    @Provides
    @Singleton
    fun provideNotiRepository(
        databaseProvider: DatabaseProvider,
        appConfig: AppConfig,
        sessionManager: SocketSessionManager
    ): NotiRepository = NotiRepository(databaseProvider, appConfig, sessionManager)

    @Provides
    @Singleton
    fun providePubSubRepository(
        sessionManager: SocketSessionManager
    ): PubSubRepository = PubSubRepository(sessionManager)

    @Provides
    @Singleton
    fun provideProjectRepository(
        databaseProvider: DatabaseProvider,
        appConfig: AppConfig,
        sessionManager: SocketSessionManager
    ): ProjectRepository = ProjectRepository(databaseProvider, appConfig, sessionManager)

    @Provides
    @Singleton
    fun provideUserNameCache(
        databaseProvider: DatabaseProvider
    ): UserNameCache = UserNameCache(databaseProvider)
}
