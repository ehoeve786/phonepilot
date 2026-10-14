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
    String openUrl(String url, String packageName) = 10;

    void expandStatusBar(boolean quickSettings) = 11;

    // One allowlisted setting (SettingKey.wire); the value comes back in canonical form.
    String readSetting(String key) = 12;

    // The value must already be canonical for the key.
    void writeSetting(String key, String value) = 13;

    // App management (AppAdminArgs checks every argument).
    void forceStop(String packageName) = 14;

    // `dumpsys package` output for one package.
    String dumpPackage(String packageName) = 15;

    // `appops get` output for one package.
    String appOps(String packageName) = 16;

    void setPermission(String packageName, String permission, boolean granted) = 17;

    void setAppOp(String packageName, String op, String mode) = 18;

    void setPackageEnabled(String packageName, boolean enabled) = 19;

    void clearPackageData(String packageName) = 20;

    // shell.exec: `sh -c command`. Returns exit code, stdout and stderr separated by NUL characters;
    // the exit code is -1 when the command timed out.
    String runShell(String command, long timeoutMs) = 21;

    // adb.query and adb.logs: a read-only command AdbQuery allows, run as argv without a shell. Only
    // lines containing filter (ignoring case) are kept when it is not empty. Same result format as runShell.
    String runReadOnly(in String[] argv, String filter, long timeoutMs) = 22;

    // Battery optimization: `cmd deviceidle whitelist +package` (exempt) or `-package`.
    void setDozeExempt(String packageName, boolean exempt) = 23;

    // `am set-standby-bucket`; bucket is one of AppAdminArgs.STANDBY_BUCKETS.
    void setStandbyBucket(String packageName, String bucket) = 24;

    // `am start -W` or `am broadcast` with arguments IntentArgs checks; returns what am printed.
    String startIntent(in String[] args, boolean broadcast) = 25;

    // Required by Shizuku to stop the service.
    void destroy() = 16777114;
}
