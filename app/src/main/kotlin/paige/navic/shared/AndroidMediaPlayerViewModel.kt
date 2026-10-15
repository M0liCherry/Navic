/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.shared

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import paige.navic.R
import paige.navic.data.database.dao.AlbumDao
import paige.navic.data.database.mapper.toDomainModel
import paige.navic.domain.manager.AudioGainManager
import paige.navic.domain.manager.ConnectivityManager
import paige.navic.domain.manager.DownloadManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.SessionManager
import paige.navic.domain.manager.SnackBarManager
import paige.navic.domain.model.DomainAlbum
import paige.navic.domain.model.DomainExplicitStatus
import paige.navic.domain.model.DomainRadio
import paige.navic.domain.model.DomainSong
import paige.navic.domain.model.DomainSongCollection
import paige.navic.domain.model.settings.ReplayGainMode
import paige.navic.domain.repository.PlayerStateRepository
import paige.navic.domain.repository.SearchRepository
import paige.navic.domain.repository.SongRepository
import paige.navic.domain.repository.YouTubeRepository
import paige.navic.exoplayer.ExoStateHolder
import paige.navic.ui.core.PlayerUiState
import paige.navic.util.Logger
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@UnstableApi
class AndroidMediaPlayerViewModel(
	stateRepository: PlayerStateRepository,
	songRepository: SongRepository,
	downloadManager: DownloadManager,
	connectivityManager: ConnectivityManager,
	preferenceManager: PreferenceManager,
	private val audioGainManager: AudioGainManager,
	private val application: Application,
	private val albumDao: AlbumDao,
	private val sessionManager: SessionManager,
	private val snackBarManager: SnackBarManager,
	private val youTubeRepository: YouTubeRepository,
	private val searchRepository: SearchRepository
) : MediaPlayerViewModel(
	stateRepository = stateRepository,
	songRepository = songRepository,
	connectivityManager = connectivityManager,
	downloadManager = downloadManager,
	preferenceManager = preferenceManager
) {
	private var controller: MediaController? = null
	private var controllerFuture: ListenableFuture<MediaController>? = null

	private var loadingCollectionId: String? = null

	private var pendingSyncState: PlayerUiState? = null

	init {
		connectToService()
	}

	// just because I hate writing boilerplate every single line
	private fun launchInView(immediate: Boolean = false, block: suspend CoroutineScope.() -> Unit) {
		val context = if (immediate) {
			Dispatchers.Main.immediate
		} else {
            EmptyCoroutineContext
		}

		viewModelScope.launch(
			context = context,
			block = block
		)
	}

	private fun connectToService() = launchInView {
		val sessionToken = ExoStateHolder.newSessionToken(application)
		controllerFuture = MediaController.Builder(application, sessionToken).buildAsync()
		controllerFuture?.addListener({
			controller = controllerFuture?.get()
			setupController()
		}, MoreExecutors.directExecutor())
	}

	// TODO: I THINK this could be somewhere else
	private fun getStreamUrl(id: String): Uri {
		val isCellular = connectivityManager.isCellular.value
		val bitrate = if (preferenceManager.isAdvancedTranscodingActive) {
			if (isCellular) preferenceManager.customMaxBitrateCellular else preferenceManager.customMaxBitrateWifi
		} else {
			if (isCellular) preferenceManager.streamingQualityCellular.bitrate else preferenceManager.streamingQualityWifi.bitrate
		}
		val container = if (preferenceManager.isAdvancedTranscodingActive) {
			if (isCellular) preferenceManager.customFormatCellular else preferenceManager.customFormatWifi
		} else {
			if (isCellular) preferenceManager.streamingQualityCellular.container else preferenceManager.streamingQualityWifi.container
		}
		return sessionManager.api.getStreamUrl(id, bitrate, container?.takeIf { it.isNotBlank() })
			.toUri()
			.buildUpon()
			.appendQueryParameter("estimateContentLength", "true")
			.build()
	}

	private fun setupController() = launchInView {
		controller?.apply {
			addListener(object : Player.Listener {
				override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
					updatePlaybackState()
					skipUnavailableSong()
					checkAndAutoFillQueue()
				}


				override fun onIsPlayingChanged(isPlaying: Boolean) {
					if (isPlaying) startProgressLoop()

					val currentSong = _uiState.value.currentSong
					val displayArtist = currentSong?.artists?.joinToString { it.name }
						?.ifBlank { currentSong.artistName }

					val intent =
						Intent("${application.packageName}.NOW_PLAYING_UPDATED").apply {
							setPackage(application.packageName)
							putExtra("isPlaying", isPlaying)
							putExtra(
								"title",
								currentSong?.title ?: "Unknown song"
							)
							putExtra(
								"artist",
								displayArtist ?: "Unknown artist"
							)
							putExtra(
								"artUrl",
								currentSong?.coverArtId?.let {
									sessionManager.resolveCoverArtUrl(it)
								})
						}

					application.sendBroadcast(intent)
					updatePlaybackState()
				}

				override fun onPlaybackStateChanged(playbackState: Int) {
					_uiState.update { it.copy(isLoading = playbackState == Player.STATE_BUFFERING) }
					updatePlaybackState()
				}

				override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
					_uiState.update { it.copy(isShuffleEnabled = shuffleModeEnabled) }
				}

				override fun onRepeatModeChanged(repeatMode: Int) {
					_uiState.update { it.copy(repeatMode = repeatMode) }
				}

				override fun onTracksChanged(tracks: Tracks) {
					updatePlaybackProperties(tracks)
				}

				override fun onTimelineChanged(timeline: Timeline, reason: Int) {
					updatePlaybackState()
				}
			})
			updatePlaybackState()
			updatePlaybackProperties(currentTracks)

			downloadManager.allDownloads.first()
			pendingSyncState?.let { state ->
				syncPlayerWithState(state)
				pendingSyncState = null
			}
			checkAndAutoFillQueue()
		}
	}

	/**
	 * strategically skip around in the queue until the
	 * current song is available while avoiding infinite
	 * loops
	 *
	 * this **INTENTIONALLY** does not check for if the song
	 * is not downloaded and if the device is offline
	 *
	 * this used to check for that but because there have
	 * been cases where the device is falsely identified
	 * as being offline that's no longer the case, so we
	 * just try to play the song anyway
	 */
	private fun skipUnavailableSong() {
		val currentSong = _uiState.value.currentSong ?: return
		if (!isExplicit(currentSong)) return
		Logger.i("MediaPlayer", "trying to skip unavailable song")
		val queue = _uiState.value.queue
		val currentIdx = queue.indexOf(currentSong)

		// look for the next available song, wrapping around, but stop before
		// we loop back past our own starting point
		val nextAvailableIdx = (1..queue.size)
			.map { offset -> (currentIdx + offset) % queue.size }
			.firstOrNull { index -> !isExplicit(queue[index]) }

		if (nextAvailableIdx == null) {
			Logger.i(
				"MediaPlayer",
				"pausing because this song is unavailable and there isn't anything to skip to"
			)
			controller?.pause()
			return
		}

		if (nextAvailableIdx <= currentIdx) {
			Logger.i(
				"MediaPlayer",
				"skipping and pausing because the last song in the queue was unavailable"
			)
			controller?.seekTo(nextAvailableIdx, 0L)
			controller?.pause()
		} else {
			// just skip to the next song
			controller?.seekTo(nextAvailableIdx, 0L)
		}
	}

	private fun refreshCurrentCollection(albumId: String) {
		if (loadingCollectionId == albumId) return
		loadingCollectionId = albumId

		viewModelScope.launch {
			runCatching {
				val album = albumDao.getAlbumById(albumId)

				_uiState.update { it.copy(currentCollection = album?.toDomainModel()) }
			}.onFailure {
				loadingCollectionId = null
			}
		}
	}

	private fun updatePlaybackState() {
		val controller = controller ?: return
		val index = controller.currentMediaItemIndex
		if (index == C.INDEX_UNSET) return

		val currentSong = _uiState.value.queue.getOrNull(index)

		val derivedCollection = currentSong?.let { song ->
			val stateCollection = _uiState.value.currentCollection

			if (stateCollection?.id == song.albumId.toString()) {
				stateCollection
			} else {
				refreshCurrentCollection(song.albumId.toString())
				null
			}
		}

		_uiState.update { state ->
			state.copy(
				currentIndex = index,
				currentSong = currentSong,
				currentCollection = derivedCollection ?: state.currentCollection,
				isPaused = (!controller.playWhenReady || controller.playbackState == Player.STATE_ENDED),
				isShuffleEnabled = controller.shuffleModeEnabled,
				repeatMode = controller.repeatMode
			)
		}
		applyAudioGain()
		updateProgress()
	}

	private fun applyAudioGain() = audioGainManager.apply {
		setAmplifierValues(preferenceManager.rgAmpGain, preferenceManager.ampGain)

		if (preferenceManager.replayGainMode != ReplayGainMode.Off) {
			val currentSong = _uiState.value.currentSong
			val replayGain = currentSong?.replayGain

			if (replayGain != null) {
				setReplayGainMetadata(replayGain)

				if (preferenceManager.replayGainMode != ReplayGainMode.Dynamic) {
					applyGainMode(preferenceManager.replayGainMode)
				} else {
					if (_uiState.value.queue.all { it.albumId == currentSong.albumId }) {
						applyGainMode(ReplayGainMode.Album)
					} else {
						applyGainMode(ReplayGainMode.Track)
					}
				}

				return@apply
			}

			setReplayGainMetadata(null)
			return@apply
		}
		resetGain()
	}

	override fun syncPlayerWithState(state: PlayerUiState) = launchInView {
		val player = controller

		if (player == null) {
			pendingSyncState = state
			return@launchInView
		}

		if (state.queue.isEmpty() || player.mediaItemCount > 0) {
			updatePlaybackState()
			return@launchInView
		}

		val resolvedQueue = withContext(Dispatchers.IO) { state.queue.map { resolveSongForPlayback(it) ?: it } }

		val mediaItems = withContext(Dispatchers.Default) {
            resolvedQueue.map { it.toMediaItem() }
        }

		player.apply {
			setMediaItems(mediaItems)

			shuffleModeEnabled = state.isShuffleEnabled
			repeatMode = state.repeatMode
			playbackParameters = PlaybackParameters(state.playbackSpeed)
		}

		val index = if (state.currentIndex in mediaItems.indices) state.currentIndex else 0

		val songDurationMs = resolvedQueue.getOrNull(index)?.duration?.inWholeMilliseconds ?: 0L

		val position = if (songDurationMs > 0) {
			(state.progress * songDurationMs).toLong()
		} else {
			0L
		}

		player.seekTo(index, position)
		player.prepare()
		if (!state.isPaused) {
			player.play()
		}
		resolvedQueue.filter { YouTubeRepository.isYouTubeSong(it) }.forEach { watchForLibraryMatch(it) }
	}

	private fun startProgressLoop() = launchInView {
		while (controller?.isPlaying == true) {
			val player = controller ?: break
			val duration = player.duration
			if (duration > 0) {
				val progress =
					(player.currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
				_uiState.update { it.copy(progress = progress) }
			}
            delay(200.milliseconds)
		}
	}

	private fun updateProgress() = controller?.also { player ->
		val duration = player.duration
		if (duration > 0) {
			val pos = player.currentPosition
			val progress = (pos.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
			_uiState.update { it.copy(progress = progress) }
		}
	}

	@OptIn(UnstableApi::class)
	private fun updatePlaybackProperties(tracks: Tracks) {
		val audioGroup =
			tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
		if (audioGroup != null) {
			for (i in 0 until audioGroup.length) {
				if (audioGroup.isTrackSelected(i)) {
					val format = audioGroup.getTrackFormat(i)
					Logger.i("MediaPlayer", "active track format: $format")
					_uiState.update { state ->
						state.copy(
							playbackBitrate = format.bitrate.takeIf { it > 0 },
							playbackSampleRate = format.sampleRate.takeIf { it > 0 },
							playbackMimeType = format.sampleMimeType
						)
					}
					break
				}
			}
		}
	}

	override fun addToQueueSingle(song: DomainSong, notify: Boolean) = launchInView {
		if (YouTubeRepository.isYouTubeSong(song)) {
			if (sendToMeTubeNow(song)) {
				watchForLibraryArrival(song)
				snackBarManager.notify(R.string.notice_download_started)
			}
			return@launchInView
		}
		val resolved = resolveSongForPlayback(song) ?: return@launchInView
		controller?.addMediaItem(withContext(Dispatchers.Default) { resolved.toMediaItem() })
		_uiState.update { state ->
			val newQueue = state.queue + resolved
			state.copy(
				queue = newQueue,
				currentIndex = if (state.currentIndex == -1) 0 else state.currentIndex,
				currentSong = if (state.currentIndex == -1) resolved else state.currentSong
			)
		}
		if (notify) snackBarManager.notifyAddedToQueue()
	}

	override fun addToQueue(collection: DomainSongCollection, notify: Boolean) {
		addToQueue(
			if (collection is DomainAlbum) collection.songs.sortedWith(
				compareBy(
					{ it.discNumber },
					{ it.trackNumber }
				)
			) else collection.songs,
			notify
		)
	}

	override fun addToQueue(songs: List<DomainSong>, notify: Boolean) = launchInView {
		val (ytSongs, ready) = songs.partition { YouTubeRepository.isYouTubeSong(it) }
		ytSongs.forEach { if (sendToMeTubeNow(it)) watchForLibraryArrival(it) }
		if (ready.isEmpty()) {
			if (ytSongs.isNotEmpty()) snackBarManager.notify(R.string.notice_download_started)
			return@launchInView
		}
		val items = withContext(Dispatchers.Default) { ready.map { it.toMediaItem() } }
		controller?.addMediaItems(items)
		_uiState.update { state ->
			val newQueue = state.queue + ready
			state.copy(
				queue = newQueue,
				currentIndex = if (state.currentIndex == -1) 0 else state.currentIndex,
				currentSong = if (state.currentIndex == -1) ready.firstOrNull() else state.currentSong
			)
		}
		if (notify) snackBarManager.notifyAddedToQueue()
	}

	override fun removeFromQueue(index: Int) = launchInView {
		controller?.removeMediaItem(index)
		_uiState.update { state ->
			val newQueue = state.queue.toMutableList().apply { removeAt(index) }
			val newIndex = when {
				index < state.currentIndex -> state.currentIndex - 1
				index == state.currentIndex -> if (newQueue.isEmpty()) -1 else state.currentIndex.coerceAtMost(
					newQueue.size - 1
				)

				else -> state.currentIndex
			}
			state.copy(
				queue = newQueue,
				currentIndex = newIndex,
				currentSong = if (newIndex == -1) null else newQueue[newIndex]
			)
		}
	}

	override fun moveQueueItem(fromIndex: Int, toIndex: Int) = launchInView {
		controller?.moveMediaItem(fromIndex, toIndex)
		_uiState.update { state ->
			val newQueue = state.queue.toMutableList().apply {
				val item = removeAt(fromIndex)
				add(toIndex, item)
			}
			val newIndex = when (state.currentIndex) {
				fromIndex -> toIndex
				in (fromIndex + 1)..toIndex -> state.currentIndex - 1
				in toIndex until fromIndex -> state.currentIndex + 1
				else -> state.currentIndex
			}
			state.copy(
				queue = newQueue,
				currentIndex = newIndex,
				currentSong = if (newIndex == -1) null else newQueue[newIndex]
			)
		}
	}

	override fun clearQueue() = launchInView {
		_uiState.update {
			it.copy(
				queue = emptyList(),
				currentSong = null,
				currentIndex = -1,
				progress = 0f
			)
		}
		controller?.clearMediaItems()
	}

	override fun playAt(index: Int) = launchInView {
		controller?.let { player ->
			if (index in 0 until player.mediaItemCount) {
				player.seekTo(index, 0L)
				player.play()
			}
		}
	}

	override fun playNextSingle(song: DomainSong) = launchInView {
		if (YouTubeRepository.isYouTubeSong(song)) {
			if (sendToMeTubeNow(song)) {
				watchForLibraryArrival(song)
				snackBarManager.notify(R.string.notice_download_started)
			}
			return@launchInView
		}
		val resolved = resolveSongForPlayback(song) ?: return@launchInView
		controller?.addMediaItem(
			_uiState.value.currentIndex + 1,
            withContext(Dispatchers.Default) { resolved.toMediaItem() }
		)
		_uiState.update { state ->
			val newQueue =
				if (state.queue.isEmpty())
					state.queue + resolved
				else
					state.queue.slice(0..state.currentIndex) + resolved + state.queue.slice(state.currentIndex + 1..<state.queue.size)
			state.copy(
				queue = newQueue,
				currentIndex = if (state.currentIndex == -1) 0 else state.currentIndex,
				currentSong = if (state.currentIndex == -1) resolved else state.currentSong
			)
		}
		snackBarManager.notifyPlayNext()
	}

	override fun playNext(collection: DomainSongCollection) = launchInView {
		val sorted = if (collection is DomainAlbum) collection.songs.sortedWith(
			compareBy({ it.discNumber }, { it.trackNumber })
		) else collection.songs
		val (ytSongs, ready) = sorted.partition { YouTubeRepository.isYouTubeSong(it) }
		ytSongs.forEach { if (sendToMeTubeNow(it)) watchForLibraryArrival(it) }
		if (ready.isEmpty()) {
			if (ytSongs.isNotEmpty()) snackBarManager.notify(R.string.notice_download_started)
			return@launchInView
		}
		val items = withContext(Dispatchers.Default) { ready.map { it.toMediaItem() } }
		controller?.addMediaItems(_uiState.value.currentIndex + 1, items)
		_uiState.update { state ->
			val newQueue =
				if (state.queue.isEmpty())
					state.queue + ready
				else
					state.queue.slice(0..state.currentIndex) + ready + state.queue.slice(
						state.currentIndex + 1..<state.queue.size
					)
			state.copy(
				queue = newQueue,
				currentIndex = if (state.currentIndex == -1) 0 else state.currentIndex,
				currentSong = if (state.currentIndex == -1) ready.firstOrNull() else state.currentSong
			)
		}
		snackBarManager.notifyPlayNext()
	}

	override fun playRadio(radio: DomainRadio) = launchInView {
		val radioId = "radio_${radio.name.hashCode()}"

		val dummyRadioSong = DomainSong(
            id = radioId,
            title = radio.name,
            artistName = "Live Radio",
            albumId = "radio_album",
            albumTitle = "Live Stream",
            duration = Duration.ZERO,
            trackNumber = 1,
            coverArtId = radio.coverArtId,
            artistId = "",
            parentId = "",
            comment = null,
            discNumber = null,
            isrc = emptyList(),
            year = null,
            genre = null,
            genres = emptyList(),
            moods = emptyList(),
            bpm = null,
            contributors = emptyList(),
            playCount = 0,
            userRating = 0,
            averageRating = null,
            bitRate = null,
            bitDepth = null,
            sampleRate = null,
            audioChannelCount = null,
            replayGain = null,
            fileSize = 0,
            fileExtension = "",
            mimeType = "",
            filePath = radio.streamUrl,
            starredAt = null,
            musicBrainzId = null,
            explicitStatus = DomainExplicitStatus.Unknown,
            artists = emptyList(),
            albumArtists = emptyList(),
            isExternal = false
        )

		val metadata = MediaMetadata.Builder()
			.setTitle(radio.name)
			.setArtist("Live Radio")
			.setIsPlayable(true)
			.build()

		val mediaItem = MediaItem.Builder()
			.setUri(radio.streamUrl)
			.setMediaId("radio_${radio.name.hashCode()}")
			.setMediaMetadata(metadata)
			.setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
			.build()

		controller?.let { player ->
			player.stop()
			player.clearMediaItems()
			player.setMediaItem(mediaItem)
			player.prepare()
			player.play()
		}

		_uiState.update { state ->
			state.copy(
				queue = listOf(dummyRadioSong),
				currentIndex = 0,
				currentSong = dummyRadioSong,
				isLoading = true
			)
		}
	}

	override fun shufflePlay(collection: DomainSongCollection) = launchInView {
		val (ytSongs, readyAll) = collection.songs.partition { YouTubeRepository.isYouTubeSong(it) }
		ytSongs.forEach { if (sendToMeTubeNow(it)) watchForLibraryArrival(it) }
		if (readyAll.isEmpty()) {
			if (ytSongs.isNotEmpty()) snackBarManager.notify(R.string.notice_download_started)
			return@launchInView
		}
		val shuffledSongs = readyAll.shuffled()
		val mediaItems = withContext(Dispatchers.Default) {
			shuffledSongs.map { it.toMediaItem() }
		}

		controller?.let { player ->
			player.shuffleModeEnabled = false
			player.setMediaItems(mediaItems, 0, 0L)
			player.prepare()
			player.play()
		}

		_uiState.update { state ->
			state.copy(
				queue = shuffledSongs,
				currentIndex = 0,
				currentSong = shuffledSongs.firstOrNull()
			)
		}
	}

	override fun pause() = launchInView(true) {
		controller?.pause()
	}

	override fun resume() = launchInView(true) {
		controller?.let { player ->
			if (player.playbackState == Player.STATE_ENDED) {
				player.seekTo(0, 0L)
			}
			player.play()
		}
	}

	override fun next() = launchInView(true) {
		if (controller?.hasNextMediaItem() == true) controller?.seekToNextMediaItem()
	}

	override fun previous() = launchInView(true) {
		val controller = controller ?: return@launchInView
		if (controller.hasPreviousMediaItem() && controller.currentPosition <= 1000) {
			controller.seekToPreviousMediaItem()
		} else {
			controller.seekTo(0)
		}
	}

	override fun toggleShuffle() {
		viewModelScope.launch {
			controller?.let { player ->
				player.shuffleModeEnabled = !player.shuffleModeEnabled
			}
		}
	}

	override fun toggleRepeat() = launchInView {
		controller?.let { player ->
			player.repeatMode = when (player.repeatMode) {
				Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
				Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
				else -> Player.REPEAT_MODE_OFF
			}
		}
	}

	override fun seek(normalized: Float) = launchInView(true) {
		controller?.let {
			val target = (it.duration * normalized).toLong()
			it.seekTo(target)
			_uiState.update { state ->
				state.copy(progress = normalized)
			}
		}

	}

	override fun onCleared() = launchInView {
		super.onCleared()
		controllerFuture?.let { MediaController.releaseFuture(it) }
	}

	override fun setPlaybackSpeed(value: Float) = launchInView {
		controller?.setPlaybackSpeed(value)
		_uiState.update { it.copy(playbackSpeed = value) }
	}

	/**
	 * Best-effort background send of a played YouTube song to the server's
	 * MeTube instance, so tapping a fallback entry both streams instantly
	 * and fetches the file server-side. Never blocks or breaks playback.
	 */
	private val ytPending = mutableSetOf<String>()

	private suspend fun sendToMeTubeNow(song: DomainSong): Boolean {
		if (!YouTubeRepository.isYouTubeSong(song)) return false
		if (youTubeRepository.effectiveMetubeBase().isBlank()) return false
		// Never queue the same song twice server-side.
		if (!ytPending.add(song.id)) return true
		return try {
			val watchUrl = song.filePath?.takeIf { it.contains("watch?v=") }
				?: YouTubeRepository.watchUrl(YouTubeRepository.videoIdOf(song))
			when (withContext(Dispatchers.IO) { youTubeRepository.sendToMeTube(watchUrl) }) {
				YouTubeRepository.MeTubeSendResult.Ok -> {
					Logger.i("MediaPlayer", "sent ${song.id} to MeTube")
					true
				}
				YouTubeRepository.MeTubeSendResult.WrongPassword -> {
					Logger.w("MediaPlayer", "MeTube rejected credentials for ${song.id}")
					snackBarManager.notify(R.string.info_wrong_download_password)
					ytPending.remove(song.id)
					false
				}
				YouTubeRepository.MeTubeSendResult.Failed -> {
					Logger.w("MediaPlayer", "MeTube send failed for ${song.id}")
					ytPending.remove(song.id)
					false
				}
			}
		} catch (e: Exception) {
			if (e is kotlinx.coroutines.CancellationException) throw e
			Logger.w("MediaPlayer", "MeTube send failed for ${song.id}", e)
			ytPending.remove(song.id)
			false
		}
	}

	private val ytMatchJobs = mutableMapOf<String, Job>()

	/**
	 * After a fallback song has been handed to MeTube, poll the server until
	 * Navidrome has scanned the download, then swap the external queue item
	 * for the real library song (no manual resync or re-search needed).
	 */
	private fun watchForLibraryMatch(ytSong: DomainSong) {
		if (!YouTubeRepository.isYouTubeSong(ytSong)) return
		if (ytMatchJobs.containsKey(ytSong.id)) return
		ytMatchJobs[ytSong.id] = viewModelScope.launch {
			try {
				// Check immediately: the song may already be scanned
				// (e.g. downloaded earlier or manual refresh). This runs
				// before the queue is guaranteed to hold the item, so a
				// missing entry here must not stop the watch.
				withContext(Dispatchers.IO) {
					searchRepository.findLibraryMatchFor(ytSong)
				}?.let {
					swapYouTubeWithLibrary(ytSong.id, it)
					return@launch
				}
				if (_uiState.value.queue.none { it.id == ytSong.id }) return@launch
				val deadline = System.currentTimeMillis() + 20 * 60 * 1000L
				while (System.currentTimeMillis() < deadline) {
					delay(10_000L)
					if (_uiState.value.queue.none { it.id == ytSong.id }) return@launch
					val match = withContext(Dispatchers.IO) {
						searchRepository.findLibraryMatchFor(ytSong)
					} ?: continue
					swapYouTubeWithLibrary(ytSong.id, match)
					return@launch
				}
				Logger.i("MediaPlayer", "gave up waiting for library match for ${ytSong.id}")
				if (_uiState.value.currentSong?.id == ytSong.id && controller?.mediaItemCount == 0) {
					failPlayback("The download never arrived in Navidrome.")
				}
			} catch (e: Exception) {
				if (e is kotlinx.coroutines.CancellationException) throw e
				Logger.w("MediaPlayer", "library match watch failed for ${ytSong.id}", e)
			} finally {
				ytMatchJobs.remove(ytSong.id)
				ytPending.remove(ytSong.id)
			}
		}
	}

	/**
	 * Background arrivals (queue/album/shuffle adds): the song was never
	 * queued, so on match it is appended — and starts playing if nothing
	 * else is. Queue and controller stay 1:1 at all times.
	 */
	private fun watchForLibraryArrival(ytSong: DomainSong) {
		if (!YouTubeRepository.isYouTubeSong(ytSong)) return
		if (ytMatchJobs.containsKey(ytSong.id)) return
		ytMatchJobs[ytSong.id] = viewModelScope.launch {
			try {
				val deadline = System.currentTimeMillis() + 20 * 60 * 1000L
				while (System.currentTimeMillis() < deadline) {
					delay(10_000L)
					val match = withContext(Dispatchers.IO) {
						searchRepository.findLibraryMatchFor(ytSong)
					} ?: continue
					if (_uiState.value.queue.any { it.id == ytSong.id }) {
						swapYouTubeWithLibrary(ytSong.id, match)
					} else {
						appendLibraryMatch(match)
					}
					return@launch
				}
				Logger.i("MediaPlayer", "gave up waiting for library match for ${ytSong.id}")
			} catch (e: Exception) {
				if (e is kotlinx.coroutines.CancellationException) throw e
				Logger.w("MediaPlayer", "library arrival watch failed for ${ytSong.id}", e)
			} finally {
				ytMatchJobs.remove(ytSong.id)
				ytPending.remove(ytSong.id)
			}
		}
	}

	private fun appendLibraryMatch(match: DomainSong) = launchInView {
		val queue = _uiState.value.queue
		if (queue.any { it.id == match.id }) return@launchInView
		val index = queue.size
		val startPlaying = index == 0
		val item = withContext(Dispatchers.Default) { match.toMediaItem() }
		controller?.addMediaItem(item)
		_uiState.update { state ->
			state.copy(
				queue = state.queue + match,
				currentIndex = if (state.currentIndex == -1) index else state.currentIndex,
				currentSong = if (state.currentIndex == -1) match else state.currentSong,
				isLoading = if (state.currentIndex == -1) true else state.isLoading
			)
		}
		if (startPlaying) {
			controller?.let { player ->
				player.seekTo(index, 0L)
				player.prepare()
				player.play()
			}
		} else {
			snackBarManager.notifyAddedToQueue()
		}
	}

	private fun swapYouTubeWithLibrary(ytId: String, match: DomainSong) = launchInView {
		try {
			val queue = _uiState.value.queue
			val indices = queue.indices.filter { queue[it].id == ytId }
			if (indices.isEmpty()) return@launchInView
			val player = controller
			val currentIdx = _uiState.value.currentIndex
			val isCurrent = currentIdx in indices &&
				_uiState.value.currentSong?.id == ytId

			if (player != null) {
				val replacement = withContext(Dispatchers.Default) { match.toMediaItem() }
				if (player.mediaItemCount == 0) {
					// Waiting state (stream never resolved): load it fresh.
					if (isCurrent) {
						player.setMediaItem(replacement)
						player.prepare()
						player.play()
					}
				} else {
					indices.forEach { index ->
						if (index in 0 until player.mediaItemCount) {
							player.replaceMediaItem(index, replacement)
						}
					}
					if (isCurrent) {
						// Fresh library source: restart it from the beginning and
						// make sure it actually plays, recovering even if the old
						// YouTube stream had errored.
						player.seekTo(currentIdx, 0L)
						player.prepare()
						player.play()
					}
				}
			}
			_uiState.update { state ->
				state.copy(
					queue = state.queue.map { if (it.id == ytId) match else it },
					currentSong = if (state.currentSong?.id == ytId) match else state.currentSong
				)
			}
			Logger.i("MediaPlayer", "swapped $ytId for library song ${match.id}")
		} catch (e: Exception) {
			if (e is kotlinx.coroutines.CancellationException) throw e
			Logger.w("MediaPlayer", "library swap failed for $ytId", e)
		}
	}

	private suspend fun resolveSongForPlayback(song: DomainSong): DomainSong? {
		// YouTube entries never resolve to direct streams: they only play
		// once fetched into the library via MeTube.
		if (!YouTubeRepository.isYouTubeSong(song)) return song
		return null
	}

	private fun failPlayback(message: String) = launchInView(true) {
		controller?.let { player ->
			player.stop()
			player.clearMediaItems()
		}
		_uiState.update { state ->
			state.copy(
				queue = emptyList(),
				currentSong = null,
				currentIndex = -1,
				progress = 0f,
				isLoading = false
			)
		}
		notifyEvent(PlayerEvent.PlaybackFailed(message))
	}

	override fun playNow(song: DomainSong) = launchInView {
		if (!YouTubeRepository.isYouTubeSong(song)) {
			clearQueue()
			addToQueueSingle(song, notify = false)
			playAt(0)
			checkAndAutoFillQueue()
			return@launchInView
		}
		_uiState.update { it.copy(isLoading = true) }
		// No YouTube streaming: hand it to MeTube and wait for the library
		// copy, then play. Only a MeTube failure ends in an error.
		if (sendToMeTubeNow(song)) {
			controller?.let { player ->
				player.stop()
				player.clearMediaItems()
			}
			_uiState.update { state ->
				state.copy(queue = listOf(song), currentIndex = 0, currentSong = song, isLoading = true)
			}
			watchForLibraryMatch(song)
			return@launchInView
		}
		failPlayback("Couldn't load this YouTube song.")
	}

	override fun playNow(collection: DomainSongCollection, startIndex: Int) {
		val songs = if (collection is DomainAlbum) collection.songs.sortedWith(
			compareBy({ it.discNumber }, { it.trackNumber })
		) else collection.songs
		playNow(songs, startIndex)
	}

	override fun playNow(songs: List<DomainSong>, startIndex: Int) = launchInView {
		if (songs.none { YouTubeRepository.isYouTubeSong(it) }) {
			if (songs.isEmpty()) {
				clearQueue()
				return@launchInView
			}
			clearQueue()
			addToQueue(songs, notify = false)
			playAt(startIndex)
			checkAndAutoFillQueue()
			return@launchInView
		}
		if (songs.size == 1) {
			// Single external song: same wait-for-library flow as tapping it.
			val only = songs[0]
			_uiState.update { it.copy(isLoading = true) }
			if (sendToMeTubeNow(only)) {
				controller?.let { player ->
					player.stop()
					player.clearMediaItems()
				}
				_uiState.update { state ->
					state.copy(queue = listOf(only), currentIndex = 0, currentSong = only, isLoading = true)
				}
				watchForLibraryMatch(only)
			} else {
				failPlayback("Couldn't load this YouTube song.")
			}
			return@launchInView
		}
		// Mixed queue: play what's already in the library now; external
		// tracks are fetched in the background and join as they arrive.
		val ready = songs.filter { !YouTubeRepository.isYouTubeSong(it) }
		songs.filter { YouTubeRepository.isYouTubeSong(it) }.forEach {
			if (sendToMeTubeNow(it)) watchForLibraryArrival(it)
		}
		if (ready.isEmpty()) {
			controller?.let { player ->
				player.stop()
				player.clearMediaItems()
			}
			_uiState.update {
				it.copy(queue = emptyList(), currentSong = null, currentIndex = -1, isLoading = false)
			}
			snackBarManager.notify(R.string.notice_download_started)
			return@launchInView
		}
		val safeStart = songs.take(startIndex.coerceIn(songs.indices))
			.count { !YouTubeRepository.isYouTubeSong(it) }
			.coerceIn(ready.indices)
		_uiState.update { it.copy(isLoading = true) }
		val items = withContext(Dispatchers.Default) { ready.map { it.toMediaItem() } }
		controller?.let { player ->
			player.stop()
			player.clearMediaItems()
			player.setMediaItems(items, safeStart, 0L)
			player.prepare()
			player.play()
		}
		_uiState.update { state ->
			state.copy(
				queue = ready,
				currentIndex = safeStart,
				currentSong = ready.getOrNull(safeStart),
				isLoading = true
			)
		}
		checkAndAutoFillQueue()
	}

	private fun DomainSong.toMediaItem(): MediaItem {
		val displayArtist = artists.joinToString { it.name }.ifBlank { artistName }
		val albumArtistName = albumArtists.joinToString { it.name }.ifBlank { artistName }

		val metadataBuilder = MediaMetadata.Builder()
			.setTitle(title)
			.setSubtitle(displayArtist)
			.setArtist(displayArtist)
			.setAlbumArtist(albumArtistName)
			.setAlbumTitle(albumTitle)
			.setDurationMs(duration.inWholeMilliseconds)
			.setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)

		metadataBuilder.setArtworkUri(
			coverArtId?.let { sessionManager.resolveCoverArtUrl(it).toUri() }
		)

		val metadata = metadataBuilder.build()

		val uri = when {
			id.startsWith("radio_") && !filePath.isNullOrEmpty() -> {
				filePath.toUri()
			}

			YouTubeRepository.isYouTubeSongId(id) && !filePath.isNullOrEmpty() -> {
				filePath.toUri()
			}

			else -> {
				val localPath = downloadManager.getDownloadedFilePath(id)
				if (localPath != null) {
					File(localPath).toUri()
				} else {
					getStreamUrl(id)
				}
			}
		}

		val builder = MediaItem.Builder()
			.setUri(uri)
			.setMediaId(id)
			.setMediaMetadata(metadata)

		if (id.startsWith("radio_")) {
			builder.setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
		}

		return builder.build()
	}
}
