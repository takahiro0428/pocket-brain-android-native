package com.tsunaguba.corechat.di

import android.content.Context
import com.tsunaguba.corechat.BuildConfig
import com.tsunaguba.corechat.data.ai.AiCoreEngine
import com.tsunaguba.corechat.data.ai.AiEngineProvider
import com.tsunaguba.corechat.data.ai.CloudGeminiEngine
import com.tsunaguba.corechat.data.ai.DefaultLlmEngineFactory
import com.tsunaguba.corechat.data.ai.GemmaModelDownloader
import com.tsunaguba.corechat.data.ai.LlmEngineFactory
import com.tsunaguba.corechat.data.ai.MediaPipeLlmEngine
import com.tsunaguba.corechat.data.repository.ChatRepositoryImpl
import com.tsunaguba.corechat.domain.repository.ChatRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

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

    /**
     * Shared OkHttp client for the Gemma download. Connection/read timeouts are
     * generous because the server-side signed URL (e.g. Firebase Storage) can
     * take seconds to establish and the model download itself runs for minutes;
     * callTimeout is left unbounded for that reason.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideGemmaModelDownloader(
        @ApplicationContext context: Context,
        http: OkHttpClient,
    ): GemmaModelDownloader = GemmaModelDownloader(
        context = context,
        httpClient = http,
        expectedSha256 = BuildConfig.GEMMA_MODEL_SHA256,
    )

    @Provides
    @Singleton
    fun provideLlmEngineFactory(): LlmEngineFactory = DefaultLlmEngineFactory()

    @Provides
    @Singleton
    fun provideMediaPipeLlmEngine(
        @ApplicationContext context: Context,
        downloader: GemmaModelDownloader,
        factory: LlmEngineFactory,
    ): MediaPipeLlmEngine = MediaPipeLlmEngine(
        context = context,
        downloader = downloader,
        factory = factory,
        expectedSizeBytes = BuildConfig.GEMMA_MODEL_SIZE_BYTES,
        modelUrl = BuildConfig.GEMMA_MODEL_URL,
    )

    @Provides
    @Singleton
    fun provideAiEngineProvider(
        aicore: AiCoreEngine,
        mediapipe: MediaPipeLlmEngine,
        cloud: CloudGeminiEngine,
        @ApplicationScope scope: CoroutineScope,
    ): AiEngineProvider = AiEngineProvider(aicore, mediapipe, cloud, scope)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindChatRepository(impl: ChatRepositoryImpl): ChatRepository
}
