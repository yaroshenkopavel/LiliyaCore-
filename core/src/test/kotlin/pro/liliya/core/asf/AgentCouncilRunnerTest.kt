package pro.liliya.core.asf

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentCouncilRunnerTest {
    private val root = AgentRootTaskId("root-runner")
    private val start = Instant.parse("2026-09-29T19:00:00Z")
    private val end = start.plusSeconds(60)

    private val p1 = participant("p1")
    private val p2 = participant("p2")

    @Test
    fun sequential_council_completes_and_preserves_disagreement() {
        val runner = AgentCouncilRunner(
            adapter = AgentCouncilParticipantAdapter { context ->
                val disposition = if (context.participant.id == p1.id) {
                    AgentCouncilFindingDisposition.SUPPORTS
                } else {
                    AgentCouncilFindingDisposition.CHALLENGES
                }
                AgentCouncilParticipantOutcome.Completed(
                    findings = listOf(
                        AgentCouncilFinding(
                            context.participant.id,
                            "claim:a",
                            disposition,
                            listOf("evidence:${context.participant.id.value}")
                        )
                    ),
                    usage = AgentRuntimeUsage(10, 10, 100, 0, 1)
                )
            },
            timeSource = { start.plusSeconds(1) }
        )

        val result = runner.runSequential(
            request(listOf(p1, p2)),
            AgentCouncilRunWindow(start, end)
        )

        assertEquals(AgentCouncilTerminalState.COMPLETED, result.state)
        assertEquals(2, result.completedParticipants)
        assertEquals(2, result.usage.participantsStarted)
        assertEquals(
            AgentCouncilClaimState.UNRESOLVED_CONFLICT,
            result.synthesis!!.claims.single().state
        )
    }

    @Test
    fun cancellation_before_first_participant_starts_nothing() {
        var calls = 0
        val runner = AgentCouncilRunner(
            adapter = AgentCouncilParticipantAdapter {
                calls++
                completed(it.participant)
            },
            timeSource = { start }
        )

        val result = runner.runSequential(
            request(listOf(p1, p2)),
            AgentCouncilRunWindow(start, end),
            isCancelled = { true }
        )

        assertEquals(AgentCouncilTerminalState.CANCELLED, result.state)
        assertEquals(0, calls)
        assertEquals(0, result.usage.participantsStarted)
        assertNull(result.synthesis)
    }

    @Test
    fun expiry_before_first_participant_fails_closed() {
        var calls = 0
        val runner = AgentCouncilRunner(
            adapter = AgentCouncilParticipantAdapter {
                calls++
                completed(it.participant)
            },
            timeSource = { end }
        )

        val result = runner.runSequential(
            request(listOf(p1)),
            AgentCouncilRunWindow(start, end)
        )

        assertEquals(AgentCouncilTerminalState.EXPIRED, result.state)
        assertEquals(0, calls)
        assertNull(result.synthesis)
    }

    @Test
    fun aggregate_budget_overrun_stops_council() {
        val tiny = request(listOf(p1, p2)).copy(
            budget = AgentCouncilBudget(
                maxParticipants = 2,
                maxWallClockMillis = 15,
                maxInferenceUnits = 15,
                maxContextBytes = 150,
                maxArtifacts = 2
            )
        )
        val runner = AgentCouncilRunner(
            adapter = AgentCouncilParticipantAdapter {
                completed(
                    it.participant,
                    AgentRuntimeUsage(10, 10, 100, 0, 1)
                )
            },
            timeSource = { start.plusSeconds(1) }
        )

        val result = runner.runSequential(
            tiny,
            AgentCouncilRunWindow(start, end)
        )

        assertEquals(AgentCouncilTerminalState.BUDGET_EXHAUSTED, result.state)
        assertEquals(1, result.completedParticipants)
        assertEquals(2, result.usage.participantsStarted)
        assertTrue(result.synthesis != null)
    }

    @Test
    fun participant_failure_after_success_is_partial() {
        var calls = 0
        val runner = AgentCouncilRunner(
            adapter = AgentCouncilParticipantAdapter { context ->
                calls++
                if (calls == 1) completed(context.participant)
                else AgentCouncilParticipantOutcome.Failed(
                    "model unavailable",
                    AgentRuntimeUsage(1, 1, 10, 0, 0)
                )
            },
            timeSource = { start.plusSeconds(1) }
        )

        val result = runner.runSequential(
            request(listOf(p1, p2)),
            AgentCouncilRunWindow(start, end)
        )

        assertEquals(AgentCouncilTerminalState.PARTIAL, result.state)
        assertEquals(1, result.completedParticipants)
        assertEquals(2, calls)
    }

    @Test
    fun participant_cannot_forge_another_participants_finding() {
        val runner = AgentCouncilRunner(
            adapter = AgentCouncilParticipantAdapter {
                AgentCouncilParticipantOutcome.Completed(
                    findings = listOf(
                        AgentCouncilFinding(
                            p2.id,
                            "claim:a",
                            AgentCouncilFindingDisposition.SUPPORTS,
                            listOf("evidence:forged")
                        )
                    ),
                    usage = AgentRuntimeUsage(1, 1, 10, 0, 1)
                )
            },
            timeSource = { start.plusSeconds(1) }
        )

        var failed = false
        try {
            runner.runSequential(
                request(listOf(p1)),
                AgentCouncilRunWindow(start, end)
            )
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun runner_contract_has_no_recursive_council_authority_execution_or_secret_fields() {
        val forbidden = listOf(
            "authority", "permission", "credential", "secret",
            "token", "license", "executiongrant", "principal",
            "childcouncil", "parentcouncil", "nestedcouncil"
        )
        listOf(
            AgentCouncilParticipantContext::class.java,
            AgentCouncilRunWindow::class.java,
            AgentCouncilUsage::class.java,
            AgentCouncilRunResult::class.java
        ).forEach { type ->
            val fields = type.declaredFields.map { it.name.lowercase() }
            forbidden.forEach { word ->
                assertTrue(fields.none { word in it })
            }
        }
    }

    private fun participant(id: String) = AgentCouncilParticipant(
        AgentCouncilParticipantId(id),
        "model:$id",
        "runtime:local",
        "role:$id"
    )

    private fun request(
        participants: List<AgentCouncilParticipant>
    ) = AgentCouncilRequest(
        rootTaskId = root,
        objective = "evaluate",
        participants = participants,
        inputReferences = listOf("input:a"),
        budget = AgentCouncilBudget(
            maxParticipants = 4,
            maxWallClockMillis = 1000,
            maxInferenceUnits = 1000,
            maxContextBytes = 8000,
            maxArtifacts = 8
        )
    )

    private fun completed(
        participant: AgentCouncilParticipant,
        usage: AgentRuntimeUsage = AgentRuntimeUsage(1, 1, 10, 0, 1)
    ) = AgentCouncilParticipantOutcome.Completed(
        findings = listOf(
            AgentCouncilFinding(
                participant.id,
                "claim:a",
                AgentCouncilFindingDisposition.SUPPORTS,
                listOf("evidence:${participant.id.value}")
            )
        ),
        usage = usage
    )
}
