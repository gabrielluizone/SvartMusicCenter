package com.matejdro.wearmusiccenter.music

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.preference.PreferenceManager
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.matejdro.wearmusiccenter.R
import com.matejdro.wearmusiccenter.actions.ActionHandler
import com.matejdro.wearmusiccenter.actions.OpenPlaylistAction
import com.matejdro.wearmusiccenter.actions.PhoneAction
import com.matejdro.wearmusiccenter.actions.playback.LikeAction
import com.matejdro.wearmusiccenter.actions.playback.RepeatAction
import com.matejdro.wearmusiccenter.actions.playback.ShuffleAction
import com.matejdro.wearmusiccenter.common.CommPaths
import com.matejdro.wearmusiccenter.common.CustomLists
import com.matejdro.wearmusiccenter.common.MiscPreferences
import com.matejdro.wearmusiccenter.common.buttonconfig.ButtonInfo
import com.matejdro.wearmusiccenter.common.util.FloatPacker
import com.matejdro.wearmusiccenter.config.ActionConfig
import com.matejdro.wearmusiccenter.config.WatchInfoProvider
import com.matejdro.wearmusiccenter.config.WatchInfoWithIcons
import com.matejdro.wearmusiccenter.di.GlobalConfig
import com.matejdro.wearmusiccenter.di.MusicServiceSubComponent
import com.matejdro.wearmusiccenter.notifications.NotificationProvider
import com.matejdro.wearmusiccenter.proto.CustomListItemAction
import com.matejdro.wearmusiccenter.proto.MusicState
import com.matejdro.wearmusiccenter.proto.WatchActions
import com.matejdro.wearmusiccenter.util.launchWithPlayServicesErrorHandling
import com.matejdro.wearutils.lifecycle.EmptyObserver
import com.matejdro.wearutils.lifecycle.Resource
import com.matejdro.wearutils.miscutils.BitmapUtils
import com.matejdro.wearutils.preferences.definition.Preferences
import com.matejdro.wearvibrationcenter.notificationprovider.ReceivedNotification
import dagger.android.AndroidInjection
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import com.matejdro.common.R as commonR

data class TrackHistoryEntry(val artist: String, val title: String)

class MusicService : LifecycleService(), MessageClient.OnMessageReceivedListener {
    companion object {
        const val ACTION_START_FROM_WATCH = "START_FROM_WATCH"
        const val ACTION_NOTIFICATION_SERVICE_ACTIVATED = "NOTIFICATION_SERVICE_ACTIVATED"

        private const val MESSAGE_STOP_SELF = 0
        private val ACK_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(3)
        private const val SEEK_DETECTION_THRESHOLD_MS = 1500L
        private const val MAX_TRACK_HISTORY_SIZE = 20

        private const val STOP_SELF_PENDING_INTENT_REQUEST_CODE = 333
        private const val ACTION_STOP_SELF = "STOP_SELF"
        private const val KEY_NOTIFICATION_CHANNEL = "Service_Channel"
        private const val KEY_NOTIFICATION_CHANNEL_ERRORS = "Error notifications"

        private const val NOTIFICATION_ID_PERSISTENT = 1
        private const val NOTIFICATION_ID_SERVICE_ERROR = 2

        var active = false
            private set
    }

    private lateinit var messageClient: MessageClient
    private lateinit var dataClient: DataClient

    private lateinit var preferences: SharedPreferences

    @Inject
    lateinit var mediaSessionProvider: ActiveMediaSessionProvider

    @Inject
    @GlobalConfig
    lateinit var config: ActionConfig

    @Inject
    lateinit var watchInfoProvider: WatchInfoProvider

    @Inject
    lateinit var notificationProvider: NotificationProvider

    @Inject
    lateinit var musicServiceComponentFactory: MusicServiceSubComponent.Factory

    private lateinit var actionHandlers: Map<Class<*>, ActionHandler<*>>

    private var ackTimeoutHandler = AckTimeoutHandler(WeakReference(this))

    private var previousMusicState: MusicState? = null
    private var previousAlbumArt: Bitmap? = null
    var currentMediaController: MediaController? = null
    private var startedFromWatch = false

    private var lastTrackArtist = ""
    private var lastTrackTitle = ""

    /** Most recently played tracks, newest first. Used as a fallback when [MediaController.getQueue] is unavailable. */
    val recentTrackHistory = ArrayDeque<TrackHistoryEntry>()

    private var currentVolume = 0

