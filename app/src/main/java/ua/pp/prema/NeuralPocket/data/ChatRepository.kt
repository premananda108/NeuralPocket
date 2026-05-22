package ua.pp.prema.NeuralPocket.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ChatRepository(private val context: Context) {

    private val file = File(context.filesDir, "chats.json")

    fun save(chats: List<Chat>) {
        try {
            val arr = JSONArray()
            for (chat in chats) {
                val chatObj = JSONObject()
                chatObj.put("id", chat.id)
                chatObj.put("title", chat.title)
                chatObj.put("systemPrompt", chat.systemPrompt)
                val msgs = JSONArray()
                for (msg in chat.messages) {
                    if (msg.isStreaming) continue // don't save incomplete
                    val m = JSONObject()
                    m.put("id", msg.id)
                    m.put("text", msg.text)
                    m.put("isUser", msg.isUser)
                    m.put("timestamp", msg.timestamp)
                    m.put("imageUriString", msg.imageUriString ?: "")
                    m.put("hasAudio", msg.hasAudio)
                    msgs.put(m)
                }
                chatObj.put("messages", msgs)
                arr.put(chatObj)
            }
            val json = arr.toString()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                error("Atomic rename failed")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun load(): MutableList<Chat> {
        val result = mutableListOf<Chat>()
        if (!file.exists()) return result
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val chatObj = arr.getJSONObject(i)
                val messages = mutableListOf<ChatMessage>()
                val msgs = chatObj.getJSONArray("messages")
                for (j in 0 until msgs.length()) {
                    val m = msgs.getJSONObject(j)
                    messages.add(
                        ChatMessage(
                            id = m.getString("id"),
                            text = m.getString("text"),
                            isUser = m.getBoolean("isUser"),
                            timestamp = m.getLong("timestamp"),
                            imageUriString = m.getString("imageUriString").ifEmpty { null },
                            hasAudio = m.getBoolean("hasAudio")
                        )
                    )
                }
                val sysPrompt = if (chatObj.has("systemPrompt")) chatObj.getString("systemPrompt") else ""
                result.add(
                    Chat(
                        id = chatObj.getString("id"),
                        title = chatObj.getString("title"),
                        messages = messages,
                        systemPrompt = sysPrompt
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }
}
