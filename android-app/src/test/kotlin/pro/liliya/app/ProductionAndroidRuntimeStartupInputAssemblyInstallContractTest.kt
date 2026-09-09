package pro.liliya.app

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.After
import org.junit.Test
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAuthorityAssemblyFailure
import pro.liliya.android.runtime.AndroidProductRuntimeStartupInputAssemblyResult

class ProductionAndroidRuntimeStartupInputAssemblyInstallContractTest {
    @After
    fun cleanup() {
        ProductionAndroidRuntimeStartupInputAssemblyInstall.clearForTests()
        ProductionAndroidRuntimeStartupInputConfiguration.clearForTests()
    }

    @Test
    fun authority_rejection_is_preserved_without_installing_startup_input() {
        val result = ProductionAndroidRuntimeStartupInputAssemblyInstall.prepareAndInstall(
            ProductionAndroidRuntimeStartupInputAssemblyPort {
                AndroidProductRuntimeStartupInputAssemblyResult.AuthorityRejected(
                    AndroidProductRuntimeStartupAuthorityAssemblyFailure.DIRECT_GRANT_REJECTED
                )
            }
        )

        val rejected = assertIs<ProductionAndroidRuntimeStartupInputAssemblyInstallResult.AuthorityRejected>(
            result
        )
        assertEquals(
            AndroidProductRuntimeStartupAuthorityAssemblyFailure.DIRECT_GRANT_REJECTED,
            rejected.reason
        )
        assertEquals(null, ProductionAndroidRuntimeStartupInputConfiguration.current())
    }

    @Test
    fun assembly_exception_is_bounded() {
        val result = ProductionAndroidRuntimeStartupInputAssemblyInstall.prepareAndInstall(
            ProductionAndroidRuntimeStartupInputAssemblyPort {
                error("private assembly failure")
            }
        )

        assertIs<ProductionAndroidRuntimeStartupInputAssemblyInstallResult.Failed>(result)
        assertEquals(null, ProductionAndroidRuntimeStartupInputConfiguration.current())
    }
}
