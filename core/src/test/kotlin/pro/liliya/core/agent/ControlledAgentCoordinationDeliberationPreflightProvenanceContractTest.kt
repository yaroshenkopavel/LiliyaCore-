package pro.liliya.core.agent

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
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

class ControlledAgentCoordinationDeliberationPreflightProvenanceContractTest {
    @Test
    fun frozen_attempt_evidence_mismatch_with_live_request_attempt_rejects_before_binding_resolution() {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "coord-delib-provenance-${sequence.incrementAndGet()}" }
        )
        val bindings = AgentCoordinationAttemptBindingComposition(foundation)
        val requestId = AutonomyDeliberationRequestId("delib-provenance")
        val requestGeneration = AutonomyDeliberationGeneration(1)
        val liveAttempt = AutonomyAttemptReference(
            proposalId = AutonomyProposalId("live-autonomy"),
            proposalGeneration = AutonomyGeneration(1),
            attemptNumber = 1
        )
        val mismatchedProposal = AutonomyProposal(
            id = AutonomyProposalId("different-autonomy"),
            origin = AutonomyOrigin.Declared(AutonomySourceId("test")),
            objective = "private objective",
            triggerDescription = "private trigger",
            priority = AutonomyPriority.NORMAL,
            budget = AutonomyBudget(1),
            createdAt = Instant.parse("2026-08-30T06:40:00Z")
        )

        val result = ControlledAgentCoordinationDeliberationPreflight(
            foundation = foundation,
            attemptBindings = bindings,
            coordinationPreflight = AgentCoordinationPreflightChecker {
                AgentCoordinationPreflightResult.Rejected("must not reach coordination preflight")
            },
            deliberationPreflight = AgentAutonomyDeliberationPreflightChecker {
                    id, generation ->
                AutonomyDeliberationPreflightResult.Ready(
                    AutonomyDeliberationReadyEvidence(
                        request = AutonomyDeliberationRequest(
                            id = id,
                            autonomy = liveAttempt,
                            objective = "private deliberation objective",
                            createdAt = Instant.parse("2026-08-30T06:41:00Z")
                        ),
                        requestGeneration = generation,
                        attempt = AutonomyDeliberationAttemptEvidence(
                            proposal = mismatchedProposal,
                            generation = AutonomyGeneration(1),
                            attemptNumber = 1
                        )
                    )
                )
            },
            testOnly = Unit
        ).check(requestId, requestGeneration)

        assertIs<AgentCoordinationDeliberationPreflightResult.Rejected>(result)
    }
}
