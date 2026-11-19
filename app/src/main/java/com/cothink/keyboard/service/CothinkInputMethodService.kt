package com.cothink.keyboard.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.cothink.keyboard.R
import com.cothink.keyboard.ai.GeminiDiagramGenerator
import com.cothink.keyboard.renderer.MermaidRenderService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CothinkInputMethodService : InputMethodService(), RecognitionListener {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var speechRecognizer: SpeechRecognizer? = null

    private var renderService: MermaidRenderService? = null
    private var isRenderBound = false

    private lateinit var normalLayout: LinearLayout
    private lateinit var previewLayout: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var previewImage: ImageView
    private lateinit var micButton: ImageButton
    private lateinit var approveButton: Button
    private lateinit var discardButton: Button

    private var currentImageUri: Uri? = null
    private var lastErrorMessage: String? = null

    private val diagramGenerator by lazy {
        val prefs = getSharedPreferences("cothink_prefs", Context.MODE_PRIVATE)
        com.cothink.keyboard.ai.AiDiagramRouter(
            apiKeyProvider = { prefs.getString("gemini_api_key", null) },
            modelProvider = { prefs.getString("gemini_model", "openai/gpt-oss-20b") }
        )
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? MermaidRenderService.LocalBinder
            renderService = localBinder?.getService()
            isRenderBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            renderService = null
            isRenderBound = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        val intent = Intent(this, MermaidRenderService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onCreateInputView(): View {
        val themedContext = android.view.ContextThemeWrapper(
            this,
            com.google.android.material.R.style.Theme_MaterialComponents_DayNight_NoActionBar
        )
        val view = android.view.LayoutInflater.from(themedContext).inflate(R.layout.keyboard_view, null)

        normalLayout = view.findViewById(R.id.normal_keyboard_layout)
        previewLayout = view.findViewById(R.id.diagram_preview_layout)
        statusText = view.findViewById(R.id.tv_status)
        progressBar = view.findViewById(R.id.progress_loading)
        previewImage = view.findViewById(R.id.iv_diagram_preview)
        micButton = view.findViewById(R.id.btn_mic)
        approveButton = view.findViewById(R.id.btn_approve)
        discardButton = view.findViewById(R.id.btn_discard)

        setupKeyListeners(view)
        setupVoiceAndPreviewListeners(view)

        return view
    }

    private fun setupVoiceAndPreviewListeners(view: View) {
        val demoButton = view.findViewById<Button>(R.id.btn_demo)
        statusText.setOnClickListener {
            val error = lastErrorMessage
            if (!error.isNullOrBlank()) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            }
        }

        demoButton?.setOnClickListener {
            val samplePrompt = "User clicks login. Check database. If credentials valid, navigate to dashboard. If invalid, show error message."
            statusText.text = "🗣️ \"$samplePrompt\""
            processTranscript(samplePrompt)
        }

        micButton.setOnClickListener {
            startVoiceCapture()
        }

        approveButton.setOnClickListener {
            currentImageUri?.let { uri ->
                commitDiagram(uri)
            }
        }

        discardButton.setOnClickListener {
            resetToKeyboard()
        }
    }

    private fun setupKeyListeners(root: View) {
        val keyCharMap = mapOf(
            R.id.key_q to "q", R.id.key_w to "w", R.id.key_e to "e", R.id.key_r to "r",
            R.id.key_t to "t", R.id.key_y to "y", R.id.key_u to "u", R.id.key_i to "i",
            R.id.key_o to "o", R.id.key_p to "p", R.id.key_a to "a", R.id.key_s to "s",
            R.id.key_d to "d", R.id.key_f to "f", R.id.key_g to "g", R.id.key_h to "h",
            R.id.key_j to "j", R.id.key_k to "k", R.id.key_l to "l", R.id.key_z to "z",
            R.id.key_x to "x", R.id.key_c to "c", R.id.key_v to "v", R.id.key_b to "b",
            R.id.key_n to "n", R.id.key_m to "m", R.id.key_comma to ",", R.id.key_period to "."
        )

        for ((id, char) in keyCharMap) {
            root.findViewById<Button>(id)?.setOnClickListener {
                currentInputConnection?.commitText(char, 1)
            }
        }

        root.findViewById<Button>(R.id.key_space)?.setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
        }

        root.findViewById<Button>(R.id.key_backspace)?.setOnClickListener {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }

        root.findViewById<Button>(R.id.key_enter)?.setOnClickListener {
            sendKeyChar('\n')
        }
    }

    private fun startVoiceCapture() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Microphone permission required. Please open CoThinker app to grant it.", Toast.LENGTH_LONG).show()
            try {
                val appIntent = Intent(this, com.cothink.keyboard.ui.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(appIntent)
            } catch (e: Exception) {
                // Ignore if unable to launch activity from IME
            }
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition unavailable on device", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(this@CothinkInputMethodService)
            }

            val defaultLocale = java.util.Locale.getDefault().toLanguageTag()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, defaultLocale)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            statusText.text = getString(R.string.listening)
            progressBar.visibility = View.VISIBLE
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            resetStatus("Voice error: ${e.message}")
        }
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val transcript = matches?.firstOrNull()

        if (transcript.isNullOrBlank()) {
            resetStatus("No speech detected")
            return
        }

        processTranscript(transcript)
    }

    private fun processTranscript(transcript: String) {
        statusText.text = getString(R.string.generating)
        serviceScope.launch {
            val genResult = diagramGenerator.generateDiagram(transcript)
            genResult.onSuccess { syntax ->
                statusText.text = getString(R.string.rendering)
                renderDiagram(syntax)
            }.onFailure { err ->
                resetStatus("AI error: ${err.message}")
            }
        }
    }

    private fun renderDiagram(syntax: String) {
        val renderer = renderService
        if (renderer == null) {
            resetStatus("Render service offline")
            return
        }

        serviceScope.launch {
            val renderResult = renderer.renderSyntaxToImage(syntax)
            renderResult.onSuccess { uri ->
                showPreview(uri)
            }.onFailure { err ->
                resetStatus("Render error: ${err.message}")
            }
        }
    }

    private fun showPreview(uri: Uri) {
        currentImageUri = uri
        progressBar.visibility = View.GONE
        normalLayout.visibility = View.GONE
        previewLayout.visibility = View.VISIBLE

        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                if (bitmap != null) {
                    previewImage.setImageBitmap(bitmap)
                } else {
                    previewImage.setImageURI(null)
                    previewImage.setImageURI(uri)
                }
            }
        } catch (e: Exception) {
            previewImage.setImageURI(null)
            previewImage.setImageURI(uri)
        }
    }

    private fun commitDiagram(uri: Uri) {
        val inputConnection = currentInputConnection
        val editorInfo = currentInputEditorInfo

        if (inputConnection == null || editorInfo == null) {
            copyToClipboardFallback(uri)
            resetToKeyboard()
            return
        }

        val supportedMimeTypes = EditorInfoCompat.getContentMimeTypes(editorInfo)
        val supportsPng = supportedMimeTypes.any {
            it.equals("image/png", ignoreCase = true) ||
            it.equals("image/*", ignoreCase = true) ||
            it.equals("*/*", ignoreCase = true)
        }

        if (!supportsPng) {
            // App does not accept direct rich image injection, use clipboard fallback
            copyToClipboardFallback(uri)
            resetToKeyboard()
            return
        }

        val inputContentInfo = InputContentInfoCompat(
            uri,
            android.content.ClipDescription("CoThinker Flowchart", arrayOf("image/png")),
            null
        )

        var flags = 0
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N_MR1) {
            flags = InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
        }

        val committed = InputConnectionCompat.commitContent(
            inputConnection,
            editorInfo,
            inputContentInfo,
            flags,
            null
        )

        if (committed) {
            resetToKeyboard()
        } else {
            copyToClipboardFallback(uri)
            resetToKeyboard()
        }
    }

    private fun copyToClipboardFallback(uri: Uri) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newUri(contentResolver, "CoThinker Flowchart", uri)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Diagram copied to clipboard! Long-press to paste.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to copy diagram: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetToKeyboard() {
        currentImageUri = null
        previewLayout.visibility = View.GONE
        normalLayout.visibility = View.VISIBLE
        resetStatus("Tap mic to dictate diagram")
    }

    private fun resetStatus(message: String) {
        lastErrorMessage = message
        statusText.text = if (message.length > 32) "${message.take(28)}... (tap for info)" else message
        progressBar.visibility = View.GONE
    }

    override fun onError(error: Int) {
        val message = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech heard. Speak clearly after tapping mic."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout. Tap mic to retry."
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error. Check mic."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission required."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network required for speech recognition."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy. Please wait a second."
            SpeechRecognizer.ERROR_SERVER -> "Speech server error. Please retry."
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Please wait a moment before tapping mic."
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language not supported by speech engine."
            10 -> "Speech service not configured on emulator. Tap 'Sample' button to test!"
            else -> "Voice error ($error). Tap mic to retry."
        }
        speechRecognizer?.cancel()
        resetStatus(message)
    }

    override fun onReadyForSpeech(params: Bundle?) {
        statusText.text = "Listening... Speak now"
    }

    override fun onBeginningOfSpeech() {
        statusText.text = "Recording your voice..."
    }

    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {
        statusText.text = "Processing speech..."
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val partial = matches?.firstOrNull()
        if (!partial.isNullOrBlank()) {
            statusText.text = "🗣️ \"$partial\""
        }
    }
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onDestroy() {
        if (isRenderBound) {
            unbindService(serviceConnection)
            isRenderBound = false
        }
        speechRecognizer?.destroy()
        super.onDestroy()
    }
}
