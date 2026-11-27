package io.github.muntashirakon.setedit.boot;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Bounded history of distinct values observed from the Android settings provider.
 * A repeated snapshot of the current value does not cause another disk write.
 */
public final class ObservedValueHistory {
    private ObservedValueHistory() {
    }

    @NonNull
    public static List<String> prependNew(@NonNull List<String> existing,
                                          @NonNull String value,
                                          int maxValues) {
        if (maxValues <= 0) {
            throw new IllegalArgumentException("maxValues must be positive");
        }
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        ordered.add(value);
        ordered.addAll(existing);
        List<String> result = new ArrayList<>(Math.min(ordered.size(), maxValues));
        for (String item : ordered) {
            if (result.size() >= maxValues) break;
            result.add(item);
        }
        return result;
    }
}
