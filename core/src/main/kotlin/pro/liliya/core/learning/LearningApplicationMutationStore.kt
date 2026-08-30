package pro.liliya.core.learning

import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

internal interface LearningApplicationMutationRegistration {
    val plan: LearningApplicationMutationPlan
    val generation: LearningApplicationMutationGeneration
    fun remove(context: LogContext): Boolean
}

internal sealed interface LearningApplicationMutationRegistrationResult {
    data class Registered(
        val registration: LearningApplicationMutationRegistration
    ) : LearningApplicationMutationRegistrationResult

    data class AlreadyCompleted(
        val receipt: LearningApplicationMutationApplicationReceipt
    ) : LearningApplicationMutationRegistrationResult

    data class Rejected(val reason: String) : LearningApplicationMutationRegistrationResult
}

internal sealed interface LearningApplicationMutationPrepareValidation {
    data object Ready : LearningApplicationMutationPrepareValidation
    data class AlreadyCompleted(
        val receipt: LearningApplicationMutationApplicationReceipt
    ) : LearningApplicationMutationPrepareValidation
    data class Rejected(val reason: String) : LearningApplicationMutationPrepareValidation
}

internal interface LearningApplicationMutationClaimRegistration {
    val plan: LearningApplicationMutationPlan
    val generation: LearningApplicationMutationGeneration
    fun release(context: LogContext): Boolean
    fun complete(
        receipt: LearningApplicationMutationApplicationReceipt,
        context: LogContext
    ): Boolean
}

enum class LearningApplicationMutationClaimRejection {
    MUTATION_MISSING,
    MUTATION_GENERATION_MISMATCH,
    ALREADY_CLAIMED
}

internal sealed interface LearningApplicationMutationClaimRegistrationResult {
    data class Claimed(
        val claim: LearningApplicationMutationClaimRegistration
    ) : LearningApplicationMutationClaimRegistrationResult

    data class Rejected(
        val reason: LearningApplicationMutationClaimRejection
    ) : LearningApplicationMutationClaimRegistrationResult
}

