package com.cothink.keyboard.renderer

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MermaidRenderService : Service() {

    private var webView: WebView? = null
    private var isWebViewReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingDeferred: CompletableDeferred<Uri>? = null

    inner class LocalBinder : Binder() {
        fun getService(): MermaidRenderService = this@MermaidRenderService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate() {
        super.onCreate()
        try {
            WebView.enableSlowWholeDocumentDraw()
        } catch (e: Exception) {
            // Ignored if already initialized
        }
        mainHandler.post {
            initWebView()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        webView = WebView(applicationContext).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_NO_CACHE
            }

            addJavascriptInterface(AndroidBridge(), "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isWebViewReady = true
                }
            }

            loadUrl("file:///android_asset/mermaid_bridge.html")
        }
    }

    /**
     * Renders Mermaid syntax into a PNG file and returns its content:// URI.
     */
    suspend fun renderSyntaxToImage(syntax: String): Result<Uri> {
        val deferred = CompletableDeferred<Uri>()
        pendingDeferred = deferred

        mainHandler.post {
            if (!isWebViewReady || webView == null) {
                mainHandler.postDelayed({
                    executeRender(syntax)
                }, 500)
            } else {
                executeRender(syntax)
            }
        }

        return try {
            val uri = deferred.await()
            Result.success(uri)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            pendingDeferred = null
        }
    }

    private fun executeRender(syntax: String) {
        val escaped = syntax
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("$", "\\$")
            .replace("\n", "\\n")
            .replace("\r", "")

        val script = "renderMermaidToPng(`$escaped`);"
        webView?.evaluateJavascript(script, null)
    }

    inner class AndroidBridge {

        @JavascriptInterface
        fun onRenderSuccess(base64Data: String) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val cleanBase64 = if (base64Data.contains(",")) {
                        base64Data.substringAfter(",")
                    } else {
                        base64Data
                    }

                    val imageBytes = Base64.decode(cleanBase64, Base64.DEFAULT)

                    val diagramsDir = File(cacheDir, "diagrams").apply { mkdirs() }
                    val imageFile = File(diagramsDir, "diagram_${System.currentTimeMillis()}.png")

                    FileOutputStream(imageFile).use { fos ->
                        fos.write(imageBytes)
                        fos.flush()
                    }

                    val contentUri = FileProvider.getUriForFile(
                        applicationContext,
                        "${applicationContext.packageName}.fileprovider",
                        imageFile
                    )

                    pendingDeferred?.complete(contentUri)
                } catch (e: Exception) {
                    pendingDeferred?.completeExceptionally(e)
                }
            }
        }

        @JavascriptInterface
        fun onDomReady(width: Int, height: Int) {
            // Only execute if onRenderSuccess did not already complete
            if (pendingDeferred?.isCompleted == true) return

            mainHandler.postDelayed({
                if (pendingDeferred?.isCompleted == true) return@postDelayed
                try {
                    val wv = webView ?: return@postDelayed
                    val scale = 2
                    val renderWidth = Math.max(width * scale, 600)
                    val renderHeight = Math.max(height * scale, 400)

                    wv.measure(
                        View.MeasureSpec.makeMeasureSpec(renderWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(renderHeight, View.MeasureSpec.EXACTLY)
                    )
                    wv.layout(0, 0, renderWidth, renderHeight)

                    val bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    wv.draw(canvas)

                    saveBitmapToFile(bitmap)
                } catch (e: Exception) {
                    pendingDeferred?.completeExceptionally(e)
                }
            }, 100)
        }

        @JavascriptInterface
        fun onRenderError(errorMessage: String) {
            pendingDeferred?.completeExceptionally(IOException("Mermaid render error: $errorMessage"))
        }
    }

    private fun saveBitmapToFile(bitmap: Bitmap) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val diagramsDir = File(cacheDir, "diagrams").apply { mkdirs() }
                val imageFile = File(diagramsDir, "diagram_${System.currentTimeMillis()}.png")

                FileOutputStream(imageFile).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    fos.flush()
                }

                val contentUri = FileProvider.getUriForFile(
                    applicationContext,
                    "${applicationContext.packageName}.fileprovider",
                    imageFile
                )

                pendingDeferred?.complete(contentUri)
            } catch (e: Exception) {
                pendingDeferred?.completeExceptionally(e)
            }
        }
    }

    override fun onDestroy() {
        mainHandler.post {
            webView?.destroy()
            webView = null
        }
        super.onDestroy()
    }
}
