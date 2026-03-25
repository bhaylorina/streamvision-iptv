package com.streamvision.iptv.di

import android.content.Context
import androidx.room.Room
import com.streamvision.iptv.data.local.AppDatabase
import com.streamvision.iptv.data.local.ChannelDao
import com.streamvision.iptv.data.local.PlaylistDao
import com.streamvision.iptv.data.repository.ChannelRepositoryImpl
import com.streamvision.iptv.data.repository.PlaylistRepositoryImpl
import com.streamvision.iptv.domain.repository.ChannelRepository
import com.streamvision.iptv.domain.repository.PlaylistRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "streamvision_db"
        ).build()
    }

    @Provides
    @Singleton
    fun provideChannelDao(database: AppDatabase): ChannelDao = database.channelDao()

    @Provides
    @Singleton
    fun providePlaylistDao(database: AppDatabase): PlaylistDao = database.playlistDao()
}

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideChannelRepository(channelDao: ChannelDao): ChannelRepository =
        ChannelRepositoryImpl(channelDao)

    @Provides
    @Singleton
    fun providePlaylistRepository(
        playlistDao: PlaylistDao,
        channelRepository: ChannelRepository,
        @ApplicationContext context: Context
    ): PlaylistRepository = PlaylistRepositoryImpl(playlistDao, channelRepository, context)
}
