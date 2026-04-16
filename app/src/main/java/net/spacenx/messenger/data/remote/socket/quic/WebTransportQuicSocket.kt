package net.spacenx.messenger.data.remote.socket.quic

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.spacenx.messenger.data.remote.socket.BinarySocketEventListener
import net.spacenx.messenger.data.remote.socket.ConnectionConfig
import net.spacenx.messenger.data.remote.socket.NeoSocketBase
import net.spacenx.messenger.data.remote.socket.codec.BinaryFrameCodec
import net.spacenx.messenger.data.remote.socket.codec.ProtocolCommand
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * NEO Binary Protocol over WebTransport (QUIC/HTTP3).
 *
 * ## 왜 WebTransport인가?
 * 서버(quiche/Rust)가 QUIC RETRY 패킷으로 주소 검증을 요구함.
 * kwik(Java)은 RETRY 응답 미지원 → 10초 타임아웃.
 * Android WebView의 Chromium(quiche)은 RETRY를 올바르게 처리 → 연결 성공.
 *
 * ## 연결 경로
 * ```
 * WebView(Chromium) → TCP HTTPS:18029 (Alt-Svc 수신)
 *   → QUIC UDP:18029 (RETRY 처리 포함)
 *   → HTTP3 WebTransport CONNECT
 *   → GatewayNeoRelayHandler → Bridge2 TCP (내부)
 * ```
 *
 * ## 프레임 브릿지
 * Kotlin ↔ JavaScript: Base64 인코딩 이진 프레임.
 * [BinaryFrameCodec] 포맷 동일 (8-byte header + UTF-8 JSON body).
 *
 * ## 요구 사항
 * Android 12+ (API 31) / WebView 96+ — WebTransport JS API 지원 시점.
 * 미지원 단말은 [isSupported] == false → TCP 폴백 권장.
 */
