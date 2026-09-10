package pro.liliya.android.runtime

import pro.liliya.android.llamacppengine.AndroidLlamaCppCognitiveModelAssembly
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageVerifier
import pro.liliya.core.protectedmodel.LargeProtectedModelSegmentedPayloadLoader
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingCoordinator
import pro.liliya.core.protectedmodel.ProtectedModelDekResolver
import pro.liliya.core.protectedmodel.ProtectedModelSignerResolver

/**
 * Exact composition for protected-model package verification/decryption into the same staging
 * ownership root later used by llama.cpp engine-source loading.
 *
 * Staging Provisioning != Model Selection.
 * Staging Provisioning != DEK Provisioning.
 * Staging Provisioning != Signer Trust Ownership.
 * Staging Provisioning != License/Authority.
 */
class AndroidProductRuntimeProtectedModelStagingProvisioning internal constructor(
    val provisioner: ProductGenerationStagingProvisioner,
    internal val stagingCoordinator: LargeProtectedModelStagingCoordinator
)

object AndroidProductRuntimeProtectedModelStagingProvisioningFactory {
    fun create(
        llamaAssembly: AndroidLlamaCppCognitiveModelAssembly,
        signerResolver: ProtectedModelSignerResolver,
        packageBudgets: LargeProtectedModelPackageBudgets,
        dekResolver: ProtectedModelDekResolver
    ): AndroidProductRuntimeProtectedModelStagingProvisioning =
        create(
            stagingCoordinator = llamaAssembly.stagingCoordinator,
            signerResolver = signerResolver,
            packageBudgets = packageBudgets,
            dekResolver = dekResolver
        )

    internal fun create(
        stagingCoordinator: LargeProtectedModelStagingCoordinator,
        signerResolver: ProtectedModelSignerResolver,
        packageBudgets: LargeProtectedModelPackageBudgets,
        dekResolver: ProtectedModelDekResolver
    ): AndroidProductRuntimeProtectedModelStagingProvisioning {
        val verifier = LargeProtectedModelPackageVerifier(
            signerResolver = signerResolver,
            budgets = packageBudgets
        )
        val loader = LargeProtectedModelSegmentedPayloadLoader(
            packageVerifier = verifier,
            dekResolver = dekResolver
        )
        return AndroidProductRuntimeProtectedModelStagingProvisioning(
            provisioner = ProductGenerationStagingProvisioner(
                loader = loader,
                staging = stagingCoordinator
            ),
            stagingCoordinator = stagingCoordinator
        )
    }
}
