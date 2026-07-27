# Dynamic Scraper Endpoint Migration Guide

## Purpose

This guide explains how to prevent the live-TV scraping feature from waiting indefinitely or
failing whenever the scraped website changes its domain or top-level domain (TLD).

No application code was changed as part of this guide. The snippets below are proposed
implementation examples.

## Scope

The issue is in the live-TV scraping path, not the movie/TV embed provider list in
`StreamProvider.kt`.

The relevant current files are:

- `app/src/main/java/com/kiduyuk/klausk/kiduyutv/data/repository/ChannelScraper.kt`
- `app/src/main/java/com/kiduyuk/klausk/kiduyutv/data/api/ScheduleApiService.kt`
- `app/src/main/java/com/kiduyuk/klausk/kiduyutv/data/repository/IptvRepository.kt`
- `app/src/main/java/com/kiduyuk/klausk/kiduyutv/util/LiveTvCacheManager.kt`
- `app/src/main/java/com/kiduyuk/klausk/kiduyutv/util/ScrapedChannelsCache.kt`
- `app/src/main/java/com/kiduyuk/klausk/kiduyutv/viewmodel/LiveTvViewModel.kt`
- `app/src/main/java/com/kiduyuk/klausk/kiduyutv/viewmodel/SettingsViewModel.kt`
- the phone and TV settings screens where a scraper-address editor can be exposed

`StreamProviderManager` already loads movie/TV provider templates from Firebase. The live-TV
scraper should use the same configuration principle, but it should have a separate configuration
node because its paths, HTML selectors, health checks, and fallback behavior are different.

## Current end-to-end implementation

There are currently three related but disconnected Live TV data paths:

| Path | Entry point | Network source | Consumer |
|---|---|---|---|
| IPTV channel list and EPG | `LiveTvViewModel.loadPlaylist()` | Hard-coded constants in `IptvRepository` | TV and phone Live TV screens |
| User-entered M3U/XMLTV URLs | `SettingsViewModel.updateLiveTvData()` | Text entered in settings | Saved by `LiveTvCacheManager`, but not read by `LiveTvViewModel` |
| Scraped 24/7 channels | `SettingsViewModel.scrapeChannels()` | Hard-coded `dlhd.pk` in `ChannelScraper` | Saved by `ScrapedChannelsCache`, but not read by either Live TV screen |

The schedule tab is a fourth request path. It uses `ScheduleRepository` and
`ScheduleApiService`, which separately hard-code the same website.

The effective runtime flow displayed by the Live TV screens is:

```text
LiveTvScreen / MobileLiveTvScreen
        |
        v
LiveTvViewModel.loadPlaylist()
        |
        v
IptvRepository.fetchPlaylist()
        |
        +--> hard-coded PLAYLIST_URL
        +--> hard-coded PLAYLIST_EPG_URL
```

The scraper button currently follows a separate path:

```text
Settings screen
        |
        v
SettingsViewModel.scrapeChannels()
        |
        v
ChannelScraper.fetchChannels(fetchStreamUrls = true)
        |
        +--> GET channel-list page
        +--> GET every channel watch page sequentially
        |
        v
ScrapedChannelsCache.saveChannels()
        |
        +--> no Live TV screen currently reads this cache
```

This means changing the settings playlist URL does not change what `LiveTvViewModel` loads, and a
successful scrape does not by itself populate the current Live TV channel grid. Fixing only the
redirect behavior would therefore leave an important integration gap.

## Why the current implementation fails

### 1. The address is compiled into two separate components

`ChannelScraper` currently declares:

```kotlin
private const val BASE_URL = "https://dlhd.pk"
private const val CHANNELS_URL = "$BASE_URL/24-7-channels.php"
```

`ScheduleApiService` separately declares:

```kotlin
const val BASE_URL = "https://dlhd.pk"
private const val SCHEDULE_URL = BASE_URL
```

Changing only one declaration leaves the channel list, schedule, watch page, relative links, or
fallback iframe URLs pointing at different origins.

