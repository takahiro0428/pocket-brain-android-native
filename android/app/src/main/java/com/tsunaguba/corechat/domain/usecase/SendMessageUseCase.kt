package com.tsunaguba.corechat.domain.usecase

import com.tsunaguba.corechat.domain.model.ChatMessage
import com.tsunaguba.corechat.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class SendMessageUseCase @Inject constructor(
    private val repository: ChatRepository,
) {
    operator fun invoke(history: List<ChatMessage>, prompt: String): Flow<String> =
        repository.sendMessage(history, prompt)
}
