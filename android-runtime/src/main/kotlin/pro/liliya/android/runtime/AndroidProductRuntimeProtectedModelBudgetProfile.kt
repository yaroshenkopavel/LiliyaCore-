package pro.liliya.android.runtime

import pro.liliya.core.protectedmodel.LargeProtectedModelPackageBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelResourceBudgets

data class AndroidProductRuntimeProtectedModelBudgetInput(
    val maxTotalPlaintextBytes: Long,
    val maxTotalCiphertextBodyBytes: Long,
    val maxTotalProtectedPayloadBytes: Long,
    val maxSegmentCount: Int,
    val minNonFinalSegmentPlaintextBytes: Long,
    val maxSegmentPlaintextBytes: Long,
    val maxSegmentCiphertextBodyBytes: Long,
    val maxStructuralIdentifierChars: Int,
    val maxCanonicalManifestBytes: Long,
    val maxModelProfileIdChars: Int,
    val maxSignerIdChars: Int,
    val maxCanonicalSignedManifestBytes: Long,
    val maxContainerBytes: Long,
    val maxSignatureBytes: Int
)

data class AndroidProductRuntimeProtectedModelBudgets(
    val manifest: LargeProtectedModelResourceBudgets,
    val packageEnvelope: LargeProtectedModelPackageBudgets,
    val container: ProductProtectedModelLocalPackageBudgets
)

sealed interface AndroidProductRuntimeProtectedModelBudgetResult {
    data class Ready(
        val budgets: AndroidProductRuntimeProtectedModelBudgets
    ) : AndroidProductRuntimeProtectedModelBudgetResult

    data object Rejected : AndroidProductRuntimeProtectedModelBudgetResult
}

/**
 * Maps one explicit product operating budget profile into existing protected-model bounds.
 *
 * Budget Profile != Model Discovery.
 * Budget Profile != Device Auto-Tuning.
 * Budget Profile != Download Policy.
 * Budget Profile != Runtime Memory Measurement.
 */
object AndroidProductRuntimeProtectedModelBudgetProfile {
    fun create(
        input: AndroidProductRuntimeProtectedModelBudgetInput
    ): AndroidProductRuntimeProtectedModelBudgetResult =
        try {
            AndroidProductRuntimeProtectedModelBudgetResult.Ready(
                AndroidProductRuntimeProtectedModelBudgets(
                    manifest = LargeProtectedModelResourceBudgets(
                        maxTotalPlaintextBytes = input.maxTotalPlaintextBytes,
                        maxTotalCiphertextBodyBytes = input.maxTotalCiphertextBodyBytes,
                        maxTotalProtectedPayloadBytes = input.maxTotalProtectedPayloadBytes,
                        maxSegmentCount = input.maxSegmentCount,
                        minNonFinalSegmentPlaintextBytes =
                            input.minNonFinalSegmentPlaintextBytes,
                        maxSegmentPlaintextBytes = input.maxSegmentPlaintextBytes,
                        maxSegmentCiphertextBodyBytes =
                            input.maxSegmentCiphertextBodyBytes,
                        maxStructuralIdentifierChars =
                            input.maxStructuralIdentifierChars,
                        maxCanonicalManifestBytes = input.maxCanonicalManifestBytes
                    ),
                    packageEnvelope = LargeProtectedModelPackageBudgets(
                        maxModelProfileIdChars = input.maxModelProfileIdChars,
                        maxSignerIdChars = input.maxSignerIdChars,
                        maxCanonicalSignedManifestBytes =
                            input.maxCanonicalSignedManifestBytes
                    ),
                    container = ProductProtectedModelLocalPackageBudgets(
                        maxContainerBytes = input.maxContainerBytes,
                        maxSignatureBytes = input.maxSignatureBytes
                    )
                )
            )
        } catch (_: IllegalArgumentException) {
            AndroidProductRuntimeProtectedModelBudgetResult.Rejected
        }
}
