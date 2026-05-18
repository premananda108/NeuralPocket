package ua.pp.prema.NeuralPocket.data

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ua.pp.prema.NeuralPocket.data.Chat
import ua.pp.prema.NeuralPocket.data.ChatMessage
import ua.pp.prema.NeuralPocket.data.ChatRepository

class ChatRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `load returns empty list when file does not exist`() {
        val mockContext = mockk<Context>()
        every { mockContext.filesDir } returns tempFolder.newFolder("files")

        val repository = ChatRepository(mockContext)
        val loaded = repository.load()

        assertTrue(loaded.isEmpty())
    }

    @Test
    fun `save and load chat correctly`() {
        val mockContext = mockk<Context>()
        every { mockContext.filesDir } returns tempFolder.newFolder("files")

        val repository = ChatRepository(mockContext)

        val message1 = ChatMessage(text = "Hello", isUser = true)
        val message2 = ChatMessage(text = "Hi there", isUser = false)
        val chat = Chat(
            id = "test-chat-id",
            title = "Test Chat",
            messages = listOf(message1, message2),
            systemPrompt = "You are a helpful assistant"
        )

        // Save
        repository.save(listOf(chat))

        // Load
        val loaded = repository.load()
        assertEquals(1, loaded.size)
        
        val loadedChat = loaded[0]
        assertEquals("test-chat-id", loadedChat.id)
        assertEquals("Test Chat", loadedChat.title)
        assertEquals("You are a helpful assistant", loadedChat.systemPrompt)
        
        assertEquals(2, loadedChat.messages.size)
        assertEquals("Hello", loadedChat.messages[0].text)
        assertEquals(true, loadedChat.messages[0].isUser)
        assertEquals("Hi there", loadedChat.messages[1].text)
        assertEquals(false, loadedChat.messages[1].isUser)
    }

    @Test
    fun `save ignores streaming messages`() {
        val mockContext = mockk<Context>()
        every { mockContext.filesDir } returns tempFolder.newFolder("files")

        val repository = ChatRepository(mockContext)

        val message1 = ChatMessage(text = "Finished", isUser = false, isStreaming = false)
        val message2 = ChatMessage(text = "Streaming...", isUser = false, isStreaming = true)
        
        val chat = Chat(messages = listOf(message1, message2))

        repository.save(listOf(chat))

        val loaded = repository.load()
        assertEquals(1, loaded[0].messages.size)
        assertEquals("Finished", loaded[0].messages[0].text)
    }
}
