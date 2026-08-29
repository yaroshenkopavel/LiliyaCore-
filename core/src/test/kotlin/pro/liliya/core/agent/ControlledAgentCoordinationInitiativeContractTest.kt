package pro.liliya.core.agent

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.autonomy.AutonomyBudget
import pro.liliya.core.autonomy.AutonomyGeneration
import pro.liliya.core.autonomy.AutonomyOrigin
import pro.liliya.core.autonomy.AutonomyOwnership
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

class ControlledAgentCoordinationInitiativeContractTest {
    private data class FakeAutonomy(
        val ownership: AutonomyOwnership,
        val removeCalls: AtomicInteger
    )

    private fun foundation(): Pair<FoundationComposition, InMemoryLogWriter> {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "coordination-initiative-${sequence.incrementAndGet()}" }
        ) to logs
    }

    private val a = ExactAgentReference(AgentId("agent-a"), AgentGeneration(3))
    private val b = ExactAgentReference(AgentId("agent-b"), AgentGeneration(5))

    private fun ready(
        participants: List<ExactAgentReference> = listOf(a, b),
        generation: Long = 7
    ) = AgentCoordinationReadyEvidence(
        coordinationId = AgentCoordinationId("coordination-1"),
        coordinationGeneration = AgentCoordinationGeneration(generation),
        participants = participants
    )

    private fun participantRequest(
        participant: ExactAgentReference,
        suffix: String
    ) = AgentCoordinationParticipantInitiativeRequest(
        participant = participant,
        autonomyProposalId = AutonomyProposalId("autonomy-$suffix"),
        objective = "private objective $suffix",
        triggerDescription = "private trigger $suffix",
        priority = AutonomyPriority.NORMAL,
        budget = AutonomyBudget(2),
        createdAt = Instant.parse("2026-08-30T00:20:00Z")
    )

    private fun request(
        participants: List<AgentCoordinationParticipantInitiativeRequest> = listOf(
            participantRequest(a, "a"),
            participantRequest(b, "b")
        )
    ) = AgentCoordinationInitiativeRequest(
        coordinationId = AgentCoordinationId("coordination-1"),
        coordinationGeneration = AgentCoordinationGeneration(7),
        participants = participants
    )

    private fun fakeAutonomy(
        request: AgentInitiativeRequest,
        generation: Long,
        removeResult: Boolean = true
    ): FakeAutonomy {
        val calls = AtomicInteger(0)
        val proposal = AutonomyProposal(
            id = request.autonomyProposalId,
            origin = AutonomyOrigin.Declared(AutonomySourceId("test")),
            objective = request.objective,
            triggerDescription = request.triggerDescription,
            priority = request.priority,
            budget = request.budget,
            createdAt = request.createdAt
        )
        return FakeAutonomy(
            ownership = object : AutonomyOwnership {
                override val proposal: AutonomyProposal = proposal
                override val generation: AutonomyGeneration = AutonomyGeneration(generation)
                override fun remove(): Boolean {
                    calls.incrementAndGet()
                    return removeResult
                }
            },
            removeCalls = calls
        )
    }

    private fun installedBinding(
        binding: AgentCoordinationWorkBinding,
        removeResult: Boolean = true,
        removeCalls: AtomicInteger = AtomicInteger(0)
    ) = AgentCoordinationWorkBindingInstallResult.Installed(
        object : AgentCoordinationWorkBindingOwnership {
            override val binding: AgentCoordinationWorkBinding = binding
            override val generation = AgentCoordinationWorkBindingGeneration(17)
            override fun remove(): Boolean {
                removeCalls.incrementAndGet()
                return removeResult
            }
        }
    )

    @Test
    fun exact_participant_set_creates_all_then_revalidates_and_binds_one_composite_receipt() {
        val (foundation, _) = foundation()
        val preflightCalls = AtomicInteger(0)
        val initiativeCalls = AtomicInteger(0)
        val created = mutableListOf<FakeAutonomy>()
        val installedBindings = mutableListOf<AgentCoordinationWorkBinding>()

        val bridge = ControlledAgentCoordinationInitiative(
            foundation = foundation,
            preflight = AgentCoordinationPreflightChecker {
                preflightCalls.incrementAndGet()
                AgentCoordinationPreflightResult.Ready(ready())
            },
            initiative = AgentCoordinationInitiativeCreator { childRequest ->
                val fake = fakeAutonomy(childRequest, initiativeCalls.incrementAndGet().toLong())
                created += fake
                AgentInitiativeResult.Created(fake.ownership)
            },
            bindings = AgentCoordinationWorkBindingInstaller { binding ->
                installedBindings += binding
                installedBinding(binding)
            },
            testOnly = Unit
        )

        val result = assertIs<AgentCoordinationInitiativeResult.Created>(bridge.create(request()))

        assertEquals(2, preflightCalls.get())
        assertEquals(2, initiativeCalls.get())
        assertEquals(1, installedBindings.size)
        assertEquals(setOf(a, b), result.ownership.receipt.assignments.map { it.participant }.toSet())
        assertEquals(
            setOf("autonomy-a", "autonomy-b"),
            result.ownership.receipt.assignments.map { it.autonomy.proposalId.value }.toSet()
        )
        assertTrue(created.all { it.removeCalls.get() == 0 })
    }

    @Test
    fun missing_or_extra_participant_is_rejected_before_any_autonomy_write() {
        val (foundation, _) = foundation()
        val initiativeCalls = AtomicInteger(0)
        val bridge = ControlledAgentCoordinationInitiative(
            foundation,
            AgentCoordinationPreflightChecker {
                AgentCoordinationPreflightResult.Ready(ready())
            },
            AgentCoordinationInitiativeCreator {
                initiativeCalls.incrementAndGet()
                error("must not create work")
            },
            AgentCoordinationWorkBindingInstaller { error("must not bind work") },
            Unit
        )

        val wrong = request(
            listOf(
                participantRequest(a, "a"),
                participantRequest(ExactAgentReference(AgentId("agent-c"), AgentGeneration(9)), "c")
            )
        )
        assertIs<AgentCoordinationInitiativeResult.Rejected>(bridge.create(wrong))
        assertEquals(0, initiativeCalls.get())
    }

    @Test
    fun later_participant_rejection_rolls_back_all_prior_exact_autonomy() {
        val (foundation, _) = foundation()
        val firstCreated = mutableListOf<FakeAutonomy>()
        val calls = AtomicInteger(0)
        val bridge = ControlledAgentCoordinationInitiative(
            foundation,
            AgentCoordinationPreflightChecker {
                AgentCoordinationPreflightResult.Ready(ready())
            },
            AgentCoordinationInitiativeCreator { childRequest ->
                if (calls.incrementAndGet() == 2) {
                    AgentInitiativeResult.Rejected("second participant rejected")
                } else {
                    val fake = fakeAutonomy(childRequest, 1)
                    firstCreated += fake
                    AgentInitiativeResult.Created(fake.ownership)
                }
            },
            AgentCoordinationWorkBindingInstaller { error("must not bind work") },
            Unit
        )

        assertIs<AgentCoordinationInitiativeResult.Rejected>(bridge.create(request()))
        assertEquals(1, firstCreated.single().removeCalls.get())
    }

    @Test
    fun post_write_coordination_change_rolls_back_every_created_autonomy() {
        val (foundation, _) = foundation()
        val preflightCalls = AtomicInteger(0)
        val created = mutableListOf<FakeAutonomy>()
        val bridge = ControlledAgentCoordinationInitiative(
            foundation,
            AgentCoordinationPreflightChecker {
                if (preflightCalls.incrementAndGet() == 1) {
                    AgentCoordinationPreflightResult.Ready(ready())
                } else {
                    AgentCoordinationPreflightResult.Rejected("participant lifecycle changed")
                }
            },
            AgentCoordinationInitiativeCreator { childRequest ->
                val fake = fakeAutonomy(childRequest, created.size.toLong() + 1)
                created += fake
                AgentInitiativeResult.Created(fake.ownership)
            },
            AgentCoordinationWorkBindingInstaller { error("must not bind work") },
            Unit
        )

        assertIs<AgentCoordinationInitiativeResult.Rejected>(bridge.create(request()))
        assertEquals(2, created.size)
        assertTrue(created.all { it.removeCalls.get() == 1 })
    }

    @Test
    fun binding_rejection_rolls_back_all_created_autonomy() {
        val (foundation, _) = foundation()
        val created = mutableListOf<FakeAutonomy>()
        val bridge = ControlledAgentCoordinationInitiative(
            foundation,
            AgentCoordinationPreflightChecker {
                AgentCoordinationPreflightResult.Ready(ready())
            },
            AgentCoordinationInitiativeCreator { childRequest ->
                val fake = fakeAutonomy(childRequest, created.size.toLong() + 1)
                created += fake
                AgentInitiativeResult.Created(fake.ownership)
            },
            AgentCoordinationWorkBindingInstaller {
                AgentCoordinationWorkBindingInstallResult.Rejected("binding conflict")
            },
            Unit
        )

        assertIs<AgentCoordinationInitiativeResult.Rejected>(bridge.create(request()))
        assertTrue(created.all { it.removeCalls.get() == 1 })
    }

    @Test
    fun compensation_failure_is_explicit_failed_and_critical_observable() {
        val (foundation, logs) = foundation()
        val created = mutableListOf<FakeAutonomy>()
        val bridge = ControlledAgentCoordinationInitiative(
            foundation,
            AgentCoordinationPreflightChecker {
                AgentCoordinationPreflightResult.Ready(ready())
            },
            AgentCoordinationInitiativeCreator { childRequest ->
                val fake = fakeAutonomy(
                    childRequest,
                    created.size.toLong() + 1,
                    removeResult = created.isNotEmpty()
                )
                created += fake
                AgentInitiativeResult.Created(fake.ownership)
            },
            AgentCoordinationWorkBindingInstaller {
                AgentCoordinationWorkBindingInstallResult.Rejected("binding conflict")
            },
            Unit
        )

        assertIs<AgentCoordinationInitiativeResult.Failed>(bridge.create(request()))
        assertTrue(logs.snapshot().any { it.marker == "AGENT_COORDINATION_INITIATIVE_COMPENSATION_FAILED" })
    }

    @Test
    fun composite_remove_cleans_all_autonomy_before_binding_and_is_one_shot() {
        val (foundation, _) = foundation()
        val created = mutableListOf<FakeAutonomy>()
        val bindingRemoveCalls = AtomicInteger(0)
        val bridge = ControlledAgentCoordinationInitiative(
            foundation,
            AgentCoordinationPreflightChecker {
                AgentCoordinationPreflightResult.Ready(ready())
            },
            AgentCoordinationInitiativeCreator { childRequest ->
                val fake = fakeAutonomy(childRequest, created.size.toLong() + 1)
                created += fake
                AgentInitiativeResult.Created(fake.ownership)
            },
            AgentCoordinationWorkBindingInstaller { binding ->
                installedBinding(binding, removeCalls = bindingRemoveCalls)
            },
            Unit
        )

        val ownership = assertIs<AgentCoordinationInitiativeResult.Created>(bridge.create(request())).ownership
        assertTrue(ownership.remove())
        assertFalse(ownership.remove())
        assertTrue(created.all { it.removeCalls.get() == 1 })
        assertEquals(1, bindingRemoveCalls.get())
    }
}
