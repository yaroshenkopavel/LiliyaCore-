package pro.liliya.core.learning

import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.encryption.CognitiveEncryptionFailureCategory
import pro.liliya.core.encryption.CognitiveEncryptionResult
import pro.liliya.core.encryption.CognitivePersistentRecordDraft
import pro.liliya.core.encryption.CognitivePersistentRecordTransitionDraft
import pro.liliya.core.encryption.CognitivePlaintext
import pro.liliya.core.encryption.EncryptedPersistentRecordStore
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentMutationResult
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentRecordOwnership

sealed interface EncryptedPersistentLearningApplicationMutationOpenResult {
    data class Opened(
        val composition: EncryptedPersistentLearningApplicationMutationComposition
    ) : EncryptedPersistentLearningApplicationMutationOpenResult

    data object Corrupt : EncryptedPersistentLearningApplicationMutationOpenResult
    data class Incompatible(val reason: String) :
        EncryptedPersistentLearningApplicationMutationOpenResult

    data class EncryptionUnavailable(
        val category: CognitiveEncryptionFailureCategory
    ) : EncryptedPersistentLearningApplicationMutationOpenResult

    data class RestorationFailed(val reason: String) :
        EncryptedPersistentLearningApplicationMutationOpenResult
}

/**
 * Encrypted durable lifecycle for governed-learning mutation plans and completion receipts.
 *
 * The mutation payload can contain learned Memory/Knowledge content. Therefore prepared and
 * completed lifecycle records are both stored only through EncryptedPersistentRecordStore.
 */
