package com.opencode.app.di

import android.content.Context
import androidx.room.Room
import com.opencode.app.data.local.OpenCodeDatabase
import com.opencode.app.data.local.SecureCredentialStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OpenCodeDatabase =
        Room.databaseBuilder(context, OpenCodeDatabase::class.java, "opencode.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideSecureCredentialStore(@ApplicationContext context: Context): SecureCredentialStore =
        SecureCredentialStore(context)

    @Provides
    @Singleton
    fun provideAppSettings(@ApplicationContext context: Context): com.opencode.app.data.settings.AppSettings =
        com.opencode.app.data.settings.AppSettings(context)

    @Provides
    @Singleton
    fun provideSessionStore(app: android.app.Application, cm: com.opencode.app.data.remote.ServerConnectionManager, notifier: com.opencode.app.notification.AppNotifier): com.opencode.app.data.session.SessionStore =
        com.opencode.app.data.session.SessionStore(app, cm, notifier)

    @Provides
    @Singleton
    fun provideOpenedDirectoryDao(db: OpenCodeDatabase): com.opencode.app.data.local.OpenedDirectoryDao =
        db.openedDirectoryDao()
}
