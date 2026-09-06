package io.github.muntashirakon.setedit;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.util.Pair;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.topjohnwu.superuser.Shell;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

import io.github.muntashirakon.setedit.utils.PrivilegeBridge;

public class EditorUtils {
    /**
     * Check whether the app can write the selected settings table.
     *
     * @return true if granted, null if a user-facing permission request is pending,
     * false otherwise.
     */
    @Nullable
    public static Boolean checkSettingsPermission(@NonNull Context context, @SettingsType String settingsType) {
        String permission = SettingsType.SYSTEM_SETTINGS.equals(settingsType)
                ? Manifest.permission.WRITE_SETTINGS : Manifest.permission.WRITE_SECURE_SETTINGS;

        // Shizuku/Shizuku+ is preferred when available because SettingsUtils can execute
        // the settings command directly through the privileged service.
        if (PrivilegeBridge.hasShizukuPermission()) {
            return true;
        }
        if (PrivilegeBridge.isShizukuRunning() && context instanceof android.app.Activity) {
            PrivilegeBridge.requestShizukuPermissionIfNeeded((android.app.Activity) context);
            return null;
        }

        if (SettingsType.SYSTEM_SETTINGS.equals(settingsType)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
                if (PrivilegeBridge.isRootGranted()) {
                    Shell.cmd("appops set " + Process.myUid() + " 23 0",
                            "appops set " + BuildConfig.APPLICATION_ID + " 23 0").exec();
                }
                if (!Settings.System.canWrite(context)) {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                                .setData(Uri.parse("package:" + BuildConfig.APPLICATION_ID));
                        if (!(context instanceof android.app.Activity)) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        }
                        context.startActivity(intent);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    return null;
                }
            }
        } else if (PrivilegeBridge.isRootGranted()) {
            Shell.cmd("pm grant " + BuildConfig.APPLICATION_ID + " " + permission).exec();
        }

        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint({"InflateParams", "SetTextI18n"})
    public static void displayGrantPermissionMessage(@NonNull Context context) {
        if (!(context instanceof android.app.Activity)) {
            return;
        }
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_unsupported, null);
        TextView tv = view.findViewById(R.id.txt);
        tv.setText("pm grant " + BuildConfig.APPLICATION_ID + " " + Manifest.permission.WRITE_SECURE_SETTINGS);
        tv.setKeyListener(null);
        tv.setSelectAllOnFocus(true);
        tv.requestFocus();
        new MaterialAlertDialogBuilder(context)
                .setView(view)
                .setNegativeButton(R.string.close, null)
                .show();
    }

    @NonNull
    public static String getJson(@NonNull List<Pair<String, String>> items, @Nullable String settingsType)
            throws JSONException {
        JSONObject jsonObject = new JSONObject();
        if (settingsType != null) {
            jsonObject.put("_settings_type", settingsType);
        }
        for (Pair<String, String> pair : items) {
            jsonObject.put(pair.first, pair.second);
        }
        return jsonObject.toString(4);
    }

    @TableType
    public static String toTableType(@TableTypeInt int tableTypeInt) {
        switch (tableTypeInt) {
            case TableTypeInt.TABLE_SYSTEM:
                return TableType.TABLE_SYSTEM;
            case TableTypeInt.TABLE_SECURE:
                return TableType.TABLE_SECURE;
            case TableTypeInt.TABLE_GLOBAL:
                return TableType.TABLE_GLOBAL;
            case TableTypeInt.TABLE_PROPERTIES:
                return TableType.TABLE_PROPERTIES;
            case TableTypeInt.TABLE_JAVA:
                return TableType.TABLE_JAVA;
            case TableTypeInt.TABLE_ENV:
                return TableType.TABLE_ENV;
            case TableTypeInt.TABLE_BOOT:
                return TableType.TABLE_BOOT;
            case TableTypeInt.TABLE_SHORTCUTS:
                return TableType.TABLE_SHORTCUTS;
            default:
                throw new IllegalArgumentException("Invalid table type: " + tableTypeInt);
        }
    }
}
