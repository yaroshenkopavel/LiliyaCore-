package pro.liliya.app

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import org.junit.After
import org.junit.Test
import pro.liliya.android.runtime.AndroidProductRuntimeAdmissionFailure
import pro.liliya.android.runtime.AndroidProductRuntimeAdmissionResult
import pro.liliya.android.runtime.AndroidProductRuntimeStartupProvisioningFailure
import pro.liliya.android.runtime.AndroidProductRuntimeStartupProvisioningResult

class ProductionAndroidRuntimeStartupCompositionInstallContractTest {
    @After
    fun cleanup() {
        ProductionAndroidRuntimeConfiguration.clearForTests()
        ProductionAndroidAppTrustedWiring.clearForTests()
    }

    @Test
    fun composition_result_is_forwarded_without_replacement() {
        val expected = ProductionAndroidRuntimeStartupInstallResult.Rejected(
            AndroidProductRuntimeStartupProvisioningResult.AdmissionRejected(
                AndroidProductRuntimeAdmissionResult.Rejected(
                    AndroidProductRuntimeAdmissionFailure.AUTHORITY_DENIED
                )
            )
        )
        var calls = 0

        val result = ProductionAndroidRuntimeStartupCompositionInstall.prepareAndInstall(
            ProductionAndroidRuntimeStartupCompositionInstallPort {
                calls += 1
                expected
            }
        )

        assertEquals(1, calls)
        assertSame(expected, result)
        assertEquals(null, ProductionAndroidRuntimeConfiguration.current())
    }

    @Test
    fun composition_exception_is_bounded_before_reaching_caller() {
        val result = ProductionAndroidRuntimeStartupCompositionInstall.prepareAndInstall(
            ProductionAndroidRuntimeStartupCompositionInstallPort {
                error("private startup composition failure")
            }
        )

        val rejected = assertIs<ProductionAndroidRuntimeStartupInstallResult.Rejected>(result)
        val provisioning = assertIs<AndroidProductRuntimeStartupProvisioningResult.Rejected>(
            rejected.provisioning
        )
        assertEquals(AndroidProductRuntimeStartupProvisioningFailure.INTERNAL_FAILURE, provisioning.reason)
        assertEquals(null, ProductionAndroidRuntimeConfiguration.current())
    }
}
