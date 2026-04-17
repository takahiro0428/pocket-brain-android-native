package com.tsunaguba.corechat.ui.chat

import app.cash.turbine.test
import com.tsunaguba.corechat.domain.model.AiModelStatus
import com.tsunaguba.corechat.domain.model.Role
import com.tsunaguba.corechat.domain.repository.ChatRepository
import com.tsunaguba.corechat.domain.usecase.ObserveModelStatusUseCase
import com.tsunaguba.corechat.domain.usecase.SendMessageUseCase
import com.tsunaguba.corechat.testing.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private fun vm(
        sendFlow: kotlinx.coroutines.flow.Flow<String> = flowOf("Hello", ", ", "world"),
        statusFlow: MutableStateFlow<AiModelStatus> = MutableStateFlow(AiModelStatus.Ready),
    ): ChatViewModel {
        val repo = mockk<ChatRepository>(relaxed = true)
        every { repo.status } returns statusFlow
        every { repo.sendMessage(any(), any()) } returns sendFlow
        return ChatViewModel(
            sendMessage = SendMessageUseCase(repo),
            observeStatus = ObserveModelStatusUseCase(repo),
        )
    }

    @Test
    fun `send aggregates streaming chunks into a single assistant message`() = runTest {
        val model = vm()

        model.uiState.test {
            awaitItem() // initial

            model.send("hi")
            advanceUntilIdle()

            // Drain any intermediate states; take the final one.
            var last = awaitItem()
            while (last.isSending || last.messages.lastOrNull()?.isStreaming == true) {
                last = awaitItem()
            }

            assertEquals(2, last.messages.size)
            assertEquals(Role.USER, last.messages[0].role)
            assertEquals("hi", last.messages[0].content)
            assertEquals(Role.ASSISTANT, last.messages[1].role)
            assertEquals("Hello, world", last.messages[1].content)
            assertFalse(last.messages[1].isStreaming)
            assertFalse(last.isSending)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `empty prompt does not trigger send`() = runTest {
        val model = vm()
        model.send("   ")
        advanceUntilIdle()
        assertTrue(model.uiState.value.messages.isEmpty())
        assertFalse(model.uiState.value.isSending)
    }

    @Test
    fun `status updates propagate into uiState`() = runTest {
        val status = MutableStateFlow<AiModelStatus>(AiModelStatus.Initializing)
        val model = vm(statusFlow = status)

        advanceUntilIdle()
        assertEquals(AiModelStatus.Initializing, model.uiState.value.status)

        status.value = AiModelStatus.CloudFallback
        advanceUntilIdle()
        assertEquals(AiModelStatus.CloudFallback, model.uiState.value.status)
    }
}
