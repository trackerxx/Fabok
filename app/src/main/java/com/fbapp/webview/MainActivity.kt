package com.fbapp.webview

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val storagePermissionCode = 100
    private val fileChooserRequestCode = 200
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // Buffers base64 chunks sent from JS while a blob download is in progress, keyed by sessionId.
    private val chunkBuffers = HashMap<String, StringBuilder>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyStatusBarColor()
        requestStoragePermissionIfNeeded()

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

        // Bridge so JS can hand blob image/video data back to Android for saving.
        webView.addJavascriptInterface(AndroidSaveBridge(), "AndroidSave")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                // Commit the login/session cookie to disk so it survives a background process kill.
                CookieManager.getInstance().flush()
                // DOM-based long-press image detection (bypasses Facebook's invisible click-layer overlay).
                view.evaluateJavascript(longPressDetectionJs, null)
            }
        }

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

        // Handles Facebook's own "Save" button on photos/videos.
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:")) {
                // DownloadManager can't fetch blob: URLs directly (they only live in page memory).
                // Read the blob back inside the page via JS, in small chunks, and hand it to Android.
                val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
                val js = buildBlobFetchJs(url, fileName)
                webView.evaluateJavascript(js, null)
                Toast.makeText(this, "Save hocche...", Toast.LENGTH_SHORT).show()
                return@setDownloadListener
            }
            // Plain http/https download link (e.g. a direct video URL).
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(
                    mimeType ?: MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(url))
                )
                val cookie = CookieManager.getInstance().getCookie(url)
                request.addRequestHeader("cookie", cookie)
                request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

                val downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                downloadManager.enqueue(request)
                Toast.makeText(this, "ডাউনলোড শুরু হয়েছে...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "ডাউনলোড ব্যর্থ হয়েছে: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl("https://m.facebook.com")
        }
    }

    /** JS that fetches a blob: URL and streams it to Android as base64 chunks (avoids the Binder size limit). */
    private fun buildBlobFetchJs(url: String, fileName: String): String {
        return """
            (function() {
                var xhr = new XMLHttpRequest();
                xhr.open('GET', '$url', true);
                xhr.responseType = 'blob';
                xhr.onload = function() {
                    var reader = new FileReader();
                    reader.onloadend = function() {
                        var dataUrl = reader.result;
                        var commaIndex = dataUrl.indexOf(',');
                        var meta = dataUrl.substring(0, commaIndex);
                        var base64 = dataUrl.substring(commaIndex + 1);
                        var chunkSize = 300000;
                        var sessionId = 's' + Date.now();
                        for (var i = 0; i < base64.length; i += chunkSize) {
                            AndroidSave.saveChunk(sessionId, base64.substring(i, i + chunkSize));
                        }
                        AndroidSave.finishSave(sessionId, '$fileName', meta);
                    };
                    reader.readAsDataURL(xhr.response);
                };
                xhr.onerror = function() { AndroidSave.onError('blob fetch failed'); };
                xhr.send();
            })();
        """.trimIndent()
    }

    /**
     * Injected on every onPageFinished. Listens for a long-press directly in the page's own DOM
     * and walks up from the touched element to find the nearest <img src> or CSS background-image.
     * Facebook's invisible click-layer sits on top of the real <img>, so Android's own
     * getHitTestResult()/touch-detection often grabs the overlay instead of the image — reading the
     * DOM from inside the page itself is what actually sees the real element underneath.
     * Guarded with a window flag so re-injection on subsequent onPageFinished calls is a no-op.
     */
    private val longPressDetectionJs = """
        (function() {
            if (window.__androidLongPressInstalled) return;
            window.__androidLongPressInstalled = true;

            var pressTimer = null;
            var startX = 0, startY = 0;
            var MOVE_THRESHOLD = 12;
            var LONG_PRESS_MS = 450;

            function findImageUrl(el) {
                var node = el;
                var depth = 0;
                while (node && depth < 6) {
                    if (node.tagName === 'IMG' && node.src) {
                        return node.src;
                    }
                    var bg = window.getComputedStyle(node).backgroundImage;
                    if (bg && bg !== 'none') {
                        var match = bg.match(/url\(["']?(.*?)["']?\)/);
                        if (match && match[1]) return match[1];
                    }
                    node = node.parentElement;
                    depth++;
                }
                return null;
            }

            function clearTimer() {
                if (pressTimer) { clearTimeout(pressTimer); pressTimer = null; }
            }

            document.addEventListener('touchstart', function(e) {
                if (e.touches.length !== 1) { clearTimer(); return; }
                var touch = e.touches[0];
                startX = touch.clientX;
                startY = touch.clientY;
                var target = e.target;
                clearTimer();
                pressTimer = setTimeout(function() {
                    pressTimer = null;
                    var url = findImageUrl(target);
                    if (url) {
                        try { AndroidSave.onImageLongPress(url); } catch (err) {}
                    }
                }, LONG_PRESS_MS);
            }, { passive: true });

            document.addEventListener('touchmove', function(e) {
                if (!pressTimer) return;
                var touch = e.touches[0];
                if (Math.abs(touch.clientX - startX) > MOVE_THRESHOLD ||
                    Math.abs(touch.clientY - startY) > MOVE_THRESHOLD) {
                    clearTimer();
                }
            }, { passive: true });

            document.addEventListener('touchend', clearTimer, { passive: true });
            document.addEventListener('touchcancel', clearTimer, { passive: true });
        })();
    """.trimIndent()

    /** Bridge exposed to JS: chunked transfer of the blob data behind Facebook's "Save" button. */
    inner class AndroidSaveBridge {
        @JavascriptInterface
        fun onImageLongPress(url: String) {
            runOnUiThread {
                showSaveImageDialog(url)
            }
        }

        @JavascriptInterface
        fun saveChunk(sessionId: String, chunk: String) {
            synchronized(chunkBuffers) {
                chunkBuffers.getOrPut(sessionId) { StringBuilder() }.append(chunk)
            }
        }

        @JavascriptInterface
        fun finishSave(sessionId: String, suggestedName: String, meta: String) {
            Thread {
                try {
                    val base64Part = synchronized(chunkBuffers) {
                        chunkBuffers.remove(sessionId)?.toString()
                    } ?: throw Exception("No data received")
                    val bytes = Base64.decode(base64Part, Base64.DEFAULT)

                    val isVideo = meta.contains("video")
                    val mimeType = if (isVideo) "video/mp4" else "image/jpeg"
                    val extension = if (isVideo) "mp4" else "jpg"
                    val fileName = if (suggestedName.contains(".")) suggestedName
                        else "FB_${System.currentTimeMillis()}.$extension"

                    saveBytesToGallery(bytes, fileName, mimeType)
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Save byartho: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }

        @JavascriptInterface
        fun onError(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Save byartho: $message", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Writes raw bytes into the device Gallery (Pictures for images, Movies for video). */
    private fun saveBytesToGallery(bytes: ByteArray, fileName: String, mimeType: String) {
        val isVideo = mimeType.startsWith("video")
        val relativeDir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        var saved = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: write via MediaStore, no storage permission needed.
            val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            }
            val uri = contentResolver.insert(collection, contentValues)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    ByteArrayInputStream(bytes).copyTo(out)
                }
                saved = true
            }
        } else {
            // Older Android: write directly into the public folder, then scan it.
            val dir = Environment.getExternalStoragePublicDirectory(relativeDir)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            FileOutputStream(file).use { out ->
                ByteArrayInputStream(bytes).copyTo(out)
            }
            MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null, null)
            saved = true
        }

        runOnUiThread {
            if (saved) {
                Toast.makeText(this, "Gallery-te save hoyeche", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Save kora jai ni", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Small confirmation so a long scroll-press doesn't accidentally trigger a save. */
    private fun showSaveImageDialog(url: String) {
        AlertDialog.Builder(this)
            .setTitle("Save Image")
            .setMessage("Ei image ta gallery-te save korte chao?")
            .setPositiveButton("Save") { _, _ -> downloadImageDirect(url) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Fetches the exact DOM-resolved image URL directly (with the FB session cookie attached,
     * in case it's a private/CDN-signed asset) and writes it straight into the gallery — no
     * dependency on Facebook's own blob-based "Save" button.
     */
    private fun downloadImageDirect(url: String) {
        Toast.makeText(this, "Save hocche...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                val cookie = CookieManager.getInstance().getCookie(url)
                if (cookie != null) {
                    connection.setRequestProperty("Cookie", cookie)
                }
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AppleWebKit/537.36")
                connection.connect()

                if (connection.responseCode !in 200..299) {
                    throw Exception("HTTP ${connection.responseCode}")
                }

                val bytes = connection.inputStream.use { it.readBytes() }

                var mimeType = connection.contentType?.substringBefore(";")?.trim()
                if (mimeType.isNullOrBlank() || !mimeType.startsWith("image")) {
                    val guessedExt = MimeTypeMap.getFileExtensionFromUrl(url)
                    mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(guessedExt) ?: "image/jpeg"
                }
                val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "jpg"
                val fileName = "FB_${System.currentTimeMillis()}.$extension"

                saveBytesToGallery(bytes, fileName, mimeType)
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Save byartho: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun requestStoragePermissionIfNeeded() {
        // API 29+ (Android 10+) doesn't need this permission for public Downloads folder access.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(permission), storagePermissionCode)
            }
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
