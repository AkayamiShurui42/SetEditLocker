package io.github.muntashirakon.setedit.utils;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import com.topjohnwu.superuser.Shell;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuPlusAPI;

/**
 * Centralized privileged-operation bridge used by SetEditLocker.
 *
 * The Shizuku+ API keeps stock Shizuku/Sui compatibility, while using the Plus
 * server's native client/provider path when Shizuku+ is installed.
 */
public final class PrivilegeBridge {
    private static final String TAG = "PrivilegeBridge";
    public static final int REQUEST_CODE_SHIZUKU = 1001;

    private PrivilegeBridge() {
    }

    public static boolean isRootGranted() {
        return Boolean.TRUE.equals(Shell.isAppGrantedRoot());
    }

    public static boolean isShizukuRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            Log.w(TAG, "Unable to ping Shizuku binder", t);
            return false;
        }
    }

    public static boolean hasShizukuPermission() {
        try {
            return isShizukuRunning()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            Log.w(TAG, "Unable to check Shizuku permission", t);
            return false;
        }
    }

    public static boolean canUsePrivilegedShell() {
        return isRootGranted() || hasShizukuPermission();
    }

    public static boolean requestShizukuPermissionIfNeeded(@NonNull Activity activity) {
        if (!isShizukuRunning()) return false;
        if (hasShizukuPermission()) return true;
        try {
            Shizuku.requestPermission(REQUEST_CODE_SHIZUKU);
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "Unable to request Shizuku permission", t);
            return false;
        }
    }

    @NonNull
    public static ActionResult execute(@ActionResult.ActionType int actionType,
                                       @NonNull String... command) {
        if (isRootGranted()) {
            Shell.Result result = Shell.cmd(toShellCommand(command)).exec();
            ActionResult actionResult = new ActionResult(actionType, result.isSuccess());
            String error = TextUtils.join("\n", result.getErr());
            if (!TextUtils.isEmpty(error)) actionResult.setLogs(error);
            return actionResult;
        }

        if (!hasShizukuPermission()) {
            ActionResult result = new ActionResult(actionType, false);
            result.setLogs("Shizuku service unavailable or permission not granted");
            return result;
        }

        try {
            // The Plus API accepts an argument array, avoids shell quoting, and internally
            // retains the standard Shizuku fallback when connected to a non-Plus server.
            ShizukuPlusAPI.CommandResult commandResult = ShizukuPlusAPI.executeShell(command);
            ActionResult result = new ActionResult(actionType, commandResult.isSuccess());
            if (!commandResult.isSuccess()) {
                String error = !TextUtils.isEmpty(commandResult.error)
                        ? commandResult.error
                        : "Privileged command exited with code " + commandResult.exitCode;
                result.setLogs(error);
            }
            return result;
        } catch (Throwable t) {
            ActionResult result = new ActionResult(actionType, false);
            result.setLogs(t.getMessage() != null ? t.getMessage() : t.toString());
            return result;
        }
    }

    @NonNull
    private static String toShellCommand(@NonNull String[] command) {
        StringBuilder builder = new StringBuilder();
        for (String argument : command) {
            if (builder.length() > 0) builder.append(' ');
            builder.append('\'').append(argument.replace("'", "'\\''")).append('\'');
        }
        return builder.toString();
    }
}
