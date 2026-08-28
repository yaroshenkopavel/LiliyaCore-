package pro.liliya.core.capability

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

interface CapabilityRegistration {
    val capabilityId: CapabilityId
    val providerId: CapabilityProviderId
    fun unregister(context: LogContext): Boolean
}

sealed interface CapabilityRegistrationResult {
    data class Registered(val registration: CapabilityRegistration) : CapabilityRegistrationResult
    data class Rejected(val reason: String) : CapabilityRegistrationResult
}

class CapabilityRegistry(
    private val observability: CoreObservability
) {
    private data class Entry(
        val token: Long,
        val descriptor: CapabilityDescriptor
    )

    private val nextToken = AtomicLong(0)
    private val capabilities = ConcurrentHashMap<CapabilityId, Entry>()

    fun register(
        descriptor: CapabilityDescriptor,
        context: LogContext
    ): CapabilityRegistrationResult {
        val entry = Entry(
            token = nextToken.incrementAndGet(),
            descriptor = descriptor
        )
        val previous = capabilities.putIfAbsent(descriptor.id, entry)
        if (previous != null) {
            val reason = "capability already registered: ${descriptor.id}"
            record(
                severity = DiagnosticSeverity.WARNING,
                code = "CAPABILITY_REGISTRATION_REJECTED",
                message = reason,
                descriptor = descriptor,
                context = context
            )
            return CapabilityRegistrationResult.Rejected(reason)
        }

        record(
            severity = DiagnosticSeverity.INFO,
            code = "CAPABILITY_REGISTERED",
            message = "capability registered",
            descriptor = descriptor,
            context = context
        )

        return CapabilityRegistrationResult.Registered(
            registration = object : CapabilityRegistration {
                override val capabilityId: CapabilityId = descriptor.id
                override val providerId: CapabilityProviderId = descriptor.providerId

                override fun unregister(context: LogContext): Boolean {
                    val removed = capabilities.remove(descriptor.id, entry)
                    record(
                        severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        code = if (removed) {
                            "CAPABILITY_UNREGISTERED"
                        } else {
                            "CAPABILITY_UNREGISTER_REJECTED"
                        },
                        message = if (removed) {
                            "capability unregistered"
                        } else {
                            "capability registration is no longer current"
                        },
                        descriptor = descriptor,
                        context = context
                    )
                    return removed
                }
            }
        )
    }

    fun find(id: CapabilityId): CapabilityDescriptor? = capabilities[id]?.descriptor

    fun contains(id: CapabilityId): Boolean = capabilities.containsKey(id)

    fun snapshot(): Map<CapabilityId, CapabilityDescriptor> =
        capabilities.mapValues { (_, entry) -> entry.descriptor }

    private fun record(
        severity: DiagnosticSeverity,
        code: String,
        message: String,
        descriptor: CapabilityDescriptor,
        context: LogContext
    ) {
        observability.record(
            severity = severity,
            code = code,
            message = message,
            context = context,
            metadata = mapOf(
                "capabilityId" to descriptor.id.value,
                "providerId" to descriptor.providerId.value
            )
        )
    }
}
