package pro.liliya.core.autonomy

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

internal interface AutonomyDeliberationRegistration {
    val request: AutonomyDeliberationRequest
    val generation: AutonomyDeliberationGeneration
    fun remove(context: LogContext): Boolean
}

internal sealed interface AutonomyDeliberationRegistrationResult {
    data class Registered(val registration: AutonomyDeliberationRegistration) : AutonomyDeliberationRegistrationResult
    data class Rejected(val reason: String) : AutonomyDeliberationRegistrationResult
}

internal class AutonomyDeliberationStore(
    private val observability: CoreObservability
) {
    private data class Entry(
        val generation: AutonomyDeliberationGeneration,
        val request: AutonomyDeliberationRequest
    )

    private val nextGeneration = AtomicLong(0)
    private val requests = ConcurrentHashMap<AutonomyDeliberationRequestId, Entry>()

    fun register(
        request: AutonomyDeliberationRequest,
        context: LogContext
    ): AutonomyDeliberationRegistrationResult {
        val entry = Entry(
            generation = AutonomyDeliberationGeneration(nextGeneration.incrementAndGet()),
            request = request
        )
        val existing = requests.putIfAbsent(request.id, entry)
        if (existing != null) {
            val reason = "autonomy deliberation request id is already registered"
            observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "AUTONOMY_DELIBERATION_REQUEST_REGISTRATION_REJECTED",
                message = reason,
                context = context,
                metadata = metadata(request, existing.generation) + ("rejectionReason" to reason)
            )
            return AutonomyDeliberationRegistrationResult.Rejected(reason)
        }

        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "AUTONOMY_DELIBERATION_REQUEST_REGISTERED",
            message = "autonomy deliberation request registered",
            context = context,
            metadata = metadata(request, entry.generation)
        )

        return AutonomyDeliberationRegistrationResult.Registered(
            object : AutonomyDeliberationRegistration {
                override val request: AutonomyDeliberationRequest = request
                override val generation: AutonomyDeliberationGeneration = entry.generation

                override fun remove(context: LogContext): Boolean {
                    val removed = requests.remove(request.id, entry)
                    observability.record(
                        severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        code = if (removed) {
                            "AUTONOMY_DELIBERATION_REQUEST_REMOVED"
                        } else {
                            "AUTONOMY_DELIBERATION_REQUEST_REMOVAL_REJECTED"
                        },
                        message = if (removed) {
                            "autonomy deliberation request removed"
                        } else {
                            "autonomy deliberation registration is no longer current"
                        },
                        context = context,
                        metadata = metadata(request, entry.generation)
                    )
                    return removed
                }
            }
        )
    }

    fun find(id: AutonomyDeliberationRequestId): AutonomyDeliberationRequest? = requests[id]?.request

    fun inspect(id: AutonomyDeliberationRequestId): AutonomyDeliberationSnapshot? = requests[id]?.let {
        AutonomyDeliberationSnapshot(it.request, it.generation)
    }

    fun snapshot(): List<AutonomyDeliberationRequest> = requests.values
        .map { it.request }
        .sortedWith(compareBy<AutonomyDeliberationRequest>({ it.createdAt }, { it.id.value }))
        .toList()

    private fun metadata(
        request: AutonomyDeliberationRequest,
        generation: AutonomyDeliberationGeneration
    ): Map<String, String> = mapOf(
        "autonomyDeliberationRequestId" to request.id.value,
        "autonomyDeliberationGeneration" to generation.value.toString(),
        "autonomyProposalId" to request.autonomy.proposalId.value,
        "autonomyGeneration" to request.autonomy.proposalGeneration.value.toString(),
        "autonomyAttemptNumber" to request.autonomy.attemptNumber.toString(),
        "createdAt" to request.createdAt.toString()
    )
}
