package io.github.muntashirakon.setedit.boot;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Persistent catalog of real values observed from Android's System, Secure and Global
 * settings providers. Kept separately per table/key, including empty string values.
 * Legacy value sets are read so previous discoveries survive the upgrade.
 */
public final class SettingsDiscoveryCatalog {
    private static final String TAG = "SettingsDiscoveryCatalog";
    private static final String PREFS_NAME = "settings_discovery_catalog";
    private static final String KEY_PREFIX = "keys:";
    private static final String VALUE_PREFIX = "values:";
    private static final String RECENT_PREFIX = "recent_values:";
    private static final int MAX_VALUES_PER_KEY = 128;

    private SettingsDiscoveryCatalog() {
    }

    public static void record(@NonNull Context context,
                              @NonNull String settingsType,
                              @NonNull String key,
                              @Nullable String value) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;

        String keysStorage = KEY_PREFIX + settingsType;
        Set<String> keys = new HashSet<>(prefs.getStringSet(keysStorage, Collections.emptySet()));
        if (keys.add(key)) {
            editor.putStringSet(keysStorage, keys);
            changed = true;
        }

        if (value != null) {
            List<String> previous = readValues(prefs, settingsType, key);
            if (!previous.contains(value)) {
                List<String> updated = ObservedValueHistory.prependNew(
                        previous, value, MAX_VALUES_PER_KEY);
                JSONArray array = new JSONArray();
                for (String item : updated) array.put(item);
                editor.putString(recentStorage(settingsType, key), array.toString());
                changed = true;
            }
        }

        // Watchdog snapshots touch every visible key. Never write to disk for an
        // unchanged value at every five-second interval.
        if (changed) editor.apply();
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
        return readValues(prefs, settingsType, key);
    }

    @NonNull
    private static List<String> readValues(@NonNull SharedPreferences prefs,
                                           @NonNull String settingsType,
                                           @NonNull String key) {
        String encoded = prefs.getString(recentStorage(settingsType, key), null);
        if (encoded != null) {
            try {
                JSONArray array = new JSONArray(encoded);
                List<String> values = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    String value = array.optString(i, null);
                    if (value != null && !values.contains(value)) values.add(value);
                }
                return values;
            } catch (JSONException e) {
                Log.w(TAG, "Invalid observed-value history; falling back to legacy values", e);
            }
        }

        // Older releases kept unordered sets capped at 32 entries. These values are
        // retained and migrated into ordered history when the next new value arrives.
        List<String> legacy = new ArrayList<>(prefs.getStringSet(
                VALUE_PREFIX + settingsType + ":" + key, Collections.emptySet()));
        Collections.sort(legacy, String.CASE_INSENSITIVE_ORDER);
        return legacy;
    }

    @NonNull
    private static String recentStorage(@NonNull String settingsType,
                                        @NonNull String key) {
        return RECENT_PREFIX + settingsType + ":" + key;
    }

    public static void clear(@NonNull Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
