package com.jarvis.assistant.ui.orb

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.jarvis.assistant.data.model.ConversationState

/**
 * Helper class to manage the 2D Canvas HTML5 Arc Reactor Orb inside a WebView.
 * Handles configuration, lifecycle, JS evaluation, state transitions, and audio level modulation.
 */
class OrbHelper(
    private val webView: WebView,
    private val onOrbClicked: (() -> Unit)? = null
) {

    private var isLoaded = false
    private var pendingState: String? = null
    private var pendingAudioLevel: Float = 0f

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun setup() {
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = true
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isLoaded = true
                pendingState?.let {
                    setOrbState(it, pendingAudioLevel)
                    pendingState = null
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                android.util.Log.d("OrbWebView", "${message?.message()} -- line ${message?.lineNumber()}")
                return true
            }
        }

        if (onOrbClicked != null) {
            val gestureDetector = android.view.GestureDetector(
                webView.context,
                object : android.view.GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                        onOrbClicked.invoke()
                        return true
                    }
                }
            )
            webView.setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                false
            }
        }

        webView.loadUrl("file:///android_asset/orb/index.html")
    }

    fun updateState(state: ConversationState, audioLevel: Float = 0f) {
        val orbKey = state.orbKey
        setOrbState(orbKey, audioLevel)
    }

    fun setOrbState(stateName: String, audioLevel: Float = 0f) {
        val level = audioLevel.coerceIn(0f, 1f)
        if (!isLoaded) {
            pendingState = stateName
            pendingAudioLevel = level
            return
        }
        val js = "javascript:if(window.setOrbState){window.setOrbState('$stateName', $level);}"
        webView.post {
            webView.evaluateJavascript(js, null)
        }
    }

    fun updateAudioLevel(level: Float) {
        if (!isLoaded) return
        val clamped = level.coerceIn(0f, 1f)
        val js = "javascript:if(window.setAudioLevel){window.setAudioLevel($clamped);}"
        webView.post {
            webView.evaluateJavascript(js, null)
        }
    }
}
