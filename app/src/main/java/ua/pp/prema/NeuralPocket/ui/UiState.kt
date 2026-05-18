package ua.pp.prema.NeuralPocket.ui

import ua.pp.prema.NeuralPocket.data.Chat
import ua.pp.prema.NeuralPocket.engine.PreflightResult

// ── Model status ──────────────────────────────────────────────────────────────

sealed class ModelStatus {
    /** Модель ещё не выбрана / не загружена. */
    object NotLoaded : ModelStatus()

    /** Идёт скачивание файла модели. */
    data class Downloading(val modelName: String, val percent: Int) : ModelStatus()

    /** Файл скачан, идёт инициализация Engine. */
    data class Loading(val modelName: String) : ModelStatus()

    /** Движок готов к работе. */
    data class Ready(val modelName: String, val backend: String) : ModelStatus()

    /** Ошибка (фатальная или с возможностью повторить). */
    data class Error(val message: String, val canRetry: Boolean = true) : ModelStatus()
}

// ── UI Events ─────────────────────────────────────────────────────────────────

/** Одноразовые события (показ диалогов, ошибок), которые не должны сохраняться в стейте. */
sealed class UiEvent {
    data class ShowError(val message: String) : UiEvent()
    data class ShowPreflight(val result: PreflightResult) : UiEvent()
    data class ShowGpuFallback(val reason: String) : UiEvent()
    object ShowModelSelection : UiEvent()
}

// ── Chat UI state ─────────────────────────────────────────────────────────────

/**
 * Единственный источник правды для UI.
 * MainActivity наблюдает за этим объектом через StateFlow.
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
