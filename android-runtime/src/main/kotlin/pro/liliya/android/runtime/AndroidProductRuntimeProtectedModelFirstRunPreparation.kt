package pro.liliya.android.runtime

import java.io.File
import pro.liliya.android.llamacppengine.AndroidLlamaCppCognitiveModelAssembly
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageVerificationResult
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageVerifier
import pro.liliya.core.protectedmodel.LargeProtectedModelResourceBudgets
import pro.liliya.core.protectedmodel.ModelDekReference
import pro.liliya.core.protectedmodel.PersistentProtectedModelDekBinding
import pro.liliya.core.protectedmodel.ProtectedModelDekProvisionAndRegisterResult
import pro.liliya.core.protectedmodel.ProtectedModelDekProvisioningCoordinator
import pro.liliya.core.protectedmodel.ProtectedModelDekProvisioningPort
import pro.liliya.core.protectedmodel.ProtectedModelDekProvisioningRequest
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorDescriptor
import pro.liliya.core.protectedmodel.ProtectedModelReference
import pro.liliya.core.protectedmodel.ProtectedModelSignerResolver

data class AndroidProductRuntimeProtectedModelFirstRunPreparationInput(
    val localModelFile: File,
    val manifestBudgets: LargeProtectedModelResourceBudgets,
    val packageBudgets: LargeProtectedModelPackageBudgets,
    val containerBudgets: ProductProtectedModelLocalPackageBudgets,
    val signerResolver: ProtectedModelSignerResolver,
    val dekProvisioning: ProtectedModelDekProvisioningPort,
    val dekAssembly: AndroidProductRuntimeProtectedModelDekAssembly,
    val protectorDescriptor: ProtectedModelKeyProtectorDescriptor,
    val llamaAssembly: AndroidLlamaCppCognitiveModelAssembly
)

enum class AndroidProductRuntimeProtectedModelFirstRunPreparationFailure {
    LOCAL_PACKAGE_REJECTED,
    PACKAGE_VERIFICATION_REJECTED,
    DEK_PROVISIONING_REJECTED,
    DEK_PROVISIONING_FAILED,
    INTERNAL_FAILURE
}

sealed interface AndroidProductRuntimeProtectedModelFirstRunPreparationResult {
    data class Ready(
        val staging: AndroidProductRuntimeProtectedModelStagingProvisioning,
        val model: ProtectedModelReference,
        val dek: ModelDekReference,
        val reusedExistingDek: Boolean
    ) : AndroidProductRuntimeProtectedModelFirstRunPreparationResult

    data class Rejected(
        val reason: AndroidProductRuntimeProtectedModelFirstRunPreparationFailure
    ) : AndroidProductRuntimeProtectedModelFirstRunPreparationResult
}

internal data class AndroidProductRuntimeVerifiedProtectedModelReferences(
    val model: ProtectedModelReference,
    val dek: ModelDekReference
)

internal sealed interface AndroidProductRuntimeProtectedModelVerificationResult {
    data class Ready(
        val references: AndroidProductRuntimeVerifiedProtectedModelReferences
    ) : AndroidProductRuntimeProtectedModelVerificationResult

    data class Rejected(
        val reason: AndroidProductRuntimeProtectedModelFirstRunPreparationFailure
    ) : AndroidProductRuntimeProtectedModelVerificationResult
}

internal fun interface AndroidProductRuntimeProtectedModelVerifyPort {
    fun verify(): AndroidProductRuntimeProtectedModelVerificationResult
}

internal fun interface AndroidProductRuntimeProtectedModelDekInspectPort {
    fun inspect(
        model: ProtectedModelReference,
        dek: ModelDekReference
    ): PersistentProtectedModelDekBinding?
}

internal fun interface AndroidProductRuntimeProtectedModelDekProvisionPort {
    fun provision(
        model: ProtectedModelReference,
        dek: ModelDekReference
    ): ProtectedModelDekProvisionAndRegisterResult
}

internal fun interface AndroidProductRuntimeProtectedModelStagingBuildPort<T> {
    fun build(): T
}

