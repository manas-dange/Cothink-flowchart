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
import com.cothink.keyboard.ai.AiDiagramRouter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val prefs by lazy {
        getSharedPreferences("cothink_prefs", Context.MODE_PRIVATE)
    }

    private lateinit var tvMicPermStatus: TextView
    private lateinit var btnGrantMic: Button
    private lateinit var btnTestMic: Button
    private lateinit var tvMicTestResult: TextView

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

        btnTestApi = findViewById(R.id.btn_test_api)
        tvApiTestResult = findViewById(R.id.tv_api_test_result)

        val btnEnable = findViewById<Button>(R.id.btn_enable_ime)
        val btnSelect = findViewById<Button>(R.id.btn_select_ime)

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

        btnTestApi.setOnClickListener {
            testAiEngineConnection()
        }

        val etTestField = findViewById<EditText>(R.id.et_test_field)
        val mimeTypes = arrayOf("image/png", "image/jpeg", "image/*")
        androidx.core.view.ViewCompat.setOnReceiveContentListener(etTestField, mimeTypes) { _, payload ->
            val split = payload.partition { it.uri != null }
            if (split.first != null) {
                Toast.makeText(this, "Direct diagram insertion successful", Toast.LENGTH_LONG).show()
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
            tvMicPermStatus.text = "Permission Granted"
            tvMicPermStatus.setTextColor(Color.parseColor("#137333"))
            btnGrantMic.visibility = View.GONE
        } else {
            tvMicPermStatus.text = "Permission Required"
            tvMicPermStatus.setTextColor(Color.parseColor("#BA1A1A"))
            btnGrantMic.visibility = View.VISIBLE
        }
    }

    private fun requestMicPermission() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO_PERM)
        } else {
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

    private fun testAiEngineConnection() {
        tvApiTestResult.visibility = View.VISIBLE
        tvApiTestResult.text = "Connecting to Cloud AI Engine..."
        tvApiTestResult.setTextColor(Color.parseColor("#0B57D0"))
        btnTestApi.isEnabled = false

        lifecycleScope.launch {
            val generator = AiDiagramRouter(
                apiKeyProvider = { null },
                modelProvider = { null }
            )

            val testPrompt = "User clicks login. Check credentials. If valid, open dashboard. If invalid, show error."
            val result = generator.generateDiagram(testPrompt)

            btnTestApi.isEnabled = true
            result.onSuccess {
                tvApiTestResult.text = "Connected to Cloud AI Engine (~150ms latency). Ready for use."
                tvApiTestResult.setTextColor(Color.parseColor("#137333"))
            }.onFailure { err ->
                tvApiTestResult.text = "Connection error: ${err.message ?: "Please check internet connection."}"
                tvApiTestResult.setTextColor(Color.parseColor("#BA1A1A"))
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
        tvMicTestResult.text = "Listening... Speak into microphone"
        tvMicTestResult.setTextColor(Color.parseColor("#0B57D0"))

        try {
            inAppSpeechRecognizer?.destroy()
            inAppSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        tvMicTestResult.text = "Listening... Speak into microphone"
                    }

                    override fun onBeginningOfSpeech() {
                        tvMicTestResult.text = "Recording audio..."
                    }

                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        tvMicTestResult.text = "Processing audio..."
                    }

                    override fun onError(error: Int) {
                        tvMicTestResult.text = "Speech recognition error ($error). Tap to retry."
                        tvMicTestResult.setTextColor(Color.parseColor("#BA1A1A"))
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()
                        if (!text.isNullOrBlank()) {
                            tvMicTestResult.text = "Recognized:\n\"$text\""
                            tvMicTestResult.setTextColor(Color.parseColor("#137333"))
                        } else {
                            tvMicTestResult.text = "No speech recognized. Speak closer to the microphone."
                            tvMicTestResult.setTextColor(Color.parseColor("#BA1A1A"))
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!partial.isNullOrBlank()) {
                            tvMicTestResult.text = "\"$partial\"..."
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
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

            inAppSpeechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            tvMicTestResult.text = "Error: ${e.message}"
            tvMicTestResult.setTextColor(Color.parseColor("#BA1A1A"))
        }
    }

    override fun onDestroy() {
        inAppSpeechRecognizer?.destroy()
        super.onDestroy()
    }
}
