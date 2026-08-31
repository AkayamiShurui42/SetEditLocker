package io.github.muntashirakon.setedit.utils;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import com.topjohnwu.superuser.Shell;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * Centralized privileged-operation bridge used by SetEditLocker.
 *
 * SetEditLocker's provider accepts both native Shizuku+ and stock Shizuku binder
 * payloads. Once delivered, both use the stable Rikka client API here.
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
            // API 13.1.5 keeps newProcess private. Isolate reflective access here rather
            // than coupling the rest of SetEditLocker to a private Shizuku method.
            Method method = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            method.setAccessible(true);
            Process process = (Process) method.invoke(null, command, null, null);
            if (process == null) {
                ActionResult result = new ActionResult(actionType, false);
                result.setLogs("Shizuku process creation returned null");
                return result;
            }

            int exitCode = process.waitFor();
            ActionResult result = new ActionResult(actionType, exitCode == 0);
            if (exitCode != 0) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                StringBuilder error = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (error.length() > 0) error.append('\n');
                    error.append(line);
                }
                result.setLogs(error.length() > 0
                        ? error.toString()
                        : "Privileged command exited with code " + exitCode);
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
