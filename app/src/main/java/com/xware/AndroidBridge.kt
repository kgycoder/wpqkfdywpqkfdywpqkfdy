package com.xware

import android.content.Intent
import android.provider.Settings
import android.webkit.JavascriptInterface
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * JavaScript ↔ Android bridge.
 * All @JavascriptInterface methods are called on a background thread by WebView.
 * Coroutines dispatch back to Main for UI, IO for network.
 */
class AndroidBridge(
    private val activity: MainActivity,
    private val webView: android.webkit.WebView
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val api   = ApiService()

    /* ── JS → Android ───────────────────────────────── */

    @JavascriptInterface
    fun postMessage(json: String) {
        scope.launch {
            try {
                val msg  = JSONObject(json)
                val type = msg.optString("type")
                val id   = msg.optString("id", "0")
                when (type) {
                    "search"        -> handleSearch(msg.optString("query"), id)
                    "suggest"       -> handleSuggest(msg.optString("query"), id)
                    "fetchLyrics"   -> handleFetchLyrics(
                        msg.optString("title"),
                        msg.optString("channel"),
                        msg.optDouble("duration", 0.0),
                        id
                    )
                    "overlayMode"   -> handleOverlayMode(msg.optBoolean("active", false))
                    "overlayLyrics" -> handleOverlayLyrics(
                        msg.optString("prev"),
                        msg.optString("active"),
                        msg.optString("next1")
                    )
                    // Window management: no-op on Android
                    "drag", "minimize", "maximize", "close", "setTitle" -> { /* ignore */ }
                }
            } catch (e: Exception) {
                android.util.Log.e("XWare/Bridge", "postMessage error: ${e.message}")
            }
        }
    }

    /** JS calls this when it has consumed the back event */
    @JavascriptInterface
    fun exitApp() {
        activity.runOnUiThread { activity.finish() }
    }

    /** JS calls this to ask for overlay permission */
    @JavascriptInterface
    fun requestOverlayPermission() {
        activity.runOnUiThread { activity.requestOverlayPermission() }
    }

    /** JS can check if the device can draw overlays */
    @JavascriptInterface
    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(activity)

    /* ── Handlers ────────────────────────────────────── */

    private suspend fun handleSearch(query: String, id: String) {
        val result = JSONObject()
        result.put("type", "searchResult")
        result.put("id", id)
        try {
            val tracks = withContext(Dispatchers.IO) { api.searchYouTube(query) }
            result.put("success", true)
            result.put("tracks", tracks)
        } catch (e: Exception) {
            result.put("success", false)
            result.put("error", e.message ?: "검색 오류")
            result.put("tracks", org.json.JSONArray())
        }
        activity.sendToWebView(result.toString())
    }

    private suspend fun handleSuggest(query: String, id: String) {
        val result = JSONObject()
        result.put("type", "suggestResult")
        result.put("id", id)
        try {
            val sugs = withContext(Dispatchers.IO) { api.getSuggestions(query) }
            result.put("success", true)
            result.put("suggestions", sugs)
        } catch (e: Exception) {
            result.put("success", false)
            result.put("suggestions", org.json.JSONArray())
        }
        activity.sendToWebView(result.toString())
    }

    private suspend fun handleFetchLyrics(
        title: String, channel: String, duration: Double, id: String
    ) {
        val result = JSONObject()
        result.put("type", "lyricsResult")
        result.put("id", id)
        try {
            val lines = withContext(Dispatchers.IO) { api.fetchLyrics(title, channel, duration) }
            if (lines != null) {
                result.put("success", true)
                result.put("lines", lines)
            } else {
                result.put("success", false)
                result.put("lines", org.json.JSONArray())
            }
        } catch (e: Exception) {
            result.put("success", false)
            result.put("lines", org.json.JSONArray())
        }
        activity.sendToWebView(result.toString())
    }

    private fun handleOverlayMode(active: Boolean) {
        val intent = Intent(activity, OverlayService::class.java)
        if (active) {
            if (!Settings.canDrawOverlays(activity)) {
                // Ask for permission, overlay will be activated after grant
                activity.requestOverlayPermission()
                return
            }
            intent.action = OverlayService.ACTION_START
            activity.startService(intent)
        } else {
            intent.action = OverlayService.ACTION_STOP
            activity.startService(intent)
        }
    }

    private fun handleOverlayLyrics(prev: String, active: String, next: String) {
        val intent = Intent(activity, OverlayService::class.java).apply {
            action = OverlayService.ACTION_UPDATE_LYRICS
            putExtra(OverlayService.EXTRA_PREV,   prev)
            putExtra(OverlayService.EXTRA_ACTIVE, active)
            putExtra(OverlayService.EXTRA_NEXT,   next)
        }
        activity.startService(intent)
    }

    fun destroy() { scope.cancel() }
}
