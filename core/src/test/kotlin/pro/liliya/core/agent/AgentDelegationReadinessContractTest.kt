package pro.liliya.core.agent

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class AgentDelegationReadinessContractTest {
    private fun composition(): AgentDelegationComposition {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "agent-delegation-ready-${sequence.incrementAndGet()}" }
        )
        return AgentDelegationComposition(foundation)
    }

    private fun delegation() = AgentDelegationRecord(
        id = AgentDelegationId("delegation-ready"),
        parent = ExactAgentReference(AgentId("parent-ready"), AgentGeneration(11)),
        child = ExactAgentReference(AgentId("child-ready"), AgentGeneration(13)),
        purpose = "private delegation readiness purpose",
        createdAt = Instant.parse("2026-08-29T20:15:00Z")
    )

    @Test
    fun delegation_data_api_contains_no_capability_authority_execution_scheduler_or_tool_semantics() {
        val forbidden = setOf(
            "authority", "authorize", "permission", "capability", "execution", "execute",
            "executor", "scheduler", "schedule", "spawn", "replicate", "tool", "grant", "initiative"
        )
        val types = listOf(
            ExactAgentReference::class.java,
            AgentDelegationRecord::class.java,
            AgentDelegationSnapshot::class.java,
            AgentDelegationOwnership::class.java,
            AgentDelegationInstallResult::class.java
        )

        types.forEach { type ->
            val methodNames = type.methods.map { it.name.lowercase() }
            assertFalse(methodNames.any { name -> forbidden.any { token -> name.contains(token) } })
        }
    }

    @Test
    fun exact_parent_and_child_generations_survive_as_structural_data_only() {
        val composition = composition()
        val value = delegation()
        val ownership = assertIs<AgentDelegationInstallResult.Installed>(
            composition.install(value)
        ).ownership

        assertEquals(value.parent, ownership.delegation.parent)
        assertEquals(value.child, ownership.delegation.child)
        assertEquals(AgentGeneration(11), ownership.delegation.parent.generation)
        assertEquals(AgentGeneration(13), ownership.delegation.child.generation)
    }

    @Test
    fun structural_composition_has_no_hidden_agent_or_lifecycle_dependency() {
        val constructorParameterTypes = AgentDelegationComposition::class.java.constructors
            .flatMap { it.parameterTypes.toList() }
        val fieldTypes = AgentDelegationComposition::class.java.declaredFields
            .map { it.type }

        assertFalse(constructorParameterTypes.contains(AgentComposition::class.java))
        assertFalse(constructorParameterTypes.contains(ControlledAgentLifecycle::class.java))
        assertFalse(fieldTypes.contains(AgentComposition::class.java))
        assertFalse(fieldTypes.contains(ControlledAgentLifecycle::class.java))
    }

    @Test
    fun delegation_ownership_exposes_exact_record_generation_and_remove_without_power_methods() {
        val publicNames = AgentDelegationOwnership::class.java.methods
            .filter { it.declaringClass == AgentDelegationOwnership::class.java }
            .map { it.name }
            .toSet()

        assertTrue(publicNames.contains("getDelegation"))
        assertTrue(publicNames.any { it.startsWith("getGeneration") })
        assertTrue(publicNames.contains("remove"))

        val forbidden = setOf(
            "authority", "authorize", "permission", "capability", "execution", "execute",
            "executor", "scheduler", "schedule", "spawn", "replicate", "tool", "grant", "initiative"
        )
        assertFalse(publicNames.any { name ->
            forbidden.any { token -> name.lowercase().contains(token) }
        })
    }
}
