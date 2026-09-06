package io.github.muntashirakon.setedit;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.AppCompatSpinner;
import androidx.appcompat.widget.SearchView;
import androidx.core.content.ContextCompat;
import androidx.core.util.Pair;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import org.json.JSONException;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.muntashirakon.setedit.adapters.AbsRecyclerAdapter;
import io.github.muntashirakon.setedit.adapters.AdapterProvider;
import io.github.muntashirakon.setedit.adapters.KnownKeys;
import io.github.muntashirakon.setedit.adapters.SettingsRecyclerAdapter;
import io.github.muntashirakon.setedit.boot.ActionItem;
import io.github.muntashirakon.setedit.boot.BootUtils;
import io.github.muntashirakon.setedit.boot.SettingsChangeLogger;
import io.github.muntashirakon.setedit.boot.SettingsDiscoveryCatalog;
import io.github.muntashirakon.setedit.boot.SettingsMonitorService;
import io.github.muntashirakon.setedit.shortcut.ShortcutUtils;
import io.github.muntashirakon.setedit.utils.ActionResult;
import io.github.muntashirakon.setedit.utils.PrivilegeBridge;
import io.github.muntashirakon.util.UiUtils;
import me.zhanghai.android.fastscroll.FastScrollerBuilder;

public class EditorActivity extends AppCompatActivity implements AdapterView.OnItemSelectedListener,
        SearchView.OnQueryTextListener {
    private static final String SELECTED_TABLE = "SELECTED_TABLE";

    @NonNull
    private final AdapterProvider adapterProvider = new AdapterProvider(this);

    @Nullable
    private AppCompatSpinner spinnerTable;
    @Nullable
    private SearchView searchView;
    private ExtendedFloatingActionButton addNewItem;
    private AbsRecyclerAdapter adapter;
    private RecyclerView listView;
    private SharedPreferences preferences;

    private final ActivityResultLauncher<String> post21SaveLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"),
            uri -> {
                if (uri == null) return;
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) throw new IOException();
                    saveAsJson(os);
                    Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
                } catch (Throwable th) {
                    th.printStackTrace();
                    Toast.makeText(this, R.string.failed, Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<String> changeLogSaveLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"),
            uri -> {
                if (uri == null) return;
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) throw new IOException();
                    SettingsChangeLogger.export(this, os);
                    Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
                } catch (Throwable th) {
                    th.printStackTrace();
                    Toast.makeText(this, R.string.failed, Toast.LENGTH_SHORT).show();
                }
            });

    private void displayOneTimeWarningDialog() {
        final SharedPreferences warningPreferences = getPreferences(MODE_PRIVATE);
        boolean hasWarned = warningPreferences.getBoolean("has_warned", false);
        if (hasWarned) return;
        new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.startup_warning)
                .setNegativeButton(R.string.close, null)
                .show();
        warningPreferences.edit().putBoolean("has_warned", true).apply();
    }

    public void addNewItemDialog() {
        View editorDialogView = getLayoutInflater().inflate(R.layout.dialog_new, null);
        MaterialAutoCompleteTextView keyNameView = editorDialogView.findViewById(R.id.txtName);
        MaterialAutoCompleteTextView keyValueView = editorDialogView.findViewById(R.id.txtValue);
        MaterialCheckBox performViaShortcut = editorDialogView.findViewById(R.id.checkbox_2);
        MaterialCheckBox performLock = editorDialogView.findViewById(R.id.checkbox_lock);

        if (adapter.canCreateShortcut()) {
            performViaShortcut.setVisibility(View.VISIBLE);
        } else {
            performViaShortcut.setVisibility(View.GONE);
        }
        if (adapter.canLock()) {
            performLock.setVisibility(View.VISIBLE);
        } else {
            performLock.setVisibility(View.GONE);
            performLock.setChecked(false);
        }

        configureSettingSuggestions(keyNameView, keyValueView);
        keyNameView.requestFocus();

        new MaterialAlertDialogBuilder(this)
                .setView(editorDialogView)
                .setTitle(R.string.new_item)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    Editable keyName = keyNameView.getText();
                    Editable keyValue = keyValueView.getText();
                    if (TextUtils.isEmpty(keyName) || keyValue == null) return;

                    String key = keyName.toString();
                    String val = keyValue.toString();
                    String tableType = EditorUtils.toTableType(adapter.getListType());

                    adapter.create(key, val);

                    if (performLock.isChecked()) {
                        getSharedPreferences("locked_settings", Context.MODE_PRIVATE)
                                .edit()
                                .putString(key + ":" + tableType, val)
                                .apply();
                        // A lock is persistent by definition. settings put/update also recreates
                        // a missing key on boot, so UPDATE is the correct unified boot action.
                        BootUtils.add(this, new ActionItem(
                                ActionResult.TYPE_UPDATE, tableType, key, val));
                        startGuardian();
                    }

                    if (adapter.canCreateShortcut() && performViaShortcut.isChecked()) {
                        ActionItem actionItem = new ActionItem(
                                ActionResult.TYPE_CREATE, tableType, key, val);
                        ShortcutUtils.displayShortcutTypeChooserDialog(this, actionItem);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void configureSettingSuggestions(@NonNull MaterialAutoCompleteTextView keyNameView,
                                             @NonNull MaterialAutoCompleteTextView keyValueView) {
        if (!(adapter instanceof SettingsRecyclerAdapter)) return;

        String settingsType = ((SettingsRecyclerAdapter) adapter).getSettingsType();
        Set<String> currentKeys = new HashSet<>();
        for (Pair<String, String> item : adapter.getAllItems()) {
            currentKeys.add(item.first);
        }

        LinkedHashSet<String> suggestions = new LinkedHashSet<>();
        suggestions.addAll(KnownKeys.forSettingsType(settingsType));
        suggestions.addAll(SettingsDiscoveryCatalog.getKeys(this, settingsType));
        suggestions.removeAll(currentKeys);

        ArrayAdapter<String> keyAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_dropdown_item_1line,
                new ArrayList<>(suggestions));
        keyNameView.setAdapter(keyAdapter);
        keyNameView.setThreshold(0);
        keyNameView.setOnClickListener(v -> keyNameView.showDropDown());

        Runnable updateValueSuggestions = () -> {
            Editable editable = keyNameView.getText();
            String key = editable != null ? editable.toString() : "";
            List<String> observedValues = SettingsDiscoveryCatalog.getValues(this, settingsType, key);
            keyValueView.setAdapter(new ArrayAdapter<>(
                    this,
                    android.R.layout.simple_dropdown_item_1line,
                    observedValues));
            keyValueView.setThreshold(0);
        };

        keyNameView.setOnItemClickListener((parent, view, position, id) -> updateValueSuggestions.run());
        keyNameView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateValueSuggestions.run();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        keyValueView.setOnClickListener(v -> keyValueView.showDropDown());
    }

    private void startGuardian() {
        try {
            ContextCompat.startForegroundService(
                    this, new Intent(this, SettingsMonitorService.class));
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @Override
    public void onCreate(Bundle bundle) {
        preferences = getSharedPreferences("prefs", MODE_PRIVATE);
        int mode = preferences.getInt("theme", AppCompatDelegate.getDefaultNightMode());
        AppCompatDelegate.setDefaultNightMode(mode);
        super.onCreate(bundle);
        setContentView(R.layout.activity_editor);
        setSupportActionBar(findViewById(R.id.toolbar));

        // Ask the existing Shizuku/Shizuku+ provider for access when its binder is already up.
        // This changes only SetEditLocker; no Shizuku-side code or configuration is touched.
        PrivilegeBridge.requestShizukuPermissionIfNeeded(this);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayShowTitleEnabled(false);
            actionBar.setDisplayShowCustomEnabled(true);
            actionBar.setCustomView(R.layout.toolbar_custom_view);
            View actionBarView = actionBar.getCustomView();
            spinnerTable = actionBarView.findViewById(R.id.spinner);
            spinnerTable.setOnItemSelectedListener(this);
            spinnerTable.setAdapter(ArrayAdapter.createFromResource(
                    this, R.array.settings_table, R.layout.item_spinner));
        }

        listView = findViewById(R.id.recycler_view);
        listView.setLayoutManager(new LinearLayoutManager(this));
        new FastScrollerBuilder(listView).useMd2Style().build();

        addNewItem = findViewById(R.id.efab);
        addNewItem.setOnClickListener(v -> {
            if (adapter instanceof SettingsRecyclerAdapter) {
                Boolean isGranted = EditorUtils.checkSettingsPermission(
                        this, ((SettingsRecyclerAdapter) adapter).getSettingsType());
                if (isGranted == null) return;
                if (isGranted) {
                    addNewItemDialog();
                } else {
                    EditorUtils.displayGrantPermissionMessage(this);
                }
            }
        });
        UiUtils.applyWindowInsetsAsMargin(addNewItem);
        displayOneTimeWarningDialog();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_editor_actions, menu);
        searchView = (SearchView) menu.findItem(R.id.action_search).getActionView();
        searchView.setOnQueryTextListener(this);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_export) {
            post21SaveLauncher.launch(getFileName());
            return true;
        } else if (id == R.id.action_export_change_log) {
            changeLogSaveLauncher.launch(getChangeLogFileName());
            return true;
        } else if (id == R.id.action_theme) {
            List<Integer> themeMap = new ArrayList<>(4);
            themeMap.add(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            themeMap.add(AppCompatDelegate.MODE_NIGHT_NO);
            themeMap.add(AppCompatDelegate.MODE_NIGHT_YES);
            themeMap.add(AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY);
            int mode = preferences.getInt("theme", AppCompatDelegate.getDefaultNightMode());
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.theme)
                    .setSingleChoiceItems(R.array.theme_options, themeMap.indexOf(mode), (dialog, which) -> {
                        int newMode = themeMap.get(which);
                        preferences.edit().putInt("theme", newMode).apply();
                        AppCompatDelegate.setDefaultNightMode(newMode);
                        dialog.dismiss();
                    })
                    .show();
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onItemSelected(AdapterView<?> adapterView, View view, int position, long id) {
        listView.setAdapter(adapter = adapterProvider.getRecyclerAdapter(position));
        if (adapter.canCreate()) {
            addNewItem.show();
        } else {
            addNewItem.hide();
        }
        if (searchView != null) {
            searchView.setQuery(null, false);
            searchView.clearFocus();
            searchView.setIconified(true);
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> adapterView) {
        addNewItem.show();
    }

    @Override
    public void onRestoreInstanceState(@NonNull Bundle bundle) {
        if (spinnerTable != null) {
            spinnerTable.setSelection(bundle.getInt(SELECTED_TABLE));
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle bundle) {
        super.onSaveInstanceState(bundle);
        if (spinnerTable != null) {
            bundle.putInt(SELECTED_TABLE, spinnerTable.getSelectedItemPosition());
        }
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        return false;
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        if (adapter != null) {
            adapter.filter(newText.toLowerCase(Locale.ROOT));
        }
        return false;
    }

    private String getFileName() {
        return "SetEdit-" + System.currentTimeMillis() + ".json";
    }

    private String getChangeLogFileName() {
        return "SetEdit-changes-" + System.currentTimeMillis() + ".jsonl";
    }

    private void saveAsJson(OutputStream os) throws JSONException, IOException {
        String jsonString = EditorUtils.getJson(
                adapter.getAllItems(),
                adapter instanceof SettingsRecyclerAdapter
                        ? ((SettingsRecyclerAdapter) adapter).getSettingsType()
                        : null);
        os.write(jsonString.getBytes());
    }
}
