package com.cothink.keyboard.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Color
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
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.google.android.material.button.MaterialButton
import com.cothink.keyboard.R
import com.cothink.keyboard.ai.GroqSmeBrainstormer
import com.cothink.keyboard.ai.GroqTextEnhancer
import com.cothink.keyboard.ai.MermaidSanitizer
import com.cothink.keyboard.ai.SmeBrainstormer
import com.cothink.keyboard.ai.TextEnhancer
import com.cothink.keyboard.renderer.MermaidRenderService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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

    private lateinit var normalTopBar: LinearLayout
    private lateinit var enhancedPreviewBar: LinearLayout
    private lateinit var enhancedPreviewText: TextView
    private lateinit var applyEnhancedButton: ImageButton
    private lateinit var discardEnhancedButton: ImageButton
    private lateinit var enhanceButton: Button
    private lateinit var demoButton: Button
    private lateinit var askButton: Button

    // Brainstorm / SME Panel Views
    private lateinit var brainstormPanelLayout: LinearLayout
    private lateinit var brainstormHeaderText: TextView
    private lateinit var closeBrainstormButton: ImageButton
    private lateinit var brainstormQueryInput: EditText
    private lateinit var brainstormSendButton: ImageButton
    private lateinit var brainstormChipsScroll: HorizontalScrollView
    private lateinit var brainstormResultText: TextView
    private lateinit var brainstormProgressBar: ProgressBar
    private lateinit var copyBrainstormButton: Button
    private lateinit var insertBrainstormButton: Button

    private lateinit var chipProsCons: Button
    private lateinit var chipStrategy: Button
    private lateinit var chipCounterargs: Button
    private lateinit var chipExplain: Button

    private lateinit var shiftButton: MaterialButton
    private lateinit var backspaceButton: MaterialButton
    private lateinit var mode123Button: Button
    private val letterButtons = mutableMapOf<Int, Button>()

    private var isSymbolsMode = false
    private var isShifted = false

    private var currentImageUri: Uri? = null
    private var lastErrorMessage: String? = null
    private var isListening = false
    private var currentEnhancedText: String? = null
    private var currentBrainstormText: String? = null

    private val diagramGenerator by lazy {
        val prefs = getSharedPreferences("cothink_prefs", Context.MODE_PRIVATE)
        com.cothink.keyboard.ai.AiDiagramRouter(
            apiKeyProvider = { prefs.getString("gemini_api_key", null) },
            modelProvider = { prefs.getString("gemini_model", "openai/gpt-oss-20b") }
        )
    }

    private val textEnhancer: TextEnhancer by lazy {
        val prefs = getSharedPreferences("cothink_prefs", Context.MODE_PRIVATE)
        GroqTextEnhancer(
            apiKeyProvider = { prefs.getString("gemini_api_key", null) },
            modelProvider = { prefs.getString("gemini_model", null) }
        )
    }

    private val smeBrainstormer: SmeBrainstormer by lazy {
        val prefs = getSharedPreferences("cothink_prefs", Context.MODE_PRIVATE)
        GroqSmeBrainstormer(
            apiKeyProvider = { prefs.getString("gemini_api_key", null) },
            modelProvider = { prefs.getString("gemini_model", null) }
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

        normalTopBar = view.findViewById(R.id.layout_normal_top_bar)
        enhancedPreviewBar = view.findViewById(R.id.layout_enhanced_preview)
        enhancedPreviewText = view.findViewById(R.id.tv_enhanced_preview)
        applyEnhancedButton = view.findViewById(R.id.btn_apply_enhanced)
        discardEnhancedButton = view.findViewById(R.id.btn_discard_enhanced)
        enhanceButton = view.findViewById(R.id.btn_enhance)
        demoButton = view.findViewById(R.id.btn_demo)
        askButton = view.findViewById(R.id.btn_ask)

        // Brainstorm Panel
        brainstormPanelLayout = view.findViewById(R.id.brainstorm_panel_layout)
        brainstormHeaderText = view.findViewById(R.id.tv_brainstorm_header)
        closeBrainstormButton = view.findViewById(R.id.btn_close_brainstorm)
        brainstormQueryInput = view.findViewById(R.id.et_brainstorm_query)
        brainstormSendButton = view.findViewById(R.id.btn_brainstorm_send)
        brainstormChipsScroll = view.findViewById(R.id.scroll_brainstorm_chips)
        brainstormResultText = view.findViewById(R.id.tv_brainstorm_result)
        brainstormProgressBar = view.findViewById(R.id.progress_brainstorm)
        copyBrainstormButton = view.findViewById(R.id.btn_copy_brainstorm)
        insertBrainstormButton = view.findViewById(R.id.btn_insert_brainstorm)

        chipProsCons = view.findViewById(R.id.chip_pros_cons)
        chipStrategy = view.findViewById(R.id.chip_strategy)
        chipCounterargs = view.findViewById(R.id.chip_counterargs)
        chipExplain = view.findViewById(R.id.chip_explain)

        setupKeyListeners(view)
        setupVoiceAndPreviewListeners(view)

        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        hideEnhancedPreview()
        hideBrainstormPanel()
    }

    private fun setupVoiceAndPreviewListeners(view: View) {
        statusText.setOnClickListener {
            val error = lastErrorMessage
            if (!error.isNullOrBlank()) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            }
        }

        enhancedPreviewText.setOnClickListener {
            val fullText = currentEnhancedText
            if (!fullText.isNullOrBlank()) {
                Toast.makeText(this, fullText, Toast.LENGTH_LONG).show()
            }
        }

        enhanceButton.setOnClickListener {
            enhanceCurrentHostText()
        }

        applyEnhancedButton.setOnClickListener {
            applyEnhancedText()
        }

        discardEnhancedButton.setOnClickListener {
            hideEnhancedPreview()
        }

        demoButton.setOnClickListener {
            val hostText = getHostFieldText().trim()
            generateDiagramFromText(hostText)
        }

        askButton.setOnClickListener {
            handleAskButtonClick()
        }

        closeBrainstormButton.setOnClickListener {
            hideBrainstormPanel()
        }

        copyBrainstormButton.setOnClickListener {
            copyBrainstormToClipboard()
        }

        insertBrainstormButton.setOnClickListener {
            insertBrainstormIntoChat()
        }

        brainstormSendButton.setOnClickListener {
            val query = brainstormQueryInput.text.toString().trim()
            if (query.isNotBlank()) {
                executeBrainstorm(query)
            }
        }

        brainstormQueryInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                val query = brainstormQueryInput.text.toString().trim()
                if (query.isNotBlank()) {
                    executeBrainstorm(query)
                }
                true
            } else {
                false
            }
        }

        chipProsCons.setOnClickListener {
            triggerBrainstormWithPrefix("Pros and cons of")
        }

        chipStrategy.setOnClickListener {
            triggerBrainstormWithPrefix("Key strategic advice and actions for")
        }

        chipCounterargs.setOnClickListener {
            triggerBrainstormWithPrefix("Counterarguments and blind spots regarding")
        }

        chipExplain.setOnClickListener {
            triggerBrainstormWithPrefix("Explain simply like an expert:")
        }

        micButton.setOnClickListener {
            if (isListening) {
                isListening = false
                micButton.clearColorFilter()
                statusText.text = "Processing speech..."
                try {
                    speechRecognizer?.stopListening()
                } catch (e: Exception) {
                    speechRecognizer?.cancel()
                }
            } else {
                startVoiceCapture()
            }
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

    private fun handleAskButtonClick() {
        val hostText = getHostFieldText().trim()
        showBrainstormPanel()
        if (hostText.isNotBlank()) {
            brainstormQueryInput.setText(hostText)
            executeBrainstorm(hostText)
        } else {
            brainstormQueryInput.setText("")
            brainstormResultText.text = "Enter a topic above or tap a starter chip to brainstorm with your SME."
            brainstormProgressBar.visibility = View.GONE
        }
    }

    private fun triggerBrainstormWithPrefix(prefix: String) {
        val existing = brainstormQueryInput.text.toString().trim()
        val query = if (existing.isNotBlank()) "$prefix: $existing" else prefix
        brainstormQueryInput.setText(query)
        brainstormQueryInput.setSelection(query.length)
        if (existing.isNotBlank()) {
            executeBrainstorm(query)
        }
    }

    private fun executeBrainstorm(query: String) {
        if (query.isBlank()) return
        brainstormProgressBar.visibility = View.VISIBLE
        brainstormResultText.text = "Consulting Subject Matter Expert..."

        serviceScope.launch {
            val result = smeBrainstormer.brainstorm(query)
            brainstormProgressBar.visibility = View.GONE
            result.onSuccess { output ->
                currentBrainstormText = output
                brainstormResultText.text = output
            }.onFailure { err ->
                brainstormResultText.text = "Error: ${err.message}"
            }
        }
    }

    private fun showBrainstormPanel() {
        normalLayout.visibility = View.GONE
        previewLayout.visibility = View.GONE
        brainstormPanelLayout.visibility = View.VISIBLE
    }

    private fun hideBrainstormPanel() {
        brainstormPanelLayout.visibility = View.GONE
        normalLayout.visibility = View.VISIBLE
    }

    private fun insertBrainstormIntoChat() {
        val text = currentBrainstormText
        if (!text.isNullOrBlank()) {
            currentInputConnection?.commitText(text, 1)
        }
        hideBrainstormPanel()
    }

    private fun copyBrainstormToClipboard() {
        val text = currentBrainstormText ?: brainstormResultText.text.toString()
        if (text.isNotBlank()) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("SME Brainstorm", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getHostFieldText(): String {
        val ic = currentInputConnection ?: return ""
        val before = ic.getTextBeforeCursor(5000, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(5000, 0)?.toString().orEmpty()
        return before + after
    }

    private fun enhanceCurrentHostText() {
        val rawText = getHostFieldText().trim()
        if (rawText.isBlank()) {
            Toast.makeText(this, "Type text in chat bar first to enhance", Toast.LENGTH_SHORT).show()
            resetStatus("Type text to enhance")
            return
        }

        statusText.text = "Enhancing text..."
        progressBar.visibility = View.VISIBLE
        enhanceButton.isEnabled = false

        serviceScope.launch {
            val result = textEnhancer.enhanceText(rawText)
            enhanceButton.isEnabled = true
            progressBar.visibility = View.GONE
            result.onSuccess { enhanced ->
                showEnhancedPreview(enhanced)
            }.onFailure { err ->
                resetStatus("Enhance error: ${err.message}")
            }
        }
    }

    private fun showEnhancedPreview(text: String) {
        currentEnhancedText = text
        enhancedPreviewText.text = text
        enhancedPreviewText.isSelected = true
        normalTopBar.visibility = View.GONE
        enhancedPreviewBar.visibility = View.VISIBLE
    }

    private fun hideEnhancedPreview() {
        currentEnhancedText = null
        enhancedPreviewBar.visibility = View.GONE
        normalTopBar.visibility = View.VISIBLE
        resetStatus("Tap mic or Enhance")
    }

    private fun applyEnhancedText() {
        val enhanced = currentEnhancedText ?: return
        val ic = currentInputConnection
        if (ic != null) {
            ic.beginBatchEdit()
            try {
                val before = ic.getTextBeforeCursor(5000, 0)?.toString().orEmpty()
                val after = ic.getTextAfterCursor(5000, 0)?.toString().orEmpty()
                ic.deleteSurroundingText(before.length, after.length)
                ic.commitText(enhanced, 1)
            } finally {
                ic.endBatchEdit()
            }
        }
        hideEnhancedPreview()
    }

    private fun generateDiagramFromText(prompt: String) {
        val query = prompt.trim().ifBlank {
            "User clicks login. Check database. If credentials valid, navigate to dashboard. If invalid, show error message."
        }
        hideEnhancedPreview()
        hideBrainstormPanel()
        statusText.text = getString(R.string.generating)
        progressBar.visibility = View.VISIBLE

        serviceScope.launch {
            val genResult = diagramGenerator.generateDiagram(query)
            genResult.onSuccess { syntax ->
                statusText.text = getString(R.string.rendering)
                renderDiagram(syntax, query)
            }.onFailure { err ->
                resetStatus("AI error: ${err.message}")
            }
        }
    }

    private fun isDiagramIntent(transcript: String): Boolean {
        val lower = transcript.lowercase()
        return lower.contains("flowchart") ||
               lower.contains("diagram") ||
               lower.contains("flow of") ||
               lower.contains("draw a flow") ||
               lower.contains("make a flow") ||
               lower.contains("generate a flow") ||
               lower.contains("create a flow") ||
               lower.contains("show a flow") ||
               lower.contains("workflow") ||
               lower.contains("process flow") ||
               lower.contains("draw a") ||
               lower.contains("mindmap") ||
               lower.contains("chart")
    }

    private fun setupKeyListeners(root: View) {
        shiftButton = root.findViewById(R.id.key_shift)
        backspaceButton = root.findViewById(R.id.key_backspace)
        mode123Button = root.findViewById(R.id.key_123)

        val keyIds = listOf(
            R.id.key_q, R.id.key_w, R.id.key_e, R.id.key_r, R.id.key_t,
            R.id.key_y, R.id.key_u, R.id.key_i, R.id.key_o, R.id.key_p,
            R.id.key_a, R.id.key_s, R.id.key_d, R.id.key_f, R.id.key_g,
            R.id.key_h, R.id.key_j, R.id.key_k, R.id.key_l,
            R.id.key_z, R.id.key_x, R.id.key_c, R.id.key_v, R.id.key_b,
            R.id.key_n, R.id.key_m, R.id.key_comma, R.id.key_period
        )

        for (id in keyIds) {
            root.findViewById<Button>(id)?.let { btn ->
                letterButtons[id] = btn
                btn.setOnClickListener {
                    val charToCommit = btn.text.toString()
                    currentInputConnection?.commitText(charToCommit, 1)
                    if (isShifted && !isSymbolsMode) {
                        isShifted = false
                        updateKeyboardKeys()
                    }
                }
            }
        }

        shiftButton.setOnClickListener {
            if (isSymbolsMode) {
                currentInputConnection?.commitText("/", 1)
            } else {
                isShifted = !isShifted
                updateKeyboardKeys()
            }
        }

        backspaceButton.setOnClickListener {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }

        mode123Button.setOnClickListener {
            isSymbolsMode = !isSymbolsMode
            isShifted = false
            updateKeyboardKeys()
        }

        root.findViewById<Button>(R.id.key_space)?.setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
        }

        root.findViewById<Button>(R.id.key_enter)?.setOnClickListener {
            sendKeyChar('\n')
        }

        updateKeyboardKeys()
    }

    private fun updateKeyboardKeys() {
        if (isSymbolsMode) {
            mode123Button.text = "ABC"
            shiftButton.icon = null
            shiftButton.text = "/"
            shiftButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#383A40"))

            letterButtons[R.id.key_q]?.text = "1"
            letterButtons[R.id.key_w]?.text = "2"
            letterButtons[R.id.key_e]?.text = "3"
            letterButtons[R.id.key_r]?.text = "4"
            letterButtons[R.id.key_t]?.text = "5"
            letterButtons[R.id.key_y]?.text = "6"
            letterButtons[R.id.key_u]?.text = "7"
            letterButtons[R.id.key_i]?.text = "8"
            letterButtons[R.id.key_o]?.text = "9"
            letterButtons[R.id.key_p]?.text = "0"

            letterButtons[R.id.key_a]?.text = "@"
            letterButtons[R.id.key_s]?.text = "#"
            letterButtons[R.id.key_d]?.text = "$"
            letterButtons[R.id.key_f]?.text = "%"
            letterButtons[R.id.key_g]?.text = "&"
            letterButtons[R.id.key_h]?.text = "-"
            letterButtons[R.id.key_j]?.text = "+"
            letterButtons[R.id.key_k]?.text = "("
            letterButtons[R.id.key_l]?.text = ")"

            letterButtons[R.id.key_z]?.text = "*"
            letterButtons[R.id.key_x]?.text = "\""
            letterButtons[R.id.key_c]?.text = "'"
            letterButtons[R.id.key_v]?.text = ":"
            letterButtons[R.id.key_b]?.text = ";"
            letterButtons[R.id.key_n]?.text = "!"
            letterButtons[R.id.key_m]?.text = "?"

            letterButtons[R.id.key_comma]?.text = "="
            letterButtons[R.id.key_period]?.text = "."
        } else {
            mode123Button.text = "?123"
            shiftButton.text = ""
            if (isShifted) {
                shiftButton.icon = ContextCompat.getDrawable(this, R.drawable.ic_shift_caps)
                shiftButton.iconTint = ColorStateList.valueOf(Color.WHITE)
                shiftButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2563EB"))
            } else {
                shiftButton.icon = ContextCompat.getDrawable(this, R.drawable.ic_shift)
                shiftButton.iconTint = ColorStateList.valueOf(Color.parseColor("#C4C6D0"))
                shiftButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#383A40"))
            }

            val caseTransform: (String) -> String = { if (isShifted) it.uppercase() else it.lowercase() }

            letterButtons[R.id.key_q]?.text = caseTransform("q")
            letterButtons[R.id.key_w]?.text = caseTransform("w")
            letterButtons[R.id.key_e]?.text = caseTransform("e")
            letterButtons[R.id.key_r]?.text = caseTransform("r")
            letterButtons[R.id.key_t]?.text = caseTransform("t")
            letterButtons[R.id.key_y]?.text = caseTransform("y")
            letterButtons[R.id.key_u]?.text = caseTransform("u")
            letterButtons[R.id.key_i]?.text = caseTransform("i")
            letterButtons[R.id.key_o]?.text = caseTransform("o")
            letterButtons[R.id.key_p]?.text = caseTransform("p")

            letterButtons[R.id.key_a]?.text = caseTransform("a")
            letterButtons[R.id.key_s]?.text = caseTransform("s")
            letterButtons[R.id.key_d]?.text = caseTransform("d")
            letterButtons[R.id.key_f]?.text = caseTransform("f")
            letterButtons[R.id.key_g]?.text = caseTransform("g")
            letterButtons[R.id.key_h]?.text = caseTransform("h")
            letterButtons[R.id.key_j]?.text = caseTransform("j")
            letterButtons[R.id.key_k]?.text = caseTransform("k")
            letterButtons[R.id.key_l]?.text = caseTransform("l")

            letterButtons[R.id.key_z]?.text = caseTransform("z")
            letterButtons[R.id.key_x]?.text = caseTransform("x")
            letterButtons[R.id.key_c]?.text = caseTransform("c")
            letterButtons[R.id.key_v]?.text = caseTransform("v")
            letterButtons[R.id.key_b]?.text = caseTransform("b")
            letterButtons[R.id.key_n]?.text = caseTransform("n")
            letterButtons[R.id.key_m]?.text = caseTransform("m")

            letterButtons[R.id.key_comma]?.text = ","
            letterButtons[R.id.key_period]?.text = "."
        }
    }

    private fun startVoiceCapture() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
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
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 60000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 8000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 8000L)
            }

            isListening = true
            micButton.setColorFilter(Color.parseColor("#EF4444"))
            statusText.text = "Listening... Tap mic when done"
            progressBar.visibility = View.VISIBLE
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            micButton.clearColorFilter()
            resetStatus("Voice error: ${e.message}")
        }
    }

    override fun onResults(results: Bundle?) {
        isListening = false
        micButton.clearColorFilter()
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val transcript = matches?.firstOrNull()

        if (transcript.isNullOrBlank()) {
            resetStatus("No speech detected")
            return
        }

        processTranscript(transcript)
    }

    private fun processTranscript(transcript: String) {
        if (isDiagramIntent(transcript)) {
            generateDiagramFromText(transcript)
        } else {
            statusText.text = "Polishing speech..."
            progressBar.visibility = View.VISIBLE
            serviceScope.launch {
                val result = textEnhancer.enhanceText(transcript)
                progressBar.visibility = View.GONE
                result.onSuccess { enhanced ->
                    showEnhancedPreview(enhanced)
                }.onFailure { err ->
                    resetStatus("AI error: ${err.message}")
                }
            }
        }
    }

    private fun renderDiagram(syntax: String, transcript: String = "") {
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
                if (transcript.isNotBlank()) {
                    val fallbackSyntax = MermaidSanitizer.fallbackDiagram(transcript)
                    val retryResult = renderer.renderSyntaxToImage(fallbackSyntax)
                    if (retryResult.isSuccess) {
                        showPreview(retryResult.getOrThrow())
                        return@launch
                    }
                }
                resetStatus("Render error: ${err.message}")
            }
        }
    }

    private fun showPreview(uri: Uri) {
        currentImageUri = uri
        progressBar.visibility = View.GONE
        normalLayout.visibility = View.GONE
        brainstormPanelLayout.visibility = View.GONE
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
            Toast.makeText(this, "Diagram copied to clipboard", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to copy diagram", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetToKeyboard() {
        currentImageUri = null
        previewLayout.visibility = View.GONE
        brainstormPanelLayout.visibility = View.GONE
        normalLayout.visibility = View.VISIBLE
        hideEnhancedPreview()
        resetStatus("Tap mic or Enhance")
    }

    private fun resetStatus(message: String) {
        lastErrorMessage = message
        statusText.text = if (message.length > 32) "${message.take(28)}... (tap for info)" else message
        progressBar.visibility = View.GONE
    }

    override fun onError(error: Int) {
        isListening = false
        micButton.clearColorFilter()
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
            11 -> "Microphone unavailable or in use by another app (e.g. call). Tap mic to retry."
            13 -> "Language model unavailable offline. Check internet connection."
            else -> "Voice error ($error). Tap mic to retry."
        }
        speechRecognizer?.cancel()
        resetStatus(message)
    }

    override fun onReadyForSpeech(params: Bundle?) {
        statusText.text = "Listening... Speak now"
    }

    override fun onBeginningOfSpeech() {
        statusText.text = "Recording voice..."
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
            statusText.text = "\"$partial\""
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
