package com.matejdro.wearmusiccenter.watch.view

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Vibrator
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.view.ViewConfiguration
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import androidx.preference.PreferenceManager
import androidx.wear.ambient.AmbientModeSupport
import androidx.wear.widget.drawer.WearableDrawerLayout
import androidx.wear.widget.drawer.WearableDrawerView
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.GooglePlayServicesRepairableException
import com.google.android.wearable.input.RotaryEncoderHelper
import com.matejdro.wearmusiccenter.R
import com.matejdro.wearmusiccenter.common.CommPaths
import com.matejdro.wearmusiccenter.common.CustomLists
import com.matejdro.wearmusiccenter.common.MiscPreferences
import com.matejdro.wearmusiccenter.common.ScreenQuadrant
import com.matejdro.wearmusiccenter.common.buttonconfig.ButtonInfo
import com.matejdro.wearmusiccenter.common.buttonconfig.GESTURE_DOUBLE_TAP
import com.matejdro.wearmusiccenter.common.buttonconfig.GESTURE_LONG_TAP
import com.matejdro.wearmusiccenter.common.buttonconfig.GESTURE_SINGLE_TAP
import com.matejdro.wearmusiccenter.common.buttonconfig.SpecialButtonCodes
import com.matejdro.wearmusiccenter.common.view.FourWayTouchLayout
import com.matejdro.wearmusiccenter.databinding.ActivityMainBinding
import com.matejdro.wearmusiccenter.proto.MusicState
import com.matejdro.wearmusiccenter.watch.communication.CustomListWithBitmaps
import com.matejdro.wearmusiccenter.watch.communication.WatchInfoSender
import com.matejdro.wearmusiccenter.watch.communication.WatchMusicService
import com.matejdro.wearmusiccenter.watch.config.WatchActionConfigProvider
import com.matejdro.wearmusiccenter.watch.model.Notification
import com.matejdro.wearutils.companionnotice.WearCompanionWatchActivity
import com.matejdro.wearutils.lifecycle.Resource
import com.matejdro.wearutils.miscutils.VibratorCompat
import com.matejdro.wearutils.preferences.definition.Preferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

