package app.pocketpilot

import kotlin.test.Test
import kotlin.test.assertEquals

class FlavorModuleTest {
    @Test
    fun `each flavor binds its own policy profile`() {
        assertEquals(
            BuildConfig.FLAVOR,
            FlavorModule
                .policyProfile()
                .flavor.name
                .lowercase(),
        )
    }
}
