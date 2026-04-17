package com.tsunaguba.corechat.domain.usecase

import com.tsunaguba.corechat.domain.repository.ChatRepository
import javax.inject.Inject

class RetryEngineUseCase @Inject constructor(
    private val repository: ChatRepository,
) {
    suspend operator fun invoke() = repository.retry()
}