    @SuppressLint("LaunchActivityFromNotification")
    override fun onCreate() {
        super.onCreate()

        AndroidInjection.inject(this)
        actionHandlers = musicServiceComponentFactory.build(this).getActionHandlers()

        messageClient = Wearable.getMessageClient(applicationContext)
        dataClient = Wearable.getDataClient(applicationContext)

        preferences = PreferenceManager.getDefaultSharedPreferences(this)

        messageClient.addListener(this, Uri.parse(CommPaths.MESSAGES_PREFIX), MessageClient.FILTER_PREFIX)

        mediaSessionProvider = ActiveMediaSessionProvider(this)
        mediaSessionProvider.observe(this, mediaCallback)

        watchInfoProvider.observe(this, EmptyObserver<WatchInfoWithIcons>())

        if (Preferences.getBoolean(preferences, MiscPreferences.ENABLE_NOTIFICATION_POPUP)) {
            notificationProvider.observe(this, notificationCallback)
        }

        val stopSelfIntent = Intent(this, MusicService::class.java)
        stopSelfIntent.action = ACTION_STOP_SELF

        val stopSelfPendingIntent = PendingIntent.getService(this,
                STOP_SELF_PENDING_INTENT_REQUEST_CODE,
                stopSelfIntent,
                PendingIntent.FLAG_UPDATE_CURRENT)

        createNotificationChannel()
        val notificationBuilder = NotificationCompat.Builder(this, KEY_NOTIFICATION_CHANNEL)
                .setContentTitle(getString(commonR.string.music_control_active))
                .setContentText(getString(R.string.tap_to_force_stop))
                .setContentIntent(stopSelfPendingIntent)
                .setSmallIcon(commonR.drawable.ic_notification)

        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeContentObserver)


        // This is still needed for Pre-O versions, so it must be used, even if it is deprecated.
        @Suppress("DEPRECATION")
        notificationBuilder.priority = Notification.PRIORITY_MIN

        startForeground(NOTIFICATION_ID_PERSISTENT, notificationBuilder.build())

