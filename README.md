# NeuralPocket

> A private, fully on-device AI chat assistant for Android — no cloud, no API keys, no data leaving your phone.

NeuralPocket runs Google's Gemma 4 language models locally via the **LiteRT** inference engine (formerly TensorFlow Lite). Every conversation is processed entirely on the device, making the app functional offline and inherently private.

---

## Features

**On-device inference**
All text generation, image understanding, and audio transcription run locally. There is no server, no telemetry, and no network connection required after the model is downloaded.

**Multimodal input**
- Text messages
- Images — attach from gallery or capture with camera
- Voice — record up to 30 seconds of audio, converted to WAV and passed to the model

**Multiple chats**
Create, switch between, and delete independent conversations. Each chat maintains its own message history, title, and system prompt.

**Conversation memory**
Configurable context depth (0–5 exchange pairs) controls how much history the model receives with each new message. Useful for balancing coherence against context window usage.

**Custom system prompts**
Set a per-chat system instruction to steer the model's behavior and persona.

**GPU acceleration**
Optionally enables the OpenCL / Vulkan GPU backend via LiteRT. Falls back to CPU automatically with a user notification if GPU initialization fails.

**Two themes**
Light and dark themes switchable at runtime from the drawer, persisted across sessions.

**Streaming responses**
Model output is emitted token-by-token with a live typing indicator, and generation can be stopped mid-response.

**Markdown rendering**
AI responses are rendered with full Markdown support (bold, italic, code blocks, lists) via the Markwon library.

---

## Screenshots

> _Add screenshots here once the app is running on a device._

---

## Supported Models

| Model | Size | Description |
|---|---|---|
| `gemma-4-E2B` | ~2.6 GB | Efficient 2B parameter model, works on most modern Android devices |
| `gemma-4-E4B` | ~3.7 GB | More capable 4B parameter model, recommended for 6 GB+ RAM devices |

Models are downloaded in-app on first use and stored in the app's internal storage. The download resumes automatically on failure (up to 2 retries with exponential backoff).

---

## Requirements

| Requirement | Minimum | Recommended |
|---|---|---|
| Android | 9.0 (API 28) | 12.0+ |
| Architecture | arm64-v8a | arm64-v8a |
| RAM | 3 GB | 6 GB+ |
| Free storage | 3 GB | 5 GB+ |
| GPU | Optional | OpenCL or Vulkan capable |

The app runs a preflight check on first launch and warns or blocks execution if the device does not meet these requirements.

---

## Architecture

NeuralPocket follows MVVM with unidirectional data flow.

```
MainActivity  ──observe──▶  ChatViewModel  ──calls──▶  LiteRtManager
     │                            │                         │
  Adapters                  ChatRepository             PreflightChecker
  (RecyclerView)            (JSON on disk)          (RAM / ABI / storage)
```

**Key design decisions:**

- `ChatViewModel` holds a single `StateFlow<ChatUiState>` as the source of truth. `MainActivity` is a pure observer — it never mutates state directly.
- One-shot events (error dialogs, model selection prompts) are delivered via a `Channel<UiEvent>` to avoid re-showing dialogs on configuration changes.
- `LiteRtManager` serializes concurrent inference calls with a `Mutex`, preventing race conditions if the user triggers a second send before the first completes.
- `ChatRepository` performs all I/O on `Dispatchers.IO` using an atomic write pattern: data is written to a `.tmp` file first, then renamed to the final path, preventing data loss on crash.
- Image files copied from the content resolver are deleted from cache immediately after inference, preventing unbounded cache growth.

---

## Project Structure

```
app/src/main/java/ua/pp/prema/NeuralPocket/
├── MainActivity.kt          # Single Activity — UI binding, input, recording
├── adapter/
│   ├── ChatListAdapter.kt   # Drawer chat list (DiffUtil)
│   └── MessageAdapter.kt    # Message bubbles with Markdown and streaming animation
├── data/
│   ├── Chat.kt              # Chat data class
│   ├── ChatMessage.kt       # Message data class
│   └── ChatRepository.kt    # Persist/load chats as JSON
├── engine/
│   ├── LiteRtManager.kt     # LiteRT engine lifecycle, inference, download
│   └── PreflightChecker.kt  # Device compatibility checks
└── ui/
    ├── ChatViewModel.kt      # Business logic, state management
    └── UiState.kt            # ChatUiState, ModelStatus, UiEvent sealed classes
```

---

## Building

**Prerequisites:**
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK with API 35

**Steps:**

```bash
# Clone the repository
git clone https://github.com/your-username/NeuralPocket.git
cd NeuralPocket

# Open in Android Studio and let Gradle sync,
# or build from the command line:
./gradlew assembleRelease
```

The release build has minification and resource shrinking enabled. If you add new libraries that use reflection, add the appropriate `-keep` rules to `app/proguard-rules.pro`.

---

## Dependencies

| Library | Version | Purpose |
|---|---|---|
| `com.google.ai.edge.litertlm` | 0.11.0 | On-device LLM inference |
| `io.noties.markwon:core` | 4.6.2 | Markdown rendering in TextViews |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` | 2.8.7 | ViewModel + viewModelScope |
| `kotlinx-coroutines-android` | 1.8.1 | Structured concurrency, Flow |
| `com.google.android.material` | 1.12.0 | MaterialToolbar, SwitchMaterial, etc. |
| `androidx.drawerlayout` | 1.2.0 | Navigation drawer |
| `androidx.recyclerview` | 1.3.2 | Chat and message lists |

---

## Permissions

| Permission | When requested | Why |
|---|---|---|
| `INTERNET` | Always | Downloading model files |
| `CAMERA` | On first camera use | Taking photos to attach to messages |
| `RECORD_AUDIO` | On first voice message | Recording audio input |
| `READ_EXTERNAL_STORAGE` | Android ≤12 only | Picking images from gallery |

---

## Privacy

- No analytics, no crash reporting, no telemetry of any kind.
- Conversations are stored only in the app's private internal storage (`files/chats.json`). They are not backed up to the cloud (`allowBackup="true"` in the manifest only covers local ADB backups).
- Images attached to messages are resized and temporarily copied to `cacheDir` for inference, then deleted immediately after the response is generated.
- The only outbound network traffic is the one-time model file download from the configured CDN.

---

## Running Tests

```bash
./gradlew test
```

Unit tests cover:

- `ChatRepositoryTest` — save/load round-trip, streaming messages are not persisted
- `LiteRtManagerTest` — engine and inference error categorization
- `ChatUiStateTest` — `statusText` computed property, `currentChat` indexing

Tests use **MockK** for Android framework mocking and `TemporaryFolder` for filesystem isolation.

---

## Known Limitations

- **Context window:** Long conversations will eventually exceed the model's context window (~8k tokens for Gemma 4). When this happens, the app shows an error and prompts the user to start a new chat.
- **Audio:** Audio transcription quality depends on the model — Gemma 4 is not a dedicated speech model. Short, clear recordings in English work best.
- **Images:** Input images are downscaled to 512×512 before inference to limit memory usage. Very detailed images may lose information.
- **GPU:** GPU acceleration is experimental and device-dependent. Devices without OpenCL or Vulkan support fall back to CPU silently.

---

## License

This project is provided as-is for personal and educational use. The bundled Gemma models are subject to Google's [Gemma Terms of Use](https://ai.google.dev/gemma/terms).
