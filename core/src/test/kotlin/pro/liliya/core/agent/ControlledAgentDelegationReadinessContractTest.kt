package pro.liliya.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import pro.liliya.core.autonomy.AutonomyGeneration
import pro.liliya.core.autonomy.AutonomyOwnership
import pro.liliya.core.autonomy.AutonomyProposalId

class ControlledAgentDelegationReadinessContractTest {
    private val forbiddenPowerTokens = setOf(
        "authority", "authorize", "permission", "capability", "grant", "executionrequest",
        "executor", "scheduler", "schedule", "spawn", "replicate", "selfspawn", "tool"
    )

    @Test
    fun delegated_data_and_receipt_api_contain_no_power_or_scheduler_semantics() {
        val types = listOf(
            AgentDelegationRecord::class.java,
            AgentDelegationSnapshot::class.java,
            AgentDelegatedWorkBinding::class.java,
            AgentDelegatedInitiativeReceipt::class.java,
            AgentDelegatedInitiativeResult::class.java,
            AgentDelegatedInitiativeAttemptResult::class.java,
            ControlledAgentDelegatedExecutionResult::class.java
        )

        types.forEach { type ->
            val names = type.methods.map { it.name.lowercase() }
            assertFalse(names.any { name ->
                forbiddenPowerTokens.any { token -> name.contains(token) }
            })
        }
    }

    @Test
    fun delegated_initiative_ownership_exposes_composite_receipt_and_remove_not_split_mutable_handles() {
        val methods = AgentDelegatedInitiativeOwnership::class.java.methods
            .filter { it.declaringClass == AgentDelegatedInitiativeOwnership::class.java }

        assertTrue(methods.any { it.name == "getReceipt" })
        assertTrue(methods.any { it.name == "remove" })
        assertFalse(methods.any { method ->
            method.returnType == AutonomyOwnership::class.java ||
                method.returnType == AgentDelegatedWorkBindingOwnership::class.java
        })
        assertFalse(methods.any { method ->
            forbiddenPowerTokens.any { token -> method.name.lowercase().contains(token) }
        })
    }

    @Test
    fun delegated_receipt_preserves_exact_generation_provenance_as_data_only() {
        val receipt = AgentDelegatedInitiativeReceipt(
            delegation = ExactAgentDelegationReference(
                AgentDelegationId("delegation-ready"),
                AgentDelegationGeneration(17)
            ),
            child = ExactAgentReference(
                AgentId("child-ready"),
                AgentGeneration(23)
            ),
            autonomy = ExactAutonomyReference(
                AutonomyProposalId("autonomy-ready"),
                AutonomyGeneration(29)
            )
        )

        assertEquals(AgentDelegationGeneration(17), receipt.delegation.generation)
        assertEquals(AgentGeneration(23), receipt.child.generation)
        assertEquals(AutonomyGeneration(29), receipt.autonomy.generation)
    }

    @Test
    fun controlled_delegated_execution_exposes_only_execution_entrypoint_and_no_permission_api() {
        val publicNames = ControlledAgentDelegatedExecution::class.java.methods
            .filter { it.declaringClass == ControlledAgentDelegatedExecution::class.java }
            .map { it.name.lowercase() }
            .toSet()

        assertEquals(setOf("execute"), publicNames)
        assertFalse(publicNames.any { name ->
            setOf("authorize", "grant", "permission", "capability", "schedule", "spawn").any {
                name.contains(it)
            }
        })
    }

    @Test
    fun delegated_foundation_and_control_types_expose_no_multi_agent_runtime_or_self_spawn_api() {
        val types = listOf(
            AgentDelegationComposition::class.java,
            AgentDelegatedWorkBindingComposition::class.java,
            ControlledAgentDelegationPreflight::class.java,
            ControlledAgentDelegatedInitiative::class.java,
            ControlledAgentDelegatedInitiativeGate::class.java,
            ControlledAgentDelegatedExecution::class.java
        )
        val forbidden = setOf(
            "spawn", "replicate", "scheduler", "schedule", "runloop", "background",
            "multiplayer", "multiagent", "broadcast", "fanout"
        )

        types.forEach { type ->
            val publicNames = type.methods
                .filter { it.declaringClass == type }
                .map { it.name.lowercase() }
            assertFalse(publicNames.any { name -> forbidden.any { token -> name.contains(token) } })
        }
    }
}
