package com.wonderlandkid.biliaudio.data.net

import kotlinx.serialization.json.Json

/**
 * B 站接口 JSON 解析配置。
 *
 * B 站接口字段经常增删、部分字段大小写/命名不统一，因此统一开启容错：
 * - [Json.ignoreUnknownKeys]：忽略未知字段；
 * - [Json.isLenient]：允许不严格的 JSON（如未加引号的字面量）；
 * - [Json.coerceInputValues]：字段为 null / 类型不符时回落到 DTO 默认值。
 */
object BiliJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }
}
