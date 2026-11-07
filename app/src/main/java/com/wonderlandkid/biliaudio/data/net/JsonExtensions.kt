package com.wonderlandkid.biliaudio.data.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 容错 JSON 读取工具：B 站搜索等接口字段不稳定，用 JsonElement 动态读取比严格 DTO 更稳。
 * 所有函数对缺失字段 / JsonNull / 类型不符都返回 null，不抛异常。
 */

internal fun JsonElement?.jsonObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement?.jsonArrayOrNull(): JsonArray? = this as? JsonArray

internal fun JsonObject.objectOrNull(key: String): JsonObject? = get(key) as? JsonObject

internal fun JsonObject.arrayOrNull(key: String): JsonArray? = get(key) as? JsonArray

internal fun JsonObject.stringOrNull(key: String): String? {
    val element = get(key) ?: return null
    if (element is JsonNull) return null
    return (element as? JsonPrimitive)?.contentOrNull
}

internal fun JsonObject.intOrNull(key: String): Int? = stringOrNull(key)?.trim()?.toIntOrNull()

internal fun JsonObject.longOrNull(key: String): Long? = stringOrNull(key)?.trim()?.toLongOrNull()

internal fun JsonObject.boolOrNull(key: String): Boolean? = when (stringOrNull(key)?.trim()?.lowercase()) {
    "true", "1" -> true
    "false", "0" -> false
    else -> null
}