@AndroidEntryPoint
class MainActivity : WearCompanionWatchActivity(),
        FourWayTouchLayout.UserActionListener,
        AmbientModeSupport.AmbientCallbackProvider {

    companion object {
        private const val MESSAGE_HIDE_VOLUME = 10
        private const val MESSAGE_UPDATE_CLOCK = 11
        private const val MESSAGE_DISMISS_NOTIFICATION = 12

        private const val VOLUME_BAR_TIMEOUT = 1000L
        private const val ROTARY_DEADZONE = 6f
        private const val OVERLAY_FADE_OUT_MS = 150L
        private const val OVERLAY_FADE_IN_MS = 80L
        private const val BLUR_RADIUS_PX = 35f
        private const val MIN_LEGACY_BLUR_DIMENSION_PX = 16
        private const val AMBIENT_ALBUM_ART_ALPHA = 0.55f
    }

    // Always 24h, regardless of system locale/setting - a stray "AM"/"PM" suffix on an ambient
    // watch face just adds clutter without adding information.
    private val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    private lateinit var binding: ActivityMainBinding
    private lateinit var drawerContentContainer: View
    private lateinit var actionsMenuFragment: ActionsMenuFragment
    private lateinit var vibrator: Vibrator
    private lateinit var ambientController: AmbientModeSupport.AmbientController
    private lateinit var stemButtonsManager: StemButtonsManager
    private val handler = TimeoutsHandler(WeakReference(this))

    private var notificationDismissDeadline: Long = Long.MAX_VALUE
    private var dimAlbumArt: Boolean = false
    private var lastKnownDurationMs: Long = 0L
    private var latestAlbumArt: Bitmap? = null
    // Read by ActionsMenuFragment to highlight the currently-playing queue row in the same color.
    var currentAccentColor: Int = 0
        private set
    private var shuffleEnabled: Boolean = false
    private var repeatMode: Int = 0
    private var liked: Boolean = false

    private val defaultSeekBarColor by lazy { getColor(R.color.theme_accent) }

    private lateinit var preferences: SharedPreferences

    private val viewModel: MusicViewModel by viewModels()

    private var rotatingInputDisabledUntil = 0L

    private val serviceConnection = MusicServiceConnection(lifecycle)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        drawerContentContainer = findViewById(R.id.drawer_content)

        // Hide peek container - we only want full blown drawer without peeks
        val peekContainer: android.view.ViewGroup = binding.drawerLayout.findViewById(
                androidx.wear.R.id.ws_drawer_view_peek_container
        )
        peekContainer.visibility = View.GONE
        while (peekContainer.childCount > 0) {
            peekContainer.removeViewAt(0)
        }

        val params = peekContainer.layoutParams
        params.width = 0
        params.height = 0
        peekContainer.layoutParams = params

        binding.fourWayTouch.listener = this
        binding.seekBar.onSeekPreview = { fraction -> showSeekOverlay(fraction) }
        binding.seekBar.onSeekFinished = { fraction ->
            viewModel.seekTo(fraction)
            hideOverlay()
        }
        binding.volumeBar.onVolumeChanged = { fraction ->
            viewModel.updateVolume(fraction)
            showVolumeBar()
        }
        binding.seekBar.excludedTouchViews = listOf(
                binding.iconTop,
                binding.iconBottom,
                binding.iconLeft,
                binding.iconRight
        )

        val centerTapGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            // Without this, the detector's default onDown() (false) would make onTouchEvent()
            // return false for ACTION_DOWN, so the view never sees the rest of the gesture -
            // the touch would fall through to FourWayTouchLayout underneath instead.
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                buzz()
                viewModel.togglePlayPause()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                buzz()
                if (isQuickActionsPanelShowing()) {
                    hideOverlay()
                } else {
                    showQuickActionsPanel()
                }
                return true
            }
        })
        binding.centerTapZone.setOnTouchListener { _, event -> centerTapGestureDetector.onTouchEvent(event) }

        // Tapping outside the panel, or swiping down on it, both dismiss it - the system back
        // gesture (left-edge swipe) closes the whole app instead of just this overlay on some
        // Wear OS builds, so a swipe-down is offered as a reliable alternative.
        val overlayDismissGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (isQuickActionsPanelShowing()) {
                    hideOverlay()
                }
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (isQuickActionsPanelShowing() && velocityY > 400f && velocityY > abs(velocityX)) {
                    hideOverlay()
                    return true
                }
                return false
            }
        })
        binding.overlayBlurImage.setOnTouchListener { _, event ->
            overlayDismissGestureDetector.onTouchEvent(event)
        }

        binding.quickActionLike.setOnTouchListener(quickActionPressFeedback)
        binding.quickActionShuffle.setOnTouchListener(quickActionPressFeedback)
        binding.quickActionRepeat.setOnTouchListener(quickActionPressFeedback)

        binding.quickActionLike.setOnClickListener {
            buzz()
            viewModel.sendQuickAction("like")
        }
        binding.quickActionShuffle.setOnClickListener {
            buzz()
            viewModel.sendQuickAction("shuffle")
        }
        binding.quickActionRepeat.setOnClickListener {
            buzz()
            viewModel.sendQuickAction("repeat")
        }
        binding.quickActionUpNext.setOnClickListener {
            buzz()
            hideOverlay()
            // Same call the working, separately-configured "open queue" button uses - unlike
            // openDefaultListInDrawer(), this isn't gated behind the swipe-up-specific preference,
            // so Up Next always opens the real queue regardless of that setting.
            viewModel.openPlaybackQueue()
            actionsMenuFragment.refreshMenu(
                    ActionsMenuFragment.MenuType.Custom(CustomListWithBitmaps(-1, "", emptyList()))
            )
            binding.actionDrawer.controller.openDrawer()
        }

        // Title's floor (22sp) is kept comfortably above artist's ceiling (16sp) so the title
        // stays visually dominant even when a long title has to shrink to fit two lines.
        binding.textArtist.enableSmartWordSizing(maxSizeSp = 16f, minSizeSp = 9f)
        binding.textTitle.enableSmartWordSizing(maxSizeSp = 46f, minSizeSp = 25f)

        binding.drawerLayout.setDrawerStateCallback(drawerStateCallback)
        binding.notificationPopup.clickableFrame.setOnClickListener { onNotificationTapped() }

        preferences = PreferenceManager.getDefaultSharedPreferences(this)
        ambientController = AmbientModeSupport.attach(this)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        viewModel.albumArt.observe(this, albumArtObserver)
        viewModel.currentButtonConfig.observe(this, buttonConfigObserver)
        viewModel.preferences.observe(this, preferencesChangeObserver)
        viewModel.volume.observe(this, phoneVolumeListener)
        viewModel.popupVolumeBar.observe(this, volumeBarPopupListener)
        viewModel.closeActionsMenu.observe(this, closeDrawerListener)
        viewModel.openActionsMenu.observe(this, openActionsMenuListener)
        viewModel.closeApp.observe(this, closeAppListener)
        viewModel.notification.observe(this, notificationObserver)
        viewModel.customList.observe(this, customListListener)
        viewModel.playbackPosition.observe(this, playbackPositionObserver)


        stemButtonsManager = StemButtonsManager(WatchInfoSender.getAvailableButtonsOnWatch(this), stemButtonListener, lifecycleScope)

        actionsMenuFragment =
                supportFragmentManager.findFragmentById(R.id.drawer_content) as ActionsMenuFragment

        onBackPressedDispatcher.addCallback(this, backButtonOverrideCallback)
        // Registered after backButtonOverrideCallback so it takes priority while enabled - the
        // back gesture should close the quick-actions panel instead of exiting the app.
        onBackPressedDispatcher.addCallback(this, quickActionsPanelBackCallback)
    }

    override fun onStart() {
        super.onStart()

        if (Preferences.getBoolean(preferences, MiscPreferences.ALWAYS_SHOW_TIME)) {
            handler.sendEmptyMessage(MESSAGE_UPDATE_CLOCK)
        }

        val crownDisableTime =
                Preferences.getInt(preferences, MiscPreferences.ROTATING_CROWN_OFF_PERIOD)
        if (crownDisableTime > 0) {
            rotatingInputDisabledUntil = System.currentTimeMillis() + crownDisableTime
        }

        viewModel.updateTimers()
        hideNotificationIfOverdue()

        bindService(Intent(this, WatchMusicService::class.java), serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (isFinishing) {
            viewModel.sendManualCloseMessage()
        }

        super.onStop()

        handler.removeMessages(MESSAGE_UPDATE_CLOCK)
        unbindService(serviceConnection)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        // onStop will trigger when screen turns off (But app stays in foreground)
        // and thus disable data transmission

        // Keep one observer alive as long as app has focus.

        if (hasFocus) {
            viewModel.musicState.observeForever(musicStateObserver)
        } else {
            viewModel.musicState.removeObserver(musicStateObserver)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        viewModel.musicState.removeObserver(musicStateObserver)
    }

    private fun updateClock() {
        binding.ambientClock.text = timeFormat.format(java.util.Date())
    }

    private val musicStateObserver = Observer<Resource<MusicState>?> {
        Timber.d("GUI Music State %s %s", it?.status, it?.data)
        if (it == null || it.status == Resource.Status.LOADING) {
            binding.loadingIndicator.visibility = View.VISIBLE
            return@Observer
        }

        binding.loadingIndicator.visibility = View.GONE

        if (it.status == Resource.Status.SUCCESS && it.data != null) {
            if ((it.data as MusicState).playing) {
                // Restores the dynamic (palette-extracted) color after a stopped/error message
                // may have forced it to plain white below.
                binding.textArtist.setTextColor(currentAccentColor)
                binding.textArtist.text = it.data?.artist
            } else {
                setStatusMessageOnArtistLine(getString(R.string.playback_stopped))
            }

            binding.textTitle.text = it.data?.title

            shuffleEnabled = it.data?.shuffleEnabled == true
            repeatMode = it.data?.repeatMode ?: 0
            liked = it.data?.liked == true
            updateQuickActionButtonStates()
        } else if (it.status == Resource.Status.ERROR) {
            setStatusMessageOnArtistLine(getString(R.string.error))
            binding.textTitle.text = it.message

            val errorData = it.errorData
            if (errorData is GooglePlayServicesRepairableException) {
                GoogleApiAvailability.getInstance().getErrorDialog(this, errorData.connectionStatusCode, 1)?.show()
            }
        } else {
            binding.textArtist.text = ""
            binding.textTitle.text = getString(R.string.playback_stopped)
        }

        binding.textArtist.visibility =
                if (binding.textArtist.text.isEmpty()) View.GONE else View.VISIBLE
    }

    private val albumArtObserver = Observer<Bitmap?> {
        latestAlbumArt = it
        binding.albumArt.setImageBitmap(it)
        applyBlurredAlbumArt(it)

        if (it == null) {
            applyAccentColor(defaultSeekBarColor)
            return@Observer
        }

        Palette.from(it).generate { palette ->
            // Try increasingly muted/neutral swatches before giving up - vibrant swatches
            // require saturated colors, so black & white / grayscale album art (which has
            // none) would otherwise always fall through to the default accent.
            val color = palette?.let { p ->
                listOf(
                        p.getVibrantSwatch(),
                        p.getMutedSwatch(),
                        p.getLightVibrantSwatch(),
                        p.getDarkVibrantSwatch(),
                        p.getLightMutedSwatch(),
                        p.getDarkMutedSwatch(),
                        p.dominantSwatch
                ).firstNotNullOfOrNull { swatch -> swatch?.rgb }
            } ?: defaultSeekBarColor

            applyAccentColor(color)
        }
    }

    private fun applyAccentColor(color: Int) {
        currentAccentColor = color
        binding.seekBar.progressColor = color
        binding.volumeBar.progressColor = color
        binding.textArtist.setTextColor(color)

        if (isQuickActionsPanelShowing()) {
            binding.quickActionPanelArtist.setTextColor(color)
            updateQuickActionButtonStates()
        }
    }

    /**
     * "Playback Stopped"/"Error" reuse the artist line, but they're status messages, not an
     * artist name - they should always read in plain white, never the dynamic accent color.
     */
    private fun setStatusMessageOnArtistLine(message: String) {
        binding.textArtist.setTextColor(getColor(android.R.color.white))
        binding.textArtist.text = message
    }

    /**
     * Shows a blurred copy of the current album art behind the volume/seek rings.
     *
     * On API 31+ this is a real GPU Gaussian blur via [android.graphics.RenderEffect] - sharp,
     * cheap, no quality loss. Older watches fall back to [createBlurredBitmapLegacy], a software
     * approximation.
     */
    private fun applyBlurredAlbumArt(source: Bitmap?) {
        if (source == null) {
            binding.overlayBlurImage.setImageBitmap(null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                binding.overlayBlurImage.setRenderEffect(null)
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.overlayBlurImage.setImageBitmap(source)
            binding.overlayBlurImage.setRenderEffect(
                    RenderEffect.createBlurEffect(BLUR_RADIUS_PX, BLUR_RADIUS_PX, Shader.TileMode.CLAMP)
            )
        } else {
            binding.overlayBlurImage.setImageBitmap(createBlurredBitmapLegacy(source))
        }
    }

    /**
     * Approximates a blur without RenderEffect by halving the bitmap's size repeatedly (each
     * halving step is naturally box-filtered by the bilinear downscale) before scaling back up
     * in one go - a single aggressive downscale jump looks blocky/pixelated instead, since
     * bilinear interpolation between too few source pixels can't approximate a smooth blur.
     */
    /** Blurs (or un-blurs) the main album art view itself - used only for ambient mode. */
    private fun setAmbientAlbumArtBlur(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.albumArt.setRenderEffect(
                    if (enabled) {
                        RenderEffect.createBlurEffect(BLUR_RADIUS_PX, BLUR_RADIUS_PX, Shader.TileMode.CLAMP)
                    } else {
                        null
                    }
            )
        } else {
            val source = latestAlbumArt
            binding.albumArt.setImageBitmap(
                    if (enabled && source != null) createBlurredBitmapLegacy(source) else source
            )
        }
    }

    private fun createBlurredBitmapLegacy(source: Bitmap): Bitmap {
        var current = source
        while (current.width > MIN_LEGACY_BLUR_DIMENSION_PX && current.height > MIN_LEGACY_BLUR_DIMENSION_PX) {
            val next = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
            if (current !== source) {
                current.recycle()
            }
            current = next
        }

        val result = Bitmap.createScaledBitmap(current, source.width, source.height, true)
        if (current !== source) {
            current.recycle()
        }
        return result
    }

    private val buttonConfigObserver = Observer<WatchActionConfigProvider?> { config ->
        if (config == null) {
            return@Observer
        }

        val topSingle = config.getAction(ButtonInfo(false, ScreenQuadrant.TOP, GESTURE_SINGLE_TAP))
        val bottomSingle =
                config.getAction(ButtonInfo(false, ScreenQuadrant.BOTTOM, GESTURE_SINGLE_TAP))
        val leftSingle =
                config.getAction(ButtonInfo(false, ScreenQuadrant.LEFT, GESTURE_SINGLE_TAP))
        val rightSingle =
                config.getAction(ButtonInfo(false, ScreenQuadrant.RIGHT, GESTURE_SINGLE_TAP))

        binding.iconTop.setImageDrawable(topSingle?.icon)
        binding.iconBottom.setImageDrawable(bottomSingle?.icon)
        binding.iconLeft.setImageDrawable(leftSingle?.icon)
        binding.iconRight.setImageDrawable(rightSingle?.icon)

        for (i in 0 until 4) {
            binding.fourWayTouch.enabledDoubleTaps[i] =
                    config.isActionActive(ButtonInfo(false, i, GESTURE_DOUBLE_TAP))
            binding.fourWayTouch.enabledLongTaps[i] =
                    config.isActionActive(ButtonInfo(false, i, GESTURE_LONG_TAP))
        }

        with(stemButtonsManager) {
            for (button in WatchInfoSender.getAvailableButtonsOnWatch(this@MainActivity)) {
                enabledDoublePressActions[button] =
                        config.isActionActive(ButtonInfo(true, button, GESTURE_DOUBLE_TAP))
                enabledLongPressActions[button] =
                        config.isActionActive(ButtonInfo(true, button, GESTURE_LONG_TAP))
            }
        }

        backButtonOverrideCallback.isEnabled = config.isActionActive(ButtonInfo(true, KeyEvent.KEYCODE_BACK, GESTURE_SINGLE_TAP))
    }

    private val preferencesChangeObserver = Observer<SharedPreferences?> {
        if (it == null) {
            return@Observer
        }

        preferences = it

        stemButtonsManager.enableDoublePressInAmbient = !Preferences.getBoolean(
                preferences,
                MiscPreferences.DISABLE_PHYSICAL_DOUBLE_CLICK_IN_AMBIENT
        )

        if (!ambientController.isAmbient) {
            val alwaysDisplayClock =
                    Preferences.getBoolean(preferences, MiscPreferences.ALWAYS_SHOW_TIME)

            if (alwaysDisplayClock) {
                binding.ambientClock.visibility = View.VISIBLE
                binding.iconTop.visibility = View.GONE
                handler.sendEmptyMessage(MESSAGE_UPDATE_CLOCK)
            } else {
                binding.iconTop.visibility = View.VISIBLE
                binding.ambientClock.visibility = View.GONE
            }
        }

        dimAlbumArt = Preferences.getBoolean(
            preferences,
            MiscPreferences.DIM_ALBUM_ART
        )

        if (!ambientController.isAmbient) {
            binding.albumArtScrim.visibility = if (dimAlbumArt) View.VISIBLE else View.INVISIBLE
        }
    }

    private val notificationObserver = Observer<Notification?> {
        if (it == null) {
            return@Observer
        }

        val notificationPopup = binding.notificationPopup

        notificationPopup.title.text = it.title
        notificationPopup.body.text = it.description
        notificationPopup.backgroundImage.setImageBitmap(it.background)

        showNotification(it)
    }

    private val phoneVolumeListener = Observer<Float> {
        binding.volumeBar.volume = it
    }

    private val playbackPositionObserver = Observer<PlaybackPosition?> { position ->
        if (position == null || position.durationMs <= 0) {
            binding.seekBar.seekable = false
            binding.textPlaybackTime.visibility = View.GONE
            return@Observer
        }

        lastKnownDurationMs = position.durationMs
        binding.seekBar.seekable = position.seekable
        binding.seekBar.progress = position.positionMs.toFloat() / position.durationMs

        binding.textPlaybackTime.visibility = View.VISIBLE
        binding.textPlaybackTime.text = getString(
                R.string.playback_time_format,
                formatPlaybackTime(position.positionMs),
                formatPlaybackTime(position.durationMs)
        )
    }

    private fun formatPlaybackTime(timeMs: Long): String {
        val totalSeconds = timeMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    private val volumeBarPopupListener = Observer<Unit?> {
        showVolumeBar()
    }

    private val closeDrawerListener = Observer<Unit?> {
        closeMenuDrawer()
    }

    fun closeMenuDrawer() {
        binding.actionDrawer.controller.closeDrawer()
    }

    private val openActionsMenuListener = Observer<Unit?> {
        actionsMenuFragment.refreshMenu(ActionsMenuFragment.MenuType.Actions)
        openMenuDrawer()
    }

    private val closeAppListener = Observer<Unit?> {
        finish()
    }

    private val customListListener = Observer<CustomListWithBitmaps?> {
        if (it == null) {
            return@Observer
        }

        updateUpNextPreview(it)

        // The quick-actions panel only asked for this to populate its "Up Next" preview text -
        // it hasn't asked to see the full list, so don't yank the user into the drawer for it.
        if (isQuickActionsPanelShowing()) {
            return@Observer
        }

        val lastListDisplayed = Preferences.getString(
                preferences,
                MiscPreferences.LAST_MENU_DISPLAYED
        ).toLong()

        if (!binding.actionDrawer.isClosed || lastListDisplayed != it.listTimestamp) {
            actionsMenuFragment.refreshMenu(ActionsMenuFragment.MenuType.Custom(it))
            openMenuDrawer()

            val editor = preferences.edit()
            Preferences.putString(
                    editor,
                    MiscPreferences.LAST_MENU_DISPLAYED,
                    it.listTimestamp.toString()
            )
            editor.apply()
        }
    }


    private val stemButtonListener = { buttonKeyCode: Int, gesture: Int ->
        if (gesture == GESTURE_DOUBLE_TAP) {
            handler.postDelayed(this::buzz, ViewConfiguration.getDoubleTapTimeout().toLong())
        } else if (buttonKeyCode != SpecialButtonCodes.TURN_ROTARY_CW && buttonKeyCode != SpecialButtonCodes.TURN_ROTARY_CCW) {
            buzz()
        }

        viewModel.executeAction(ButtonInfo(true, buttonKeyCode, gesture))
    }

    private fun openMenuDrawer() {
        binding.actionDrawer.controller.openDrawer()
    }

    private val drawerStateCallback = object : WearableDrawerLayout.DrawerStateCallback() {
        override fun onDrawerClosed(layout: WearableDrawerLayout, drawerView: WearableDrawerView) {
            binding.fourWayTouch.requestFocus()
            actionsMenuFragment.scrollToTop()
        }

        override fun onDrawerOpened(layout: WearableDrawerLayout, drawerView: WearableDrawerView) {
            drawerContentContainer.requestFocus()
        }

        override fun onDrawerStateChanged(layout: WearableDrawerLayout, newState: Int) {
            if (newState == WearableDrawerView.STATE_DRAGGING && binding.actionDrawer.isClosed) {
                openDefaultListInDrawer()
            }
        }
    }

    val backButtonOverrideCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            stemButtonsManager.simulateKeyPress(KeyEvent.KEYCODE_BACK)
        }
    }

    private val quickActionsPanelBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            hideOverlay()
        }
    }

    private fun openDefaultListInDrawer() {
        val type = if (Preferences.getBoolean(
                        preferences,
                        MiscPreferences.OPEN_PLAYBACK_QUEUE_ON_SWIPE_UP
                )
        ) {
            viewModel.openPlaybackQueue()

            ActionsMenuFragment.MenuType.Custom(
                    CustomListWithBitmaps(-1, "", emptyList())
            )
        } else {
            ActionsMenuFragment.MenuType.Actions
        }

        actionsMenuFragment.refreshMenu(type)
    }

    override fun getAmbientCallback(): AmbientModeSupport.AmbientCallback =
            object : AmbientModeSupport.AmbientCallback() {
                override fun onEnterAmbient(ambientDetails: Bundle?) {
                    stemButtonsManager.onEnterAmbient()
                    binding.ambientClock.visibility = View.VISIBLE

                    handler.removeMessages(MESSAGE_UPDATE_CLOCK)
                    updateClock()
                    hideNotificationIfOverdue()

                    binding.iconTop.visibility = View.GONE
                    binding.iconBottom.visibility = View.GONE
                    binding.iconLeft.visibility = View.GONE
                    binding.iconRight.visibility = View.GONE

                    binding.albumArt.alpha = AMBIENT_ALBUM_ART_ALPHA
                    setAmbientAlbumArtBlur(true)
                    binding.albumArtScrim.visibility = View.GONE
                    binding.volumeBar.visibility = View.GONE
                    binding.seekBar.visibility = View.GONE
                    binding.overlayBlurImage.visibility = View.GONE
                    binding.overlayDim.visibility = View.GONE
                    binding.textVolumePercent.visibility = View.GONE
                    binding.textSeekTime.visibility = View.GONE
                    binding.volumeIconTop.visibility = View.GONE
                    binding.volumeIconBottom.visibility = View.GONE
                    binding.quickActionsPanel.visibility = View.GONE
                    binding.loadingIndicator.visibility = View.GONE

                    // A continuously scrolling marquee would be both a burn-in risk and a
                    // pointless battery drain on an always-on display - freeze it in place.
                    binding.textArtist.setMarqueePaused(true)
                    binding.textTitle.setMarqueePaused(true)

                    // The system only calls onUpdateAmbient roughly once a minute, so a
                    // playback position display would just look frozen/stale here. The
                    // always-visible ambientClock above already covers "what time is it".
                    binding.textPlaybackTime.visibility = View.GONE

                    viewModel.setContinuousPositionTicking(false)

                    binding.root.background = ColorDrawable(Color.BLACK)

                    binding.notificationPopup.backgroundImage.visibility = View.GONE
                    binding.notificationPopup.solidBackground.background = ColorDrawable(Color.BLACK)

                    // Artist stays plain bold (no outline effect) - only the title mimics the
                    // stock look of an outlined, "etched" headline.
                    binding.textTitle.displayTextOutline = true
                    binding.textPlaybackTime.displayTextOutline = true

                    binding.actionDrawer.controller.closeDrawer()
                }

                override fun onUpdateAmbient() {
                    updateClock()
                    viewModel.updateTimers()
                    hideNotificationIfOverdue()

                    binding.drawerLayout.translationX = Random.nextInt(-5, 6).toFloat()
                    binding.drawerLayout.translationY = Random.nextInt(-5, 6).toFloat()
                }

                override fun onExitAmbient() {
                    stemButtonsManager.onExitAmbient()

                    if (Preferences.getBoolean(preferences, MiscPreferences.ALWAYS_SHOW_TIME)) {
                        handler.sendEmptyMessage(MESSAGE_UPDATE_CLOCK)
                    } else {
                        binding.ambientClock.visibility = View.GONE
                        binding.iconTop.visibility = View.VISIBLE
                    }

                    binding.iconBottom.visibility = View.VISIBLE
                    binding.iconLeft.visibility = View.VISIBLE
                    binding.iconRight.visibility = View.VISIBLE

                    binding.albumArt.alpha = 1f
                    setAmbientAlbumArtBlur(false)
                    binding.albumArtScrim.visibility = if (dimAlbumArt) View.VISIBLE else View.INVISIBLE
                    binding.seekBar.visibility = View.VISIBLE
                    binding.seekBar.alpha = 1f
                    binding.volumeBar.visibility = View.GONE
                    binding.volumeBar.alpha = 1f
                    binding.overlayBlurImage.visibility = View.GONE
                    binding.overlayDim.visibility = View.GONE
                    binding.textVolumePercent.visibility = View.GONE
                    binding.textSeekTime.visibility = View.GONE

                    binding.textArtist.setMarqueePaused(false)
                    binding.textTitle.setMarqueePaused(false)

                    viewModel.setContinuousPositionTicking(true)

                    binding.root.background = null

                    binding.notificationPopup.backgroundImage.visibility = View.VISIBLE
                    binding.notificationPopup.solidBackground.background =
                            AppCompatResources.getDrawable(
                                    this@MainActivity,
                                    R.drawable.notification_popup_background
                            )

                    if (viewModel.musicState.value == null || (viewModel.musicState.value as Resource<MusicState>).status == Resource.Status.LOADING) {
                        binding.loadingIndicator.visibility = View.VISIBLE
                    }

                    val crownDisableTime =
                            Preferences.getInt(preferences, MiscPreferences.ROTATING_CROWN_OFF_PERIOD)
                    if (crownDisableTime > 0) {
                        rotatingInputDisabledUntil = System.currentTimeMillis() + crownDisableTime
                    }

                    binding.textTitle.displayTextOutline = false
                    binding.textPlaybackTime.displayTextOutline = false

                    // onUpdateAmbient() offsets drawerLayout (not root) for burn-in protection -
                    // resetting the wrong view here left it permanently shifted after ambient.
                    binding.drawerLayout.translationX = 0f
                    binding.drawerLayout.translationY = 0f
                }

            }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (binding.actionDrawer.isOpened && actionsMenuFragment.onGenericMotionEvent(ev)) {
            return true
        }

        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        if (binding.actionDrawer.isOpened) {
            return false
        }

        if (rotatingInputDisabledUntil > System.currentTimeMillis()) {
            return false
        }

        if (ev.action == android.view.MotionEvent.ACTION_SCROLL && RotaryEncoderHelper.isFromRotaryEncoder(
                        ev
                )
        ) {
            val delta =
                    -RotaryEncoderHelper.getRotaryAxisValue(ev) * RotaryEncoderHelper.getScaledScrollFactor(
                            this
                    )

            if (WatchInfoSender.hasDiscreteRotaryInput()) {
                val keyCode = if (delta > 0) {
                    SpecialButtonCodes.TURN_ROTARY_CW
                } else {
                    SpecialButtonCodes.TURN_ROTARY_CCW
                }

                return stemButtonsManager.simulateKeyPress(keyCode)
            }

            // getScaledScrollFactor() is tuned for scrolling lists by a full screen-ish amount
            // per detent, so even a tiny/accidental crown nudge produces a surprisingly large
            // delta for fine-grained volume control - a deadzone filters out that noise, and the
            // base factor is well below the old list-scrolling-derived value on top of it.
            if (abs(delta) < ROTARY_DEADZONE) {
                return true
            }

            val multipler =
                    Preferences.getInt(preferences, MiscPreferences.ROTATING_CROWN_SENSITIVITY) / 100f

            binding.volumeBar.incrementVolume(delta * 0.0011f * multipler)
            viewModel.updateVolume(binding.volumeBar.volume)
            showVolumeBar()


            return true
        }

        return super.onGenericMotionEvent(ev)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Back button is handled through onBackPressedDispatcher
            return super.onKeyDown(keyCode, event)
        }

        if (binding.actionDrawer.isOpened) {
            return actionsMenuFragment.onKeyDown(keyCode, event)
        }

        if (stemButtonsManager.onKeyDown(keyCode, event)) {
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Back button is handled through onBackPressedDispatcher
            return super.onKeyDown(keyCode, event)
        }

        if (stemButtonsManager.onKeyUp(keyCode)) {
            return true
        }

        return super.onKeyUp(keyCode, event)
    }

    private fun onNotificationTapped() {
        hideNotification()
    }

    private fun hideNotificationIfOverdue() {
        if (notificationDismissDeadline < System.currentTimeMillis()) {
            hideNotification()
        }
    }

    private fun hideNotification() {
        val card = binding.notificationPopup.notificationCard
        card.animate().scaleX(0f).scaleY(0f).setDuration(200).withEndAction {
            card.visibility = View.GONE
        }.start()
        handler.removeMessages(MESSAGE_DISMISS_NOTIFICATION)
        notificationDismissDeadline = Long.MAX_VALUE
    }

    private fun showNotification(notification: Notification) {
        val timeout = Preferences.getInt(preferences, MiscPreferences.NOTIFICATION_TIMEOUT)
        val deadlineMs = timeout * 1000

        notificationDismissDeadline = notification.time + deadlineMs
        if (notificationDismissDeadline < System.currentTimeMillis()) {
            return
        }

        val card = binding.notificationPopup.notificationCard
        card.animate().scaleX(1f).scaleY(1f).setDuration(200).withStartAction {
            card.visibility = View.VISIBLE
        }.start()

        handler.removeMessages(MESSAGE_DISMISS_NOTIFICATION)
        if (timeout > 0) {
            handler.sendEmptyMessageDelayed(MESSAGE_DISMISS_NOTIFICATION, deadlineMs.toLong())
        }
    }

    /**
     * Fades in the full-screen acrylic scrim that backs both the volume and seek overlays - it
     * sits below them in the layout but above everything else, so showing it alone is enough to
     * visually hide the rest of the screen without touching every other view's visibility.
     */
    private fun showOverlay() {
        if (binding.overlayBlurImage.visibility != View.VISIBLE) {
            binding.overlayBlurImage.alpha = 0f
            binding.overlayBlurImage.visibility = View.VISIBLE
            binding.overlayBlurImage.animate().cancel()
            binding.overlayBlurImage.animate().alpha(1f).setDuration(OVERLAY_FADE_IN_MS).start()

            binding.overlayDim.alpha = 0f
            binding.overlayDim.visibility = View.VISIBLE
            binding.overlayDim.animate().cancel()
            binding.overlayDim.animate().alpha(1f).setDuration(OVERLAY_FADE_IN_MS).start()
        }
    }

    private fun hideOverlay() {
        binding.overlayBlurImage.animate().cancel()
        binding.overlayBlurImage.animate()
                .alpha(0f)
                .setDuration(OVERLAY_FADE_OUT_MS)
                .withEndAction { binding.overlayBlurImage.visibility = View.GONE }
                .start()

        binding.overlayDim.animate().cancel()
        binding.overlayDim.animate()
                .alpha(0f)
                .setDuration(OVERLAY_FADE_OUT_MS)
                .withEndAction { binding.overlayDim.visibility = View.GONE }
                .start()

        binding.volumeBar.animate().cancel()
        binding.volumeBar.animate()
                .alpha(0f)
                .setDuration(OVERLAY_FADE_OUT_MS)
                .withEndAction { binding.volumeBar.visibility = View.GONE }
                .start()

        binding.seekBar.animate().cancel()
        binding.seekBar.animate().alpha(1f).setDuration(OVERLAY_FADE_OUT_MS).start()

        binding.textVolumePercent.visibility = View.GONE
        binding.textSeekTime.visibility = View.GONE
        binding.volumeIconTop.visibility = View.GONE
        binding.volumeIconBottom.visibility = View.GONE
        binding.quickActionsPanel.visibility = View.GONE
        quickActionsPanelBackCallback.isEnabled = false

        handler.removeMessages(MESSAGE_HIDE_VOLUME)
    }

    /** Opened by double-tapping center_tap_zone - like/shuffle/repeat shortcuts plus a way into
     *  the queue, on top of the same blur/dim scrim the volume and seek previews use. Stays open
     *  until the user taps outside it, taps Up Next, or presses back - it does not auto-hide. */
    private fun showQuickActionsPanel() {
        showOverlay()

        binding.seekBar.animate().cancel()
        binding.seekBar.animate().alpha(0f).setDuration(OVERLAY_FADE_IN_MS).start()

        binding.textVolumePercent.visibility = View.GONE
        binding.textSeekTime.visibility = View.GONE
        binding.quickActionsPanel.visibility = View.VISIBLE
        quickActionsPanelBackCallback.isEnabled = true

        binding.quickActionPanelTitle.text = binding.textTitle.text
        binding.quickActionPanelArtist.text = binding.textArtist.text
        binding.quickActionPanelArtist.setTextColor(currentAccentColor)
        binding.quickActionPanelArtist.visibility =
                if (binding.quickActionPanelArtist.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        updateQuickActionButtonStates()

        // Show whatever was cached from a previous fetch immediately, then ask the phone for a
        // fresh queue snapshot in the background - customListListener() will update the preview
        // text in place without yanking the user into the full drawer while this panel is open.
        viewModel.customList.value?.let { updateUpNextPreview(it) }
        viewModel.openPlaybackQueue()
    }

    private fun isQuickActionsPanelShowing() = binding.quickActionsPanel.visibility == View.VISIBLE

    private fun accentCircleDrawable(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(currentAccentColor)
    }

    /** White icons can disappear against a light album-art accent color, so the icon itself
     *  flips to black/white depending on how light or dark [backgroundColor] is. */
    private fun contrastingIconColor(backgroundColor: Int): Int =
            if (ColorUtils.calculateLuminance(backgroundColor) > 0.5) Color.BLACK else Color.WHITE

    private fun setQuickActionButtonActive(view: ImageView, active: Boolean) {
        if (active) {
            view.background = accentCircleDrawable()
            view.setColorFilter(contrastingIconColor(currentAccentColor))
        } else {
            view.background = AppCompatResources.getDrawable(this, R.drawable.glass_pill_background)
            view.clearColorFilter()
        }
    }

    /** Reflects confirmed shuffle/repeat/like state (from the phone) on the three buttons.
     *  Shuffle/repeat are reliable (real MediaSession state); "liked" is a best-effort guess
     *  since there's no generic cross-app API for it - see LikeAction.isCurrentlyLiked(). */
    private fun updateQuickActionButtonStates() {
        setQuickActionButtonActive(binding.quickActionLike, liked)
        setQuickActionButtonActive(binding.quickActionShuffle, shuffleEnabled)
        setQuickActionButtonActive(binding.quickActionRepeat, repeatMode != 0)
    }

    private val quickActionPressFeedback = View.OnTouchListener { v, event ->
        val imageView = v as ImageView
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                imageView.background = accentCircleDrawable()
                imageView.setColorFilter(contrastingIconColor(currentAccentColor))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> when (imageView) {
                binding.quickActionLike -> setQuickActionButtonActive(imageView, liked)
                binding.quickActionShuffle -> setQuickActionButtonActive(imageView, shuffleEnabled)
                binding.quickActionRepeat -> setQuickActionButtonActive(imageView, repeatMode != 0)
                else -> setQuickActionButtonActive(imageView, false)
            }
        }
        false
    }

    private fun updateUpNextPreview(data: CustomListWithBitmaps) {
        // History (the fallback shown when the playing app exposes no real queue) is backward
        // looking - there's no "next" track to preview in that case.
        val nextTrack = if (data.listId == CustomLists.PLAYLIST) {
            data.items.firstOrNull()?.listItem?.entryTitle
        } else {
            null
        }

        binding.quickActionUpNextTrack.apply {
            if (nextTrack.isNullOrEmpty()) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = nextTrack
            }
        }
    }

    private fun showVolumeBar() {
        showOverlay()

        if (binding.volumeBar.visibility != View.VISIBLE) {
            binding.volumeBar.alpha = 0f
            binding.volumeBar.visibility = View.VISIBLE
            binding.volumeBar.animate().cancel()
            binding.volumeBar.animate().alpha(1f).setDuration(OVERLAY_FADE_OUT_MS).start()
        }

        // The two rings share the same accent color and would otherwise both be visible at
        // once now that they're drawn on top of the blur overlay - only one should show at a time.
        binding.seekBar.animate().cancel()
        binding.seekBar.animate().alpha(0f).setDuration(OVERLAY_FADE_IN_MS).start()

        binding.textSeekTime.visibility = View.GONE
        binding.textVolumePercent.visibility = View.VISIBLE
        binding.textVolumePercent.text = getString(
                R.string.volume_percent_format,
                (binding.volumeBar.volume * 100).roundToInt()
        )
        binding.volumeIconTop.visibility = View.VISIBLE
        binding.volumeIconBottom.visibility = View.VISIBLE

        handler.removeMessages(MESSAGE_HIDE_VOLUME)
        handler.sendEmptyMessageDelayed(MESSAGE_HIDE_VOLUME, VOLUME_BAR_TIMEOUT)
    }

    private fun showSeekOverlay(fraction: Float) {
        showOverlay()

        binding.textVolumePercent.visibility = View.GONE
        binding.textSeekTime.visibility = View.VISIBLE
        binding.textSeekTime.text = formatPlaybackTime((fraction * lastKnownDurationMs).toLong())
    }

    fun buzz() {
        if (!Preferences.getBoolean(preferences, MiscPreferences.HAPTIC_FEEDBACK)) {
            return
        }

        VibratorCompat.vibrate(vibrator, 50)
    }

    override fun onUpwardsSwipe() {
        Timber.d("UpwardsSwipe")

        binding.actionDrawer.controller.openDrawer()
        openDefaultListInDrawer()
    }

    override fun onSingleTap(quadrant: Int) {
        buzz()

        viewModel.executeAction(ButtonInfo(false, quadrant, GESTURE_SINGLE_TAP))
    }

    override fun onDoubleTap(quadrant: Int) {
        // Single tap vibration has delay, because it needs to wait to see if user presses
        // for the second time.
        // Introduce similar delay to double tap vibration to make it more apparent to the user
        // that he double pressed
        handler.postDelayed(this::buzz, ViewConfiguration.getDoubleTapTimeout().toLong())
        viewModel.executeAction(ButtonInfo(false, quadrant, GESTURE_DOUBLE_TAP))
    }

    override fun onLongTap(quadrant: Int) {
        buzz()
        viewModel.executeAction(ButtonInfo(false, quadrant, GESTURE_LONG_TAP))
    }

    private class TimeoutsHandler(val activity: WeakReference<MainActivity>) :
            Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MESSAGE_HIDE_VOLUME -> {
                    activity.get()?.hideOverlay()
                }
                MESSAGE_UPDATE_CLOCK -> {
                    removeMessages(MESSAGE_UPDATE_CLOCK)

                    val activity = activity.get() ?: return

                    activity.updateClock()

                    if (!activity.ambientController.isAmbient &&
                            Preferences.getBoolean(
                                    activity.preferences,
                                    MiscPreferences.ALWAYS_SHOW_TIME
                            )
                    ) {
                        sendEmptyMessageDelayed(MESSAGE_UPDATE_CLOCK, 60_000)
                    }
                }
                MESSAGE_DISMISS_NOTIFICATION -> {
                    activity.get()?.hideNotification()
                }
                else -> super.handleMessage(msg)
            }

        }
    }

    override fun getPhoneAppPresenceCapability(): String = CommPaths.PHONE_APP_CAPABILITY

    private class MusicServiceConnection(private val lifecycle: Lifecycle) : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            service as WatchMusicService.Binder

            lifecycle.coroutineScope.launch {
                service.uiOpenFlow.flowWithLifecycle(lifecycle).collect()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
        }

    }
}
