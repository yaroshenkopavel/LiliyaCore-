package pro.liliya.core.identity

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

internal interface SelfRegistration {
    val identity: SelfIdentity
    val generation: SelfGeneration
    fun remove(context: LogContext): Boolean
}

internal sealed interface SelfRegistrationResult {
    data class Registered(val registration: SelfRegistration) : SelfRegistrationResult
    data class Rejected(val reason: String) : SelfRegistrationResult
}

internal class SelfStore(
    private val observability: CoreObservability
) {
    private data class Entry(
        val generation: SelfGeneration,
        val identity: SelfIdentity
    )

    private val nextGeneration = AtomicLong(0)
    private val current = AtomicReference<Entry?>(null)

    fun register(identity: SelfIdentity, context: LogContext): SelfRegistrationResult {
        val entry = Entry(
            generation = SelfGeneration(nextGeneration.incrementAndGet()),
            identity = identity
        )
        if (!current.compareAndSet(null, entry)) {
            val reason = "self identity is already registered"
            observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "SELF_REGISTRATION_REJECTED",
                message = reason,
                context = context,
                metadata = metadata(identity, entry.generation) + ("rejectionReason" to reason)
            )
            return SelfRegistrationResult.Rejected(reason)
        }

        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "SELF_REGISTERED",
            message = "self identity registered",
            context = context,
            metadata = metadata(identity, entry.generation)
        )

        return SelfRegistrationResult.Registered(
            registration = object : SelfRegistration {
                override val identity: SelfIdentity = identity
                override val generation: SelfGeneration = entry.generation

                override fun remove(context: LogContext): Boolean {
                    val removed = current.compareAndSet(entry, null)
                    observability.record(
                        severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        code = if (removed) "SELF_REMOVED" else "SELF_REMOVAL_REJECTED",
                        message = if (removed) {
                            "self identity removed"
                        } else {
                            "self registration is no longer current"
                        },
                        context = context,
                        metadata = metadata(identity, entry.generation)
                    )
                    return removed
                }
            }
        )
    }

    fun current(): SelfIdentity? = current.get()?.identity

    fun inspect(): SelfIdentitySnapshot? = current.get()?.let { entry ->
        SelfIdentitySnapshot(identity = entry.identity, generation = entry.generation)
    }

    fun isPresent(): Boolean = current.get() != null

    private fun metadata(
        identity: SelfIdentity,
        generation: SelfGeneration
    ): Map<String, String> = buildMap {
        put("selfIdentityId", identity.id.value)
        put("selfGeneration", generation.value.toString())
        put("createdAt", identity.createdAt.toString())
        when (val origin = identity.origin) {
            is SelfOrigin.Knowledge -> {
                put("selfOriginType", "knowledge")
                put("knowledgeItemId", origin.itemId.value)
                put("knowledgeGeneration", origin.generation.value.toString())
            }

            is SelfOrigin.Declared -> {
                put("selfOriginType", "declared")
                put("selfSourceId", origin.sourceId.value)
                origin.sourceReference?.let { reference ->
                    put("selfSourceReference", reference.value)
                }
            }
        }
    }
}
