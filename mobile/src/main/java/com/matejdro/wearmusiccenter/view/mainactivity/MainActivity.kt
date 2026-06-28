package com.matejdro.wearmusiccenter.view.mainactivity

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import com.matejdro.wearmusiccenter.NotificationService
import com.matejdro.wearmusiccenter.R
import com.matejdro.wearmusiccenter.common.CommPaths
import com.matejdro.wearmusiccenter.config.WatchInfoWithIcons
import com.matejdro.wearmusiccenter.databinding.ActivityMainBinding
import com.matejdro.wearmusiccenter.di.InjectableViewModelFactory
import com.matejdro.wearmusiccenter.music.isPlaying
import com.matejdro.wearmusiccenter.view.ActivityResultReceiver
import com.matejdro.wearmusiccenter.view.FabFragment
import com.matejdro.wearmusiccenter.view.TitledActivity
import com.matejdro.wearmusiccenter.view.actionlist.ActionListFragment
import com.matejdro.wearmusiccenter.view.buttonconfig.ButtonConfigFragment
import com.matejdro.wearmusiccenter.view.settings.MiscSettingsFragment
import android.widget.SeekBar
import android.widget.ImageButton
import android.widget.TextView
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.palette.graphics.Palette
import androidx.preference.PreferenceManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.matejdro.wearutils.companionnotice.WearCompanionPhoneActivity
import dagger.android.AndroidInjection
import dagger.android.AndroidInjector
import dagger.android.DispatchingAndroidInjector
import dagger.android.HasAndroidInjector
import javax.inject.Inject