### 2. Following the first redirect is not sufficient

OkHttp and Jsoup can follow HTTP redirects, but the code continues constructing later URLs with
the original `BASE_URL`:

```kotlin
val watchPageUrl = if (href.startsWith("http")) href else "$BASE_URL$href"
```

For example, if `https://old.example` redirects to `https://new.example`, the first HTML page may
load correctly while every relative watch URL is rebuilt against `old.example`.

### 3. The settings scrape performs potentially unbounded sequential work

`SettingsViewModel.scrapeChannels()` calls:

```kotlin
ChannelScraper.fetchChannels(fetchStreamUrls = true)
```

The scraper then visits every channel page sequentially. With a 15-second timeout, 100 dead
channel pages can theoretically consume roughly 25 minutes, excluding DNS delays and parsing.
The UI therefore appears to be initializing indefinitely even though each individual request has
a timeout.

### 4. Some failures are converted into empty results

Per-channel exceptions return an empty stream list. This makes these different conditions look
the same:

- the domain is dead;
- the domain redirected;
- the HTML structure changed;
- the channel has no active stream;
- the request timed out.

The UI needs a terminal error state and a reason instead of only an empty list.

### 5. User-configured playlist and EPG addresses are not the active data source

`LiveTvCacheManager` persists `playlist_url` and `epg_url`, but `IptvRepository` downloads:

```kotlin
const val PLAYLIST_URL =
    "https://raw.githubusercontent.com/abusaeeidx/IPTV-Scraper-Zilla/main/combined-playlist.m3u"
const val PLAYLIST_EPG_URL =
    "https://raw.githubusercontent.com/JulioCesarXY/EPG-LG-Channels/refs/heads/main/lg_epg_us.xml"
```

`LiveTvViewModel` calls `IptvRepository.fetchPlaylist()` and `fetchEpg()`, not
`LiveTvCacheManager.parsePlaylist()`. Therefore the visible Live TV list ignores the URL entered
in settings.

### 6. Scraped results are persisted but not presented

`SettingsViewModel` saves scraper results using:

```kotlin
ScrapedChannelsCache.saveChannels(context, channels)
```

The only corresponding load currently occurs in
`SettingsViewModel.refreshScrapedChannels()`, where it updates the cached count. Neither
`LiveTvScreen` nor `MobileLiveTvScreen` loads those `ScrapedChannel` objects. This can make a
successful scrape appear to have had no effect.

## Recommended architecture

Use one endpoint configuration for both scraper implementations and resolve it before scraping:

```text
User override ───────┐
Firebase config ─────┼─> ScraperEndpointRepository ─> EndpointResolver
Last known good URL ─┤                                │
Bundled defaults ────┘                                ├─> ChannelScraper
                                                     └─> ScheduleApiService
```

The recommended precedence is:

1. a validated user override;
2. Firebase primary and fallback addresses;
3. the last successfully resolved address;
4. bundled fallback candidates.

The endpoint resolver should test candidates, accept a valid redirect destination, persist the
working address, and return one canonical base URL to both scraper classes.

## Step 1: Define a scraper-specific configuration model

Keep paths separate from the origin so a TLD change requires updating only `baseUrls`.

```kotlin
data class ScraperEndpointConfig(
    val enabled: Boolean = true,
    val baseUrls: List<String> = listOf("https://dlhd.pk"),
    val schedulePath: String = "/",
    val channelsPath: String = "/24-7-channels.php",
    val watchPathTemplate: String = "/watch.php?id=%s",
    val connectTimeoutMs: Long = 8_000,
    val readTimeoutMs: Long = 12_000,
    val overallTimeoutMs: Long = 45_000,
    val updatedAt: Long = 0L
)
```

Firebase Realtime Database can store it under
`app_config/scraper_Configuration`:

