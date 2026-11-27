package io.github.muntashirakon.setedit.boot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import io.github.muntashirakon.setedit.utils.ActionResult;

public class ActionItemTest {
    @Test
    public void updateWithEmptyValueSurvivesSaveAndReload() {
        ActionItem saved = new ActionItem(ActionResult.TYPE_UPDATE, "system", "empty_value", "");
        ActionItem restored = ActionItem.unflattenFromString(saved.flattenToString());

        assertEquals(ActionResult.TYPE_UPDATE, restored.action);
        assertEquals("system", restored.table);
        assertEquals("empty_value", restored.name);
        assertEquals("", restored.value);
    }

    @Test
    public void ordinaryUpdateSurvivesSaveAndReload() {
        ActionItem saved = new ActionItem(ActionResult.TYPE_UPDATE, "secure", "example", "enabled");
        ActionItem restored = ActionItem.unflattenFromString(saved.flattenToString());

        assertEquals("enabled", restored.value);
    }

    @Test
    public void deletedSettingKeepsNullValue() {
        ActionItem saved = new ActionItem(ActionResult.TYPE_DELETE, "global", "obsolete", null);
        ActionItem restored = ActionItem.unflattenFromString(saved.flattenToString());

        assertEquals(ActionResult.TYPE_DELETE, restored.action);
        assertNull(restored.value);
    }

    @Test
    public void tabInsideValueIsNotTruncated() {
        ActionItem saved = new ActionItem(ActionResult.TYPE_UPDATE, "system", "example", "one\ttwo");
        ActionItem restored = ActionItem.unflattenFromString(saved.flattenToString());

        assertEquals("one\ttwo", restored.value);
    }

    @Test
    public void malformedRecordFailsClearly() {
        assertThrows(IllegalArgumentException.class,
                () -> ActionItem.unflattenFromString("1\tsystem"));
    }
}