internal sealed interface AndroidProductRuntimeProtectedModelPreparationCoordinationResult<out T> {
    data class Ready<T>(
        val staging: T,
        val references: AndroidProductRuntimeVerifiedProtectedModelReferences,
        val reusedExistingDek: Boolean
    ) : AndroidProductRuntimeProtectedModelPreparationCoordinationResult<T>

    data class Rejected(
        val reason: AndroidProductRuntimeProtectedModelFirstRunPreparationFailure
    ) : AndroidProductRuntimeProtectedModelPreparationCoordinationResult<Nothing>
}

/**
 * Performs the security-sensitive first-run ordering for one protected model:
 *
 * parse -> verify signature -> exact model/DEK -> reuse/provision exact DEK -> staging wiring.
 *
 * DEK provisioning is never invoked from an unverified package manifest.
 */
object AndroidProductRuntimeProtectedModelFirstRunPreparation {
    fun prepare(
        input: AndroidProductRuntimeProtectedModelFirstRunPreparationInput
    ): AndroidProductRuntimeProtectedModelFirstRunPreparationResult {
        val verifier = LargeProtectedModelPackageVerifier(
            signerResolver = input.signerResolver,
            budgets = input.packageBudgets
        )

        val coordinated = coordinate(
            verifyPort = AndroidProductRuntimeProtectedModelVerifyPort {
                val opened = when (
                    val result = ProductProtectedModelLocalPackage.open(
                        file = input.localModelFile,
                        manifestBudgets = input.manifestBudgets,
                        packageBudgets = input.packageBudgets,
                        containerBudgets = input.containerBudgets
                    )
                ) {
                    is ProductProtectedModelLocalPackageOpenResult.Opened -> result
                    is ProductProtectedModelLocalPackageOpenResult.Rejected ->
                        return@AndroidProductRuntimeProtectedModelVerifyPort
                            rejectedVerification(
                                AndroidProductRuntimeProtectedModelFirstRunPreparationFailure
                                    .LOCAL_PACKAGE_REJECTED
                            )
                }

                when (val verified = verifier.verify(opened.envelope)) {
                    is LargeProtectedModelPackageVerificationResult.Verified -> {
                        val payload = verified.value.manifest.payload
                        AndroidProductRuntimeProtectedModelVerificationResult.Ready(
                            AndroidProductRuntimeVerifiedProtectedModelReferences(
                                model = payload.model,
                                dek = payload.modelDek
                            )
                        )
                    }

                    is LargeProtectedModelPackageVerificationResult.Rejected ->
                        rejectedVerification(
                            AndroidProductRuntimeProtectedModelFirstRunPreparationFailure
                                .PACKAGE_VERIFICATION_REJECTED
                        )

                    is LargeProtectedModelPackageVerificationResult.Failed ->
                        rejectedVerification(
                            AndroidProductRuntimeProtectedModelFirstRunPreparationFailure
                                .INTERNAL_FAILURE
                        )
                }
            },
            inspectPort = AndroidProductRuntimeProtectedModelDekInspectPort { model, dek ->
                input.dekAssembly.store.inspect(model, dek)
            },
            provisionPort = AndroidProductRuntimeProtectedModelDekProvisionPort { model, dek ->
                ProtectedModelDekProvisioningCoordinator.provisionAndRegister(
                    provider = input.dekProvisioning,
                    store = input.dekAssembly.store,
                    request = ProtectedModelDekProvisioningRequest(
                        model = model,
                        dek = dek
                    ),
                    protectorDescriptor = input.protectorDescriptor
                )
            },
            stagingPort = AndroidProductRuntimeProtectedModelStagingBuildPort {
                AndroidProductRuntimeProtectedModelStagingProvisioningFactory.create(
                    llamaAssembly = input.llamaAssembly,
                    signerResolver = input.signerResolver,
                    packageBudgets = input.packageBudgets,
                    dekResolver = input.dekAssembly.store
                )
            }
        )

        return when (coordinated) {
            is AndroidProductRuntimeProtectedModelPreparationCoordinationResult.Ready ->
                AndroidProductRuntimeProtectedModelFirstRunPreparationResult.Ready(
                    staging = coordinated.staging,
                    model = coordinated.references.model,
                    dek = coordinated.references.dek,
                    reusedExistingDek = coordinated.reusedExistingDek
                )

            is AndroidProductRuntimeProtectedModelPreparationCoordinationResult.Rejected ->
                AndroidProductRuntimeProtectedModelFirstRunPreparationResult.Rejected(
                    coordinated.reason
                )
        }
    }

