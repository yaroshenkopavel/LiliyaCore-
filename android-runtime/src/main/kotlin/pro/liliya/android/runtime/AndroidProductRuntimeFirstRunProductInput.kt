package pro.liliya.android.runtime

import android.content.Context
import java.io.File
import pro.liliya.core.license.LicenseSignedEnvelope

data class AndroidProductRuntimeFirstRunProductInput(
    val context: Context,
    val observability: AndroidProductRuntimeObservability,
    val supportedLicenseSchemaVersion: Long,
    val licenseTrustKeys: List<AndroidProductRuntimeLicenseTrustKey>,
    val licenseEnvelope: LicenseSignedEnvelope,
    val authorityPlan: AndroidProductRuntimeStartupAuthorityPlan,
    val admission: AndroidProductRuntimeStartupAdmissionInput,
    val keyChoice: AndroidProductRuntimeFirstRunKeyChoice,
    val localModelFile: File,
    val protectedModelBudgets: AndroidProductRuntimeProtectedModelBudgetInput,
    val staging: AndroidProductRuntimeProtectedModelStagingProvisioning,
    val preparedInputOwners: AndroidProductRuntimeStartupPreparedInputOwnerTemplate,
    val cognitiveStorageDirectoryName: String? = null,
    val semanticDirectoryName: String =
        pro.liliya.android.semanticprovider.AndroidOfflineSemanticArtifactProvisioner
            .DEFAULT_DIRECTORY
)

enum class AndroidProductRuntimeFirstRunProductInputFailure {
    TRUST_INPUT_REJECTED,
    ADMISSION_INPUT_REJECTED,
    COGNITIVE_KEY_CHOICE_REJECTED,
    PROTECTED_MODEL_BUDGET_REJECTED,
    INTERNAL_FAILURE
}

sealed interface AndroidProductRuntimeFirstRunProductInputResult {
    data class Ready(
        val input: AndroidProductRuntimeStartupInputAssemblyInput
    ) : AndroidProductRuntimeFirstRunProductInputResult

    data class Rejected(
        val reason: AndroidProductRuntimeFirstRunProductInputFailure
    ) : AndroidProductRuntimeFirstRunProductInputResult
}

internal data class AndroidProductRuntimeResolvedFirstRunProductInputs(
    val trust: AndroidProductRuntimeStartupTrustInput,
    val admission: AndroidProductRuntimeStartupAdmissionContracts,
    val keyRequest:
        pro.liliya.android.cognitivestorage.AndroidCognitiveStorageFirstRunKeySetupRequest,
    val budgets: AndroidProductRuntimeProtectedModelBudgets
)

internal sealed interface AndroidProductRuntimeResolvedFirstRunProductInputResult {
    data class Ready(
        val inputs: AndroidProductRuntimeResolvedFirstRunProductInputs
    ) : AndroidProductRuntimeResolvedFirstRunProductInputResult

    data class Rejected(
        val reason: AndroidProductRuntimeFirstRunProductInputFailure
    ) : AndroidProductRuntimeResolvedFirstRunProductInputResult
}

/**
 * Resolves all already-frozen technical first-run factories into one canonical startup input.
 *
 * Product Input Factory != License Issuer.
 * Product Input Factory != Authority Policy.
 * Product Input Factory != Learning Governance.
 * Product Input Factory != Model Selection.
 * Product Input Factory != DEK Provisioning.
 *
 * Every policy-sensitive input is supplied explicitly by an outer product owner.
 */
