package com.xvideo.downloader.ui.downloads

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.xvideo.downloader.R
import com.xvideo.downloader.data.local.database.entity.DownloadHistoryEntity
import com.xvideo.downloader.data.model.DownloadTask
import com.xvideo.downloader.data.model.DownloadTaskState
import com.xvideo.downloader.data.model.M3u8Stream
import com.xvideo.downloader.data.model.VideoInfo
import com.xvideo.downloader.data.model.VideoVariant
import com.xvideo.downloader.data.remote.repository.VideoParseState
import com.xvideo.downloader.databinding.FragmentDownloadsBinding
import com.xvideo.downloader.ui.player.PlayerActivity
import com.xvideo.downloader.util.FileUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DownloadsViewModel by viewModels()
    private lateinit var activeAdapter: ActiveDownloadsAdapter
    private lateinit var historyAdapter: DownloadHistoryAdapter

    // Track selected quality for download
    private var selectedVariant: VideoVariant? = null
    private var selectedM3u8Stream: M3u8Stream? = null

    companion object {
        const val ARG_VIDEO_INFO = "arg_video_info"

        fun newInstance(videoInfo: VideoInfo? = null): DownloadsFragment {
            return DownloadsFragment().apply {
                videoInfo?.let {
                    arguments = Bundle().apply {
                        putParcelable(ARG_VIDEO_INFO, it)
                    }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerViews()
        setupUrlInput()
        observeState()

        // Check if VideoInfo was passed from HomeFragment
        val videoInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            arguments?.getParcelable(ARG_VIDEO_INFO, VideoInfo::class.java)
        } else {
            @Suppress("DEPRECATION")
            arguments?.getParcelable(ARG_VIDEO_INFO)
        }
        videoInfo?.let { viewModel.setVideoInfo(it) }

        // Auto-paste from clipboard if URL field is empty
        if (binding.etVideoUrl.text.isNullOrEmpty()) {
            pasteFromClipboardIfNeeded()
        }
    }

    private fun setupRecyclerViews() {
        activeAdapter = ActiveDownloadsAdapter(
            onPause = { viewModel.pauseDownload(it.id) },
            onResume = { viewModel.resumeDownload(it.id) },
            onCancel = { viewModel.cancelDownload(it.id) },
            onPlay = { task ->
                openPlayer(task.videoInfo.videoVariants.firstOrNull()?.url
                    ?: return@ActiveDownloadsAdapter)
            }
        )

        historyAdapter = DownloadHistoryAdapter(
            onPlay = { item ->
                item.filePath?.let { path ->
                    if (FileUtils.fileExists(path)) {
                        openLocalFile(path)
                    } else {
                        showSnackbar(getString(R.string.file_not_found))
                    }
                }
            },
            onDelete = { viewModel.deleteDownload(it.id) },
            onShare = { shareFile(it) }
        )

        binding.rvActiveDownloads.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = activeAdapter
        }

        binding.rvHistory.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = historyAdapter
        }

        binding.swipeRefresh.setOnRefreshListener {
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun setupUrlInput() {
        // Detect button
        binding.btnDetect.setOnClickListener {
            val url = binding.etVideoUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                viewModel.parseUrl(url)
            } else {
                showSnackbar(getString(R.string.please_enter_url))
            }
        }

        // Close video resource card
        binding.btnCloseResource.setOnClickListener {
            viewModel.clearParseState()
        }

        // Download button
        binding.btnDownload.setOnClickListener {
            if (selectedM3u8Stream != null) {
                viewModel.startM3u8Download(selectedM3u8Stream!!)
            } else if (selectedVariant != null) {
                viewModel.startDownload(selectedVariant!!)
            } else {
                viewModel.quickDownload()
            }
        }

        // Long press to paste
        binding.etVideoUrl.setOnLongClickListener {
            pasteFromClipboard()
            true
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe parse state
                launch {
                    viewModel.parseState.collectLatest { state ->
                        when (state) {
                            is VideoParseState.Idle -> {
                                binding.progressDetect.isVisible = false
                                binding.cardVideoResource.isVisible = false
                                binding.tvError.isVisible = false
                            }
                            is VideoParseState.Loading -> {
                                binding.progressDetect.isVisible = true
                                binding.cardVideoResource.isVisible = false
                                binding.tvError.isVisible = false
                                binding.btnDetect.isEnabled = false
                            }
                            is VideoParseState.Success -> {
                                binding.progressDetect.isVisible = false
                                binding.btnDetect.isEnabled = true
                                showVideoResources(state.videoInfo)
                            }
                            is VideoParseState.Error -> {
                                binding.progressDetect.isVisible = false
                                binding.btnDetect.isEnabled = true
                                binding.cardVideoResource.isVisible = false
                                binding.tvError.isVisible = true
                                binding.tvError.text = state.message
                            }
                        }
                    }
                }

                // Observe M3U8 streams for quality options
                launch {
                    viewModel.m3u8Streams.collectLatest { streams ->
                        if (streams.isNotEmpty()) {
                            updateQualityChips(m3u8Streams = streams)
                        }
                    }
                }

                // Observe active downloads
                launch {
                    viewModel.activeDownloads.collectLatest { downloads ->
                        activeAdapter.submitList(downloads)
                        binding.tvActiveEmpty.isVisible = downloads.isEmpty()
                        binding.rvActiveDownloads.isVisible = downloads.isNotEmpty()
                    }
                }

                // Observe completed downloads
                launch {
                    viewModel.completedDownloads.collectLatest { history ->
                        historyAdapter.submitList(history)
                        binding.tvHistoryEmpty.isVisible = history.isEmpty()
                        binding.rvHistory.isVisible = history.isNotEmpty()
                    }
                }

                // Observe toast messages
                launch {
                    viewModel.toastMessage.collectLatest { message ->
                        showSnackbar(message)
                    }
                }
            }
        }
    }

    private fun showVideoResources(videoInfo: VideoInfo) {
        binding.cardVideoResource.isVisible = true
        binding.tvError.isVisible = false

        // Set video info
        binding.tvVideoAuthor.text = if (videoInfo.authorUsername != "direct") {
            "${videoInfo.authorName} (@${videoInfo.authorUsername})"
        } else {
            videoInfo.authorName
        }
        binding.tvVideoTweet.text = videoInfo.tweetText

        // Build quality chips from video variants
        val variants = videoInfo.getAvailableQualities()
        if (variants.isNotEmpty()) {
            updateQualityChips(variants = variants)
            // Default select best quality
            selectedVariant = variants.first()
            selectedM3u8Stream = null
        }

        binding.btnDownload.isEnabled = true
    }

    private fun updateQualityChips(
        variants: List<VideoVariant> = emptyList(),
        m3u8Streams: List<M3u8Stream> = emptyList()
    ) {
        binding.chipGroupQuality.removeAllViews()

        // Add MP4 variant chips
        for (variant in variants) {
            val chip = Chip(requireContext()).apply {
                text = variant.getQualityLabel()
                isCheckable = true
                isChecked = variant == selectedVariant
                setOnClickListener {
                    selectedVariant = variant
                    selectedM3u8Stream = null
                }
            }
            binding.chipGroupQuality.addView(chip)
        }

        // Add M3U8 stream chips
        for (stream in m3u8Streams) {
            val chip = Chip(requireContext()).apply {
                text = stream.quality + if (stream.resolution != null) " (${stream.resolution})" else ""
                isCheckable = true
                isChecked = stream == selectedM3u8Stream
                setOnClickListener {
                    selectedM3u8Stream = stream
                    selectedVariant = null
                }
            }
            binding.chipGroupQuality.addView(chip)
        }
    }

    private fun pasteFromClipboard() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        if (!text.isNullOrEmpty()) {
            binding.etVideoUrl.setText(text)
        } else {
            showSnackbar(getString(R.string.clipboard_empty))
        }
    }

    private fun pasteFromClipboardIfNeeded() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        if (!text.isNullOrEmpty()) {
            val lowerText = text.lowercase()
            if (lowerText.contains("twitter.com") || lowerText.contains("x.com") ||
                lowerText.contains(".mp4") || lowerText.contains(".m3u8") ||
                lowerText.contains("video")) {
                binding.etVideoUrl.setText(text)
            }
        }
    }

    private fun openPlayer(url: String) {
        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO_URL, url)
            putExtra(PlayerActivity.EXTRA_IS_STREAMING, true)
        }
        startActivity(intent)
    }

    private fun openLocalFile(path: String) {
        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO_URL, path)
            putExtra(PlayerActivity.EXTRA_IS_STREAMING, false)
        }
        startActivity(intent)
    }

    private fun shareFile(item: DownloadHistoryEntity) {
        item.filePath?.let { path ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse(path))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share_video)))
        }
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// Active Downloads Adapter
class ActiveDownloadsAdapter(
    private val onPause: (DownloadTask) -> Unit,
    private val onResume: (DownloadTask) -> Unit,
    private val onCancel: (DownloadTask) -> Unit,
    private val onPlay: (DownloadTask) -> Unit
) : androidx.recyclerview.widget.ListAdapter<DownloadTask, ActiveDownloadsAdapter.ViewHolder>(
    object : androidx.recyclerview.widget.DiffUtil.ItemCallback<DownloadTask>() {
        override fun areItemsTheSame(oldItem: DownloadTask, newItem: DownloadTask) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: DownloadTask, newItem: DownloadTask) = oldItem == newItem
    }
) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = com.xvideo.downloader.databinding.ItemActiveDownloadBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: com.xvideo.downloader.databinding.ItemActiveDownloadBinding) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root) {

        fun bind(task: DownloadTask) {
            binding.apply {
                // Show author name or URL for direct downloads
                tvAuthor.text = if (task.videoInfo.authorUsername != "direct") {
                    task.videoInfo.authorName
                } else {
                    task.videoInfo.tweetText.take(40) + if (task.videoInfo.tweetText.length > 40) "..." else ""
                }
                tvQuality.text = task.variant.getQualityLabel()
                tvProgress.text = "${task.progress}%"

                progressBar.progress = task.progress
                progressBar.max = 100

                when (task.state) {
                    DownloadTaskState.DOWNLOADING -> {
                        btnPauseResume.setImageResource(android.R.drawable.ic_media_pause)
                        btnPauseResume.setOnClickListener { onPause(task) }
                    }
                    DownloadTaskState.PAUSED -> {
                        btnPauseResume.setImageResource(android.R.drawable.ic_media_play)
                        btnPauseResume.setOnClickListener { onResume(task) }
                    }
                    else -> {}
                }

                btnCancel.setOnClickListener { onCancel(task) }
            }
        }
    }
}

