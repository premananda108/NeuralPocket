package ua.pp.prema.NeuralPocket.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.pp.prema.NeuralPocket.data.Chat
import ua.pp.prema.NeuralPocket.ui.ChatUiState
import ua.pp.prema.NeuralPocket.ui.ModelStatus

class ChatUiStateTest {

    @Test
    fun `statusText returns correct string for NotLoaded`() {
        val state = ChatUiState(modelStatus = ModelStatus.NotLoaded)
        assertEquals("Model not loaded", state.statusText)
    }

    @Test
    fun `statusText returns correct string for Downloading`() {
        val state = ChatUiState(modelStatus = ModelStatus.Downloading("gemma", 45))
        assertEquals("Downloading: 45%", state.statusText)
    }

    @Test
    fun `statusText returns correct string for Ready`() {
        val state = ChatUiState(modelStatus = ModelStatus.Ready("gemma", "CPU"))
        assertEquals("gemma [CPU]", state.statusText)
    }

    @Test
    fun `currentChat returns null when chats list is empty`() {
        val state = ChatUiState(chats = emptyList(), currentChatIndex = 0)
        assertEquals(null, state.currentChat)
    }

    @Test
    fun `currentChat returns correct chat by index`() {
        val chat1 = Chat(title = "Chat 1")
        val chat2 = Chat(title = "Chat 2")
        val state = ChatUiState(chats = listOf(chat1, chat2), currentChatIndex = 1)
        assertEquals(chat2, state.currentChat)
    }
}
