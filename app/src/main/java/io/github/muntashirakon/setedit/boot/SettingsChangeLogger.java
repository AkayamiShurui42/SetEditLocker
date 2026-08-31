package io.github.muntashirakon.setedit.boot;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.github.muntashirakon.setedit.SettingsType;

public final class SettingsChangeLogger {
    private static final String TAG = "SettingsChangeLogger";
    private static final String LOG_FILE_NAME = "settings-change-log.jsonl";
    private static final long MAX_LOG_BYTES = 4L * 1024L * 1024L;
    private static final long KEEP_LOG_BYTES = 2L * 1024L * 1024L;

    private static final Object LOCK = new Object();
    private static final Map<String, Map<String, String>> snapshots = new HashMap<>();
    private static boolean initialized;

    private SettingsChangeLogger() {
    }

    public static void initialize(@NonNull Context context) {
        synchronized (LOCK) {
            Map<String, String> system = readTable(context, SettingsType.SYSTEM_SETTINGS);
            Map<String, String> secure = readTable(context, SettingsType.SECURE_SETTINGS);
            Map<String, String> global = readTable(context, SettingsType.GLOBAL_SETTINGS);
            snapshots.put(SettingsType.SYSTEM_SETTINGS, system);
            snapshots.put(SettingsType.SECURE_SETTINGS, secure);
            snapshots.put(SettingsType.GLOBAL_SETTINGS, global);
            recordTableInCatalog(context, SettingsType.SYSTEM_SETTINGS, system);
            recordTableInCatalog(context, SettingsType.SECURE_SETTINGS, secure);
            recordTableInCatalog(context, SettingsType.GLOBAL_SETTINGS, global);
            initialized = true;
        }
    }

    public static void scanAll(@NonNull Context context) {
        synchronized (LOCK) {
            if (!initialized) {
                initialize(context);
                return;
            }
            scanTableLocked(context, SettingsType.SYSTEM_SETTINGS, "watchdog");
            scanTableLocked(context, SettingsType.SECURE_SETTINGS, "watchdog");
            scanTableLocked(context, SettingsType.GLOBAL_SETTINGS, "watchdog");
        }
    }

    public static void captureKey(@NonNull Context context,
                                  @NonNull String settingsType,
                                  @NonNull String key) {
        synchronized (LOCK) {
            if (!initialized) initialize(context);

            Map<String, String> previousTable = snapshots.get(settingsType);
            if (previousTable == null) {
                previousTable = new HashMap<>();
                snapshots.put(settingsType, previousTable);
            }

            boolean hadOldValue = previousTable.containsKey(key);
            String oldValue = previousTable.get(key);
            String newValue = readKey(context, settingsType, key);
            SettingsDiscoveryCatalog.record(context, settingsType, key, newValue);

            if (newValue == null) {
                if (hadOldValue) {
                    appendChange(context, settingsType, key, oldValue, null, "deleted", "observer");
                    previousTable.remove(key);
                }
            } else if (!hadOldValue) {
                appendChange(context, settingsType, key, null, newValue, "created", "observer");
                previousTable.put(key, newValue);
            } else if (!equalsNullable(oldValue, newValue)) {
                appendChange(context, settingsType, key, oldValue, newValue, "changed", "observer");
                previousTable.put(key, newValue);
            }
        }
    }

    public static void recordGuardianRestore(@NonNull Context context,
                                             @NonNull String settingsType,
                                             @NonNull String key,
                                             @Nullable String displacedValue,
                                             @NonNull String lockedValue) {
        synchronized (LOCK) {
            SettingsDiscoveryCatalog.record(context, settingsType, key, displacedValue);
            SettingsDiscoveryCatalog.record(context, settingsType, key, lockedValue);
            appendChange(context, settingsType, key, displacedValue, lockedValue,
                    "guardian_restore", "guardian");
            Map<String, String> table = snapshots.get(settingsType);
            if (table != null) table.put(key, lockedValue);
        }
    }