```json
{
  "scraper_Configuration": {
    "enabled": true,
    "base_urls": {
      "0": "https://current-domain.example",
      "1": "https://fallback-domain.example"
    },
    "schedule_path": "/",
    "channels_path": "/24-7-channels.php",
    "watch_path_template": "/watch.php?id=%s",
    "connect_timeout_ms": 8000,
    "read_timeout_ms": 12000,
    "overall_timeout_ms": 45000,
    "updated_at": 1783987200000
  }
}
```

This allows the address to be corrected remotely without shipping a new APK.

## Step 2: Add a validated local user override

Store only the normalized base address. Do not store a full channel or watch-page path in this
field.

```kotlin
class ScraperEndpointPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(
        "scraper_endpoint_preferences",
        Context.MODE_PRIVATE
    )

    fun getOverride(): String? = preferences
        .getString(KEY_OVERRIDE, null)
        ?.takeIf { it.isNotBlank() }

    fun saveOverride(value: String?) {
        preferences.edit()
            .putString(KEY_OVERRIDE, value?.trim()?.trimEnd('/'))
            .apply()
    }

    fun getLastKnownGood(): String? =
        preferences.getString(KEY_LAST_KNOWN_GOOD, null)

    fun saveLastKnownGood(value: String) {
        preferences.edit()
            .putString(KEY_LAST_KNOWN_GOOD, value.trimEnd('/'))
            .apply()
    }

    private companion object {
        const val KEY_OVERRIDE = "scraper_base_url_override"
        const val KEY_LAST_KNOWN_GOOD = "scraper_last_known_good_url"
    }
}
```

Validate the value before saving it:

```kotlin
fun normalizeScraperBaseUrl(input: String): HttpUrl? {
    val candidate = input.trim().let { value ->
        if (value.startsWith("http://") || value.startsWith("https://")) {
            value
        } else {
            "https://$value"
        }
    }

    val parsed = candidate.toHttpUrlOrNull() ?: return null
    if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
    if (parsed.query != null || parsed.fragment != null) return null

    return parsed.newBuilder()
        .encodedPath("/")
        .build()
}
```

### Address safety

Allowing arbitrary addresses introduces server-side request forgery-style risks from the app's
network context. Unless private-network scraping is an explicit feature, reject:

- `localhost` and loopback addresses;
- link-local addresses;
- private IPv4 ranges;
- URLs containing credentials;
- non-HTTP schemes.

Prefer HTTPS hostnames. A raw IP used with HTTPS commonly fails certificate hostname validation.
Supporting plain HTTP IPs also requires an explicit Android network-security decision and should
not be silently enabled.

## Step 3: Resolve redirects and fallback candidates once

Use the final `response.request.url` returned by OkHttp. This is the canonical address after all
accepted redirects.

```kotlin
data class ResolvedScraperEndpoint(
    val baseUrl: HttpUrl,
    val source: String
)

class ScraperEndpointResolver(
    private val client: OkHttpClient,
    private val preferences: ScraperEndpointPreferences
) {
    suspend fun resolve(
        userOverride: String?,
        remoteCandidates: List<String>,
        bundledCandidates: List<String>,
        channelsPath: String
    ): Result<ResolvedScraperEndpoint> = withContext(Dispatchers.IO) {
        val candidates = buildList {
            userOverride?.let(::add)
            addAll(remoteCandidates)
            preferences.getLastKnownGood()?.let(::add)
            addAll(bundledCandidates)
        }.mapNotNull(::normalizeScraperBaseUrl)
            .distinctBy { it.host }

        for (candidate in candidates) {
            val probeUrl = candidate.resolve(channelsPath) ?: continue
            val request = Request.Builder()
                .url(probeUrl)
                .header("User-Agent", SCRAPER_USER_AGENT)
                .get()
                .build()

            val resolved = runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")

                    val html = response.body?.string().orEmpty()
                    val document = Jsoup.parse(html, response.request.url.toString())
                    if (document.select("div.grid a.card").isEmpty()) {
                        error("Expected channel selector not found")
                    }

                    // Keep only the origin; paths are supplied by configuration.
                    response.request.url.newBuilder()
                        .encodedPath("/")
                        .query(null)
                        .fragment(null)
                        .build()
                }
            }.getOrNull()

            if (resolved != null) {
                preferences.saveLastKnownGood(resolved.toString())
                return@withContext Result.success(
                    ResolvedScraperEndpoint(
                        baseUrl = resolved,
                        source = if (candidate.toString() == userOverride) "user" else "resolved"
                    )
                )
            }
        }

        Result.failure(IllegalStateException("No scraper endpoint passed validation"))
    }

    private companion object {
        const val SCRAPER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    }
}
```

