package io.github.muntashirakon.setedit.utils;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;

import io.github.muntashirakon.setedit.EditorUtils;
import io.github.muntashirakon.setedit.SettingsType;

public final class SettingsUtils {
    @NonNull
    public static ActionResult create(@NonNull Context context, @SettingsType String settingsType,
                                      @NonNull String keyName, @NonNull String newValue) {
        return updateInternal(context, settingsType, keyName, newValue, ActionResult.TYPE_CREATE);
    }

    @NonNull
    public static ActionResult update(@NonNull Context context, @SettingsType String settingsType,
                                      @NonNull String keyName, @NonNull String newValue) {
        return updateInternal(context, settingsType, keyName, newValue, ActionResult.TYPE_UPDATE);
    }

    @NonNull
    public static ActionResult delete(@NonNull Context context, @SettingsType String settingsType,
                                      @NonNull String keyName) {
        if (PrivilegeBridge.canUsePrivilegedShell()) {
            return PrivilegeBridge.execute(ActionResult.TYPE_DELETE,
                    "settings", "delete", settingsType, keyName);
        }

        Boolean isGranted = EditorUtils.checkSettingsPermission(context, settingsType);
        if (isGranted == null) return failure(ActionResult.TYPE_DELETE, "Permission request pending");
        if (!isGranted) {
            EditorUtils.displayGrantPermissionMessage(context);
            return failure(ActionResult.TYPE_DELETE, "Permission denied");
        }

        ContentResolver contentResolver = context.getContentResolver();
        try {
            String[] args = {keyName};
            contentResolver.delete(Uri.parse("content://settings/" + settingsType), "name = ?", args);
            return new ActionResult(ActionResult.TYPE_DELETE, true);
        } catch (Throwable t) {
            t.printStackTrace();
            return failure(ActionResult.TYPE_DELETE, t.getMessage());
        }
    }

    @NonNull
    private static ActionResult updateInternal(@NonNull Context context, @SettingsType String settingsType,
                                               @NonNull String keyName, @NonNull String newValue,
                                               @ActionResult.ActionType int actionType) {
        if ("null".equals(newValue)) {
            // "null" clears the value but leaves the key present.
            newValue = "";
        }

        if (PrivilegeBridge.canUsePrivilegedShell()) {
            return PrivilegeBridge.execute(actionType,
                    "settings", "put", settingsType, keyName, newValue);
        }

        Boolean isGranted = EditorUtils.checkSettingsPermission(context, settingsType);
        if (isGranted == null) return failure(actionType, "Permission request pending");
        if (!isGranted) {
            EditorUtils.displayGrantPermissionMessage(context);
            return failure(actionType, "Permission denied");
        }

        ContentResolver contentResolver = context.getContentResolver();
        try {
            ContentValues values = new ContentValues(2);
            values.put("name", keyName);
            values.put("value", newValue);
            contentResolver.insert(Uri.parse("content://settings/" + settingsType), values);
            return new ActionResult(actionType, true);
        } catch (Throwable t) {
            t.printStackTrace();
            return failure(actionType, t.getMessage());
        }
    }

    @NonNull
    private static ActionResult failure(@ActionResult.ActionType int actionType, String message) {
        ActionResult result = new ActionResult(actionType, false);
        result.setLogs(message != null ? message : "Operation failed");
        return result;
    }
}
