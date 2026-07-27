package com.kiduyuk.klausk.kiduyutv.ui.player.directstream.api

import android.content.Context
import androidx.core.net.toUri
import com.kiduyuk.klausk.kiduyutv.BuildConfig
import com.kiduyuk.klausk.kiduyutv.ui.player.directstream.model.SubtitleItem
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class SubdlSubtitleResult(
    val nId: String,
    val language: String,
    val releaseName: String,
    val fileName: String?,
    val unpackedUrl: String?
) {
    val displayName: String
        get() = buildString {
            append(language.ifBlank { "Unknown language" })
            if (releaseName.isNotBlank()) append(" • ").append(releaseName)
            fileName?.takeIf { it.isNotBlank() }?.let { append(" • ").append(it) }
        }
}

class SubdlSubtitleClient(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    val isConfigured: Boolean
        get() = BuildConfig.SUBDL_API_KEY.isNotBlank()

    suspend fun search(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?
    ): List<SubdlSubtitleResult> = withContext(Dispatchers.IO) {
        require(isConfigured) { "SubDL API key is not configured" }
        val url = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("tmdb_id", tmdbId.toString())
            .addQueryParameter("type", if (isTv) "tv" else "movie")
            .addQueryParameter("unpack", "1")
            .apply {
                if (isTv) {
                    season?.let { addQueryParameter("season", it.toString()) }
                    episode?.let { addQueryParameter("episode", it.toString()) }
                }
            }
            .build()

        execute(url.toString()).use { response ->
            if (!response.isSuccessful) throw apiError("Search", response.code, response.body?.string())
            val root = JSONObject(response.body?.string().orEmpty())
            val subtitles = root.findArray("subtitles", "results")
                ?: root.optJSONObject("data")?.findArray("subtitles", "results")
                ?: JSONArray()
            parseResults(subtitles)
        }
    }

    suspend fun download(result: SubdlSubtitleResult): SubtitleItem =
        withContext(Dispatchers.IO) {
            require(isConfigured) { "SubDL API key is not configured" }
            val url = API_BASE.toHttpUrl().newBuilder()
                .addPathSegments("api/v2/subtitles")
                .addPathSegment(result.nId)
                .addPathSegment("download")
                .addQueryParameter("format", "file")
                .build()
            val bytes = result.unpackedUrl
                ?.takeIf { it.isNotBlank() }
                ?.let(::downloadBytes)
                ?: executeDownload(url.toString(), null)
            val subtitle = saveSubtitle(bytes, result)
            SubtitleItem(
                url = subtitle.toUri().toString(),
                mimeType = mimeType(subtitle.name),
                language = result.language.takeIf { it.isNotBlank() },
                label = "SubDL • ${result.language.ifBlank { result.releaseName }}"
            )
        }

    private fun parseResults(items: JSONArray): List<SubdlSubtitleResult> = buildList {
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val nId = item.firstString("n_id", "nId", "nid", "id") ?: continue
            val language = item.firstString(
                "language_name",
                "language",
                "lang",
                "language_code"
            ).orEmpty()
            val release = item.firstString(
                "release_name",
                "release",
                "name",
                "title"
            ).orEmpty()
            val files = item.optJSONArray("files")
            if (files != null && files.length() > 0) {
                for (fileIndex in 0 until files.length()) {
                    val file = files.optJSONObject(fileIndex) ?: continue
                    val fileName = file.firstString("file_name", "filename", "name")
                    if (fileName != null && !isSupportedSubtitle(fileName)) continue
                    add(
                        SubdlSubtitleResult(
                            nId = nId,
                            language = language,
                            releaseName = release,
                            fileName = fileName,
                            unpackedUrl = file.firstString(
                                "download_url",
                                "file_url",
                                "url",
                                "link"
                            )
                        )
                    )
                }
            } else {
                val fileName = item.firstString("file_name", "filename")
                if (fileName == null || isSupportedSubtitle(fileName)) {
                    add(
                        SubdlSubtitleResult(
                            nId = nId,
                            language = language,
                            releaseName = release,
                            fileName = fileName,
                            unpackedUrl = item.firstString(
                                "download_url",
                                "file_url",
                                "url",
                                "link"
                            )
                        )
                    )
                }
            }
        }
    }.distinctBy { "${it.nId}:${it.fileName}:${it.unpackedUrl}" }

    private fun executeDownload(primaryUrl: String, fallbackUrl: String?): ByteArray {
        val response = execute(primaryUrl)
        response.use {
            if (!it.isSuccessful) {
                if (!fallbackUrl.isNullOrBlank()) return downloadBytes(fallbackUrl)
                throw apiError("Download", it.code, it.body?.string())
            }
            val body = it.body?.bytes() ?: throw IOException("Empty subtitle response")
            val contentType = it.header("Content-Type").orEmpty()
            if (contentType.contains("json", ignoreCase = true) || body.looksLikeJson()) {
                val downloadUrl = JSONObject(body.toString(Charsets.UTF_8))
                    .findDownloadUrl()
                    ?: fallbackUrl
                    ?: throw IOException("SubDL did not return a subtitle file URL")
                return downloadBytes(downloadUrl)
            }
            return body
        }
    }

    private fun downloadBytes(url: String): ByteArray =
        execute(resolveDownloadUrl(url)).use { response ->
            if (!response.isSuccessful) {
                throw apiError("File download", response.code, response.body?.string())
            }
            response.body?.bytes() ?: throw IOException("Empty subtitle file")
        }

    private fun resolveDownloadUrl(url: String): String =
        API_BASE.toHttpUrl().resolve(url)?.toString()
            ?: throw IOException("SubDL returned an invalid subtitle URL")

    private fun saveSubtitle(bytes: ByteArray, result: SubdlSubtitleResult): File {
        val directory = File(context.cacheDir, "subdl_subtitles").apply { mkdirs() }
        val safeId = result.nId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (bytes.isZip()) {
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || !isSupportedSubtitle(entry.name)) continue
                    val extension = entry.name.substringAfterLast('.', "srt").lowercase()
                    val output = File(directory, "$safeId.$extension")
                    FileOutputStream(output).use { zip.copyTo(it) }
                    return output
                }
            }
            throw IOException("The downloaded archive contains no SRT or VTT file")
        }

        val extension = result.fileName
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it == "srt" || it == "vtt" }
            ?: detectExtension(bytes)
        return File(directory, "$safeId.$extension").apply { writeBytes(bytes) }
    }

    private fun execute(url: String) = client.newCall(
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${BuildConfig.SUBDL_API_KEY}")
            .header("Accept", "*/*")
            .build()
    ).execute()

    private fun apiError(operation: String, code: Int, body: String?): IOException {
        val message = runCatching {
            JSONObject(body.orEmpty()).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty()
        return IOException("$operation failed (HTTP $code)${message.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
    }

    private fun JSONObject.findArray(vararg keys: String): JSONArray? =
        keys.firstNotNullOfOrNull { key -> optJSONArray(key) }

    private fun JSONObject.firstString(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            optString(key).trim().takeIf { it.isNotBlank() && it != "null" }
        }

    private fun JSONObject.findDownloadUrl(): String? {
        firstString("download_url", "file_url", "url", "link")?.let { return it }
        val data = optJSONObject("data") ?: return null
        return data.firstString("download_url", "file_url", "url", "link")
    }

    private fun ByteArray.isZip(): Boolean =
        size >= 4 && this[0] == 0x50.toByte() && this[1] == 0x4b.toByte()

    private fun ByteArray.looksLikeJson(): Boolean =
        firstOrNull { !it.toInt().toChar().isWhitespace() }?.toInt()?.toChar() == '{'

    private fun detectExtension(bytes: ByteArray): String =
        if (bytes.toString(Charsets.UTF_8).trimStart().startsWith("WEBVTT")) "vtt" else "srt"

    private fun isSupportedSubtitle(name: String): Boolean {
        val path = name.substringBefore('?').lowercase()
        return path.endsWith(".srt") || path.endsWith(".vtt")
    }

    private fun mimeType(name: String): String =
        if (name.lowercase().endsWith(".vtt")) "text/vtt" else "application/x-subrip"

    private companion object {
        const val API_BASE = "https://api.subdl.com"
        const val SEARCH_URL = "$API_BASE/api/v2/subtitles/search"
    }
}
