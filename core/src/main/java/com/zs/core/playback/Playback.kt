@file:SuppressLint("UnsafeOptInUsageError")

/*
 * Copyright 2024 Zakir Sheikh
 *
 * Created by 2024 on 30-09-2024.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zs.core.playback

import android.annotation.SuppressLint
import android.app.*
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.audiofx.Equalizer
import android.media.audiofx.Equalizer.Settings
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.media3.common.*
import androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.session.*
import androidx.media3.session.MediaLibraryService.MediaLibrarySession.Callback
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSession.ControllerInfo
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.zs.core.db.Playlist
import com.zs.core.get
import com.zs.core.getValue
import com.zs.core.set
import kotlinx.coroutines.*
import kotlin.String
import kotlin.random.Random
import com.zs.core.db.Playlists2 as Playlists

class Playback : MediaLibraryService(), Callback, Player.Listener {

    /**
     * This companion object contains constants and static values used throughout the application.
     * It includes playlist identifiers, action strings, and extra keys for intents.
     *
     * @property PLAYLIST_FAVOURITE The name of the global 'Favourite' playlist in [Playlists].
     * @property PLAYLIST_RECENT The name of the global 'Recent' playlist in [Playlists].
     * @property PLAYLIST_QUEUE The name of the global 'Queue' playlist in [Playlists].
     * @property ROOT_QUEUE The root identifier for the queue-related content served by this service.
     *                      Used as a key to access queue-related content in the application's data structure.
     * @property SAVE_POSITION_DELAY_MILLS Delay in milliseconds for saving position.
     * @property ACTION_SCHEDULE_SLEEP_TIME Action string for scheduling sleep time.
     * @property EXTRA_SCHEDULED_TIME_MILLS Extra key for scheduled time in milliseconds.
     * @property UNINITIALIZED_SLEEP_TIME_MILLIS Constant for uninitialized sleep time in milliseconds.
     * @property ACTION_AUDIO_SESSION_ID  A action string for [SessionCommand] for getting
     *                                    [EXTRA_AUDIO_SESSION_ID].
     *                                    The client can use this action to send a custom command to
     *                                    the service and request the current audio session id.
     *
     * @property EXTRA_AUDIO_SESSION_ID Extra key for audio session ID.
     * @property ACTION_EQUALIZER_CONFIG Action string for equalizer configuration.
     * @property EXTRA_EQUALIZER_ENABLED Extra key for equalizer enabled state.
     * @property EXTRA_EQUALIZER_PROPERTIES Extra key for equalizer properties.
     */
    companion object {
        private const val TAG = "Playback"

        // The standard global playlists.
        val PLAYLIST_FAVOURITE = Playlists.PRIVATE_PLAYLIST_PREFIX + "favourite"
        val PLAYLIST_RECENT = Playlists.PRIVATE_PLAYLIST_PREFIX + "recent"
        internal val PLAYLIST_QUEUE = Playlists.PRIVATE_PLAYLIST_PREFIX + "queue"

        // The roots for accessing global playlists
        const val ROOT_QUEUE = "com.prime.player.queue"

        // Keys for SharedPreferences.
        private val PREF_KEY_SHUFFLE_MODE = "_shuffle"
        private val PREF_KEY_REPEAT_MODE = "_repeat_mode"
        private val PREF_KEY_INDEX = "_index"
        private val PREF_KEY_BOOKMARK = "_bookmark"
        private val PREF_KEY_RECENT_PLAYLIST_LIMIT = "_max_recent_size"
        private val PREF_KEY_EQUALIZER_ENABLED = "_equalizer_enabled"
        private val PREF_KEY_EQUALIZER_PROPERTIES = "_equalizer_properties"
        private val PREF_KEY_CLOSE_WHEN_REMOVED = "_stop_playback_when_removed"
        private val PREF_KEY_ORDERS = "_orders"

        //
        private const val SAVE_POSITION_DELAY_MILLS = 5_000L

        //
        private const val PREFIX = "com.prime.player"
        const val ACTION_AUDIO_SESSION_ID = "$PREFIX.action.AUDIO_SESSION_ID"
        const val EXTRA_AUDIO_SESSION_ID = "$PREFIX.extra.AUDIO_SESSION_ID"
        const val ACTION_SCHEDULE_SLEEP_TIME = "$PREFIX.action.SCHEDULE_SLEEP_TIME"
        const val EXTRA_SCHEDULED_TIME_MILLS = "$PREFIX.extra.AUDIO_SESSION_ID"
        const val UNINITIALIZED_SLEEP_TIME_MILLIS = -1L
        const val ACTION_EQUALIZER_CONFIG = "$PREFIX.extra.EQUALIZER"
        const val EXTRA_EQUALIZER_ENABLED = "$PREFIX.extra.EXTRA_EQUALIZER_ENABLED"
        const val EXTRA_EQUALIZER_PROPERTIES = "$PREFIX.extra.EXTRA_EQUALIZER_PROPERTIES"

        //
        private val LIST_ITEM_DELIMITER = ';'
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    lateinit var preferences: SharedPreferences
    lateinit var playlists: Playlists

    /**
     *  A coroutine job that monitors the playback state and performs some actions based on it.
     *  It saves the current playback position every 5 seconds and pauses the player if the sleep time is reached.
     *  The job is cancelled when the service is destroyed or the playback is stopped.
     */
    private var playbackMonitorJob: Job? = null

    /**
     * The timestamp, in milliseconds, representing the scheduled time to pause playback.
     * This variable is used to store the timestamp when playback should be paused in the future.
     * If no future pause is scheduled, the value is set to -1.
     */
    private var scheduledPauseTimeMillis = UNINITIALIZED_SLEEP_TIME_MILLIS

    /**
     * The PendingIntent associated with the underlying activity.
     * It launches the main activity when triggered.
     */
    private val activity by lazy {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * Lazily initializes and configures an ExoPlayer instance for audio playback.
     * The player is configured with audio attributes suitable for music content,
     * and it automatically handles audio becoming noisy.
     */
    private val player: Player by lazy {
        // Create a Renderer factory. Use DynamicRendererFactory if available,
        // otherwise use DefaultRenderersFactory.
        // Enable decoder fallback to handle different media formats.
        val factory = DynamicRendererFactory(applicationContext)
            ?: DefaultRenderersFactory(applicationContext)
        factory.setEnableDecoderFallback(true)
        factory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val attr = AudioAttributes.Builder()
            .setContentType(AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        ExoPlayer.Builder(this)
            .setRenderersFactory(factory)
            .setAudioAttributes(attr, true)
            .setHandleAudioBecomingNoisy(true).build()
    }

    /**
     * Lazily initializes a [MediaLibrarySession] for managing media playback.
     * It restores the saved state, adds the current class as a listener to the player,
     * and sets the session activity using the [activity] PendingIntent.
     */
    private val session: MediaLibrarySession by lazy {
        // Build and configure the MediaLibrarySession
        MediaLibrarySession.Builder(this, player, this)
            .setSessionActivity(activity)
            .build()
    }

    /**
     * Manages the audio equalizer for enhancing audio effects.
     *
     * This class is responsible for configuring and controlling the audio equalizer
     * to improve the quality of audio output.
     *
     * @see ACTION_EQUALIZER_CONFIG
     */
    private var equalizer: Equalizer? = null

    /**
     * Init this service.
     */
    override fun onCreate() {
        super.onCreate()
        playlists = Playlists(this)
        preferences = getSharedPreferences("playback_prefs", Context.MODE_PRIVATE)
        // Initialize the SharedPreferences and Playlists
        scope.launch {
            // Asynchronously restore the saved state of the service
            runCatching {
                // Restore shuffle mode and repeat mode
                player.shuffleModeEnabled = preferences[PREF_KEY_SHUFFLE_MODE, false]
                player.repeatMode = preferences[PREF_KEY_REPEAT_MODE, Player.REPEAT_MODE_OFF]
                // Set media items from the QUEUE playlist
                val items = withContext(Dispatchers.IO) {
                    val data = playlists.getMembers(PLAYLIST_QUEUE)
                    // FIXME - Disable video from appearing in history once app restarts;
                    //  This is because of the unknown ANR.
                    //  Once the ANR is fixed this must be removed.
                    if (data.any { it.mimeType?.startsWith("video") == true })
                        error("restoring video queue is not supported!!")
                    data.map(Playlist.Track::toMediaSource)
                }
                player.setMediaItems(items)
                // Restore the saved shuffle order
                val orders by runCatching {
                    val value = preferences[PREF_KEY_ORDERS, ""].split(LIST_ITEM_DELIMITER)
                    if (value.isEmpty()) null else value.map(String::toInt).toIntArray()
                }
                (player as ExoPlayer).setShuffleOrder(
                    DefaultShuffleOrder(orders ?: IntArray(0), Random.nextLong())
                )
                // Seek to the saved playback position
                val index = preferences[PREF_KEY_INDEX, C.INDEX_UNSET]
                if (index != C.INDEX_UNSET) {
                    player.seekTo(index, preferences[PREF_KEY_BOOKMARK, C.TIME_UNSET])
                    // Now if the currentMediaItem is 3rd party uri.
                    // just remove it.
                    if (player.currentMediaItem?.mediaUri?.isThirdPartyUri == true)
                        player.removeMediaItem(index)
                }
            }
            // Regardless of whether errors occurred during state restoration or not, add the current
            // class as a player listener
            player.addListener(this@Playback)
            // Initialize the audio effects;
            onAudioSessionIdChanged(-1)
        }
    }

    /**
     * Returns the session associated with the provided [controllerInfo].
     * @param controllerInfo The controller information.
     * @return The associated session.
     */
    override fun onGetSession(controllerInfo: ControllerInfo) = session

    /**
     * Returns a list of media items converted to media sources to be added to the player.
     *
     * @param mediaSession The media session.
     * @param controller The controller information.
     * @param mediaItems The list of media items to be added.
     * @return A ListenableFuture containing the list of media sources.
     */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: ControllerInfo,
        mediaItems: MutableList<MediaItem>
    ): ListenableFuture<List<MediaItem>> =
        Futures.immediateFuture(mediaItems.map(MediaItem::asMediaSource))

    // FIXME: The impact of this function on the application's behavior is unclear.
    // It returns a library result of a media item with the [BROWSER_ROOT_QUEUE] identifier and optional parameters.
    @SuppressLint("UnsafeOptInUsageError")
    override fun onGetLibraryRoot(
        session: MediaLibrarySession, browser: ControllerInfo, params: LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(LibraryResult.ofItem(MediaRoot(ROOT_QUEUE), params))

    /**
     * Returns the individual media item identified by the given [mediaId].
     *
     * @param session The media library session.
     * @param browser The controller information.
     * @param mediaId The unique identifier of the media item to retrieve.
     * @return A ListenableFuture containing the library result of the specified media item.
     */
    override fun onGetItem(
        session: MediaLibrarySession, browser: ControllerInfo, mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val item = player.mediaItems.find { it.mediaId == mediaId }
        val result = if (item == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        else LibraryResult.ofItem(item, /* params = */ null)
        return Futures.immediateFuture(result)
    }

    /**
     * TODO: Determine how to return the playing queue with upcoming items only.
     * This function is intended to handle subscriptions to media items under a parent.
     *
     * @param session The media library session.
     * @param browser The controller information.
     * @param parentId The unique identifier of the parent item.
     * @param params Optional library parameters.
     * @return A ListenableFuture containing the library result.
     */
    override fun onSubscribe(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        parentId: String,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        val children = when (parentId) {
            ROOT_QUEUE -> player.queue
            else -> return Futures.immediateFuture(
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            )
        }
        session.notifyChildrenChanged(browser, parentId, children.size, params)
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    /**
     * TODO: Consider adding support for paging to efficiently retrieve children.
     *
     * This function retrieves the children of a specified parent item for a given page and page size.
     *
     * @see MediaLibraryService.onGetChildren
     */
    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val children = when (parentId) {
            ROOT_QUEUE -> player.queue
            else -> return Futures.immediateFuture(
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            )
        }
        return Futures.immediateFuture(LibraryResult.ofItemList(children, params))
    }

    /**
     * Called when a media item transition occurs in the player.
     *
     * @param mediaItem The new media item being played.
     * @param reason The reason for the media item transition.
     */
    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int
    ) {
        // save current index in preference
        preferences[PREF_KEY_INDEX] = player.currentMediaItemIndex

        // Add the media item to the "recent" playlist if the mediaItem is not null
        // and its media URI is not from a third-party source. Third-party content URIs
        // are unplayable after an app reboot.
        // FIXME - Disabling saving of videos in history for now.
        if (mediaItem == null || mediaItem.mediaUri?.isThirdPartyUri == true)
            return
        if (mediaItem.mimeType?.startsWith("video/") == true)
            return
        // else save file in history
        val limit = preferences[PREF_KEY_RECENT_PLAYLIST_LIMIT, 50]
        scope.launch(Dispatchers.IO) { playlists.addToRecent(mediaItem, limit.toLong()) }
        session.notifyChildrenChanged(ROOT_QUEUE, 0, null)
    }

    /**
     * Called when the shuffle mode is enabled or disabled in the player.
     *
     * @param shuffleModeEnabled True if shuffle mode is enabled, false otherwise.
     */
    override fun onShuffleModeEnabledChanged(
        shuffleModeEnabled: Boolean
    ) {
        preferences[PREF_KEY_SHUFFLE_MODE] = shuffleModeEnabled
        session.notifyChildrenChanged(ROOT_QUEUE, 0, null)
    }

    /**
     * Called when the repeat mode is changed in the player.
     *
     * @param repeatMode The new repeat mode (e.g., [Player.REPEAT_MODE_OFF], [Player.REPEAT_MODE_ONE], [Player.REPEAT_MODE_ALL]).
     */
    override fun onRepeatModeChanged(
        repeatMode: Int
    ) {
        preferences[PREF_KEY_REPEAT_MODE] = repeatMode
    }

    /**
     * Called when the player's timeline changes, indicating a change in the playlist.
     *
     * @param timeline The new timeline of media items.
     * @param reason The reason for the timeline change (e.g., [Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED]).
     */
    override fun onTimelineChanged(
        timeline: Timeline,
        reason: Int
    ) {
        // construct list and update.
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {

            //Obtain the media_items to be saved.
            val items = player.mediaItems

            // Save the media_items in the playlist on change in time line
            scope.launch(Dispatchers.IO) { playlists.save(items) }

            // Save the orders in preferences
            preferences[PREF_KEY_ORDERS] = player.orders.joinToString("$LIST_ITEM_DELIMITER")
            session.notifyChildrenChanged(ROOT_QUEUE, 0, null)
        }
    }

    /**
     * Called when an update to the media session's notification is required, possibly to start it in foreground mode.
     *
     * @param session The media session.
     * @param startInForegroundRequired True if the notification should be started in foreground mode.
     */
    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean
    ) {
        super.onUpdateNotification(session, startInForegroundRequired)

        // Send an intent for updating the widget
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
            .setPackage("com.prime.player")
        //intent.setAction()

        // Retrieve widget IDs
        val ids = AppWidgetManager.getInstance(application)
            .getAppWidgetIds(ComponentName(application, "com.prime.media.old.console.Widget"))
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)

        // Broadcast the intent to update the widget
        sendBroadcast(intent)
    }

    /**
     * Called when a player error occurs.
     *
     * @param error The playback exception representing the error.
     */
    override fun onPlayerError(
        error: PlaybackException
    ) {
        // Display a simple toast message indicating an unplayable file
        Toast.makeText(this, "Unplayable file", Toast.LENGTH_SHORT).show()
        // You may choose to handle the error here or take other actions like seeking to the next media item
        player.seekToNextMediaItem()
    }

    override fun onPlayWhenReadyChanged(isPlaying: Boolean, reason: Int) {
        super.onPlayWhenReadyChanged(isPlaying, reason)
        //  Just cancel the job in case is playing false
        if (!isPlaying) {
            playbackMonitorJob?.cancel()
            // change this to uninitialized
            scheduledPauseTimeMillis = UNINITIALIZED_SLEEP_TIME_MILLIS
        }
        // Launch a new job
        else playbackMonitorJob = scope.launch {
            var isPlaying = player.playWhenReady
            do {
                // Save the current playback position to preferences
                preferences[PREF_KEY_BOOKMARK] = player.currentPosition
                Log.i(TAG, "Saved playback position: ${player.currentPosition}")

                // Check if playback is scheduled to be paused.
                if (scheduledPauseTimeMillis != UNINITIALIZED_SLEEP_TIME_MILLIS && scheduledPauseTimeMillis <= System.currentTimeMillis()) {
                    // Pause the player as the scheduled pause time has been reached.
                    player.pause()

                    // Once the scheduled pause has been triggered, reset the scheduled time to uninitialized.
                    scheduledPauseTimeMillis = UNINITIALIZED_SLEEP_TIME_MILLIS
                }
                // Delay for the specified time
                delay(SAVE_POSITION_DELAY_MILLS)
                isPlaying = player.isPlaying
            } while (isPlaying)
        }
    }

    /**
     * Handles a connection request from a media controller to this media session.
     * Add the supported custom commands to the session.
     *@see [MediaLibraryService.onConnect]
     * @see [ConnectionResult]
     * @see [SessionCommand1]
     * @see [ACTION_AUDIO_SESSION_ID]
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onConnect(
        session: MediaSession,
        controller: ControllerInfo
    ): ConnectionResult {
        // obtain the available commands
        // TODO: Maybe add more buttons to notifications.
        val available = ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()

        // add commands to session.
        available.add(SessionCommand(ACTION_AUDIO_SESSION_ID, Bundle.EMPTY))
        available.add(SessionCommand(ACTION_SCHEDULE_SLEEP_TIME, Bundle.EMPTY))
        available.add(SessionCommand(ACTION_EQUALIZER_CONFIG, Bundle.EMPTY))

        // return the result.
        return ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(available.build())
            .build()
    }

    /**
     * Initialize audio effects here. If the [audioSessionId] is -1, it indicates that someone called
     * this function locally, and there is no need to create a new instance of the effect.
     * Instead, only initialize the settings of the effect.
     *
     * If the effect is null, it should be created if supported; otherwise, it should not be created.
     */
    @SuppressLint("UnsafeOptInUsageError")
    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        if (audioSessionId != -1)
            super.onAudioSessionIdChanged(audioSessionId)

        // Initialize the equalizer with the audio session ID from the ExoPlayer.
        // We only reinitialize it if the 'audioSessionId' is not -1,
        // which means the system called this function with a new session ID.
        // In this case, we reinitialize the audio effect; otherwise, we just apply settings.
        if (equalizer == null || audioSessionId != -1) {
            equalizer?.release()
            // TODO: Find the real reason why equalizer is not init when calling from onCreate.
            equalizer = runCatching {
                Equalizer(0, (player as ExoPlayer).audioSessionId)
            }.getOrNull()
        }

        // Enable the equalizer.
        equalizer?.enabled = preferences[PREF_KEY_EQUALIZER_ENABLED, false]

        // Retrieve equalizer properties from preferences.
        val properties = preferences[PREF_KEY_EQUALIZER_PROPERTIES, ""]

        // Apply equalizer properties only if they are not null or blank.
        if (properties.isNotBlank()) {
            equalizer?.properties = Settings(properties)
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCustomCommand(
        session: MediaSession,
        controller: ControllerInfo,
        command: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        val action = command.customAction
        return when (action) {
            // Handle the command to retrieve the audio session ID from the player.
            ACTION_AUDIO_SESSION_ID -> {
                // Retrieve the audio session ID from the ExoPlayer.
                val audioSessionId = (player as ExoPlayer).audioSessionId

                // Create a result with the audio session ID and return it immediately.
                val result = SessionResult(SessionResult.RESULT_SUCCESS) {
                    putInt(EXTRA_AUDIO_SESSION_ID, audioSessionId)
                }

                // Return the result with the audio session ID.
                Futures.immediateFuture(result)
            }

            // Handle sleep timer commands
            ACTION_SCHEDULE_SLEEP_TIME -> {
                // Retrieve the scheduled time in milliseconds from the command's custom extras
                val newTimeMills = command.customExtras.getLong(EXTRA_SCHEDULED_TIME_MILLS)
                // If the new time is 0, it means the client wants to retrieve the sleep timer.
                // If the new time is not zero, set the new sleep timer.
                // FixMe: Consider setting the timer only when it's not equal to the default value
                //  and greater than the current time.
                if (newTimeMills != 0L)
                    scheduledPauseTimeMillis = newTimeMills

                // Regardless of whether the client wants to set or retrieve the timer,
                // include the current or updated timer value in the response to the client.
                Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_SUCCESS) {
                        putLong(
                            EXTRA_SCHEDULED_TIME_MILLS,
                            scheduledPauseTimeMillis
                        )
                    }
                )
            }

            ACTION_EQUALIZER_CONFIG -> {
                // obtain the extras that are accompanied with this action.
                val extras = command.customExtras

                // if extras are empty means user wants to retrieve the equalizer saved config.
                if (!extras.isEmpty) {
                    val isEqualizerEnabled =
                        command.customExtras.getBoolean(EXTRA_EQUALIZER_ENABLED)
                    val properties = command.customExtras.getString(
                        EXTRA_EQUALIZER_PROPERTIES, null
                    )
                    // save in pref
                    preferences[PREF_KEY_EQUALIZER_PROPERTIES] = properties
                    preferences[PREF_KEY_EQUALIZER_ENABLED] = isEqualizerEnabled
                    onAudioSessionIdChanged(-1)
                }

                // in both cases weather retrieve or set; return the extras
                // include the current config of equalizer response to the client.
                Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_SUCCESS) {
                        putBoolean(
                            EXTRA_EQUALIZER_ENABLED,
                            preferences[PREF_KEY_EQUALIZER_ENABLED, false]
                        )
                        putString(
                            EXTRA_EQUALIZER_PROPERTIES,
                            preferences[PREF_KEY_EQUALIZER_PROPERTIES, ""]
                        )
                    }
                )
            }

            // Handle unrecognized or unsupported commands.
            else -> Futures.immediateFuture(SessionResult(SessionError.ERROR_UNKNOWN))
        }
    }

    /**
     * Releases resources and cancels any associated scope before destroying the service.
     * @see Service.onDestroy
     */
    override fun onDestroy() {
        // Release the media player to free up system resources.
        player.release()
        // Release the audio session associated with the media player.
        session.release()
        // Release the equalizer (if it exists) to free up resources.
        equalizer?.release()
        // Cancel any ongoing coroutines associated with this scope.
        scope.cancel()
        // Call the superclass method to properly handle onDestroy().
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        player.playWhenReady = false
        if (preferences[PREF_KEY_CLOSE_WHEN_REMOVED, false]) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
}