        active = true
        Timber.d("Service started")
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_START_FROM_WATCH) {
            startedFromWatch = true
        } else if (action == ACTION_STOP_SELF || !startedFromWatch) {
            stopSelf()
            return Service.START_NOT_STICKY
        } else if (action == ACTION_NOTIFICATION_SERVICE_ACTIVATED) {
            mediaSessionProvider.activate()
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID_SERVICE_ERROR)
        }

        super.onStartCommand(intent, flags, startId)
        return Service.START_STICKY
    }

    override fun onDestroy() {
        Timber.d("Service stopped")

        messageClient.removeListener(this)

        ackTimeoutHandler.removeCallbacksAndMessages(null)
        contentResolver.unregisterContentObserver(volumeContentObserver)

        active = false

        super.onDestroy()
    }

    private val mediaCallback = Observer<Resource<MediaController>?> {
        when {
            it == null -> {
                buildMusicStateAndTransmit(null)
            }
            it.status == Resource.Status.ERROR -> {
                transmitError(it.message ?: "")

                if (it.message == getString(R.string.error_notification_access)) {
                    showNotificationServiceErrorNotification()
                }
            }
            else -> {
                currentMediaController = it.data
                buildMusicStateAndTransmit(currentMediaController)
            }
        }
    }

    private val notificationCallback = Observer<ReceivedNotification?> {
        if (it == null) {
            return@Observer
        }

        val putDataRequest = PutDataRequest.create(CommPaths.DATA_NOTIFICATION)

        val protoNotification = com.matejdro.wearmusiccenter.proto.Notification.newBuilder()
                .setTitle(it.title.trim())
                .setDescription(it.description.trim())
                .setTime(System.currentTimeMillis().toInt())
                .build()

        it.imageDataPng?.let { imageData ->
            val albumArtAsset = Asset.createFromBytes(imageData)
            putDataRequest.putAsset(CommPaths.ASSET_NOTIFICATION_BACKGROUND, albumArtAsset)
        }

        putDataRequest.data = protoNotification.toByteArray()
        putDataRequest.setUrgent()

        lifecycleScope.launchWithPlayServicesErrorHandling(this) {
            dataClient.putDataItem(putDataRequest).await()
        }

        startTimeout()
    }

    private fun updateVolume(newVolume: Float) {
        val previousMediaController = currentMediaController ?: return

        val maxVolume = previousMediaController.playbackInfo?.maxVolume ?: 0
        val newAbsoluteVolume = (maxVolume * newVolume).toInt()
        currentVolume = newAbsoluteVolume
        previousMediaController.setVolumeTo(newAbsoluteVolume, 0)
    }

    private fun seekTo(positionMs: Long) {
        currentMediaController?.transportControls?.seekTo(positionMs)
    }

    private fun togglePlayPause() {
        currentMediaController?.let {
            it.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            it.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        }
    }

    /** Drives the watch's quick-actions panel - these always work, regardless of button config. */
    private fun executeQuickAction(name: String) {
        val action: PhoneAction = when (name) {
            "like" -> LikeAction(this)
            "shuffle" -> ShuffleAction(this)
            "repeat" -> RepeatAction(this)
            else -> return
        }

        executeAction(action)
    }

    private fun executeAction(buttonInfo: ButtonInfo) {
        val playing = currentMediaController?.isPlaying() == true

        val config = if (playing)
            config.getPlayingConfig()
        else
            config.getStoppedConfig()

        val buttonAction = config.getScreenAction(buttonInfo) ?: return
        executeAction(buttonAction)
    }

    private fun executeMenuAction(index: Int) {
        val config = config.getActionList()
        val list = config.actions

        if (index < 0 || index >= list.size) {
            Timber.e("Action out of bounds: %d", index)
            return
        }

        executeAction(list[index])
    }

    private fun executeAction(action: PhoneAction) {
        lifecycleScope.launchWithPlayServicesErrorHandling(this) {
            @Suppress("UNCHECKED_CAST")
            val handler = actionHandlers[action.javaClass] as ActionHandler<PhoneAction>?
                    ?: throw IllegalStateException("Action handler for $action missing")

            handler.handleAction(action)
        }
    }

    private fun buildMusicStateAndTransmit(mediaController: MediaController?) {
        val musicStateBuilder = MusicState.newBuilder()
        var albumArt: Bitmap? = null

        musicStateBuilder.time = System.currentTimeMillis().toInt()

        if (mediaController == null) {
            musicStateBuilder.playing = false
        } else {
            val playbackState = mediaController.playbackState

            musicStateBuilder.playing = playbackState?.isPlaying() == true

            val meta = mediaController.metadata
            if (meta != null) {
                val newArtist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                val newTitle = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""

                if (newArtist.isNotEmpty()) {
                    musicStateBuilder.artist = newArtist
                }
                if (newTitle.isNotEmpty()) {
                    musicStateBuilder.title = newTitle
                }

                recordTrackHistoryIfChanged(newArtist, newTitle)

                albumArt = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)

                val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
                if (duration > 0) {
                    musicStateBuilder.durationMs = duration
                }
            }

            if (playbackState != null) {
                musicStateBuilder.positionMs = playbackState.position

                // PlaybackState.lastPositionUpdateTime is in SystemClock.elapsedRealtime() time,
                // not wall-clock time, and the watch has no way to relate its own elapsedRealtime
                // (different device, different boot time) to ours. Convert it to an epoch
                // timestamp here so the watch can extrapolate using its own currentTimeMillis().
                val elapsedRealtimeNow = android.os.SystemClock.elapsedRealtime()
                musicStateBuilder.positionUpdateTime =
                        System.currentTimeMillis() - (elapsedRealtimeNow - playbackState.lastPositionUpdateTime)

                musicStateBuilder.playbackSpeed = playbackState.playbackSpeed
                musicStateBuilder.seekable = (playbackState.actions and PlaybackState.ACTION_SEEK_TO) != 0L
            }

            // Shuffle/repeat only exist on the AndroidX media-compat layer, not the framework
            // MediaController API - see ShuffleAction/RepeatAction for why this wrapping works.
            val compatController = MediaControllerCompat(
                    this,
                    MediaSessionCompat.Token.fromToken(mediaController.sessionToken)
            )
            musicStateBuilder.shuffleEnabled =
                    compatController.shuffleMode != PlaybackStateCompat.SHUFFLE_MODE_NONE
            musicStateBuilder.repeatMode = when (compatController.repeatMode) {
                PlaybackStateCompat.REPEAT_MODE_ALL, PlaybackStateCompat.REPEAT_MODE_GROUP -> 1
                PlaybackStateCompat.REPEAT_MODE_ONE -> 2
                else -> 0
            }

            currentVolume = mediaController.playbackInfo?.currentVolume ?: 0

            val volume = currentVolume.toFloat() / (mediaController.playbackInfo?.maxVolume?.toFloat() ?: 0f)

            musicStateBuilder.volume = volume
        }


        val musicState = musicStateBuilder.build()

        // MediaMetadata is immutable, so as long as the source app hasn't published a new
        // MediaMetadata object, repeated getBitmap() calls return the same Bitmap reference -
        // making reference equality a cheap, reliable signal for "is this actually new art".
        // This must be checked in addition to equalsIgnoringTime(): some apps publish metadata
        // in two steps (text fields, then artwork moments later); without this, the artwork-only
        // update would look "equal" on every field that comparison checks and get silently
        // dropped, leaving the watch stuck on stale (or missing) album art until some other
        // unrelated change (e.g. pause) forced a retransmit.
        val albumArtChanged = albumArt !== previousAlbumArt

        // Do not waste BT bandwitch and re-transmit equal music state
        if (!albumArtChanged && musicState.equalsIgnoringTime(previousMusicState)) {
            return
        }

        Timber.d("TransmittingToWear %s", musicState)
        previousMusicState = musicState
        previousAlbumArt = albumArt
        transmitToWear(musicState, albumArt)
    }

    private fun transmitToWear(musicState: MusicState, originalAlbumArt: Bitmap?) {
        lifecycleScope.launchWithPlayServicesErrorHandling(this) {
            val putDataRequest = PutDataRequest.create(CommPaths.DATA_MUSIC_STATE)

            var albumArt = originalAlbumArt
            val watchInfo = watchInfoProvider.value?.watchInfo
            if (watchInfo != null && albumArt != null) {
                albumArt = BitmapUtils.resizeAndCrop(albumArt,
                        watchInfo.displayWidth,
                        watchInfo.displayHeight,
                        true)
            }

            if (albumArt != null) {
                val albumArtAsset = Asset.createFromBytes(BitmapUtils.serialize(albumArt)!!)
                putDataRequest.putAsset(CommPaths.ASSET_ALBUM_ART, albumArtAsset)
            }

            putDataRequest.data = musicState.toByteArray()
            putDataRequest.setUrgent()

            dataClient.putDataItem(putDataRequest).await()
            startTimeout()
        }
    }

    private fun transmitError(error: String) = lifecycleScope.launchWithPlayServicesErrorHandling(this) {
        val musicStateBuilder = MusicState.newBuilder()

        // Add time to the first message to make sure it gets transmitted even if it is
        // identical to the previous one
        musicStateBuilder.time = System.currentTimeMillis().toInt()

        musicStateBuilder.error = true
        musicStateBuilder.title = error
        musicStateBuilder.playing = false

        val musicState = musicStateBuilder.build()

        val putDataRequest = PutDataRequest.create(CommPaths.DATA_MUSIC_STATE)

        putDataRequest.data = musicState.toByteArray()
        putDataRequest.setUrgent()

        dataClient.putDataItem(putDataRequest).await()
    }

    private fun showNotificationServiceErrorNotification() {
        val notificationManagerIntent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")

        val notificationManagerPendingIntent = PendingIntent.getActivity(this,
                STOP_SELF_PENDING_INTENT_REQUEST_CODE,
                notificationManagerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT)

        createNotificationChannel()
        val notificationBuilder = NotificationCompat.Builder(this, KEY_NOTIFICATION_CHANNEL_ERRORS)
                .setContentTitle(getString(R.string.notification_access_notification_title))
                .setContentText(getString(R.string.notification_access_notification_title_description))
                .setContentIntent(notificationManagerPendingIntent)
                .setSmallIcon(commonR.drawable.ic_notification)


        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID_SERVICE_ERROR,
                notificationBuilder.build())
    }

    private fun onWatchSwipeExited() {
        if (!Preferences.getBoolean(preferences, MiscPreferences.PAUSE_ON_SWIPE_EXIT)) {
            return
        }

        if (currentMediaController?.isPlaying() == true) {
            currentMediaController?.transportControls?.pause()
        }
    }

    private fun onCustomMenuItemPresed(customListItemAction: CustomListItemAction) {
        if (customListItemAction.entryId == CustomLists.SPECIAL_ITEM_ERROR) {
            return
        }

        when (customListItemAction.listId) {
            CustomLists.PLAYLIST -> {
                currentMediaController?.transportControls?.skipToQueueItem(
                        customListItemAction.entryId.toLong()
                )
            }
        }
    }

    private fun recordTrackHistoryIfChanged(newArtist: String, newTitle: String) {
        if (newTitle.isEmpty() || (newArtist == lastTrackArtist && newTitle == lastTrackTitle)) {
            return
        }

        if (lastTrackTitle.isNotEmpty()) {
            recentTrackHistory.addFirst(TrackHistoryEntry(lastTrackArtist, lastTrackTitle))
            while (recentTrackHistory.size > MAX_TRACK_HISTORY_SIZE) {
                recentTrackHistory.removeLast()
            }
        }

        lastTrackArtist = newArtist
        lastTrackTitle = newTitle
    }

    private fun openPlaybackQueueOnWatch() {
        executeAction(OpenPlaylistAction(this))
    }

    override fun onMessageReceived(event: MessageEvent) {
        Timber.d("Message %s", event.path)

        when (event.path) {
            CommPaths.MESSAGE_WATCH_CLOSED -> {
                stopSelf()
            }
            CommPaths.MESSAGE_ACK -> {
                ackTimeoutHandler.removeMessages(MESSAGE_STOP_SELF)
            }
            CommPaths.MESSAGE_CHANGE_VOLUME -> {
                updateVolume(FloatPacker.unpackFloat(event.data))
            }
            CommPaths.MESSAGE_SEEK_TO -> {
                seekTo(ByteBuffer.wrap(event.data).long)
            }
            CommPaths.MESSAGE_TOGGLE_PLAY_PAUSE -> {
                togglePlayPause()
            }
            CommPaths.MESSAGE_QUICK_ACTION -> {
                executeQuickAction(String(event.data, Charsets.UTF_8))
            }
            CommPaths.MESSAGE_EXECUTE_ACTION -> {
                executeAction(ButtonInfo(WatchActions.ProtoButtonInfo.parseFrom(event.data)))
            }
            CommPaths.MESSAGE_EXECUTE_MENU_ACTION -> {
                executeMenuAction(ByteBuffer.wrap(event.data).int)
            }
            CommPaths.MESSAGE_WATCH_OPENED -> {

                ackTimeoutHandler.removeMessages(MESSAGE_STOP_SELF)
                buildMusicStateAndTransmit(currentMediaController)
            }
            CommPaths.MESSAGE_WATCH_CLOSED_MANUALLY -> {
                onWatchSwipeExited()
            }
            CommPaths.MESSAGE_CUSTOM_LIST_ITEM_SELECTED -> {
                onCustomMenuItemPresed(CustomListItemAction.parseFrom(event.data))
            }
            CommPaths.MESSAGE_OPEN_PLAYBACK_QUEUE -> {
                openPlaybackQueueOnWatch()
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager


        val persistentChannel = NotificationChannel(KEY_NOTIFICATION_CHANNEL,
                getString(commonR.string.music_control),
                NotificationManager.IMPORTANCE_MIN)
        notificationManager.createNotificationChannel(persistentChannel)

        val errorChannel = NotificationChannel(KEY_NOTIFICATION_CHANNEL_ERRORS,
                getString(R.string.error_notifications),
                NotificationManager.IMPORTANCE_DEFAULT)
        notificationManager.createNotificationChannel(errorChannel)
    }

    private fun startTimeout() {
        ackTimeoutHandler.removeMessages(MESSAGE_STOP_SELF)
    }

    private class AckTimeoutHandler(val service: WeakReference<MusicService>) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == MESSAGE_STOP_SELF) {
                Timber.d("TIMEOUT!")
                service.get()?.stopSelf()
            }
        }
    }

    private fun MusicState.equalsIgnoringTime(other: MusicState?): Boolean {
        if (other == null ||
                other.playing != playing ||
                other.artist != artist ||
                other.title != title ||
                other.volume != volume ||
                other.error != error ||
                other.durationMs != durationMs ||
                other.seekable != seekable
        ) {
            return false
        }

        // positionMs naturally drifts forward every time playback is polled, so comparing it
        // directly would defeat the point of this check. Instead, extrapolate where playback
        // "should" be based on the previous state and only treat a bigger-than-expected jump
        // (a real seek, or a track restart) as a change worth re-transmitting for.
        val elapsedSincePrevious = positionUpdateTime - other.positionUpdateTime
        val expectedPosition = if (other.playing) {
            other.positionMs + (elapsedSincePrevious * other.playbackSpeed).toLong()
        } else {
            other.positionMs
        }

        return Math.abs(positionMs - expectedPosition) <= SEEK_DETECTION_THRESHOLD_MS
    }

    private val volumeContentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            val newVolume = currentMediaController?.playbackInfo?.currentVolume
            if (newVolume != currentVolume) {
                buildMusicStateAndTransmit(currentMediaController)
            }
        }
    }
}
