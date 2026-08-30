package pro.liliya.core.learning

import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentInstallResult
import pro.liliya.core.persistence.PersistentMutationResult
import pro.liliya.core.persistence.PersistentRecordBackend
import pro.liliya.core.persistence.PersistentRecordOwnership
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentRecordTransitionResult
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.persistence.PersistentStoreOpenResult

interface PersistentLearningApplicationMutationOwnership {
    val plan: LearningApplicationMutationPlan
    val generation: LearningApplicationMutationGeneration
    fun remove(): PersistentLearningApplicationMutationResult
}

class PersistentLearningApplicationMutationClaim internal constructor(
    val plan: LearningApplicationMutationPlan,
    val reference: LearningApplicationMutationReference,
    private val releaseAction: () -> Boolean,
    private val completeAction: (LearningApplicationMutationApplicationReceipt) ->
        PersistentLearningApplicationMutationResult
) {
    fun release(): Boolean = releaseAction()

    /** Durable completion remains controlled/internal; a public claim is not completion authority. */
    internal fun complete(
        receipt: LearningApplicationMutationApplicationReceipt
    ): PersistentLearningApplicationMutationResult = completeAction(receipt)
}

sealed interface PersistentLearningApplicationMutationPrepareResult {
    data class Prepared(
        val ownership: PersistentLearningApplicationMutationOwnership
    ) : PersistentLearningApplicationMutationPrepareResult

    data class AlreadyCompleted(
        val receipt: LearningApplicationMutationApplicationReceipt
    ) : PersistentLearningApplicationMutationPrepareResult

    data class Rejected(val reason: String) : PersistentLearningApplicationMutationPrepareResult

    data class Failed(val reason: String, val throwable: Throwable? = null) :
        PersistentLearningApplicationMutationPrepareResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

sealed interface PersistentLearningApplicationMutationClaimResult {
    data class Claimed(
        val claim: PersistentLearningApplicationMutationClaim
    ) : PersistentLearningApplicationMutationClaimResult

    data class Rejected(
        val reason: LearningApplicationMutationClaimRejection
    ) : PersistentLearningApplicationMutationClaimResult
}

sealed interface PersistentLearningApplicationMutationResult {
    data object Committed : PersistentLearningApplicationMutationResult
    data class Rejected(val reason: String) : PersistentLearningApplicationMutationResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        PersistentLearningApplicationMutationResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

sealed interface PersistentLearningApplicationMutationOpenResult {
    data class Opened(val composition: PersistentLearningApplicationMutationComposition) :
        PersistentLearningApplicationMutationOpenResult

    data object Corrupt : PersistentLearningApplicationMutationOpenResult
    data class Incompatible(val reason: String) : PersistentLearningApplicationMutationOpenResult
    data class RestorationFailed(val reason: String) : PersistentLearningApplicationMutationOpenResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        PersistentLearningApplicationMutationOpenResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

class PersistentLearningApplicationMutationComposition private constructor(
    private val foundation: FoundationComposition,
    private val persistentStore: PersistentRecordStore,
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
                return PersistentLearningApplicationMutationPrepareResult.AlreadyCompleted(validation.receipt)
            is LearningApplicationMutationPrepareValidation.Rejected ->
                return PersistentLearningApplicationMutationPrepareResult.Rejected(validation.reason)
        }

        val encoded = LearningApplicationMutationPersistentCodec.encodePrepared(plan)
        return when (val durable = persistentStore.install(encoded)) {
            is PersistentInstallResult.Installed -> installCommitted(plan, durable.ownership)
            is PersistentInstallResult.Rejected ->
                PersistentLearningApplicationMutationPrepareResult.Rejected(durable.reason)
            is PersistentInstallResult.Failed ->
                PersistentLearningApplicationMutationPrepareResult.Failed(
                    reason = "persistent learning mutation durable prepare failed",
                    throwable = durable.throwable
                )
        }
    }

