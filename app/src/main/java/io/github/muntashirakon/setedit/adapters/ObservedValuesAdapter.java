package io.github.muntashirakon.setedit.adapters;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Filter;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * AutoComplete adapter which shows every observed value even when an existing
 * setting already fills the text field. Ordinary ArrayAdapter filtering hides
 * historical values that do not match the current value.
 */
public final class ObservedValuesAdapter extends ArrayAdapter<String> {
    private final List<String> choices;
    private final Filter allChoicesFilter = new Filter() {
        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            FilterResults results = new FilterResults();
            results.values = choices;
            results.count = choices.size();
            return results;
        }

        @Override
        protected void publishResults(CharSequence constraint, FilterResults results) {
            notifyDataSetChanged();
        }
    };

    public ObservedValuesAdapter(@NonNull Context context, @NonNull List<String> values) {
        super(context, android.R.layout.simple_dropdown_item_1line, new ArrayList<>(values));
        choices = new ArrayList<>(values);
    }

    @NonNull
    @Override
    public Filter getFilter() {
        return allChoicesFilter;
    }
}
