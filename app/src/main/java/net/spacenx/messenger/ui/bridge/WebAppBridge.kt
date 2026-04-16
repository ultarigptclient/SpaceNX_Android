package net.spacenx.messenger.ui.bridge

import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

class WebAppBridge(private val dispatcher: BridgeDispatcher) {

    companion object {
        const val NAME = "AndroidBridge"
        private const val TAG = "WebAppBridge"

        /** JSONObject → Map<String, Any?> (action 제외) */
        fun parseParams(json: JSONObject): Map<String, Any?> {
            val params = mutableMapOf<String, Any?>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key != "action") params[key] = json.opt(key)
            }
            return params
        }
    }

    /**
     * 통합 브릿지 엔트리포인트 — JSON { action, ...params }
     * params는 타입을 유지하여 Map<String, Any?> 로 전달
     */
    @JavascriptInterface
    fun postMessage(message: String) {
        try {
            val json = JSONObject(message)
            val action = json.optString("action", "")
            if (action.isEmpty()) {
                Log.w(TAG, "postMessage: action is empty")
                return
            }
            if (action != "windowDrag") {
                Log.d(TAG, "postMessage: action=$action")
            }
            val params = mutableMapOf<String, Any?>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key != "action") {
                    params[key] = json.opt(key) // 타입 유지 (JSONArray, JSONObject, Number, Boolean, String)
                }
            }
            dispatcher.dispatch(action, params)
        } catch (e: Exception) {
            Log.e(TAG, "postMessage parse error: ${e.message}")
        }
    }

    @JavascriptInterface
    fun finishApp() {
        dispatcher.dispatch("finishApp")
    }

    @JavascriptInterface
    fun requestPermission() {
        dispatcher.dispatch("requestPermission")
    }

    @JavascriptInterface
    fun login(username: String, password: String) {
        dispatcher.dispatch("login", mapOf("userId" to username, "password" to password))
    }

    @JavascriptInterface
    fun autoLogin() {
        dispatcher.dispatch("autoLogin")
    }

    @JavascriptInterface
    fun agreePrivacyPolicy() {
        dispatcher.dispatch("agreePrivacyPolicy")
    }

    @JavascriptInterface
    fun logout() {
        dispatcher.dispatch("logout")
    }

    @JavascriptInterface
    fun syncChannel() {
        dispatcher.dispatch("syncChannel")
    }

    @JavascriptInterface
    fun syncBuddy() {
        dispatcher.dispatch("syncBuddy")
    }

    @JavascriptInterface
    fun updateStatusBar(hex: String) {
        dispatcher.dispatch("updateStatusBar", mapOf("hex" to hex))
    }
}
