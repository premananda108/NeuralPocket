package ua.pp.prema.NeuralPocket.data

import java.util.UUID

data class Chat(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Chat",
    val messages: List<ChatMessage> = emptyList(),
    val systemPrompt: String = ""
)
