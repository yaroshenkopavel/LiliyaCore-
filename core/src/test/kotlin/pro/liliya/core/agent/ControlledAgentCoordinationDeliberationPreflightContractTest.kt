package pro.liliya.core.agent

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import pro.liliya.core.autonomy.AutonomyAttemptReference
import pro.liliya.core.autonomy.AutonomyBudget
import pro.liliya.core.autonomy.AutonomyDeliberationAttemptEvidence
import pro.liliya.core.autonomy.AutonomyDeliberationGeneration
import pro.liliya.core.autonomy.AutonomyDeliberationPreflightResult
import pro.liliya.core.autonomy.AutonomyDeliberationReadyEvidence
import pro.liliya.core.autonomy.AutonomyDeliberationRequest
import pro.liliya.core.autonomy.AutonomyDeliberationRequestId
import pro.liliya.core.autonomy.AutonomyGeneration
import pro.liliya.core.autonomy.AutonomyOrigin
import pro.liliya.core.autonomy.AutonomyPriority
import pro.liliya.core.autonomy.AutonomyProposal
import pro.liliya.core.autonomy.AutonomyProposalId
import pro.liliya.core.autonomy.AutonomySourceId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class ControlledAgentCoordinationDeliberationPreflightContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val bindings: AgentCoordinationAttemptBindingComposition
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "coord-delib-preflight-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, foundation, AgentCoordinationAttemptBindingComposition(foundation))
    }

    private fun participant(id: String) = ExactAgentReference(AgentId(id), AgentGeneration(1))

    private fun autonomyProposal(id: String) = AutonomyProposal(
        id = AutonomyProposalId(id),
        origin = AutonomyOrigin.Declared(AutonomySourceId("coordination-preflight-test")),
        objective = "private autonomy objective $id",
        triggerDescription = "private autonomy trigger $id",
        priority = AutonomyPriority.NORMAL,
        budget = AutonomyBudget(2),
        createdAt = Instant.parse("2026-08-30T06:30:00Z")
    )

    private fun attempt(id: String, generation: Long = 1L, number: Int = 1) =
        AutonomyAttemptReference(AutonomyProposalId(id), AutonomyGeneration(generation), number)

    private fun binding(
        coordination: ExactAgentCoordinationReference,
        a: ExactAgentReference,
        b: ExactAgentReference,
        attemptA: AutonomyAttemptReference,
        attemptB: AutonomyAttemptReference
    ) = AgentCoordinationAttemptBinding(
        coordination = coordination,
        assignments = listOf(
            AgentCoordinationAttemptAssignment(a, attemptA),
            AgentCoordinationAttemptAssignment(b, attemptB)
        )
    )

    private fun deliberationChecker(
        attempt: AutonomyAttemptReference,
        requestId: AutonomyDeliberationRequestId,
        requestGeneration: AutonomyDeliberationGeneration,
        secretObjective: String = "private coordinated objective"
    ) = AgentAutonomyDeliberationPreflightChecker { id, generation ->
        if (id != requestId || generation != requestGeneration) {
            AutonomyDeliberationPreflightResult.Rejected("unexpected exact deliberation reference")
        } else {
            val proposal = autonomyProposal(attempt.proposalId.value)
            AutonomyDeliberationPreflightResult.Ready(
                AutonomyDeliberationReadyEvidence(
                    request = AutonomyDeliberationRequest(
                        id = requestId,
                        autonomy = attempt,
                        objective = secretObjective,
                        createdAt = Instant.parse("2026-08-30T06:31:00Z")
                    ),
                    requestGeneration = requestGeneration,
                    attempt = AutonomyDeliberationAttemptEvidence(
                        proposal = proposal,
                        generation = attempt.proposalGeneration,
                        attemptNumber = attempt.attemptNumber
                    )
                )
            )
        }
    }

    private fun readyCoordination(
        coordination: ExactAgentCoordinationReference,
        participants: List<ExactAgentReference>,
        beforeReady: (() -> Unit)? = null
    ) = AgentCoordinationPreflightChecker {
        beforeReady?.invoke()
        AgentCoordinationPreflightResult.Ready(
            AgentCoordinationReadyEvidence(
                coordinationId = coordination.id,
                coordinationGeneration = coordination.generation,
                participants = participants
            )
        )
    }

    @Test
    fun exact_live_deliberation_resolves_committed_binding_and_returns_structural_evidence() {
        val f = fixture()
        val coordination = ExactAgentCoordinationReference(
            AgentCoordinationId("coord-ready"), AgentCoordinationGeneration(1)
        )
        val a = participant("agent-a")
        val b = participant("agent-b")
        val attemptA = attempt("autonomy-a")
        val attemptB = attempt("autonomy-b")
        val installed = assertIs<AgentCoordinationAttemptBindingInstallResult.Installed>(
            f.bindings.install(binding(coordination, a, b, attemptA, attemptB))
        ).ownership
        val requestId = AutonomyDeliberationRequestId("delib-a")
        val requestGeneration = AutonomyDeliberationGeneration(3)

        val result = assertIs<AgentCoordinationDeliberationPreflightResult.Ready>(
            ControlledAgentCoordinationDeliberationPreflight(
                foundation = f.foundation,
                attemptBindings = f.bindings,
                coordinationPreflight = readyCoordination(coordination, listOf(a, b)),
                deliberationPreflight = deliberationChecker(attemptA, requestId, requestGeneration),
                testOnly = Unit
            ).check(requestId, requestGeneration)
        ).evidence

        assertEquals(coordination, result.coordination)
        assertEquals(installed.generation, result.attemptBindingGeneration)
        assertEquals(a, result.participant)
        assertEquals(attemptA, result.attempt)
        assertEquals(requestId, result.requestId)
        assertEquals(requestGeneration, result.requestGeneration)
    }

    @Test
    fun exact_attempt_without_coordination_binding_rejects() {
        val f = fixture()
        val requestId = AutonomyDeliberationRequestId("unbound-delib")
        val requestGeneration = AutonomyDeliberationGeneration(1)
        val exactAttempt = attempt("unbound-autonomy")
        val unusedCoordination = ExactAgentCoordinationReference(
            AgentCoordinationId("unused"), AgentCoordinationGeneration(1)
        )

        assertIs<AgentCoordinationDeliberationPreflightResult.Rejected>(
            ControlledAgentCoordinationDeliberationPreflight(
                foundation = f.foundation,
                attemptBindings = f.bindings,
                coordinationPreflight = readyCoordination(unusedCoordination, emptyList()),
                deliberationPreflight = deliberationChecker(exactAttempt, requestId, requestGeneration),
                testOnly = Unit
            ).check(requestId, requestGeneration)
        )
    }

    @Test
    fun coordination_preflight_rejection_fails_closed() {
        val f = fixture()
        val coordination = ExactAgentCoordinationReference(
            AgentCoordinationId("coord-reject"), AgentCoordinationGeneration(1)
        )
        val a = participant("agent-a")
        val b = participant("agent-b")
        val attemptA = attempt("autonomy-a")
        val attemptB = attempt("autonomy-b")
        f.bindings.install(binding(coordination, a, b, attemptA, attemptB))
        val requestId = AutonomyDeliberationRequestId("delib-reject")
        val requestGeneration = AutonomyDeliberationGeneration(1)

        assertIs<AgentCoordinationDeliberationPreflightResult.Rejected>(
            ControlledAgentCoordinationDeliberationPreflight(
                foundation = f.foundation,
                attemptBindings = f.bindings,
                coordinationPreflight = AgentCoordinationPreflightChecker {
                    AgentCoordinationPreflightResult.Rejected("participant lifecycle not ACTIVE")
                },
                deliberationPreflight = deliberationChecker(attemptA, requestId, requestGeneration),
                testOnly = Unit
            ).check(requestId, requestGeneration)
        )
    }

    @Test
    fun attempt_binding_removed_during_coordination_check_rejects_before_evidence_commit() {
        val f = fixture()
        val coordination = ExactAgentCoordinationReference(
            AgentCoordinationId("coord-race"), AgentCoordinationGeneration(1)
        )
        val a = participant("agent-a")
        val b = participant("agent-b")
        val attemptA = attempt("autonomy-a")
        val attemptB = attempt("autonomy-b")
        val ownership = assertIs<AgentCoordinationAttemptBindingInstallResult.Installed>(
            f.bindings.install(binding(coordination, a, b, attemptA, attemptB))
        ).ownership
        val requestId = AutonomyDeliberationRequestId("delib-race")
        val requestGeneration = AutonomyDeliberationGeneration(1)
        var removed = false

        assertIs<AgentCoordinationDeliberationPreflightResult.Rejected>(
            ControlledAgentCoordinationDeliberationPreflight(
                foundation = f.foundation,
                attemptBindings = f.bindings,
                coordinationPreflight = readyCoordination(coordination, listOf(a, b)) {
                    if (!removed) {
                        removed = ownership.remove()
                    }
                },
                deliberationPreflight = deliberationChecker(attemptA, requestId, requestGeneration),
                testOnly = Unit
            ).check(requestId, requestGeneration)
        )
        assertEquals(true, removed)
    }

    @Test
    fun private_deliberation_objective_is_absent_from_evidence_and_observability() {
        val f = fixture()
        val coordination = ExactAgentCoordinationReference(
            AgentCoordinationId("coord-private"), AgentCoordinationGeneration(1)
        )
        val a = participant("agent-a")
        val b = participant("agent-b")
        val attemptA = attempt("autonomy-a")
        val attemptB = attempt("autonomy-b")
        f.bindings.install(binding(coordination, a, b, attemptA, attemptB))
        val requestId = AutonomyDeliberationRequestId("delib-private")
        val requestGeneration = AutonomyDeliberationGeneration(1)
        val secret = "never-expose-coordinated-deliberation-objective"

        val evidence = assertIs<AgentCoordinationDeliberationPreflightResult.Ready>(
            ControlledAgentCoordinationDeliberationPreflight(
                foundation = f.foundation,
                attemptBindings = f.bindings,
                coordinationPreflight = readyCoordination(coordination, listOf(a, b)),
                deliberationPreflight = deliberationChecker(
                    attemptA,
                    requestId,
                    requestGeneration,
                    secretObjective = secret
                ),
                testOnly = Unit
            ).check(requestId, requestGeneration)
        ).evidence

        assertFalse(evidence.toString().contains(secret))
        assertFalse(f.logs.snapshot().any { event ->
            event.message == secret || event.metadata.values.any { it == secret }
        })
    }

    @Test
    fun readiness_evidence_exposes_no_permission_authority_execution_or_planning_methods() {
        val forbidden = setOf(
            "permission", "grant", "authority", "authorize", "execution", "execute",
            "executor", "planning", "reasoning", "decision", "scheduler", "schedule"
        )
        val names = AgentCoordinationDeliberationReadyEvidence::class.java.methods
            .map { it.name.lowercase() }

        assertFalse(names.any { name -> forbidden.any { token -> name.contains(token) } })
    }
}
