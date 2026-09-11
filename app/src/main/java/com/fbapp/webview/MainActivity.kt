package com.fbapp.webview

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.view.WindowCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val fileChooserRequestCode = 200
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* if denied, the relevant action (upload/download) just won't work until retried */ }

    /**
     * DownloadManager can only fetch http/https URLs, but some pages generate
     * blob: URLs for exports/media. This bridge lets injected JS hand the blob's
     * base64 content straight to Kotlin so it can be written to Downloads directly.
     */
    private inner class BlobDownloader {
        @JavascriptInterface
        fun saveBase64File(base64Data: String, fileName: String) {
            try {
                val commaIndex = base64Data.indexOf(",")
                val pureBase64 = if (commaIndex != -1) base64Data.substring(commaIndex + 1) else base64Data
                val bytes = Base64.decode(pureBase64, Base64.DEFAULT)

                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val outFile = File(downloadsDir, fileName)

                FileOutputStream(outFile).use { it.write(bytes) }

                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Downloaded $fileName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun reportError(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Save error: $message", Toast.LENGTH_LONG).show()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyStatusBarColor()
        requestNeededPermissions()

        webView = WebView(this)
        setContentView(webView)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)

        // Keep login sessions saved
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                // Commit the login/session cookie to disk so it survives a background process kill.
                CookieManager.getInstance().flush()
            }
        }

        webView.addJavascriptInterface(BlobDownloader(), "AndroidDownloader")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                // Always offer both images and videos, regardless of what the page asked for.
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                    addCategory(Intent.CATEGORY_OPENABLE)
                    if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }

                try {
                    startActivityForResult(
                        Intent.createChooser(intent, "Select Photo or Video"),
                        fileChooserRequestCode
                    )
                } catch (e: Exception) {
                    filePathCallback = null
                    Toast.makeText(this@MainActivity, "File chooser open kora jai ni", Toast.LENGTH_SHORT).show()
                    return false
                }
                return true
            }

            // Some "Save" flows open a popup (window.open / target="_blank") instead of
            // navigating the main WebView. Without this, WebView silently blocks the popup
            // and nothing happens when the button is tapped.
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val popup = WebView(this@MainActivity)
                popup.settings.javaScriptEnabled = true
                popup.settings.domStorageEnabled = true
                popup.addJavascriptInterface(BlobDownloader(), "AndroidDownloader")

                // Reuse the same download handling for anything the popup tries to save.
                popup.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                    handleDownload(popup, url, contentDisposition, mimeType)
                }

                // The popup never needs to be shown on screen; it only exists to let the
                // page's JS run (fetch/blob/save logic) and hand the result back to us.
                popup.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        Toast.makeText(this@MainActivity, "Popup loaded: $url", Toast.LENGTH_SHORT).show()
                    }
                }

                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = popup
                resultMsg?.sendToTarget()
                return true
            }

            // Surfaces JS errors as Toasts so problems are visible without USB/adb debugging.
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    if (it.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                        Toast.makeText(
                            this@MainActivity,
                            "JS error: ${it.message()} (line ${it.lineNumber()})",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                return true
            }
        }

        // Handles files that the page pushes out for download (media, documents, etc.)
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            handleDownload(webView, url, contentDisposition, mimeType)
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl("https://m.facebook.com")
        }
    }

    /** Shared download logic used by both the main WebView and any popup WebView. */
    private fun handleDownload(source: WebView, url: String, contentDisposition: String?, mimeType: String?) {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)

        if (url.startsWith("blob:")) {
            val isImage = mimeType?.startsWith("image") == true ||
                fileName.substringAfterLast('.', "").lowercase() in
                    listOf("jpg", "jpeg", "png", "webp", "gif")

            val js = if (isImage) {
                // Some pages (e.g. Facebook) set a CSP that blocks XHR/fetch to blob: URLs
                // (connect-src). Loading it into an <img> and reading it back via <canvas>
                // is governed by img-src instead, which is usually permitted, so this
                // sidesteps the CSP block.
                """
                (function() {
                    try {
                        var img = new Image();
                        img.onload = function() {
                            try {
                                var canvas = document.createElement('canvas');
                                canvas.width = img.naturalWidth;
                                canvas.height = img.naturalHeight;
                                var ctx = canvas.getContext('2d');
                                ctx.drawImage(img, 0, 0);
                                var dataUrl = canvas.toDataURL('image/jpeg', 0.95);
                                AndroidDownloader.saveBase64File(dataUrl, '$fileName');
                            } catch (e) {
                                AndroidDownloader.reportError('canvas: ' + e.message);
                            }
                        };
                        img.onerror = function() {
                            AndroidDownloader.reportError('image load failed for blob');
                        };
                        img.src = '$url';
                    } catch (e) {
                        AndroidDownloader.reportError('img setup: ' + e.message);
                    }
                })();
                """.trimIndent()
            } else {
                // Non-image blobs (video, documents) - try the direct XHR read.
                // This can still fail under a strict CSP; reportError will surface why.
                """
                (function() {
                    try {
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', '$url', true);
                        xhr.responseType = 'blob';
                        xhr.onload = function() {
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                AndroidDownloader.saveBase64File(reader.result, '$fileName');
                            };
                            reader.readAsDataURL(xhr.response);
                        };
                        xhr.onerror = function() {
                            AndroidDownloader.reportError('XHR failed to read blob (possible CSP block)');
                        };
                        xhr.send();
                    } catch (e) {
                        AndroidDownloader.reportError('xhr setup: ' + e.message);
                    }
                })();
                """.trimIndent()
            }
            source.evaluateJavascript(js, null)
            return
        }

        try {
            val cookie = CookieManager.getInstance().getCookie(url) ?: ""

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                addRequestHeader("cookie", cookie)
                setMimeType(mimeType)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }

            getSystemService<DownloadManager>()?.enqueue(request)
            Toast.makeText(this, "Downloading $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Asks for the storage/media/notification permissions needed for upload & download. */
    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.READ_MEDIA_IMAGES
            needed += Manifest.permission.READ_MEDIA_VIDEO
            needed += Manifest.permission.READ_MEDIA_AUDIO
        } else {
            needed += Manifest.permission.READ_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
            }
        }

        val notGranted = needed.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun applyStatusBarColor() {
        val color = Color.parseColor("#242424")

        window.statusBarColor = color
        window.navigationBarColor = color

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == fileChooserRequestCode) {
            var results: Array<Uri>? = null
            if (resultCode == RESULT_OK && data != null) {
                val dataUri = data.data
                val clipData = data.clipData
                if (clipData != null) {
                    results = Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                } else if (dataUri != null) {
                    results = arrayOf(dataUri)
                }
            }
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onPause() {
        super.onPause()
        // Facebook uses this login session; commit it before we background for the file picker.
        CookieManager.getInstance().flush()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
