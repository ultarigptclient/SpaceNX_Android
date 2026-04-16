package net.spacenx.messenger.common

/**
 * 프로세스 전역 앱 상태 (포그라운드 여부, 현재 열린 채널).
 * MainActivity와 BridgeDispatcher가 갱신하고, FCM Service 등 Activity 외부에서 읽는다.
 */
object AppState {
    @Volatile var isForeground: Boolean = false
    @Volatile var activeChannelCode: String? = null
}
