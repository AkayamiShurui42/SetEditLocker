package io.github.muntashirakon.setedit.boot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "SetEditBootReceiver";

    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        // Run saved boot actions as before.
        try {
            ContextCompat.startForegroundService(context, new Intent(context, BootService.class));
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to start boot-action service", e);
        }

        // The observer must run even if no values are locked. Otherwise changes
        // made after a reboot and before opening the app are never discovered.
        // Guardian enforcement uses the same monitor when locks are present.
        try {
            ContextCompat.startForegroundService(
                    context, new Intent(context, SettingsMonitorService.class));
        } catch (RuntimeException e) {
            // OEM/background restrictions may deny foreground starts; keep the
            // receiver alive and report the failure rather than crash the app.
            Log.e(TAG, "Unable to restart settings history monitor after boot", e);
        }
    }
}
