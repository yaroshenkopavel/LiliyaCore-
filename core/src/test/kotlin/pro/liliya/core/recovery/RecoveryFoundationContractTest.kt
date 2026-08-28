package pro.liliya.core.recovery

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RecoveryFoundationContractTest {

    private fun context(operation: String) = LogContextPropagation.root(
        module = "CORE",
        component = "Recovery",
        operation = operation,
        generator = CorrelationIdGenerator { "recovery-$operation" }
    )

    private fun request(
        target: String = "model-engine",
        attempt: Int = 1,
        reason: String = "runtime failure",
        operation: String = "recover"
    ) = RecoveryRequest(
        target = target,
        reason = reason,
        attempt = attempt,
        context = context(operation)
    )

    private fun fixture(maxAttempts: Int = 3): Triple<RecoveryCoordinator, InMemoryDiagnosticSink, RecoveryPolicy> {
        val sink = InMemoryDiagnosticSink()
        val recorder = DiagnosticRecorder(sink)
        val policy = RecoveryPolicy(maxAttempts = maxAttempts)
        val coordinator = RecoveryCoordinator(
            policy = policy,
            diagnostics = recorder
        )
        return Triple(coordinator, sink, policy)
    }

    @Test
    fun policy_selects_retry_restart_and_fail_at_boundaries() {
        val policy = RecoveryPolicy(maxAttempts = 3)

        assertEquals(RecoveryAction.RETRY, policy.select(1))
        assertEquals(RecoveryAction.RETRY, policy.select(2))
        assertEquals(RecoveryAction.RESTART, policy.select(3))
        assertEquals(RecoveryAction.FAIL, policy.select(4))
    }

    @Test
    fun request_and_policy_reject_invalid_attempts() {
        assertFailsWith<IllegalArgumentException> {
            request(attempt = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            RecoveryPolicy(maxAttempts = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            RecoveryPolicy().select(0)
        }
    }

    @Test
    fun request_requires_non_blank_target_and_reason() {
        assertFailsWith<IllegalArgumentException> {
            request(target = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            request(reason = " ")
        }
    }

    @Test
    fun selected_recovery_owns_target_and_records_correlated_diagnostic() {
        val (coordinator, diagnostics, _) = fixture()
        val request = request(operation = "selected")

        val result = coordinator.decide(request)

        val selected = assertIs<RecoveryDecision.Selected>(result)
        assertEquals(RecoveryAction.RETRY, selected.action)
        assertTrue(coordinator.isActive(request.target))

        val event = diagnostics.snapshot().single()
        assertEquals("RECOVERY_DECISION_SELECTED", event.code)
        assertEquals(request.context.correlationId, event.context.correlationId)
        assertEquals("model-engine", event.metadata["target"])
        assertEquals("RETRY", event.metadata["action"])
    }

    @Test
    fun duplicate_recovery_is_rejected_until_owner_completes() {
        val (coordinator, diagnostics, _) = fixture()
        val first = request(operation = "first")
        val duplicate = request(attempt = 2, operation = "duplicate")

        assertIs<RecoveryDecision.Selected>(coordinator.decide(first))
        val rejected = assertIs<RecoveryDecision.Rejected>(coordinator.decide(duplicate))

        assertEquals("recovery already active for target", rejected.reason)
        assertTrue(coordinator.isActive(first.target))
        assertEquals(
            listOf("RECOVERY_DECISION_SELECTED", "RECOVERY_DUPLICATE_REJECTED"),
            diagnostics.snapshot().map { it.code }
        )
    }

    @Test
    fun completion_releases_target_and_allows_next_recovery_cycle() {
        val (coordinator, diagnostics, _) = fixture()
        val first = request(operation = "first")
        val second = request(attempt = 2, operation = "second")

        assertIs<RecoveryDecision.Selected>(coordinator.decide(first))
        assertTrue(coordinator.complete(first))
        assertFalse(coordinator.isActive(first.target))

        val next = assertIs<RecoveryDecision.Selected>(coordinator.decide(second))
        assertEquals(RecoveryAction.RETRY, next.action)
        assertTrue(coordinator.isActive(second.target))
        assertEquals(
            listOf(
                "RECOVERY_DECISION_SELECTED",
                "RECOVERY_COMPLETED",
                "RECOVERY_DECISION_SELECTED"
            ),
            diagnostics.snapshot().map { it.code }
        )
    }

    @Test
    fun completion_without_active_owner_is_observable() {
        val (coordinator, diagnostics, _) = fixture()
        val request = request(operation = "orphan-complete")

        assertFalse(coordinator.complete(request))
        assertEquals("RECOVERY_COMPLETION_IGNORED", diagnostics.snapshot().single().code)
    }
}