    @Synchronized
    fun claim(
        reference: LearningApplicationMutationReference
    ): PersistentLearningApplicationMutationClaimResult {
        val context = foundation.rootContext(
            operation = "claimPersistedLearningApplicationMutation",
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
                            synchronized(this@PersistentLearningApplicationMutationComposition) {
                                val released = registration.release(
                                    foundation.rootContext(
                                        operation = "releasePersistedLearningApplicationMutationClaim",
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

    fun find(id: LearningApplicationMutationId): LearningApplicationMutationPlan? = mutationStore.find(id)

    fun inspect(id: LearningApplicationMutationId): LearningApplicationMutationSnapshot? = mutationStore.inspect(id)

    fun contains(id: LearningApplicationMutationId): Boolean = mutationStore.contains(id)

    fun findByIdempotencyKey(key: LearningApplicationIdempotencyKey): LearningApplicationMutationPlan? =
        mutationStore.findByIdempotencyKey(key)

    fun completedOutcomeByMutationId(
        id: LearningApplicationMutationId
    ): LearningApplicationMutationApplicationReceipt? = mutationStore.completedOutcomeByMutationId(id)

    fun completedOutcomeByIdempotencyKey(
        key: LearningApplicationIdempotencyKey
    ): LearningApplicationMutationApplicationReceipt? = mutationStore.completedOutcomeByIdempotencyKey(key)

    fun isCompletedIdempotencyKey(key: LearningApplicationIdempotencyKey): Boolean =
        mutationStore.isCompletedIdempotencyKey(key)

    fun snapshot(): List<LearningApplicationMutationPlan> = mutationStore.snapshot()

    fun snapshotEntries(): List<LearningApplicationMutationSnapshot> = mutationStore.snapshotEntries()

    @Synchronized
    private fun completeClaim(
        registration: LearningApplicationMutationClaimRegistration,
        exactReference: LearningApplicationMutationReference,
        receipt: LearningApplicationMutationApplicationReceipt
    ): PersistentLearningApplicationMutationResult {
        if (exactReference !in activeClaims) {
            return PersistentLearningApplicationMutationResult.Rejected(
                "persistent learning mutation claim is no longer active"
            )
        }
        if (receipt.mutation != exactReference) {
            return PersistentLearningApplicationMutationResult.Rejected(
                "persistent learning mutation completion receipt reference mismatch"
            )
        }
        if (receipt.target != registration.plan.target ||
            !downstreamMatchesTarget(receipt.downstream, registration.plan.target)
        ) {
            return PersistentLearningApplicationMutationResult.Rejected(
                "persistent learning mutation completion receipt target mismatch"
            )
        }

        val completedRecord = try {
            LearningApplicationMutationPersistentCodec.encodeCompleted(registration.plan, receipt)
        } catch (_: IllegalArgumentException) {
            return PersistentLearningApplicationMutationResult.Rejected(
                "persistent learning mutation completion receipt is invalid"
            )
        }
        val sourceId = PersistentEntityId("learning-mutation:prepared:${registration.plan.id.value}")
        val sourceGeneration = PersistentGeneration(registration.generation.value)

        return when (
            val durable = persistentStore.transitionExact(
                sourceId = sourceId,
                sourceGeneration = sourceGeneration,
                replacement = completedRecord
            )
        ) {
            is PersistentRecordTransitionResult.Committed -> {
                val completedLocally = registration.complete(
                    receipt = receipt,
                    context = foundation.rootContext(
                        operation = "completePersistedLearningApplicationMutation",
                        component = "LearningApplicationMutation",
                        metadata = metadata(registration.plan, registration.generation)
                    )
                )
                if (completedLocally) {
                    activeClaims.remove(exactReference)
                    PersistentLearningApplicationMutationResult.Committed
                } else {
                    PersistentLearningApplicationMutationResult.Failed(
                        "durable learning mutation completion committed but local exact completion failed"
                    )
                }
            }

            is PersistentRecordTransitionResult.Rejected ->
                PersistentLearningApplicationMutationResult.Rejected(durable.reason)

            is PersistentRecordTransitionResult.Failed ->
                PersistentLearningApplicationMutationResult.Failed(
                    reason = "persistent learning mutation durable completion failed",
                    throwable = durable.throwable
                )
        }
    }

    private fun installCommitted(
        plan: LearningApplicationMutationPlan,
        persistentOwnership: PersistentRecordOwnership
    ): PersistentLearningApplicationMutationPrepareResult {
        val generation = LearningApplicationMutationGeneration(persistentOwnership.generation.value)
        val context = foundation.rootContext(
            operation = "preparePersistedLearningApplicationMutation",
            component = "LearningApplicationMutation",
            metadata = metadata(plan, generation)
        )
        return when (
            val local = mutationStore.installCommitted(
                plan = plan,
                generation = generation,
                highWatermark = persistentStore.generationHighWatermark(),
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
                    PersistentLearningApplicationMutationPrepareResult.AlreadyCompleted(local.receipt)
                } else {
                    PersistentLearningApplicationMutationPrepareResult.Failed(
                        "local learning mutation was already completed after durable prepare; durable compensation failed"
                    )
                }
            }

            is LearningApplicationMutationRegistrationResult.Rejected -> {
                val compensated = persistentOwnership.remove()
                val reason = if (compensated is PersistentMutationResult.Committed) {
                    "local committed learning mutation install rejected after durable prepare; durable candidate compensated"
                } else {
                    "local committed learning mutation install rejected after durable prepare; durable compensation failed"
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
            override val plan: LearningApplicationMutationPlan = localRegistration.plan
            override val generation: LearningApplicationMutationGeneration = localRegistration.generation

            override fun remove(): PersistentLearningApplicationMutationResult =
                synchronized(this@PersistentLearningApplicationMutationComposition) {
                    val reference = LearningApplicationMutationReference(plan.id, generation)
                    if (reference in activeClaims) {
                        return@synchronized PersistentLearningApplicationMutationResult.Rejected(
                            "persistent learning mutation is actively claimed"
                        )
                    }
                    when (val durable = persistentOwnership.remove()) {
                        PersistentMutationResult.Committed -> {
                            val removedLocally = localRegistration.remove(
                                foundation.rootContext(
                                    operation = "removePersistedLearningApplicationMutation",
                                    component = "LearningApplicationMutation",
                                    metadata = metadata(plan, generation)
                                )
                            )
                            if (removedLocally) {
                                PersistentLearningApplicationMutationResult.Committed
                            } else {
                                PersistentLearningApplicationMutationResult.Failed(
                                    "durable learning mutation removal committed but local exact removal failed"
                                )
                            }
                        }

                        is PersistentMutationResult.Rejected ->
                            PersistentLearningApplicationMutationResult.Rejected(durable.reason)

                        is PersistentMutationResult.Failed ->
                            PersistentLearningApplicationMutationResult.Failed(
                                reason = "persistent learning mutation durable removal failed",
                                throwable = durable.throwable
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

    private fun downstreamMatchesTarget(
        downstream: LearningApplicationDownstreamReference,
        target: LearningApplicationTarget
    ): Boolean = when (target) {
        LearningApplicationTarget.MEMORY -> downstream is LearningApplicationDownstreamReference.Memory
        LearningApplicationTarget.KNOWLEDGE -> downstream is LearningApplicationDownstreamReference.Knowledge
    }

    private fun metadata(
        plan: LearningApplicationMutationPlan,
        generation: LearningApplicationMutationGeneration
    ): Map<String, String> = buildMap {
        put("learningApplicationMutationId", plan.id.value)
        put("learningApplicationMutationGeneration", generation.value.toString())
        put("learningApplicationId", plan.application.applicationId.value)
        put("learningApplicationGeneration", plan.application.generation.value.toString())
        put("learningApplicationTarget", plan.target.name.lowercase())
        put("idempotencyKey", plan.idempotencyKey.value)
        put("createdAt", plan.createdAt.toString())
        when (val payload = plan.payload) {
            is LearningApplicationMutationPayload.Memory -> put("memoryRecordId", payload.record.id.value)
            is LearningApplicationMutationPayload.Knowledge -> put("knowledgeItemId", payload.item.id.value)
        }
    }

    companion object {
        fun open(
            foundation: FoundationComposition,
            storeId: PersistentStoreId,
            backend: PersistentRecordBackend
        ): PersistentLearningApplicationMutationOpenResult = when (
            val opened = PersistentRecordStore.open(foundation, storeId, backend)
        ) {
            is PersistentStoreOpenResult.Opened -> restoreOpened(foundation, opened.store)
            PersistentStoreOpenResult.Corrupt -> PersistentLearningApplicationMutationOpenResult.Corrupt
            is PersistentStoreOpenResult.Incompatible ->
                PersistentLearningApplicationMutationOpenResult.Incompatible(opened.reason)
            is PersistentStoreOpenResult.Failed ->
                PersistentLearningApplicationMutationOpenResult.Failed(
                    reason = "persistent learning mutation backend open failed",
                    throwable = opened.throwable
                )
        }

        private fun restoreOpened(
            foundation: FoundationComposition,
            persistentStore: PersistentRecordStore
        ): PersistentLearningApplicationMutationOpenResult {
            val liveEntries = mutableListOf<LearningPreparedPersistentState>()
            val completedEntries = mutableListOf<LearningCompletedPersistentState>()

            for (snapshot in persistentStore.snapshotEntries()) {
                when (val decoded = LearningApplicationMutationPersistentCodec.decode(snapshot.record)) {
                    is LearningApplicationMutationPersistentDecodeResult.Prepared -> {
                        val generation = try {
                            LearningApplicationMutationGeneration(snapshot.generation.value)
                        } catch (_: IllegalArgumentException) {
                            return PersistentLearningApplicationMutationOpenResult.Corrupt
                        }
                        liveEntries += LearningPreparedPersistentState(decoded.plan, generation)
                    }

                    is LearningApplicationMutationPersistentDecodeResult.Completed -> {
                        val generation = try {
                            LearningApplicationMutationGeneration(snapshot.generation.value)
                        } catch (_: IllegalArgumentException) {
                            return PersistentLearningApplicationMutationOpenResult.Corrupt
                        }
                        if (decoded.receipt.mutation.generation != generation) {
                            return PersistentLearningApplicationMutationOpenResult.RestorationFailed(
                                "completed learning receipt generation does not match persistent generation"
                            )
                        }
                        completedEntries += LearningCompletedPersistentState(decoded.plan, decoded.receipt)
                    }

                    LearningApplicationMutationPersistentDecodeResult.Corrupt ->
                        return PersistentLearningApplicationMutationOpenResult.Corrupt

                    is LearningApplicationMutationPersistentDecodeResult.Incompatible ->
                        return PersistentLearningApplicationMutationOpenResult.Incompatible(decoded.reason)
                }
            }

            val restored = when (
                val result = LearningApplicationMutationRestorationBoundary.restore(
                    liveEntries = liveEntries,
                    completedEntries = completedEntries,
                    highWatermark = persistentStore.generationHighWatermark()
                )
            ) {
                is LearningApplicationMutationRestorationResult.Restored -> result.state
                is LearningApplicationMutationRestorationResult.Rejected ->
                    return PersistentLearningApplicationMutationOpenResult.RestorationFailed(result.reason)
            }

            return PersistentLearningApplicationMutationOpenResult.Opened(
                PersistentLearningApplicationMutationComposition(
                    foundation = foundation,
                    persistentStore = persistentStore,
                    mutationStore = LearningApplicationMutationStore.restore(
                        observability = foundation.observability,
                        state = restored
                    )
                )
            )
        }
    }
}
