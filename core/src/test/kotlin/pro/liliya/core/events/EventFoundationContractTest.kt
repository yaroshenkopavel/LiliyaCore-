package pro.liliya.core.events

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventFoundationContractTest {

    private data class TestEvent(
        override val type: String,
        override val context: LogContext,
        override val metadata: Map<String, String> = emptyMap()
    ) : CoreEvent

    private fun context(operation: String) = LogContextPropagation.root(
        module = "CORE",
        component = "Events",
        operation = operation,
        metadata = mapOf("scope" to "event-foundation"),
        generator = CorrelationIdGenerator { "event-$operation" }
    )

    private fun fixture(): Pair<EventBus, InMemoryDiagnosticSink> {
        val sink = InMemoryDiagnosticSink()
        return EventBus(DiagnosticRecorder(sink), clock = { 1234L }) to sink
    }

    @BeforeTest
    fun resetSequence() {
        GlobalEventSequence.resetForTest()
    }

    @Test
    fun listeners_receive_events_in_subscription_order() {
        val (bus, _) = fixture()
        val deliveries = mutableListOf<String>()
        val event = TestEvent("ORDERED", context("ordered"))

        bus.subscribe { deliveries += "first:${it.sequence}" }
        bus.subscribe { deliveries += "second:${it.sequence}" }

        val report = bus.publish(event)

        assertEquals(listOf("first:1", "second:1"), deliveries)
        assertEquals(2, report.delivered)
        assertEquals(0, report.failed)
        assertEquals(2, report.attempted)
    }

    @Test
    fun listener_failure_is_isolated_and_observable() {
        val (bus, diagnostics) = fixture()
        var secondDelivered = false
        val event = TestEvent("FAILURE", context("failure"))

        bus.subscribe { error("listener failed") }
        bus.subscribe { secondDelivered = true }

        val report = bus.publish(event)

        assertTrue(secondDelivered)
        assertEquals(1, report.delivered)
        assertEquals(1, report.failed)
        assertEquals(
            listOf("EVENT_LISTENER_FAILED", "EVENT_PUBLISHED"),
            diagnostics.snapshot().map { it.code }
        )
        assertEquals("event-failure", diagnostics.snapshot().first().context.correlationId)
    }

    @Test
    fun cancellation_has_single_owner_and_is_idempotent() {
        val (bus, _) = fixture()
        var deliveries = 0
        val subscription = bus.subscribe { deliveries += 1 }

        assertEquals(1, bus.subscriberCount())
        assertTrue(subscription.cancel())
        assertFalse(subscription.cancel())
        assertEquals(0, bus.subscriberCount())

        bus.publish(TestEvent("CANCELLED", context("cancelled")))
        assertEquals(0, deliveries)
    }

    @Test
    fun publication_uses_listener_snapshot() {
        val (bus, _) = fixture()
        val deliveries = mutableListOf<String>()
        var secondSubscription: EventSubscription? = null

        bus.subscribe {
            deliveries += "first"
            secondSubscription = bus.subscribe { deliveries += "late" }
        }
        bus.subscribe { deliveries += "second" }

        bus.publish(TestEvent("SNAPSHOT_ONE", context("snapshot-one")))
        assertEquals(listOf("first", "second"), deliveries)

        deliveries.clear()
        bus.publish(TestEvent("SNAPSHOT_TWO", context("snapshot-two")))
        assertEquals(listOf("first", "second", "late"), deliveries)

        secondSubscription?.cancel()
    }

    @Test
    fun event_sequence_is_global_across_event_buses() {
        val (firstBus, _) = fixture()
        val (secondBus, _) = fixture()
        val sequences = mutableListOf<Long>()

        firstBus.subscribe { sequences += it.sequence }
        secondBus.subscribe { sequences += it.sequence }

        firstBus.publish(TestEvent("FIRST", context("first")))
        secondBus.publish(TestEvent("SECOND", context("second")))

        assertEquals(listOf(1L, 2L), sequences)
    }

    @Test
    fun envelope_metadata_is_snapshotted_at_publication() {
        val (bus, _) = fixture()
        val mutableMetadata = linkedMapOf("event" to "before")
        var captured: EventEnvelope? = null
        val event = TestEvent(
            type = "METADATA",
            context = context("metadata"),
            metadata = mutableMetadata
        )

        bus.subscribe { captured = it }
        bus.publish(event)
        mutableMetadata["event"] = "after"

        assertEquals("before", captured?.metadata?.get("event"))
        assertEquals("event-foundation", captured?.metadata?.get("scope"))
        assertEquals(1234L, captured?.timestampMillis)
    }
}
