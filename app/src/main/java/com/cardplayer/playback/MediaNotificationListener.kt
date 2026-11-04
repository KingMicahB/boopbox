package com.cardplayer.playback

import android.service.notification.NotificationListenerService

/**
 * Deliberately empty. Holding "notification access" is what lets the app ask
 * MediaSessionManager for other apps' media sessions (Spotify, YouTube Music...),
 * which gives us their real play/pause state, title and artwork.
 */
class MediaNotificationListener : NotificationListenerService()
