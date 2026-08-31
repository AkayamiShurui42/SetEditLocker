package io.github.muntashirakon.setedit.boot;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.muntashirakon.setedit.BuildConfig;
import io.github.muntashirakon.setedit.R;
import io.github.muntashirakon.setedit.SettingsType;
import io.github.muntashirakon.setedit.TableType;
import io.github.muntashirakon.setedit.utils.ActionResult;
import io.github.muntashirakon.setedit.utils.AndroidPropertyUtils;
import io.github.muntashirakon.setedit.utils.SettingsUtils;
import rikka.shizuku.Shizuku;

public class SettingsMonitorService extends Service {
    public static final String TAG = "SettingsMonitor";
    public static final String NOTIFICATION_CHANNEL_ID = BuildConfig.APPLICATION_ID + ".notification.MONITOR_SERVICE";

    private static final long WATCHDOG_INTERVAL_MS = 5000L;
    private static final long REVERT_DELAY_MS = 500L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, Runnable> pendingReverts = new ConcurrentHashMap<>();

    private Handler handler;
    private SettingsObserver systemObserver;
    private SettingsObserver secureObserver;
    private SettingsObserver globalObserver;
    private Shizuku.OnBinderReceivedListener shizukuListener;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            executor.execute(() -> applyAllLockedSettings(false));
            if (handler != null) {
                handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Settings Monitor Active")
                .setContentText("Enforcing locked settings")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(2, notification);
        }

        handler = new Handler(Looper.getMainLooper());

        systemObserver = new SettingsObserver(handler, SettingsType.SYSTEM_SETTINGS, TableType.TABLE_SYSTEM);
        secureObserver = new SettingsObserver(handler, SettingsType.SECURE_SETTINGS, TableType.TABLE_SECURE);
        globalObserver = new SettingsObserver(handler, SettingsType.GLOBAL_SETTINGS, TableType.TABLE_GLOBAL);

        getContentResolver().registerContentObserver(Settings.System.CONTENT_URI, true, systemObserver);
        getContentResolver().registerContentObserver(Settings.Secure.CONTENT_URI, true, secureObserver);
        getContentResolver().registerContentObserver(Settings.Global.CONTENT_URI, true, globalObserver);

