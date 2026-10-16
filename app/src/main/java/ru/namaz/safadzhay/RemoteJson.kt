package ru.namaz.safadzhay

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Reject ambiguous JSON before schema validation. Bounds also cover ignored metadata. */
internal object RemoteJson {
    fun objectFrom(bytes: ByteArray): JSONObject {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        var nodes = 0
        JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            fun value(depth: Int): Any {
                require(depth <= 16 && ++nodes <= 100000) { "JSON complexity limit" }
                return when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        val obj = JSONObject()
                        val keys = mutableSetOf<String>()
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val key = reader.nextName()
                            require(keys.add(key)) { "Duplicate JSON key" }
                            obj.put(key, value(depth + 1))
                        }
                        reader.endObject()
                        obj
                    }
                    JsonToken.BEGIN_ARRAY -> {
                        val array = JSONArray()
                        reader.beginArray()
                        while (reader.hasNext()) array.put(value(depth + 1))
                        reader.endArray()
                        array
                    }
                    JsonToken.STRING -> reader.nextString()
                    JsonToken.NUMBER -> {
                        val number = reader.nextString()
                        require(number.length <= 64)
                        // Keep non-integer numbers distinct: integer fields must never be truncated.
                        number.toLongOrNull() ?: number.toBigDecimal()
                    }
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
                    else -> throw IllegalArgumentException("Invalid JSON value")
                }
            }
            val root = value(0)
            require(root is JSONObject && reader.peek() == JsonToken.END_DOCUMENT)
            return root
        }
    }
}

internal fun JSONObject.strictInt(key: String): Int {
    val value = get(key)
    require(value is Int || value is Long)
    val number = (value as Number).toLong()
    require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
    return number.toInt()
}

internal fun JSONObject.strictString(key: String): String =
    (get(key) as? String) ?: throw IllegalArgumentException("Expected JSON string")

internal fun JSONObject.strictBoolean(key: String): Boolean =
    (get(key) as? Boolean) ?: throw IllegalArgumentException("Expected JSON boolean")
