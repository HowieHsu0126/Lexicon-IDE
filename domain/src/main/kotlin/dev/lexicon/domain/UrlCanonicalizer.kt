package dev.lexicon.domain

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

object UrlCanonicalizer {
    /** Conservative identity: preserve meaningful query parameters, scheme and trailing path slash. */
    fun canonicalize(url: String): String {
        val uri = URI(url.trim()).normalize()
        val scheme = requireNotNull(uri.scheme).lowercase(Locale.ROOT)
        require(scheme == "https" || scheme == "http") { "仅支持 HTTP(S) 文章链接" }
        require(uri.rawUserInfo == null) { "文章链接不能包含登录凭据" }
        val host = requireNotNull(uri.host) { "文章链接缺少主机名" }.lowercase(Locale.ROOT)
        val port = uri.port.takeUnless { it == -1 || (scheme == "https" && it == 443) || (scheme == "http" && it == 80) }
        val authority = host + (port?.let { ":$it" } ?: "")
        val query = uri.rawQuery?.split('&')?.filterNot { part ->
            val key = URLDecoder.decode(part.substringBefore('='), StandardCharsets.UTF_8.name()).lowercase(Locale.ROOT)
            key.startsWith("utm_") || key in setOf("fbclid", "gclid", "dclid", "mc_cid", "mc_eid")
        }?.filter { it.isNotEmpty() }?.joinToString("&")?.takeIf { it.isNotBlank() }
        return "$scheme://$authority${uri.rawPath.ifEmpty { "/" }}" + (query?.let { "?$it" } ?: "")
    }
}
