package pro.liliya.android.runtime

import android.content.Context
import java.io.File
import pro.liliya.android.cognitivestorage.AndroidCognitiveStorageFirstRunKeySetup
import pro.liliya.android.cognitivestorage.AndroidCognitiveStorageFirstRunKeySetupRequest
import pro.liliya.android.semanticprovider.AndroidOfflineSemanticArtifactProvisioner
import pro.liliya.core.license.LicenseAuthorityComposition
import pro.liliya.core.license.LicenseAuthorityRequest
import pro.liliya.core.license.LicensePolicyContext
import pro.liliya.core.license.LicensePolicyRequest
import pro.liliya.core.license.LicenseVerificationResult
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelResourceBudgets

/**
 * Explicit startup composition request. Every policy-sensitive value is supplied by an existing
 * owner/caller; this object performs no discovery, selection, minting or retry.
 */
data class AndroidProductRuntimeStartupCompositionRequest(
    val licenseAuthority: LicenseAuthorityComposition,
    val verifiedLicense: LicenseVerificationResult.Verified,
    val licenseRequest: LicensePolicyRequest,
    val licensePolicyContext: LicensePolicyContext,
    val authorityRequest: LicenseAuthorityRequest,
    val keySetup: AndroidCognitiveStorageFirstRunKeySetup,
    val keyRequest: AndroidCognitiveStorageFirstRunKeySetupRequest,
    val semanticContext: Context,
    val semanticProvisioner: AndroidOfflineSemanticArtifactProvisioner,
    val semanticDirectoryName: String = AndroidOfflineSemanticArtifactProvisioner.DEFAULT_DIRECTORY,
    val localModelFile: File,
    val manifestBudgets: LargeProtectedModelResourceBudgets,
    val packageBudgets: LargeProtectedModelPackageBudgets,
    val containerBudgets: ProductProtectedModelLocalPackageBudgets,
    val stagingProvisioner: ProductGenerationStagingProvisioner,
    val preparedInputOwners: AndroidProductRuntimeStartupPreparedInputOwners
)

internal data class AndroidProductRuntimeStartupPortSet(
    val admission: AndroidProductRuntimeStartupAdmissionPort,
    val activeDek: AndroidProductRuntimeStartupActiveDekPort,
    val semantic: AndroidProductRuntimeStartupSemanticPort,
    val model: AndroidProductRuntimeStartupModelPort,
    val preparedInputs: AndroidProductRuntimeStartupPreparedInputsPort
)

object AndroidProductRuntimeStartupPortsComposition {
    fun create(
        request: AndroidProductRuntimeStartupCompositionRequest
    ): AndroidProductRuntimeStartupProvisioningPorts =
        assemble(
            AndroidProductRuntimeStartupPortSet(
                admission = AndroidProductRuntimeStartupAdmissionPort {
                    AndroidProductRuntimeAdmissionGate.admit(
                        composition = request.licenseAuthority,
                        verified = request.verifiedLicense,
                        licenseRequest = request.licenseRequest,
                        policyContext = request.licensePolicyContext,
                        authorityRequest = request.authorityRequest
                    )
                },
                activeDek = AndroidProductRuntimeStartupActiveDekAdapter(
                    setup = request.keySetup,
                    request = request.keyRequest
                ),
                semantic = AndroidProductRuntimeStartupSemanticAdapter(
                    context = request.semanticContext,
                    provisioner = request.semanticProvisioner,
                    directoryName = request.semanticDirectoryName
                ),
                model = AndroidProductRuntimeStartupLocalModelAdapter(
                    file = request.localModelFile,
                    manifestBudgets = request.manifestBudgets,
                    packageBudgets = request.packageBudgets,
                    containerBudgets = request.containerBudgets,
                    stagingProvisioner = request.stagingProvisioner
                ),
                preparedInputs = AndroidProductRuntimeStartupPreparedInputsAdapter(
                    owners = request.preparedInputOwners
                )
            )
        )

    internal fun assemble(
        ports: AndroidProductRuntimeStartupPortSet
    ): AndroidProductRuntimeStartupProvisioningPorts =
        AndroidProductRuntimeStartupProvisioningPorts(
            admission = ports.admission,
            activeDek = ports.activeDek,
            semantic = ports.semantic,
            model = ports.model,
            preparedInputs = ports.preparedInputs
        )
}
