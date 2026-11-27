package io.github.muntashirakon.setedit.boot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

import io.github.muntashirakon.setedit.utils.ActionResult;

public class ActionItem {
    @NonNull
    public static ActionItem unflattenFromString(@NonNull String str) {
        // Keep trailing empty fields: an empty string is a valid setting value.
        // The limit preserves any tabs within the saved value as well.
        String[] fields = str.split("\\t", 4);
        if (fields.length < 3) {
            throw new IllegalArgumentException("Malformed boot action: expected action, table, and key");
        }
        int action = Integer.parseInt(fields[0]);
        String value = fields.length == 4 ? fields[3] : null;
        return new ActionItem(action, fields[1], fields[2], value);
    }

    @ActionResult.ActionType
    public final int action;
    @NonNull
    public final String table;
    @NonNull
    public final String name;
    @Nullable
    public final String value;

    public ActionItem(@ActionResult.ActionType int action, @NonNull String table, @NonNull String name, @Nullable String value) {
        this.action = action;
        this.table = table;
        this.name = name;
        this.value = value;
        if (action != ActionResult.TYPE_DELETE && value == null) {
            throw new IllegalArgumentException("Value must exist if the property needs to be updated");
        }
    }

    public String flattenToString() {
        StringBuilder sb = new StringBuilder();
        sb.append(action).append("\t").append(table).append("\t").append(name);
        if (value != null) {
            sb.append("\t").append(value);
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ActionItem)) return false;
        ActionItem that = (ActionItem) o;
        return Objects.equals(table, that.table) && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(table, name);
    }
}
