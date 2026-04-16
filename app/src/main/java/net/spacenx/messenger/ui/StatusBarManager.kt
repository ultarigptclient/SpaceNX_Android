package net.spacenx.messenger.ui

import android.graphics.Color
import android.os.Build
import android.util.Log
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import net.spacenx.messenger.R
import net.spacenx.messenger.ui.bridge.WebAppBridge

class StatusBarManager(private val activity: AppCompatActivity) {

    companion object {
        private const val TAG = "StatusBarManager"
        private const val READ_THEME_COLOR_JS =
            "var s=getComputedStyle(document.documentElement);" +
                    "var meta=document.querySelector('meta[name=\"theme-color\"]');" +
                    "var path=location.pathname||'';" +
                    "var cssVar;" +
                    "if(path.indexOf('login')!==-1)cssVar='--bg';" +
                    "else if(path.indexOf('permission')!==-1||path.indexOf('privacy')!==-1)cssVar='--header-bg';" +
                    "else cssVar='--bg-surface';" +
                    "var v=s.getPropertyValue(cssVar).trim();" +
                    "if(!v)return null;" +
                    "function toHex(c){" +
                    "if(c.startsWith('#'))return c;" +
                    "var m=c.match(/(\\d+)/g);" +
                    "if(m&&m.length>=3)return'#'+('0'+(+m[0]).toString(16)).slice(-2)" +
                    "+('0'+(+m[1]).toString(16)).slice(-2)" +
                    "+('0'+(+m[2]).toString(16)).slice(-2);" +
                    "return null;}" +
                    "var hex=toHex(v);" +
                    "if(hex&&meta)meta.content=hex;" +
                    "return hex;"
    }

    fun applyStatusBarColor(hex: String) {
        val color = Color.parseColor(hex)
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0
        val luminance = 0.299 * r + 0.587 * g + 0.114 * b
        WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            .isAppearanceLightStatusBars = luminance > 0.5
        if (Build.VERSION.SDK_INT >= 35) {
            activity.findViewById<FrameLayout>(R.id.rootLayout).setBackgroundColor(color)
        } else {
            @Suppress("DEPRECATION")
            activity.window.statusBarColor = color
        }
    }

    fun onUpdateStatusBarFromWeb(hex: String) {
        if (hex.startsWith("#")) applyStatusBarColor(hex)
    }

    fun refreshThemeColorAndStatusBar(webView: WebView?) {
        webView?.postDelayed({
            if (activity.isDestroyed || activity.isFinishing) return@postDelayed
            readAndApplyThemeColor(webView)
        }, 500)
    }

    fun injectColorSchemeListener(webView: WebView?) {
        webView?.evaluateJavascript(
            "(function(){" +
                    "if(window._themeListenerAttached)return;" +
                    "window._themeListenerAttached=true;" +
                    "window.matchMedia('(prefers-color-scheme:dark)').addEventListener('change',function(){" +
                    "setTimeout(function(){" +
                    "var s=getComputedStyle(document.documentElement);" +
                    "var meta=document.querySelector('meta[name=\"theme-color\"]');" +
                    "var path=location.pathname||'';" +
                    "var cssVar;" +
                    "if(path.indexOf('login')!==-1)cssVar='--bg';" +
                    "else if(path.indexOf('permission')!==-1||path.indexOf('privacy')!==-1)cssVar='--header-bg';" +
                    "else cssVar='--bg-surface';" +
                    "var v=s.getPropertyValue(cssVar).trim();" +
                    "if(!v)return;" +
                    "function toHex(c){" +
                    "if(c.startsWith('#'))return c;" +
                    "var m=c.match(/(\\d+)/g);" +
                    "if(m&&m.length>=3)return'#'+('0'+(+m[0]).toString(16)).slice(-2)" +
                    "+('0'+(+m[1]).toString(16)).slice(-2)" +
                    "+('0'+(+m[2]).toString(16)).slice(-2);" +
                    "return null;}" +
                    "var hex=toHex(v);" +
                    "if(hex&&meta)meta.content=hex;" +
                    "if(hex&&window.${WebAppBridge.NAME})window.${WebAppBridge.NAME}.updateStatusBar(hex);" +
                    "},300);" +
                    "});})()", null
        )
    }

    fun updateStatusBarColor(webView: WebView?) {
        webView?.evaluateJavascript(
            "(function(){" +
                    "var metas=document.querySelectorAll('meta[name=\"theme-color\"]');" +
                    "if(metas.length>1){" +
                    "for(var i=0;i<metas.length;i++){" +
                    "var media=metas[i].getAttribute('media');" +
                    "if(media&&window.matchMedia(media).matches)return metas[i].content;" +
                    "}" +
                    "}" +
                    "if(metas.length>0)return metas[0].content;" +
                    "var h=document.querySelector('[class*=\"-header\"]');" +
                    "if(h){var bg=getComputedStyle(h).backgroundColor;" +
                    "var m=bg.match(/(\\d+)/g);" +
                    "if(m&&m.length>=3){" +
                    "return'#'+('0'+(+m[0]).toString(16)).slice(-2)" +
                    "+('0'+(+m[1]).toString(16)).slice(-2)" +
                    "+('0'+(+m[2]).toString(16)).slice(-2);}}" +
                    "return null;})()"
        ) { value ->
            Log.d(TAG, "updateStatusBarColor raw=$value")
            applyStatusBarFromJsValue(value)
        }
    }

    private fun readAndApplyThemeColor(webView: WebView?) {
        webView?.evaluateJavascript("(function(){$READ_THEME_COLOR_JS})()") { value ->
            Log.d(TAG, "readThemeColor raw=$value")
            applyStatusBarFromJsValue(value)
        }
    }

    private fun applyStatusBarFromJsValue(value: String?) {
        if (value == null || value == "null") return
        try {
            val hex = value.trim('"').trim()
            if (hex.startsWith("#")) {
                Log.d(TAG, "applyStatusBar hex=$hex")
                applyStatusBarColor(hex)
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyStatusBar error: $e")
        }
    }
}
