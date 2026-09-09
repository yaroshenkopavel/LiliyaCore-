package pro.liliya.android.runtime

import pro.liliya.android.cognitivestorage.AndroidCognitiveStorageFirstRunKeySetupRequest
import pro.liliya.core.encryption.CognitiveDekGeneration
import pro.liliya.core.encryption.CognitiveDekId
import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.encryption.CognitiveKeyProtectorCreationRequest
import pro.liliya.core.encryption.CognitiveKeyProtectorGeneration
import pro.liliya.core.encryption.CognitiveKeyProtectorId
import pro.liliya.core.encryption.CognitiveKeyProtectorSecurityLevel

enum class AndroidProductRuntimeFirstRunKeySecurity {
    STRONGBOX,
    TRUSTED_ENVIRONMENT,
    SOFTWARE
}

sealed interface AndroidProductRuntimeFirstRunKeyChoice {
    data class CreateOnce(
        val dekId: String,
        val protectorId: String,
        val protectorGeneration: Long,
        val security: AndroidProductRuntimeFirstRunKeySecurity
    ) : AndroidProductRuntimeFirstRunKeyChoice

    data class RestoreExact(
        val dekId: String,
        val dekGeneration: Long
    ) : AndroidProductRuntimeFirstRunKeyChoice
}

sealed interface AndroidProductRuntimeFirstRunKeyChoiceResult {
    data class Ready(
        val request: AndroidCognitiveStorageFirstRunKeySetupRequest
    ) : AndroidProductRuntimeFirstRunKeyChoiceResult

    data object Rejected : AndroidProductRuntimeFirstRunKeyChoiceResult
}

/**
 * Maps one explicit product-level first-run key choice to the exact cognitive-storage request.
 *
 * Key Choice != Key Selection Heuristic.
 * Key Choice != Rotation.
 * Key Choice != Silent Security Downgrade.
 */
object AndroidProductRuntimeFirstRunKeyChoiceFactory {
    fun create(
        choice: AndroidProductRuntimeFirstRunKeyChoice
    ): AndroidProductRuntimeFirstRunKeyChoiceResult =
        try {
            when (choice) {
                is AndroidProductRuntimeFirstRunKeyChoice.CreateOnce ->
                    AndroidProductRuntimeFirstRunKeyChoiceResult.Ready(
                        AndroidCognitiveStorageFirstRunKeySetupRequest.CreateOnce(
                            dekId = CognitiveDekId(choice.dekId),
                            protectorRequest = CognitiveKeyProtectorCreationRequest(
                                id = CognitiveKeyProtectorId(choice.protectorId),
                                generation = CognitiveKeyProtectorGeneration(
                                    choice.protectorGeneration
                                ),
                                requestedSecurityLevel = when (choice.security) {
                                    AndroidProductRuntimeFirstRunKeySecurity.STRONGBOX ->
                                        CognitiveKeyProtectorSecurityLevel.STRONGBOX
                                    AndroidProductRuntimeFirstRunKeySecurity.TRUSTED_ENVIRONMENT ->
                                        CognitiveKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT
                                    AndroidProductRuntimeFirstRunKeySecurity.SOFTWARE ->
                                        CognitiveKeyProtectorSecurityLevel.SOFTWARE
                                }
                            )
                        )
                    )

                is AndroidProductRuntimeFirstRunKeyChoice.RestoreExact ->
                    AndroidProductRuntimeFirstRunKeyChoiceResult.Ready(
                        AndroidCognitiveStorageFirstRunKeySetupRequest.RestoreExact(
                            CognitiveDekReference(
                                id = CognitiveDekId(choice.dekId),
                                generation = CognitiveDekGeneration(choice.dekGeneration)
                            )
                        )
                    )
            }
        } catch (_: IllegalArgumentException) {
            AndroidProductRuntimeFirstRunKeyChoiceResult.Rejected
        }
}
