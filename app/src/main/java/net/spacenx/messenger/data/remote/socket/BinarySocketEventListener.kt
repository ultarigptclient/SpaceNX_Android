package net.spacenx.messenger.data.remote.socket

import net.spacenx.messenger.data.remote.socket.codec.BinaryFrameCodec

/**
 * 바이너리 프로토콜 소켓 이벤트 콜백
 */
interface BinarySocketEventListener {
    fun onConnected()
    fun onFrameReceived(frame: BinaryFrameCodec.BinaryFrame)
    fun onDisconnected()
    fun onError(error: Throwable)
    /** 바이너리 프로토콜 헤더 없이 raw JSON을 직접 수신한 경우 (서버 알림 프레임) */
    fun onRawJsonFrame(json: String) {}
}
