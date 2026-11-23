package com.drivemate.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps DriveMate in the foreground while a route is being navigated, so
 * GPS, guidance and voice keep running when the screen is off or the driver
 * switches to another app (music, a call). It holds no state of its own: the
 * activity keeps its location listener registered and does the work; this
 * service only tells Android the app is actively navigating, and shows the
 * ongoing notification Android requires for that.
 */
public final class NavigationService extends Service {
    private static final String CHANNEL_ID = "drivemate_navigation";
    private static final int NOTIFICATION_ID = 7031;
    private static final String EXTRA_DESTINATION = "destination";

    /** Start (or update) the foreground navigation notification. */
    public static void start(Context context, String destinationTitle) {
        Intent intent = new Intent(context, NavigationService.class)
                .putExtra(EXTRA_DESTINATION, destinationTitle == null ? "" : destinationTitle);
        context.startForegroundService(intent);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, NavigationService.class));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String destination = intent == null ? "" : intent.getStringExtra(EXTRA_DESTINATION);
        Notification notification = buildNotification(destination);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        // If Android kills the process, don't restart navigation silently.
        return START_NOT_STICKY;
    }

    private Notification buildNotification(String destination) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Navigation", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shown while DriveMate is guiding you");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        // Behaves like tapping the launcher icon: brings back the existing
        // navigation screen rather than starting a second copy.
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open == null) open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = destination == null || destination.isEmpty()
                ? "Guidance continues in the background"
                : "Navigating to " + destination;
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.drivemate_mark)
                .setContentTitle("DriveMate is navigating")
                .setContentText(text)
                .setContentIntent(tap)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .setOnlyAlertOnce(true)
                .build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
