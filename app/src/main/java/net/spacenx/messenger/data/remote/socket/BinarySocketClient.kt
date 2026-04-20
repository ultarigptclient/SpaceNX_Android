package net.spacenx.messenger.data.remote.socket

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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import net.spacenx.messenger.data.remote.socket.codec.BinaryFrameCodec
import net.spacenx.messenger.data.remote.socket.codec.ProtocolCommand
import net.spacenx.messenger.util.FileLogger
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.security.MessageDigest //2026-04-20 TLS Pinning, SPKI hash
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException
import net.spacenx.messenger.BuildConfig //2026-04-20 TLS Pinning, SPKI hash
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Binary Protocol 소켓 클라이언트 (WebSocket over TLS)
 *
 * TLS 위에 WebSocket HTTP Upgrade를 수행한 뒤 raw bytes를 직접 처리.
 * OkHttp text frame UTF-8 디코딩 우회 → bytes ≥ 0x80 손상 없음.
 *
 * 흐름:
 *   TCP → TLS handshake → HTTP Upgrade (101) → text frame "WhoAU?\n"
 *   → binary frame HI → ... binary frame 양방향 통신
 */
class BinarySocketClient(
    private val config: ConnectionConfig,
    private val listener: BinarySocketEventListener,
    private val noopBodyProvider: (() -> ByteArray)? = null
) : NeoSocketBase {

    companion object {
        private const val TAG = "BinarySocketClient"
        private const val READ_TIMEOUT_SEC = 42L
        private const val KEEPALIVE_INTERVAL_MS = 40_000L

        //2026-04-20 TLS Pinning, SPKI hash
        //최종 apk 릴리즈시 확인 필수!
        private const val SPKI_PINNING_ENABLED = true // false 로 바꾸면 전체 비활성화
        private val SPKI_PINS = setOf(
            "iFvwVyJSxnQdyaUvUERIf+8qk7gRze3612JMwoO3zdU=", // Let's Encrypt E8 Intermediate (exp 2027-03)
            "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M="  // ISRG Root X1 (exp 2035-06)
        )
    }

    // SSL
    private lateinit var sslEngine: SSLEngine
    private val sslWrapMutex = Mutex()

    // NIO
    private var socketChannel: AsynchronousSocketChannel? = null
    private var clientScope: CoroutineScope? = null
    private val sendChannel = Channel<ByteArray>(capacity = 1024, onBufferOverflow = BufferOverflow.SUSPEND)

    // WebSocket 수신 누산기 (raw bytes)
    private val wsAccumulator = ByteArrayOutputStream()

    // WebSocket 프레임 단편화(fragmentation) 조립 버퍼
    private val wsFragmentBuffer = ByteArrayOutputStream()

    // SSL 버퍼
    private lateinit var myAppData: ByteBuffer
    private lateinit var myNetData: ByteBuffer
    private lateinit var peerAppData: ByteBuffer
    private lateinit var peerNetData: ByteBuffer

    private val invokeIdCounter = AtomicInteger(0)

    @Volatile private var connected = false

    override fun nextInvokeId(): Int = invokeIdCounter.incrementAndGet() and 0xFFFFFF

    // ── 연결 진입점 ──

    override suspend fun connect() {
        initSSL()
        openChannel()
        performHandshake()
        if (!BuildConfig.DEBUG && SPKI_PINNING_ENABLED) verifySPKIPin() //2026-04-20 TLS Pinning, SPKI hash
        sendWebSocketUpgrade()
        awaitWebSocketUpgrade()
        awaitWhoAU()

        connected = true
        clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        clientScope?.launch { startReadLoop() }
        clientScope?.launch { startSendLoop() }
        clientScope?.launch { startKeepAlive() }
        listener.onConnected()
    }

    override fun sendFrame(commandCode: Int, jsonBody: ByteArray, invokeId: Int): Int {
        val payload = BinaryFrameCodec.encode(commandCode, invokeId, jsonBody)
        val wsFrame = wrapWsFrame(payload, opcode = 0x02) // binary frame
        val result = sendChannel.trySend(wsFrame)
        if (result.isFailure) {
            Log.w(TAG, "sendFrame dropped (channel full): cmd=$commandCode")
        }
        return invokeId
    }

    override fun disconnect() {
        cleanup()
        listener.onDisconnected()
    }

    override fun disconnectSilently() {
        cleanup()
    }

    private fun cleanup() {
        connected = false
        clientScope?.cancel()
        clientScope = null
        try { if (::sslEngine.isInitialized) sslEngine.closeOutbound() } catch (_: Exception) {}
        try { socketChannel?.close() } catch (_: Exception) {}
        socketChannel = null
        wsAccumulator.reset()
        wsFragmentBuffer.reset()
    }

    // ── SSL ──

    private fun initSSL() {
        val sslContext = net.spacenx.messenger.data.remote.api.ApiClient.sslContext
        sslEngine = sslContext.createSSLEngine(config.host, config.port).apply {
            useClientMode = true
            enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
        }
        val session = sslEngine.session
        myAppData = ByteBuffer.allocate(session.applicationBufferSize)
        myNetData = ByteBuffer.allocate(session.packetBufferSize)
        peerAppData = ByteBuffer.allocate(session.applicationBufferSize)
        peerNetData = ByteBuffer.allocate(session.packetBufferSize)
    }

    //2026-04-20 TLS Pinning, SPKI hash
    private fun verifySPKIPin() {
        val certs = sslEngine.session.peerCertificates
        for (cert in certs) {
            val hash = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
            )
            if (hash in SPKI_PINS) return
        }
        throw SSLException("TLS pinning failed: no matching SPKI pin for ${config.host}")
    }

    // ── TCP 연결 ──

    private suspend fun openChannel() {
        socketChannel = AsynchronousSocketChannel.open()
        suspendCancellableCoroutine { cont ->
            socketChannel!!.connect(InetSocketAddress(config.host, config.port), null,
                object : CompletionHandler<Void?, Void?> {
                    override fun completed(r: Void?, a: Void?) = cont.resume(Unit)
                    override fun failed(e: Throwable, a: Void?) = cont.resumeWithException(e)
                })
            cont.invokeOnCancellation { socketChannel?.close() }
        }
        Log.d(TAG, "TCP connected to ${config.host}:${config.port}")
    }

    // ── TLS 핸드셰이크 ──

    private suspend fun performHandshake() {
        sslEngine.beginHandshake()
        var hs = sslEngine.handshakeStatus
        while (hs != SSLEngineResult.HandshakeStatus.FINISHED
            && hs != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            when (hs) {
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    readFromChannelBlocking()
                    peerNetData.flip()
                    do {
                        peerAppData.clear()
                        val r = sslEngine.unwrap(peerNetData, peerAppData)
                        hs = r.handshakeStatus
                    } while (r.status == SSLEngineResult.Status.OK
                        && hs == SSLEngineResult.HandshakeStatus.NEED_UNWRAP)
                    peerNetData.compact()
                }
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    myNetData.clear(); myAppData.clear(); myAppData.flip()
                    val r = sslEngine.wrap(myAppData, myNetData)
                    hs = r.handshakeStatus
                    myNetData.flip()
                    writeToChannelBlocking(myNetData)
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    var task = sslEngine.delegatedTask
                    while (task != null) { task.run(); task = sslEngine.delegatedTask }
                    hs = sslEngine.handshakeStatus
                }
                else -> throw SSLException("Invalid handshake status: $hs")
            }
        }
        Log.d(TAG, "TLS OK: ${sslEngine.session.protocol} ${sslEngine.session.cipherSuite}")
    }

    // ── WebSocket HTTP Upgrade ──

    private suspend fun sendWebSocketUpgrade() {
        val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = Base64.getEncoder().encodeToString(keyBytes)
        val req = "GET / HTTP/1.1\r\n" +
            "Host: ${config.host}:${config.port}\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n\r\n"
        sslSend(req.toByteArray(Charsets.US_ASCII))
        Log.i(TAG, "[NeoHS 1/4] WebSocket Upgrade 전송")
        FileLogger.log(TAG, "[NeoHS 1/4] WebSocket Upgrade 전송 → ${config.host}:${config.port}")
    }

    private suspend fun awaitWebSocketUpgrade() {
        val acc = ByteArrayOutputStream()
        withTimeout(10_000L) {
            while (true) {
                val chunk = sslRead() ?: throw IOException("Channel closed during WS upgrade")
                acc.write(chunk)
                val text = acc.toString(Charsets.US_ASCII.name())
                val sep = text.indexOf("\r\n\r\n")
                if (sep >= 0) {
                    if (!text.startsWith("HTTP/1.1 101")) {
                        throw IOException("WS upgrade failed: ${text.substringBefore('\r')}")
                    }
                    // 헤더 이후 남은 바이트는 wsAccumulator에 보관
                    val extra = acc.toByteArray().drop(sep + 4).toByteArray()
                    if (extra.isNotEmpty()) wsAccumulator.write(extra)
                    Log.i(TAG, "[NeoHS 1/4] WebSocket 101 수신 OK")
                    FileLogger.log(TAG, "[NeoHS 1/4] WebSocket 101 수신 OK")
                    return@withTimeout
                }
            }
        }
    }

    // ── WhoAU? 수신 ──

    private suspend fun awaitWhoAU() {
        withTimeout(10_000L) {
            while (true) {
                val frame = readNextWsFrame() ?: continue
                val text = frame.toString(Charsets.UTF_8)
                if (text.contains("WhoAU?")) {
                    Log.i(TAG, "[NeoHS 2/4] WhoAU? 수신 (raw bytes) → onConnected() 예정")
                    FileLogger.log(TAG, "[NeoHS 2/4] WhoAU? 수신 → onConnected() 예정")
                    return@withTimeout
                }
            }
        }
    }

    // ── 읽기 루프 ──

    private suspend fun startReadLoop() {
        try {
            while (connected && clientScope?.isActive == true) {
                val payload = readNextWsFrame() ?: break
                if (payload.isEmpty()) continue
                try {
                    val frame = BinaryFrameCodec.decode(payload)
                    Log.d(TAG, "Frame received: $frame")
                    val cmdName = net.spacenx.messenger.data.remote.socket.codec.ProtocolCommand.fromCode(frame.commandCode)?.protocol
                        ?: "0x${frame.commandCode.toString(16)}"
                    if (frame.commandCode != net.spacenx.messenger.data.remote.socket.codec.ProtocolCommand.NOOP.code) {
                        FileLogger.log(TAG, "Frame recv: cmd=$cmdName invokeId=${frame.invokeId} size=${payload.size}B")
                    }
                    listener.onFrameReceived(frame)
                } catch (e: Exception) {
                    if (payload.isNotEmpty() && payload[0] == '{'.code.toByte()) {
                        val json = String(payload, Charsets.UTF_8)
                        Log.d(TAG, "Raw JSON frame received (${payload.size}B): $json")
                        FileLogger.log(TAG, "Raw JSON frame recv: ${json.take(200)}")
                        listener.onRawJsonFrame(json)
                    } else {
                        Log.e(TAG, "dispatchFrame error: ${e.message} (${payload.size}B)")
                        FileLogger.log(TAG, "dispatchFrame error: ${e.message} (${payload.size}B)")
                    }
                }
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Read loop cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Read loop error: ${e.message}")
            if (connected) listener.onError(e)
        }
        if (connected) { connected = false; listener.onDisconnected() }
    }

    /**
     * wsAccumulator에서 완전한 WS 프레임 하나의 payload를 추출.
     * 없으면 소켓에서 더 읽어와서 재시도.
     */
    private suspend fun readNextWsFrame(): ByteArray? {
        while (true) {
            val payload = extractWsFrame()
            if (payload != null) return payload
            // 더 읽기
            val chunk = sslRead() ?: return null
            wsAccumulator.write(chunk)
        }
    }

    /**
     * wsAccumulator에서 완전한 WS 프레임 payload 추출. 부족하면 null.
     * 제어 프레임(ping/close)은 처리 후 null 반환.
     */
    private suspend fun extractWsFrame(): ByteArray? {
        val buf = wsAccumulator.toByteArray()
        if (buf.size < 2) return null

        val opcode = buf[0].toInt() and 0x0F
        val masked = (buf[1].toInt() and 0x80) != 0
        var payloadLen = (buf[1].toInt() and 0x7F).toLong()
        var offset = 2

        when {
            payloadLen == 126L -> {
                if (buf.size < 4) return null
                payloadLen = ((buf[2].toInt() and 0xFF shl 8) or (buf[3].toInt() and 0xFF)).toLong()
                offset = 4
            }
            payloadLen == 127L -> {
                if (buf.size < 10) return null
                payloadLen = 0
                for (i in 2..9) payloadLen = (payloadLen shl 8) or (buf[i].toInt() and 0xFF).toLong()
                offset = 10
            }
        }
        if (masked) offset += 4
        val totalLen = offset + payloadLen.toInt()
        if (buf.size < totalLen) return null

        // accumulator 에서 이 프레임 제거
        wsAccumulator.reset()
        if (buf.size > totalLen) wsAccumulator.write(buf, totalLen, buf.size - totalLen)

        val payload = if (masked) {
            val key = buf.copyOfRange(offset - 4, offset)
            ByteArray(payloadLen.toInt()) { (buf[offset + it].toInt() xor key[it % 4].toInt()).toByte() }
        } else {
            buf.copyOfRange(offset, offset + payloadLen.toInt())
        }

        val fin = (buf[0].toInt() and 0x80) != 0

        // 제어 프레임 처리
        return when (opcode) {
            0x08 -> { Log.d(TAG, "WS close frame"); null }  // close
            0x09 -> { sendWsPong(payload); null }            // ping → pong
            0x0A -> null                                      // pong
            0x00 -> {
                // continuation frame: 조각 누산
                wsFragmentBuffer.write(payload)
                if (fin) {
                    val complete = wsFragmentBuffer.toByteArray()
                    wsFragmentBuffer.reset()
                    complete
                } else null
            }
            else -> {
                if (fin) {
                    // 단일 완전 프레임
                    payload
                } else {
                    // 첫 번째 조각: 버퍼 시작
                    wsFragmentBuffer.reset()
                    wsFragmentBuffer.write(payload)
                    null
                }
            }
        }
    }

    // ── 전송 루프 ──

    private suspend fun startSendLoop() {
        try {
            for (wsFrame in sendChannel) {
                if (!connected) break
                sslSend(wsFrame)
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Send loop cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Send loop error: ${e.message}")
            if (connected) listener.onError(e)
        }
    }

    // ── Keep-alive ──

    private suspend fun startKeepAlive() {
        try {
            while (connected && clientScope?.isActive == true) {
                delay(KEEPALIVE_INTERVAL_MS)
                if (connected) {
                    val body = noopBodyProvider?.invoke() ?: ByteArray(0)
                    Log.d(TAG, "KeepAlive NOOP 전송 (bodySize=${body.size})")
                    sendFrame(ProtocolCommand.NOOP.code, body)
                }
            }
        } catch (_: CancellationException) {}
    }

    // ── WebSocket 프레임 래핑 (클라이언트→서버, 마스킹 필수) ──

    private fun wrapWsFrame(payload: ByteArray, opcode: Int): ByteArray {
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val masked = ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
        val out = ByteArrayOutputStream(6 + payload.size)

        out.write(0x80 or opcode) // FIN=1
        when {
            payload.size < 126 -> { out.write(0x80 or payload.size) }
            payload.size < 65536 -> {
                out.write(0x80 or 126)
                out.write(payload.size shr 8); out.write(payload.size and 0xFF)
            }
            else -> {
                out.write(0x80 or 127)
                for (i in 7 downTo 0) out.write((payload.size shr (i * 8)) and 0xFF)
            }
        }
        out.write(mask)
        out.write(masked)
        return out.toByteArray()
    }

    private suspend fun sendWsPong(payload: ByteArray) {
        val frame = wrapWsFrame(payload, opcode = 0x0A)
        sslSend(frame)
    }

    // ── SSL 송수신 헬퍼 ──

    private suspend fun sslRead(): ByteArray? {
        val bytesRead = readFromChannelAsync()
        if (bytesRead < 0) return null
        if (bytesRead == 0) return ByteArray(0)

        val out = ByteArrayOutputStream()
        peerNetData.flip()
        while (peerNetData.hasRemaining()) {
            peerAppData.clear()
            val result = sslEngine.unwrap(peerNetData, peerAppData)
            if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) break
            if (result.status != SSLEngineResult.Status.OK) break
            peerAppData.flip()
            if (peerAppData.hasRemaining()) {
                val bytes = ByteArray(peerAppData.remaining())
                peerAppData.get(bytes)
                out.write(bytes)
            }
        }
        peerNetData.compact()
        return out.toByteArray()
    }

    private suspend fun sslSend(data: ByteArray) {
        sslWrapMutex.withLock {
            val sendBuffer = ByteBuffer.wrap(data)
            while (sendBuffer.hasRemaining()) {
                myNetData.clear()
                val result = sslEngine.wrap(sendBuffer, myNetData)
                if (result.status != SSLEngineResult.Status.OK
                    && result.status != SSLEngineResult.Status.BUFFER_OVERFLOW) {
                    throw SSLException("SSL wrap failed: ${result.status}")
                }
                myNetData.flip()
                writeToChannelBlocking(myNetData)
            }
        }
    }

    // ── NIO I/O ──

    private fun readFromChannelBlocking(): Int =
        socketChannel!!.read(peerNetData).get(READ_TIMEOUT_SEC, TimeUnit.SECONDS)

    private suspend fun readFromChannelAsync(): Int =
        suspendCancellableCoroutine { cont ->
            socketChannel!!.read(peerNetData, READ_TIMEOUT_SEC, TimeUnit.SECONDS, null,
                object : CompletionHandler<Int, Void?> {
                    override fun completed(r: Int, a: Void?) = cont.resume(r)
                    override fun failed(e: Throwable, a: Void?) = cont.resumeWithException(e)
                })
        }

    private fun writeToChannelBlocking(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) socketChannel!!.write(buffer).get()
    }
}
