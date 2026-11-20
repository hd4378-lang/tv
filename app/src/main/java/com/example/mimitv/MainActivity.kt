package com.example.mimitv

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingOverlay: LinearLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var statusText: TextView
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val prefsName = "mimitv_prefs"
    private val keyLastNum = "last_domain_number"
    private val startNumber = 15
    private val maxNumber = 99

    private var currentNumber = startNumber
    private var pageLoadedSuccessfully = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null

    private fun buildUrl(num: Int): String = "https://mimitv${num}.com/"

    private fun isAndroidTV(): Boolean {
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                packageManager.hasSystemFeature("android.software.leanback")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        statusText = findViewById(R.id.statusText)

        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        currentNumber = prefs.getInt(keyLastNum, startNumber)

        setupWebView()
        loadNumber(currentNumber)
    }

    private fun saveWorkingNumber(num: Int) {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().putInt(keyLastNum, num).apply()
    }

    private fun loadNumber(num: Int) {
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        currentNumber = num
        pageLoadedSuccessfully = false

        val url = buildUrl(num)
        statusText.text = "접속 시도 중: $url"
        loadingOverlay.visibility = View.VISIBLE

        webView.loadUrl(url)

        val timeoutRunnable = Runnable {
            if (!pageLoadedSuccessfully) {
                tryNextNumber()
            }
        }
        retryRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, 6000)
    }

    private fun tryNextNumber() {
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        if (currentNumber < maxNumber) {
            val nextNum = currentNumber + 1
            statusText.text = "접속 실패 → https://mimitv${nextNum}.com/ 시도 중..."
            loadingOverlay.visibility = View.VISIBLE
            mainHandler.postDelayed({ loadNumber(nextNum) }, 500)
        } else {
            currentNumber = startNumber
            statusText.text = "연결 가능한 주소를 찾지 못했습니다. 네트워크를 확인해 주세요."
            loadingOverlay.visibility = View.VISIBLE
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        if (isAndroidTV()) {
            settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    view?.loadUrl(url)
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val loadedUrl = url ?: return

                if (!loadedUrl.startsWith("chrome-error://") && loadedUrl.startsWith("http")) {
                    view?.evaluateJavascript("document.body ? document.body.innerText.trim().length : 0") { lenStr ->
                        val textLen = lenStr?.toIntOrNull() ?: 0
                        if (textLen > 20) {
                            pageLoadedSuccessfully = true
                            retryRunnable?.let { mainHandler.removeCallbacks(it) }
                            saveWorkingNumber(currentNumber)
                            loadingOverlay.visibility = View.GONE
                        }
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true && !pageLoadedSuccessfully) {
                    tryNextNumber()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                @Suppress("DEPRECATION")
                super.onReceivedError(view, errorCode, description, failingUrl)
                if (failingUrl != null && failingUrl.startsWith(buildUrl(currentNumber).dropLast(1)) && !pageLoadedSuccessfully) {
                    tryNextNumber()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE

                if (!isAndroidTV()) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
                hideSystemBars()
            }

            override fun onHideCustomView() {
                if (customView == null) return
                fullscreenContainer.removeView(customView)
                fullscreenContainer.visibility = View.GONE
                customView = null
                customViewCallback?.onHideCustomView()
                webView.visibility = View.VISIBLE

                if (!isAndroidTV()) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
                showSystemBars()
            }
        }
    }

    private fun hideSystemBars() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun showSystemBars() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (customView != null) {
                webView.webChromeClient?.onHideCustomView()
                return true
            }
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        super.onDestroy()
    }
}
