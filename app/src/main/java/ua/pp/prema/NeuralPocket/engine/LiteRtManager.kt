package ua.pp.prema.NeuralPocket.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// ── Model catalog ─────────────────────────────────────────────────────────────

data class ModelInfo(val name: String, val url: String, val sizeGb: Double = 0.0, val minRamGb: Double = 0.0)

val AVAILABLE_MODELS = listOf(
    ModelInfo("gemma-4-E2B(2.6GB)", "https://pub-b07512464f924792a1bb4c7b3571db1e.r2.dev/gemma-4-E2B-it.litertlm", 2.6, 3.6),
    ModelInfo("gemma-4-E4B(3.7GB)", "https://pub-b07512464f924792a1bb4c7b3571db1e.r2.dev/gemma-4-E4B-it.litertlm", 3.7, 4.5)
)

object PreflightLimits {
    const val MIN_RAM_GB = 3.6
    const val RECOMMENDED_RAM_GB = 5.2
    const val MIN_STORAGE_GB = 3.0
    const val RECOMMENDED_STORAGE_GB = 4.5
}

// ── Download progress ─────────────────────────────────────────────────────────

sealed class DownloadState {
    data class Progress(val percent: Int) : DownloadState()
    data class Done(val file: File) : DownloadState()
    data class Error(val exception: DownloadException) : DownloadState()
}

// ── Download errors ───────────────────────────────────────────────────────────

sealed class DownloadException(msg: String) : Exception(msg) {
    class NoSpace : DownloadException("no space")
    class HttpError(val code: Int) : DownloadException("HTTP $code")
    class Timeout : DownloadException("timeout")
    class IoError(val detail: String) : DownloadException(detail)
    class LowRam(val deviceRam: Double, val requiredRam: Double) : DownloadException("low ram")
}

// ── Engine result ─────────────────────────────────────────────────────────────

data class EngineHandle(val engine: Engine, val backend: String, val modelName: String)

// ── LiteRtManager ─────────────────────────────────────────────────────────────

/**
 * Управляет жизненным циклом LiteRT Engine.
 * Создаётся в ChatViewModel и живёт столько, сколько ViewModel (переживает поворот экрана).
 */
class LiteRtManager(private val context: Context) {

    private var engineHandle: EngineHandle? = null

    val activeHandle: EngineHandle? get() = engineHandle

    // ── Engine init ───────────────────────────────────────────────────────────

    /**
     * Инициализирует Engine из файла модели.
     * Должен вызываться из Dispatchers.IO.
     * @throws Exception с категоризированным сообщением при ошибке
     */
    suspend fun initEngine(modelFile: File, preferGpu: Boolean): EngineHandle =
        withContext(Dispatchers.IO) {
            runPreflight(modelFile)

            engineHandle?.engine?.close()
            engineHandle = null

            val handle = tryCreateEngine(modelFile.absolutePath, preferGpu)
            engineHandle = handle
            handle
        }

