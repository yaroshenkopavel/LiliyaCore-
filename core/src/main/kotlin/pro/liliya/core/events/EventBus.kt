package pro.liliya.core.events

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LoggerFactory
import pro.liliya.core.observability.CoreObservability
import pro.liliya.core.observability.LoggerProvider
import java.util.concurrent.atomic.AtomicLong

class EventBus(
    diagnostics: DiagnosticRecorder,
    private val clock: () -> Long = System::currentTimeMillis,
    private val observability: CoreObservability = CoreObservability(
        loggerProvider = LoggerProvider { context -> LoggerFactory.create(context) },
        diagnostics = diagnostics
    )
) {
    private data class ListenerEntry(
        val id: Long,
        val listener: EventListener
    )

    private val lock = Any()
    private val nextSubscriptionId = AtomicLong(0)
    private val listeners = mutableListOf<ListenerEntry>()

    fun subscribe(listener: EventListener): EventSubscription {
        val entry = ListenerEntry(
            id = nextSubscriptionId.incrementAndGet(),
            listener = listener
        )

        synchronized(lock) {
            listeners += entry
        }

        return EventSubscription {
            synchronized(lock) {
                listeners.removeAll { it.id == entry.id }
            }
        }
    }

    fun publish(event: CoreEvent): EventDeliveryReport {
        val envelope = EventEnvelope(
            sequence = GlobalEventSequence.next(),
            timestampMillis = clock(),
            event = event,
            metadata = (event.context.metadata + event.metadata).toMap()
        )
        val snapshot = synchronized(lock) { listeners.toList() }

        var delivered = 0
        var failed = 0

        snapshot.forEach { entry ->
            try {
                entry.listener.onEvent(envelope)
                delivered += 1
            } catch (throwable: Throwable) {
                failed += 1
                observability.record(
                    severity = DiagnosticSeverity.ERROR,
                    code = "EVENT_LISTENER_FAILED",
                    message = "Event listener failed during delivery",
                    context = event.context,
                    metadata = mapOf(
                        "eventType" to event.type,
                        "eventSequence" to envelope.sequence.toString(),
                        "subscriptionId" to entry.id.toString()
                    ),
                    throwable = throwable
                )
            }
        }

        observability.record(
            severity = if (failed == 0) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
            code = "EVENT_PUBLISHED",
            message = "Event publication completed",
            context = event.context,
            metadata = mapOf(
                "eventType" to event.type,
                "eventSequence" to envelope.sequence.toString(),
                "delivered" to delivered.toString(),
                "failed" to failed.toString()
            )
        )

        return EventDeliveryReport(
            delivered = delivered,
            failed = failed
        )
    }

    fun subscriberCount(): Int = synchronized(lock) { listeners.size }
}
