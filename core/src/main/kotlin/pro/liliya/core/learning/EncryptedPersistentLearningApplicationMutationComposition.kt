package pro.liliya.core.learning

import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.encryption.CognitiveEncryptionResult
import pro.liliya.core.encryption.CognitivePersistentRecordDraft
import pro.liliya.core.encryption.CognitivePlaintext
import pro.liliya.core.encryption.EncryptedPersistentRecordStore
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentMutationResult
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentRecordOwnership

sealed interface EncryptedPersistentLearningApplicationMutationOpenResult {
    data class Opened(
        val composition: EncryptedPersistentLearningApplicationMutationComposition
    ) : EncryptedPersistentLearningApplicationMutationOpenResult

    data object Corrupt : EncryptedPersistentLearningApplicationMutationOpenResult
    data class Incompatible(val reason: String) :
        EncryptedPersistentLearningApplicationMutationOpenResult

    data class EncryptionUnavailable(val reason: String) :
        EncryptedPersistentLearningApplicationMutationOpenResult

    data class RestorationFailed(val reason: String) :
        EncryptedPersistentLearningApplicationMutationOpenResult
}

/**
 * Encrypted durable mutation lifecycle for governed learning.
 *
 * Both prepared payloads and completed receipts are persisted through EncryptedPersistentRecordStore,
 * so learned content never needs a plaintext durable backend representation.
 */
