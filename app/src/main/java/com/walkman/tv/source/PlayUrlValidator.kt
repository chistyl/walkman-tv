package com.walkman.tv.source

import java.net.URI
import java.util.Locale

/**
 * Rejects empty, placeholder, malformed, or non-web URLs returned by online source scripts.
 * Local file/content URIs are handled by PlaybackController's local resolver, not source scripts.
 */
internal object PlayUrlValidator {
    fun requireHttpUrl(raw: String?): String {
        val value = raw?.trim().orEmpty()
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            null
        }
        val scheme = uri?.scheme?.lowercase(Locale.ROOT)
        if (uri == null || !uri.isAbsolute || scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            throw IllegalArgumentException("音源未提供有效的 HTTP／HTTPS 播放網址")
        }
        return value
    }
}
