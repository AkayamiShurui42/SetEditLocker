package io.github.muntashirakon.setedit;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.WindowCompat;

import com.google.android.material.color.DynamicColors;
import com.topjohnwu.superuser.Shell;

import io.github.muntashirakon.setedit.utils.PrivilegeBridge;
import rikka.shizuku.Shizuku;

public class App extends Application {
    static {
        // Set settings before the main shell can be created
        Shell.enableVerboseLogging = BuildConfig.DEBUG;
        Shell.setDefaultBuilder(Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(this));

        DynamicColors.applyToActivitiesIfAvailable(this);
        registerActivityLifecycleCallbacks(new ActivityAppearanceCallback());
        Shell.getShell();

        // Warm the privileged command UserService as soon as Shizuku is available. This keeps
        // settings edits and Guardian writes off the deprecated Shizuku.newProcess() path.
        Shizuku.addBinderReceivedListenerSticky(() -> {
            if (PrivilegeBridge.hasShizukuPermission()) {
                PrivilegeBridge.initializeShizukuUserService();
            }
        });
        Shizuku.addBinderDeadListener(PrivilegeBridge::onShizukuBinderDead);
        Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> {
            if (requestCode == PrivilegeBridge.REQUEST_CODE_SHIZUKU
                    && grantResult == PackageManager.PERMISSION_GRANTED) {
                PrivilegeBridge.initializeShizukuUserService();
            }
        });

        Intent intent = new Intent(this, io.github.muntashirakon.setedit.boot.SettingsMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    public static class ActivityAppearanceCallback implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
            Window window = activity.getWindow();
            WindowCompat.setDecorFitsSystemWindows(window, false);
        }

        @Override
        public void onActivityStarted(@NonNull Activity activity) {

        }

        @Override
        public void onActivityResumed(@NonNull Activity activity) {

        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {

        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {

        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {

        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {

        }
    }
}