Use a client with redirects and a call-level timeout:

```kotlin
val scraperClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(12, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()
```

A candidate is considered healthy only when it returns successful HTML containing an expected
page marker. A redirect to a parking page, challenge page, or unrelated website must not be
accepted merely because it returned HTTP 200.

## Step 4: Resolve relative links from the final page location

Do not concatenate `BASE_URL` and `href`. Jsoup records the final document location after a
redirect and can resolve relative attributes correctly.

```kotlin
val document = Jsoup.connect(channelsUrl.toString())
    .followRedirects(true)
    .timeout(config.readTimeoutMs.toInt())
    .userAgent(SCRAPER_USER_AGENT)
    .get()

val finalPageUrl = document.location().toHttpUrlOrNull()
    ?: error("Redirect produced an invalid final URL")

val channels = document.select("div.grid a.card").mapNotNull { link ->
    val watchPageUrl = link.absUrl("href").takeIf { it.isNotBlank() }
        ?: finalPageUrl.resolve(link.attr("href"))?.toString()
        ?: return@mapNotNull null

    ScrapedChannel(
        id = extractChannelId(watchPageUrl),
        name = link.selectFirst("div.card__title")?.text().orEmpty(),
        watchPageUrl = watchPageUrl,
        iframeUrls = emptyList(),
        category = "Channels"
    )
}
```

The same rule applies to stream buttons and iframes:

```kotlin
val streamUrls = document
    .select("#playerBtns button[data-url], iframe#playerFrame[src]")
    .mapNotNull { element ->
        val attribute = if (element.hasAttr("data-url")) "data-url" else "src"
        element.absUrl(attribute).takeIf { it.isNotBlank() }
    }
    .distinct()
```

When HTML is obtained through OkHttp instead of Jsoup's network API, always pass its final URL as
the base URI:

```kotlin
client.newCall(request).execute().use { response ->
    val html = response.body?.string().orEmpty()
    val document = Jsoup.parse(html, response.request.url.toString())
    val absoluteWatchUrl = document.selectFirst("a[data-ch]")?.absUrl("href")
}
```

## Step 5: Make `ChannelScraper` and `ScheduleApiService` share the endpoint

Both classes should receive the same resolved endpoint rather than defining their own constants.

```kotlin
class ChannelScraper(
    private val endpointRepository: ScraperEndpointRepository
) {
    suspend fun fetchChannels(fetchStreamUrls: Boolean = false): Result<List<ScrapedChannel>> {
        val endpoint = endpointRepository.requireResolvedEndpoint()
        val config = endpointRepository.currentConfig()
        val channelsUrl = endpoint.baseUrl.resolve(config.channelsPath)
            ?: return Result.failure(IllegalArgumentException("Invalid channels path"))

        return scrapeChannelList(channelsUrl, config, fetchStreamUrls)
    }
}
```

```kotlin
class ScheduleApiService(
    private val endpointRepository: ScraperEndpointRepository,
    private val client: OkHttpClient
) {
    suspend fun fetchChannelWatchPage(channelId: String): Result<ChannelWatchPage> {
        val endpoint = endpointRepository.requireResolvedEndpoint()
        val config = endpointRepository.currentConfig()
        val relativePath = config.watchPathTemplate.format(channelId)
        val watchUrl = endpoint.baseUrl.resolve(relativePath)
            ?: return Result.failure(IllegalArgumentException("Invalid watch path"))

        return fetchAndParseWatchPage(watchUrl)
    }
}
```

