package io.github.muntashirakon.setedit.boot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ObservedValueHistoryTest {
    @Test
    public void latestDistinctObservationAppearsFirst() {
        List<String> values = ObservedValueHistory.prependNew(
                Arrays.asList("60", "90"), "120", 10);
        assertEquals(Arrays.asList("120", "60", "90"), values);
    }

    @Test
    public void duplicatesRemainUnique() {
        List<String> values = ObservedValueHistory.prependNew(
                Arrays.asList("120", "60", "90"), "60", 10);
        assertEquals(Arrays.asList("60", "120", "90"), values);
    }

    @Test
    public void emptyStringIsAnObservedValue() {
        List<String> values = ObservedValueHistory.prependNew(
                Collections.singletonList("1"), "", 10);
        assertEquals(Arrays.asList("", "1"), values);
    }

    @Test
    public void evictsOldestWhenFull() {
        List<String> values = ObservedValueHistory.prependNew(
                Arrays.asList("new", "old"), "latest", 2);
        assertEquals(Arrays.asList("latest", "new"), values);
    }

    @Test
    public void rejectsInvalidHistoryLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> ObservedValueHistory.prependNew(
                        Collections.emptyList(), "1", 0));
    }
}
