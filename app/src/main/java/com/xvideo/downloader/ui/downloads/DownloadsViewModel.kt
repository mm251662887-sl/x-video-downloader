package com.xvideo.downloader.ui.downloads

import android.app.Application
import android.content.res.Resources
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xvideo.downloader.App
import com.xvideo.downloader.R
import com.xvideo.downloader.data.local.DownloadManager
import com.xvideo.downloader.data.local.database.entity.DownloadHistoryEntity
import com.xvideo.downloader.data.model.DownloadTask
import com.xvideo.downloader.data.model.M3u8Stream
import com.xvideo.downloader.data.model.VideoInfo
import com.xvideo.downloader.data.model.VideoVariant
import com.xvideo.downloader.data.remote.repository.VideoParseState
import com.xvideo.downloader.data.remote.repository.VideoRepository
import com.xvideo.downloader.util.UrlUtils
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {

    private val downloadManager = App.getInstance().downloadManager
    private val database = App.getInstance().database
    private val downloadHistoryDao = database.downloadHistoryDao()
    private val repository = VideoRepository()
    private val resources: Resources = application.resources

    // Active downloads from DownloadManager
    val activeDownloads: StateFlow<List<DownloadTask>> = downloadManager.activeDownloads

    // Download history
    val completedDownloads: Flow<List<DownloadHistoryEntity>> = downloadHistoryDao.getCompletedDownloads()

    // Video parse state for URL detection on this page
    private val _parseState = MutableStateFlow<VideoParseState>(VideoParseState.Idle)
    val parseState: StateFlow<VideoParseState> = _parseState.asStateFlow()

    // Current parsed video info
    private val _currentVideoInfo = MutableStateFlow<VideoInfo?>(null)
    val currentVideoInfo: StateFlow<VideoInfo?> = _currentVideoInfo.asStateFlow()

    // M3U8 streams for quality selection
    private val _m3u8Streams = MutableStateFlow<List<M3u8Stream>>(emptyList())
    val m3u8Streams: StateFlow<List<M3u8Stream>> = _m3u8Streams.asStateFlow()

    // Toast messages
    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    // Event: navigate to downloads list when download starts
    private val _navigateToDownloads = MutableSharedFlow<Unit>()
    val navigateToDownloads: SharedFlow<Unit> = _navigateToDownloads.asSharedFlow()

    init {
        viewModelScope.launch {
            downloadManager.downloadProgress.collect { progress ->
                if (progress.isCompleted) {
                    _toastMessage.emit(resources.getString(R.string.download_completed))
                } else if (progress.error != null) {
                    _toastMessage.emit(resources.getString(R.string.download_failed, progress.error))
                }
            }
        }
    }

    /**
     * Receive VideoInfo from HomeFragment after parsing
     */
    fun setVideoInfo(videoInfo: VideoInfo) {
        _currentVideoInfo.value = videoInfo
        _parseState.value = VideoParseState.Success(videoInfo)

        // Parse M3U8 if available
        if (videoInfo.hasM3u8Stream()) {
            viewModelScope.launch {
                val result = repository.parseM3u8Playlist(videoInfo.m3u8Url!!)
                result.onSuccess { streams ->
                    _m3u8Streams.value = streams
                }
            }
        }
    }

    /**
     * Parse a URL entered on the downloads page.
     * Supports both Twitter/X URLs (via API) and direct video URLs.
     */
    fun parseUrl(url: String) {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) {
            viewModelScope.launch {
                _toastMessage.emit(resources.getString(R.string.please_enter_url))
            }
            return
        }

        viewModelScope.launch {
            _parseState.value = VideoParseState.Loading
            _m3u8Streams.value = emptyList()

            // Check if it's a Twitter/X URL
            if (UrlUtils.isValidTwitterUrl(trimmedUrl)) {
                val normalizedUrl = UrlUtils.normalizeTwitterUrl(trimmedUrl)
                val result = repository.parseVideoFromUrl(normalizedUrl)
                result.fold(
                    onSuccess = { videoInfo ->
                        _currentVideoInfo.value = videoInfo
                        _parseState.value = VideoParseState.Success(videoInfo)
                        if (videoInfo.hasM3u8Stream()) {
                            parseM3u8Streams(videoInfo.m3u8Url!!)
                        }
                    },
                    onFailure = { error ->
                        val errorMsg = error.message ?: resources.getString(R.string.detect_failed)
                        _parseState.value = VideoParseState.Error(errorMsg)
                        _toastMessage.emit("${resources.getString(R.string.error)}: $errorMsg")
                    }
                )
            } else if (isDirectVideoUrl(trimmedUrl)) {
                // Direct video URL — create a minimal VideoInfo for download
                val variant = VideoVariant(
                    url = trimmedUrl,
                    bitrate = 2500000,
                    contentType = if (trimmedUrl.contains(".m3u8")) "application/x-mpegURL" else "video/mp4"
                )
                val videoInfo = VideoInfo(
                    tweetId = "direct_${System.currentTimeMillis()}",
                    tweetUrl = trimmedUrl,
                    authorName = resources.getString(R.string.btn_quick_download),
                    authorUsername = "direct",
                    tweetText = trimmedUrl,
                    thumbnailUrl = null,
                    videoVariants = listOf(variant),
                    gifVariants = emptyList(),
                    m3u8Url = if (trimmedUrl.contains(".m3u8")) trimmedUrl else null,
                    hasM3u8 = trimmedUrl.contains(".m3u8")
                )
                _currentVideoInfo.value = videoInfo
                _parseState.value = VideoParseState.Success(videoInfo)

                // If it's an m3u8 URL, parse the playlist
                if (trimmedUrl.contains(".m3u8")) {
                    parseM3u8Streams(trimmedUrl)
                }
            } else {
                _parseState.value = VideoParseState.Error(resources.getString(R.string.error_invalid_url_general))
                _toastMessage.emit(resources.getString(R.string.error_invalid_url_general))
            }
        }
    }

    private suspend fun parseM3u8Streams(m3u8Url: String) {
        val result = repository.parseM3u8Playlist(m3u8Url)
        result.fold(
            onSuccess = { streams -> _m3u8Streams.value = streams },
            onFailure = { /* m3u8 parse failed, direct variants may still work */ }
        )
    }

    private fun isDirectVideoUrl(url: String): Boolean {
        return url.contains(".mp4") ||
                url.contains(".m3u8") ||
                url.contains("video") ||
                url.startsWith("http://") ||
                url.startsWith("https://")
    }

    /**
     * Start downloading a specific quality variant
     */
    fun startDownload(variant: VideoVariant) {
        val videoInfo = _currentVideoInfo.value ?: return
        viewModelScope.launch {
            try {
                val taskId = downloadManager.startDownload(videoInfo, variant)
                _toastMessage.emit(resources.getString(R.string.downloading))
                _navigateToDownloads.emit(Unit)
            } catch (e: Exception) {
                val errorMsg = e.message ?: resources.getString(R.string.error_download_failed)
                _toastMessage.emit("${resources.getString(R.string.error)}: $errorMsg")
            }
        }
    }

    /**
     * Start downloading an M3U8 stream with specific quality
     */
    fun startM3u8Download(stream: M3u8Stream) {
        val videoInfo = _currentVideoInfo.value ?: return
        viewModelScope.launch {
            try {
                val variant = VideoVariant(
                    url = stream.videoUrl,
                    bitrate = (stream.bandwidth / 1000).toInt(),
                    contentType = "video/mp4"
                )
                val updatedInfo = videoInfo.copy(
                    m3u8Url = stream.videoUrl,
                    hasM3u8 = true
                )
                _currentVideoInfo.value = updatedInfo
                val taskId = downloadManager.startDownload(updatedInfo, variant)
                _toastMessage.emit(resources.getString(R.string.downloading))
                _navigateToDownloads.emit(Unit)
            } catch (e: Exception) {
                val errorMsg = e.message ?: resources.getString(R.string.error_download_failed)
                _toastMessage.emit("${resources.getString(R.string.error)}: $errorMsg")
            }
        }
    }

    /**
     * Quick download — pick best quality and start
     */
    fun quickDownload() {
        val videoInfo = _currentVideoInfo.value ?: return
        val bestVariant = videoInfo.getBestQualityVideo()
        if (bestVariant != null) {
            startDownload(bestVariant)
        } else if (videoInfo.hasM3u8Stream() && _m3u8Streams.value.isNotEmpty()) {
            startM3u8Download(_m3u8Streams.value.first())
        } else {
            viewModelScope.launch {
                _toastMessage.emit(resources.getString(R.string.error_no_video))
            }
        }
    }

    // Download controls
    fun pauseDownload(taskId: String) {
        downloadManager.pauseDownload(taskId)
    }

    fun resumeDownload(taskId: String) {
        downloadManager.resumeDownload(taskId)
    }

    fun cancelDownload(taskId: String) {
        downloadManager.cancelDownload(taskId)
    }

    fun deleteDownload(taskId: String) {
        downloadManager.deleteDownload(taskId)
    }

    fun clearHistory() {
        viewModelScope.launch {
            downloadHistoryDao.clearAll()
        }
    }

    /**
     * Clear the parsed video state (reset to idle)
     */
    fun clearParseState() {
        _parseState.value = VideoParseState.Idle
        _currentVideoInfo.value = null
        _m3u8Streams.value = emptyList()
    }
}