internal class LearningApplicationMutationStore private constructor(
    private val observability: CoreObservability,
    initialHighWatermark: Long
) {
    constructor(observability: CoreObservability) : this(observability, 0L)

    private class ClaimToken

    private data class Entry(
        val generation: LearningApplicationMutationGeneration,
        val plan: LearningApplicationMutationPlan,
        var activeClaim: ClaimToken? = null
    )

    private data class CompletedEntry(
        val plan: LearningApplicationMutationPlan,
        val receipt: LearningApplicationMutationApplicationReceipt
    )

    private val lock = Any()
    private val nextGeneration = AtomicLong(initialHighWatermark)
    private val entries = mutableMapOf<LearningApplicationMutationId, Entry>()
    private val idempotency = mutableMapOf<LearningApplicationIdempotencyKey, Entry>()
    private val completedByMutationId = mutableMapOf<LearningApplicationMutationId, CompletedEntry>()
    private val completedByIdempotencyKey = mutableMapOf<LearningApplicationIdempotencyKey, CompletedEntry>()

    fun register(
        plan: LearningApplicationMutationPlan,
        context: LogContext
    ): LearningApplicationMutationRegistrationResult = synchronized(lock) {
        when (val validation = validatePrepareLocked(plan)) {
            LearningApplicationMutationPrepareValidation.Ready -> Unit
            is LearningApplicationMutationPrepareValidation.AlreadyCompleted -> {
                observeAlreadyCompleted(plan, validation.receipt, context)
                return@synchronized LearningApplicationMutationRegistrationResult.AlreadyCompleted(validation.receipt)
            }
            is LearningApplicationMutationPrepareValidation.Rejected ->
                return@synchronized rejected(plan, null, validation.reason, context)
        }

        val nextValue = nextGeneration.incrementAndGet()
        if (nextValue <= 0L) {
            return@synchronized rejected(plan, null, "learning application mutation generation overflow", context)
        }
        val entry = Entry(
            generation = LearningApplicationMutationGeneration(nextValue),
            plan = plan
        )
        installEntry(entry)
        observePrepared(plan, entry.generation, context)
        LearningApplicationMutationRegistrationResult.Registered(registration(entry))
    }

    internal fun validatePrepare(plan: LearningApplicationMutationPlan): LearningApplicationMutationPrepareValidation =
        synchronized(lock) { validatePrepareLocked(plan) }

    internal fun installCommitted(
        plan: LearningApplicationMutationPlan,
        generation: LearningApplicationMutationGeneration,
        highWatermark: Long,
        context: LogContext
    ): LearningApplicationMutationRegistrationResult = synchronized(lock) {
        when (val validation = validatePrepareLocked(plan)) {
            LearningApplicationMutationPrepareValidation.Ready -> Unit
            is LearningApplicationMutationPrepareValidation.AlreadyCompleted -> {
                observeAlreadyCompleted(plan, validation.receipt, context)
                return@synchronized LearningApplicationMutationRegistrationResult.AlreadyCompleted(validation.receipt)
            }
            is LearningApplicationMutationPrepareValidation.Rejected ->
                return@synchronized rejected(plan, generation, validation.reason, context)
        }
        if (highWatermark < generation.value) {
            return@synchronized rejected(
                plan,
                generation,
                "learning application mutation high-watermark is below committed generation",
                context
            )
        }
        if (generation.value != highWatermark) {
            return@synchronized rejected(
                plan,
                generation,
                "learning application mutation committed generation must equal current high-watermark",
                context
            )
        }
        if (highWatermark <= nextGeneration.get()) {
            return@synchronized rejected(
                plan,
                generation,
                "learning application mutation committed high-watermark is not newer than local state",
                context
            )
        }
        if (entries.values.any { it.generation == generation }) {
            return@synchronized rejected(plan, generation, "learning application mutation generation is already live", context)
        }

        val entry = Entry(generation = generation, plan = plan)
        installEntry(entry)
        nextGeneration.set(highWatermark)
        observePrepared(plan, generation, context)
        LearningApplicationMutationRegistrationResult.Registered(registration(entry))
    }

    fun claim(
        reference: LearningApplicationMutationReference,
        context: LogContext
    ): LearningApplicationMutationClaimRegistrationResult = synchronized(lock) {
        val entry = entries[reference.mutationId]
            ?: return@synchronized claimRejected(
                reference,
                LearningApplicationMutationClaimRejection.MUTATION_MISSING,
                context
            )
        if (entry.generation != reference.generation) {
            return@synchronized claimRejected(
                reference,
                LearningApplicationMutationClaimRejection.MUTATION_GENERATION_MISMATCH,
                context
            )
        }
        if (entry.activeClaim != null) {
            return@synchronized claimRejected(
                reference,
                LearningApplicationMutationClaimRejection.ALREADY_CLAIMED,
                context
            )
        }

        val token = ClaimToken()
        entry.activeClaim = token
        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "LEARNING_APPLICATION_MUTATION_CLAIMED",
            message = "learning application mutation claimed",
            context = context,
            metadata = metadata(entry.plan, entry.generation)
        )

        LearningApplicationMutationClaimRegistrationResult.Claimed(
            claim = object : LearningApplicationMutationClaimRegistration {
                override val plan: LearningApplicationMutationPlan = entry.plan
                override val generation: LearningApplicationMutationGeneration = entry.generation

                override fun release(context: LogContext): Boolean = synchronized(lock) {
                    val current = entries[reference.mutationId]
                    val released = current === entry && entry.activeClaim === token
                    if (released) {
                        entry.activeClaim = null
                    }
                    observability.record(
                        severity = if (released) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        code = if (released) {
                            "LEARNING_APPLICATION_MUTATION_CLAIM_RELEASED"
                        } else {
                            "LEARNING_APPLICATION_MUTATION_CLAIM_RELEASE_REJECTED"
                        },
                        message = if (released) {
                            "learning application mutation claim released"
                        } else {
                            "learning application mutation claim is no longer current"
                        },
                        context = context,
                        metadata = metadata(entry.plan, entry.generation)
                    )
                    released
                }

                override fun complete(
                    receipt: LearningApplicationMutationApplicationReceipt,
                    context: LogContext
                ): Boolean = synchronized(lock) {
                    val exactReference = LearningApplicationMutationReference(entry.plan.id, entry.generation)
                    val current = entries[reference.mutationId]
                    val validReceipt = receipt.mutation == exactReference &&
                        receipt.target == entry.plan.target &&
                        downstreamMatchesTarget(receipt.downstream, entry.plan.target)
                    val completed = validReceipt &&
                        current === entry &&
                        entry.activeClaim === token &&
                        !completedByMutationId.containsKey(entry.plan.id) &&
                        !completedByIdempotencyKey.containsKey(entry.plan.idempotencyKey) &&
                        entries.remove(reference.mutationId) === entry
                    if (completed) {
                        idempotency.remove(entry.plan.idempotencyKey, entry)
                        val completedEntry = CompletedEntry(entry.plan, receipt)
                        completedByMutationId[entry.plan.id] = completedEntry
                        completedByIdempotencyKey[entry.plan.idempotencyKey] = completedEntry
                        entry.activeClaim = null
                    }
                    observability.record(
                        severity = if (completed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        code = if (completed) {
                            "LEARNING_APPLICATION_MUTATION_COMPLETED"
                        } else {
                            "LEARNING_APPLICATION_MUTATION_COMPLETION_REJECTED"
                        },
                        message = if (completed) {
                            "learning application mutation completed"
                        } else {
                            "learning application mutation completion is no longer current or receipt is invalid"
                        },
                        context = context,
                        metadata = metadata(entry.plan, entry.generation)
                    )
                    completed
                }
            }
        )
    }

    fun find(id: LearningApplicationMutationId): LearningApplicationMutationPlan? = synchronized(lock) {
        entries[id]?.plan
    }

    fun inspect(id: LearningApplicationMutationId): LearningApplicationMutationSnapshot? = synchronized(lock) {
        entries[id]?.let { LearningApplicationMutationSnapshot(it.plan, it.generation) }
    }

    fun contains(id: LearningApplicationMutationId): Boolean = synchronized(lock) {
        entries.containsKey(id)
    }

    fun findByIdempotencyKey(key: LearningApplicationIdempotencyKey): LearningApplicationMutationPlan? =
        synchronized(lock) { idempotency[key]?.plan }

    fun completedOutcomeByMutationId(
        id: LearningApplicationMutationId
    ): LearningApplicationMutationApplicationReceipt? = synchronized(lock) {
        completedByMutationId[id]?.receipt
    }

    fun completedOutcomeByIdempotencyKey(
        key: LearningApplicationIdempotencyKey
    ): LearningApplicationMutationApplicationReceipt? = synchronized(lock) {
        completedByIdempotencyKey[key]?.receipt
    }

    fun isCompletedIdempotencyKey(key: LearningApplicationIdempotencyKey): Boolean =
        synchronized(lock) { completedByIdempotencyKey.containsKey(key) }

    fun snapshot(): List<LearningApplicationMutationPlan> = snapshotEntries().map { it.plan }

    fun snapshotEntries(): List<LearningApplicationMutationSnapshot> = synchronized(lock) {
        entries.values
            .map { LearningApplicationMutationSnapshot(it.plan, it.generation) }
            .sortedWith(
                compareBy<LearningApplicationMutationSnapshot> { it.plan.createdAt }
                    .thenBy { it.plan.id.value }
            )
    }

    private fun validatePrepareLocked(plan: LearningApplicationMutationPlan): LearningApplicationMutationPrepareValidation {
        if (entries.containsKey(plan.id)) {
            return LearningApplicationMutationPrepareValidation.Rejected(
                "learning application mutation id is already registered"
            )
        }
        val completedById = completedByMutationId[plan.id]
        if (completedById != null) {
            return if (completedById.plan == plan) {
                LearningApplicationMutationPrepareValidation.AlreadyCompleted(completedById.receipt)
            } else {
                LearningApplicationMutationPrepareValidation.Rejected(
                    "learning application mutation id is already completed"
                )
            }
        }
        if (idempotency.containsKey(plan.idempotencyKey)) {
            return LearningApplicationMutationPrepareValidation.Rejected(
                "learning application idempotency key is already reserved"
            )
        }
        val completedByKey = completedByIdempotencyKey[plan.idempotencyKey]
        if (completedByKey != null) {
            return if (completedByKey.plan == plan) {
                LearningApplicationMutationPrepareValidation.AlreadyCompleted(completedByKey.receipt)
            } else {
                LearningApplicationMutationPrepareValidation.Rejected(
                    "learning application idempotency key is already completed"
                )
            }
        }
        return LearningApplicationMutationPrepareValidation.Ready
    }

    private fun installEntry(entry: Entry) {
        entries[entry.plan.id] = entry
        idempotency[entry.plan.idempotencyKey] = entry
    }

    private fun registration(entry: Entry): LearningApplicationMutationRegistration =
        object : LearningApplicationMutationRegistration {
            override val plan: LearningApplicationMutationPlan = entry.plan
            override val generation: LearningApplicationMutationGeneration = entry.generation

            override fun remove(context: LogContext): Boolean = synchronized(lock) {
                val current = entries[entry.plan.id]
                val removed = current === entry && entry.activeClaim == null && entries.remove(entry.plan.id) === entry
                if (removed) {
                    idempotency.remove(entry.plan.idempotencyKey, entry)
                }
                observability.record(
                    severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                    code = if (removed) {
                        "LEARNING_APPLICATION_MUTATION_REMOVED"
                    } else {
                        "LEARNING_APPLICATION_MUTATION_REMOVAL_REJECTED"
                    },
                    message = if (removed) {
                        "learning application mutation removed"
                    } else if (current === entry && entry.activeClaim != null) {
                        "learning application mutation is actively claimed"
                    } else {
                        "learning application mutation registration is no longer current"
                    },
                    context = context,
                    metadata = metadata(entry.plan, entry.generation)
                )
                removed
            }
        }

    private fun observePrepared(
        plan: LearningApplicationMutationPlan,
        generation: LearningApplicationMutationGeneration,
        context: LogContext
    ) {
        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "LEARNING_APPLICATION_MUTATION_PREPARED",
            message = "learning application mutation prepared",
            context = context,
            metadata = metadata(plan, generation)
        )
    }

    private fun observeAlreadyCompleted(
        plan: LearningApplicationMutationPlan,
        receipt: LearningApplicationMutationApplicationReceipt,
        context: LogContext
    ) {
        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "LEARNING_APPLICATION_MUTATION_ALREADY_COMPLETED",
            message = "learning application mutation is already completed",
            context = context,
            metadata = metadata(plan, receipt.mutation.generation)
        )
    }

    private fun rejected(
        plan: LearningApplicationMutationPlan,
        generation: LearningApplicationMutationGeneration?,
        reason: String,
        context: LogContext
    ): LearningApplicationMutationRegistrationResult.Rejected {
        observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "LEARNING_APPLICATION_MUTATION_PREPARATION_REJECTED",
            message = reason,
            context = context,
            metadata = metadata(plan, generation) + ("rejectionReason" to reason)
        )
        return LearningApplicationMutationRegistrationResult.Rejected(reason)
    }

    private fun claimRejected(
        reference: LearningApplicationMutationReference,
        reason: LearningApplicationMutationClaimRejection,
        context: LogContext
    ): LearningApplicationMutationClaimRegistrationResult.Rejected {
        observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "LEARNING_APPLICATION_MUTATION_CLAIM_REJECTED",
            message = "learning application mutation claim rejected",
            context = context,
            metadata = mapOf(
                "learningApplicationMutationId" to reference.mutationId.value,
                "learningApplicationMutationGeneration" to reference.generation.value.toString(),
                "rejectionReason" to reason.name.lowercase()
            )
        )
        return LearningApplicationMutationClaimRegistrationResult.Rejected(reason)
    }

    private fun downstreamMatchesTarget(
        downstream: LearningApplicationDownstreamReference,
        target: LearningApplicationTarget
    ): Boolean = when (target) {
        LearningApplicationTarget.MEMORY -> downstream is LearningApplicationDownstreamReference.Memory
        LearningApplicationTarget.KNOWLEDGE -> downstream is LearningApplicationDownstreamReference.Knowledge
    }

    private fun metadata(
        plan: LearningApplicationMutationPlan,
        generation: LearningApplicationMutationGeneration?
    ): Map<String, String> = buildMap {
        put("learningApplicationMutationId", plan.id.value)
        generation?.let { put("learningApplicationMutationGeneration", it.value.toString()) }
        put("learningApplicationId", plan.application.applicationId.value)
        put("learningApplicationGeneration", plan.application.generation.value.toString())
        put("authorityPrincipal", plan.principal.value)
        put("learningApplicationTarget", plan.target.name.lowercase())
        put("idempotencyKey", plan.idempotencyKey.value)
        put("createdAt", plan.createdAt.toString())
        when (val payload = plan.payload) {
            is LearningApplicationMutationPayload.Memory -> put("memoryRecordId", payload.record.id.value)
            is LearningApplicationMutationPayload.Knowledge -> put("knowledgeItemId", payload.item.id.value)
        }
    }

    companion object {
        internal fun restore(
            observability: CoreObservability,
            state: LearningApplicationMutationRestoredState
        ): LearningApplicationMutationStore {
            val store = LearningApplicationMutationStore(observability, state.highWatermark)
            synchronized(store.lock) {
                state.liveEntries.forEach { restored ->
                    val entry = Entry(restored.generation, restored.plan)
                    store.installEntry(entry)
                }
                state.completedEntries.forEach { restored ->
                    val completed = CompletedEntry(restored.plan, restored.receipt)
                    store.completedByMutationId[restored.plan.id] = completed
                    store.completedByIdempotencyKey[restored.plan.idempotencyKey] = completed
                }
            }
            return store
        }
    }
}
