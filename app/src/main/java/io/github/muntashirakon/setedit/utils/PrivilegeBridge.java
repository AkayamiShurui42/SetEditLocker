package io.github.muntashirakon.setedit.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.topjohnwu.superuser.Shell;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * Centralized privileged-operation bridge used by SetEditLocker.
 *
 * Stock Shizuku clients are also the officially supported compatibility path for
 * Shizuku+: the normal Plus build uses its Compat Hub, while the Drop-In build
 * already owns the stock Shizuku package name.
 */
public final class PrivilegeBridge {
    private static final String TAG = "PrivilegeBridge";
    public static final int REQUEST_CODE_SHIZUKU = 1001;

    public static final String SHIZUKU_PLUS_PACKAGE = "af.shizuku.plus.api";
    public static final String STOCK_OR_COMPAT_PACKAGE = "moe.shizuku.privileged.api";

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

    public static boolean isNativeShizukuPlusInstalled(@NonNull Context context) {
        return isPackageInstalled(context, SHIZUKU_PLUS_PACKAGE);
    }

    public static boolean isStockOrCompatInstalled(@NonNull Context context) {
        return isPackageInstalled(context, STOCK_OR_COMPAT_PACKAGE);
    }

    @Nullable
    public static String getShizukuPlusVersion(@NonNull Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(SHIZUKU_PLUS_PACKAGE, 0);
            return info.versionName;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean openShizukuManager(@NonNull Context context) {
        String[] packages = new String[]{SHIZUKU_PLUS_PACKAGE, STOCK_OR_COMPAT_PACKAGE};
        for (String packageName : packages) {
            try {
                Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(launch);
                    return true;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Unable to open " + packageName, t);
            }
        }
        return false;
    }

    private static boolean isPackageInstalled(@NonNull Context context, @NonNull String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "Unable to inspect package " + packageName, t);
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
            // API 13.1.5 keeps newProcess private. Keep this path isolated so it can be
            // replaced by a UserService without coupling the rest of SetEditLocker to it.
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
            Throwable cause = unwrap(t);
            Log.e(TAG, "Privileged Shizuku command failed", cause);
            ActionResult result = new ActionResult(actionType, false);
            String message = cause.getMessage();
            result.setLogs(cause.getClass().getName()
                    + (TextUtils.isEmpty(message) ? "" : ": " + message));
            return result;
        }
    }

    @NonNull
    private static Throwable unwrap(@NonNull Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof InvocationTargetException
                && ((InvocationTargetException) current).getTargetException() != null) {
            current = ((InvocationTargetException) current).getTargetException();
        }
        return current;
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
