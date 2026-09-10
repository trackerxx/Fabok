package com.fbapp.webview

import android.Manifest
import android.annotation.SuppressLint
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
import android.content.res.Configuration
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

        // Bridge so JS can hand blob: image/video data back to Android for saving.
        webView.addJavascriptInterface(BlobSaveInterface(), "AndroidSave")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                // Commit the login/session cookie to disk so it survives a background process kill.
                CookieManager.getInstance().flush()
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

        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:")) {
                // DownloadManager can't fetch blob: URLs directly (they only live in page memory).
                // Read the blob back inside the page via JS and hand the bytes to Android as base64.
                val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
                val js = """
                    (function() {
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', '$url', true);
                        xhr.responseType = 'blob';
                        xhr.onload = function() {
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                AndroidSave.saveBase64('$fileName', reader.result);
                            };
                            reader.readAsDataURL(xhr.response);
                        };
                        xhr.send();
                    })();
                """.trimIndent()
                webView.evaluateJavascript(js, null)
                Toast.makeText(this, "Save hocche...", Toast.LENGTH_SHORT).show()
                return@setDownloadListener
            }
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

        // Long-press on any post image -> offer to save it straight to the phone's Gallery.
        webView.setOnLongClickListener {
            val result = webView.hitTestResult
            val imageUrl = when (result.type) {
                WebView.HitTestResult.IMAGE_TYPE,
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> result.extra
                else -> null
            }
            if (imageUrl != null) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Save Image")
                    .setMessage("Ei chobi ta gallery te save korte chan?")
                    .setPositiveButton("Save") { _, _ -> saveImageToGallery(imageUrl) }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            } else {
                false
            }
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl("https://m.facebook.com")
        }
    }

    private fun saveImageToGallery(imageUrl: String) {
        Toast.makeText(this, "Save hocche...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val connection = URL(imageUrl).openConnection() as HttpURLConnection
                val cookie = CookieManager.getInstance().getCookie(imageUrl)
                if (cookie != null) {
                    connection.setRequestProperty("Cookie", cookie)
                }
                connection.connect()
                val bytes = connection.inputStream.use { it.readBytes() }
                connection.disconnect()

                val fileName = "FB_${System.currentTimeMillis()}.jpg"
                saveBytesToGallery(bytes, fileName, "image/jpeg")
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Save byartho: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** Called from JS when the page hands us a blob (Facebook's own "Save" button) as base64. */
    inner class BlobSaveInterface {
        @JavascriptInterface
        fun saveBase64(suggestedName: String, dataUrl: String) {
            Thread {
                try {
                    // dataUrl looks like "data:image/jpeg;base64,....." — strip the prefix.
                    val commaIndex = dataUrl.indexOf(',')
                    val meta = dataUrl.substring(0, commaIndex)
                    val base64Part = dataUrl.substring(commaIndex + 1)
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
