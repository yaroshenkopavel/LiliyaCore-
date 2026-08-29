package pro.liliya.core.agent

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import pro.liliya.core.autonomy.AutonomyBudget
import pro.liliya.core.autonomy.AutonomyPriority
import pro.liliya.core.autonomy.AutonomyProposalId

class AgentCoordinationInitiativeRequestPrivacyContractTest {
    private fun participant(
        id: String,
        autonomy: String,
        secret: String
    ) = AgentCoordinationParticipantInitiativeRequest(
        participant = ExactAgentReference(AgentId(id), AgentGeneration(1)),
        autonomyProposalId = AutonomyProposalId(autonomy),
        objective = "objective-$secret",
        triggerDescription = "trigger-$secret",
        priority = AutonomyPriority.NORMAL,
        budget = AutonomyBudget(1),
        createdAt = Instant.parse("2026-08-30T00:30:00Z")
    )

    @Test
    fun request_defensively_copies_caller_participant_list() {
        val source = mutableListOf(
            participant("agent-a", "autonomy-a", "a"),
            participant("agent-b", "autonomy-b", "b")
        )
        val request = AgentCoordinationInitiativeRequest(
            coordinationId = AgentCoordinationId("coordination-1"),
            coordinationGeneration = AgentCoordinationGeneration(1),
            participants = source
        )

        source.clear()

        assertEquals(2, request.participants.size)
        assertEquals(setOf("agent-a", "agent-b"), request.participants.map { it.participant.id.value }.toSet())
    }

    @Test
    fun private_objective_and_trigger_are_redacted_from_rendering() {
        val secret = "never-render-private-coordination-input"
        val participant = participant("agent-a", "autonomy-a", secret)
        val request = AgentCoordinationInitiativeRequest(
            coordinationId = AgentCoordinationId("coordination-1"),
            coordinationGeneration = AgentCoordinationGeneration(1),
            participants = listOf(
                participant,
                participant("agent-b", "autonomy-b", "other-secret")
            )
        )

        assertFalse(participant.toString().contains(secret))
        assertFalse(request.toString().contains(secret))
        assertFalse(participant.toString().contains(participant.objective))
        assertFalse(participant.toString().contains(participant.triggerDescription))
    }
}
