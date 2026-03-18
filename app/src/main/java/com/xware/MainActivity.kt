package com.xware

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

class MainActivity : AppCompatActivity() {

    lateinit var webView: WebView
    private lateinit var bridge: AndroidBridge

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        setupWebView()
        loadApp()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // YouTube 재생을 위해 데스크탑 Chrome UA 사용
        val desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled   = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess   = true
            allowContentAccess = true
            mixedContentMode  = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode         = WebSettings.LOAD_DEFAULT
            userAgentString   = desktopUA
            setSupportZoom(false)
            builtInZoomControls  = false
            displayZoomControls  = false
            // 자바스크립트로 window.open 허용 (YouTube IFrame 필요)
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
        }

        webView.setBackgroundColor(Color.parseColor("#06060F"))

        // 쿠키 허용
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        bridge = AndroidBridge(this, webView)
        webView.addJavascriptInterface(bridge, "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, req: WebResourceRequest
            ): Boolean {
                val url = req.url.toString()
                if (url.startsWith("file://") ||
                    url.contains("youtube.com") ||
                    url.contains("googlevideo.com") ||
                    url.contains("ytimg.com") ||
                    url.contains("googleapis.com"))
                    return false
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                return true
            }

            override fun onReceivedError(
                view: WebView, req: WebResourceRequest, err: WebResourceError
            ) {
                if (req.isForMainFrame)
                    view.loadUrl("file:///android_asset/index.html")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                android.util.Log.d("XWare/JS",
                    "[${msg.messageLevel()}] ${msg.message()}")
                return true
            }
        }
    }

    private fun loadApp() {
        webView.loadUrl("file:///android_asset/index.html")
    }

    fun handleBack() {
        runOnUiThread {
            webView.evaluateJavascript(
                "window.androidBack && window.androidBack()", null)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { handleBack() }

    fun sendToWebView(json: String) {
        runOnUiThread {
            val escaped = json
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
            webView.evaluateJavascript(
                "window.__xw && window.__xw('$escaped')", null)
        }
    }

    fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")), REQ_OVERLAY)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) {
            val granted = Settings.canDrawOverlays(this)
            runOnUiThread {
                webView.evaluateJavascript(
                    "window.onOverlayPermission && window.onOverlayPermission($granted)", null)
            }
        }
    }

    override fun onResume()  { super.onResume();  webView.onResume() }
    override fun onPause()   { super.onPause();   webView.onPause() }
    override fun onDestroy() { bridge.destroy();  webView.destroy(); super.onDestroy() }

    companion object { const val REQ_OVERLAY = 1001 }
}
