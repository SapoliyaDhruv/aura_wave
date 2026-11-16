package com.aurawave.innertube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// InnerTube responses are deep, loosely-typed trees whose shape changes without notice,
// so they are walked defensively instead of being decoded into fixed classes.

internal fun JsonElement?.at(vararg keys: String): JsonElement? {
    var e = this
    for (k in keys) e = (e as? JsonObject)?.get(k) ?: return null
    return e
}

internal val JsonElement?.str: String?
    get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

internal val JsonElement?.arr: List<JsonElement>
    get() = (this as? JsonArray) ?: emptyList()

/**
 * Finds every value stored under [key] anywhere in the tree. Like the web app's
 * version, it does not look inside a match for further matches.
 */
internal fun JsonElement?.collect(key: String, out: MutableList<JsonElement> = mutableListOf()): List<JsonElement> {
    when (this) {
        is JsonObject -> {
            val hit = this[key]
            if (hit != null && hit !is JsonNull) {
                out += hit
            } else {
                for (v in values) v.collect(key, out)
            }
        }
        is JsonArray -> for (v in this) v.collect(key, out)
        else -> Unit
    }
    return out
}

/** Like [collect], but for several keys at once, keeping the order they appear in the document. */
internal fun JsonElement?.collectAny(keys: Set<String>, out: MutableList<Pair<String, JsonElement>> = mutableListOf()): List<Pair<String, JsonElement>> {
    when (this) {
        is JsonObject -> for ((k, v) in this) {
            if (k in keys && v !is JsonNull) out += k to v else v.collectAny(keys, out)
        }
        is JsonArray -> for (v in this) v.collectAny(keys, out)
        else -> Unit
    }
    return out
}
