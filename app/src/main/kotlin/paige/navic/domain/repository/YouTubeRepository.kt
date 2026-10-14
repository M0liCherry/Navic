/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.domain.repository

import io.ktor.client.plugins.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.model.DomainExplicitStatus
import paige.navic.domain.model.DomainSong
import paige.navic.domain.model.DomainSongArtist
import paige.navic.util.Logger
import paige.navic.util.createHttpClientWithPreferences
import kotlin.time.Duration.Companion.seconds

/**
 * YouTube Music fallback search.
 *
 * Returns proper **song** entries (like YouTube Music's "Song • Artist • plays"
 * rows) rather than video entries ("Video • Artist • views").
 *
 * Uses YouTube Music's internal Innertube API (`WEB_REMIX` client) with the
 * public web client key, so no user-supplied YouTube API key is needed.
 * Audio playback resolves the audio-only stream from the `player` endpoint.
 *
 * Downloads are intentionally routed through the server's MeTube instance
 * (see [sendToMeTube]) when one is configured, so the server fetches the
 * media instead of the phone.
 */
class YouTubeRepository(
	private val preferenceManager: PreferenceManager
) {
	companion object {
		const val PREFIX = "yt-"
		private const val YTM_KEY = "AIzaSyC9XL3ZjWddXya6X74bWGX15bd39eoZJS8"
		// Public Android client key, used for the player endpoint (streams).
		private const val ANDROID_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
		private const val SEARCH_URL = "https://music.youtube.com/youtubei/v1/search"
		private const val PLAYER_URL = "https://music.youtube.com/youtubei/v1/player"
		// Innertube filter param: songs only
		private const val SONGS_FILTER_PARAM = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
		private const val CLIENT_NAME = "WEB_REMIX"
		private const val CLIENT_VERSION = "1.20250101.00.00"
		private const val ANDROID_CLIENT_VERSION = "20.10.38"

		fun isYouTubeSongId(id: String) = id.startsWith(PREFIX)
		fun isYouTubeSong(song: DomainSong) = isYouTubeSongId(song.id)
		fun videoIdOf(song: DomainSong): String = song.id.removePrefix(PREFIX)
		fun watchUrl(videoId: String) = "https://music.youtube.com/watch?v=$videoId"
	}

	private val client = createHttpClientWithPreferences(preferenceManager).config {
		install(ContentNegotiation) {
			json(Json { ignoreUnknownKeys = true })
		}
		install(HttpTimeout) {
			requestTimeoutMillis = 20000
			connectTimeoutMillis = 15000
			socketTimeoutMillis = 20000
		}
	}

	private val json = Json {
		ignoreUnknownKeys = true
		isLenient = true
		explicitNulls = false
	}

	private val streamCache = mutableMapOf<String, String>()
	private val streamCacheMutex = Mutex()

	fun maxResults(): Int = preferenceManager.youtubeMaxResults.coerceIn(1, 25)

	suspend fun search(query: String): List<DomainSong> {
		if (query.isBlank()) return emptyList()
		val limit = maxResults()
		return try {
			// Build request body manually to avoid escaping issues; query is JSON-escaped below.
			val requestJson = """{"context":{"client":{"clientName":"$CLIENT_NAME","clientVersion":"$CLIENT_VERSION","hl":"en","gl":"US"}},"query":${json.encodeToString(query)},"params":"$SONGS_FILTER_PARAM"}"""

			val response = client.post("$SEARCH_URL?prettyPrint=false&key=$YTM_KEY") {
				contentType(ContentType.Application.Json)
				header("Origin", "https://music.youtube.com")
				header("Referer", "https://music.youtube.com/")
				header("Accept-Language", "en-US,en;q=0.9")
				header(
					"User-Agent",
					"Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
				)
				setBody(requestJson)
			}
			if (!response.status.isSuccess()) {
				Logger.w("YouTubeRepository", "search failed: ${response.status}")
				return emptyList()
			}
			val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
			parseSongItems(root).take(limit)
		} catch (e: Exception) {
			Logger.w("YouTubeRepository", "search failed for '$query'", e)
			emptyList()
		}
	}

	suspend fun resolveStreamUrl(videoId: String): String {
		streamCacheMutex.withLock { streamCache[videoId]?.let { return it } }
		// The ANDROID client returns playable audio streams where WEB_REMIX
		// is often UNPLAYABLE without a signature timestamp.
		val requestJson = """{"context":{"client":{"clientName":"ANDROID","clientVersion":"$ANDROID_CLIENT_VERSION","hl":"en","gl":"US","androidSdkVersion":30}},"videoId":${json.encodeToString(videoId)},"playbackContext":{"contentPlaybackContext":{"html5Preference":"HTML5_PREF_WANTS"}},"contentCheckOk":true,"racyCheckOk":true}"""
		val response = client.post("$PLAYER_URL?prettyPrint=false&key=$ANDROID_KEY") {
			contentType(ContentType.Application.Json)
			header("Origin", "https://music.youtube.com")
			header("Referer", "https://music.youtube.com/")
			header("Accept-Language", "en-US,en;q=0.9")
			header(
				"User-Agent",
				"Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
			)
			setBody(requestJson)
		}
		if (!response.status.isSuccess()) throw IllegalStateException("player failed: ${response.status}")
		val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
		val status = root.obj("playabilityStatus")?.str("status")
		if (status != null && status != "OK" && status != "LOGIN_REQUIRED") {
			val reason = root.obj("playabilityStatus")?.str("reason")
			throw IllegalStateException("unplayable: ${reason ?: status}")
		}
		val streamingData = root.obj("streamingData")
			?: throw IllegalStateException("no streamingData")
		val candidates = mutableListOf<Pair<Int, String>>()
		for (key in listOf("adaptiveFormats", "formats")) {
			val arr = streamingData.arr(key) ?: continue
			for (el in arr) {
				val obj = el as? JsonObject ?: continue
				val mime = obj.str("mimeType") ?: continue
				if (!mime.startsWith("audio/")) continue
				val url = obj.str("url") ?: continue
				val bitrate = obj.str("bitrate")?.toIntOrNull() ?: 0
				candidates += bitrate to url
			}
		}
		val best = candidates.maxByOrNull { it.first }?.second
			?: throw IllegalStateException("no audio stream found")
		streamCacheMutex.withLock { streamCache[videoId] = best }
		return best
	}

	suspend fun resolveSong(song: DomainSong): DomainSong {
		if (!isYouTubeSong(song)) return song
		val videoId = videoIdOf(song)
		streamCacheMutex.withLock {
			streamCache[videoId]?.let { return song.copy(filePath = it, mimeType = "audio/webm") }
		}
		val url = resolveStreamUrl(videoId)
		return song.copy(filePath = url, mimeType = "audio/webm")
	}

	/**
	 * Send a YouTube URL to the server's MeTube instance so the *server*
	 * downloads it (`POST {metubeUrl}/add`).
	 */
	suspend fun sendToMeTube(watchUrl: String): Boolean {
		val base = preferenceManager.metubeUrl.trim().trimEnd('/')
		if (base.isBlank()) return false
		return try {
			val response = client.post("$base/add") {
				contentType(ContentType.Application.Json)
				setBody("""{"url":${json.encodeToString(watchUrl)},"quality":"best","format":"mp3"}""")
			}
			if (!response.status.isSuccess()) {
				Logger.w("YouTubeRepository", "metube rejected: ${response.status}")
				return false
			}
			true
		} catch (e: Exception) {
			Logger.w("YouTubeRepository", "metube request failed", e)
			false
		}
	}

	// --- parsing ---

	private fun kotlinx.serialization.json.JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
	private fun kotlinx.serialization.json.JsonObject.arr(key: String): kotlinx.serialization.json.JsonArray? =
		get(key) as? kotlinx.serialization.json.JsonArray
	private fun kotlinx.serialization.json.JsonObject.str(key: String): String? =
		(get(key) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

	private fun parseSongItems(root: JsonObject): List<DomainSong> {
		val renderers = mutableListOf<JsonObject>()
		collectRenderers(root, "musicResponsiveListItemRenderer", renderers)
		val songs = mutableListOf<DomainSong>()
		val seen = mutableSetOf<String>()
		for (r in renderers) {
			try {
				val videoId = extractVideoId(r) ?: continue
				if (!seen.add(videoId)) continue
				val flexColumns = r.arr("flexColumns") ?: continue
				val title = (flexColumns.getOrNull(0) as? JsonObject)
					?.obj("musicResponsiveListItemFlexColumnRenderer")
					?.get("text")
					?.let(::runsText) ?: continue
				if (title.isBlank()) continue

				val secondRuns = (flexColumns.getOrNull(1) as? JsonObject)
					?.obj("musicResponsiveListItemFlexColumnRenderer")
					?.obj("text")
					?.arr("runs")

				var artist: String? = null
				var album: String? = null
				var durationSec: Long? = null
				if (secondRuns != null) {
					val parts = secondRuns.mapNotNull {
						(it as? JsonObject)?.str("text")
					}.filter { it != " • " && it != " · " }.map { it.trim() }
					// Typical songs-filter shape: [artist, album, duration]
					// e.g. ["Kanye West", "808s & Heartbreak", "3:24"]
					artist = parts.getOrNull(0)
					for (p in parts.drop(1)) {
						parseDuration(p)?.let { durationSec = it; continue }
						if (album == null) album = p
					}
				}

				val thumbnails = r.obj("thumbnail")
					?.obj("musicThumbnailRenderer")
					?.obj("thumbnail")
					?.arr("thumbnails")
				val thumbnail = thumbnails?.lastOrNull()
					.let { it as? JsonObject }?.str("url")

				val explicit = r.arr("badges")?.any { badge ->
					badge.toString().contains("MUSIC_INLINE_BADGE_ICON_TYPE_EXPLICIT")
				} == true

				val duration = (durationSec ?: 0).seconds
				val artistName = artist?.takeIf { it.isNotBlank() } ?: "Unknown artist"
				songs += DomainSong(
					id = "$PREFIX$videoId",
					title = title,
					artistName = artistName,
					artistId = "$PREFIX-artist-${artistName.hashCode()}",
					albumTitle = album?.takeIf { it.isNotBlank() } ?: "YouTube Music",
					albumId = "$PREFIX-album",
					parentId = null,
					comment = null,
					trackNumber = null,
					discNumber = null,
					isrc = emptyList(),
					year = null,
					genre = null,
					genres = emptyList(),
					moods = emptyList(),
					duration = duration,
					bpm = null,
					contributors = emptyList(),
					playCount = 0,
					userRating = null,
					averageRating = null,
					bitRate = null,
					bitDepth = null,
					sampleRate = null,
					audioChannelCount = null,
					replayGain = null,
					fileSize = 0,
					fileExtension = "webm",
					mimeType = "audio/webm",
					filePath = watchUrl(videoId),
					starredAt = null,
					coverArtId = thumbnail,
					musicBrainzId = null,
					explicitStatus = if (explicit) DomainExplicitStatus.Explicit else DomainExplicitStatus.Unknown,
					artists = listOf(DomainSongArtist(id = "$PREFIX-artist-${artistName.hashCode()}", name = artistName)),
					albumArtists = emptyList(),
					isExternal = true
				)
			} catch (_: Exception) {
				continue
			}
		}
		return songs
	}

	private fun extractVideoId(r: JsonObject): String? {
		// overlay play button endpoint is the most reliable for song rows
		r.obj("overlay")
			?.obj("musicItemThumbnailOverlayRenderer")
			?.obj("content")
			?.obj("musicPlayButtonRenderer")
			?.obj("playNavigationEndpoint")
			?.obj("watchEndpoint")
			?.str("videoId")?.let { return it }
		r.obj("navigationEndpoint")
			?.obj("watchEndpoint")
			?.str("videoId")?.let { return it }
		r.obj("playlistItemData")
			?.str("videoId")?.let { return it }
		return null
	}

	private fun runsText(text: kotlinx.serialization.json.JsonElement): String {
		val obj = text as? JsonObject ?: return ""
		obj.str("simpleText")?.let { return it }
		val runs = obj.arr("runs") ?: return ""
		return runs.mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("")
	}

	private fun parseDuration(s: String): Long? {
		if (!s.contains(":")) return null
		val parts = s.split(":").mapNotNull { it.toIntOrNull() }
		if (parts.isEmpty()) return null
		if (parts.any { it < 0 }) return null
		var total = 0L
		for (p in parts) total = total * 60 + p
		// sanity: songs are shorter than 12h
		if (total <= 0 || total > 43200) return null
		return total
	}

	private fun collectRenderers(
		element: kotlinx.serialization.json.JsonElement,
		key: String,
		out: MutableList<JsonObject>
	) {
		when (element) {
			is JsonObject -> {
				(element[key] as? JsonObject)?.let { out += it }
				for (v in element.values) collectRenderers(v, key, out)
			}
			is kotlinx.serialization.json.JsonArray -> {
				for (v in element) collectRenderers(v, key, out)
			}
			else -> Unit
		}
	}
}
