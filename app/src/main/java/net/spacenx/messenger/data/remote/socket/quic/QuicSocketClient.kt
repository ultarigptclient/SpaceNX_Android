package net.spacenx.messenger.data.remote.socket.quic

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.spacenx.messenger.data.remote.socket.BinarySocketEventListener
import net.spacenx.messenger.data.remote.socket.ConnectionConfig
import net.spacenx.messenger.data.remote.socket.NeoSocketBase
import net.spacenx.messenger.data.remote.socket.codec.BinaryFrameCodec
import net.spacenx.messenger.data.remote.socket.codec.ProtocolCommand
import tech.kwik.core.QuicClientConnection
import tech.kwik.core.QuicStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * NEO Binary Protocol over QUIC (Flutter `NeoQuicSocket` 과 동일 wire format).
 *
 * Transport : raw QUIC bidirectional stream (kwik 0.10.x, IETF RFC 9000)
 * Wire      : 8-byte header + UTF-8 JSON body ([BinaryFrameCodec]) — TCP와 동일
 * Gateway   : :18029/UDP, ALPN "neo" → 서버 `QuicNeoProtocolHandler` 로 라우팅
 *             (`backend/at/app/gateway/net/config/GatewayQuicConfig.java:122`)
 *
 * 상위 코드(`SocketSessionManager`)는 [NeoSocketBase] 인터페이스만 참조하므로 TCP/QUIC 무관.
 * 활성화: `ConnectionConfig.transport = Transport.QUIC` (즉 `AppConfig.isQuicEnabled() == true`).
 *
 * ── 향후 옵션 A (Cronet + HTTP/3) 로 교체 시 ──
 *   본 파일만 Cronet `BidirectionalStream` 기반으로 재구현. 인터페이스·`SocketSessionManager` 손대지 않음.
 */
