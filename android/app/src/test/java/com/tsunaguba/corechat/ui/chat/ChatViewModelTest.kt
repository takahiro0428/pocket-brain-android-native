package com.tsunaguba.corechat.ui.chat

import com.tsunaguba.corechat.domain.model.AiEngineMode
import com.tsunaguba.corechat.domain.model.AiModelStatus
import com.tsunaguba.corechat.domain.model.Role
import com.tsunaguba.corechat.domain.repository.ChatRepository
import com.tsunaguba.corechat.domain.usecase.ObserveModelStatusUseCase
import com.tsunaguba.corechat.domain.usecase.RetryEngineUseCase
import com.tsunaguba.corechat.domain.usecase.SendMessageUseCase
import com.tsunaguba.corechat.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
        statusFlow: MutableStateFlow<AiModelStatus> =
            MutableStateFlow(AiModelStatus.Ready(AiEngineMode.OnDevice)),
        repo: ChatRepository = mockk(relaxed = false) {
            every { status } returns statusFlow
            every { sendMessage(any(), any()) } returns sendFlow
            coEvery { retry() } returns Unit
        },
    ): Pair<ChatViewModel, ChatRepository> {
        val model = ChatViewModel(
            sendMessage = SendMessageUseCase(repo),
            retryEngine = RetryEngineUseCase(repo),
            observeStatus = ObserveModelStatusUseCase(repo),
        )
        return model to repo
    }

    @Test
    fun `send aggregates streaming chunks into a single assistant message`() =
        runTest(mainRule.testDispatcher.scheduler) {
            val (model, _) = vm()

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
        val (model, _) = vm()
        model.send("   ")
        assertTrue(model.uiState.value.messages.isEmpty())
        assertFalse(model.uiState.value.isSending)
    }

    @Test
    fun `status updates propagate into uiState`() = runTest(mainRule.testDispatcher.scheduler) {
        val status = MutableStateFlow<AiModelStatus>(AiModelStatus.Initializing)
        val (model, _) = vm(statusFlow = status)

        // UnconfinedTestDispatcher flushes observeStatus() synchronously during init.
        assertEquals(AiModelStatus.Initializing, model.uiState.value.status)

        val cloudReady = AiModelStatus.Ready(AiEngineMode.Cloud)
        status.value = cloudReady
        assertEquals(cloudReady, model.uiState.value.status)
    }

    @Test
    fun `send is skipped when status is Unavailable`() =
        runTest(mainRule.testDispatcher.scheduler) {
            val status = MutableStateFlow<AiModelStatus>(AiModelStatus.Unavailable)
            val (model, repo) = vm(statusFlow = status)

            model.send("hi")

            assertTrue(model.uiState.value.messages.isEmpty())
            assertFalse(model.uiState.value.isSending)
            verify(exactly = 0) { repo.sendMessage(any(), any()) }
        }

    @Test
    fun `send is skipped when status is Error`() =
        runTest(mainRule.testDispatcher.scheduler) {
            val status = MutableStateFlow<AiModelStatus>(AiModelStatus.Error("boom"))
            val (model, repo) = vm(statusFlow = status)

            model.send("hi")

            assertTrue(model.uiState.value.messages.isEmpty())
            assertFalse(model.uiState.value.isSending)
            verify(exactly = 0) { repo.sendMessage(any(), any()) }
        }

    @Test
    fun `send is skipped when status is Initializing`() =
        runTest(mainRule.testDispatcher.scheduler) {
            val status = MutableStateFlow<AiModelStatus>(AiModelStatus.Initializing)
            val (model, repo) = vm(statusFlow = status)

            model.send("hi")

            assertTrue(model.uiState.value.messages.isEmpty())
            verify(exactly = 0) { repo.sendMessage(any(), any()) }
        }

    @Test
    fun `stream failure sets sendFailed flag and clearError resets it`() =
        runTest(mainRule.testDispatcher.scheduler) {
            val failing = flow<String> { throw IllegalStateException("boom") }
            val (model, _) = vm(sendFlow = failing)

            model.send("hi")

            val afterFail = model.uiState.first { !it.isSending }
            assertTrue("sendFailed should be true after stream error", afterFail.sendFailed)

            model.clearError()
            assertFalse(model.uiState.value.sendFailed)
        }

    @Test
    fun `successful send does not set sendFailed`() =
        runTest(mainRule.testDispatcher.scheduler) {
            val (model, _) = vm()

            model.send("hi")

            val final = model.uiState.first { !it.isSending }
            assertFalse(final.sendFailed)
        }
}
