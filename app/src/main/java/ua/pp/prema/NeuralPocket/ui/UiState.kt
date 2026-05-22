package ua.pp.prema.NeuralPocket.ui

import ua.pp.prema.NeuralPocket.data.Chat
import ua.pp.prema.NeuralPocket.engine.PreflightResult

// ── Model status ──────────────────────────────────────────────────────────────

sealed class ModelStatus {
    /** Model not selected / not loaded. */
    object NotLoaded : ModelStatus()

    /** Downloading model file. */
    data class Downloading(val modelName: String, val percent: Int) : ModelStatus()

    /** File downloaded, initializing engine. */
    data class Loading(val modelName: String) : ModelStatus()

    /** Engine ready for work. */
    data class Ready(val modelName: String, val backend: String) : ModelStatus()

    /** Error (fatal or retriable). */
    data class Error(val message: String, val canRetry: Boolean = true) : ModelStatus()
}

// ── UI Events ─────────────────────────────────────────────────────────────────

/** One-time events (showing dialogs, errors) that shouldn't be kept in state. */
sealed class UiEvent {
    data class ShowError(val message: String) : UiEvent()
    data class ShowPreflight(val result: PreflightResult) : UiEvent()
    data class ShowGpuFallback(val reason: String) : UiEvent()
    object ShowModelSelection : UiEvent()
}

// ── Chat UI state ─────────────────────────────────────────────────────────────

/**
 * Single source of truth for UI.
 * MainActivity observes this object via StateFlow.
 */
data class ChatUiState(
    val chats: List<Chat>         = emptyList(),
    val currentChatIndex: Int     = 0,
    val modelStatus: ModelStatus  = ModelStatus.NotLoaded,
    val isInputEnabled: Boolean   = false,
    val isGenerating: Boolean     = false,
    val streamTrigger: Int        = 0
) {
    val currentChat: Chat? get() = chats.getOrNull(currentChatIndex)

    val statusText: String get() = when (modelStatus) {
        is ModelStatus.NotLoaded          -> "Model not loaded"
        is ModelStatus.Downloading        -> "Downloading: ${modelStatus.percent}%"
        is ModelStatus.Loading            -> "Loading ${modelStatus.modelName}…"
        is ModelStatus.Ready              -> "${modelStatus.modelName} [${modelStatus.backend}]"
        is ModelStatus.Error              -> "❌ Error"
    }
}