    public static void export(@NonNull Context context, @NonNull OutputStream outputStream)
            throws IOException {
        synchronized (LOCK) {
            File log = getLogFile(context);
            if (!log.exists()) return;
            try (FileInputStream input = new FileInputStream(log)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, count);
                }
            }
        }
    }

    public static void clear(@NonNull Context context) {
        synchronized (LOCK) {
            File log = getLogFile(context);
            if (log.exists() && !log.delete()) Log.w(TAG, "Unable to delete settings change log");
            SettingsDiscoveryCatalog.clear(context);
            initialize(context);
        }
    }

    private static void scanTableLocked(@NonNull Context context,
                                        @NonNull String settingsType,
                                        @NonNull String source) {
        Map<String, String> oldTable = snapshots.get(settingsType);
        if (oldTable == null) oldTable = new HashMap<>();
        Map<String, String> newTable = readTable(context, settingsType);
        recordTableInCatalog(context, settingsType, newTable);

        Set<String> keys = new HashSet<>();
        keys.addAll(oldTable.keySet());
        keys.addAll(newTable.keySet());

        for (String key : keys) {
            boolean existedBefore = oldTable.containsKey(key);
            boolean existsNow = newTable.containsKey(key);
            String oldValue = oldTable.get(key);
            String newValue = newTable.get(key);

            if (!existedBefore && existsNow) {
                appendChange(context, settingsType, key, null, newValue, "created", source);
            } else if (existedBefore && !existsNow) {
                appendChange(context, settingsType, key, oldValue, null, "deleted", source);
            } else if (existedBefore && !equalsNullable(oldValue, newValue)) {
                appendChange(context, settingsType, key, oldValue, newValue, "changed", source);
            }
        }

        snapshots.put(settingsType, newTable);
    }

    private static void recordTableInCatalog(@NonNull Context context,
                                             @NonNull String settingsType,
                                             @NonNull Map<String, String> table) {
        for (Map.Entry<String, String> entry : table.entrySet()) {
            SettingsDiscoveryCatalog.record(context, settingsType, entry.getKey(), entry.getValue());
        }
    }

    @NonNull
    private static Map<String, String> readTable(@NonNull Context context,
                                                 @NonNull String settingsType) {
        Map<String, String> values = new HashMap<>();
        Cursor cursor = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            cursor = resolver.query(Uri.parse("content://settings/" + settingsType),
                    new String[]{"name", "value"}, null, null, null);
            if (cursor != null) {
                int nameIndex = cursor.getColumnIndex("name");
                int valueIndex = cursor.getColumnIndex("value");
                while (cursor.moveToNext()) {
                    String key = nameIndex >= 0 ? cursor.getString(nameIndex) : null;
                    if (key == null) continue;
                    String value = valueIndex >= 0 ? cursor.getString(valueIndex) : null;
                    values.put(key, value);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to snapshot " + settingsType + " settings", t);
        } finally {
            if (cursor != null) cursor.close();
        }
        return values;
    }

    @Nullable
    private static String readKey(@NonNull Context context,
                                  @NonNull String settingsType,
                                  @NonNull String key) {
        try {
            if (SettingsType.SYSTEM_SETTINGS.equals(settingsType)) {
                return android.provider.Settings.System.getString(context.getContentResolver(), key);
            } else if (SettingsType.SECURE_SETTINGS.equals(settingsType)) {
                return android.provider.Settings.Secure.getString(context.getContentResolver(), key);
            } else if (SettingsType.GLOBAL_SETTINGS.equals(settingsType)) {
                return android.provider.Settings.Global.getString(context.getContentResolver(), key);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to read changed key " + key, t);
        }
        return null;
    }

    private static void appendChange(@NonNull Context context,
                                     @NonNull String settingsType,
                                     @NonNull String key,
                                     @Nullable String oldValue,
                                     @Nullable String newValue,
                                     @NonNull String event,
                                     @NonNull String source) {
        SettingsDiscoveryCatalog.record(context, settingsType, key, oldValue);
        SettingsDiscoveryCatalog.record(context, settingsType, key, newValue);
        try {
            JSONObject object = new JSONObject();
            object.put("timestamp_ms", System.currentTimeMillis());
            object.put("table", settingsType);
            object.put("key", key);
            object.put("event", event);
            object.put("source", source);
            object.put("old_value", oldValue == null ? JSONObject.NULL : oldValue);
            object.put("new_value", newValue == null ? JSONObject.NULL : newValue);

            File log = getLogFile(context);
            rotateIfNeeded(log);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(log, true), StandardCharsets.UTF_8))) {
                writer.write(object.toString());
                writer.newLine();
            }
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Unable to append settings change", e);
        }
    }

    private static void rotateIfNeeded(@NonNull File log) throws IOException {
        if (!log.exists() || log.length() < MAX_LOG_BYTES) return;

        byte[] tail;
        try (FileInputStream input = new FileInputStream(log)) {
            long skip = Math.max(0L, log.length() - KEEP_LOG_BYTES);
            while (skip > 0) {
                long skipped = input.skip(skip);
                if (skipped <= 0) break;
                skip -= skipped;
            }
            tail = new byte[(int) Math.min(KEEP_LOG_BYTES, log.length())];
            int offset = 0;
            int read;
            while (offset < tail.length
                    && (read = input.read(tail, offset, tail.length - offset)) != -1) {
                offset += read;
            }
            if (offset < tail.length) {
                byte[] resized = new byte[offset];
                System.arraycopy(tail, 0, resized, 0, offset);
                tail = resized;
            }
        }

        int firstNewline = -1;
        for (int i = 0; i < tail.length; i++) {
            if (tail[i] == '\n') {
                firstNewline = i;
                break;
            }
        }

        try (FileOutputStream output = new FileOutputStream(log, false)) {
            int start = firstNewline >= 0 ? firstNewline + 1 : 0;
            output.write(tail, start, tail.length - start);
        }
    }

    @NonNull
    private static File getLogFile(@NonNull Context context) {
        return new File(context.getFilesDir(), LOG_FILE_NAME);
    }

    private static boolean equalsNullable(@Nullable String first, @Nullable String second) {
        return first == null ? second == null : first.equals(second);
    }
}
