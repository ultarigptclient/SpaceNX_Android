package net.spacenx.messenger.data.remote.socket

/**
 * NEO Binary Protocol 소켓 클라이언트 공통 인터페이스.
 * 구체 구현:
 *  - [BinarySocketClient] — WebSocket/TLS (기본, 포트 18020)
 *  - [net.spacenx.messenger.data.remote.socket.quic.QuicSocketClient] — QUIC (옵션, 포트 18029)
 *
 * 8-byte header + JSON body 프레임 포맷은 [net.spacenx.messenger.data.remote.socket.codec.BinaryFrameCodec] 공유.
 * HI/NOOP/REFRESH_TOKEN 등 상위 로직은 [net.spacenx.messenger.data.repository.SocketSessionManager] 에서 전송 무관하게 동작한다.
 */
interface NeoSocketBase {
    fun nextInvokeId(): Int
    suspend fun connect()
    fun sendFrame(commandCode: Int, jsonBody: ByteArray, invokeId: Int = nextInvokeId()): Int
    fun disconnect()
    fun disconnectSilently()
}

/** 전송 프로토콜 선택 */
enum class Transport { TCP, QUIC }
