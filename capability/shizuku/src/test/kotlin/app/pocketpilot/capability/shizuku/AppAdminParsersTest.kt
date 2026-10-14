package app.pocketpilot.capability.shizuku

import app.pocketpilot.capability.api.apps.ActivityEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppAdminParsersTest {
    @Test
    fun `reads runtime permissions of the first user only`() {
        val dump =
            """
            Packages:
              Package [com.example.app] (1a2b3c):
                requested permissions:
                  android.permission.CAMERA
                install permissions:
                  android.permission.INTERNET: granted=true
                User 0: ceDataInode=1 installed=true
                  gids=[3003]
                  runtime permissions:
                    android.permission.CAMERA: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED ]
                    android.permission.POST_NOTIFICATIONS: granted=true, flags=[ USER_SET ]
                  enabledComponents:
                User 10: ceDataInode=2 installed=true
                  runtime permissions:
                    android.permission.CAMERA: granted=true
            """.trimIndent()
        assertEquals(
            mapOf("android.permission.CAMERA" to false, "android.permission.POST_NOTIFICATIONS" to true),
            AppAdminParsers.runtimePermissions(dump),
        )
    }

    @Test
    fun `reads app op modes`() {
        val output =
            """
            Uid mode: COARSE_LOCATION: foreground
            RUN_IN_BACKGROUND: ignore; time=+2h ago
            CAMERA: allow; time=+1d ago; duration=+3s
            COARSE_LOCATION: allow
            """.trimIndent()
        assertEquals(
            mapOf("COARSE_LOCATION" to "allow", "RUN_IN_BACKGROUND" to "ignore", "CAMERA" to "allow"),
            AppAdminParsers.appOps(output),
        )
    }

    @Test
    fun `reads an app's activities with their intent filters`() {
        val dump =
            """
            Activity Resolver Table:
              Non-Data Actions:
                  android.intent.action.MAIN:
                    5a3b2c1 com.example.clock/.DeskClock filter 8f9e0d
                      Action: "android.intent.action.MAIN"
                      Category: "android.intent.category.LAUNCHER"
                  com.example.action.STOPWATCH:
                    1b2c3d4 com.example.clock/.StopwatchActivity filter 2e3f4a
                      Action: "com.example.action.STOPWATCH"
                      Category: "android.intent.category.DEFAULT"
                    7c8d9e0 com.other.app/.Spy filter 3f4a5b
                      Action: "com.example.action.STOPWATCH"
              Schemes:
                  clock:
                    5a3b2c1 com.example.clock/.DeskClock filter 9a8b7c
                      Action: "android.intent.action.VIEW"
                      Scheme: "clock"

            Receiver Resolver Table:
              Non-Data Actions:
                  android.intent.action.BOOT_COMPLETED:
                    6d7e8f9 com.example.clock/.BootReceiver filter 1a2b3c
                      Action: "android.intent.action.BOOT_COMPLETED"
            """.trimIndent()
        assertEquals(
            listOf(
                ActivityEntry(
                    "com.example.clock/.DeskClock",
                    listOf("android.intent.action.MAIN", "android.intent.action.VIEW"),
                    listOf("android.intent.category.LAUNCHER"),
                    listOf("clock"),
                ),
                ActivityEntry(
                    "com.example.clock/.StopwatchActivity",
                    listOf("com.example.action.STOPWATCH"),
                    listOf("android.intent.category.DEFAULT"),
                ),
            ),
            AppAdminParsers.activities(dump, "com.example.clock"),
        )
    }

    @Test
    fun `keeps activities and the package record of a long dump`() {
        val dump =
            "Activity Resolver Table:\n  Non-Data Actions:\n" + "      filler line\n".repeat(5_000) +
                "\nPackages:\n  Package [com.android.settings] (1a2b):\n" + "    more\n".repeat(5_000)
        val trimmed = AppAdminParsers.trimDump(dump, 20_000)
        assertTrue(trimmed.length <= 20_000)
        assertTrue(trimmed.startsWith("Activity Resolver Table:"))
        assertTrue(trimmed.contains("Package [com.android.settings]"))
        assertEquals("short", AppAdminParsers.trimDump("short", 20_000))
    }
}