This prevents the schedule and channel-list implementations from drifting to different domains.

## Step 6: Stop eagerly scraping every stream during initialization

The channel-list refresh should fetch only the list:

```kotlin
val result = withTimeout(config.overallTimeoutMs) {
    channelScraper.fetchChannels(fetchStreamUrls = false)
}
```

Fetch the selected channel's stream URLs when the user opens that channel:

```kotlin
suspend fun openChannel(channel: ScrapedChannel) {
    _uiState.update { it.copy(selectedChannelLoading = true) }

    val result = withTimeoutOrNull(15_000) {
        channelScraper.fetchStreamUrls(channel.watchPageUrl)
    }

    _uiState.update {
        if (!result.isNullOrEmpty()) {
            it.copy(selectedChannelLoading = false, selectedChannelStreams = result)
        } else {
            it.copy(
                selectedChannelLoading = false,
                selectedChannelError = "The channel address could not be resolved"
            )
        }
    }
}
```

This changes initial work from `1 + numberOfChannels` network requests to one request. If eager
prefetching is still required, use limited concurrency and one overall deadline rather than an
unbounded sequential loop.

```kotlin
withTimeout(60_000) {
    channels.chunked(4).flatMap { batch ->
        coroutineScope {
            batch.map { channel ->
                async(Dispatchers.IO) {
                    channel.copy(
                        iframeUrls = withTimeoutOrNull(8_000) {
                            channelScraper.fetchStreamUrls(channel.watchPageUrl)
                        }.orEmpty()
                    )
                }
            }.awaitAll()
        }
    }
}
```

## Step 7: Expose clear terminal states in settings

Avoid representing every operation with one indefinite Boolean. Model resolution separately from
scraping:

```kotlin
sealed interface ScraperStatus {
    data object Idle : ScraperStatus
    data object ResolvingAddress : ScraperStatus
    data class TestingAddress(val address: String) : ScraperStatus
    data class FetchingChannels(val address: String) : ScraperStatus
    data class Success(val address: String, val channelCount: Int) : ScraperStatus
    data class Failed(val reason: String, val attemptedAddresses: List<String>) : ScraperStatus
}
```

The settings editor should provide:

- current effective address;
- optional user override;
- **Test address** action;
- **Save and use** action enabled only after validation;
- **Reset to automatic** action;
- last successful address and timestamp;
- a finite error message listing attempted hosts.

Example Compose surface:

```kotlin
OutlinedTextField(
    value = scraperAddress,
    onValueChange = viewModel::updateScraperAddressDraft,
    label = { Text("Scraper address") },
    placeholder = { Text("https://example.com") },
    singleLine = true,
    isError = scraperAddressError != null,
    supportingText = {
        scraperAddressError?.let { Text(it) }
    }
)

Button(
    onClick = viewModel::testScraperAddress,
    enabled = !isTestingAddress
) {
    Text(if (isTestingAddress) "Testing…" else "Test address")
}

TextButton(onClick = viewModel::clearScraperAddressOverride) {
    Text("Reset to automatic")
}
```

## Step 8: Connect configured and scraped sources to the Live TV screens

Use one repository as the source of truth instead of making the ViewModel choose between
unrelated cache implementations. A minimal source model is:

```kotlin
sealed interface LiveTvSource {
    data class RemotePlaylist(
        val playlistUrl: HttpUrl,
        val epgUrl: HttpUrl?
    ) : LiveTvSource

    data class ScrapedWebsite(
        val endpoint: ResolvedScraperEndpoint
    ) : LiveTvSource
}
```

For playlist mode, make `IptvRepository` accept the configured URL:

