package net.spacenx.messenger.common

import android.util.JsonReader
import android.util.JsonToken
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader

/**
 * OkHttp ResponseBody 를 전체 문자열로 로드하지 않고 [JsonReader] 로 스트리밍 파싱하기 위한 헬퍼.
 *
 * 이전에 `response.body()?.string()` + `JSONObject(rawJson)` 패턴은 대용량 응답(수십 MB+) 시
 * 중간 버퍼가 2~3배 생겨 256MB heap 초과 → OOM 크래시를 유발했다.
 * 이 유틸은 byteStream 을 직접 소비하여 일정 메모리로 파싱을 수행한다.
 *
 * 사용법:
 * ```
 * body.parseStream { reader ->
 *     reader.beginObject()
 *     while (reader.hasNext()) {
 *         when (reader.nextName()) {
 *             "errorCode" -> errorCode = reader.nextInt()
 *             "events" -> {
 *                 reader.beginArray()
 *                 while (reader.hasNext()) { /* 원소별 파싱 */ }
 *                 reader.endArray()
 *             }
 *             else -> reader.skipValue()
 *         }
 *     }
 *     reader.endObject()
 * }
 * ```
 */
object JsonStreamUtil {

    /** ResponseBody 를 JsonReader 로 스트리밍 파싱. body/stream/reader 모두 자동 close. */
    inline fun <T> parse(body: ResponseBody, block: (JsonReader) -> T): T {
        return body.byteStream().use { stream ->
            JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use(block)
        }
    }

    /** 현재 토큰을 String 으로 소비 (null/number/bool 도 안전 처리). */
    fun nextStringOrEmpty(reader: JsonReader): String {
        return when (reader.peek()) {
            JsonToken.NULL -> { reader.nextNull(); "" }
            JsonToken.STRING -> reader.nextString()
            JsonToken.NUMBER -> reader.nextString()
            JsonToken.BOOLEAN -> reader.nextBoolean().toString()
            else -> { reader.skipValue(); "" }
        }
    }

    fun nextLongOrZero(reader: JsonReader): Long {
        return when (reader.peek()) {
            JsonToken.NULL -> { reader.nextNull(); 0L }
            JsonToken.NUMBER -> reader.nextLong()
            JsonToken.STRING -> reader.nextString().toLongOrNull() ?: 0L
            else -> { reader.skipValue(); 0L }
        }
    }

    fun nextIntOrZero(reader: JsonReader): Int {
        return when (reader.peek()) {
            JsonToken.NULL -> { reader.nextNull(); 0 }
            JsonToken.NUMBER -> reader.nextInt()
            JsonToken.STRING -> reader.nextString().toIntOrNull() ?: 0
            else -> { reader.skipValue(); 0 }
        }
    }

    fun nextBooleanOrFalse(reader: JsonReader): Boolean {
        return when (reader.peek()) {
            JsonToken.NULL -> { reader.nextNull(); false }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.STRING -> reader.nextString().equals("true", ignoreCase = true)
            JsonToken.NUMBER -> reader.nextInt() != 0
            else -> { reader.skipValue(); false }
        }
    }

    /** 중첩 object 를 JSONObject 문자열로 재직렬화 (DB entity.json 저장용). */
    fun readObjectAsJsonString(reader: JsonReader): String {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return "{}" }
        return readObject(reader).toString()
    }

    /** 중첩 object 를 JSONObject 로 재구성. */
    fun readObject(reader: JsonReader): JSONObject {
        val obj = JSONObject()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            val v = readValue(reader)
            if (v == null) obj.put(name, JSONObject.NULL) else obj.put(name, v)
        }
        reader.endObject()
        return obj
    }

    /** 임의의 JSON 값을 재귀적으로 Any? 로 변환 (nested object/array 지원). */
    fun readValue(reader: JsonReader): Any? {
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> readObject(reader)
            JsonToken.BEGIN_ARRAY -> {
                val arr = JSONArray()
                reader.beginArray()
                while (reader.hasNext()) arr.put(readValue(reader))
                reader.endArray()
                arr
            }
            JsonToken.STRING -> reader.nextString()
            JsonToken.NUMBER -> {
                val s = reader.nextString()
                s.toLongOrNull() ?: s.toDoubleOrNull() ?: s
            }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> { reader.nextNull(); null }
            else -> { reader.skipValue(); null }
        }
    }
}

/** 확장: `body.parseStream { reader -> ... }` */
inline fun <T> ResponseBody.parseStream(block: (JsonReader) -> T): T =
    JsonStreamUtil.parse(this, block)
