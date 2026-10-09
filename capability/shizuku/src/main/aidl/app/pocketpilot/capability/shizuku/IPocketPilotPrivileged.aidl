package app.pocketpilot.capability.shizuku;

// The fixed method set of the privileged service (spec section 6, Shizuku privileged service).
// Bump PrivilegedService.VERSION with every change; the client rebinds when versions differ.
interface IPocketPilotPrivileged {
    int getVersion() = 1;

    void tap(int x, int y) = 2;

    void swipe(int fromX, int fromY, int toX, int toY, long durationMs) = 3;

    void keyEvents(in int[] keyCodes) = 4;

    // Printable ASCII only; `input text` cannot type anything else.
    void text(String text) = 5;

    // Raw `screencap` output: a header, then RGBA pixels.
    ParcelFileDescriptor captureScreen() = 6;

    // uiautomator XML of the active window.
    ParcelFileDescriptor dumpUiHierarchy() = 7;

    // "package/activity" of the resumed activity, or empty.
    String foregroundActivity() = 8;

    void launchPackage(String packageName) = 9;

    // http and https only.
    void openUrl(String url) = 10;

    void expandStatusBar(boolean quickSettings) = 11;

    // Required by Shizuku to stop the service.
    void destroy() = 16777114;
}
