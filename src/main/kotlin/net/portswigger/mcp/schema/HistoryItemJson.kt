package net.portswigger.mcp.schema

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val DEFAULT_HISTORY_ITEM_MAX_LENGTH = 5_000
const val MIN_HISTORY_ITEM_MAX_LENGTH = 1_000
const val MAX_HISTORY_ITEM_MAX_LENGTH = 10_000_000
private const val TRUNCATION_MARKER = "... (truncated)"

internal inline fun <reified T> encodeHistoryItem(item: T, maxLength: Int = DEFAULT_HISTORY_ITEM_MAX_LENGTH): String =
    limitHistoryItemJson(Json.encodeToString(item), maxLength)

@PublishedApi
internal fun limitHistoryItemJson(serialized: String, maxLength: Int = DEFAULT_HISTORY_ITEM_MAX_LENGTH): String {
    if (serialized.length <= maxLength) return serialized

    val item = Json.parseToJsonElement(serialized)
    var lowerBound = TRUNCATION_MARKER.length
    var upperBound = serialized.length
    var best: String? = null

    while (lowerBound <= upperBound) {
        val fieldLimit = (lowerBound + upperBound) / 2
        val candidate = Json.encodeToString(
            JsonElement.serializer(), item.truncateStringsTo(fieldLimit)
        )

        if (candidate.length <= maxLength) {
            best = candidate
            lowerBound = fieldLimit + 1
        } else {
            upperBound = fieldLimit - 1
        }
    }

    return checkNotNull(best) {
        "History item JSON structure exceeds the $maxLength character limit"
    }
}

private fun JsonElement.truncateStringsTo(maxLength: Int): JsonElement = when (this) {
    is JsonObject -> JsonObject(mapValues { (_, value) -> value.truncateStringsTo(maxLength) })
    is JsonArray -> JsonArray(map { it.truncateStringsTo(maxLength) })
    is JsonPrimitive -> if (isString) JsonPrimitive(content.truncateTo(maxLength)) else this
}

private fun String.truncateTo(maxLength: Int): String {
    if (length <= maxLength) return this

    var prefixLength = maxLength - TRUNCATION_MARKER.length
    if (prefixLength > 0 && this[prefixLength - 1].isHighSurrogate() && this[prefixLength].isLowSurrogate()) {
        prefixLength--
    }
    return take(prefixLength) + TRUNCATION_MARKER
}