```kotlin
suspend fun fetchPlaylist(
    context: Context,
    playlistUrl: HttpUrl,
    forceRefresh: Boolean = false
): Result<IptvPlaylist> = withContext(Dispatchers.IO) {
    if (!forceRefresh) {
        loadFromCache(context)?.let { return@withContext Result.success(it) }
    }

    val request = Request.Builder()
        .url(playlistUrl)
        .header("User-Agent", "KiduyuTV/1.0")
        .build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            return@withContext Result.failure(
                IOException("Playlist request failed with HTTP ${response.code}")
            )
        }
        val body = response.body
            ?: return@withContext Result.failure(IOException("Empty playlist response"))
        Result.success(parseAndCacheM3uPlaylist(context, body))
    }
}
```

Then resolve the setting before loading:

```kotlin
fun loadPlaylist(forceRefresh: Boolean = false) {
    val context = appContext ?: return

    viewModelScope.launch {
        _uiState.update { it.copy(isLoading = true, error = null) }

        val configured = LiveTvCacheManager.getSavedPlaylistUrl(context)
        val playlistUrl = configured.toHttpUrlOrNull()
            ?: IptvRepository.DEFAULT_PLAYLIST_URL.toHttpUrl()

        repository.fetchPlaylist(context, playlistUrl, forceRefresh).fold(
            onSuccess = ::showPlaylist,
            onFailure = ::showPlaylistError
        )
    }
}
```

For scraper mode, either convert `ScrapedChannel` into the screen's common channel model or add a
separate `scrapedChannels` collection to `LiveTvUiState`. A common model avoids duplicating the TV
and phone UI:

```kotlin
data class LiveChannelItem(
    val stableId: String,
    val name: String,
    val logoUrl: String?,
    val playbackUrls: List<String>,
    val source: ChannelSource
)

enum class ChannelSource {
    PLAYLIST,
    SCRAPER
}

fun ScrapedChannel.toLiveChannelItem() = LiveChannelItem(
    stableId = "scraper:$id",
    name = name,
    logoUrl = thumbnailUrl,
    playbackUrls = iframeUrls,
    source = ChannelSource.SCRAPER
)
```

Because the recommended initialization no longer prefetches stream URLs, `playbackUrls` may
initially be empty. On click, resolve that channel's watch page, update only that row, and then
launch `SchedulePlayerActivity`.

```kotlin
fun playScrapedChannel(channel: ScrapedChannel) {
    viewModelScope.launch {
        _uiState.update { it.copy(openingChannelId = channel.id, channelOpenError = null) }

        val streams = withTimeoutOrNull(15_000) {
            channelScraper.fetchStreamUrls(channel.watchPageUrl)
        }.orEmpty()

        if (streams.isEmpty()) {
            _uiState.update {
                it.copy(
                    openingChannelId = null,
                    channelOpenError = "No working player address was found for ${channel.name}"
                )
            }
        } else {
            _events.emit(LiveTvEvent.PlayScrapedChannel(channel, streams))
            _uiState.update { it.copy(openingChannelId = null) }
        }
    }
}
```

`fetchStreamUrls()` must become public (or move behind a repository method) for lazy loading.
The UI should disable only the selected channel while it resolves; it should not replace the
entire Live TV page with a global loading indicator.

## Recommended policy for user overrides

Two reasonable policies exist when a user-entered scraper address fails:

1. **Strict override:** show the failure and do not try automatic alternatives. This makes
   testing predictable.
2. **Preferred override with fallback:** try the user address first, then remote and bundled
   candidates. Show which address was ultimately selected.

The second policy provides better availability, but it must not silently claim that the entered
address worked. Display both the configured override and effective resolved address.

Suggested controls for both TV and phone settings:

- scraper base address input;
- **Test address**;
- **Save and use**;
- **Reset to automatic**;
- **Refresh channel list**;
- effective address and redirect destination;
- last successful check timestamp;
- attempted-host summary after failure.

Do not tie address testing to a full scrape. Testing should perform one bounded request against
the channel-list path and validate `div.grid a.card`.

## Error classification

Return actionable errors instead of silently converting everything to an empty list:

