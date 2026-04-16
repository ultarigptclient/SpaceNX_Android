package net.spacenx.messenger.common

/**
 * NHM-64: JS 문자열 이스케이프 — 한글 대응
 * replaceAll 대신 문자 단위 순회로 처리하여 한글/유니코드 깨짐 방지
 */
object JsEscapeUtil {

    /**
     * JSON 문자열을 JS single-quote 문자열 안에 안전하게 삽입할 수 있도록 이스케이프
     * 사용: webView.evaluateJavascript("window._fooResolve('${escapeForJs(json)}')", null)
     */
    fun escapeForJs(s: String): String {
        val buf = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '\\' -> buf.append("\\\\")
                '\'' -> buf.append("\\'")
                '\n' -> buf.append("\\n")
                '\r' -> buf.append("\\r")
                '\t' -> buf.append("\\t")
                else -> buf.append(c)
            }
        }
        return buf.toString()
    }
}
