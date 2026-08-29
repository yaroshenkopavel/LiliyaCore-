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

    data class Rejected(val reason: String) : LearningApplicationMutationRegistrationResult
}

internal interface LearningApplicationMutationClaimRegistration {
    val plan: LearningApplicationMutationPlan
    val generation: LearningApplicationMutationGeneration
    fun release(context: LogContext): Boolean
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

internal class LearningApplicationMutationStore(
    private val observability: CoreObservability
) {
    private class ClaimToken

    private data class Entry(
        val generation: LearningApplicationMutationGeneration,
        val plan: LearningApplicationMutationPlan,
        var activeClaim: ClaimToken? = null
    )

    private val lock = Any()
    private val nextGeneration = AtomicLong(0)
    private val entries = mutableMapOf<LearningApplicationMutationId, Entry>()
    private val idempotency = mutableMapOf<LearningApplicationIdempotencyKey, Entry>()

    fun register(
        plan: LearningApplicationMutationPlan,
        context: LogContext
    ): LearningApplicationMutationRegistrationResult = synchronized(lock) {
        if (entries.containsKey(plan.id)) {
            return@synchronized rejected(plan, null, "learning application mutation id is already registered", context)
        }
        if (idempotency.containsKey(plan.idempotencyKey)) {
            return@synchronized rejected(plan, null, "learning application idempotency key is already registered", context)
        }

        val entry = Entry(
            generation = LearningApplicationMutationGeneration(nextGeneration.incrementAndGet()),
            plan = plan
        )
        entries[plan.id] = entry
        idempotency[plan.idempotencyKey] = entry

        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "LEARNING_APPLICATION_MUTATION_PREPARED",
            message = "learning application mutation prepared",
            context = context,
            metadata = metadata(plan, entry.generation)
        )

        LearningApplicationMutationRegistrationResult.Registered(
            registration = object : LearningApplicationMutationRegistration {
                override val plan: LearningApplicationMutationPlan = plan
                override val generation: LearningApplicationMutationGeneration = entry.generation

                override fun remove(context: LogContext): Boolean = synchronized(lock) {
                    val current = entries[plan.id]
                    val removed = current === entry && entry.activeClaim == null && entries.remove(plan.id) === entry
                    if (removed) {
                        idempotency.remove(plan.idempotencyKey, entry)
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
                        metadata = metadata(plan, entry.generation)
                    )
                    removed
                }
            }
        )
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

    fun snapshot(): List<LearningApplicationMutationPlan> = snapshotEntries().map { it.plan }

    fun snapshotEntries(): List<LearningApplicationMutationSnapshot> = synchronized(lock) {
        entries.values
            .map { LearningApplicationMutationSnapshot(it.plan, it.generation) }
            .sortedWith(
                compareBy<LearningApplicationMutationSnapshot> { it.plan.createdAt }
                    .thenBy { it.plan.id.value }
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
}
