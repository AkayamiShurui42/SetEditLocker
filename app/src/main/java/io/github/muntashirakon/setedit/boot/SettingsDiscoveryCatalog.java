package io.github.muntashirakon.setedit.boot;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Persistent catalog of settings keys and values observed by SettingsChangeLogger.
 * Used to populate the add-setting dropdowns without hard-coding OEM-specific keys.
 */
public final class SettingsDiscoveryCatalog {
    private static final String PREFS_NAME = "settings_discovery_catalog";
    private static final String KEY_PREFIX = "keys:";
    private static final String VALUE_PREFIX = "values:";
    private static final int MAX_VALUES_PER_KEY = 32;

    private SettingsDiscoveryCatalog() {
    }

    public static void record(@NonNull Context context,
                              @NonNull String settingsType,
                              @NonNull String key,
                              @Nullable String value) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        String keysStorage = KEY_PREFIX + settingsType;
        Set<String> keys = new HashSet<>(prefs.getStringSet(keysStorage, Collections.emptySet()));
        if (keys.add(key)) {
            editor.putStringSet(keysStorage, keys);
        }

        if (value != null) {
            String valuesStorage = valuesStorage(settingsType, key);
            Set<String> values = new HashSet<>(prefs.getStringSet(valuesStorage, Collections.emptySet()));
            if (values.add(value)) {
                if (values.size() > MAX_VALUES_PER_KEY) {
                    List<String> sorted = new ArrayList<>(values);
                    Collections.sort(sorted);
                    values = new HashSet<>(sorted.subList(
                            Math.max(0, sorted.size() - MAX_VALUES_PER_KEY), sorted.size()));
                }
                editor.putStringSet(valuesStorage, values);
            }
        }

        editor.apply();
    }

    @NonNull
    public static List<String> getKeys(@NonNull Context context, @NonNull String settingsType) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        List<String> keys = new ArrayList<>(prefs.getStringSet(
                KEY_PREFIX + settingsType, Collections.emptySet()));
        Collections.sort(keys, String.CASE_INSENSITIVE_ORDER);
        return keys;
    }

    @NonNull
    public static List<String> getValues(@NonNull Context context,
                                         @NonNull String settingsType,
                                         @NonNull String key) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        List<String> values = new ArrayList<>(prefs.getStringSet(
                valuesStorage(settingsType, key), Collections.emptySet()));
        Collections.sort(values, String.CASE_INSENSITIVE_ORDER);
        return values;
    }

    public static void clear(@NonNull Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply();
    }

    @NonNull
    private static String valuesStorage(@NonNull String settingsType, @NonNull String key) {
        return VALUE_PREFIX + settingsType + ":" + key;
    }
}
