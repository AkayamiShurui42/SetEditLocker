package io.github.muntashirakon.setedit.utils;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.topjohnwu.superuser.Shell;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class AndroidPropertyUtils {
    private AndroidPropertyUtils() {
    }

    @NonNull
    public static ActionResult update(@NonNull String keyName, @NonNull String newValue) {
        if (PrivilegeBridge.isRootGranted()) {
            // resetprop is intentionally retained for root because it can handle properties
            // that ordinary setprop cannot replace.
            Shell.Result result = Shell.cmd("resetprop '" + escapeSingleQuotes(keyName) + "' '"
                    + escapeSingleQuotes(newValue) + "'").exec();
            ActionResult actionResult = new ActionResult(ActionResult.TYPE_UPDATE, result.isSuccess());
            String error = TextUtils.join("\n", result.getErr());
            if (!TextUtils.isEmpty(error)) actionResult.setLogs(error);
            return actionResult;
        }

        if (PrivilegeBridge.hasShizukuPermission()) {
            return PrivilegeBridge.execute(ActionResult.TYPE_UPDATE,
                    "setprop", keyName, newValue);
        }

        ActionResult result = new ActionResult(ActionResult.TYPE_UPDATE, false);
        result.setLogs("Root or Shizuku permission is required to write Android properties");
        return result;
    }

    @Nullable
    public static String read(@NonNull String keyName) {
        Process process = null;
        try {
            process = new ProcessBuilder("getprop", keyName).redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String value = reader.readLine();
            process.waitFor();
            return value != null ? value.trim() : "";
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (process != null) process.destroy();
        }
    }

    @NonNull
    private static String escapeSingleQuotes(@NonNull String value) {
        return value.replace("'", "'\\''");
    }
}
