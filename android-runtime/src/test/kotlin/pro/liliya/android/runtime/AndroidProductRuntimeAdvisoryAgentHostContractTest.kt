package pro.liliya.android.runtime

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import pro.liliya.core.asf.AgentAggregateUsage
import pro.liliya.core.asf.AgentBlueprintId
import pro.liliya.core.asf.AgentBlueprintReference
import pro.liliya.core.asf.AgentBlueprintVersion
import pro.liliya.core.asf.AgentCognitiveScope
import pro.liliya.core.asf.AgentCoordinatorPlan
import pro.liliya.core.asf.AgentCoordinatorResult
import pro.liliya.core.asf.AgentCoordinatorRunWindow
import pro.liliya.core.asf.AgentCoordinatorStep
import pro.liliya.core.asf.AgentCoordinatorStepId
import pro.liliya.core.asf.AgentCoordinatorTerminalState
import pro.liliya.core.asf.AgentRootTaskId
import pro.liliya.core.asf.AgentWorkBudget

class AndroidProductRuntimeAdvisoryAgentHostContractTest {
    @Test
    fun run_delegates_exact_plan_window_and_cancellation_without_mutation() {
        val plan = plan()
        val window = AgentCoordinatorRunWindow(
            admittedAt = Instant.parse("2026-10-07T12:00:00Z"),
            expiresAt = Instant.parse("2026-10-07T12:01:00Z")
        )
        val expected = AgentCoordinatorResult(
            state = AgentCoordinatorTerminalState.CANCELLED,
            artifacts = emptyList(),
            terminalInstances = emptyList(),
            aggregateUsage = AgentAggregateUsage(),
            completedSteps = 0
        )
        val cancelled = { true }

        var receivedPlan: AgentCoordinatorPlan? = null
        var receivedWindow: AgentCoordinatorRunWindow? = null
        var receivedCancelled: (() -> Boolean)? = null

        val host = AndroidProductRuntimeAdvisoryAgentHost(
            AndroidProductRuntimeAdvisoryAgentRunPort { actualPlan, actualWindow, actualCancelled ->
                receivedPlan = actualPlan
                receivedWindow = actualWindow
                receivedCancelled = actualCancelled
                expected
            }
        )

        assertSame(expected, host.run(plan, window, cancelled))
        assertSame(plan, receivedPlan)
        assertSame(window, receivedWindow)
        assertSame(cancelled, receivedCancelled)
    }

    @Test
    fun public_host_api_contains_no_authority_or_execution_surface() {
        val forbidden = listOf(
            "AuthorityPrincipal",
            "CapabilityAuthority",
            "Execution",
            "Orchestration",
            "License"
        )

        val signatures = AndroidProductRuntimeAdvisoryAgentHost::class.java.methods
            .filter { it.declaringClass == AndroidProductRuntimeAdvisoryAgentHost::class.java }
            .flatMap { method ->
                listOf(method.returnType.name) + method.parameterTypes.map { it.name }
            }

        forbidden.forEach { marker ->
            assertFalse(
                signatures.any { marker in it },
                "advisory ASF host must not expose $marker: $signatures"
            )
        }
    }

    private fun plan(): AgentCoordinatorPlan {
        val scope = AgentCognitiveScope.create(listOf("advisory"))
        val budget = AgentWorkBudget(
            maxWallClockMillis = 1_000,
            maxInferenceUnits = 100,
            maxContextBytes = 1_024,
            maxRetrievalItems = 1,
            maxArtifacts = 1,
            maxDescendants = 0
        )
        return AgentCoordinatorPlan(
            rootTaskId = AgentRootTaskId("product-advisory-test"),
            steps = listOf(
                AgentCoordinatorStep.create(
                    id = AgentCoordinatorStepId("root"),
                    parentStepId = null,
                    blueprint = AgentBlueprintReference(
                        AgentBlueprintId("product-advisory-blueprint"),
                        AgentBlueprintVersion(1)
                    ),
                    cognitiveScope = scope,
                    budget = budget,
                    inputReferences = listOf("evidence:test")
                )
            )
        )
    }
}
