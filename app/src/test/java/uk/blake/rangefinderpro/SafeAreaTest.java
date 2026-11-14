package uk.blake.rangefinderpro;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class SafeAreaTest {
    @Test public void threeButtonNavigationRemainsVisible() {
        assertEquals(72, SafeArea.bottomInset(72, 0));
    }

    @Test public void gestureNavigationGetsItsInset() {
        assertEquals(24, SafeArea.bottomInset(24, 0));
    }

    @Test public void keyboardTakesPrecedence() {
        assertEquals(390, SafeArea.bottomInset(72, 390));
    }

    @Test public void neverUsesNegativePadding() {
        assertEquals(0, SafeArea.bottomInset(-10, -25));
    }
}
