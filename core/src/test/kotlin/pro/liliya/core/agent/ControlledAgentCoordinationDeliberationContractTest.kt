package pro.liliya.core.agent

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import pro.liliya.core.autonomy.AutonomyBudget
import pro.liliya.core.autonomy.AutonomyComposition
import pro.liliya.core.autonomy.AutonomyDeliberationAttemptResult
import pro.liliya.core.autonomy.AutonomyDeliberationAttemptValidationResult
import pro.liliya.core.autonomy.AutonomyDeliberationComposition
import pro.liliya.core.autonomy.AutonomyDeliberationInstallResult
import pro.liliya.core.autonomy.AutonomyDeliberationOwnership
import pro.liliya.core.autonomy.AutonomyDeliberationRequest
import pro.liliya.core.autonomy.AutonomyDeliberationRequestId
import pro.liliya.core.autonomy.AutonomyGeneration
import pro.liliya.core.autonomy.AutonomyInstallResult
import pro.liliya.core.autonomy.AutonomyOrigin
import pro.liliya.core.autonomy.AutonomyPriority
import pro.liliya.core.autonomy.AutonomyProposal
import pro.liliya.core.autonomy.AutonomyProposalId
import pro.liliya.core.autonomy.AutonomySourceId
import pro.liliya.core.autonomy.ControlledAutonomyDeliberationGate
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class ControlledAgentCoordinationDeliberationContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val attemptBindings: AgentCoordinationAttemptBindingComposition,
        val autonomy: AutonomyComposition,
        val autonomyGate: ControlledAutonomyDeliberationGate,
        val deliberation: AutonomyDeliberationComposition
    )

    private data class Prepared(
        val coordination: ExactAgentCoordinationReference,
        val participants: List<ExactAgentReference>,
        val attempts: List<pro.liliya.core.autonomy.AutonomyAttemptReference>,
        val bindingGeneration: AgentCoordinationAttemptBindingGeneration
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "coord-delib-${sequence.incrementAndGet()}" }
        )
        val autonomy = AutonomyComposition(foundation)
        return Fixture(
            logs = logs,
            foundation = foundation,
            attemptBindings = AgentCoordinationAttemptBindingComposition(foundation),
            autonomy = autonomy,
            autonomyGate = ControlledAutonomyDeliberationGate(foundation, autonomy),
            deliberation = AutonomyDeliberationComposition(foundation)
        )
    }

    private fun participant(id: String, generation: Long = 1L) =
        ExactAgentReference(AgentId(id), AgentGeneration(generation))

    private fun installClaimedAttempt(
        f: Fixture,
        id: String
    ): pro.liliya.core.autonomy.AutonomyAttemptReference {
        val installed = assertIs<AutonomyInstallResult.Installed>(
            f.autonomy.install(
                AutonomyProposal(
                    id = AutonomyProposalId(id),
                    origin = AutonomyOrigin.Declared(AutonomySourceId("coordination-test")),
                    objective = "private autonomy objective $id",
                    triggerDescription = "private autonomy trigger $id",
                    priority = AutonomyPriority.NORMAL,
                    budget = AutonomyBudget(2),
                    createdAt = Instant.parse("2026-08-30T06:00:00Z")
                )
            )
        ).ownership
        val claimed = assertIs<AutonomyDeliberationAttemptResult.Claimed>(
            f.autonomyGate.claimAttempt(installed.proposal.id, installed.generation)
        )
        return pro.liliya.core.autonomy.AutonomyAttemptReference(
            proposalId = installed.proposal.id,
            proposalGeneration = installed.generation,
            attemptNumber = claimed.evidence.attemptNumber
        )
    }

    private fun prepare(f: Fixture): Prepared {
        val coordination = ExactAgentCoordinationReference(
            AgentCoordinationId("coord-delib"),
            AgentCoordinationGeneration(1)
        )
        val participants = listOf(participant("agent-a"), participant("agent-b"))
        val attempts = listOf(
            installClaimedAttempt(f, "autonomy-a"),
            installClaimedAttempt(f, "autonomy-b")
        )
        val installed = assertIs<AgentCoordinationAttemptBindingInstallResult.Installed>(
            f.attemptBindings.install(
                AgentCoordinationAttemptBinding(
                    coordination = coordination,
                    assignments = participants.zip(attempts).map { (agent, attempt) ->
                        AgentCoordinationAttemptAssignment(agent, attempt)
                    }
                )
            )
        ).ownership
        return Prepared(coordination, participants, attempts, installed.generation)
    }

    private fun request(
        p: Prepared,
        bindingGeneration: AgentCoordinationAttemptBindingGeneration = p.bindingGeneration
    ) = AgentCoordinationDeliberationRequest(
        coordination = p.coordination,
        attemptBindingGeneration = bindingGeneration,
        specs = p.participants.mapIndexed { index, participant ->
            AgentCoordinationDeliberationSpec(
                participant = participant,
                requestId = AutonomyDeliberationRequestId("coord-request-${index + 1}"),
                objective = "private coordinated deliberation objective ${index + 1}",
                createdAt = Instant.parse("2026-08-30T06:10:0${index}Z")
            )
        }
    )

    private fun readyChecker(
        p: Prepared,
        rejectOnCall: Int? = null
    ): AgentCoordinationPreflightChecker {
        val calls = AtomicInteger(0)
        return AgentCoordinationPreflightChecker {
            if (rejectOnCall != null && calls.incrementAndGet() == rejectOnCall) {
                AgentCoordinationPreflightResult.Rejected("simulated governance change")
            } else {
                AgentCoordinationPreflightResult.Ready(
                    AgentCoordinationReadyEvidence(
                        coordinationId = p.coordination.id,
                        coordinationGeneration = p.coordination.generation,
                        participants = p.participants
                    )
                )
            }
        }
    }

    private fun transaction(
        f: Fixture,
        p: Prepared,
        preflight: AgentCoordinationPreflightChecker = readyChecker(p),
        installer: AgentCoordinationDeliberationInstaller =
            AgentCoordinationDeliberationInstaller(f.deliberation::install)
    ) = ControlledAgentCoordinationDeliberation(
        foundation = f.foundation,
        attemptBindings = f.attemptBindings,
        deliberation = f.deliberation,
        preflight = preflight,
        validator = AgentCoordinationAttemptValidator(f.autonomyGate::validateAttempt),
        installer = installer,
        testOnly = Unit
    )

    @Test
    fun exact_stable_binding_creates_one_deliberation_per_bound_attempt() {
        val f = fixture()
        val p = prepare(f)

        val created = assertIs<AgentCoordinationDeliberationResult.Created>(
            transaction(f, p).create(request(p))
        ).receipt

        assertEquals(2, created.deliberations.size)
        assertEquals(p.bindingGeneration, created.attemptBindingGeneration)
        created.deliberations.forEachIndexed { index, entry ->
            assertEquals(p.participants[index], entry.participant)
            assertEquals(p.attempts[index], entry.attempt)
            val live = assertNotNull(f.deliberation.inspect(entry.requestId))
            assertEquals(entry.requestGeneration, live.generation)
            assertEquals(entry.attempt, live.request.autonomy)
        }
    }

    @Test
    fun stale_attempt_binding_generation_creates_zero_deliberation_writes() {
        val f = fixture()
        val p = prepare(f)

        assertIs<AgentCoordinationDeliberationResult.Rejected>(
            transaction(f, p).create(
                request(p, AgentCoordinationAttemptBindingGeneration(p.bindingGeneration.value + 1))
            )
        )
        assertEquals(0, f.deliberation.snapshot().size)
    }

    @Test
    fun invalid_attempt_before_transaction_creates_zero_deliberation_writes() {
        val f = fixture()
        val p = prepare(f)
        f.autonomyGate.cancel(p.attempts.first().proposalId, p.attempts.first().proposalGeneration)

        assertIs<AgentCoordinationDeliberationResult.Rejected>(
            transaction(f, p).create(request(p))
        )
        assertEquals(0, f.deliberation.snapshot().size)
    }

    @Test
    fun governance_change_after_first_write_rolls_back_all_created_requests() {
        val f = fixture()
        val p = prepare(f)

        assertIs<AgentCoordinationDeliberationResult.Rejected>(
            transaction(f, p, preflight = readyChecker(p, rejectOnCall = 3)).create(request(p))
        )
        assertEquals(0, f.deliberation.snapshot().size)
    }

    @Test
    fun later_install_rejection_rolls_back_earlier_created_request_without_touching_preexisting_one() {
        val f = fixture()
        val p = prepare(f)
        val secondId = request(p).specs[1].requestId
        val preexisting = assertIs<AutonomyDeliberationInstallResult.Installed>(
            f.deliberation.install(
                AutonomyDeliberationRequest(
                    id = secondId,
                    autonomy = p.attempts[1],
                    objective = "preexisting private objective",
                    createdAt = Instant.parse("2026-08-30T06:09:00Z")
                )
            )
        ).ownership

        assertIs<AgentCoordinationDeliberationResult.Rejected>(
            transaction(f, p).create(request(p))
        )

        assertNull(f.deliberation.inspect(request(p).specs[0].requestId))
        assertEquals(preexisting.generation, f.deliberation.inspect(secondId)?.generation)
        assertEquals(1, f.deliberation.snapshot().size)
    }

    @Test
    fun compensation_failure_is_explicit_failed_and_critical_observable() {
        val f = fixture()
        val p = prepare(f)
        val specs = request(p).specs
        var calls = 0
        val installer = AgentCoordinationDeliberationInstaller { candidate ->
            calls += 1
            if (calls == 1) {
                val real = assertIs<AutonomyDeliberationInstallResult.Installed>(
                    f.deliberation.install(candidate)
                ).ownership
                AutonomyDeliberationInstallResult.Installed(
                    object : AutonomyDeliberationOwnership {
                        override val request: AutonomyDeliberationRequest = real.request
                        override val generation = real.generation
                        override fun remove(): Boolean = false
                    }
                )
            } else {
                AutonomyDeliberationInstallResult.Rejected("simulated second install rejection")
            }
        }

        assertIs<AgentCoordinationDeliberationResult.Failed>(
            transaction(f, p, installer = installer).create(
                AgentCoordinationDeliberationRequest(
                    p.coordination,
                    p.bindingGeneration,
                    specs
                )
            )
        )
        assertNotNull(f.deliberation.inspect(specs[0].requestId))
        assertEquals(
            1,
            f.logs.snapshot().count {
                it.marker == "AGENT_COORDINATION_DELIBERATION_COMPENSATION_FAILED"
            }
        )
    }

    @Test
    fun private_deliberation_objectives_do_not_enter_observability() {
        val f = fixture()
        val p = prepare(f)
        val secret = "never-log-coordination-deliberation-objective"
        val original = request(p)
        val secretRequest = AgentCoordinationDeliberationRequest(
            coordination = p.coordination,
            attemptBindingGeneration = p.bindingGeneration,
            specs = original.specs.map { spec ->
                AgentCoordinationDeliberationSpec(
                    participant = spec.participant,
                    requestId = spec.requestId,
                    objective = secret,
                    createdAt = spec.createdAt
                )
            }
        )

        assertIs<AgentCoordinationDeliberationResult.Created>(
            transaction(f, p).create(secretRequest)
        )
        assertFalse(f.logs.snapshot().any { event ->
            event.message == secret || event.metadata.values.any { it == secret }
        })
    }
}