class MainActivity : WearCompanionPhoneActivity(),
        TitledActivity, ActivityResultReceiver, HasAndroidInjector {

    private lateinit var binding: ActivityMainBinding

    private var currentFragment: Fragment? = null
    private var miniPlayerController: MediaController? = null

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = Runnable { updateMiniPlayerProgress() }

    private var mediaDetailsDialog: BottomSheetDialog? = null
    private var detailSeekBar: SeekBar? = null
    private var detailTimeElapsed: TextView? = null
    private var detailTimeTotal: TextView? = null
    private var detailTitle: TextView? = null
    private var detailArtist: TextView? = null
    private var detailPlayPause: ImageButton? = null
    private var detailAlbumArt: ImageView? = null
    private var detailQueueContainer: LinearLayout? = null
    private var isSeeking = false
    // Holds the currently extracted dynamic accent color (null = use default lyra_accent)
    private var dynamicAccentColor: Int? = null

    @Inject
    lateinit var fragmentInjector: DispatchingAndroidInjector<Fragment>

    @Inject
    lateinit var viewModelFactory: InjectableViewModelFactory<MainActivityViewModel>

    private val viewmodel: MainActivityViewModel by viewModels { viewModelFactory }

    private val miniPlayerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateMiniPlayerMetadata(metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateMiniPlayerPlayState(state)
            if (state?.isPlaying() == true) startProgressUpdates() else stopProgressUpdates()
        }
    }

    private val preferenceChangeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "dynamic_accent_color" || key == "desaturated_color" || key == "custom_accent_color") {
            updateMiniPlayerMetadata(miniPlayerController?.metadata)
        }
    }

    fun onCustomAccentColorChanged(hex: String?) {
        val color = if (hex != null) {
            try { android.graphics.Color.parseColor(hex) } catch (e: Exception) { resolveDefaultAccent() }
        } else {
            resolveDefaultAccent()
        }
        dynamicAccentColor = if (hex != null) color else null
        applyAccentColor(color)
    }

    private fun resolveDefaultAccent(): Int {
        val customHex = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("custom_accent_color", null)
        return if (customHex != null) {
            try { android.graphics.Color.parseColor(customHex) } catch (e: Exception) {
                ContextCompat.getColor(this, R.color.lyra_accent)
            }
        } else {
            ContextCompat.getColor(this, R.color.lyra_accent)
        }
    }

    private val playFabRunnable = Runnable {
        val isMiniPlayerVisible = binding.miniPlayer.visibility == View.VISIBLE
        val isFabFragment = currentFragment is FabFragment
        binding.fabPlay.visibility = if (!isMiniPlayerVisible && !isFabFragment) View.VISIBLE else View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)

        viewmodel.watchInfoProvider.observe(this, watchInfoObserver)

        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tutorial -> swapFragment(TutorialFragment())
                R.id.playing_controls -> swapFragment(ButtonConfigFragment.newInstance(true))
                R.id.stopped_controls -> swapFragment(ButtonConfigFragment.newInstance(false))
                R.id.actions_menu -> swapFragment(ActionListFragment())
                R.id.settings -> swapFragment(MiscSettingsFragment())
            }
            true
        }

        binding.fab.setOnClickListener {
            (currentFragment as? FabFragment)?.onFabClicked()
        }

        binding.miniPrev.setOnClickListener {
            miniPlayerController?.transportControls?.skipToPrevious()
        }
        binding.miniNext.setOnClickListener {
            miniPlayerController?.transportControls?.skipToNext()
        }
        binding.miniPlayPause.setOnClickListener {
            val controls = miniPlayerController?.transportControls ?: return@setOnClickListener
            if (miniPlayerController?.isPlaying() == true) controls.pause() else controls.play()
        }

        // Enable marquee for scrolling song titles
        binding.miniTitle.isSelected = true
        binding.miniArtist.isSelected = true

        // Click to expand song details
        binding.miniMetadataContainer.setOnClickListener {
            showMediaDetailsDialog()
        }

        // Listen for seeking in mini progress bar
        binding.miniProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
                stopProgressUpdates()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
                val controller = miniPlayerController
                val duration = controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L
                if (duration > 0 && seekBar != null) {
                    val newPos = (seekBar.progress.toFloat() / 1000 * duration).toLong()
                    controller?.transportControls?.seekTo(newPos)
                }
                if (controller?.isPlaying() == true) {
                    startProgressUpdates()
                }
            }
        })

        viewmodel.activeMediaSessionProvider.observe(this) { resource ->
            val controller = resource?.data
            miniPlayerController?.unregisterCallback(miniPlayerCallback)
            miniPlayerController = controller
            controller?.registerCallback(miniPlayerCallback)

            if (controller != null) {
                updateMiniPlayerMetadata(controller.metadata)
                updateMiniPlayerPlayState(controller.playbackState)
                setMiniPlayerVisible(true)
            } else {
                setMiniPlayerVisible(false)
            }
        }

        // Play FAB click: resume the active session or send a system media key if none is bound yet
        binding.fabPlay.setOnClickListener {
            val controls = miniPlayerController?.transportControls
            if (controls != null) {
                controls.play()
            } else {
                val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
                am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
            }
        }

        updateCurrentFragment(supportFragmentManager.findFragmentById(R.id.fragment_container))
    }

    override fun onResume() {
        super.onResume()
        showNotificationServiceWarning()
    }

    override fun onDestroy() {
        miniPlayerController?.unregisterCallback(miniPlayerCallback)
        stopProgressUpdates()
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        super.onDestroy()
    }

    private fun setBottomNavVisible(visible: Boolean) {
        binding.bottomNav.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun setMiniPlayerVisible(visible: Boolean) {
        binding.miniPlayer.visibility = if (visible) View.VISIBLE else View.GONE
        val navH = resources.getDimensionPixelSize(R.dimen.bottom_nav_height)
        val miniH = if (visible) resources.getDimensionPixelSize(R.dimen.mini_player_height) else 0
        binding.fragmentContainer.setPadding(0, 0, 0, navH + miniH)

        val margin = navH + miniH + resources.getDimensionPixelSize(R.dimen.fab_margin)
        val fabParams = binding.fab.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
        fabParams.bottomMargin = margin
        binding.fab.layoutParams = fabParams

        val fabPlayParams = binding.fabPlay.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
        fabPlayParams.bottomMargin = margin
        binding.fabPlay.layoutParams = fabPlayParams

        updatePlayFabVisibility(null)

        if (visible) startProgressUpdates() else stopProgressUpdates()
    }

    private fun isDarkThemeActive(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val theme = prefs.getString("app_theme", "system")
        return when (theme) {
            "dark" -> true
            "light" -> false
            else -> {
                val nightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    private fun adjustColorForContrast(color: Int, isDarkTheme: Boolean): Int {
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(color, hsl)
        hsl[1] = hsl[1].coerceAtMost(0.65f) // Desaturate slightly
        if (isDarkTheme) {
            if (hsl[2] < 0.65f) {
                hsl[2] = 0.65f
            }
        } else {
            if (hsl[2] > 0.45f) {
                hsl[2] = 0.45f
            }
        }
        return androidx.core.graphics.ColorUtils.HSLToColor(hsl)
    }

    private fun updateMiniPlayerMetadata(metadata: MediaMetadata?) {
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "—"
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""

        binding.miniTitle.text = title
        binding.miniArtist.text = artist

        val art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        if (art != null) {
            binding.miniAlbumArt.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            binding.miniAlbumArt.setImageBitmap(art)

            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            if (prefs.getBoolean("dynamic_accent_color", false)) {
                Palette.from(art).generate { palette ->
                    var extracted = palette?.getVibrantColor(
                        palette.getMutedColor(
                            ContextCompat.getColor(this, R.color.lyra_accent)
                        )
                    ) ?: ContextCompat.getColor(this, R.color.lyra_accent)

                    if (prefs.getBoolean("desaturated_color", false)) {
                        extracted = adjustColorForContrast(extracted, isDarkThemeActive())
                    }

                    dynamicAccentColor = extracted
                    applyAccentColor(extracted)
                }
            } else {
                dynamicAccentColor = null
                applyAccentColor(resolveDefaultAccent())
            }
        } else {
            binding.miniAlbumArt.scaleType = android.widget.ImageView.ScaleType.CENTER
            binding.miniAlbumArt.setImageResource(R.drawable.ic_music_note)
            dynamicAccentColor = null
            applyAccentColor(resolveDefaultAccent())
        }

        if (mediaDetailsDialog?.isShowing == true) {
            detailTitle?.text = title
            detailArtist?.text = artist
            if (art != null) {
                detailAlbumArt?.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                detailAlbumArt?.setImageBitmap(art)
            } else {
                detailAlbumArt?.scaleType = android.widget.ImageView.ScaleType.CENTER
                detailAlbumArt?.setImageResource(R.drawable.ic_music_note)
            }
            updateQueueList()
        }
    }

    private fun updatePlayFabVisibility(state: PlaybackState?) {
        progressHandler.removeCallbacks(playFabRunnable)
        progressHandler.postDelayed(playFabRunnable, 250)
    }

    private fun showPlayFab(show: Boolean) {
        // Obsolete: Handled by playFabRunnable debounce
    }

    private fun applyAccentColor(color: Int) {
        val csl = android.content.res.ColorStateList.valueOf(color)
        binding.miniProgress.progressTintList = csl
        binding.miniProgress.thumbTintList = csl
        binding.miniPlayPause.imageTintList = csl
        binding.fabPlay.backgroundTintList = csl
        binding.fab.backgroundTintList = csl
        detailSeekBar?.progressTintList = csl
        detailSeekBar?.thumbTintList = csl
        detailPlayPause?.imageTintList = csl

        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf()
        )
        val colors = intArrayOf(
            color,
            ContextCompat.getColor(this, R.color.lyra_nav_inactive)
        )
        val colorStateList = android.content.res.ColorStateList(states, colors)
        binding.bottomNav.itemIconTintList = colorStateList
        binding.bottomNav.itemTextColor = colorStateList

        if (mediaDetailsDialog?.isShowing == true) {
            updateQueueList()
        }

        applyAccentColorToFragmentView(currentFragment, color)
    }

    private fun startProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable)
        updateMiniPlayerProgress()
    }

    private fun stopProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable)
    }

    private fun updateMiniPlayerProgress() {
        val controller = miniPlayerController ?: return
        val state = controller.playbackState ?: return
        val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L
        if (duration > 0) {
            val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            val pos = (state.position + (elapsed * state.playbackSpeed).toLong()).coerceAtLeast(0L)
            val progressPercent = ((pos.toFloat() / duration) * 1000).toInt().coerceIn(0, 1000)

            if (!isSeeking) {
                binding.miniProgress.progress = progressPercent
            }

            // Update dialog progress if showing
            if (mediaDetailsDialog?.isShowing == true && !isSeeking) {
                detailSeekBar?.progress = progressPercent
                detailTimeElapsed?.text = formatTime(pos)
                detailTimeTotal?.text = formatTime(duration)
            }
        }
        if (controller.isPlaying()) {
            progressHandler.postDelayed(progressRunnable, 500)
        }
    }

    private fun updateMiniPlayerPlayState(state: PlaybackState?) {
        val playing = state?.isPlaying() == true
        binding.miniPlayPause.setImageResource(
            if (playing) R.drawable.ic_nav_stopped else R.drawable.ic_nav_playing
        )
        binding.miniPlayPause.contentDescription = getString(
            if (playing) R.string.action_pause else R.string.action_play
        )
        updatePlayFabVisibility(state)

        // Update dialog play/pause button if showing
        if (mediaDetailsDialog?.isShowing == true) {
            detailPlayPause?.setImageResource(
                if (playing) R.drawable.ic_nav_stopped else R.drawable.ic_nav_playing
            )
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    private fun updateQueueList() {
        val container = detailQueueContainer ?: return
        container.removeAllViews()

        val queue = miniPlayerController?.queue
        if (queue.isNullOrEmpty()) {
            val noQueueTv = TextView(this).apply {
                text = getString(R.string.queue_unavailable)
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.lyra_text_secondary))
                gravity = android.view.Gravity.CENTER
                setPadding(0, 24, 0, 24)
            }
            container.addView(noQueueTv)
            return
        }

        // Get currently active queue item id from playback state
        val activeQueueId = miniPlayerController?.playbackState?.activeQueueItemId ?: -1L
        val accentColor = dynamicAccentColor ?: ContextCompat.getColor(this, R.color.lyra_accent)

        val inflater = layoutInflater
        for (item in queue) {
            val itemView = inflater.inflate(R.layout.item_queue_song, container, false)
            val titleTv = itemView.findViewById<TextView>(R.id.queue_item_title)
            val artistTv = itemView.findViewById<TextView>(R.id.queue_item_artist)
            val activeBar = itemView.findViewById<android.view.View>(R.id.queue_item_active_bar)
            val playingIcon = itemView.findViewById<ImageView>(R.id.queue_item_playing_icon)

            titleTv.text = item.description.title ?: "—"
            artistTv.text = item.description.subtitle ?: ""

            val isActive = item.queueId == activeQueueId
            if (isActive) {
                activeBar.visibility = android.view.View.VISIBLE
                playingIcon.visibility = android.view.View.VISIBLE
                titleTv.setTextColor(accentColor)
                titleTv.typeface = android.graphics.Typeface.DEFAULT_BOLD
                playingIcon.imageTintList = android.content.res.ColorStateList.valueOf(accentColor)
                activeBar.backgroundTintList = android.content.res.ColorStateList.valueOf(accentColor)
                
                val activeBg = ContextCompat.getDrawable(this, R.drawable.queue_item_active_bg)?.mutate()
                if (activeBg != null) {
                    val alphaColor = androidx.core.graphics.ColorUtils.setAlphaComponent(accentColor, 38) // ~15% opacity
                    DrawableCompat.setTint(activeBg, alphaColor)
                    itemView.background = activeBg
                }
            } else {
                activeBar.visibility = android.view.View.INVISIBLE
                playingIcon.visibility = android.view.View.GONE
                itemView.background = ContextCompat.getDrawable(this, android.R.color.transparent)
                titleTv.setTextColor(ContextCompat.getColor(this, R.color.lyra_on_surface))
                titleTv.typeface = android.graphics.Typeface.DEFAULT
            }

            itemView.setOnClickListener {
                miniPlayerController?.transportControls?.skipToQueueItem(item.queueId)
                // Refresh highlights after skip
                progressHandler.postDelayed({ updateQueueList() }, 300)
            }

            container.addView(itemView)
        }
    }

    private fun showMediaDetailsDialog() {
        val dialog = BottomSheetDialog(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_media_details, null)
        dialog.setContentView(dialogView)

        // Fix status bar color: ensure dialog window background matches app theme
        dialog.window?.apply {
            val bgColor = ContextCompat.getColor(this@MainActivity, R.color.lyra_background)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            statusBarColor = bgColor
        }

        mediaDetailsDialog = dialog

        detailAlbumArt = dialogView.findViewById(R.id.detail_album_art)
        detailTitle = dialogView.findViewById(R.id.detail_title)
        detailArtist = dialogView.findViewById(R.id.detail_artist)
        detailSeekBar = dialogView.findViewById(R.id.detail_seek_bar)
        detailTimeElapsed = dialogView.findViewById(R.id.detail_time_elapsed)
        detailTimeTotal = dialogView.findViewById(R.id.detail_time_total)
        detailPlayPause = dialogView.findViewById(R.id.detail_play_pause)
        detailQueueContainer = dialogView.findViewById(R.id.detail_queue_container)

        detailTitle?.isSelected = true
        detailArtist?.isSelected = true

        detailAlbumArt?.setOnClickListener {
            val art = miniPlayerController?.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: miniPlayerController?.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            if (art != null) showAlbumArtFullscreen(art)
        }

        val prevBtn = dialogView.findViewById<ImageButton>(R.id.detail_prev)
        val nextBtn = dialogView.findViewById<ImageButton>(R.id.detail_next)

        prevBtn.setOnClickListener {
            miniPlayerController?.transportControls?.skipToPrevious()
        }
        nextBtn.setOnClickListener {
            miniPlayerController?.transportControls?.skipToNext()
        }
        detailPlayPause?.setOnClickListener {
            val controls = miniPlayerController?.transportControls ?: return@setOnClickListener
            if (miniPlayerController?.isPlaying() == true) controls.pause() else controls.play()
        }

        detailSeekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
                val controller = miniPlayerController
                val duration = controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L
                if (duration > 0 && seekBar != null) {
                    val newPos = (seekBar.progress.toFloat() / 1000 * duration).toLong()
                    controller?.transportControls?.seekTo(newPos)
                }
            }
        })

        dialog.setOnDismissListener {
            mediaDetailsDialog = null
            detailAlbumArt = null
            detailTitle = null
            detailArtist = null
            detailSeekBar = null
            detailTimeElapsed = null
            detailTimeTotal = null
            detailPlayPause = null
            detailQueueContainer = null
        }

        // Show first so views are attached to the window
        dialog.show()

        // Fix sheet size: make the sheet non-draggable, stay wrap_content, and set to STATE_EXPANDED
        // so it opens fully at its natural compact height and cannot be dragged to the top.
        val bottomSheetView = dialog.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheetView?.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        if (bottomSheetView != null) {
            val behavior = BottomSheetBehavior.from(bottomSheetView)
            behavior.skipCollapsed = true
            behavior.isDraggable = false
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

        // Then populate with current data (views are now ready)
        val metadata = miniPlayerController?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "\u2014"
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        detailTitle?.text = title
        detailTitle?.isSelected = true
        detailArtist?.text = artist
        detailArtist?.isSelected = true

        val art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        if (art != null) {
            detailAlbumArt?.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            detailAlbumArt?.setImageBitmap(art)
        } else {
            detailAlbumArt?.scaleType = android.widget.ImageView.ScaleType.CENTER
            detailAlbumArt?.setImageResource(R.drawable.ic_music_note)
        }

        // Apply current dynamic color to the dialog SeekBar and play button
        val accentColor = dynamicAccentColor ?: ContextCompat.getColor(this, R.color.lyra_accent)
        val accentCsl = android.content.res.ColorStateList.valueOf(accentColor)
        detailSeekBar?.progressTintList = accentCsl
        detailSeekBar?.thumbTintList = accentCsl
        detailPlayPause?.imageTintList = accentCsl

        updateMiniPlayerPlayState(miniPlayerController?.playbackState)
        updateMiniPlayerProgress()
        updateQueueList()
    }

    private fun showAlbumArtFullscreen(bitmap: android.graphics.Bitmap) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val imageView = android.widget.ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        val downloadBtn = android.widget.Button(this).apply {
            text = getString(R.string.download_album_art)
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(48, 24, 48, 24)
        }
        downloadBtn.setOnClickListener { saveAlbumArtToGallery(bitmap) }

        val root = android.widget.FrameLayout(this)
        root.addView(imageView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))
        val btnLp = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
        ).also { it.bottomMargin = 64 }
        root.addView(downloadBtn, btnLp)

        root.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(root)
        dialog.show()
    }

    private fun saveAlbumArtToGallery(bitmap: android.graphics.Bitmap) {
        val title = miniPlayerController?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.replace(Regex("[^a-zA-Z0-9_\\-]"), "_") ?: "album_art"
        val filename = "${title}_${System.currentTimeMillis()}.jpg"

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_PICTURES + "/WearMusicCenter")
                }
                val uri = contentResolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    android.widget.Toast.makeText(this, R.string.album_art_saved, android.widget.Toast.LENGTH_SHORT).show()
                }
            } else {
                @Suppress("DEPRECATION")
                val path = android.provider.MediaStore.Images.Media.insertImage(
                    contentResolver, bitmap, title, null)
                if (path != null) {
                    android.widget.Toast.makeText(this, R.string.album_art_saved, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, R.string.album_art_save_error, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private val watchInfoObserver = Observer<WatchInfoWithIcons?> {
        if (it != null) {
            setBottomNavVisible(true)
            if (currentFragment == null || currentFragment is NoWatchFragment) {
                swapFragment(ButtonConfigFragment.newInstance(true))
                binding.bottomNav.selectedItemId = R.id.playing_controls
            }
        } else {
            setBottomNavVisible(false)
            swapFragment(NoWatchFragment())
        }
    }

    private fun swapFragment(newFragment: Fragment) {
        supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, newFragment)
                .commit()
        updateCurrentFragment(newFragment)
    }

    private fun updateCurrentFragment(newFragment: Fragment?) {
        currentFragment = newFragment
        if (newFragment == null) return

        if (newFragment is FabFragment) {
            binding.fab.let {
                it.show()
                newFragment.prepareFab(it)
            }
        } else {
            binding.fab.hide()
        }

        // Keep play FAB visibility updated based on fragment changes
        updatePlayFabVisibility(miniPlayerController?.playbackState)

        // Apply current accent color to newly loaded fragment views
        val color = dynamicAccentColor ?: ContextCompat.getColor(this, R.color.lyra_accent)
        applyAccentColorToFragmentView(newFragment, color)
    }

    private fun applyAccentColorToFragmentView(fragment: Fragment?, color: Int) {
        val view = fragment?.view ?: return
        applyAccentColorToViewTree(view, color)

        if (fragment is androidx.preference.PreferenceFragmentCompat) {
            val recyclerView = fragment.listView
            recyclerView?.addOnChildAttachStateChangeListener(object : androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    applyAccentColorToViewTree(view, color)
                }
                override fun onChildViewDetachedFromWindow(view: View) {}
            })
            if (recyclerView != null) {
                applyAccentColorToViewTree(recyclerView, color)
            }
        }
    }

    private fun applyAccentColorToViewTree(view: View, color: Int) {
        val csl = android.content.res.ColorStateList.valueOf(color)
        if (view is android.widget.CompoundButton) {
            view.buttonTintList = csl
        } else if (view is android.widget.SeekBar) {
            view.progressTintList = csl
            view.thumbTintList = csl
        } else if (view is com.google.android.material.floatingactionbutton.FloatingActionButton) {
            view.backgroundTintList = csl
        } else if (view is com.google.android.material.button.MaterialButton) {
            view.strokeColor = csl
            view.iconTint = csl
            view.rippleColor = csl
            if (view.backgroundTintList != null) {
                view.backgroundTintList = csl
            }
        } else if (view is android.widget.Button) {
            view.backgroundTintList = csl
        } else if (view is ImageView) {
            // Apply only to views that use the default accent tint
            if (view.imageTintList == android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.lyra_accent))) {
                view.imageTintList = csl
            }
        }

        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                applyAccentColorToViewTree(view.getChildAt(i), color)
            }
        }
    }

    private fun showNotificationServiceWarning() {
        if (NotificationService.isEnabled(this)) return

        AlertDialog.Builder(this)
                .setTitle(getString(R.string.error_service_not_enabled))
                .setNegativeButton(android.R.string.cancel, null)
                .setMessage(getString(R.string.error_service_not_enabled_description))
                .setPositiveButton(getString(R.string.action_open_settings)) { _, _ ->
                    openNotificationListener()
                }
                .show()
    }

    private fun openNotificationListener() {
        try {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        } catch (e: ActivityNotFoundException) {
            AlertDialog.Builder(this)
                    .setTitle(R.string.error_service_not_enabled)
                    .setMessage(getString(R.string.error_no_notification_service_support))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        currentFragment?.onActivityResult(requestCode, resultCode, data)
    }

    override fun updateActivityTitle(newTitle: String) {
        supportActionBar?.title = newTitle
    }

    override fun getWatchAppPresenceCapability(): String = CommPaths.WATCH_APP_CAPABILITY



    @Suppress("UNCHECKED_CAST")
    override fun androidInjector(): AndroidInjector<Any> {
        return fragmentInjector as AndroidInjector<Any>
    }
}
