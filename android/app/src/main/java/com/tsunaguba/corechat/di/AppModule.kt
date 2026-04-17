package com.tsunaguba.corechat.di

import android.content.Context
import com.tsunaguba.corechat.BuildConfig
import com.tsunaguba.corechat.data.ai.AiCoreEngine
import com.tsunaguba.corechat.data.ai.AiEngineProvider
import com.tsunaguba.corechat.data.ai.CloudGeminiEngine
import com.tsunaguba.corechat.data.repository.ChatRepositoryImpl
import com.tsunaguba.corechat.domain.repository.ChatRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @IoDispatcher io: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + io)

    @Provides
    @Singleton
    fun provideAiCoreEngine(
        @ApplicationContext context: Context,
    ): AiCoreEngine = AiCoreEngine(context)

    @Provides
    @Singleton
    fun provideCloudGeminiEngine(): CloudGeminiEngine =
        CloudGeminiEngine(apiKey = BuildConfig.GEMINI_API_KEY)

    @Provides
    @Singleton
    fun provideAiEngineProvider(
        aicore: AiCoreEngine,
        cloud: CloudGeminiEngine,
        @ApplicationScope scope: CoroutineScope,
    ): AiEngineProvider = AiEngineProvider(aicore, cloud, scope)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindChatRepository(impl: ChatRepositoryImpl): ChatRepository
}
