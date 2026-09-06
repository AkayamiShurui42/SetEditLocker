package io.github.muntashirakon.setedit.boot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        // Apply normal boot actions first.
        ContextCompat.startForegroundService(context, new Intent(context, BootService.class));

        // A locked value is inherently persistent. Restart Guardian automatically whenever
        // any lock exists so the user never has to open SetEditLocker after reboot.
        if (!context.getSharedPreferences("locked_settings", Context.MODE_PRIVATE).getAll().isEmpty()) {
            ContextCompat.startForegroundService(
                    context, new Intent(context, SettingsMonitorService.class));
        }
    }
}