class EncryptedPersistentLearningApplicationMutationComposition private constructor(
    private val foundation: FoundationComposition,
    private val encryptedStore: EncryptedPersistentRecordStore,
    private val activeDek: CognitiveDekReference,
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
        val plaintext = encoded.payload.copyBytes()
        val durable = try {
            encryptedStore.install(
                CognitivePersistentRecordDraft(
                    id = encoded.id,
                    schemaId = encoded.schemaId,
                    schemaVersion = encoded.schemaVersion,
                    plaintext = CognitivePlaintext(plaintext),
                    createdAt = encoded.createdAt,
                    dek = activeDek
                )
            )
        } finally {
            plaintext.fill(0)
        }

        return when (durable) {
            is CognitiveEncryptionResult.Success ->
                installCommitted(plan, durable.value)

            is CognitiveEncryptionResult.Rejected ->
                PersistentLearningApplicationMutationPrepareResult.Rejected(
                    "encrypted persistent learning mutation rejected: ${durable.category}"
                )

            is CognitiveEncryptionResult.Failed ->
                PersistentLearningApplicationMutationPrepareResult.Failed(
                    reason = "encrypted persistent learning mutation durable prepare failed",
                    throwable = durable.throwable
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
                                        operation =
                                            "releaseEncryptedPersistedLearningApplicationMutationClaim",
                                        component = "LearningApplicationMutation",
                                        metadata = metadata(
                                            registration.plan,
                                            registration.generation
                                        )
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

    fun find(id: LearningApplicationMutationId): LearningApplicationMutationPlan? =
        mutationStore.find(id)

    fun inspect(id: LearningApplicationMutationId): LearningApplicationMutationSnapshot? =
        mutationStore.inspect(id)

    fun contains(id: LearningApplicationMutationId): Boolean =
        mutationStore.contains(id)

    fun findByIdempotencyKey(
        key: LearningApplicationIdempotencyKey
    ): LearningApplicationMutationPlan? =
        mutationStore.findByIdempotencyKey(key)

    fun completedOutcomeByMutationId(
        id: LearningApplicationMutationId
    ): LearningApplicationMutationApplicationReceipt? =
        mutationStore.completedOutcomeByMutationId(id)

    fun completedOutcomeByIdempotencyKey(
        key: LearningApplicationIdempotencyKey
    ): LearningApplicationMutationApplicationReceipt? =
        mutationStore.completedOutcomeByIdempotencyKey(key)

    fun isCompletedIdempotencyKey(
        key: LearningApplicationIdempotencyKey
    ): Boolean =
        mutationStore.isCompletedIdempotencyKey(key)

    fun snapshot(): List<LearningApplicationMutationPlan> =
        mutationStore.snapshot()

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
                "encrypted persistent learning mutation completion receipt reference mismatch"
            )
        }
        if (
            receipt.target != registration.plan.target ||
            !downstreamMatchesTarget(receipt.downstream, registration.plan.target)
        ) {
            return PersistentLearningApplicationMutationResult.Rejected(
                "encrypted persistent learning mutation completion receipt target mismatch"
            )
        }

        val completedRecord = try {
            LearningApplicationMutationPersistentCodec.encodeCompleted(
                registration.plan,
                receipt
            )
        } catch (_: IllegalArgumentException) {
            return PersistentLearningApplicationMutationResult.Rejected(
                "encrypted persistent learning mutation completion receipt is invalid"
            )
        }

        val plaintext = completedRecord.payload.copyBytes()
        val durable = try {
            encryptedStore.transitionExact(
                CognitivePersistentRecordTransitionDraft(
                    sourceId = PersistentEntityId(
                        "learning-mutation:prepared:${registration.plan.id.value}"
                    ),
                    sourceGeneration = PersistentGeneration(
                        registration.generation.value
                    ),
                    replacementId = completedRecord.id,
                    replacementSchemaId = completedRecord.schemaId,
                    replacementSchemaVersion = completedRecord.schemaVersion,
                    replacementPlaintext = CognitivePlaintext(plaintext),
                    replacementCreatedAt = completedRecord.createdAt,
                    dek = activeDek
                )
            )
        } finally {
            plaintext.fill(0)
        }

        return when (durable) {
            is CognitiveEncryptionResult.Success -> {
                val completedLocally = registration.complete(
                    receipt = receipt,
                    context = foundation.rootContext(
                        operation =
                            "completeEncryptedPersistedLearningApplicationMutation",
                        component = "LearningApplicationMutation",
                        metadata = metadata(
                            registration.plan,
                            registration.generation
                        )
                    )
                )
                if (completedLocally) {
                    activeClaims.remove(exactReference)
                    PersistentLearningApplicationMutationResult.Committed
                } else {
                    PersistentLearningApplicationMutationResult.Failed(
                        "encrypted durable learning mutation completion committed " +
                            "but local exact completion failed"
                    )
                }
            }

            is CognitiveEncryptionResult.Rejected ->
                PersistentLearningApplicationMutationResult.Rejected(
                    "encrypted persistent learning mutation transition rejected: " +
                        durable.category
                )

            is CognitiveEncryptionResult.Failed ->
                PersistentLearningApplicationMutationResult.Failed(
                    reason =
                        "encrypted persistent learning mutation durable completion failed",
                    throwable = durable.throwable
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
                        "local learning mutation was already completed after encrypted " +
                            "durable prepare; compensation failed"
                    )
                }
            }

            is LearningApplicationMutationRegistrationResult.Rejected -> {
                val compensated = persistentOwnership.remove()
                val reason =
                    if (compensated is PersistentMutationResult.Committed) {
                        "local encrypted learning mutation install rejected after durable " +
                            "prepare; durable candidate compensated"
                    } else {
                        "local encrypted learning mutation install rejected after durable " +
                            "prepare; durable compensation failed"
                    }
                PersistentLearningApplicationMutationPrepareResult.Failed(reason)
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
                    val reference = LearningApplicationMutationReference(
                        plan.id,
                        generation
                    )
                    if (reference in activeClaims) {
                        return@synchronized PersistentLearningApplicationMutationResult.Rejected(
                            "encrypted persistent learning mutation is actively claimed"
                        )
                    }

                    when (val durable = persistentOwnership.remove()) {
                        PersistentMutationResult.Committed -> {
                            val removedLocally = localRegistration.remove(
                                foundation.rootContext(
                                    operation =
                                        "removeEncryptedPersistedLearningApplicationMutation",
                                    component = "LearningApplicationMutation",
                                    metadata = metadata(plan, generation)
                                )
                            )
                            if (removedLocally) {
                                PersistentLearningApplicationMutationResult.Committed
                            } else {
                                PersistentLearningApplicationMutationResult.Failed(
                                    "encrypted durable learning mutation removal committed " +
                                        "but local exact removal failed"
                                )
                            }
                        }

                        is PersistentMutationResult.Rejected ->
                            PersistentLearningApplicationMutationResult.Rejected(
                                durable.reason
                            )

                        is PersistentMutationResult.Failed ->
                            PersistentLearningApplicationMutationResult.Failed(
                                reason =
                                    "encrypted persistent learning mutation durable removal failed",
                                throwable = durable.throwable
                            )
                    }
                }
        }

    private fun referenceMetadata(
        reference: LearningApplicationMutationReference
    ): Map<String, String> = mapOf(
        "learningApplicationMutationId" to reference.mutationId.value,
        "learningApplicationMutationGeneration" to
            reference.generation.value.toString()
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

    private fun metadata(
        plan: LearningApplicationMutationPlan,
        generation: LearningApplicationMutationGeneration
    ): Map<String, String> = buildMap {
        put("learningApplicationMutationId", plan.id.value)
        put(
            "learningApplicationMutationGeneration",
            generation.value.toString()
        )
        put("learningApplicationId", plan.application.applicationId.value)
        put(
            "learningApplicationGeneration",
            plan.application.generation.value.toString()
        )
        put("learningApplicationTarget", plan.target.name.lowercase())
        put("idempotencyKey", plan.idempotencyKey.value)
        put("createdAt", plan.createdAt.toString())
        when (val payload = plan.payload) {
            is LearningApplicationMutationPayload.Memory ->
                put("memoryRecordId", payload.record.id.value)

            is LearningApplicationMutationPayload.Knowledge ->
                put("knowledgeItemId", payload.item.id.value)
        }
    }

    companion object {
        fun open(
            foundation: FoundationComposition,
            encryptedStore: EncryptedPersistentRecordStore,
            activeDek: CognitiveDekReference
        ): EncryptedPersistentLearningApplicationMutationOpenResult {
            val liveEntries = mutableListOf<LearningPreparedPersistentState>()
            val completedEntries = mutableListOf<LearningCompletedPersistentState>()

            for (snapshot in encryptedStore.snapshotEntries()) {
                val plaintext = when (
                    val opened = encryptedStore.open(snapshot.record.id)
                ) {
                    is CognitiveEncryptionResult.Success -> opened.value
                    is CognitiveEncryptionResult.Rejected ->
                        return EncryptedPersistentLearningApplicationMutationOpenResult
                            .EncryptionUnavailable(opened.category)

                    is CognitiveEncryptionResult.Failed ->
                        return EncryptedPersistentLearningApplicationMutationOpenResult
                            .EncryptionUnavailable(opened.category)
                }

                val bytes = plaintext.copyBytes()
                val decoded = try {
                    LearningApplicationMutationPersistentCodec.decode(
                        PersistentRecord(
                            id = snapshot.record.id,
                            schemaId = snapshot.record.schemaId,
                            schemaVersion = snapshot.record.schemaVersion,
                            payload = PersistentPayload(bytes),
                            createdAt = snapshot.record.createdAt
                        )
                    )
                } finally {
                    bytes.fill(0)
                }

                when (decoded) {
                    is LearningApplicationMutationPersistentDecodeResult.Prepared -> {
                        val generation = try {
                            LearningApplicationMutationGeneration(
                                snapshot.generation.value
                            )
                        } catch (_: IllegalArgumentException) {
                            return EncryptedPersistentLearningApplicationMutationOpenResult
                                .Corrupt
                        }
                        liveEntries += LearningPreparedPersistentState(
                            decoded.plan,
                            generation
                        )
                    }

                    is LearningApplicationMutationPersistentDecodeResult.Completed -> {
                        val generation = try {
                            LearningApplicationMutationGeneration(
                                snapshot.generation.value
                            )
                        } catch (_: IllegalArgumentException) {
                            return EncryptedPersistentLearningApplicationMutationOpenResult
                                .Corrupt
                        }
                        if (decoded.receipt.mutation.generation != generation) {
                            return EncryptedPersistentLearningApplicationMutationOpenResult
                                .RestorationFailed(
                                    "completed learning receipt generation does not match " +
                                        "encrypted persistent generation"
                                )
                        }
                        completedEntries += LearningCompletedPersistentState(
                            decoded.plan,
                            decoded.receipt
                        )
                    }

                    LearningApplicationMutationPersistentDecodeResult.Corrupt ->
                        return EncryptedPersistentLearningApplicationMutationOpenResult
                            .Corrupt

                    is LearningApplicationMutationPersistentDecodeResult.Incompatible ->
                        return EncryptedPersistentLearningApplicationMutationOpenResult
                            .Incompatible(decoded.reason)
                }
            }

            val restored = when (
                val result = LearningApplicationMutationRestorationBoundary.restore(
                    liveEntries = liveEntries,
                    completedEntries = completedEntries,
                    highWatermark = encryptedStore.generationHighWatermark()
                )
            ) {
                is LearningApplicationMutationRestorationResult.Restored ->
                    result.state

                is LearningApplicationMutationRestorationResult.Rejected ->
                    return EncryptedPersistentLearningApplicationMutationOpenResult
                        .RestorationFailed(result.reason)
            }

            return EncryptedPersistentLearningApplicationMutationOpenResult.Opened(
                EncryptedPersistentLearningApplicationMutationComposition(
                    foundation = foundation,
                    encryptedStore = encryptedStore,
                    activeDek = activeDek,
                    mutationStore = LearningApplicationMutationStore.restore(
                        observability = foundation.observability,
                        state = restored
                    )
                )
            )
        }
    }
}
