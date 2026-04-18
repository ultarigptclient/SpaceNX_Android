package net.spacenx.messenger.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.data.local.DatabaseProvider
import net.spacenx.messenger.data.repository.ChannelRepository
import net.spacenx.messenger.data.repository.MessageRepository
import net.spacenx.messenger.data.repository.NotiRepository
import net.spacenx.messenger.service.push.PushEventHandler
import net.spacenx.messenger.service.push.GroupNotificationManager
import net.spacenx.messenger.service.push.NotificationGroupManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PushModule {

    @Provides
    @Singleton
    fun providePushEventHandler(
        databaseProvider: DatabaseProvider,
        channelRepository: ChannelRepository,
        messageRepository: MessageRepository,
        notiRepository: NotiRepository,
        appConfig: AppConfig
    ): PushEventHandler = PushEventHandler(databaseProvider, channelRepository, messageRepository, notiRepository, appConfig)

    @Provides
    @Singleton
    fun provideGroupNotificationManager(
        @ApplicationContext context: Context,
        appConfig: AppConfig
    ): GroupNotificationManager = NotificationGroupManager(context, appConfig)
}
