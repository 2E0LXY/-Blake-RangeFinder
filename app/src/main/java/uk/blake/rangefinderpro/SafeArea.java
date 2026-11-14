package uk.blake.rangefinderpro;

/** Pure calculation used when applying navigation-bar and keyboard insets. */
final class SafeArea {
    private SafeArea() {}
    static int bottomInset(int navigationBarBottom, int keyboardBottom) {
        return Math.max(0, Math.max(navigationBarBottom, keyboardBottom));
    }
}
