/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.domain.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import paige.navic.data.database.dao.AlbumDao
import paige.navic.data.database.dao.ArtistDao
import paige.navic.data.database.dao.PlaylistDao
import paige.navic.data.database.dao.SongDao
import paige.navic.data.database.mapper.toDomainModel
import paige.navic.data.database.mapper.toEntity
import paige.navic.domain.manager.ConnectivityManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.SessionManager
import paige.navic.domain.model.DomainAlbum
import paige.navic.domain.model.DomainSong
import paige.navic.util.Logger

class SearchRepository(
	private val albumDao: AlbumDao,
	private val artistDao: ArtistDao,
	private val songDao: SongDao,
	private val playlistDao: PlaylistDao,
	private val sessionManager: SessionManager,
	private val preferenceManager: PreferenceManager,
	private val youTubeRepository: YouTubeRepository,
	connectivityManager: ConnectivityManager
) {
	val isOnline = connectivityManager.isOnline

	suspend fun search(query: String): List<Any> {
		return if (isOnline.value) {
			try {
				val data = sessionManager.api.searchID3(query)

				albumDao.insertAlbumsIgnoringConflicts(data.albums.map { it.toEntity() })
				artistDao.insertArtistsIgnoringConflicts(data.artists.map { it.toEntity() })
				songDao.insertSongsIgnoringConflicts(data.songs.map { it.toEntity() })

				val albums = albumDao.getAlbumsByIds(data.albums.map { it.id })
				val artists = artistDao.getArtistsByIds(data.artists.map { it.id })
				val songs = songDao.getSongsByIds(data.songs.map { it.id })
				val localPlaylists = playlistDao.searchPlaylistsList(query)

				val serverResults = (albums.map { it.toDomainModel() }
					+ artists.map { it.toDomainModel() }
					+ songs.map { it.toDomainModel() }
					+ localPlaylists.map { it.toDomainModel() })

				// The two YouTube lookups run concurrently to keep this fast.
				val extra = coroutineScope {
					val songsJob = async {
						youTubeSongFallback(
							query,
							songs.map { it.toDomainModel() })
					}
					val albumsJob = async {
						youTubeAlbumSupplement(
							query,
							albums.map { it.toDomainModel() })
					}
					songsJob.await() + albumsJob.await()
				}
				serverResults + extra
			} catch (e: Exception) {
				if (e is CancellationException) throw e
				Logger.e(
					"SearchRepository",
					"Online search failed despite network connection, falling back to local DB",
					e
				)
				performLocalSearch(query)
			}
		} else {
			Logger.i("SearchRepository", "Device offline, performing local search.")
			performLocalSearch(query)
		}
	}

	/**
	 * YouTube Music fallback: only kicks in when the server returned no songs
	 * for the query, so normal library results are never polluted.
	 */
	private suspend fun youTubeSongFallback(query: String, serverSongs: List<DomainSong>): List<DomainSong> {
		if (!preferenceManager.youtubeFallbackEnabled) return emptyList()
		if (serverSongs.isNotEmpty()) return emptyList()
		return try {
			youTubeRepository.search(query)
		} catch (e: Exception) {
			if (e is CancellationException) throw e
			Logger.w("SearchRepository", "YouTube fallback search failed", e)
			emptyList()
		}
	}

	private suspend fun youTubeAlbumSupplement(query: String, serverAlbums: List<DomainAlbum>): List<DomainAlbum> {
		if (!preferenceManager.youtubeFallbackEnabled) return emptyList()
		return try {
			val known = serverAlbums.map {
				normalize(it.name.orEmpty()) to normalize(it.artistName.orEmpty())
			}.toSet()
			youTubeRepository.searchAlbums(query).filter { album ->
				val title = normalize(album.name.orEmpty())
				if (title.isBlank()) return@filter false
				val artist = normalize(album.artistName.orEmpty())
				known.none { (knownTitle, knownArtist) ->
					knownTitle == title && (knownArtist.isBlank() || artist.isBlank() || knownArtist == artist)
				}
			}
		} catch (e: Exception) {
			if (e is CancellationException) throw e
			Logger.w("SearchRepository", "YouTube album fallback search failed", e)
			emptyList()
		}
	}

	/**
	 * Look for a real library song matching a YouTube fallback entry.
	 *
	 * Used after a fallback song has been sent to MeTube: once Navidrome has
	 * scanned the download, this finds it via a server-side search so the
	 * player can swap the external item for the library one without the user
	 * re-searching. Returns null while the server doesn't know the song yet.
	 */
	suspend fun findLibraryMatchFor(ytSong: DomainSong): DomainSong? {
		if (!YouTubeRepository.isYouTubeSong(ytSong)) return null
		val wantedTitle = normalize(ytSong.title)
		val wantedArtist = normalize(ytSong.artistName ?: "")
		if (wantedTitle.isBlank()) return null
		return try {
			// Local DB first: covers the manual-refresh case with no roundtrip.
			localMatch(wantedTitle, wantedArtist)?.let { return it }

			val fullQuery = listOfNotNull(ytSong.artistName, ytSong.title).joinToString(" ")
			serverMatch(fullQuery, wantedTitle, wantedArtist)
				?: serverMatch(ytSong.title, wantedTitle, wantedArtist)
		} catch (e: Exception) {
			if (e is CancellationException) throw e
			Logger.w("SearchRepository", "library match lookup failed for ${ytSong.id}", e)
			null
		}
	}

	private suspend fun localMatch(wantedTitle: String, wantedArtist: String): DomainSong? {
		return try {
			songDao.searchSongsList(wantedTitle)
				.map { it.toDomainModel() }
				.mapNotNull { song ->
					scoreMatch(wantedTitle, wantedArtist, normalize(song.title), normalize(song.artistName ?: ""))
						?.let { score -> song to score }
				}
				.maxByOrNull { it.second }
				?.first
		} catch (e: Exception) {
			if (e is CancellationException) throw e
			null
		}
	}

	private suspend fun serverMatch(query: String, wantedTitle: String, wantedArtist: String): DomainSong? {
		val data = try {
			sessionManager.api.searchID3(query)
		} catch (e: Exception) {
			if (e is CancellationException) throw e
			return null
		}
		if (data.songs.isEmpty()) return null
		val best = data.songs
			.mapNotNull { remote ->
				scoreMatch(
					wantedTitle,
					wantedArtist,
					normalize(remote.title),
					normalize(remote.artistName ?: "")
				)?.let { score -> remote to score }
			}
			.maxByOrNull { it.second }
			?.first ?: return null

		songDao.insertSongsIgnoringConflicts(listOf(best.toEntity()))
		return songDao.getSongById(best.id)?.toDomainModel()
	}

 /**
	 * Fuzzy match score, or null for no match. Titles must clearly agree;
	 * artists must not contradict (covers/same-name songs stay out).
	 */
	private fun scoreMatch(wantedTitle: String, wantedArtist: String, title: String, artist: String): Int? {
		val wantedTitleFlat = wantedTitle.filterNot { it == ' ' }
		val titleFlat = title.filterNot { it == ' ' }
		val titleScore = when {
			title.isBlank() -> return null
			title == wantedTitle -> 3
			title.contains(wantedTitle) || wantedTitle.contains(title) ||
				(titleFlat.isNotEmpty() && wantedTitleFlat.isNotEmpty() &&
					(titleFlat.contains(wantedTitleFlat) || wantedTitleFlat.contains(titleFlat))) -> 2
			tokenOverlap(title, wantedTitle) >= 0.6 -> 1
			else -> return null
		}
		val wantedArtistFlat = wantedArtist.filterNot { it == ' ' }
		val artistFlat = artist.filterNot { it == ' ' }
		val artistOk = when {
			wantedArtist.isBlank() || artist.isBlank() -> true
			artist == wantedArtist -> true
			artist.contains(wantedArtist) || wantedArtist.contains(artist) -> true
			wantedArtistFlat.isNotEmpty() && artistFlat.isNotEmpty() &&
				(artistFlat.contains(wantedArtistFlat) || wantedArtistFlat.contains(artistFlat)) -> true
			tokenOverlap(artist, wantedArtist) >= 0.5 -> true
			else -> false
		}
		if (titleScore < 2 || !artistOk) return null
		return titleScore
	}

	private fun tokenOverlap(a: String, b: String): Double {
		val ta = a.split(" ").filter { it.length > 1 }.toSet()
		val tb = b.split(" ").filter { it.length > 1 }.toSet()
		if (ta.isEmpty() || tb.isEmpty()) return 0.0
		return (ta intersect tb).size.toDouble() / (ta union tb).size
	}

	private fun normalize(s: String): String =
		s.lowercase()
			.replace(Regex("""\([^)]*\)"""), " ")
			.replace(Regex("""\[[^]]*]"""), " ")
			.replace(Regex("""[^a-z0-9]+"""), " ")
			.split(" ")
			.filter { it.isNotBlank() && it !in NOISE_TOKENS }
			.joinToString(" ")

	companion object {
		private val NOISE_TOKENS = setOf(
			"topic", "vevo", "official", "music", "audio", "video",
			"lyrics", "lyric", "hq", "hd", "explicit", "clean",
			"remaster", "remastered", "version", "mv", "m-v"
		)
	}

	private suspend fun performLocalSearch(query: String): List<Any> {
		val localAlbums = albumDao.searchAlbumsList(query).map { it.toDomainModel() }
		val localArtists = artistDao.searchArtistsList(query).map { it.toDomainModel() }
		val localSongs = songDao.searchSongsList(query).map { it.toDomainModel() }
		val localPlaylists = playlistDao.searchPlaylistsList(query).map { it.toDomainModel() }

		return listOf(localAlbums, localArtists, localSongs, localPlaylists).flatten()
	}
}
