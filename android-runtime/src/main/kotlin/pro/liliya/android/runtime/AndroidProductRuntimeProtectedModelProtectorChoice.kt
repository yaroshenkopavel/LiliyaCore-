package pro.liliya.android.runtime

import pro.liliya.core.protectedmodel.ProtectedModelKeyProtector
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorCreationRequest
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorDescriptor
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorFailure
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorGeneration
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorId
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorPlatformReference
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorReference
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorResult
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorSecurityLevel

enum class AndroidProductRuntimeProtectedModelProtectorSecurity {
    STRONGBOX,
    TRUSTED_ENVIRONMENT,
    SOFTWARE
}

sealed interface AndroidProductRuntimeProtectedModelProtectorChoice {
    data class CreateOnce(
        val protectorId: String,
        val generation: Long,
        val security: AndroidProductRuntimeProtectedModelProtectorSecurity
    ) : AndroidProductRuntimeProtectedModelProtectorChoice

    data class RestoreExact(
        val protectorId: String,
        val generation: Long,
        val platformReference: String
    ) : AndroidProductRuntimeProtectedModelProtectorChoice
}

sealed interface AndroidProductRuntimeProtectedModelProtectorRequest {
    data class CreateOnce(
        val request: ProtectedModelKeyProtectorCreationRequest
    ) : AndroidProductRuntimeProtectedModelProtectorRequest

    data class RestoreExact(
        val reference: ProtectedModelKeyProtectorReference
    ) : AndroidProductRuntimeProtectedModelProtectorRequest
}

sealed interface AndroidProductRuntimeProtectedModelProtectorChoiceResult {
    data class Ready(
        val request: AndroidProductRuntimeProtectedModelProtectorRequest
    ) : AndroidProductRuntimeProtectedModelProtectorChoiceResult

    data object Rejected : AndroidProductRuntimeProtectedModelProtectorChoiceResult
}

sealed interface AndroidProductRuntimeProtectedModelProtectorSetupResult {
    data class Ready(
        val descriptor: ProtectedModelKeyProtectorDescriptor
    ) : AndroidProductRuntimeProtectedModelProtectorSetupResult

    data class Rejected(
        val reason: ProtectedModelKeyProtectorFailure
    ) : AndroidProductRuntimeProtectedModelProtectorSetupResult

    data class Failed(
        val reason: ProtectedModelKeyProtectorFailure,
        val throwable: Throwable? = null
    ) : AndroidProductRuntimeProtectedModelProtectorSetupResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

/**
 * Exact product ownership for protected-model DEK protector create/restore.
 *
 * Protector Choice != Discovery.
 * Protector Choice != Generation Bump.
 * Protector Choice != Rotation.
 * Protector Choice != Silent Security Downgrade.
 */
object AndroidProductRuntimeProtectedModelProtectorChoiceFactory {
    fun create(
        choice: AndroidProductRuntimeProtectedModelProtectorChoice
    ): AndroidProductRuntimeProtectedModelProtectorChoiceResult =
        try {
            when (choice) {
                is AndroidProductRuntimeProtectedModelProtectorChoice.CreateOnce ->
                    AndroidProductRuntimeProtectedModelProtectorChoiceResult.Ready(
                        AndroidProductRuntimeProtectedModelProtectorRequest.CreateOnce(
                            ProtectedModelKeyProtectorCreationRequest(
                                id = ProtectedModelKeyProtectorId(choice.protectorId),
                                generation = ProtectedModelKeyProtectorGeneration(
                                    choice.generation
                                ),
                                requestedSecurityLevel = when (choice.security) {
                                    AndroidProductRuntimeProtectedModelProtectorSecurity.STRONGBOX ->
                                        ProtectedModelKeyProtectorSecurityLevel.STRONGBOX
                                    AndroidProductRuntimeProtectedModelProtectorSecurity.TRUSTED_ENVIRONMENT ->
                                        ProtectedModelKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT
                                    AndroidProductRuntimeProtectedModelProtectorSecurity.SOFTWARE ->
                                        ProtectedModelKeyProtectorSecurityLevel.SOFTWARE
                                }
                            )
                        )
                    )

                is AndroidProductRuntimeProtectedModelProtectorChoice.RestoreExact ->
                    AndroidProductRuntimeProtectedModelProtectorChoiceResult.Ready(
                        AndroidProductRuntimeProtectedModelProtectorRequest.RestoreExact(
                            ProtectedModelKeyProtectorReference(
                                id = ProtectedModelKeyProtectorId(choice.protectorId),
                                generation = ProtectedModelKeyProtectorGeneration(
                                    choice.generation
                                ),
                                platformReference =
                                    ProtectedModelKeyProtectorPlatformReference(
                                        choice.platformReference
                                    )
                            )
                        )
                    )
            }
        } catch (_: IllegalArgumentException) {
            AndroidProductRuntimeProtectedModelProtectorChoiceResult.Rejected
        }
}

/**
 * Executes exactly the product-selected create or restore operation.
 *
 * RestoreExact calls only inspect(reference). CreateOnce calls only create(request).
 * No fallback from StrongBox/TEE to weaker security is performed here.
 */
object AndroidProductRuntimeProtectedModelProtectorSetup {
    fun execute(
        protector: ProtectedModelKeyProtector,
        request: AndroidProductRuntimeProtectedModelProtectorRequest
    ): AndroidProductRuntimeProtectedModelProtectorSetupResult {
        val result = try {
            when (request) {
                is AndroidProductRuntimeProtectedModelProtectorRequest.CreateOnce ->
                    protector.create(request.request)
                is AndroidProductRuntimeProtectedModelProtectorRequest.RestoreExact ->
                    protector.inspect(request.reference)
            }
        } catch (throwable: Throwable) {
            return AndroidProductRuntimeProtectedModelProtectorSetupResult.Failed(
                reason = ProtectedModelKeyProtectorFailure.PROVIDER_FAILED,
                throwable = throwable
            )
        }

        return when (result) {
            is ProtectedModelKeyProtectorResult.Success ->
                AndroidProductRuntimeProtectedModelProtectorSetupResult.Ready(result.value)
            is ProtectedModelKeyProtectorResult.Rejected ->
                AndroidProductRuntimeProtectedModelProtectorSetupResult.Rejected(result.reason)
            is ProtectedModelKeyProtectorResult.Failed ->
                AndroidProductRuntimeProtectedModelProtectorSetupResult.Failed(
                    result.reason,
                    result.throwable
                )
        }
    }
}