        // Enforce all saved locks immediately instead of waiting for the first observer event.
        executor.execute(() -> applyAllLockedSettings(true));
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);

        // If Shizuku/Shizuku+ reconnects after this service has already started, force all
        // locks again immediately. The watchdog remains as a fallback for missed events.
        shizukuListener = () -> executor.execute(() -> applyAllLockedSettings(true));
        try {
            Shizuku.addBinderReceivedListenerSticky(shizukuListener);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to register Shizuku binder listener", t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        executor.execute(() -> applyAllLockedSettings(true));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (handler != null) {
            handler.removeCallbacks(watchdog);
            for (Runnable runnable : pendingReverts.values()) {
                handler.removeCallbacks(runnable);
            }
            pendingReverts.clear();
        }

        if (systemObserver != null) getContentResolver().unregisterContentObserver(systemObserver);
        if (secureObserver != null) getContentResolver().unregisterContentObserver(secureObserver);
        if (globalObserver != null) getContentResolver().unregisterContentObserver(globalObserver);

        if (shizukuListener != null) {
            try {
                Shizuku.removeBinderReceivedListener(shizukuListener);
            } catch (Throwable t) {
                Log.w(TAG, "Unable to unregister Shizuku binder listener", t);
            }
        }

        executor.shutdownNow();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        NotificationChannelCompat notificationChannel = new NotificationChannelCompat.Builder(
                NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_MIN)
                .setVibrationEnabled(false)
                .setName("Settings Monitor")
                .build();
        notificationManager.createNotificationChannel(notificationChannel);
    }

    private class SettingsObserver extends ContentObserver {
        private final String settingsType;
        private final String tableType;

        SettingsObserver(Handler handler, String settingsType, String tableType) {
            super(handler);
            this.settingsType = settingsType;
            this.tableType = tableType;
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            if (uri == null) return;

            String key = uri.getLastPathSegment();
            if (key == null || key.isEmpty()) return;

            checkAndScheduleSettingRevert(key, settingsType, tableType, true);
        }
    }

    private void applyAllLockedSettings(boolean immediate) {
        SharedPreferences lockedPrefs = getSharedPreferences("locked_settings", Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> entry : lockedPrefs.getAll().entrySet()) {
            String fullKey = entry.getKey();
            if (fullKey == null) continue;

            int lastColon = fullKey.lastIndexOf(':');
            if (lastColon <= 0 || lastColon >= fullKey.length() - 1) continue;

            String key = fullKey.substring(0, lastColon);
            String tableType = fullKey.substring(lastColon + 1);
            String savedValue = String.valueOf(entry.getValue());

            if (TableType.TABLE_SYSTEM.equals(tableType)) {
                checkAndScheduleSettingRevert(key, SettingsType.SYSTEM_SETTINGS, tableType, !immediate);
            } else if (TableType.TABLE_SECURE.equals(tableType)) {
                checkAndScheduleSettingRevert(key, SettingsType.SECURE_SETTINGS, tableType, !immediate);
            } else if (TableType.TABLE_GLOBAL.equals(tableType)) {
                checkAndScheduleSettingRevert(key, SettingsType.GLOBAL_SETTINGS, tableType, !immediate);
            } else if (TableType.TABLE_PROPERTIES.equals(tableType)) {
                checkAndSchedulePropertyRevert(key, savedValue, !immediate);
            }
        }
    }

    private void checkAndScheduleSettingRevert(String key, String settingsType, String tableType,
                                               boolean delayed) {
        SharedPreferences lockedPrefs = getSharedPreferences("locked_settings", Context.MODE_PRIVATE);
        String savedValue;
        try {
            savedValue = lockedPrefs.getString(key + ":" + tableType, null);
        } catch (ClassCastException e) {
            savedValue = null;
        }
        if (savedValue == null) return;

        String currentValue = readSetting(settingsType, key);
        if (savedValue.equals(currentValue)) return;

        String pendingKey = tableType + ":" + key;
        Runnable revert = () -> {
            pendingReverts.remove(pendingKey);
            executor.execute(() -> {
                String latestSavedValue;
                try {
                    latestSavedValue = getSharedPreferences("locked_settings", Context.MODE_PRIVATE)
                            .getString(key + ":" + tableType, null);
                } catch (ClassCastException e) {
                    latestSavedValue = null;
                }
                if (latestSavedValue == null) return;

                String latestValue = readSetting(settingsType, key);
                if (latestSavedValue.equals(latestValue)) return;

                ActionResult result = SettingsUtils.update(
                        SettingsMonitorService.this, settingsType, key, latestSavedValue);
                if (!result.successful) {
                    Log.e(TAG, "Failed to revert locked setting " + key + ": " + result.getLogs());
                } else {
                    Log.i(TAG, "Reverted locked setting " + key + " to " + latestSavedValue);
                }
            });
        };

        scheduleRevert(pendingKey, revert, delayed ? REVERT_DELAY_MS : 0L);
    }

    private void checkAndSchedulePropertyRevert(String key, String savedValue, boolean delayed) {
        String currentValue = AndroidPropertyUtils.read(key);
        if (currentValue != null && savedValue.equals(currentValue)) return;

        String pendingKey = TableType.TABLE_PROPERTIES + ":" + key;
        Runnable revert = () -> {
            pendingReverts.remove(pendingKey);
            executor.execute(() -> {
                SharedPreferences prefs = getSharedPreferences("locked_settings", Context.MODE_PRIVATE);
                String latestSavedValue;
                try {
                    latestSavedValue = prefs.getString(key + ":" + TableType.TABLE_PROPERTIES, null);
                } catch (ClassCastException e) {
                    latestSavedValue = null;
                }
                if (latestSavedValue == null) return;

                String latestValue = AndroidPropertyUtils.read(key);
                if (latestSavedValue.equals(latestValue)) return;

                ActionResult result = AndroidPropertyUtils.update(key, latestSavedValue);
                if (!result.successful) {
                    Log.e(TAG, "Failed to revert locked property " + key + ": " + result.getLogs());
                } else {
                    Log.i(TAG, "Reverted locked property " + key + " to " + latestSavedValue);
                }
            });
        };

        scheduleRevert(pendingKey, revert, delayed ? REVERT_DELAY_MS : 0L);
    }

    private void scheduleRevert(String pendingKey, Runnable replacement, long delayMs) {
        if (handler == null) return;

        Runnable old = pendingReverts.put(pendingKey, replacement);
        if (old != null) handler.removeCallbacks(old);
        handler.postDelayed(replacement, delayMs);
    }

    @Nullable
    private String readSetting(String settingsType, String key) {
        try {
            if (SettingsType.SYSTEM_SETTINGS.equals(settingsType)) {
                return Settings.System.getString(getContentResolver(), key);
            } else if (SettingsType.SECURE_SETTINGS.equals(settingsType)) {
                return Settings.Secure.getString(getContentResolver(), key);
            } else if (SettingsType.GLOBAL_SETTINGS.equals(settingsType)) {
                return Settings.Global.getString(getContentResolver(), key);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to read setting " + key, t);
        }
        return null;
    }
}
