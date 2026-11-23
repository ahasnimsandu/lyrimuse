package me.lyrimuse.android;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Declaring a notification listener is what grants access to other apps'
 * MediaSessions on Android; PlayerService reads the active sessions from it.
 */
public class MediaListenerService extends NotificationListenerService {

    @Override
    public void onListenerConnected() {
        if (PlayerService.instance != null) PlayerService.instance.requestTick();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (PlayerService.instance != null) PlayerService.instance.requestTick();
    }
}