// History Adapter
class DownloadHistoryAdapter(
    private val onPlay: (DownloadHistoryEntity) -> Unit,
    private val onDelete: (DownloadHistoryEntity) -> Unit,
    private val onShare: (DownloadHistoryEntity) -> Unit
) : androidx.recyclerview.widget.ListAdapter<DownloadHistoryEntity, DownloadHistoryAdapter.ViewHolder>(
    object : androidx.recyclerview.widget.DiffUtil.ItemCallback<DownloadHistoryEntity>() {
        override fun areItemsTheSame(oldItem: DownloadHistoryEntity, newItem: DownloadHistoryEntity) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: DownloadHistoryEntity, newItem: DownloadHistoryEntity) = oldItem == newItem
    }
) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = com.xvideo.downloader.databinding.ItemDownloadHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: com.xvideo.downloader.databinding.ItemDownloadHistoryBinding) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DownloadHistoryEntity) {
            binding.apply {
                tvAuthor.text = item.authorName
                tvUsername.text = if (item.authorUsername != "direct") "@${item.authorUsername}" else item.videoUrl.take(30)
                tvQuality.text = item.quality
                tvFileSize.text = if (item.fileSize > 0) FileUtils.formatFileSize(item.fileSize) else ""
                tvDate.text = item.completedAt?.let { FileUtils.formatDuration(System.currentTimeMillis() - it) } ?: ""

                btnPlay.setOnClickListener { onPlay(item) }
                btnShare.setOnClickListener { onShare(item) }
                btnDelete.setOnClickListener { onDelete(item) }
            }
        }
    }
}