    private fun runPreflight(modelFile: File) {
        val abis = Build.SUPPORTED_ABIS.toList()
        Log.d(TAG, "=== Engine Init === Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        Log.d(TAG, "ABIs: $abis  SDK: ${Build.VERSION.SDK_INT}")

        if (!abis.contains("arm64-v8a")) {
            throw Exception("unsupported abi: arm64-v8a required, found ${abis.joinToString()}")
        }
        if (!modelFile.exists()) {
            throw Exception("not found: ${modelFile.absolutePath}")
        }

        val actMgr  = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { actMgr.getMemoryInfo(it) }
        val ramGb   = memInfo.totalMem.toDouble() / GiB
        val availGb = memInfo.availMem.toDouble() / GiB
        Log.d(TAG, "RAM Total: %.1f GB, Avail: %.1f GB, LowMemory: ${memInfo.lowMemory}")
        
        if (ramGb < 2.5) {
            throw Exception("out of memory: device has only %.1f GB RAM".format(ramGb))
        }
        
        if (memInfo.lowMemory) {
            throw Exception("out of memory: device is currently in a low memory state (avail: %.1f GB)".format(availGb))
        }

        val modelSizeGb = modelFile.length().toDouble() / GiB
        if (ramGb < modelSizeGb + 0.5) {
            throw Exception("out of memory: total RAM (%.1f GB) is too low for model size (%.1f GB)".format(ramGb, modelSizeGb))
        }
    }

    private fun tryCreateEngine(modelPath: String, preferGpu: Boolean): EngineHandle {
        val cache = context.cacheDir.absolutePath

        if (preferGpu) {
            try {
                Log.d(TAG, "Trying GPU…")
                val eng = Engine(
                    EngineConfig(
                        modelPath = modelPath,
                        backend = Backend.GPU(),
                        visionBackend = Backend.GPU(),
                        audioBackend = Backend.CPU(),
                        cacheDir = cache
                    )
                ).apply { initialize() }
                Log.d(TAG, "GPU OK")
                return EngineHandle(eng, "GPU", File(modelPath).nameWithoutExtension)
            } catch (e: Exception) {
                val rawMsg = e.message ?: "unknown"
                Log.w(TAG, "GPU failed: $rawMsg — falling back to CPU")
                // GPU failure is reported to ViewModel via gpuFallbackReason
                gpuFallbackReason = when {
                    rawMsg.contains("OpenCL", ignoreCase = true) -> "OpenCL is not supported"
                    rawMsg.contains("Vulkan", ignoreCase = true)  -> "Vulkan is not supported"
                    else -> rawMsg
                }
            }
        }

        Log.d(TAG, "Using CPU backend")
        val eng = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                visionBackend = Backend.CPU(),
                audioBackend = Backend.CPU(),
                cacheDir = cache
            )
        ).apply { initialize() }
        return EngineHandle(eng, "CPU", File(modelPath).nameWithoutExtension)
    }

    /** Если GPU упал и переключились на CPU — здесь причина для отображения пользователю. */
    var gpuFallbackReason: String? = null
        private set

    fun clearGpuFallback() { gpuFallbackReason = null }

    // ── Inference ─────────────────────────────────────────────────────────────
    private val inferenceMutex = Mutex()

    /**
     * Отправляет сообщение в модель. Возвращает Flow<String> токенов.
     * Flow завершается после последнего токена или с исключением при ошибке.
     */
    fun generateResponse(
        prompt: String,
        imageFile: File?,
        audioBytes: ByteArray?
    ): Flow<String> = flow {
        val handle = engineHandle ?: throw IllegalStateException("Engine not initialized")

        inferenceMutex.withLock {
            handle.engine.createConversation().use { conv ->
                val contentList = mutableListOf<Content>()
                imageFile?.let { contentList.add(Content.ImageFile(it.absolutePath)) }
                audioBytes?.let { contentList.add(Content.AudioBytes(it)) }
                when {
                    prompt.isNotEmpty() -> contentList.add(Content.Text(prompt))
                    audioBytes != null  -> contentList.add(Content.Text("Transcribe or describe this audio"))
                    else                -> contentList.add(Content.Text("Describe this image"))
                }

                conv.sendMessageAsync(Contents.of(contentList))
                    .collect { msg ->
                        currentCoroutineContext().ensureActive()
                        emit(msg.text)
                    }
            }
        }
    }.flowOn(Dispatchers.IO)

    // ── Download ──────────────────────────────────────────────────────────────

    /**
     * Скачивает модель по URL. Возвращает Flow<DownloadState>.
     * Проверяет свободное место перед началом загрузки.
     */
    fun downloadModel(model: ModelInfo, targetFile: File): Flow<DownloadState> = flow {
        val partFile = File(targetFile.parentFile, "${targetFile.name}.part")
        
        val actMgr  = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { actMgr.getMemoryInfo(it) }
        val ramGb   = memInfo.totalMem.toDouble() / GiB
        if (model.minRamGb > 0 && ramGb < model.minRamGb) {
            throw DownloadException.LowRam(ramGb, model.minRamGb)
        }

        if (model.sizeGb > 0) {
            val stat = StatFs(targetFile.parentFile!!.absolutePath)
            val free = stat.availableBlocksLong * stat.blockSizeLong
            val requiredBytes = (model.sizeGb * 1024 * 1024 * 1024).toLong() + (100 * 1024 * 1024L)
            if (free < requiredBytes) {
                throw DownloadException.NoSpace()
            }
        }

        try {
            val url        = URL(model.url)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout    = 60_000
                setRequestProperty("Accept-Encoding", "identity") // точный Content-Length
                connect()
            }

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw DownloadException.HttpError(code)
            }

            val fileLength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                connection.contentLengthLong
            else
                connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L

            if (fileLength > 0) {
                val stat = StatFs(targetFile.parentFile!!.absolutePath)
                val free = stat.availableBlocksLong * stat.blockSizeLong
                if (free < fileLength + 100 * 1024 * 1024L) {
                    throw DownloadException.NoSpace()
                }
            }

            connection.inputStream.use { input ->
                FileOutputStream(partFile).use { output ->
                    val buf         = ByteArray(65_536)
                    var total       = 0L
                    var lastPercent = -1
                    var count: Int

                    while (input.read(buf).also { count = it } != -1) {
                        currentCoroutineContext().ensureActive()
                        output.write(buf, 0, count)
                        total += count
                        if (fileLength > 0) {
                            val pct = (total * 100 / fileLength).toInt()
                            if (pct != lastPercent) {
                                lastPercent = pct
                                emit(DownloadState.Progress(pct))
                            }
                        }
                    }
                    output.flush()
                    
                    if (fileLength > 0 && total != fileLength) {
                        throw IOException("Incomplete download: expected $fileLength bytes, got $total")
                    }
                }
            }
            
            if (!partFile.renameTo(targetFile)) {
                throw IOException("Atomic rename failed")
            }
            
            emit(DownloadState.Done(targetFile))
        } finally {
            if (!currentCoroutineContext().isActive || !targetFile.exists()) {
                partFile.delete()
            }
        }
    }.retryWhen { cause, attempt ->
        if (cause is CancellationException || cause is DownloadException.NoSpace) {
            return@retryWhen false
        }
        if (cause is DownloadException.HttpError && cause.code in 400..499) {
            return@retryWhen false
        }
        if (attempt < 2) {
            delay(2000L * (attempt + 1))
            return@retryWhen true
        }
        false
    }.catch { e ->
        if (e is CancellationException) throw e
        val exception = e as? DownloadException ?: DownloadException.IoError(e.message ?: "unknown")
        emit(DownloadState.Error(exception))
    }.flowOn(Dispatchers.IO)

    // ── Error categorization ──────────────────────────────────────────────────

    fun categorizeEngineError(e: Exception, modelFile: File? = null): EngineErrorKind {
        val raw = e.message?.lowercase() ?: ""
        return when {
            raw.contains("out of memory") || raw.contains("oom") || raw.contains("failed to allocate") ->
                EngineErrorKind.OutOfMemory
            raw.contains("not found") || raw.contains("no such file") -> {
                modelFile?.delete()
                EngineErrorKind.FileNotFound
            }
            raw.contains("corrupt") || raw.contains("invalid") || raw.contains("magic") ||
            raw.contains("parse")   || raw.contains("flatbuffer") -> {
                modelFile?.delete()
                EngineErrorKind.Corrupt
            }
            raw.contains("arm64") || raw.contains("abi") || raw.contains("unsupported abi") ->
                EngineErrorKind.UnsupportedAbi
            else -> EngineErrorKind.Generic(e.message ?: "unknown")
        }
    }

    fun categorizeInferenceError(e: Throwable): InferenceErrorKind {
        val raw = e.message?.lowercase() ?: ""
        return when {
            raw.contains("context") && (raw.contains("limit") || raw.contains("overflow") || raw.contains("length")) ->
                InferenceErrorKind.ContextOverflow
            raw.contains("out of memory") || raw.contains("oom") || raw.contains("failed to allocate") ->
                InferenceErrorKind.OutOfMemory
            else -> InferenceErrorKind.Generic(e.message ?: "unknown")
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    fun downloadedModels(): List<File> =
        context.filesDir.listFiles { _, n -> n.endsWith(".litertlm") }?.toList() ?: emptyList()

    fun close() {
        engineHandle?.engine?.close()
        engineHandle = null
        Log.d(TAG, "Engine closed")
    }

    companion object {
        private const val TAG = "LiteRtManager"
        private const val GiB = 1024.0 * 1024 * 1024
    }
}

// ── Error kinds ───────────────────────────────────────────────────────────────

sealed class EngineErrorKind {
    object OutOfMemory    : EngineErrorKind()
    object FileNotFound   : EngineErrorKind()
    object Corrupt        : EngineErrorKind()
    object UnsupportedAbi : EngineErrorKind()
    data class Generic(val message: String) : EngineErrorKind()
}

sealed class InferenceErrorKind {
    object ContextOverflow : InferenceErrorKind()
    object OutOfMemory     : InferenceErrorKind()
    data class Generic(val message: String) : InferenceErrorKind()
}

// ── Extension: extract text from Message ─────────────────────────────────────

val Message.text: String
    get() = contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
