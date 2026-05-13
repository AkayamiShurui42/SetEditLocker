package io.github.muntashirakon.util;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.util.DisplayMetrics;

import org.junit.Test;

public class UiUtilsTest {

    @Test
    public void pxToDp_convertsCorrectly() {
        // Arrange
        Context mockContext = mock(Context.class);
        Resources mockResources = mock(Resources.class);
        DisplayMetrics mockDisplayMetrics = mock(DisplayMetrics.class);
        mockDisplayMetrics.density = 2.0f; // 2 pixels = 1 dp

        when(mockContext.getResources()).thenReturn(mockResources);
        when(mockResources.getDisplayMetrics()).thenReturn(mockDisplayMetrics);

        // Act
        int resultDp = UiUtils.pxToDp(mockContext, 10);

        // Assert
        assertEquals("10px should convert to 5dp at 2.0 density", 5, resultDp);
    }

    @Test
    public void pxToDp_convertsZero() {
        // Arrange
        Context mockContext = mock(Context.class);
        Resources mockResources = mock(Resources.class);
        DisplayMetrics mockDisplayMetrics = mock(DisplayMetrics.class);
        mockDisplayMetrics.density = 2.0f;

        when(mockContext.getResources()).thenReturn(mockResources);
        when(mockResources.getDisplayMetrics()).thenReturn(mockDisplayMetrics);

        // Act
        int resultDp = UiUtils.pxToDp(mockContext, 0);

        // Assert
        assertEquals("0px should convert to 0dp", 0, resultDp);
    }

    @Test
    public void pxToDp_convertsNegative() {
        // Arrange
        Context mockContext = mock(Context.class);
        Resources mockResources = mock(Resources.class);
        DisplayMetrics mockDisplayMetrics = mock(DisplayMetrics.class);
        mockDisplayMetrics.density = 1.5f;

        when(mockContext.getResources()).thenReturn(mockResources);
        when(mockResources.getDisplayMetrics()).thenReturn(mockDisplayMetrics);

        // Act
        int resultDp = UiUtils.pxToDp(mockContext, -30);

        // Assert
        assertEquals("-30px should convert to -20dp at 1.5 density", -20, resultDp);
    }
}
