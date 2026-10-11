package app.pocketpilot.capability.shizuku

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
