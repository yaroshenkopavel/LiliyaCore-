package pro.liliya.app

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunProductInputFailure
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunProductInputResult
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAuthorityAssemblyFailure

class ProductionAndroidFirstRunProductInstallContractTest {
    @Test
    fun rejected_product_input_is_preserved_and_install_is_not_called() {
        var installCalled = false

        val result = ProductionAndroidFirstRunProductInstall.prepareAndInstall(
            resolvePort = ProductionAndroidFirstRunProductResolvePort {
                AndroidProductRuntimeFirstRunProductInputResult.Rejected(
                    AndroidProductRuntimeFirstRunProductInputFailure
                        .PROTECTED_MODEL_BUDGET_REJECTED
                )
            },
            installPort = ProductionAndroidFirstRunStartupInstallPort {
                installCalled = true
                error("must not install rejected product input")
            }
        )

        val rejected =
            assertIs<ProductionAndroidFirstRunProductInstallResult.ProductInputRejected>(result)
        assertEquals(
            AndroidProductRuntimeFirstRunProductInputFailure
                .PROTECTED_MODEL_BUDGET_REJECTED,
            rejected.reason
        )
        assertEquals(false, installCalled)
    }

    @Test
    fun install_result_already_configured_is_preserved() {
        val result = ProductionAndroidFirstRunProductInstall.mapInstallResult(
            ProductionAndroidRuntimeStartupInputAssemblyInstallResult.AlreadyConfigured
        )

        assertIs<ProductionAndroidFirstRunProductInstallResult.AlreadyConfigured>(result)
    }

    @Test
    fun trust_verification_rejection_is_preserved() {
        val result = ProductionAndroidFirstRunProductInstall.mapInstallResult(
            ProductionAndroidRuntimeStartupInputAssemblyInstallResult
                .TrustVerificationRejected
        )

        assertIs<ProductionAndroidFirstRunProductInstallResult.TrustVerificationRejected>(result)
    }

    @Test
    fun authority_rejection_is_preserved() {
        val result = ProductionAndroidFirstRunProductInstall.mapInstallResult(
            ProductionAndroidRuntimeStartupInputAssemblyInstallResult.AuthorityRejected(
                AndroidProductRuntimeStartupAuthorityAssemblyFailure
                    .DIRECT_GRANT_REJECTED
            )
        )

        val rejected =
            assertIs<ProductionAndroidFirstRunProductInstallResult.AuthorityRejected>(result)
        assertEquals(
            AndroidProductRuntimeStartupAuthorityAssemblyFailure.DIRECT_GRANT_REJECTED,
            rejected.reason
        )
    }

    @Test
    fun failed_install_is_preserved() {
        val result = ProductionAndroidFirstRunProductInstall.mapInstallResult(
            ProductionAndroidRuntimeStartupInputAssemblyInstallResult.Failed
        )

        assertIs<ProductionAndroidFirstRunProductInstallResult.Failed>(result)
    }
}