```kotlin
sealed class ScraperFailure(message: String) : Exception(message) {
    data class InvalidAddress(val value: String) :
        ScraperFailure("Invalid scraper address")

    data class Unreachable(val hosts: List<String>) :
        ScraperFailure("No configured scraper address is reachable")

    data class UnexpectedPage(val host: String, val missingSelector: String) :
        ScraperFailure("The page structure changed on $host")

    data class TimedOut(val operation: String) :
        ScraperFailure("$operation exceeded its time limit")
}
```

Recommended user messages:

| Failure | User message |
|---|---|
| DNS/connect failure for every candidate | “No configured scraper address could be reached.” |
| Redirect to invalid page | “The address redirected to an unsupported page.” |
| Required selector missing | “The website layout has changed.” |
| Overall timeout | “Scraping took too long and was stopped.” |
| User override invalid | “Enter a valid HTTP or HTTPS base address.” |

## Relationship to `StreamProvider.kt`

The provider definitions selected in the IDE control movie and TV embed URLs. Their Firebase path
is currently `app_config/stream_providers_Configuration`, and they already support remotely
changing `movie_url_template` and `tv_url_template`.

Do not put the live-TV scraper address into an individual `StreamProvider`. Instead:

- use `stream_providers_Configuration` for movie/TV embed providers;
- use `scraper_Configuration` for the live-TV channel and schedule website;
- use one shared scraper endpoint repository for `ChannelScraper` and `ScheduleApiService`.

## Migration sequence

1. Add `ScraperEndpointConfig`, preferences, and Firebase parsing.
2. Add `ScraperEndpointResolver` with redirects, health validation, and fallbacks.
3. Inject the shared resolved endpoint into `ChannelScraper` and `ScheduleApiService`.
4. Replace manual `"$BASE_URL$href"` construction with base-aware URL resolution.
5. Change settings refresh to `fetchStreamUrls = false` and fetch streams on channel selection.
6. Add an overall timeout and terminal failure states.
7. Add phone/TV settings controls for testing, saving, and resetting the override.
8. Make `IptvRepository` consume the saved playlist/EPG URLs instead of unrelated constants.
9. Load cached scraped channels into the common Live TV state and resolve streams lazily.
10. After all consumers use shared configuration, remove duplicated hard-coded URL constants.

## Test matrix

| Scenario | Expected result |
|---|---|
| Primary address works | It is selected and persisted as last known good. |
| Primary redirects to a new HTTPS TLD | The final origin is used for all relative URLs. |
| Primary is dead, fallback works | Fallback is selected within the overall deadline. |
| User override works | Override takes precedence and is shown as active. |
| User override fails | Clear failure is shown; automatic fallback behavior follows the chosen policy. |
| Redirect returns a parking page | Candidate fails expected-selector validation. |
| Channel list works but HTML selectors changed | `UnexpectedPage` is returned instead of an empty success. |
| One channel is dead | Channel-list initialization still completes; only that channel shows an error. |
| All candidates are dead | Loading ends within the configured overall timeout. |
| App restarts offline | Cached channels and last-known-good address remain available. |
| User changes the M3U URL | `LiveTvViewModel` displays channels from the new playlist. |
| Scrape succeeds | Scraped channels become visible in both TV and phone Live TV screens. |
| Selected scraped channel is dead | Only that channel fails and the main screen remains usable. |

## Acceptance criteria

The migration is complete when:

- no scraper base URL is duplicated in `ChannelScraper` or `ScheduleApiService`;
- a user or Firebase update can change the scraper address without an APK update;
- redirects update the base used for relative watch and stream URLs;
- a dead address automatically tries bounded fallback candidates;
- initial channel loading performs no per-channel stream prefetch;
- every operation has a total deadline and a terminal UI state;
- invalid user addresses cannot target unsafe schemes or unintended local-network hosts;
- cached channels remain usable when all scraper endpoints are temporarily unavailable;
- the playlist and EPG URLs shown in settings are the URLs consumed by `IptvRepository`;
- scraped-channel cache data is consumed by both TV and phone Live TV screens;
- opening one scraped channel has a bounded loading state independent of global initialization.
