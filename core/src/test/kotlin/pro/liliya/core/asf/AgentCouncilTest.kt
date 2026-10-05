package pro.liliya.core.asf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentCouncilTest {
    private val root = AgentRootTaskId("root-council")
    private val p1 = AgentCouncilParticipant(
        AgentCouncilParticipantId("p1"),
        "model:a",
        "runtime:local",
        "explorer"
    )
    private val p2 = AgentCouncilParticipant(
        AgentCouncilParticipantId("p2"),
        "model:b",
        "runtime:local",
        "critic"
    )
    private val p3 = AgentCouncilParticipant(
        AgentCouncilParticipantId("p3"),
        "model:c",
        "runtime:local",
        "verifier"
    )

    @Test
    fun participant_cap_is_enforced() {
        assertFailsWith<IllegalArgumentException> {
            AgentCouncilRequest(
                rootTaskId = root,
                objective = "bounded council",
                participants = listOf(p1, p2),
                inputReferences = listOf("input:a"),
                budget = AgentCouncilBudget(
                    maxParticipants = 1,
                    maxWallClockMillis = 1000,
                    maxInferenceUnits = 100,
                    maxContextBytes = 1000,
                    maxArtifacts = 4
                )
            )
        }
    }

    @Test
    fun minority_challenge_preserves_unresolved_conflict() {
        val request = request(listOf(p1, p2, p3))
        val result = AgentCouncilSynthesis.synthesize(
            request,
            listOf(
                finding(p1, AgentCouncilFindingDisposition.SUPPORTS),
                finding(p2, AgentCouncilFindingDisposition.SUPPORTS),
                finding(p3, AgentCouncilFindingDisposition.CHALLENGES)
            )
        )

        assertEquals(
            AgentCouncilClaimState.UNRESOLVED_CONFLICT,
            result.claims.single().state
        )
        assertEquals(3, result.participantCount)
    }

    @Test
    fun unknown_participant_fails_closed() {
        val request = request(listOf(p1))
        val outsider = AgentCouncilParticipant(
            AgentCouncilParticipantId("outsider"),
            "model:x",
            "runtime:x",
            "outsider"
        )

        assertFailsWith<IllegalArgumentException> {
            AgentCouncilSynthesis.synthesize(
                request,
                listOf(finding(outsider, AgentCouncilFindingDisposition.SUPPORTS))
            )
        }
    }

    @Test
    fun inconclusive_is_not_promoted_to_supported() {
        val result = AgentCouncilSynthesis.synthesize(
            request(listOf(p1)),
            listOf(finding(p1, AgentCouncilFindingDisposition.INCONCLUSIVE))
        )

        assertEquals(
            AgentCouncilClaimState.INCONCLUSIVE,
            result.claims.single().state
        )
    }

    @Test
    fun council_contract_has_no_recursive_council_authority_execution_or_secret_fields() {
        val forbidden = listOf(
            "authority", "permission", "credential", "secret",
            "token", "license", "executiongrant", "principal",
            "childcouncil", "parentcouncil", "nestedcouncil"
        )
        listOf(
            AgentCouncilParticipant::class.java,
            AgentCouncilBudget::class.java,
            AgentCouncilRequest::class.java,
            AgentCouncilFinding::class.java,
            AgentCouncilResult::class.java
        ).forEach { type ->
            val fields = type.declaredFields.map { it.name.lowercase() }
            forbidden.forEach { word ->
                assertTrue(fields.none { word in it })
            }
        }
    }

    @Test
    fun council_result_has_no_score_winner_truth_or_confidence_field() {
        val forbidden = listOf("score", "winner", "truth", "confidence", "vote")
        val fields = AgentCouncilClaimResult::class.java
            .declaredFields
            .map { it.name.lowercase() }
        forbidden.forEach { word ->
            assertTrue(fields.none { word in it })
        }
    }

    private fun request(
        participants: List<AgentCouncilParticipant>
    ) = AgentCouncilRequest(
        rootTaskId = root,
        objective = "evaluate claim",
        participants = participants,
        inputReferences = listOf("input:a"),
        budget = AgentCouncilBudget(
            maxParticipants = 4,
            maxWallClockMillis = 5000,
            maxInferenceUnits = 1000,
            maxContextBytes = 8000,
            maxArtifacts = 8
        )
    )

    private fun finding(
        participant: AgentCouncilParticipant,
        disposition: AgentCouncilFindingDisposition
    ) = AgentCouncilFinding(
        participantId = participant.id,
        claimKey = "claim:a",
        disposition = disposition,
        evidenceReferences = listOf("evidence:${participant.id.value}")
    )
}
