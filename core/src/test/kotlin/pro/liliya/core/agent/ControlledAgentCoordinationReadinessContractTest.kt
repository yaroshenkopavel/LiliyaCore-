package pro.liliya.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControlledAgentCoordinationReadinessContractTest {
    @Test
    fun final_coordination_boundary_exposes_only_one_execution_entrypoint() {
        val publicDeclared = ControlledAgentCoordinationExecution::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .map { it.name }
            .toSet()

        assertEquals(setOf("execute"), publicDeclared)
    }

    @Test
    fun public_execution_guard_constructor_reuses_frozen_controlled_orchestration_boundary() {
        val publicConstructors = ControlledAgentCoordinationExecution::class.java.constructors
            .map { constructor -> constructor.parameterTypes.map { it.name } }

        assertTrue(publicConstructors.isNotEmpty())
        assertTrue(publicConstructors.any { parameters ->
            parameters.contains("pro.liliya.core.orchestration.ControlledOrchestrationExecution")
        })

        val forbiddenDirectPowerTypes = setOf(
            "pro.liliya.core.execution.ExecutionComposition",
            "pro.liliya.core.authority.AuthorityManager",
            "pro.liliya.core.authority.AuthorityComposition"
        )
        assertFalse(publicConstructors.flatten().any { it in forbiddenDirectPowerTypes })
    }

    @Test
    fun execution_request_carries_identity_and_action_but_no_capability_scope_or_grant() {
        val accessors = AgentCoordinationExecutionRequest::class.java.methods
            .filter { it.declaringClass == AgentCoordinationExecutionRequest::class.java }
            .map { it.name.lowercase() }

        assertTrue(accessors.any { it.contains("principal") })
        assertTrue(accessors.any { it.contains("actionid") })

        val forbidden = setOf(
            "capability", "scope", "grant", "permission", "authorized", "authorityrequest",
            "executor", "scheduler", "retry"
        )
        assertFalse(accessors.any { name -> forbidden.any { token -> name.contains(token) } })
    }

    @Test
    fun structural_coordination_api_remains_data_ownership_without_power_methods() {
        val types = listOf(
            AgentCoordinationRecord::class.java,
            AgentCoordinationSnapshot::class.java,
            AgentCoordinationOwnership::class.java,
            AgentCoordinationInstallResult::class.java
        )
        val forbidden = setOf(
            "authority", "authorize", "permission", "capability", "execution", "execute",
            "executor", "scheduler", "schedule", "grant", "tool"
        )

        types.forEach { type ->
            val methodNames = type.methods.map { it.name.lowercase() }
            assertFalse(methodNames.any { name -> forbidden.any { token -> name.contains(token) } })
        }
    }

    @Test
    fun coordination_execution_result_does_not_expose_authority_evidence_or_retry_control() {
        val types = listOf(
            AgentCoordinationExecutionResult::class.java,
            AgentCoordinationExecutionResult.Succeeded::class.java,
            AgentCoordinationExecutionResult.Rejected::class.java,
            AgentCoordinationExecutionResult.Failed::class.java
        )
        val forbidden = setOf(
            "authority", "grant", "capability", "scope", "permission", "retry", "schedule"
        )

        types.forEach { type ->
            val names = type.methods.map { it.name.lowercase() }
            assertFalse(names.any { name -> forbidden.any { token -> name.contains(token) } })
        }
    }
}
