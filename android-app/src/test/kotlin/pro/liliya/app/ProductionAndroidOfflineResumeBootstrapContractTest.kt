package pro.liliya.app

import android.content.Context
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.After
import org.junit.Test
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunProductInput
import sun.misc.Unsafe

class ProductionAndroidOfflineResumeBootstrapContractTest {
    @After
    fun cleanup() {
        ProductionAndroidOfflineResumePolicyOwner.clearForTests()
    }

    @Test
    fun process_local_policy_is_required_before_any_reconstructed_input_can_exist() {
        val material = allocateWithoutConstructor<ProductionAndroidOfflineResumeMaterial>()

        val result = ProductionAndroidOfflineResumeBootstrap.prepareAndInstall(
            materialPort = ProductionAndroidOfflineResumeMaterialPort {
                ProductionAndroidOfflineResumeMaterialLoadResult.Ready(material)
            },
            policyPort = ProductionAndroidOfflineResumePolicyPort { null },
            installPort = ProductionAndroidOfflineResumeInstallPort {
                error("install must not run without process-local policy")
            }
        )

        assertEquals(
            ProductionAndroidOfflineResumeBootstrapFailure.POLICY_REQUIRED,
            assertIs<ProductionAndroidOfflineResumeBootstrapResult.Rejected>(result).reason
        )
    }

    @Test
    fun policy_returning_null_fails_closed_before_install() {
        val material = allocateWithoutConstructor<ProductionAndroidOfflineResumeMaterial>()

        val result = ProductionAndroidOfflineResumeBootstrap.prepareAndInstall(
            materialPort = ProductionAndroidOfflineResumeMaterialPort {
                ProductionAndroidOfflineResumeMaterialLoadResult.Ready(material)
            },
            policyPort = ProductionAndroidOfflineResumePolicyPort {
                ProductionAndroidOfflineResumeProductInputFactory { null }
            },
            installPort = ProductionAndroidOfflineResumeInstallPort {
                error("install must not run after null reconstruction input")
            }
        )

        assertEquals(
            ProductionAndroidOfflineResumeBootstrapFailure.INPUT_REJECTED,
            assertIs<ProductionAndroidOfflineResumeBootstrapResult.Rejected>(result).reason
        )
    }

    @Test
    fun material_rejection_stops_before_policy_lookup() {
        val result = ProductionAndroidOfflineResumeBootstrap.prepareAndInstall(
            materialPort = ProductionAndroidOfflineResumeMaterialPort {
                ProductionAndroidOfflineResumeMaterialLoadResult.Rejected(
                    ProductionAndroidOfflineResumeMaterialFailure.LICENSE_REJECTED
                )
            },
            policyPort = ProductionAndroidOfflineResumePolicyPort {
                error("policy must not be consulted after material rejection")
            },
            installPort = ProductionAndroidOfflineResumeInstallPort {
                error("install must not run after material rejection")
            }
        )

        val rejected = assertIs<ProductionAndroidOfflineResumeBootstrapResult.Rejected>(result)
        assertEquals(
            ProductionAndroidOfflineResumeBootstrapFailure.MATERIAL_REJECTED,
            rejected.reason
        )
        assertEquals(
            ProductionAndroidOfflineResumeMaterialFailure.LICENSE_REJECTED,
            rejected.materialFailure
        )
    }

    @Test
    fun canonical_install_rejection_stays_rejected() {
        val material = allocateWithoutConstructor<ProductionAndroidOfflineResumeMaterial>()
        val input = allocateWithoutConstructor<AndroidProductRuntimeFirstRunProductInput>()

        val result = ProductionAndroidOfflineResumeBootstrap.prepareAndInstall(
            materialPort = ProductionAndroidOfflineResumeMaterialPort {
                ProductionAndroidOfflineResumeMaterialLoadResult.Ready(material)
            },
            policyPort = ProductionAndroidOfflineResumePolicyPort {
                ProductionAndroidOfflineResumeProductInputFactory { input }
            },
            installPort = ProductionAndroidOfflineResumeInstallPort {
                ProductionAndroidFirstRunProductInstallResult.TrustVerificationRejected
            }
        )

        assertEquals(
            ProductionAndroidOfflineResumeBootstrapFailure.INSTALL_REJECTED,
            assertIs<ProductionAndroidOfflineResumeBootstrapResult.Rejected>(result).reason
        )
    }

    private inline fun <reified T> allocateWithoutConstructor(): T {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (field.get(null) as Unsafe).allocateInstance(T::class.java) as T
    }
}
