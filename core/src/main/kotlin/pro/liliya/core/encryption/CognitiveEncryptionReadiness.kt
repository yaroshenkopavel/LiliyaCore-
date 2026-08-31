package pro.liliya.core.encryption

import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentStoreId

data class CognitiveCiphertextDependency(
    val storeId: PersistentStoreId,
    val entityId: PersistentEntityId,
    val entityGeneration: PersistentGeneration,
    val dek: CognitiveDekReference
) {
    override fun toString(): String =
        "CognitiveCiphertextDependency(storeId=[redacted], entityId=[redacted], " +
            "entityGeneration=${entityGeneration.value}, dek=$dek)"
}

enum class CognitiveDependencyUpdateFailure {
    DUPLICATE_COMMITTED_DEPENDENCY,
    EXPECTED_DEPENDENCY_MISMATCH,
    DEPENDENCY_MISSING
}

sealed interface CognitiveDependencyUpdateResult {
    data object Updated : CognitiveDependencyUpdateResult
    data class Rejected(val reason: CognitiveDependencyUpdateFailure) : CognitiveDependencyUpdateResult
}

/**
 * Exact committed-ciphertext dependency ownership used by rotation and recovery readiness checks.
 * This registry is deliberately process-local metadata; it does not replace durable persistence
 * migration state and it does not authorize protected use.
 */
class CognitiveCiphertextDependencyRegistry {
    private data class Key(
        val storeId: PersistentStoreId,
        val entityId: PersistentEntityId
    )

    private val lock = Any()
    private val entries = mutableMapOf<Key, CognitiveCiphertextDependency>()

    fun registerCommitted(
        dependency: CognitiveCiphertextDependency
    ): CognitiveDependencyUpdateResult = synchronized(lock) {
        val key = Key(dependency.storeId, dependency.entityId)
        if (entries.containsKey(key)) {
            CognitiveDependencyUpdateResult.Rejected(
                CognitiveDependencyUpdateFailure.DUPLICATE_COMMITTED_DEPENDENCY
            )
        } else {
            entries[key] = dependency
            CognitiveDependencyUpdateResult.Updated
        }
    }

    fun migrateCommitted(
        expected: CognitiveCiphertextDependency,
        replacement: CognitiveCiphertextDependency
    ): CognitiveDependencyUpdateResult = synchronized(lock) {
        val expectedKey = Key(expected.storeId, expected.entityId)
        val replacementKey = Key(replacement.storeId, replacement.entityId)
        val current = entries[expectedKey]
            ?: return@synchronized CognitiveDependencyUpdateResult.Rejected(
                CognitiveDependencyUpdateFailure.DEPENDENCY_MISSING
            )

        if (expectedKey != replacementKey || current != expected) {
            return@synchronized CognitiveDependencyUpdateResult.Rejected(
                CognitiveDependencyUpdateFailure.EXPECTED_DEPENDENCY_MISMATCH
            )
        }

        entries[expectedKey] = replacement
        CognitiveDependencyUpdateResult.Updated
    }

    fun releaseCommitted(
        expected: CognitiveCiphertextDependency
    ): CognitiveDependencyUpdateResult = synchronized(lock) {
        val key = Key(expected.storeId, expected.entityId)
        val current = entries[key]
            ?: return@synchronized CognitiveDependencyUpdateResult.Rejected(
                CognitiveDependencyUpdateFailure.DEPENDENCY_MISSING
            )
        if (current != expected) {
            return@synchronized CognitiveDependencyUpdateResult.Rejected(
                CognitiveDependencyUpdateFailure.EXPECTED_DEPENDENCY_MISMATCH
            )
        }
        entries.remove(key)
        CognitiveDependencyUpdateResult.Updated
    }

    fun canRetire(dek: CognitiveDekReference): Boolean = synchronized(lock) {
        entries.values.none { it.dek == dek }
    }

    fun dependenciesFor(dek: CognitiveDekReference): List<CognitiveCiphertextDependency> =
        synchronized(lock) {
            entries.values
                .filter { it.dek == dek }
                .sortedWith(
                    compareBy<CognitiveCiphertextDependency> { it.storeId.value }
                        .thenBy { it.entityId.value }
                        .thenBy { it.entityGeneration.value }
                )
        }

    fun snapshot(): List<CognitiveCiphertextDependency> = synchronized(lock) {
        entries.values.sortedWith(
            compareBy<CognitiveCiphertextDependency> { it.storeId.value }
                .thenBy { it.entityId.value }
                .thenBy { it.entityGeneration.value }
        )
    }
}

enum class CognitiveRecoveryState {
    READY,
    PROTECTOR_MISSING,
    PROTECTOR_INVALIDATED,
    STALE_PROTECTOR,
    WRAPPED_DEK_UNAVAILABLE,
    AUTHENTICATION_FAILED,
    UNAVAILABLE
}

data class CognitiveRecoveryAssessment(
    val state: CognitiveRecoveryState,
    val protector: CognitiveKeyProtectorReference? = null,
    val dek: CognitiveDekReference? = null
) {
    override fun toString(): String =
        "CognitiveRecoveryAssessment(state=$state, protector=$protector, dek=$dek)"
}

object CognitiveRecoveryClassifier {
    fun classify(
        result: CognitiveEncryptionResult<*>,
        protector: CognitiveKeyProtectorReference? = null,
        dek: CognitiveDekReference? = null
    ): CognitiveRecoveryAssessment {
        val state = when (result) {
            is CognitiveEncryptionResult.Success -> CognitiveRecoveryState.READY
            is CognitiveEncryptionResult.Rejected -> when (result.category) {
                CognitiveEncryptionFailureCategory.PROTECTOR_MISSING -> CognitiveRecoveryState.PROTECTOR_MISSING
                CognitiveEncryptionFailureCategory.PROTECTOR_INVALIDATED -> CognitiveRecoveryState.PROTECTOR_INVALIDATED
                CognitiveEncryptionFailureCategory.STALE_PROTECTOR_OWNERSHIP -> CognitiveRecoveryState.STALE_PROTECTOR
                CognitiveEncryptionFailureCategory.DEK_MISSING,
                CognitiveEncryptionFailureCategory.UNWRAP_REJECTED,
                CognitiveEncryptionFailureCategory.UNWRAP_FAILED -> CognitiveRecoveryState.WRAPPED_DEK_UNAVAILABLE
                CognitiveEncryptionFailureCategory.CIPHERTEXT_AUTHENTICATION_FAILED ->
                    CognitiveRecoveryState.AUTHENTICATION_FAILED
                else -> CognitiveRecoveryState.UNAVAILABLE
            }
            is CognitiveEncryptionResult.Failed -> when (result.category) {
                CognitiveEncryptionFailureCategory.PROTECTOR_MISSING -> CognitiveRecoveryState.PROTECTOR_MISSING
                CognitiveEncryptionFailureCategory.PROTECTOR_INVALIDATED -> CognitiveRecoveryState.PROTECTOR_INVALIDATED
                CognitiveEncryptionFailureCategory.STALE_PROTECTOR_OWNERSHIP -> CognitiveRecoveryState.STALE_PROTECTOR
                CognitiveEncryptionFailureCategory.CIPHERTEXT_AUTHENTICATION_FAILED ->
                    CognitiveRecoveryState.AUTHENTICATION_FAILED
                else -> CognitiveRecoveryState.UNAVAILABLE
            }
        }
        return CognitiveRecoveryAssessment(state, protector, dek)
    }
}
