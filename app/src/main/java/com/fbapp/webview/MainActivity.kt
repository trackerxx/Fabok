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
        }

        // Handles files that the page pushes out for download (media, documents, etc.)
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)

            if (url.startsWith("blob:")) {
                // DownloadManager can't fetch blob: URLs itself — pull the bytes out via JS instead.
                val js = """
                    (function() {
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
                        xhr.send();
                    })();
                """.trimIndent()
                webView.evaluateJavascript(js, null)
                return@setDownloadListener
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

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl("https://m.facebook.com")
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
