package pro.liliya.android.runtime

import android.content.Context
import java.io.File
import pro.liliya.android.cognitivestorage.AndroidCognitiveStorageFirstRunKeySetupRequest
import pro.liliya.android.semanticprovider.AndroidOfflineSemanticArtifactProvisioner
import pro.liliya.core.license.LicenseAuthorityRequest
import pro.liliya.core.license.LicensePolicyContext
import pro.liliya.core.license.LicensePolicyRequest
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelResourceBudgets

/**
 * Explicit product inputs that remain outside Trust/Foundation and Authority assembly.
 *
 * Startup Input Assembly != DEK Selection.
 * Startup Input Assembly != Model Discovery/Download.
 * Startup Input Assembly != Learning Governance.
 */
data class AndroidProductRuntimeStartupInputAssemblyInput(
    val context: Context,
    val trust: AndroidProductRuntimeStartupTrustInput,
    val authorityPlan: AndroidProductRuntimeStartupAuthorityPlan,
    val licenseRequest: LicensePolicyRequest,
    val licensePolicyContext: LicensePolicyContext,
    val authorityRequest: LicenseAuthorityRequest,
    val keyRequest: AndroidCognitiveStorageFirstRunKeySetupRequest,
    val localModelFile: File,
    val manifestBudgets: LargeProtectedModelResourceBudgets,
    val packageBudgets: LargeProtectedModelPackageBudgets,
    val containerBudgets: ProductProtectedModelLocalPackageBudgets,
    val stagingProvisioner: ProductGenerationStagingProvisioner,
    val preparedInputOwners: AndroidProductRuntimeStartupPreparedInputOwnerTemplate,
    val cognitiveStorageDirectoryName: String? = null,
    val semanticDirectoryName: String = AndroidOfflineSemanticArtifactProvisioner.DEFAULT_DIRECTORY
)

data class AndroidProductRuntimeStartupInputOwnership(
    val sourceInput: AndroidProductRuntimeStartupRequestSourceInput,
    val trust: AndroidProductRuntimeStartupTrustOwnership,
    val authority: AndroidProductRuntimeStartupAuthorityOwnership
)

sealed interface AndroidProductRuntimeStartupInputAssemblyResult {
    data class Ready(
        val ownership: AndroidProductRuntimeStartupInputOwnership
    ) : AndroidProductRuntimeStartupInputAssemblyResult

    data class TrustVerificationRejected(
        val reason: pro.liliya.core.license.LicenseVerificationRejection
    ) : AndroidProductRuntimeStartupInputAssemblyResult

    data class AuthorityRejected(
        val reason: AndroidProductRuntimeStartupAuthorityAssemblyFailure
    ) : AndroidProductRuntimeStartupInputAssemblyResult

    data object Failed : AndroidProductRuntimeStartupInputAssemblyResult
}

internal fun interface AndroidProductRuntimeStartupInputBuildPort {
    fun build(
        trust: AndroidProductRuntimeStartupTrustOwnership,
        authority: AndroidProductRuntimeStartupAuthorityOwnership
    ): AndroidProductRuntimeStartupRequestSourceInput
}

/**
 * Connects verified startup trust and explicit authority ownership into one canonical source input.
 *
 * Input Assembly != License Issuer.
 * Input Assembly != Capability Grant Authority.
 * Input Assembly != Provisioning Authority.
 */
object AndroidProductRuntimeStartupInputAssembly {
    fun create(
        input: AndroidProductRuntimeStartupInputAssemblyInput
    ): AndroidProductRuntimeStartupInputAssemblyResult {
        val trustResult = AndroidProductRuntimeStartupTrustAssembly.create(input.trust)
        return resolve(
            trustResult = trustResult,
            authorityPlan = input.authorityPlan,
            buildPort = AndroidProductRuntimeStartupInputBuildPort { trust, _ ->
                AndroidProductRuntimeStartupRequestSourceInput(
                    context = input.context.applicationContext,
                    foundation = trust.foundation,
                    capabilityAuthority = trust.capabilityAuthority,
                    verifiedLicense = trust.verifiedLicense,
                    licenseRequest = input.licenseRequest,
                    licensePolicyContext = input.licensePolicyContext,
                    authorityRequest = input.authorityRequest,
                    keyRequest = input.keyRequest,
                    localModelFile = input.localModelFile,
                    manifestBudgets = input.manifestBudgets,
                    packageBudgets = input.packageBudgets,
                    containerBudgets = input.containerBudgets,
                    stagingProvisioner = input.stagingProvisioner,
                    preparedInputOwners = input.preparedInputOwners,
                    cognitiveStorageDirectoryName = input.cognitiveStorageDirectoryName,
                    semanticDirectoryName = input.semanticDirectoryName
                )
            }
        )
    }

    internal fun resolve(
        trustResult: AndroidProductRuntimeStartupTrustAssemblyResult,
        authorityPlan: AndroidProductRuntimeStartupAuthorityPlan,
        buildPort: AndroidProductRuntimeStartupInputBuildPort
    ): AndroidProductRuntimeStartupInputAssemblyResult {
        val trust = when (trustResult) {
            is AndroidProductRuntimeStartupTrustAssemblyResult.Ready -> trustResult.ownership
            is AndroidProductRuntimeStartupTrustAssemblyResult.VerificationRejected ->
                return AndroidProductRuntimeStartupInputAssemblyResult.TrustVerificationRejected(
                    trustResult.reason
                )
            AndroidProductRuntimeStartupTrustAssemblyResult.Failed ->
                return AndroidProductRuntimeStartupInputAssemblyResult.Failed
        }

        val authority = when (
            val installed = AndroidProductRuntimeStartupAuthorityGrantAssembly.install(
                authority = trust.capabilityAuthority,
                plan = authorityPlan
            )
        ) {
            is AndroidProductRuntimeStartupAuthorityAssemblyResult.Ready -> installed.ownership
            is AndroidProductRuntimeStartupAuthorityAssemblyResult.Rejected ->
                return AndroidProductRuntimeStartupInputAssemblyResult.AuthorityRejected(
                    installed.reason
                )
        }

        val sourceInput = try {
            buildPort.build(trust, authority)
        } catch (_: Exception) {
            rollbackAuthority(authority)
            return AndroidProductRuntimeStartupInputAssemblyResult.Failed
        }

        return AndroidProductRuntimeStartupInputAssemblyResult.Ready(
            AndroidProductRuntimeStartupInputOwnership(
                sourceInput = sourceInput,
                trust = trust,
                authority = authority
            )
        )
    }

    private fun rollbackAuthority(
        ownership: AndroidProductRuntimeStartupAuthorityOwnership
    ) {
        ownership.directGrants.asReversed().forEach { grant ->
            runCatching { grant.revoke() }
        }
        ownership.capabilities.asReversed().forEach { capability ->
            runCatching { capability.unregister() }
        }
    }
}