    internal fun <T> coordinate(
        verifyPort: AndroidProductRuntimeProtectedModelVerifyPort,
        inspectPort: AndroidProductRuntimeProtectedModelDekInspectPort,
        provisionPort: AndroidProductRuntimeProtectedModelDekProvisionPort,
        stagingPort: AndroidProductRuntimeProtectedModelStagingBuildPort<T>
    ): AndroidProductRuntimeProtectedModelPreparationCoordinationResult<T> {
        val verified = when (val result = try {
            verifyPort.verify()
        } catch (_: Exception) {
            return rejectedCoordination(
                AndroidProductRuntimeProtectedModelFirstRunPreparationFailure
                    .INTERNAL_FAILURE
            )
        }) {
            is AndroidProductRuntimeProtectedModelVerificationResult.Ready ->
                result.references
            is AndroidProductRuntimeProtectedModelVerificationResult.Rejected ->
                return rejectedCoordination(result.reason)
        }

        val existing = try {
            inspectPort.inspect(verified.model, verified.dek)
        } catch (_: Exception) {
            return rejectedCoordination(
                AndroidProductRuntimeProtectedModelFirstRunPreparationFailure
                    .INTERNAL_FAILURE
            )
        }

        val reused = if (existing != null) {
            if (existing.model != verified.model || existing.dek != verified.dek) {
                return rejectedCoordination(
                    AndroidProductRuntimeProtectedModelFirstRunPreparationFailure
                        .INTERNAL_FAILURE
                )
            }
            true
        } else {
            when (val provisioned = try {
                provisionPort.provision(verified.model, verified.dek)
            } catch (_: Exception) {
                return rejectedCoordination(
                    AndroidProductRuntimeProtectedModelFirstRunPreparationFailure
                        .DEK_PROVISIONING_FAILED
                )
            }) {
                is ProtectedModelDekProvisionAndRegisterResult.Registered -> {
                    if (
                        provisioned.binding.model != verified.model ||
                        provisioned.binding.dek != verified.dek
                    ) {
                        return rejectedCoordination(
                            AndroidProductRuntimeProtectedModelFirstRunPreparationFailure
                                .DEK_PROVISIONING_FAILED
                        )
                    }
                }

                is ProtectedModelDekProvisionAndRegisterResult.ProvisioningRejected,
                is ProtectedModelDekProvisionAndRegisterResult.StoreRejected ->
                    return rejectedCoordination(
                        AndroidProductRuntimeProtectedModelFirstRunPreparationFailure
                            .DEK_PROVISIONING_REJECTED
                    )

                is ProtectedModelDekProvisionAndRegisterResult.Failed ->
                    return rejectedCoordination(
                        AndroidProductRuntimeProtectedModelFirstRunPreparationFailure
                            .DEK_PROVISIONING_FAILED
                    )
            }
            false
        }

        val staging = try {
            stagingPort.build()
        } catch (_: Exception) {
            return rejectedCoordination(
                AndroidProductRuntimeProtectedModelFirstRunPreparationFailure
                    .INTERNAL_FAILURE
            )
        }

        return AndroidProductRuntimeProtectedModelPreparationCoordinationResult.Ready(
            staging = staging,
            references = verified,
            reusedExistingDek = reused
        )
    }

    private fun rejectedVerification(
        reason: AndroidProductRuntimeProtectedModelFirstRunPreparationFailure
    ): AndroidProductRuntimeProtectedModelVerificationResult.Rejected =
        AndroidProductRuntimeProtectedModelVerificationResult.Rejected(reason)

    private fun rejectedCoordination(
        reason: AndroidProductRuntimeProtectedModelFirstRunPreparationFailure
    ): AndroidProductRuntimeProtectedModelPreparationCoordinationResult.Rejected =
        AndroidProductRuntimeProtectedModelPreparationCoordinationResult.Rejected(reason)
}
