package pro.liliya.core.trust

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

internal interface TrustAnchorRegistration {
    val anchor: TrustAnchor
    val generation: TrustGeneration
    fun remove(context: LogContext): Boolean
}

internal sealed interface TrustAnchorRegistrationResult {
    data class Registered(val registration: TrustAnchorRegistration) : TrustAnchorRegistrationResult
    data class Rejected(val reason: String) : TrustAnchorRegistrationResult
}

internal class TrustAnchorStore(
    private val observability: CoreObservability
) {
    private data class Entry(
        val generation: TrustGeneration,
        val anchor: TrustAnchor
    )

    private val nextGeneration = AtomicLong(0)
    private val anchors = ConcurrentHashMap<TrustAnchorId, Entry>()

    fun register(anchor: TrustAnchor, context: LogContext): TrustAnchorRegistrationResult {
        val entry = Entry(
            generation = TrustGeneration(nextGeneration.incrementAndGet()),
            anchor = anchor
        )
        val previous = anchors.putIfAbsent(anchor.id, entry)
        if (previous != null) {
            val reason = "trust anchor id is already registered"
            observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "TRUST_ANCHOR_REGISTRATION_REJECTED",
                message = reason,
                context = context,
                metadata = metadata(anchor, entry.generation) + ("rejectionReason" to reason)
            )
            return TrustAnchorRegistrationResult.Rejected(reason)
        }

        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "TRUST_ANCHOR_REGISTERED",
            message = "trust anchor registered",
            context = context,
            metadata = metadata(anchor, entry.generation)
        )

        return TrustAnchorRegistrationResult.Registered(
            registration = object : TrustAnchorRegistration {
                override val anchor: TrustAnchor = anchor
                override val generation: TrustGeneration = entry.generation

                override fun remove(context: LogContext): Boolean {
                    val removed = anchors.remove(anchor.id, entry)
                    observability.record(
                        severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        code = if (removed) "TRUST_ANCHOR_REMOVED" else "TRUST_ANCHOR_REMOVAL_REJECTED",
                        message = if (removed) {
                            "trust anchor removed"
                        } else {
                            "trust anchor registration is no longer current"
                        },
                        context = context,
                        metadata = metadata(anchor, entry.generation)
                    )
                    return removed
                }
            }
        )
    }

    fun find(id: TrustAnchorId): TrustAnchor? = anchors[id]?.anchor

    fun inspect(id: TrustAnchorId): TrustAnchorSnapshot? = anchors[id]?.let { entry ->
        TrustAnchorSnapshot(anchor = entry.anchor, generation = entry.generation)
    }

    fun contains(id: TrustAnchorId): Boolean = anchors.containsKey(id)

    fun snapshot(): List<TrustAnchor> = snapshotEntries().map { it.anchor }

    fun snapshotEntries(): List<TrustAnchorSnapshot> = anchors.values
        .map { entry -> TrustAnchorSnapshot(entry.anchor, entry.generation) }
        .sortedWith(compareBy<TrustAnchorSnapshot> { it.anchor.createdAt }.thenBy { it.anchor.id.value })

    private fun metadata(
        anchor: TrustAnchor,
        generation: TrustGeneration
    ): Map<String, String> = buildMap {
        put("trustAnchorId", anchor.id.value)
        put("trustGeneration", generation.value.toString())
        put("createdAt", anchor.createdAt.toString())
        put("trustSourceId", anchor.provenance.sourceId.value)
        anchor.provenance.sourceReference?.let { reference ->
            put("trustSourceReference", reference.value)
        }
        when (val subject = anchor.subject) {
            is TrustSubject.Self -> {
                put("trustSubjectType", "self")
                put("selfIdentityId", subject.identityId.value)
                put("selfGeneration", subject.generation.value.toString())
            }

            is TrustSubject.Declared -> {
                put("trustSubjectType", "declared")
                put("trustSubjectId", subject.subjectId.value)
            }
        }
    }
}
