package ua.pp.prema.NeuralPocket

import android.Manifest
import android.app.AlertDialog
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import ua.pp.prema.NeuralPocket.adapter.ChatListAdapter
import ua.pp.prema.NeuralPocket.adapter.MessageAdapter
import ua.pp.prema.NeuralPocket.engine.AVAILABLE_MODELS
import ua.pp.prema.NeuralPocket.engine.PreflightResult
import ua.pp.prema.NeuralPocket.ui.ChatUiState
import ua.pp.prema.NeuralPocket.ui.ChatViewModel
import ua.pp.prema.NeuralPocket.ui.ModelStatus
import ua.pp.prema.NeuralPocket.ui.UiEvent
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private val viewModel: ChatViewModel by viewModels()

    // ── UI ────────────────────────────────────────────────────────────────────
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var toolbarTitle: TextView
    private lateinit var toolbarSubtitle: TextView
    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var chatListRecyclerView: RecyclerView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var attachButton: ImageButton
    private lateinit var audioButton: ImageButton
    private lateinit var attachmentRow: LinearLayout
    private lateinit var imagePreviewCard: View
    private lateinit var imagePreview: ImageView
    private lateinit var removeImageButton: ImageButton
    private lateinit var audioPreviewCard: View
    private lateinit var removeAudioButton: ImageButton
    private lateinit var drawerModelStatus: TextView
    private lateinit var historyLimitButton: MaterialButton
    private lateinit var systemPromptButton: ImageButton
    private lateinit var selectModelDrawerButton: MaterialButton
    private lateinit var themeSwitch: SwitchMaterial
    private lateinit var themeSwitchLabel: TextView
    private lateinit var gpuSwitch: SwitchMaterial

    // ── Adapters ──────────────────────────────────────────────────────────────
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var chatListAdapter: ChatListAdapter

    // ── Recording ─────────────────────────────────────────────────────────────
    private var selectedImageUri: Uri? = null
    private var cameraImageUri: Uri? = null
    private var cameraImageFile: File? = null   // underlying camera file — deleted by ViewModel after send
    private var recordedAudioBytes: ByteArray? = null
    private var isRecording = false
    private var audioRecord: AudioRecord? = null

    private lateinit var prefs: SharedPreferences

    // ── Activity Result Launchers ─────────────────────────────────────────────
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedImageUri = uri
            imagePreview.setImageURI(uri)
            imagePreviewCard.visibility = View.VISIBLE
            attachmentRow.visibility = View.VISIBLE
        }
    }

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && cameraImageUri != null) {
            selectedImageUri = cameraImageUri
            imagePreview.setImageURI(cameraImageUri)
            imagePreviewCard.visibility = View.VISIBLE
            attachmentRow.visibility = View.VISIBLE
        }
    }

    private val requestCameraPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) takePhoto() else Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
    }

    private val requestAudioPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) toggleRecording() else Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        applyTheme(prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_YES))

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupToolbar()
        setupDrawer()
        setupMessagesList()
        setupInput()
        observeUiState()
    }

    private fun bindViews() {
        drawerLayout       = findViewById(R.id.drawerLayout)
        toolbar            = findViewById(R.id.toolbar)
        toolbarTitle       = findViewById(R.id.toolbarTitle)
        toolbarSubtitle    = findViewById(R.id.toolbarSubtitle)
        messagesRecyclerView = findViewById(R.id.messagesRecyclerView)
        chatListRecyclerView = findViewById(R.id.chatListRecyclerView)
        messageInput       = findViewById(R.id.messageInput)
        sendButton         = findViewById(R.id.sendButton)
        attachButton       = findViewById(R.id.attachButton)
        audioButton        = findViewById(R.id.audioButton)
        attachmentRow      = findViewById(R.id.attachmentRow)
        imagePreviewCard   = findViewById(R.id.imagePreviewCard)
        imagePreview       = findViewById(R.id.imagePreview)
        removeImageButton  = findViewById(R.id.removeImageButton)
        audioPreviewCard   = findViewById(R.id.audioPreviewCard)
        removeAudioButton  = findViewById(R.id.removeAudioButton)
        drawerModelStatus  = findViewById(R.id.drawerModelStatus)
        historyLimitButton = findViewById(R.id.historyLimitButton)
        selectModelDrawerButton = findViewById(R.id.selectModelDrawerButton)
        systemPromptButton = findViewById(R.id.systemPromptButton)
        themeSwitch        = findViewById(R.id.themeSwitch)
        themeSwitchLabel   = findViewById(R.id.themeSwitchLabel)
        gpuSwitch          = findViewById(R.id.gpuSwitch)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        val toggle = ActionBarDrawerToggle(this, drawerLayout, toolbar, R.string.drawer_open, R.string.drawer_close)
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        systemPromptButton.setOnClickListener { showSystemPromptDialog() }
        findViewById<ImageButton>(R.id.newChatButton).setOnClickListener { viewModel.createNewChat() }
    }

    private fun setupDrawer() {
        chatListAdapter = ChatListAdapter(
            chats = mutableListOf(),
            onChatClick = { idx -> viewModel.switchToChat(idx); drawerLayout.closeDrawers() },
            onChatLongClick = { idx -> showDeleteChatDialog(idx) }
        )
        chatListRecyclerView.layoutManager = LinearLayoutManager(this)
        chatListRecyclerView.adapter = chatListAdapter
        (chatListRecyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        findViewById<MaterialButton>(R.id.newChatDrawerButton).setOnClickListener {
            viewModel.createNewChat(); drawerLayout.closeDrawers()
        }
        selectModelDrawerButton.setOnClickListener {
            showModelSelectionDialog()
            drawerLayout.closeDrawers()
        }

        val limit = prefs.getInt("history_limit", 3)
        historyLimitButton.text = if (limit == 0) "Context memory: Off" else "Memory: $limit messages"
        historyLimitButton.setOnClickListener {
            showHistoryLimitDialog()
        }

        val isDark = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_YES) == AppCompatDelegate.MODE_NIGHT_YES
        themeSwitch.isChecked = isDark
        themeSwitchLabel.text = if (isDark) getString(R.string.theme_dark) else getString(R.string.theme_light)
        themeSwitch.setOnCheckedChangeListener { _, checked ->
            val mode = if (checked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            prefs.edit().putInt("theme_mode", mode).apply()
            themeSwitchLabel.text = if (checked) getString(R.string.theme_dark) else getString(R.string.theme_light)
            applyTheme(mode)
            recreate()
        }

        val preferGpu = prefs.getBoolean("prefer_gpu", false)
        gpuSwitch.isChecked = preferGpu
        gpuSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("prefer_gpu", checked).apply()
            val currentModelStatus = viewModel.uiState.value.modelStatus
            if (currentModelStatus is ModelStatus.Ready) {
                val file = File(filesDir, "${currentModelStatus.modelName}.litertlm")
                if (file.exists()) {
                    Toast.makeText(this, "Reloading model...", Toast.LENGTH_SHORT).show()
                    viewModel.loadModel(file)
                }
            }
        }
    }

    private fun setupMessagesList() {
        messageAdapter = MessageAdapter(mutableListOf())
        messagesRecyclerView.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRecyclerView.adapter = messageAdapter
        
        // Отключаем анимацию изменения элементов
        (messagesRecyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    private fun setupInput() {
        sendButton.setOnClickListener {
            if (viewModel.uiState.value.isGenerating) {
                viewModel.stopGeneration()
            } else {
                viewModel.sendMessage(
                    messageInput.text.toString().trim(),
                    selectedImageUri,
                    recordedAudioBytes,
                    cameraImageFile
                )
                clearInputs()
            }
        }
        attachButton.setOnClickListener { showAttachmentMenu(it) }
        audioButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                toggleRecording()
            else
                requestAudioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        removeImageButton.setOnClickListener {
            selectedImageUri = null
            imagePreviewCard.visibility = View.GONE
            if (audioPreviewCard.visibility != View.VISIBLE) attachmentRow.visibility = View.GONE
        }
        removeAudioButton.setOnClickListener {
            recordedAudioBytes = null
            audioPreviewCard.visibility = View.GONE
            if (imagePreviewCard.visibility != View.VISIBLE) attachmentRow.visibility = View.GONE
            audioButton.isEnabled = true
        }
    }

    private fun clearInputs() {
        messageInput.setText("")
        selectedImageUri = null
        recordedAudioBytes = null
        cameraImageFile = null       // ViewModel takes ownership of the file; clear reference only
        imagePreviewCard.visibility = View.GONE
        audioPreviewCard.visibility = View.GONE
        attachmentRow.visibility = View.GONE
        audioButton.isEnabled = true
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        updateUi(state)
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is UiEvent.ShowError -> showErrorDialog(event.message)
                            is UiEvent.ShowPreflight -> showPreflightDialog(event.result)
                            is UiEvent.ShowGpuFallback -> showErrorDialog(getString(R.string.error_engine_gpu, event.reason))
                            is UiEvent.ShowModelSelection -> showModelSelectionDialog()
                        }
                    }
                }
            }
        }
    }

    private fun updateUi(state: ChatUiState) {
        // Toolbar
        val currentChat = state.currentChat
        toolbarTitle.text = currentChat?.title ?: getString(R.string.chats_title)
        val statusText = state.statusText
        toolbarSubtitle.text = statusText
        drawerModelStatus.text = statusText

        // Input state
        if (state.isGenerating) {
            sendButton.isEnabled = true
            sendButton.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            sendButton.alpha = 1.0f
            messageInput.isEnabled = false
        } else {
            sendButton.isEnabled = state.isInputEnabled
            sendButton.setImageResource(android.R.drawable.ic_menu_send)
            sendButton.alpha = if (state.isInputEnabled) 1.0f else 0.5f
            messageInput.isEnabled = state.isInputEnabled
        }

        // Chats list
        chatListAdapter.updateChats(state.chats)
        chatListAdapter.setActiveIndex(state.currentChatIndex)

        // Messages list
        if (currentChat != null) {
            val isAtBottom = !messagesRecyclerView.canScrollVertically(1)
            val layoutManager = messagesRecyclerView.layoutManager as LinearLayoutManager
            val isLastItemVisible = layoutManager.findLastVisibleItemPosition() >= messageAdapter.itemCount - 1

            val oldItemCount = messageAdapter.itemCount
            messageAdapter.replaceAll(currentChat.messages)
            val newItemCount = messageAdapter.itemCount
            
            // Stay at bottom if we were at bottom, or if the last message is currently visible
            val shouldScroll = newItemCount > oldItemCount || isAtBottom || isLastItemVisible

            if (shouldScroll && newItemCount > 0) {
                messagesRecyclerView.post {
                    messagesRecyclerView.scrollToPosition(newItemCount - 1)
                }
            }
        }
    }

    private fun showPreflightDialog(result: PreflightResult) {
        val isFatal = !result.canRun
        val title   = getString(if (isFatal) R.string.preflight_error_title else R.string.preflight_warning_title)
        val body    = (result.errors + result.warnings).joinToString("\n\n")

        val dlg = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body)
            .setCancelable(false)

        if (isFatal) {
            dlg.setPositiveButton(getString(R.string.preflight_cancel)) { _, _ -> finish() }
        } else {
            dlg.setPositiveButton(getString(R.string.preflight_ok)) { _, _ -> viewModel.onPreflightAccepted() }
               .setNegativeButton(getString(R.string.preflight_cancel)) { _, _ -> finish() }
        }
        dlg.show()
    }

    private fun showDeleteChatDialog(index: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_chat)
            .setMessage(R.string.delete_chat_confirm)
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteChat(index) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showModelSelectionDialog() {
        val downloadedModels = filesDir.listFiles { _, n -> n.endsWith(".litertlm") }?.map { it.name } ?: emptyList()
        val options = AVAILABLE_MODELS.map {
            if (downloadedModels.contains(it.name + ".litertlm")) "${it.name} (Downloaded)" else it.name 
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.choose_model_title)
            .setItems(options) { _, i -> 
                val model = AVAILABLE_MODELS[i]
                val file = File(filesDir, "${model.name}.litertlm")
                if (file.exists()) {
                    viewModel.loadModel(file)
                } else {
                    viewModel.downloadModel(model)
                }
            }
            .setCancelable(true)
            .show()
    }

    private fun showHistoryLimitDialog() {
        val options = arrayOf("Don't remember (0)", "1 message", "2 messages", "3 messages", "4 messages", "5 messages")
        val values = intArrayOf(0, 1, 2, 3, 4)
        val current = prefs.getInt("history_limit", 3)
        val checkedItem = values.indexOf(current).takeIf { it >= 0 } ?: 2

        AlertDialog.Builder(this)
            .setTitle("Context Depth")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val newLimit = values[which]
                prefs.edit().putInt("history_limit", newLimit).apply()
                historyLimitButton.text = if (newLimit == 0) "Context memory: Off" else "Memory: $newLimit messages"
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSystemPromptDialog() {
        val chat = viewModel.uiState.value.currentChat ?: return
        val input = EditText(this)
        input.setText(chat.systemPrompt)
        input.hint = "Example: Answer concisely and like a pirate"
        
        val pad = resources.displayMetrics.density * 16
        input.setPadding(pad.toInt(), pad.toInt(), pad.toInt(), pad.toInt())

        AlertDialog.Builder(this)
            .setTitle("System Prompt")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                viewModel.setSystemPrompt(input.text.toString().trim())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showErrorDialog(
        message: String,
        title: String = getString(R.string.error_dialog_title)
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.close), null)
            .show()
    }

    private fun showAttachmentMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "📷 Take a photo")
        popup.menu.add(0, 2, 1, "🖼 Choose from gallery")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        takePhoto()
                    } else {
                        requestCameraPermLauncher.launch(Manifest.permission.CAMERA)
                    }
                    true
                }
                2 -> {
                    pickImageLauncher.launch("image/*")
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun takePhoto() {
        try {
            val file = File(cacheDir, "camera_${System.currentTimeMillis()}.jpg")
            cameraImageFile = file   // track for deletion after send
            cameraImageUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            takePictureLauncher.launch(cameraImageUri!!)
        } catch (e: Exception) {
            cameraImageFile = null
            Toast.makeText(this, "Error starting camera", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Audio recording ───────────────────────────────────────────────────────
    private fun toggleRecording() {
        if (isRecording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val sampleRate = 16000
        val bufSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        
        if (bufSize <= 0) {
            Toast.makeText(this, "AudioRecord error: bad bufSize", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Toast.makeText(this, "AudioRecord not initialized", Toast.LENGTH_SHORT).show()
                audioRecord?.release()
                audioRecord = null
                return
            }
            audioRecord?.startRecording()
            isRecording = true
            audioButton.setColorFilter(ContextCompat.getColor(this, R.color.record_red))

            lifecycleScope.launch(Dispatchers.IO) {
                val tempFile = File(cacheDir, "temp_record.pcm")
                try {
                    FileOutputStream(tempFile).use { out ->
                        val buf = ByteArray(bufSize)
                        val maxBytes = sampleRate * 2 * 30 // 30 sec limit (16kHz 16-bit mono)
                        var totalBytes = 0

                        while (isRecording && totalBytes < maxBytes) {
                            val read = audioRecord?.read(buf, 0, buf.size) ?: 0
                            if (read > 0) {
                                out.write(buf, 0, read)
                                totalBytes += read
                            }
                        }
                    }
                    
                    if (isRecording) { // Hit the 30s limit
                        withContext(Dispatchers.Main) { stopRecording() }
                    }

                    if (tempFile.exists() && tempFile.length() > 0) {
                        recordedAudioBytes = pcmToWav(tempFile.readBytes(), sampleRate, 1, 16)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    tempFile.delete()
                    withContext(Dispatchers.Main) {
                        if (recordedAudioBytes != null) {
                            audioPreviewCard.visibility = View.VISIBLE
                            attachmentRow.visibility = View.VISIBLE
                            audioButton.isEnabled = false
                        }
                        audioButton.clearColorFilter()
                    }
                }
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        } finally {
            audioRecord?.release()
            audioRecord = null
            audioButton.clearColorFilter()
        }
    }

    // ── WAV helper ────────────────────────────────────────────────────────────
    private fun pcmToWav(pcm: ByteArray, sampleRate: Int, channels: Int, bits: Int): ByteArray {
        val byteRate = sampleRate * channels * bits / 8
        val total = pcm.size + 36
        val h = ByteArray(44)
        fun Int.le4(off: Int) { h[off]=(this and 0xff).toByte(); h[off+1]=((this shr 8) and 0xff).toByte(); h[off+2]=((this shr 16) and 0xff).toByte(); h[off+3]=((this shr 24) and 0xff).toByte() }
        fun Int.le2(off: Int) { h[off]=(this and 0xff).toByte(); h[off+1]=((this shr 8) and 0xff).toByte() }
        "RIFF".forEachIndexed { i, c -> h[i] = c.code.toByte() }
        total.le4(4)
        "WAVE".forEachIndexed { i, c -> h[8+i] = c.code.toByte() }
        "fmt ".forEachIndexed { i, c -> h[12+i] = c.code.toByte() }
        16.le4(16); 1.le2(20); channels.le2(22); sampleRate.le4(24)
        byteRate.le4(28); (channels*bits/8).le2(32); bits.le2(34)
        "data".forEachIndexed { i, c -> h[36+i] = c.code.toByte() }
        pcm.size.le4(40)
        return h + pcm
    }

    // ── Theme ─────────────────────────────────────────────────────────────────
    private fun applyTheme(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(mode)
        val isDark = mode == AppCompatDelegate.MODE_NIGHT_YES
        setTheme(if (isDark) R.style.Theme_NeuralPocket_Dark else R.style.Theme_NeuralPocket)
    }

    override fun onDestroy() {
        if (isRecording) stopRecording()
        super.onDestroy()
    }
}
