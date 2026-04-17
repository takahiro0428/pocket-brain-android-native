package com.tsunaguba.corechat.domain.usecase

import com.tsunaguba.corechat.domain.model.AiModelStatus
import com.tsunaguba.corechat.domain.repository.ChatRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class ObserveModelStatusUseCase @Inject constructor(
    private val repository: ChatRepository,
) {
    operator fun invoke(): StateFlow<AiModelStatus> = repository.status
}
