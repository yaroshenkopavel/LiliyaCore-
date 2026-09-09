package pro.liliya.app

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test
import pro.liliya.android.runtime.AndroidProductRuntimeStartupRequestSourceFailure
import pro.liliya.android.runtime.AndroidProductRuntimeStartupRequestSourceResult

class ProductionAndroidRuntimeStartupSourceInstallContractTest {
    @After
    fun cleanup() {
        ProductionAndroidRuntimeConfiguration.clearForTests()
        ProductionAndroidAppTrustedWiring.clearForTests()
    }

    @Test
    fun source_rejection_is_preserved_and_configuration_stays_absent() {
        val result = ProductionAndroidRuntimeStartupSourceInstall.prepareAndInstall(
            ProductionAndroidRuntimeStartupRequestSourcePort {
                AndroidProductRuntimeStartupRequestSourceResult.Rejected(
                    AndroidProductRuntimeStartupRequestSourceFailure.COGNITIVE_STORAGE_CORRUPT
                )
            }
        )

        val rejected = assertIs<ProductionAndroidRuntimeStartupSourceInstallResult.SourceRejected>(result)
        assertEquals(
            AndroidProductRuntimeStartupRequestSourceFailure.COGNITIVE_STORAGE_CORRUPT,
            rejected.reason
        )
        assertEquals(null, ProductionAndroidRuntimeConfiguration.current())
    }

    @Test
    fun source_exception_is_bounded() {
        val result = ProductionAndroidRuntimeStartupSourceInstall.prepareAndInstall(
            ProductionAndroidRuntimeStartupRequestSourcePort {
                error("private startup source failure")
            }
        )

        val rejected = assertIs<ProductionAndroidRuntimeStartupSourceInstallResult.SourceRejected>(result)
        assertEquals(AndroidProductRuntimeStartupRequestSourceFailure.INTERNAL_FAILURE, rejected.reason)
        assertEquals(null, ProductionAndroidRuntimeConfiguration.current())
    }

    @Test
    fun existing_configuration_blocks_source_before_any_work() {
        val exact = ProductionAndroidRuntimeWiringSources(
            admission = { error("must not execute") },
            preparedInputs = { error("must not execute") }
        )
        assertTrue(ProductionAndroidRuntimeConfiguration.install(exact))
        var sourceCalls = 0

        val result = ProductionAndroidRuntimeStartupSourceInstall.prepareAndInstall(
            ProductionAndroidRuntimeStartupRequestSourcePort {
                sourceCalls += 1
                error("must not source")
            }
        )

        assertIs<ProductionAndroidRuntimeStartupSourceInstallResult.AlreadyConfigured>(result)
        assertEquals(0, sourceCalls)
        assertTrue(ProductionAndroidRuntimeConfiguration.current() === exact)
    }
}
