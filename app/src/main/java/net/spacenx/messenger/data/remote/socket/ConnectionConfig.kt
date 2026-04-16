package net.spacenx.messenger.data.remote.socket

data class ConnectionConfig(
    val host: String,
    val port: Int,
    val userId: String,
    val password: String,
    //val encryptedUserId: String,
    //val encryptedPassword: String,
    val gmsToken: String,
    val uuid: String,
    /** 전송 프로토콜 — 기본 TCP. QUIC 선택 시 [QuicSocketClient]가 연결함 */
    val transport: Transport = Transport.TCP
)
