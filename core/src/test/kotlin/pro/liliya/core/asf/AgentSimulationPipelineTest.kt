package pro.liliya.core.asf

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentSimulationPipelineTest {
    private val now = Instant.parse("2026-09-29T16:40:00Z")
    private val root = AgentRootTaskId("root-pipeline")

    @Test
    fun plausible_simulation_completes_ready_for_governance() {
        val proposal = proposal("ok")
        val pipeline = pipeline(
            AgentSimulationAdapter { request ->
                AgentSimulationResult.create(
                    request,
                    AgentSimulationState.PLAUSIBLE,
                    listOf("evidence:simulation")
                )
            }
        )

        val result = pipeline.run(proposal, listOf("context:a"))

        assertEquals(AgentSimulationRunState.COMPLETED, result.state)
        assertEquals(AgentProposalReadiness.READY_FOR_GOVERNANCE, result.readiness)
        assertEquals(
            AgentSimulationRecoveryDisposition.NEW_SIMULATION_REQUIRED,
            result.recoveryDisposition
        )
    }

    @Test
    fun cancellation_before_adapter_prevents_simulation() {
        var adapterCalls = 0
        val pipeline = pipeline(
            AgentSimulationAdapter {
                adapterCalls++
                error("adapter must not run")
            }
        )

        val result = pipeline.run(
            proposal("cancel-before"),
            listOf("context:a"),
            cancelled = { true }
        )

        assertEquals(AgentSimulationRunState.CANCELLED, result.state)
        assertEquals(0, adapterCalls)
        assertEquals(null, result.simulation)
    }

    @Test
    fun cancellation_after_adapter_discards_simulation_result() {
        var cancellationChecks = 0
        var adapterCalls = 0
        val pipeline = pipeline(
            AgentSimulationAdapter { request ->
                adapterCalls++
                AgentSimulationResult.create(
                    request,
                    AgentSimulationState.PLAUSIBLE,
                    listOf("evidence:simulation")
                )
            }
        )

        val result = pipeline.run(
            proposal("cancel-after"),
            listOf("context:a"),
            cancelled = {
                cancellationChecks++
                cancellationChecks >= 3
            }
        )

        assertEquals(1, adapterCalls)
        assertEquals(AgentSimulationRunState.CANCELLED, result.state)
        assertEquals(null, result.simulation)
        assertEquals(null, result.readiness)
    }

    @Test
    fun context_budget_exhaustion_prevents_adapter() {
        var adapterCalls = 0
        val pipeline = AgentSimulationPipeline(
            adapter = AgentSimulationAdapter {
                adapterCalls++
                error("adapter must not run")
            },
            budget = AgentSimulationBudget(
                maxContextReferences = 1,
                maxEvidenceReferences = 4,
                maxRiskItems = 2
            )
        )

        val result = pipeline.run(
            proposal("context-budget"),
            listOf("context:a", "context:b")
        )

        assertEquals(AgentSimulationRunState.BUDGET_EXHAUSTED, result.state)
        assertEquals(0, adapterCalls)
    }

    @Test
    fun evidence_budget_exhaustion_discards_result() {
        val pipeline = AgentSimulationPipeline(
            adapter = AgentSimulationAdapter { request ->
                AgentSimulationResult.create(
                    request,
                    AgentSimulationState.PLAUSIBLE,
                    listOf("evidence:a", "evidence:b")
                )
            },
            budget = AgentSimulationBudget(
                maxContextReferences = 4,
                maxEvidenceReferences = 1,
                maxRiskItems = 2
            )
        )

        val result = pipeline.run(
            proposal("evidence-budget"),
            listOf("context:a")
        )

        assertEquals(AgentSimulationRunState.BUDGET_EXHAUSTED, result.state)
        assertEquals(null, result.simulation)
    }

    @Test
    fun risk_budget_exhaustion_discards_result() {
        val pipeline = AgentSimulationPipeline(
            adapter = AgentSimulationAdapter { request ->
                AgentSimulationResult.create(
                    request,
                    AgentSimulationState.UNRESOLVED,
                    listOf("evidence:a"),
                    listOf("risk:a")
                )
            },
            budget = AgentSimulationBudget(
                maxContextReferences = 4,
                maxEvidenceReferences = 4,
                maxRiskItems = 0
            )
        )

        val result = pipeline.run(
            proposal("risk-budget"),
            listOf("context:a")
        )

        assertEquals(AgentSimulationRunState.BUDGET_EXHAUSTED, result.state)
        assertEquals(null, result.simulation)
    }

    @Test
    fun adapter_failure_is_fail_closed() {
        val pipeline = pipeline(
            AgentSimulationAdapter {
                throw IllegalStateException("simulator unavailable")
            }
        )

        val result = pipeline.run(
            proposal("adapter-failure"),
            listOf("context:a")
        )

        assertEquals(AgentSimulationRunState.FAILED, result.state)
        assertEquals(null, result.simulation)
        assertEquals(null, result.readiness)
    }

    @Test
    fun mismatched_adapter_result_is_fail_closed() {
        val other = proposal("other")
        val pipeline = pipeline(
            AgentSimulationAdapter {
                val wrongRequest = AgentSimulationRequest.create(
                    other,
                    listOf("context:a")
                )
                AgentSimulationResult.create(
                    wrongRequest,
                    AgentSimulationState.PLAUSIBLE,
                    listOf("evidence:a")
                )
            }
        )

        val result = pipeline.run(
            proposal("expected"),
            listOf("context:a")
        )

        assertEquals(AgentSimulationRunState.FAILED, result.state)
        assertEquals(null, result.simulation)
    }

    @Test
    fun completed_simulation_recovery_never_replays_prior_result() {
        var adapterCalls = 0
        val pipeline = pipeline(
            AgentSimulationAdapter { request ->
                adapterCalls++
                AgentSimulationResult.create(
                    request,
                    AgentSimulationState.PLAUSIBLE,
                    listOf("evidence:simulation-$adapterCalls")
                )
            }
        )
        val proposal = proposal("recovery-no-replay")

        val first = pipeline.run(
            proposal,
            listOf("context:a")
        )
        val second = pipeline.run(
            proposal,
            listOf("context:a")
        )

        assertEquals(2, adapterCalls)
        assertEquals(
            AgentSimulationRecoveryDisposition.NEW_SIMULATION_REQUIRED,
            first.recoveryDisposition
        )
        assertEquals(
            AgentSimulationRecoveryDisposition.NEW_SIMULATION_REQUIRED,
            second.recoveryDisposition
        )
        assertTrue(first.simulation != second.simulation)
    }

    @Test
    fun pipeline_contract_contains_no_authority_execution_or_secret_fields() {
        val forbidden = listOf(
            "authority", "permission", "credential", "secret",
            "token", "license", "executiongrant", "principal"
        )
        listOf(
            AgentSimulationBudget::class.java,
            AgentSimulationRun::class.java
        ).forEach { type ->
            val fields = type.declaredFields.map { it.name.lowercase() }
            forbidden.forEach { word ->
                assertTrue(fields.none { word in it })
            }
        }
    }

    private fun pipeline(
        adapter: AgentSimulationAdapter
    ) = AgentSimulationPipeline(
        adapter = adapter,
        budget = AgentSimulationBudget(
            maxContextReferences = 4,
            maxEvidenceReferences = 4,
            maxRiskItems = 2
        )
    )

    private fun proposal(seed: String): AgentToolProposal {
        val source = AgentArtifact.create(
            AgentInstanceId("producer-$seed"),
            AgentInstanceGeneration(1),
            root,
            "proposal-source",
            "sha256:$seed",
            listOf("evidence:$seed"),
            now
        )
        return AgentToolProposal.create(
            source,
            "tool:opaque",
            "prepare-change",
            "target:$seed",
            AgentEffectClass.UNKNOWN,
            listOf("precondition:a"),
            listOf("effect:a")
        )
    }
}
