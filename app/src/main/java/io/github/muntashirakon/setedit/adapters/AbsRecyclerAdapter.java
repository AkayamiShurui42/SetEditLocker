package io.github.muntashirakon.setedit.adapters;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.Editable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Filter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.util.Pair;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.muntashirakon.setedit.EditorUtils;
import io.github.muntashirakon.setedit.R;
import io.github.muntashirakon.setedit.TableTypeInt;
import io.github.muntashirakon.setedit.boot.ActionItem;
import io.github.muntashirakon.setedit.boot.BootUtils;
import io.github.muntashirakon.setedit.boot.SettingsDiscoveryCatalog;
import io.github.muntashirakon.setedit.boot.SettingsMonitorService;
import io.github.muntashirakon.setedit.shortcut.ShortcutUtils;
import io.github.muntashirakon.setedit.utils.ActionResult;
import io.github.muntashirakon.setedit.utils.PrivilegeBridge;

public abstract class AbsRecyclerAdapter extends RecyclerView.Adapter<AbsRecyclerAdapter.ViewHolder> {
    protected final FragmentActivity context;
    private String constraint;

    public AbsRecyclerAdapter(FragmentActivity context) {
        setHasStableIds(true);
        this.context = context;
    }

    public abstract void refresh();

    @NonNull
    public abstract List<Pair<String, String>> getAllItems();

    @TableTypeInt
    public abstract int getListType();

    public void filter(String constraint) {
        this.constraint = constraint;
        getFilter().filter(constraint);
    }

    public void filter() {
        getFilter().filter(constraint);
    }

    public boolean canSetOnReboot() {
        return false;
    }

    public boolean canCreateShortcut() {
        return false;
    }

    public boolean canCreate() {
        return false;
    }

    public boolean canEdit() {
        return false;
    }

    public boolean canDelete() {
        return false;
    }