class EncryptedPersistentLearningApplicationMutationComposition private constructor(
    private val foundation: FoundationComposition,
    private val encryptedStore: EncryptedPersistentRecordStore,
    private val dek: CognitiveDekReference,
    private val mutationStore: LearningApplicationMutationStore
) {
    private val activeClaims = mutableSetOf<LearningApplicationMutationReference>()

    @Synchronized
    fun prepare(
        plan: LearningApplicationMutationPlan
    ): PersistentLearningApplicationMutationPrepareResult {
        when (val validation = mutationStore.validatePrepare(plan)) {
            LearningApplicationMutationPrepareValidation.Ready -> Unit
            is LearningApplicationMutationPrepareValidation.AlreadyCompleted ->
                return PersistentLearningApplicationMutationPrepareResult.AlreadyCompleted(
                    validation.receipt
                )
            is LearningApplicationMutationPrepareValidation.Rejected ->
                return PersistentLearningApplicationMutationPrepareResult.Rejected(
                    validation.reason
                )
        }

        val encoded = LearningApplicationMutationPersistentCodec.encodePrepared(plan)
        return when (val durable = encryptedStore.install(encoded.toEncryptedDraft(dek))) {
            is CognitiveEncryptionResult.Success ->
                installCommitted(plan, durable.value)
            is CognitiveEncryptionResult.Rejected ->
                PersistentLearningApplicationMutationPrepareResult.Rejected(
                    "encrypted persistent learning mutation prepare rejected"
                )
            is CognitiveEncryptionResult.Failed ->
                PersistentLearningApplicationMutationPrepareResult.Failed(
                    "encrypted persistent learning mutation prepare failed",
                    durable.throwable
                )
        }
    }

    @Synchronized
    fun claim(
        reference: LearningApplicationMutationReference
    ): PersistentLearningApplicationMutationClaimResult {
        val context = foundation.rootContext(
            operation = "claimEncryptedPersistedLearningApplicationMutation",
            component = "LearningApplicationMutation",
            metadata = referenceMetadata(reference)
        )
        return when (val result = mutationStore.claim(reference, context)) {
            is LearningApplicationMutationClaimRegistrationResult.Claimed -> {
                val registration = result.claim
                val exactReference = LearningApplicationMutationReference(
                    registration.plan.id,
                    registration.generation
                )
                activeClaims += exactReference
                PersistentLearningApplicationMutationClaimResult.Claimed(
                    PersistentLearningApplicationMutationClaim(
                        plan = registration.plan,
                        reference = exactReference,
                        releaseAction = {
                            synchronized(this@EncryptedPersistentLearningApplicationMutationComposition) {
                                val released = registration.release(
                                    foundation.rootContext(
                                        operation = "releaseEncryptedPersistedLearningApplicationMutationClaim",
                                        component = "LearningApplicationMutation",
                                        metadata = metadata(registration.plan, registration.generation)
                                    )
                                )
                                if (released) activeClaims.remove(exactReference)
                                released
                            }
                        },
                        completeAction = { receipt ->
                            completeClaim(
                                registration = registration,
                                exactReference = exactReference,
                                receipt = receipt
                            )
                        }
                    )
                )
            }
            is LearningApplicationMutationClaimRegistrationResult.Rejected ->
                PersistentLearningApplicationMutationClaimResult.Rejected(result.reason)
        }
    }

    fun inspect(id: LearningApplicationMutationId): LearningApplicationMutationSnapshot? =
        mutationStore.inspect(id)

    fun completedOutcomeByMutationId(
        id: LearningApplicationMutationId
    ): LearningApplicationMutationApplicationReceipt? =
        mutationStore.completedOutcomeByMutationId(id)

    fun completedOutcomeByIdempotencyKey(
        key: LearningApplicationIdempotencyKey
    ): LearningApplicationMutationApplicationReceipt? =
        mutationStore.completedOutcomeByIdempotencyKey(key)

    fun snapshotEntries(): List<LearningApplicationMutationSnapshot> =
        mutationStore.snapshotEntries()

    @Synchronized
    private fun completeClaim(
        registration: LearningApplicationMutationClaimRegistration,
        exactReference: LearningApplicationMutationReference,
        receipt: LearningApplicationMutationApplicationReceipt
    ): PersistentLearningApplicationMutationResult {
        if (exactReference !in activeClaims) {
            return PersistentLearningApplicationMutationResult.Rejected(
                "encrypted persistent learning mutation claim is no longer active"
            )
        }
        if (receipt.mutation != exactReference) {
            return PersistentLearningApplicationMutationResult.Rejected(
                "encrypted persistent learning mutation completion reference mismatch"
            )
        }
        if (receipt.target != registration.plan.target ||
            !downstreamMatchesTarget(receipt.downstream, registration.plan.target)
        ) {
            return PersistentLearningApplicationMutationResult.Rejected(
                "encrypted persistent learning mutation completion target mismatch"
            )
        }

        val completed = try {
            LearningApplicationMutationPersistentCodec.encodeCompleted(
                registration.plan,
                receipt
            )
        } catch (_: IllegalArgumentException) {
            return PersistentLearningApplicationMutationResult.Rejected(
                "encrypted persistent learning mutation completion receipt is invalid"
            )
        }
        val prepared = LearningApplicationMutationPersistentCodec.encodePrepared(
            registration.plan
        )
        val sourceGeneration = PersistentGeneration(registration.generation.value)

        return when (
            val durable = encryptedStore.transitionExact(
                sourceId = prepared.id,
                sourceGeneration = sourceGeneration,
                replacement = completed.toEncryptedDraft(dek)
            )
        ) {
            is CognitiveEncryptionResult.Success -> {
                val completedLocally = registration.complete(
                    receipt = receipt,
                    context = foundation.rootContext(
                        operation = "completeEncryptedPersistedLearningApplicationMutation",
                        component = "LearningApplicationMutation",
                        metadata = metadata(registration.plan, registration.generation)
                    )
                )
                if (completedLocally) {
                    activeClaims.remove(exactReference)
                    PersistentLearningApplicationMutationResult.Committed
                } else {
                    PersistentLearningApplicationMutationResult.Failed(
                        "encrypted durable completion committed but local exact completion failed"
                    )
                }
            }
            is CognitiveEncryptionResult.Rejected ->
                PersistentLearningApplicationMutationResult.Rejected(
                    "encrypted persistent learning mutation transition rejected"
                )
            is CognitiveEncryptionResult.Failed ->
                PersistentLearningApplicationMutationResult.Failed(
                    "encrypted persistent learning mutation transition failed",
                    durable.throwable
                )
        }
    }

    private fun installCommitted(
        plan: LearningApplicationMutationPlan,
        persistentOwnership: PersistentRecordOwnership
    ): PersistentLearningApplicationMutationPrepareResult {
        val generation = LearningApplicationMutationGeneration(
            persistentOwnership.generation.value
        )
        val context = foundation.rootContext(
            operation = "prepareEncryptedPersistedLearningApplicationMutation",
            component = "LearningApplicationMutation",
            metadata = metadata(plan, generation)
        )
        return when (
            val local = mutationStore.installCommitted(
                plan = plan,
                generation = generation,
                highWatermark = encryptedStore.generationHighWatermark(),
                context = context
            )
        ) {
            is LearningApplicationMutationRegistrationResult.Registered ->
                PersistentLearningApplicationMutationPrepareResult.Prepared(
                    ownership(
                        persistentOwnership = persistentOwnership,
                        localRegistration = local.registration
                    )
                )

            is LearningApplicationMutationRegistrationResult.AlreadyCompleted -> {
                val compensated = persistentOwnership.remove()
                if (compensated is PersistentMutationResult.Committed) {
                    PersistentLearningApplicationMutationPrepareResult.AlreadyCompleted(
                        local.receipt
                    )
                } else {
                    PersistentLearningApplicationMutationPrepareResult.Failed(
                        "encrypted mutation already completed after durable prepare; compensation failed"
                    )
                }
            }

            is LearningApplicationMutationRegistrationResult.Rejected -> {
                val compensated = persistentOwnership.remove()
                PersistentLearningApplicationMutationPrepareResult.Failed(
                    if (compensated is PersistentMutationResult.Committed) {
                        "local encrypted mutation install rejected; durable candidate compensated"
                    } else {
                        "local encrypted mutation install rejected; durable compensation failed"
                    }
                )
            }
        }
    }

    private fun ownership(
        persistentOwnership: PersistentRecordOwnership,
        localRegistration: LearningApplicationMutationRegistration
    ): PersistentLearningApplicationMutationOwnership =
        object : PersistentLearningApplicationMutationOwnership {
            override val plan: LearningApplicationMutationPlan =
                localRegistration.plan
            override val generation: LearningApplicationMutationGeneration =
                localRegistration.generation

            override fun remove(): PersistentLearningApplicationMutationResult =
                synchronized(this@EncryptedPersistentLearningApplicationMutationComposition) {
                    val reference = LearningApplicationMutationReference(plan.id, generation)
                    if (reference in activeClaims) {
                        return@synchronized PersistentLearningApplicationMutationResult.Rejected(
                            "encrypted persistent learning mutation is actively claimed"
                        )
                    }
                    when (val durable = persistentOwnership.remove()) {
                        PersistentMutationResult.Committed -> {
                            val removedLocally = localRegistration.remove(
                                foundation.rootContext(
                                    operation = "removeEncryptedPersistedLearningApplicationMutation",
                                    component = "LearningApplicationMutation",
                                    metadata = metadata(plan, generation)
                                )
                            )
                            if (removedLocally) {
                                PersistentLearningApplicationMutationResult.Committed
                            } else {
                                PersistentLearningApplicationMutationResult.Failed(
                                    "encrypted durable mutation removal committed but local removal failed"
                                )
                            }
                        }
                        is PersistentMutationResult.Rejected ->
                            PersistentLearningApplicationMutationResult.Rejected(
                                durable.reason
                            )
                        is PersistentMutationResult.Failed ->
                            PersistentLearningApplicationMutationResult.Failed(
                                durable.reason,
                                durable.throwable
                            )
                    }
                }
        }

    private fun referenceMetadata(
        reference: LearningApplicationMutationReference
    ): Map<String, String> = mapOf(
        "learningApplicationMutationId" to reference.mutationId.value,
        "learningApplicationMutationGeneration" to reference.generation.value.toString()
    )

    private fun metadata(
        plan: LearningApplicationMutationPlan,
        generation: LearningApplicationMutationGeneration
    ): Map<String, String> = mapOf(
        "learningApplicationMutationId" to plan.id.value,
        "learningApplicationMutationGeneration" to generation.value.toString(),
        "learningApplicationTarget" to plan.target.name.lowercase()
    )

    private fun downstreamMatchesTarget(
        downstream: LearningApplicationDownstreamReference,
        target: LearningApplicationTarget
    ): Boolean = when (target) {
        LearningApplicationTarget.MEMORY ->
            downstream is LearningApplicationDownstreamReference.Memory
        LearningApplicationTarget.KNOWLEDGE ->
            downstream is LearningApplicationDownstreamReference.Knowledge
    }

    companion object {
        fun open(
            foundation: FoundationComposition,
            encryptedStore: EncryptedPersistentRecordStore,
            dek: CognitiveDekReference
        ): EncryptedPersistentLearningApplicationMutationOpenResult {
            val snapshots = when (val restored = encryptedStore.decryptedSnapshotEntries()) {
                is CognitiveEncryptionResult.Success -> restored.value
                is CognitiveEncryptionResult.Rejected ->
                    return EncryptedPersistentLearningApplicationMutationOpenResult.EncryptionUnavailable(
                        "encrypted learning mutation payload could not be opened"
                    )
                is CognitiveEncryptionResult.Failed ->
                    return EncryptedPersistentLearningApplicationMutationOpenResult.EncryptionUnavailable(
                        "encrypted learning mutation payload open failed"
                    )
            }

            val liveEntries = mutableListOf<LearningPreparedPersistentState>()
            val completedEntries = mutableListOf<LearningCompletedPersistentState>()

            for (snapshot in snapshots) {
                when (val decoded = LearningApplicationMutationPersistentCodec.decode(snapshot.record)) {
                    is LearningApplicationMutationPersistentDecodeResult.Prepared -> {
                        val generation = try {
                            LearningApplicationMutationGeneration(snapshot.generation.value)
                        } catch (_: IllegalArgumentException) {
                            return EncryptedPersistentLearningApplicationMutationOpenResult.Corrupt
                        }
                        liveEntries += LearningPreparedPersistentState(
                            decoded.plan,
                            generation
                        )
                    }
                    is LearningApplicationMutationPersistentDecodeResult.Completed -> {
                        val generation = try {
                            LearningApplicationMutationGeneration(snapshot.generation.value)
                        } catch (_: IllegalArgumentException) {
                            return EncryptedPersistentLearningApplicationMutationOpenResult.Corrupt
                        }
                        if (decoded.receipt.mutation.generation != generation) {
                            return EncryptedPersistentLearningApplicationMutationOpenResult.RestorationFailed(
                                "completed encrypted learning receipt generation mismatch"
                            )
                        }
                        completedEntries += LearningCompletedPersistentState(
                            decoded.plan,
                            decoded.receipt
                        )
                    }
                    LearningApplicationMutationPersistentDecodeResult.Corrupt ->
                        return EncryptedPersistentLearningApplicationMutationOpenResult.Corrupt
                    is LearningApplicationMutationPersistentDecodeResult.Incompatible ->
                        return EncryptedPersistentLearningApplicationMutationOpenResult.Incompatible(
                            decoded.reason
                        )
                }
            }

            val restored = when (
                val result = LearningApplicationMutationRestorationBoundary.restore(
                    liveEntries = liveEntries,
                    completedEntries = completedEntries,
                    highWatermark = encryptedStore.generationHighWatermark()
                )
            ) {
                is LearningApplicationMutationRestorationResult.Restored -> result.state
                is LearningApplicationMutationRestorationResult.Rejected ->
                    return EncryptedPersistentLearningApplicationMutationOpenResult.RestorationFailed(
                        result.reason
                    )
            }

            return EncryptedPersistentLearningApplicationMutationOpenResult.Opened(
                EncryptedPersistentLearningApplicationMutationComposition(
                    foundation = foundation,
                    encryptedStore = encryptedStore,
                    dek = dek,
                    mutationStore = LearningApplicationMutationStore.restore(
                        observability = foundation.observability,
                        state = restored
                    )
                )
            )
        }
    }
}

private fun PersistentRecord.toEncryptedDraft(
    dek: CognitiveDekReference
): CognitivePersistentRecordDraft =
    CognitivePersistentRecordDraft(
        id = id,
        schemaId = schemaId,
        schemaVersion = schemaVersion,
        plaintext = CognitivePlaintext(payload.copyBytes()),
        createdAt = createdAt,
        dek = dek
    )
