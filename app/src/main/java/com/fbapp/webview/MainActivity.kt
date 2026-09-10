package com.igapp.webview

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.view.WindowCompat
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // Holds the WebView's callback while the system file picker is open (for uploads)
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val uris: Array<Uri>? = when {
            result.resultCode != RESULT_OK || data == null -> null
            data.clipData != null -> {
                val clip = data.clipData!!
                Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
            }
            data.data != null -> arrayOf(data.data!!)
            else -> null
        }
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* if denied, the relevant action (upload/download/mic) just won't work until retried */ }

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
        settings.allowFileAccess = true
        settings.mediaPlaybackRequiresUserGesture = false

        // Some devices need audio mode explicitly reset to NORMAL for WebView mic capture to work
        (getSystemService(AUDIO_SERVICE) as? AudioManager)?.mode = AudioManager.MODE_NORMAL

        // Keep login sessions saved
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(HIDE_OPEN_APP_JS, null)
            }
        }

        // Lets the upload/attach button inside instagram.com open the system file picker
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                fileChooserLauncher.launch(Intent.createChooser(intent, "Select file"))
                return true
            }

            // Grants mic (and camera, if requested) access for voice notes / calls,
            // as long as the matching Android runtime permission has already been granted.
            override fun onPermissionRequest(request: PermissionRequest?) {
                request ?: return
                val granted = request.resources.filter { resource ->
                    when (resource) {
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        else -> false
                    }
                }
                if (granted.isNotEmpty()) {
                    runOnUiThread { request.grant(granted.toTypedArray()) }
                } else {
                    runOnUiThread { request.deny() }
                }
            }
        }

        // Handles files that Instagram pushes out for download (photos, videos, reels, etc.)
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            try {
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val cookie = CookieManager.getInstance().getCookie(url) ?: ""

                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    addRequestHeader("cookie", cookie)
                    setMimeType(mimeType)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                }

                getSystemService<DownloadManager>()?.enqueue(request)
                Toast.makeText(this, "Downloading $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // Long-press on any post/story image -> offer to save it straight to the phone's Gallery.
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
                    .setMessage("Save this photo to your Gallery?")
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
            webView.loadUrl("https://www.instagram.com")
        }
    }

    /** Downloads the given image URL in the background and writes it into the device Gallery. */
    private fun saveImageToGallery(imageUrl: String) {
        Toast.makeText(this, "Saving...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val connection = URL(imageUrl).openConnection() as HttpURLConnection
                val cookie = CookieManager.getInstance().getCookie(imageUrl)
                if (cookie != null) {
                    connection.setRequestProperty("Cookie", cookie)
                }
                connection.connect()
                val inputStream = connection.inputStream

                val fileName = "IG_${System.currentTimeMillis()}.jpg"
                var saved = false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+: write via MediaStore, no storage permission needed.
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            inputStream.copyTo(out)
                        }
                        saved = true
                    }
                } else {
                    // Older Android: write directly into the public Pictures folder, then scan it.
                    val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    if (!picturesDir.exists()) picturesDir.mkdirs()
                    val file = File(picturesDir, fileName)
                    FileOutputStream(file).use { out ->
                        inputStream.copyTo(out)
                    }
                    MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null, null)
                    saved = true
                }

                inputStream.close()
                connection.disconnect()

                runOnUiThread {
                    if (saved) {
                        Toast.makeText(this, "Saved to Gallery", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Could not save image", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** Asks for the storage/media/mic permissions needed for upload, download & voice notes. */
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
        needed += Manifest.permission.RECORD_AUDIO

        val notGranted = needed.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun applyStatusBarColor() {
        val barColor = Color.parseColor("#0C1014")

        window.statusBarColor = barColor
        window.navigationBarColor = barColor

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
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

    companion object {
        // Continuously watches the page for "Open app" / "Get the app"
        // style native-app install prompts and hides them as they appear,
        // including ones added later while scrolling. Matches by visible
        // text/aria-label instead of CSS class names, since Instagram's
        // class names change often but the button's text stays similar.
        private const val HIDE_OPEN_APP_JS = """
            (function() {
                if (window.__openAppHiderInstalled) return;
                window.__openAppHiderInstalled = true;

                var phrases = ['open app', 'get the app', 'open in app', 'install app'];

                function isOpenAppButton(el) {
                    if (!el || el.nodeType !== 1) return false;
                    var label = (el.getAttribute('aria-label') || '').trim().toLowerCase();
                    var text = (el.textContent || '').trim().toLowerCase();
                    for (var i = 0; i < phrases.length; i++) {
                        if (label === phrases[i] || text === phrases[i]) return true;
                    }
                    return false;
                }

                function hideIfMatch(el) {
                    if (!el || el.nodeType !== 1) return;
                    if (isOpenAppButton(el)) {
                        var target = el;
                        for (var i = 0; i < 4 && target.parentElement; i++) {
                            target = target.parentElement;
                        }
                        target.style.setProperty('display', 'none', 'important');
                    }
                }

                function scan(root) {
                    try {
                        if (isOpenAppButton(root)) {
                            hideIfMatch(root);
                            return;
                        }
                        var all = root.querySelectorAll('div,a,span,button');
                        for (var i = 0; i < all.length; i++) {
                            hideIfMatch(all[i]);
                        }
                    } catch (e) {}
                }

                scan(document.body);

                var observer = new MutationObserver(function(mutations) {
                    for (var i = 0; i < mutations.length; i++) {
                        var added = mutations[i].addedNodes;
                        for (var j = 0; j < added.length; j++) {
                            scan(added[j]);
                        }
                    }
                });

                observer.observe(document.body, { childList: true, subtree: true });
            })();
        """
    }
}
