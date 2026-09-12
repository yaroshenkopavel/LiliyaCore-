package pro.liliya.app

import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test

class ProductionAndroidFirstRunProductProfileSourceOwnerContractTest {
    @After
    fun cleanup() {
        ProductionAndroidFirstRunProductProfileSourceOwner.clearForTests()
    }

    @Test
    fun source_owner_is_install_once_and_never_discovers_or_replaces_profile_source() {
        assertNull(ProductionAndroidFirstRunProductProfileSourceOwner.current())
        val first = ProductionAndroidFirstRunProductProfileSource { error("not executed by owner") }
        val replacement = ProductionAndroidFirstRunProductProfileSource { error("must not replace") }

        assertTrue(ProductionAndroidFirstRunProductProfileSourceOwner.install(first))
        assertFalse(ProductionAndroidFirstRunProductProfileSourceOwner.install(replacement))
        assertSame(first, ProductionAndroidFirstRunProductProfileSourceOwner.current())
    }
}
