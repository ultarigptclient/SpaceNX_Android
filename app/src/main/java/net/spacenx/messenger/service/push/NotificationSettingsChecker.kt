package net.spacenx.messenger.service.push

/**
 * 알림 카테고리별 활성화 여부를 확인하는 인터페이스.
 * 레거시 AtTalkNotificationManager.UseNotification() 대체.
 *
 * 카테고리: "TALK", "MESSAGE", "MCU", "NOTIFY", "SYSTEMALARM_BOARD", "MAIL",
 *           "SYSTEMALARM_WEBMAIL", "SYSTEMALARM_ERP", "SYSTEMALARM_SSO",
 *           "SYSTEMALARM_ONNARA", "SYSTEMALARM_SIGN", "SYSTEMALARM_XF", "SYSTEMALARM_ETC"
 */
interface NotificationSettingsChecker {
    fun useNotification(category: String): Boolean
}

/**
 * 모든 카테고리를 활성화하는 기본 구현.
 * TODO: SharedPreferences 기반 알림 설정 UI 구현 후 교체
 */
class DefaultNotificationSettingsChecker : NotificationSettingsChecker {
    override fun useNotification(category: String): Boolean = true
}