class WebTransportQuicSocket(
    private val context: Context,
    private val config: ConnectionConfig,
    private val listener: BinarySocketEventListener,
    private val noopBodyProvider: (() -> ByteArray)? = null
) : NeoSocketBase {

    companion object {
        private const val TAG = "WtQuicSocket"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val KEEPALIVE_INTERVAL_MS = 40_000L

        /**
         * WebTransport JS API 지원 여부.
         * Chrome/WebView 96 = Android 12(API 31) 기본 탑재.
         * API 29~30 단말도 WebView 업데이트로 지원 가능하나 보장 안 됨 → 31로 보수적 설정.
         */
        val isSupported: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }

    private var webView: WebView? = null
    private var clientScope: CoroutineScope? = null
    private val accumulator = ByteArrayOutputStream()
    private val invokeIdCounter = AtomicInteger(0)

    @Volatile private var connected = false
    @Volatile private var connectDeferred: CompletableDeferred<Unit>? = null

    override fun nextInvokeId(): Int = invokeIdCounter.incrementAndGet() and 0xFFFFFF

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override suspend fun connect() {
        Log.d(TAG, "connect() → WebTransport ${config.host}:${config.port}")
        val deferred = CompletableDeferred<Unit>()
        connectDeferred = deferred

        withContext(Dispatchers.Main) {
            val wv = WebView(context)
            webView = wv
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            // _NeoWs: Kotlin ← JS 브릿지
            wv.addJavascriptInterface(NativeInterface(), "_NeoWs")

            val url = "https://${config.host}:${config.port}/"
            wv.webViewClient = object : WebViewClient() {
                private var jsInjected = false

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    // 실제 HTTP 요청 완료 → 서버의 alt-svc: h3=":18029" 수신됨
                    // Chrome이 HTTP/3 캐시 → WebTransport JS 주입 시 HTTP/3 직접 사용
                    if (!jsInjected) {
                        jsInjected = true
                        Log.d(TAG, "Page loaded ($loadedUrl), injecting WebTransport JS")
                        view?.evaluateJavascript(buildJs(url), null)
                    }
                }

                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                ) {
                    // 404 등 HTTP 에러도 onPageFinished 호출됨 → JS 주입 정상 진행
                    // 메인 프레임 네트워크 단절(DNS 실패 등)만 문제
                    if (request?.isForMainFrame == true) {
                        Log.w(TAG, "Main frame error: ${error?.description}")
                    }
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: android.webkit.SslErrorHandler?,
                    error: android.net.http.SslError?
                ) {
                    // 개발 환경 self-signed cert 허용
                    Log.w(TAG, "SSL error: ${error?.primaryError} — proceeding")
                    handler?.proceed()
                }
            }

            // 실제 HTTP 요청 → 서버 응답 헤더 alt-svc: h3=":18029" Chrome 캐시
            wv.loadUrl(url)
        }

        try {
            withTimeout(CONNECT_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            Log.e(TAG, "connect() timeout/failed: ${e.message}")
            cleanup()
            throw e
        }

        connected = true
        clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        clientScope?.launch { startKeepAlive() }
        listener.onConnected()
        Log.d(TAG, "★ WebTransport connected")
    }

    override fun sendFrame(commandCode: Int, jsonBody: ByteArray, invokeId: Int): Int {
        val frame = BinaryFrameCodec.encode(commandCode, invokeId, jsonBody)
        val b64 = Base64.encodeToString(frame, Base64.NO_WRAP)
        webView?.post {
            webView?.evaluateJavascript(
                "window._neoWt && window._neoWt.send('$b64')", null
            )
        }
        return invokeId
    }

    override fun disconnect() {
        cleanup()
        listener.onDisconnected()
    }

    override fun disconnectSilently() = cleanup()

    // ── 내부 ──────────────────────────────────────────────────

    private fun cleanup() {
        connected = false
        clientScope?.cancel()
        clientScope = null
        accumulator.reset()
        val wv = webView
        webView = null
        wv?.post {
            wv.evaluateJavascript("window._neoWt && window._neoWt.close()", null)
            wv.destroy()
        }
    }

    private suspend fun startKeepAlive() {
        try {
            while (connected && clientScope?.isActive == true) {
                delay(KEEPALIVE_INTERVAL_MS)
                if (connected) {
                    val body = noopBodyProvider?.invoke() ?: ByteArray(0)
                    sendFrame(ProtocolCommand.NOOP.code, body)
                    Log.d(TAG, "KeepAlive NOOP sent")
                }
            }
        } catch (_: CancellationException) {
            Log.d(TAG, "KeepAlive cancelled")
        }
    }

    /** accumulator에서 완성된 NEO 프레임 추출 → listener 전달 */
    private fun tryDrainFrames() {
        while (true) {
            val all = accumulator.toByteArray()
            if (all.size < BinaryFrameCodec.HEADER_SIZE) return
            val bodyLen = BinaryFrameCodec.readUint24(all, 2)
            val total = BinaryFrameCodec.HEADER_SIZE + bodyLen
            if (all.size < total) return
            val frame = BinaryFrameCodec.decode(all.copyOfRange(0, total))
            accumulator.reset()
            if (all.size > total) accumulator.write(all, total, all.size - total)
            try {
                listener.onFrameReceived(frame)
            } catch (e: Exception) {
                Log.w(TAG, "onFrameReceived error: ${e.message}")
            }
        }
    }

    // ── JS → Kotlin 브릿지 ───────────────────────────────────

    inner class NativeInterface {
        /** WebTransport bidi stream 준비 완료 */
        @JavascriptInterface
        fun onConnected() {
            Log.d(TAG, "[JS] WebTransport bidi stream ready")
            connectDeferred?.complete(Unit)
        }

        /** 서버에서 수신한 바이너리 프레임 (Base64) */
        @JavascriptInterface
        fun onData(base64: String) {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            synchronized(accumulator) {
                accumulator.write(bytes)
            }
            tryDrainFrames()
        }

        /** 서버/스트림 종료 */
        @JavascriptInterface
        fun onDisconnected() {
            Log.w(TAG, "[JS] WebTransport disconnected")
            if (connected) {
                connected = false
                cleanup()
                listener.onDisconnected()
            }
        }

        /** WebTransport 오류 */
        @JavascriptInterface
        fun onError(msg: String) {
            Log.e(TAG, "[JS] WebTransport error: $msg")
            if (connectDeferred?.isActive == true) {
                connectDeferred?.completeExceptionally(Exception(msg))
            } else if (connected) {
                connected = false
                cleanup()
                listener.onError(Exception(msg))
            }
        }
    }

    // ── HTML / JavaScript ────────────────────────────────────

    /**
     * WebView에 주입할 WebTransport 클라이언트 JS.
     *
     * onPageFinished 후 evaluateJavascript로 실행됨.
     * 이 시점에 Chrome은 서버 응답의 alt-svc: h3=":port" 를 캐시했으므로
     * WebTransport CONNECT가 HTTP/3(QUIC)으로 직접 연결됨.
     *
     * - bidi stream 1개로 NEO 바이너리 프레임 전이중 교환
     * - 수신: Uint8Array → btoa → _NeoWs.onData (Kotlin)
     * - 송신: _neoWt.send(base64) → atob → WritableStream
     */
    private fun buildJs(url: String): String = """
(async function() {
  var ns = window._NeoWs;
  if (!ns) { return; }
  try {
    if (typeof WebTransport === 'undefined') {
      ns.onError('WebTransport not supported');
      return;
    }
    var wt = new WebTransport('$url');
    wt.closed.catch(function(e) {
      if (window._neoWtAlive) { window._neoWtAlive = false; ns.onDisconnected(); }
    });
    await wt.ready;

    var bidi = await wt.createBidirectionalStream();
    var writer = bidi.writable.getWriter();
    var reader = bidi.readable.getReader();

    window._neoWtAlive = true;
    window._neoWt = {
      send: function(b64) {
        var s = atob(b64);
        var arr = new Uint8Array(s.length);
        for (var i = 0; i < s.length; i++) arr[i] = s.charCodeAt(i);
        writer.write(arr).catch(function(e) { ns.onError('send: ' + e); });
      },
      close: function() {
        window._neoWtAlive = false;
        try { wt.close(); } catch(e) {}
      }
    };
    ns.onConnected();

    for (;;) {
      var res = await reader.read();
      if (res.done) {
        if (window._neoWtAlive) { window._neoWtAlive = false; ns.onDisconnected(); }
        break;
      }
      if (!res.value || !res.value.byteLength) continue;
      var v = new Uint8Array(res.value.buffer || res.value);
      var bin = '';
      for (var i = 0; i < v.byteLength; i++) bin += String.fromCharCode(v[i]);
      ns.onData(btoa(bin));
    }
  } catch(e) {
    ns.onError(String(e));
  }
})();
""".trimIndent()
}
