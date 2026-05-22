package ua.pp.prema.NeuralPocket.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ua.pp.prema.NeuralPocket.R
import ua.pp.prema.NeuralPocket.data.Chat
import ua.pp.prema.NeuralPocket.data.ChatMessage
import ua.pp.prema.NeuralPocket.data.ChatRepository
import ua.pp.prema.NeuralPocket.engine.DownloadException
import ua.pp.prema.NeuralPocket.engine.DownloadState
import ua.pp.prema.NeuralPocket.engine.EngineErrorKind
import ua.pp.prema.NeuralPocket.engine.InferenceErrorKind
import ua.pp.prema.NeuralPocket.engine.LiteRtManager
import ua.pp.prema.NeuralPocket.engine.ModelInfo
import ua.pp.prema.NeuralPocket.engine.PreflightChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.collections.plus

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    // ── State ─────────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    private var inferenceJob: Job? = null

    // ── Dependencies ──────────────────────────────────────────────────────────

    private val chatRepository   = ChatRepository(application)
    private val liteRtManager    = LiteRtManager(application)
    private val preflightChecker = PreflightChecker(application)

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        loadChats()
        runPreflightAndLoadModel()

        viewModelScope.launch {
            uiState.map { it.currentChat?.id }.distinctUntilChanged().collect { id ->
                if (id != null) {
                    getApplication<Application>()
                        .getSharedPreferences("app_prefs", Application.MODE_PRIVATE)
                        .edit()
                        .putString("last_active_chat_id", id)
                        .apply()
                }
            }
        }
    }

    // ── Preflight ─────────────────────────────────────────────────────────────

    private fun runPreflightAndLoadModel() {
        // Если уже есть скачанные модели, пропускаем проверку места
        val hasModels = getApplication<Application>().filesDir.listFiles { _, n -> n.endsWith(".litertlm") }?.isNotEmpty() == true
        if (hasModels) {
            onPreflightAccepted()
            return
        }

        val result = preflightChecker.check()
        if (!result.canRun || result.warnings.isNotEmpty()) {
            _events.trySend(UiEvent.ShowPreflight(result))
        } else {
            proceedWithModelLoad()
        }
    }

    fun onPreflightAccepted() {
        proceedWithModelLoad()
    }

    // ── Model loading ─────────────────────────────────────────────────────────

    private fun proceedWithModelLoad() {
        val downloaded = liteRtManager.downloadedModels()
        if (downloaded.isEmpty()) {
            _events.trySend(UiEvent.ShowModelSelection)
            _uiState.update { it.copy(modelStatus = ModelStatus.NotLoaded) }
        } else {
            loadModel(downloaded[0])
        }
    }

    /**
     * @param skipMemoryCheck передаётся в LiteRtManager.initEngine — пропускает lowMemory проверку.
     *   Должен быть true при перезагрузке после отмены генерации (старый C++ поток ещё жив).
     */
    fun loadModel(modelFile: File, skipMemoryCheck: Boolean = false) {
        val preferGpu = getApplication<Application>()
            .getSharedPreferences("app_prefs", Application.MODE_PRIVATE)
            .getBoolean("prefer_gpu", false)

        _uiState.update { it.copy(modelStatus = ModelStatus.Loading(modelFile.nameWithoutExtension), isInputEnabled = false) }

        viewModelScope.launch {
            try {
                val handle = liteRtManager.initEngine(modelFile, preferGpu, skipMemoryCheck)

                // Проверяем, было ли GPU→CPU переключение
                val fallback = liteRtManager.gpuFallbackReason
                if (fallback != null) {
                    _events.trySend(UiEvent.ShowGpuFallback(fallback))
                    liteRtManager.clearGpuFallback()
                }

                _uiState.update { it.copy(
                    modelStatus      = ModelStatus.Ready(handle.modelName, handle.backend),
                    isInputEnabled   = true
                )}
            } catch (e: Exception) {
                val kind    = liteRtManager.categorizeEngineError(e, modelFile)
                val message = engineErrorMessage(kind)
                _events.trySend(UiEvent.ShowError(message))
                _uiState.update { it.copy(
                    modelStatus    = ModelStatus.Error(message),
                    isInputEnabled = false
                )}
            }
        }
    }

    // ── Model download ────────────────────────────────────────────────────────

    fun downloadModel(model: ModelInfo) {
        val targetFile = File(getApplication<Application>().filesDir, "${model.name}.litertlm")
        _uiState.update { it.copy(modelStatus = ModelStatus.Downloading(model.name, 0), isInputEnabled = false) }

        viewModelScope.launch {
            liteRtManager.downloadModel(model, targetFile)
                .catch { e ->
                    val msg = when (e) {
                        is SocketTimeoutException ->
                            getApplication<Application>().getString(R.string.error_download_timeout)
                        is IOException ->
                            getApplication<Application>().getString(R.string.error_download_io, e.message ?: "unknown")
                        else ->
                            getApplication<Application>().getString(R.string.error_download_io, e.message ?: "unknown")
                    }
                    targetFile.delete()
                    _events.trySend(UiEvent.ShowError(msg))
                    _uiState.update { it.copy(modelStatus = ModelStatus.Error(msg)) }
                }
                .collect { state ->
                    when (state) {
                        is DownloadState.Progress ->
                            _uiState.update { it.copy(modelStatus = ModelStatus.Downloading(model.name, state.percent)) }
                        is DownloadState.Done ->
                            loadModel(state.file)
                        is DownloadState.Error -> {
                            targetFile.delete()
                            val msg = downloadErrorMessage(state.exception)
                            _events.trySend(UiEvent.ShowError(msg))
                            _uiState.update { it.copy(modelStatus = ModelStatus.Error(msg)) }
                        }
                    }
                }
        }
    }

    // ── Send message ──────────────────────────────────────────────────────────

    fun sendMessage(
        prompt: String,
        imageUri: Uri?,
        audioBytes: ByteArray?,
        cameraImageFile: File? = null
    ) {
        if (prompt.isEmpty() && imageUri == null && audioBytes == null) return
        val chat = _uiState.value.currentChat ?: return
        val chatId = chat.id

        val userText = when {
            prompt.isNotEmpty() -> prompt
            imageUri != null    -> getApplication<Application>().getString(R.string.image_message)
            else                -> getApplication<Application>().getString(R.string.audio_message)
        }
        val userMsg = ChatMessage(
            text = userText,
            isUser = true,
            imageUriString = imageUri?.toString(),
            hasAudio = audioBytes != null
        )

        val aiMsg = ChatMessage(text = "", isUser = false, isStreaming = true)

        _uiState.update { state ->
            val chats = state.chats.map { c ->
                if (c.id == chatId) {
                    val newTitle = if (c.messages.isEmpty() && prompt.isNotEmpty()) {
                        if (prompt.length > 28) prompt.take(28) + "…" else prompt
                    } else c.title
                    c.copy(title = newTitle, messages = c.messages + userMsg + aiMsg)
                } else c
            }
            state.copy(chats = chats, isInputEnabled = false, isGenerating = true, streamTrigger = state.streamTrigger + 1)
        }

        val updatedChat = _uiState.value.chats.find { it.id == chatId } ?: return

        val prefs = getApplication<Application>().getSharedPreferences("app_prefs", Application.MODE_PRIVATE)
        val historyLimit = prefs.getInt("history_limit", 3)
        
        val historyToInclude = updatedChat.messages
            .dropLast(2) // Drop current user msg and AI placeholder
            .takeLast(historyLimit * 2)

        val fullPrompt = buildString {
            if (updatedChat.systemPrompt.isNotBlank()) {
                append("SYSTEM INSTRUCTION (strictly follow it):\n")
                append(updatedChat.systemPrompt)
                append("\n\n")
            }

            if (historyToInclude.isNotEmpty()) {
                append("Here is our conversation history for context:\n")
                for (m in historyToInclude) {
                    val role = if (m.isUser) "User" else "You (AI)"
                    append("$role: ${m.text}\n")
                }
                append("\nNow answer my new request:\n")
            }
            if (prompt.isEmpty() && imageUri != null) {
                append("Please describe and analyze what you see in this image in detail.")
            } else if (prompt.isEmpty() && audioBytes != null) {
                val audioPrompt = "Listen to the audio. First, write down the exact transcription of what is said. Then, analyze whether the speaker is addressing you (the AI assistant). If they are addressing you, respond to their query."
                append(audioPrompt)
            } else {
                append(prompt)
            }
        }

        inferenceJob = viewModelScope.launch {
            // Copy image to internal storage so it survives app restart.
            // External provider URIs (gallery / downloads) lose their permission
            // grant after the process is killed, causing a SecurityException on reload.
            val persistedUri: Uri? = imageUri?.let { uri ->
                withContext(Dispatchers.IO) { persistImageToStorage(uri) }
            }

            // Update the user message with the persistent file URI.
            if (persistedUri != null) {
                _uiState.update { state ->
                    val chats = state.chats.map { c ->
                        if (c.id == chatId && c.messages.size >= 2) {
                            val msgs = c.messages.toMutableList()
                            msgs[msgs.size - 2] = msgs[msgs.size - 2].copy(imageUriString = persistedUri.toString())
                            c.copy(messages = msgs)
                        } else c
                    }
                    state.copy(chats = chats)
                }
            }

            // For inference, create a 512px cache copy (deleted after use).
            // If persistence failed fall back to copying from the original URI.
            val inferUri = persistedUri ?: imageUri
            val imageFile: File? = inferUri?.let { uri ->
                withContext(Dispatchers.IO) { copyUriToCache(uri) }
            }

            var fullResponse = ""
            try {
                liteRtManager.generateResponse(fullPrompt, imageFile, audioBytes)
                    .catch { e ->
                        val kind    = liteRtManager.categorizeInferenceError(e)
                        fullResponse = inferenceErrorMessage(kind)
                        finaliseAiMessage(chatId, fullResponse)
                    }
                    .collect { token ->
                        fullResponse += token
                        _uiState.update { state ->
                            val chats = state.chats.map { c ->
                                if (c.id == chatId && c.messages.isNotEmpty()) {
                                    val msgs = c.messages.toMutableList()
                                    msgs[msgs.lastIndex] = msgs.last().copy(text = fullResponse, isStreaming = true)
                                    c.copy(messages = msgs)
                                } else c
                            }
                            state.copy(chats = chats, streamTrigger = state.streamTrigger + 1)
                        }
                    }

                if (fullResponse.isNotEmpty()) {
                    finaliseAiMessage(chatId, fullResponse)
                }
            } catch (e: CancellationException) {
                // Stopped by user
                fullResponse = if (fullResponse.isEmpty()) "— Stopped —" else "$fullResponse\n— Stopped —"
                finaliseAiMessage(chatId, fullResponse)
                
                // LiteRT-LM < 0.15 does not support cancelling generation natively.
                // The C++ inference thread will keep running in the background and burn CPU/battery.
                // Workaround: completely close and re-initialize the engine.
                // skipMemoryCheck=true: the old C++ thread may still hold native memory, causing
                // the OS to report lowMemory=true — which is a false positive at this point.
                val currentModelName = liteRtManager.activeHandle?.modelName
                if (currentModelName != null) {
                    val file = File(getApplication<Application>().filesDir, "$currentModelName.litertlm")
                    if (file.exists()) {
                        loadModel(file, skipMemoryCheck = true)
                    }
                }
                throw e
            } finally {
                withContext(Dispatchers.IO) {
                    imageFile?.delete()
                    cameraImageFile?.delete()
                }
                inferenceJob = null
            }
        }
    }

    fun stopGeneration() {
        val job = inferenceJob
        if (job != null) {
            job.cancel()
            inferenceJob = null
            
            _uiState.update { state ->
                val chat = state.currentChat
                if (chat != null && chat.messages.isNotEmpty()) {
                    val lastMsg = chat.messages.last()
                    if (!lastMsg.isUser && lastMsg.isStreaming) {
                        val finalTxt = if (lastMsg.text.isEmpty()) "— Stopped —" else "${lastMsg.text}\n— Stopped —"
                        val chats = state.chats.map { c ->
                            if (c.id == chat.id) {
                                val msgs = c.messages.toMutableList()
                                msgs[msgs.lastIndex] = lastMsg.copy(text = finalTxt, isStreaming = false)
                                c.copy(messages = msgs)
                            } else c
                        }
                        return@update state.copy(chats = chats, isInputEnabled = true, isGenerating = false, streamTrigger = state.streamTrigger + 1)
                    }
                }
                state.copy(isInputEnabled = true, isGenerating = false, streamTrigger = state.streamTrigger + 1)
            }
            saveChats()
        }
    }

    private fun finaliseAiMessage(chatId: String, text: String) {
        _uiState.update { state ->
            val chats = state.chats.map { c ->
                if (c.id == chatId && c.messages.isNotEmpty()) {
                    val msgs = c.messages.toMutableList()
                    msgs[msgs.lastIndex] = msgs.last().copy(text = text, isStreaming = false)
                    c.copy(messages = msgs)
                } else c
            }
            state.copy(chats = chats, isInputEnabled = true, isGenerating = false, streamTrigger = state.streamTrigger + 1)
        }
        saveChats()
    }

    // ── Chat management ───────────────────────────────────────────────────────

    private fun loadChats() {
        val loaded = chatRepository.load()
        val chats = if (loaded.isEmpty()) listOf(Chat(title = "Chat 1")) else loaded.toList()

        val prefs = getApplication<Application>().getSharedPreferences("app_prefs", Application.MODE_PRIVATE)
        val lastChatId = prefs.getString("last_active_chat_id", null)
        
        var indexToSelect = chats.lastIndex
        if (lastChatId != null) {
            val idx = chats.indexOfFirst { it.id == lastChatId }
            if (idx != -1) {
                indexToSelect = idx
            }
        }

        _uiState.update { it.copy(chats = chats, currentChatIndex = indexToSelect) }
    }

    fun createNewChat() {
        _uiState.update { state ->
            val newChat = Chat(title = "Chat ${state.chats.size + 1}")
            state.copy(chats = state.chats + newChat, currentChatIndex = state.chats.size)
        }
        saveChats()
    }

    fun switchToChat(index: Int) {
        val chats = _uiState.value.chats
        if (index < 0 || index >= chats.size) return
        _uiState.update { it.copy(currentChatIndex = index) }
    }

    fun deleteChat(index: Int) {
        _uiState.update { state ->
            val mutableChats = state.chats.toMutableList()
            mutableChats.removeAt(index)
            if (mutableChats.isEmpty()) mutableChats.add(Chat(title = "Chat 1"))
            val newIndex = when {
                index >= mutableChats.size -> mutableChats.size - 1
                else                       -> index
            }
            state.copy(chats = mutableChats.toList(), currentChatIndex = newIndex)
        }
        saveChats()
    }

    fun setSystemPrompt(text: String) {
        val chat = _uiState.value.currentChat ?: return
        _uiState.update { state ->
            val chats = state.chats.map { c ->
                if (c.id == chat.id) c.copy(systemPrompt = text) else c
            }
            state.copy(chats = chats, streamTrigger = state.streamTrigger + 1)
        }
        saveChats()
    }

    private fun saveChats() {
        val snapshot = _uiState.value.chats
        viewModelScope.launch(Dispatchers.IO) {
            chatRepository.save(snapshot)
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /**
     * Copies [uri] to [destFile] with the decoded image fitting within [maxSide]×[maxSide].
     *
     * Uses the two-pass Android approach to avoid OOM on large camera photos:
     * - Pass 1: inJustDecodeBounds — reads only the image header, zero pixel memory allocated.
     * - Compute inSampleSize — largest power-of-2 that keeps both dimensions ≤ maxSide.
     * - Pass 2: decodeStream with inSampleSize — allocates only (W/s × H/s × 4) bytes.
     *
     * Example: 12 MP photo (4000×3000), maxSide=512 → inSampleSize=8 → ~0.7 MB instead of ~45 MB.
     */
    private fun copyUriToFile(uri: Uri, destFile: File, maxSide: Int = 512): File {
        val resolver = getApplication<Application>().contentResolver

        // Pass 1: decode bounds only — no pixels allocated.
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }

        // Compute power-of-2 subsampling so the decoded image fits within maxSide×maxSide.
        opts.inSampleSize = run {
            var s = 1
            while ((opts.outWidth / s) > maxSide || (opts.outHeight / s) > maxSide) s *= 2
            s
        }
        opts.inJustDecodeBounds = false

        // Pass 2: decode subsampled bitmap and compress to file.
        resolver.openInputStream(uri)?.use { inp ->
            val bitmap = BitmapFactory.decodeStream(inp, null, opts)
            if (bitmap != null) {
                try {
                    FileOutputStream(destFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }
        return destFile
    }

    /** Temporary 512px copy in cacheDir — deleted after inference. */
    private fun copyUriToCache(uri: Uri): File =
        copyUriToFile(uri, File(getApplication<Application>().cacheDir, "input_image_${System.currentTimeMillis()}.jpg"))

    /**
     * Persists image to [filesDir]/chat_images/ so it remains accessible after app restart.
     * Returns a file:// [Uri] on success, or null on failure (caller keeps original URI).
     */
    private fun persistImageToStorage(uri: Uri): Uri? {
        return try {
            val app = getApplication<Application>()
            val imagesDir = File(app.filesDir, "chat_images").apply { mkdirs() }
            val dest = File(imagesDir, "img_${System.currentTimeMillis()}.jpg")
            copyUriToFile(uri, dest, maxSide = 768)
            if (dest.length() > 0) Uri.fromFile(dest) else null
        } catch (e: Exception) {
            Log.w(TAG, "persistImageToStorage failed: ${e.message}")
            null
        }
    }

    // ── Error messages ────────────────────────────────────────────────────────

    private fun str(id: Int, vararg args: Any) =
        getApplication<Application>().getString(id, *args)

    private fun engineErrorMessage(kind: EngineErrorKind) = when (kind) {
        is EngineErrorKind.OutOfMemory    -> str(R.string.error_model_oom)
        is EngineErrorKind.FileNotFound   -> str(R.string.error_model_not_found)
        is EngineErrorKind.Corrupt        -> str(R.string.error_model_corrupt)
        is EngineErrorKind.UnsupportedAbi -> str(R.string.preflight_error_abi, Build.SUPPORTED_ABIS.joinToString())
        is EngineErrorKind.Generic        -> str(R.string.error_engine_init, kind.message)
    }

    private fun downloadErrorMessage(e: DownloadException) = when (e) {
        is DownloadException.NoSpace    -> str(R.string.error_download_no_space)
        is DownloadException.HttpError  -> str(R.string.error_download_http, e.code)
        is DownloadException.Timeout    -> str(R.string.error_download_timeout)
        is DownloadException.IoError    -> str(R.string.error_download_io, e.detail)
        is DownloadException.LowRam     -> str(R.string.error_download_low_ram, e.deviceRam, e.requiredRam)
    }

    private fun inferenceErrorMessage(kind: InferenceErrorKind) = when (kind) {
        is InferenceErrorKind.ContextOverflow -> str(R.string.error_inference_context)
        is InferenceErrorKind.OutOfMemory     -> str(R.string.error_model_oom)
        is InferenceErrorKind.Generic         -> str(R.string.error_inference_generic, kind.message)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCleared() {
        liteRtManager.close()
        Log.d(TAG, "ViewModel cleared — engine released")
        super.onCleared()
    }

    companion object {
        private const val TAG = "ChatViewModel"
    }
}