class QuicSocketClient(
    private val context: Context,
    private val config: ConnectionConfig,
    private val listener: BinarySocketEventListener,
    private val noopBodyProvider: (() -> ByteArray)? = null
) : NeoSocketBase {

    companion object {
        private const val TAG = "QuicSocketClient"
        /** 서버 `QuicAlpnRouter.register("neo", ...)` 매칭. default handler 도 neo 지만 명시가 안전. */
        private const val ALPN = "neo"
        private const val KEEPALIVE_INTERVAL_MS = 40_000L
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        private val IDLE_TIMEOUT: Duration = Duration.ofSeconds(30)
        private const val READ_BUFFER_SIZE = 16 * 1024
    }

    private var clientScope: CoroutineScope? = null
    // 재연결 폭주 시 OOM 방지 — bounded capacity + SUSPEND 백프레셔
    private val sendChannel = Channel<ByteArray>(capacity = 1024, onBufferOverflow = BufferOverflow.SUSPEND)
    private val accumulator = ByteArrayOutputStream()
    private val invokeIdCounter = AtomicInteger(0)

    @Volatile
    private var connected = false

    // kwik 핸들
    private var connection: QuicClientConnection? = null
    private var stream: QuicStream? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    override fun nextInvokeId(): Int = invokeIdCounter.incrementAndGet() and 0xFFFFFF

    override suspend fun connect() {
        Log.d(TAG, "connect() to ${config.host}:${config.port} (ALPN=$ALPN)")
        try {
            doConnect()
            awaitWhoAU()
            connected = true
            clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            clientScope?.launch { startReadLoop() }
            clientScope?.launch { startSendLoop() }
            clientScope?.launch { startKeepAlive() }
            listener.onConnected()
        } catch (e: Exception) {
            Log.e(TAG, "QUIC connect failed: ${e.message}", e)
            connected = false
            cleanup()
            listener.onError(e)
            throw e
        }
    }

    override fun sendFrame(commandCode: Int, jsonBody: ByteArray, invokeId: Int): Int {
        val frame = BinaryFrameCodec.encode(commandCode, invokeId, jsonBody)
        val result = sendChannel.trySend(frame)
        if (result.isFailure) {
            Log.w(TAG, "sendFrame dropped: channel full (cmd=$commandCode, invokeId=$invokeId)")
        }
        return invokeId
    }

    override fun disconnect() {
        Log.d(TAG, "disconnect() called")
        cleanup()
        listener.onDisconnected()
    }

    override fun disconnectSilently() {
        Log.d(TAG, "disconnectSilently() called")
        cleanup()
    }

    // ══════════════════════════════════════════════════════
    //  QUIC I/O
    // ══════════════════════════════════════════════════════

    /**
     * kwik `QuicClientConnection` 수립 → bidirectional stream 오픈.
     * Flutter `NeoQuicSocket._doConnect` 와 동일 시퀀스: endpointConnect → openBi.
     */
    private fun doConnect() {
        val uri = URI("https://${config.host}:${config.port}/")
        Log.d(TAG, "[QUIC-STEP 1/6] builder 시작, uri=$uri")
        val conn = try {
            QuicClientConnection.newBuilder()
                .uri(uri)
                .applicationProtocol(ALPN)
                .connectTimeout(CONNECT_TIMEOUT)
                .maxIdleTimeout(IDLE_TIMEOUT)
                // 개발용 self-signed 허용. 프로덕션 전 `customTrustManager(...)` 로 교체.
                .noServerCertificateCheck()
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "[QUIC-STEP 2/6] build() FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            throw e
        }
        Log.d(TAG, "[QUIC-STEP 2/6] build() OK — connection instance created")

        Log.d(TAG, "[QUIC-STEP 3/6] connect() 시작 (UDP 핸드셰이크, timeout=${CONNECT_TIMEOUT.seconds}s)...")
        try {
            conn.connect()
        } catch (e: Exception) {
            Log.e(TAG, "[QUIC-STEP 3/6] connect() FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            throw e
        }
        Log.d(TAG, "[QUIC-STEP 4/6] connect() OK — 핸드셰이크 완료, isConnected=${conn.isConnected}")
        try {
            Log.d(TAG, "[QUIC-STEP 4/6] localAddr=${conn.localAddress}, serverAddr=${conn.serverAddress}")
        } catch (_: Exception) {}

        val s = try {
            conn.createStream(true) // bidi
        } catch (e: Exception) {
            Log.e(TAG, "[QUIC-STEP 5/6] createStream FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            throw e
        }
        Log.d(TAG, "[QUIC-STEP 5/6] createStream OK, streamId=${s.streamId}")

        connection = conn
        stream = s
        outputStream = s.outputStream
        inputStream = s.inputStream
        Log.d(TAG, "[QUIC-STEP 6/6] ★★★ QUIC 전체 연결 완료 ★★★ streamId=${s.streamId}")
    }

    // ── WhoAU? welcome 핸드셰이크 수신 대기 ──

    private fun awaitWhoAU() {
        Log.i(TAG, "[NeoHS 1/4] QUIC 스트림 오픈 완료 → WhoAU? 대기 중 (timeout=10s)")
        val ins = inputStream ?: throw IllegalStateException("QUIC input stream not ready")
        val buf = ByteArray(64)
        val accumulated = java.io.ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + 10_000L

        while (System.currentTimeMillis() < deadline) {
            val n = ins.read(buf)
            if (n < 0) throw java.io.IOException("Stream closed while waiting for WhoAU?")
            if (n == 0) continue
            accumulated.write(buf, 0, n)
            val text = accumulated.toString(Charsets.UTF_8.name())
            if (text.contains("WhoAU?")) {
                Log.i(TAG, "[NeoHS 2/4] WhoAU? 수신 완료 → onConnected() 호출 (raw=\"${text.trim()}\")")
                return
            }
        }
        throw java.io.IOException("WhoAU? greeting not received within 10s")
    }

    private suspend fun startSendLoop() {
        val scope = clientScope ?: return
        try {
            while (scope.isActive) {
                val frame = sendChannel.receive()
                doSend(frame)
            }
        } catch (_: CancellationException) {
            Log.d(TAG, "Send loop cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Send loop error: ${e.message}", e)
            if (connected) listener.onError(e)
        }
    }

    /**
     * QuicStream.outputStream 은 flush 시점에 QUIC STREAM frame 을 즉시 푸시.
     * 채팅 지연 방지를 위해 프레임마다 flush.
     */
    private fun doSend(frame: ByteArray) {
        val os = outputStream ?: throw IllegalStateException("QUIC not connected")
        synchronized(os) {
            os.write(frame)
            os.flush()
        }
    }

    private suspend fun startReadLoop() {
        val scope = clientScope ?: return
        val buffer = ByteArray(READ_BUFFER_SIZE)
        try {
            while (scope.isActive && connected) {
                val n = doRead(buffer)
                if (n < 0) {
                    Log.d(TAG, "QUIC stream closed by server")
                    break
                }
                if (n == 0) continue
                accumulator.write(buffer, 0, n)
                tryDrainFrames()
            }
        } catch (_: CancellationException) {
            Log.d(TAG, "Read loop cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Read loop error: ${e.message}", e)
            if (connected) listener.onError(e)
        } finally {
            // 읽기 루프 종료 = 원격 끊김. cleanup 후 상위에 알림.
            if (connected) {
                cleanup()
                listener.onDisconnected()
            }
        }
    }

    private fun doRead(buffer: ByteArray): Int {
        val input = inputStream ?: return -1
        return input.read(buffer)
    }

    private suspend fun startKeepAlive() {
        Log.d(TAG, "KeepAlive started (${KEEPALIVE_INTERVAL_MS}ms)")
        try {
            while (connected && clientScope?.isActive == true) {
                delay(KEEPALIVE_INTERVAL_MS)
                if (connected) {
                    val body = noopBodyProvider?.invoke() ?: ByteArray(0)
                    Log.d(TAG, "KeepAlive sending NOOP (bodySize=${body.size})")
                    sendFrame(ProtocolCommand.NOOP.code, body)
                }
            }
        } catch (_: CancellationException) {
            Log.d(TAG, "KeepAlive cancelled")
        }
    }

    /** accumulator 에서 완성된 프레임을 순차 추출 → [listener.onFrameReceived] */
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
                Log.w(TAG, "listener.onFrameReceived threw: ${e.message}")
            }
        }
    }

    private fun cleanup() {
        connected = false
        try { clientScope?.cancel() } catch (_: Exception) {}
        clientScope = null
        try { outputStream?.close() } catch (_: Exception) {}
        try { inputStream?.close() } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        outputStream = null
        inputStream = null
        stream = null
        connection = null
        accumulator.reset()
    }
}
