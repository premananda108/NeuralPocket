package ua.pp.prema.NeuralPocket.data

import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val imageUriString: String? = null,
    val hasAudio: Boolean = false,
    val isStreaming: Boolean = false
)
