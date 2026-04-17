package com.tsunaguba.corechat.data.repository

import com.tsunaguba.corechat.data.ai.AiEngineProvider
import com.tsunaguba.corechat.di.IoDispatcher
import com.tsunaguba.corechat.domain.model.AiModelStatus
import com.tsunaguba.corechat.domain.model.ChatMessage
import com.tsunaguba.corechat.domain.repository.ChatRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val engines: AiEngineProvider,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ChatRepository {

    override val status: StateFlow<AiModelStatus> = engines.status

    override fun sendMessage(history: List<ChatMessage>, prompt: String): Flow<String> =
        engines.stream(history, prompt).flowOn(io)

    override suspend fun retry() = engines.retry()
}
