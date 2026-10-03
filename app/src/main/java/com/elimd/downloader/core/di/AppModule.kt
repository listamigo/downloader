package com.elimd.downloader.core.di

import android.content.Context
import androidx.room.Room
import com.elimd.downloader.core.database.AppDatabase
import com.elimd.downloader.core.database.DownloadDataSourceImpl
import com.elimd.downloader.core.datastore.DataStoreSettingsDataSource
import com.elimd.downloader.core.download.DownloadEngineImpl
import com.elimd.downloader.core.extract.MediaResolver
import com.elimd.downloader.core.extract.NewPipeMediaResolver
import com.elimd.downloader.core.extract.OkHttpNewPipeDownloader
import com.elimd.downloader.core.network.YouTubeSearchDataSourceImpl
import com.elimd.downloader.core.network.YouTubeSession
import com.elimd.downloader.data.repository.DownloadRepositoryImpl
import com.elimd.downloader.data.repository.SettingsRepositoryImpl
import com.elimd.downloader.data.repository.YouTubeSearchRepositoryImpl
import com.elimd.downloader.data.source.DownloadDataSource
import com.elimd.downloader.data.source.DownloadEngine
import com.elimd.downloader.data.source.SettingsDataSource
import com.elimd.downloader.data.source.YouTubeSearchDataSource
import com.elimd.downloader.domain.repository.DownloadRepository
import com.elimd.downloader.domain.repository.SettingsRepository
import com.elimd.downloader.domain.repository.YouTubeSearchRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
    }

    @Provides
    @Singleton
    fun provideDownloadDataSource(appDatabase: AppDatabase): DownloadDataSource {
        return DownloadDataSourceImpl(appDatabase)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    fun provideSettingsDataSource(@ApplicationContext context: Context): SettingsDataSource {
        return DataStoreSettingsDataSource(context)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Provides
    @Singleton
    fun provideNewPipeDownloader(
        client: OkHttpClient,
        session: YouTubeSession
    ): OkHttpNewPipeDownloader = OkHttpNewPipeDownloader(client, session)

    @Provides
    @Singleton
    fun provideYouTubeSearchDataSource(
        @ApplicationContext context: Context,
        resolver: MediaResolver,
        client: OkHttpClient
    ): YouTubeSearchDataSource = YouTubeSearchDataSourceImpl(context, resolver, client)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindYouTubeSearchRepository(impl: YouTubeSearchRepositoryImpl): YouTubeSearchRepository

    @Binds
    @Singleton
    abstract fun bindDownloadRepository(impl: DownloadRepositoryImpl): DownloadRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindDownloadEngine(impl: DownloadEngineImpl): DownloadEngine

    @Binds
    @Singleton
    abstract fun bindMediaResolver(impl: NewPipeMediaResolver): MediaResolver
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context = context.applicationContext
}