object AndroidProductRuntimeFirstRunProductInputFactory {
    fun create(
        input: AndroidProductRuntimeFirstRunProductInput
    ): AndroidProductRuntimeFirstRunProductInputResult {
        val resolved = resolve(
            trust = AndroidProductRuntimeStartupTrustInputFactory.create(
                observability = input.observability,
                supportedLicenseSchemaVersion = input.supportedLicenseSchemaVersion,
                keys = input.licenseTrustKeys,
                licenseEnvelope = input.licenseEnvelope
            ),
            admission = AndroidProductRuntimeStartupAdmissionInputFactory.create(
                input.admission
            ),
            keyChoice = AndroidProductRuntimeFirstRunKeyChoiceFactory.create(
                input.keyChoice
            ),
            budgets = AndroidProductRuntimeProtectedModelBudgetProfile.create(
                input.protectedModelBudgets
            )
        )

        val exact = when (resolved) {
            is AndroidProductRuntimeResolvedFirstRunProductInputResult.Ready ->
                resolved.inputs
            is AndroidProductRuntimeResolvedFirstRunProductInputResult.Rejected ->
                return AndroidProductRuntimeFirstRunProductInputResult.Rejected(
                    resolved.reason
                )
        }

        return try {
            AndroidProductRuntimeFirstRunProductInputResult.Ready(
                AndroidProductRuntimeStartupInputAssemblyInput(
                    context = input.context.applicationContext,
                    trust = exact.trust,
                    authorityPlan = input.authorityPlan,
                    licenseRequest = exact.admission.licenseRequest,
                    licensePolicyContext = exact.admission.policyContext,
                    authorityRequest = exact.admission.authorityRequest,
                    keyRequest = exact.keyRequest,
                    localModelFile = input.localModelFile,
                    manifestBudgets = exact.budgets.manifest,
                    packageBudgets = exact.budgets.packageEnvelope,
                    containerBudgets = exact.budgets.container,
                    stagingProvisioner = input.staging.provisioner,
                    preparedInputOwners = input.preparedInputOwners,
                    cognitiveStorageDirectoryName =
                        input.cognitiveStorageDirectoryName,
                    semanticDirectoryName = input.semanticDirectoryName
                )
            )
        } catch (_: Exception) {
            AndroidProductRuntimeFirstRunProductInputResult.Rejected(
                AndroidProductRuntimeFirstRunProductInputFailure.INTERNAL_FAILURE
            )
        }
    }

    internal fun resolve(
        trust: AndroidProductRuntimeStartupTrustInputFactoryResult,
        admission: AndroidProductRuntimeStartupAdmissionInputResult,
        keyChoice: AndroidProductRuntimeFirstRunKeyChoiceResult,
        budgets: AndroidProductRuntimeProtectedModelBudgetResult
    ): AndroidProductRuntimeResolvedFirstRunProductInputResult {
        val exactTrust = when (trust) {
            is AndroidProductRuntimeStartupTrustInputFactoryResult.Ready ->
                trust.input
            AndroidProductRuntimeStartupTrustInputFactoryResult.Rejected ->
                return rejected(
                    AndroidProductRuntimeFirstRunProductInputFailure
                        .TRUST_INPUT_REJECTED
                )
        }

        val exactAdmission = when (admission) {
            is AndroidProductRuntimeStartupAdmissionInputResult.Ready ->
                admission.contracts
            AndroidProductRuntimeStartupAdmissionInputResult.Rejected ->
                return rejected(
                    AndroidProductRuntimeFirstRunProductInputFailure
                        .ADMISSION_INPUT_REJECTED
                )
        }

        val exactKeyRequest = when (keyChoice) {
            is AndroidProductRuntimeFirstRunKeyChoiceResult.Ready ->
                keyChoice.request
            AndroidProductRuntimeFirstRunKeyChoiceResult.Rejected ->
                return rejected(
                    AndroidProductRuntimeFirstRunProductInputFailure
                        .COGNITIVE_KEY_CHOICE_REJECTED
                )
        }

        val exactBudgets = when (budgets) {
            is AndroidProductRuntimeProtectedModelBudgetResult.Ready ->
                budgets.budgets
            AndroidProductRuntimeProtectedModelBudgetResult.Rejected ->
                return rejected(
                    AndroidProductRuntimeFirstRunProductInputFailure
                        .PROTECTED_MODEL_BUDGET_REJECTED
                )
        }

        return AndroidProductRuntimeResolvedFirstRunProductInputResult.Ready(
            AndroidProductRuntimeResolvedFirstRunProductInputs(
                trust = exactTrust,
                admission = exactAdmission,
                keyRequest = exactKeyRequest,
                budgets = exactBudgets
            )
        )
    }

    private fun rejected(
        reason: AndroidProductRuntimeFirstRunProductInputFailure
    ): AndroidProductRuntimeResolvedFirstRunProductInputResult.Rejected =
        AndroidProductRuntimeResolvedFirstRunProductInputResult.Rejected(reason)
}
