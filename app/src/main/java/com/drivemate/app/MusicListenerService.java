package com.drivemate.app;

import android.service.notification.NotificationListenerService;

/**
 * Empty notification listener. Android only lets an app see and control other
 * apps' media sessions (Spotify, YouTube Music, podcasts) when the driver has
 * granted it notification access; this service is what that permission names.
 * No notification content is read or stored.
 */
public final class MusicListenerService extends NotificationListenerService { }
