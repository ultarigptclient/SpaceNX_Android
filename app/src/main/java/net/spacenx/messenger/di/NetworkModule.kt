package net.spacenx.messenger.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.spacenx.messenger.common.AppConfig
import net.spacenx.messenger.service.socket.SocketSessionManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideSocketSessionManager(
        @ApplicationContext context: Context,
        appConfig: AppConfig
    ): SocketSessionManager = SocketSessionManager(context, appConfig)
}
