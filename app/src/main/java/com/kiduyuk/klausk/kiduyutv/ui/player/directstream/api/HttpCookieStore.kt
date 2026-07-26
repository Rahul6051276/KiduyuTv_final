package com.kiduyuk.klausk.kiduyutv.ui.player.directstream.api

import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI

/**
 * Process-wide, domain-scoped cookie store shared by API discovery and
 * Media3's HttpURLConnection-backed data source.
 */
object HttpCookieStore {
    private val manager = CookieManager(null, CookiePolicy.ACCEPT_ALL)

    init {
        CookieHandler.setDefault(manager)
    }

    fun applyTo(connection: HttpURLConnection, url: String) {
        manager.get(URI(url), emptyMap()).forEach { (name, values) ->
            if (name.isNotBlank() && values.isNotEmpty()) {
                connection.setRequestProperty(name, values.joinToString("; "))
            }
        }
    }

    fun captureFrom(connection: HttpURLConnection, url: String) {
        manager.put(URI(url), connection.headerFields)
    }

    fun cookieHeader(url: String): String? =
        manager.get(URI(url), emptyMap())
            .entries
            .firstOrNull { it.key.equals("Cookie", ignoreCase = true) }
            ?.value
            ?.joinToString("; ")
            ?.takeIf { it.isNotBlank() }
}
