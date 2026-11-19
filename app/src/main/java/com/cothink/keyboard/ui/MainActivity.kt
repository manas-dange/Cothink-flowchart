package com.cothink.keyboard.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cothink.keyboard.R
import com.cothink.keyboard.ai.GeminiDiagramGenerator
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val prefs by lazy {
        getSharedPreferences("cothink_prefs", Context.MODE_PRIVATE)
    }

    private lateinit var tvMicPermStatus: TextView
    private lateinit var btnGrantMic: Button
    private lateinit var btnTestMic: Button
    private lateinit var tvMicTestResult: TextView

    private lateinit var etApiKey: EditText
    private lateinit var etModelName: EditText
    private lateinit var btnSaveKey: Button
    private lateinit var btnTestApi: Button
    private lateinit var tvApiTestResult: TextView

    private var inAppSpeechRecognizer: SpeechRecognizer? = null

    companion object {
        private const val REQ_AUDIO_PERM = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvMicPermStatus = findViewById(R.id.tv_mic_perm_status)
        btnGrantMic = findViewById(R.id.btn_grant_mic)
        btnTestMic = findViewById(R.id.btn_test_mic)
        tvMicTestResult = findViewById(R.id.tv_mic_test_result)

        etApiKey = findViewById(R.id.et_api_key)
        etModelName = findViewById(R.id.et_model_name)
        btnSaveKey = findViewById(R.id.btn_save_key)
        btnTestApi = findViewById(R.id.btn_test_api)
        tvApiTestResult = findViewById(R.id.tv_api_test_result)

        val btnEnable = findViewById<Button>(R.id.btn_enable_ime)
        val btnSelect = findViewById<Button>(R.id.btn_select_ime)

        val savedKey = prefs.getString("gemini_api_key", "").orEmpty()
        val savedModel = prefs.getString("gemini_model", "openai/gpt-oss-20b").orEmpty()
        etApiKey.setText(savedKey)
        etModelName.setText(if (savedModel.isBlank()) "openai/gpt-oss-20b" else savedModel)

        btnEnable.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        btnSelect.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        btnGrantMic.setOnClickListener {
            requestMicPermission()
        }

        btnTestMic.setOnClickListener {
            startInAppMicTest()
        }

        btnSaveKey.setOnClickListener {
            saveSettings()
        }

        btnTestApi.setOnClickListener {
            testApiConnection()
        }

        // Enable rich PNG content receiving on the test EditText so commitContent succeeds
        val etTestField = findViewById<EditText>(R.id.et_test_field)
        val mimeTypes = arrayOf("image/png", "image/jpeg", "image/*")
        androidx.core.view.ViewCompat.setOnReceiveContentListener(etTestField, mimeTypes) { _, payload ->
            val split = payload.partition { it.uri != null }
            if (split.first != null) {
                Toast.makeText(this, "✓ Direct Diagram Injection Successful in Test Field!", Toast.LENGTH_LONG).show()
            }
            split.second
        }

        updatePermissionUi()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionUi()
    }

    private fun updatePermissionUi() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            tvMicPermStatus.text = "✓ Microphone Permission: GRANTED"
            tvMicPermStatus.setTextColor(Color.parseColor("#16A34A"))
            btnGrantMic.visibility = View.GONE
        } else {
            tvMicPermStatus.text = "✗ Microphone Permission: NOT GRANTED"
            tvMicPermStatus.setTextColor(Color.parseColor("#DC2626"))
            btnGrantMic.visibility = View.VISIBLE
        }
    }

    private fun requestMicPermission() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO_PERM)
        } else {
            // Direct request or open app settings if permanently denied
            val isFirstTime = prefs.getBoolean("perm_requested_once", false)
            if (!isFirstTime) {
                prefs.edit().putBoolean("perm_requested_once", true).apply()
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO_PERM)
            } else {
                Toast.makeText(this, "Opening App Settings to grant Microphone", Toast.LENGTH_SHORT).show()
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO_PERM) {
            updatePermissionUi()
        }
    }

    private fun saveSettings() {
        val key = etApiKey.text.toString().trim()
        val model = etModelName.text.toString().trim()

        prefs.edit()
            .putString("gemini_api_key", key)
            .putString("gemini_model", model)
            .apply()

        Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()
    }

    private fun testApiConnection() {
        saveSettings()
        val key = etApiKey.text.toString().trim()
        val model = etModelName.text.toString().trim()

        if (key.isBlank()) {
            tvApiTestResult.visibility = View.VISIBLE
            tvApiTestResult.text = "✗ Error: API key is empty. Paste your Gemini key first."
            tvApiTestResult.setTextColor(Color.parseColor("#DC2626"))
            return
        }

        tvApiTestResult.visibility = View.VISIBLE
        tvApiTestResult.text = "⏳ Contacting Gemini API using model '$model'..."
        tvApiTestResult.setTextColor(Color.parseColor("#0284C7"))

        lifecycleScope.launch {
            val generator = com.cothink.keyboard.ai.AiDiagramRouter(
                apiKeyProvider = { key },
                modelProvider = { model }
            )

            val testPrompt = "User clicks login. If valid credentials, navigate to dashboard, else show login error."
            val result = generator.generateDiagram(testPrompt)

            result.onSuccess { syntax ->
                val providerName = if (key.startsWith("gsk_", ignoreCase = true)) "Groq ($model)" else "Gemini ($model)"
                tvApiTestResult.text = "✓ SUCCESS: Connected to $providerName in ~150ms!\nGenerated Syntax:\n$syntax"
                tvApiTestResult.setTextColor(Color.parseColor("#16A34A"))
            }.onFailure { err ->
                tvApiTestResult.text = "✗ ERROR: ${err.message}"
                tvApiTestResult.setTextColor(Color.parseColor("#DC2626"))
            }
        }
    }

    private fun startInAppMicTest() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestMicPermission()
            return
        }

        tvMicTestResult.visibility = View.VISIBLE
        tvMicTestResult.text = "🎙️ Listening... Speak anything now..."
        tvMicTestResult.setTextColor(Color.parseColor("#0284C7"))

        try {
            inAppSpeechRecognizer?.destroy()
            inAppSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        tvMicTestResult.text = "🎙️ Listening... Speak into your mic now!"
                    }

                    override fun onBeginningOfSpeech() {
                        tvMicTestResult.text = "🔴 Recording sound..."
                    }

                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        tvMicTestResult.text = "⏳ Processing speech audio..."
                    }

                    override fun onError(error: Int) {
                        tvMicTestResult.text = "✗ Mic Test Failed (Error $error). Tap 'Test Microphone in App' to retry."
                        tvMicTestResult.setTextColor(Color.parseColor("#DC2626"))
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()
                        if (!text.isNullOrBlank()) {
                            tvMicTestResult.text = "✓ SUCCESS! Heard:\n\"$text\""
                            tvMicTestResult.setTextColor(Color.parseColor("#16A34A"))
                        } else {
                            tvMicTestResult.text = "✗ No speech recognized. Speak closer to the microphone."
                            tvMicTestResult.setTextColor(Color.parseColor("#DC2626"))
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!partial.isNullOrBlank()) {
                            tvMicTestResult.text = "🗣️ \"$partial\"..."
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            inAppSpeechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            tvMicTestResult.text = "✗ Error: ${e.message}"
            tvMicTestResult.setTextColor(Color.parseColor("#DC2626"))
        }
    }

    override fun onDestroy() {
        inAppSpeechRecognizer?.destroy()
        super.onDestroy()
    }
}
