package com.tsunaguba.corechat.ui.chat

import com.tsunaguba.corechat.domain.model.AiModelStatus
import com.tsunaguba.corechat.domain.model.Role
import com.tsunaguba.corechat.domain.repository.ChatRepository
import com.tsunaguba.corechat.domain.usecase.ObserveModelStatusUseCase
import com.tsunaguba.corechat.domain.usecase.SendMessageUseCase
import com.tsunaguba.corechat.testing.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private fun vm(
        sendFlow: Flow<String> = flowOf("Hello", ", ", "world"),
        statusFlow: MutableStateFlow<AiModelStatus> = MutableStateFlow(AiModelStatus.Ready),
    ): ChatViewModel {
        val repo = mockk<ChatRepository>(relaxed = false)
        every { repo.status } returns statusFlow
        every { repo.sendMessage(any(), any()) } returns sendFlow
        return ChatViewModel(
            sendMessage = SendMessageUseCase(repo),
            observeStatus = ObserveModelStatusUseCase(repo),
        )
    }

    @Test
    fun `send aggregates streaming chunks into a single assistant message`() =
        runTest(mainRule.testDispatcher.scheduler) {
            val model = vm()

            model.send("hi")

            val final = model.uiState.first { !it.isSending }
            assertEquals(2, final.messages.size)
            assertEquals(Role.USER, final.messages[0].role)
            assertEquals("hi", final.messages[0].content)
            assertEquals(Role.ASSISTANT, final.messages[1].role)
            assertEquals("Hello, world", final.messages[1].content)
            assertFalse(final.messages[1].isStreaming)
            assertFalse(final.isSending)
        }

    @Test
    fun `empty prompt does not trigger send`() = runTest(mainRule.testDispatcher.scheduler) {
        val model = vm()
        model.send("   ")
        assertTrue(model.uiState.value.messages.isEmpty())
        assertFalse(model.uiState.value.isSending)
    }

    @Test
    fun `status updates propagate into uiState`() = runTest(mainRule.testDispatcher.scheduler) {
        val status = MutableStateFlow<AiModelStatus>(AiModelStatus.Initializing)
        val model = vm(statusFlow = status)

        // UnconfinedTestDispatcher flushes observeStatus() synchronously during init.
        assertEquals(AiModelStatus.Initializing, model.uiState.value.status)

        status.value = AiModelStatus.CloudFallback
        assertEquals(AiModelStatus.CloudFallback, model.uiState.value.status)
    }
}
