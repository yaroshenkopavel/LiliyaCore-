package pro.liliya.app

import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test
import pro.liliya.android.runtime.AndroidProductRuntimeAdmissionFailure
import pro.liliya.android.runtime.AndroidProductRuntimeAdmissionResult

class ProductionAndroidRuntimeConfigurationContractTest {
    @After
    fun cleanup() {
        ProductionAndroidRuntimeConfiguration.clearForTests()
        ProductionAndroidAppTrustedWiring.clearForTests()
    }

    @Test
    fun missing_configuration_keeps_trusted_wiring_absent() {
        assertFalse(ProductionAndroidRuntimeConfigurationInstaller.ensureTrustedWiringInstalled())
        assertNull(ProductionAndroidAppTrustedWiring.current())
    }

    @Test
    fun exact_configuration_installs_trusted_wiring_once() {
        val first = rejectedSources(AndroidProductRuntimeAdmissionFailure.AUTHORITY_DENIED)
        val second = rejectedSources(AndroidProductRuntimeAdmissionFailure.INTERNAL_FAILURE)

        assertTrue(ProductionAndroidRuntimeConfiguration.install(first))
        assertFalse(ProductionAndroidRuntimeConfiguration.install(second))
        assertTrue(ProductionAndroidRuntimeConfiguration.current() === first)

        assertTrue(ProductionAndroidRuntimeConfigurationInstaller.ensureTrustedWiringInstalled())
        val exactPort = ProductionAndroidAppTrustedWiring.current()
        assertTrue(exactPort != null)

        assertTrue(ProductionAndroidRuntimeConfigurationInstaller.ensureTrustedWiringInstalled())
        assertTrue(ProductionAndroidAppTrustedWiring.current() === exactPort)
    }

    private fun rejectedSources(
        failure: AndroidProductRuntimeAdmissionFailure
    ): ProductionAndroidRuntimeWiringSources =
        ProductionAndroidRuntimeWiringSources(
            admission = { AndroidProductRuntimeAdmissionResult.Rejected(failure) },
            preparedInputs = { error("must not execute for rejected admission") }
        )
}