    public boolean canLock() {
        if (PrivilegeBridge.canUsePrivilegedShell()) return true;
        if (getListType() == TableTypeInt.TABLE_SECURE || getListType() == TableTypeInt.TABLE_GLOBAL) {
            return ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        if (getListType() == TableTypeInt.TABLE_SYSTEM) {
            return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                    && android.provider.Settings.System.canWrite(context);
        }
        return false;
    }

    public void create(String keyName, String newValue) {
    }

    public void update(String keyName, String newValue) {
    }

    public void delete(String keyName) {
    }

    @NonNull
    @Override
    public final ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_setting, parent, false);
        return new ViewHolder(view);
    }

    public abstract Pair<String, String> getItem(int position);

    @Override
    public abstract long getItemId(int position);

    @Override
    public abstract int getItemCount();

    @Override
    public final void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Pair<String, String> key = getItem(position);
        onBindViewHolder(holder, key.first, key.second, position);
    }

    protected abstract Filter getFilter();

    private void onBindViewHolder(@NonNull ViewHolder holder, String keyName, String keyValue, int position) {
        holder.keyName.setText(keyName);
        holder.keyValue.setText(keyValue);
        holder.itemView.setBackgroundColor(ContextCompat.getColor(
                context,
                position % 2 == 1 ? android.R.color.transparent : R.color.semi_transparent));
        holder.itemView.setOnClickListener(v -> onClickItem(v, keyName, keyValue, position));
    }

    protected void onClickItem(View view, String keyName, String keyValue, int position) {
        View v = View.inflate(context, R.layout.dialog_edit, null);
        v.findViewById(R.id.button_help).setOnClickListener(v2 -> openHelp(keyName));
        ((TextView) v.findViewById(R.id.title)).setText(keyName);
        AutoCompleteTextView editText = v.findViewById(R.id.txt);
        MaterialCheckBox performViaShortcut = v.findViewById(R.id.checkbox_2);
        MaterialCheckBox performLock = v.findViewById(R.id.checkbox_lock);

        String tableType = EditorUtils.toTableType(getListType());
        SharedPreferences lockedPrefs = context.getSharedPreferences("locked_settings", Context.MODE_PRIVATE);
        boolean isLocked = lockedPrefs.contains(keyName + ":" + tableType);
        performLock.setChecked(isLocked);

        SharedPreferences prefs = context.getSharedPreferences("value_history_" + keyName, Context.MODE_PRIVATE);
        String historyStr = prefs.getString("history", "");
        String[] history = historyStr.isEmpty() ? new String[]{} : historyStr.split("\n");

        Set<String> valueSuggestions = new LinkedHashSet<>();
        for (String historyValue : history) {
            if (!historyValue.isEmpty()) valueSuggestions.add(historyValue);
        }
        if (this instanceof SettingsRecyclerAdapter) {
            String settingsType = ((SettingsRecyclerAdapter) this).getSettingsType();
            valueSuggestions.addAll(SettingsDiscoveryCatalog.getValues(context, settingsType, keyName));
        }
        ArrayAdapter<String> historyAdapter = new ArrayAdapter<>(
                context,
                android.R.layout.simple_dropdown_item_1line,
                new ArrayList<>(valueSuggestions));
        editText.setAdapter(historyAdapter);
        editText.setThreshold(0);
        editText.setOnClickListener(clicked -> editText.showDropDown());

        boolean canEdit = canEdit();
        boolean canDelete = canDelete();
        editText.setText(keyValue);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setView(v)
                .setNegativeButton(R.string.close, null);

        if (canCreateShortcut() && (canEdit || canDelete)) {
            performViaShortcut.setVisibility(View.VISIBLE);
        } else {
            performViaShortcut.setVisibility(View.GONE);
        }
        if (canLock() && (canEdit || canDelete)) {
            performLock.setVisibility(View.VISIBLE);
        } else {
            performLock.setVisibility(View.GONE);
            performLock.setChecked(false);
        }

        if (canEdit) {
            builder.setPositiveButton(R.string.save, (dialog, which) -> {
                Editable editable = editText.getText();
                if (editable == null) return;
                String newValue = editable.toString();

                SharedPreferences.Editor historyEditor = prefs.edit();
                if (!historyStr.contains(newValue) && !newValue.isEmpty() && !newValue.equals("null")) {
                    historyEditor.putString("history", newValue + "\n" + historyStr).apply();
                }

                update(keyName, newValue);

                if (performLock.isChecked()) {
                    lockedPrefs.edit().putString(keyName + ":" + tableType, newValue).apply();
                    BootUtils.add(context, new ActionItem(
                            ActionResult.TYPE_UPDATE, tableType, keyName, newValue));
                    startGuardian();
                } else {
                    lockedPrefs.edit().remove(keyName + ":" + tableType).apply();
                    BootUtils.delete(context, tableType, keyName);
                }

                if (canCreateShortcut() && performViaShortcut.isChecked()) {
                    ActionItem actionItem = new ActionItem(
                            ActionResult.TYPE_UPDATE, tableType, keyName, newValue);
                    ShortcutUtils.displayShortcutTypeChooserDialog(context, actionItem);
                }
            });
        } else {
            editText.setKeyListener(null);
        }

        if (canDelete) {
            builder.setNeutralButton(R.string.delete, (dialog, which) -> {
                delete(keyName);
                lockedPrefs.edit().remove(keyName + ":" + tableType).apply();
                BootUtils.delete(context, tableType, keyName);
                if (canCreateShortcut() && performViaShortcut.isChecked()) {
                    ActionItem actionItem = new ActionItem(
                            ActionResult.TYPE_DELETE, tableType, keyName, null);
                    ShortcutUtils.displayShortcutTypeChooserDialog(context, actionItem);
                }
            });
        }

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(dialogInterface -> {
            if (canEdit()) {
                editText.requestFocus();
                editText.requestFocusFromTouch();
                if (keyValue != null) {
                    editText.setSelection(0, keyValue.length());
                }
                editText.postDelayed(() -> {
                    InputMethodManager imm = (InputMethodManager) editText.getContext()
                            .getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT);
                }, 200);
            }
        });
        dialog.show();
    }

    private void startGuardian() {
        try {
            ContextCompat.startForegroundService(
                    context,
                    new Intent(context, SettingsMonitorService.class));
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    protected void setMessage(CharSequence charSequence) {
        new MaterialAlertDialogBuilder(context)
                .setMessage(charSequence)
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void openHelp(String keyName) {
        String str;
        StringBuilder sb = new StringBuilder("https://search.brave.com/search?q=android+");
        switch (getListType()) {
            case TableTypeInt.TABLE_SYSTEM:
                str = "settings put system \"";
                break;
            case TableTypeInt.TABLE_SECURE:
                str = "settings put secure \"";
                break;
            case TableTypeInt.TABLE_GLOBAL:
                str = "settings put global \"";
                break;
            case TableTypeInt.TABLE_PROPERTIES:
                str = "setprop \"";
                break;
            case TableTypeInt.TABLE_JAVA:
                str = "java properties \"";
                break;
            case TableTypeInt.TABLE_ENV:
                str = "environment \"";
                break;
            default:
                return;
        }
        sb.append(str);
        sb.append(Uri.encode(keyName));
        sb.append('\"');
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(sb.toString())));
        } catch (Exception ignore) {
        }
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public final TextView keyName;
        public final TextView keyValue;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            keyName = itemView.findViewById(R.id.txtName);
            keyValue = itemView.findViewById(R.id.txtValue);
        }
    }
}
