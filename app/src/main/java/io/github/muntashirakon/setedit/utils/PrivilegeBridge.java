package io.github.muntashirakon.setedit.utils;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.topjohnwu.superuser.Shell;

import io.github.muntashirakon.setedit.BuildConfig;
import io.github.muntashirakon.setedit.service.ISetEditUserService;
import io.github.muntashirakon.setedit.service.SetEditUserService;
import rikka.shizuku.Shizuku;

/**
 * Centralized privileged-operation bridge used by SetEditLocker.
 *
 * Stock Shizuku clients are also the officially supported compatibility path for
 * Shizuku+: the normal Plus build uses its Compat Hub, while the Drop-In build
 * already owns the stock Shizuku package name.
 *
 * Privileged command execution uses a Shizuku UserService. Do not reintroduce
 * Shizuku.newProcess(): it is deprecated and may return a null remote process on
 * current Shizuku+ / Android builds.
 */
public final class PrivilegeBridge {
    private static final String TAG = "PrivilegeBridge";
    public static final int REQUEST_CODE_SHIZUKU = 1001;

    public static final String SHIZUKU_PLUS_PACKAGE = "af.shizuku.plus.api";
    public static final String STOCK_OR_COMPAT_PACKAGE = "moe.shizuku.privileged.api";

    private static final Object USER_SERVICE_LOCK = new Object();
    private static final long USER_SERVICE_WAIT_MS = 5000L;

    @Nullable
    private static volatile ISetEditUserService userService;
    private static boolean userServiceBinding;

    private static final Shizuku.UserServiceArgs USER_SERVICE_ARGS =
            new Shizuku.UserServiceArgs(new ComponentName(
                    BuildConfig.APPLICATION_ID, SetEditUserService.class.getName()))
                    .daemon(false)
                    .processNameSuffix("privileged")
                    .debuggable(BuildConfig.DEBUG)
                    .version(BuildConfig.VERSION_CODE);

    private static final ServiceConnection USER_SERVICE_CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (USER_SERVICE_LOCK) {
                if (binder != null && binder.pingBinder()) {
                    userService = ISetEditUserService.Stub.asInterface(binder);
                    Log.i(TAG, "Shizuku UserService connected");
                } else {
                    userService = null;
                    Log.w(TAG, "Shizuku UserService returned an invalid binder");
                }
                userServiceBinding = false;
                USER_SERVICE_LOCK.notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (USER_SERVICE_LOCK) {
                userService = null;
                userServiceBinding = false;
                USER_SERVICE_LOCK.notifyAll();
            }
            Log.w(TAG, "Shizuku UserService disconnected");
        }
    };

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
        if (hasShizukuPermission()) {
            initializeShizukuUserService();
            return true;
        }
        try {
            Shizuku.requestPermission(REQUEST_CODE_SHIZUKU);
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "Unable to request Shizuku permission", t);
            return false;
        }
    }

    /**
     * Start/warm the privileged UserService asynchronously. Safe to call repeatedly.
     */
    public static void initializeShizukuUserService() {
        if (!hasShizukuPermission()) return;

        synchronized (USER_SERVICE_LOCK) {
            if (isUserServiceAliveLocked() || userServiceBinding) return;
            userServiceBinding = true;
        }

        try {
            Shizuku.bindUserService(USER_SERVICE_ARGS, USER_SERVICE_CONNECTION);
        } catch (Throwable t) {
            synchronized (USER_SERVICE_LOCK) {
                userService = null;
                userServiceBinding = false;
                USER_SERVICE_LOCK.notifyAll();
            }
            Log.e(TAG, "Unable to bind Shizuku UserService", t);
        }
    }

    public static void onShizukuBinderDead() {
        synchronized (USER_SERVICE_LOCK) {
            userService = null;
            userServiceBinding = false;
            USER_SERVICE_LOCK.notifyAll();
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
            return failure(actionType, "Shizuku service unavailable or permission not granted");
        }

        ISetEditUserService service = obtainUserService();
        if (service == null) {
            return failure(actionType,
                    Looper.myLooper() == Looper.getMainLooper()
                            ? "Shizuku command service is starting; retry in a moment"
                            : "Shizuku command service did not connect");
        }

        try {
            String response = service.execute(command);
            if (response == null) {
                return failure(actionType, "Shizuku UserService returned no result");
            }

            int separator = response.indexOf('\n');
            String codeText = separator >= 0 ? response.substring(0, separator) : response;
            String output = separator >= 0 ? response.substring(separator + 1) : "";

            int exitCode;
            try {
                exitCode = Integer.parseInt(codeText.trim());
            } catch (NumberFormatException e) {
                return failure(actionType, "Invalid Shizuku UserService result: " + response);
            }

            ActionResult result = new ActionResult(actionType, exitCode == 0);
            if (exitCode != 0) {
                result.setLogs(!TextUtils.isEmpty(output)
                        ? output
                        : "Privileged command exited with code " + exitCode);
            }
            return result;
        } catch (Throwable t) {
            Log.e(TAG, "Privileged Shizuku UserService command failed", t);
            onShizukuBinderDead();
            String message = t.getMessage();
            return failure(actionType, t.getClass().getName()
                    + (TextUtils.isEmpty(message) ? "" : ": " + message));
        }
    }

    @Nullable
    private static ISetEditUserService obtainUserService() {
        synchronized (USER_SERVICE_LOCK) {
            if (isUserServiceAliveLocked()) return userService;
        }

        initializeShizukuUserService();

        // Shizuku dispatches ServiceConnection callbacks on the main looper. Never wait
        // for that callback while already on the main thread.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            synchronized (USER_SERVICE_LOCK) {
                return isUserServiceAliveLocked() ? userService : null;
            }
        }

        long deadline = SystemClock.uptimeMillis() + USER_SERVICE_WAIT_MS;
        synchronized (USER_SERVICE_LOCK) {
            while (!isUserServiceAliveLocked() && userServiceBinding) {
                long remaining = deadline - SystemClock.uptimeMillis();
                if (remaining <= 0) break;
                try {
                    USER_SERVICE_LOCK.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return isUserServiceAliveLocked() ? userService : null;
        }
    }

    private static boolean isUserServiceAliveLocked() {
        if (userService == null) return false;
        try {
            return userService.asBinder().pingBinder();
        } catch (Throwable t) {
            userService = null;
            return false;
        }
    }

    @NonNull
    private static ActionResult failure(@ActionResult.ActionType int actionType, @NonNull String message) {
        ActionResult result = new ActionResult(actionType, false);
        result.setLogs(message);
        return result;
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
