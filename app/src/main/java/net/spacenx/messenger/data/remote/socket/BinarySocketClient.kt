package net.spacenx.messenger.data.remote.socket

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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.spacenx.messenger.data.remote.socket.codec.BinaryFrameCodec
import net.spacenx.messenger.data.remote.socket.codec.ProtocolCommand
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Binary Protocol 5.0 소켓 클라이언트
 *
 * TalkSocketClient와 동일한 SSL/TLS + NIO.2 기반이나:
 * - 송신: BinaryFrameCodec.encode() → SSL wrap → channel write (SEED 암호화 없음)
 * - 수신: SSL unwrap → 길이 기반 프레임 조립 (0x0C 구분자 아님) → BinaryFrameCodec.decode()
 * - 킵얼라이브: NOOP 바이너리 프레임
 */
class BinarySocketClient(
    private val context: Context,
    private val config: ConnectionConfig,
    private val listener: BinarySocketEventListener,
    private val noopBodyProvider: (() -> ByteArray)? = null
) : NeoSocketBase {
    companion object {
        private const val TAG = "BinarySocketClient"
        private const val READ_TIMEOUT_SEC = 42L
        private const val KEEPALIVE_INTERVAL_MS = 40_000L
    }

    // SSL
    private lateinit var sslEngine: SSLEngine
    private val sslWrapMutex = Mutex()

    // NIO
    private var socketChannel: AsynchronousSocketChannel? = null

    // Coroutine
    private var clientScope: CoroutineScope? = null
    // 재연결 폭주 시 OOM 방지 — bounded capacity + SUSPEND 백프레셔
    private val sendChannel = Channel<ByteArray>(capacity = 1024, onBufferOverflow = BufferOverflow.SUSPEND)

    // 바이너리 프레임 조립용 accumulator
    private val accumulator = ByteArrayOutputStream()

    // SSL 버퍼
    private lateinit var myAppData: ByteBuffer
    private lateinit var myNetData: ByteBuffer
    private lateinit var peerAppData: ByteBuffer
    private lateinit var peerNetData: ByteBuffer

    // invokeId 카운터 (요청/응답 매칭)
    private val invokeIdCounter = AtomicInteger(0)

    @Volatile
    private var connected = false

    /**
     * 다음 invokeId 발급
     */
    override fun nextInvokeId(): Int = invokeIdCounter.incrementAndGet() and 0xFFFFFF // uint24 범위

    /**
     * SSL 초기화 → TCP 연결 → 핸드셰이크 → 읽기/쓰기/킵얼라이브 루프 시작
     */
    override suspend fun connect() {
        initSSL()
        openChannel()
        performHandshake()

        connected = true
        clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        clientScope?.launch { startReadLoop() }
        clientScope?.launch { startSendLoop() }
        clientScope?.launch { startKeepAlive() }

        listener.onConnected()
    }

    /**
     * 바이너리 프레임 전송 (commandCode + invokeId + JSON body)
     * @return 사용된 invokeId
     */
    override fun sendFrame(commandCode: Int, jsonBody: ByteArray, invokeId: Int): Int {
        val frame = BinaryFrameCodec.encode(commandCode, invokeId, jsonBody)
        val result = sendChannel.trySend(frame)
        if (result.isFailure) {
            // bounded(1024) 가 꽉 참 — 소켓 stall 또는 폭증 상황. drop 후 로그.
            Log.w(TAG, "sendFrame dropped: channel full (cmd=$commandCode, invokeId=$invokeId)")
        }
        return invokeId
    }

    /**
     * 연결 종료 (listener에 onDisconnected 콜백 호출)
     */
    override fun disconnect() {
        Log.d(TAG, "disconnect() called")
        cleanup()
        listener.onDisconnected()
    }

    /**
     * 연결 종료 (listener 호출 없이 조용히 닫기)
     */
    override fun disconnectSilently() {
        Log.d(TAG, "disconnectSilently() called")
        cleanup()
    }

    private fun cleanup() {
        connected = false

        clientScope?.cancel()
        clientScope = null

        try {
            if (::sslEngine.isInitialized) {
                sslEngine.closeOutbound()
            }
        } catch (_: Exception) {}

        try {
            socketChannel?.close()
        } catch (_: Exception) {}
        socketChannel = null

        accumulator.reset()
    }

    // ── SSL 초기화 (ApiClient의 SSLContext 싱글톤 재사용) ──

    private fun initSSL() {
        Log.d(TAG, "Initializing SSL (shared SSLContext)")

        val sslContext = net.spacenx.messenger.data.remote.api.ApiClient.sslContext

        sslEngine = sslContext.createSSLEngine(config.host, config.port).apply {
            useClientMode = true
            enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
        }

        val session = sslEngine.session
        val appBufferSize = session.applicationBufferSize
        val packetBufferSize = session.packetBufferSize

        myAppData = ByteBuffer.allocate(appBufferSize)
        myNetData = ByteBuffer.allocate(packetBufferSize)
        peerAppData = ByteBuffer.allocate(appBufferSize)
        peerNetData = ByteBuffer.allocate(packetBufferSize)

        Log.d(TAG, "SSL initialized - appBuffer: $appBufferSize, packetBuffer: $packetBufferSize")
    }

    // ── TCP 연결 ──

    private suspend fun openChannel() {
        Log.d(TAG, "Connecting to ${config.host}:${config.port}")
        socketChannel = AsynchronousSocketChannel.open()

        suspendCancellableCoroutine { cont ->
            socketChannel!!.connect(
                InetSocketAddress(config.host, config.port),
                null,
                object : CompletionHandler<Void?, Void?> {
                    override fun completed(result: Void?, attachment: Void?) {
                        Log.d(TAG, "TCP connection established")
                        cont.resume(Unit)
                    }

                    override fun failed(exc: Throwable, attachment: Void?) {
                        Log.e(TAG, "TCP connection failed: ${exc.javaClass.simpleName}: ${exc.message}", exc)
                        cont.resumeWithException(exc)
                    }
                }
            )

            cont.invokeOnCancellation {
                try { socketChannel?.close() } catch (_: Exception) {}
            }
        }
    }

    // ── SSL 핸드셰이크 (TalkSocketClient와 동일) ──

    private suspend fun performHandshake() {
        Log.d(TAG, "Starting SSL handshake")
        sslEngine.beginHandshake()
        var hsStatus = sslEngine.handshakeStatus

        while (hsStatus != SSLEngineResult.HandshakeStatus.FINISHED
            && hsStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
        ) {
            when (hsStatus) {
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    val bytesRead = readFromChannelBlocking()
                    if (bytesRead < 0) throw SSLException("Channel closed during handshake")

                    // 디버그: 서버 응답의 첫 바이트 확인
                    peerNetData.flip()
                    if (peerNetData.remaining() > 0) {
                        val pos = peerNetData.position()
                        val first5 = ByteArray(minOf(5, peerNetData.remaining()))
                        peerNetData.get(first5)
                        peerNetData.position(pos)
                        Log.d(TAG, "Handshake raw bytes (${bytesRead}B): ${first5.joinToString(" ") { "0x%02X".format(it) }}")
                    }

                    var unwrapResult: SSLEngineResult
                    do {
                        peerAppData.clear()
                        unwrapResult = sslEngine.unwrap(peerNetData, peerAppData)
                        hsStatus = unwrapResult.handshakeStatus
                        Log.d(TAG, "Unwrap - Status: ${unwrapResult.status}, HS: $hsStatus")
                    } while (unwrapResult.status == SSLEngineResult.Status.OK
                        && hsStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP)

                    peerNetData.compact()
                }

                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    myNetData.clear()
                    myAppData.clear()
                    myAppData.flip()
                    val wrapResult = sslEngine.wrap(myAppData, myNetData)
                    hsStatus = wrapResult.handshakeStatus
                    Log.d(TAG, "Wrap - Status: ${wrapResult.status}, HS: $hsStatus")

                    myNetData.flip()
                    writeToChannelBlocking(myNetData)
                }

                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    var task = sslEngine.delegatedTask
                    while (task != null) {
                        task.run()
                        task = sslEngine.delegatedTask
                    }
                    hsStatus = sslEngine.handshakeStatus
                }

                else -> throw SSLException("Invalid handshake status: $hsStatus")
            }
        }

        val protocol = sslEngine.session.protocol
        val cipherSuite = sslEngine.session.cipherSuite
        Log.d(TAG, "SSL handshake completed - Protocol: $protocol, CipherSuite: $cipherSuite")
    }

    // ── 읽기/쓰기 루프 ──

    private suspend fun startReadLoop() {
        Log.d(TAG, "Read loop started")
        peerNetData.clear()

        try {
            while (connected && clientScope?.isActive == true) {
                val bytesRead = readFromChannelAsync()

                if (bytesRead < 0) {
                    Log.d(TAG, "Read loop: channel closed (bytesRead=$bytesRead)")
                    break
                }

                if (bytesRead > 0) {
                    peerNetData.flip()

                    while (peerNetData.hasRemaining()) {
                        peerAppData.clear()
                        val result = sslEngine.unwrap(peerNetData, peerAppData)

                        if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) break
                        if (result.status != SSLEngineResult.Status.OK) {
                            Log.w(TAG, "SSL unwrap failed: ${result.status}")
                            break
                        }

                        peerAppData.flip()
                        processReceivedBinaryData(peerAppData)
                    }

                    peerNetData.compact()
                }
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Read loop cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Read loop error: ${e.message}")
            if (connected) listener.onError(e)
        }

        if (connected) {
            connected = false
            listener.onDisconnected()
        }
    }

    private suspend fun startSendLoop() {
        Log.d(TAG, "Send loop started")
        try {
            for (frameData in sendChannel) {
                if (!connected) break
                doSend(frameData)
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Send loop cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Send loop error: ${e.message}")
            if (connected) listener.onError(e)
        }
    }

    private suspend fun startKeepAlive() {
        Log.d(TAG, "KeepAlive started (${KEEPALIVE_INTERVAL_MS}ms)")
        try {
            while (connected && clientScope?.isActive == true) {
                delay(KEEPALIVE_INTERVAL_MS)
                if (connected) {
                    val noopBody = noopBodyProvider?.invoke() ?: ByteArray(0)
                    Log.d(TAG, "KeepAlive sending NOOP frame (bodySize=${noopBody.size})")
                    sendFrame(ProtocolCommand.NOOP.code, noopBody)
                }
            }
        } catch (_: CancellationException) {
            Log.d(TAG, "KeepAlive cancelled")
        }
    }

    // ── 실제 전송 (SSL wrap → channel write, SEED 암호화 없음) ──

    private suspend fun doSend(frameData: ByteArray) {
        sslWrapMutex.withLock {
            try {
                val sendBuffer = ByteBuffer.wrap(frameData)
                Log.d(TAG, "doSend: ${frameData.size} bytes")

                while (sendBuffer.hasRemaining()) {
                    myNetData.clear()
                    val result = sslEngine.wrap(sendBuffer, myNetData)

                    if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        val newSize = sslEngine.session.packetBufferSize
                        if (myNetData.capacity() < newSize) {
                            myNetData = ByteBuffer.allocate(newSize)
                            continue
                        }
                    }

                    if (result.status != SSLEngineResult.Status.OK
                        && result.status != SSLEngineResult.Status.BUFFER_OVERFLOW
                    ) {
                        throw SSLException("SSL wrap failed: ${result.status}")
                    }

                    myNetData.flip()
                    writeToChannelBlocking(myNetData)
                }
            } catch (e: Exception) {
                Log.e(TAG, "doSend failed: ${e.message}")
                throw e
            }
        }
    }

    // ── 수신 데이터 처리 (길이 기반 바이너리 프레임 조립) ──

    private fun processReceivedBinaryData(buffer: ByteBuffer) {
        val remaining = buffer.remaining()
        if (remaining == 0) return

        // accumulator에 데이터 추가
        val bytes = ByteArray(remaining)
        buffer.get(bytes)
        accumulator.write(bytes)

        // 완전한 프레임 추출
        extractFrames()
    }

    private fun extractFrames() {
        while (true) {
            val data = accumulator.toByteArray()
            if (data.size < BinaryFrameCodec.HEADER_SIZE) break

            val bodyLength = BinaryFrameCodec.readUint24(data, 2)
            val totalSize = BinaryFrameCodec.HEADER_SIZE + bodyLength

            if (data.size < totalSize) break // 아직 불완전한 프레임

            // 완전한 프레임 추출
            val frameBytes = data.copyOfRange(0, totalSize)
            val frame = BinaryFrameCodec.decode(frameBytes)

            Log.d(TAG, "Frame received: $frame")
            listener.onFrameReceived(frame)

            // accumulator에서 처리된 프레임 제거
            accumulator.reset()
            if (data.size > totalSize) {
                accumulator.write(data, totalSize, data.size - totalSize)
            }
        }
    }

    // ── NIO 채널 I/O ──

    private fun readFromChannelBlocking(): Int {
        return socketChannel!!.read(peerNetData).get(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
    }

    private suspend fun readFromChannelAsync(): Int {
        return suspendCancellableCoroutine { cont ->
            socketChannel!!.read(
                peerNetData, READ_TIMEOUT_SEC, TimeUnit.SECONDS, null,
                object : CompletionHandler<Int, Void?> {
                    override fun completed(result: Int, attachment: Void?) {
                        cont.resume(result)
                    }

                    override fun failed(exc: Throwable, attachment: Void?) {
                        cont.resumeWithException(exc)
                    }
                }
            )
        }
    }

    private fun writeToChannelBlocking(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            socketChannel!!.write(buffer).get()
        }
    }
}
